package com.cloudstream.shared

import android.content.Context
import com.cloudstream.shared.domain.DomainManager
import com.cloudstream.shared.http.*
import com.cloudstream.shared.parsing.BaseParser
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.WebViewEngine
import com.cloudstream.shared.webview.WebViewResult
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * THE GATEWAY - Single entry point for all provider HTTP operations.
 * 
 * Handles:
 * - Request queuing (leader-follower pattern)
 * - CF detection and bypass
 * - Cookie management
 * - Domain management
 * - Parsing via injected parser
 */
class ProviderHttpService private constructor(
    private val config: ProviderConfig,
    private val cookieStore: CookieStore,
    private val webViewEngine: WebViewEngine,
    private val domainManager: DomainManager,
    private val parser: BaseParser
) {
    private val TAG = "ProviderHttpService"
    
    private val requestQueue = RequestQueue(
        executeRequest = { url -> executeDirectRequest(url) },
        solveCfAndRequest = { url -> solveCloudflareThenRequest(url) }
    )
    
    val currentDomain: String
        get() = domainManager.currentDomain
    
    // ==================== PUBLIC API: High-level ====================
    
    /**
     * Get main page content, parsed into intermediate items.
     */
    suspend fun getMainPage(path: String): List<BaseParser.ParsedSearchItem> {
        domainManager.ensureInitialized()
        
        val url = domainManager.buildUrl(path)
        val doc = getDocument(url, checkDomainChange = true)
        
        return doc?.let { parser.parseMainPage(it) } ?: emptyList()
    }
    
    /**
     * Search for content, parsed into intermediate items.
     */
    suspend fun search(query: String, searchPath: String = "/search/"): List<BaseParser.ParsedSearchItem> {
        domainManager.ensureInitialized()
        
        val url = domainManager.buildUrl("$searchPath$query")
        val doc = getDocument(url, checkDomainChange = true)
        
        return doc?.let { parser.parseSearch(it) } ?: emptyList()
    }
    
    /**
     * Load content detail page.
     */
    suspend fun load(url: String): LoadResponse? {
        domainManager.ensureInitialized()
        
        val doc = getDocument(url, checkDomainChange = true)
        return doc?.let { parser.parseLoadPage(it, url) }
    }
    
    /**
     * Get episodes for a series (intermediate items).
     */
    suspend fun getEpisodes(url: String, seasonNum: Int?): List<BaseParser.ParsedEpisode> {
        val doc = getDocument(url, checkDomainChange = false)
        return doc?.let { parser.parseEpisodes(it, seasonNum) } ?: emptyList()
    }
    
    /**
     * Extract player URLs from a page.
     */
    suspend fun getPlayerUrls(url: String): List<String> {
        val doc = getDocument(url, checkDomainChange = false)
        return doc?.let { parser.extractPlayerUrls(it) } ?: emptyList()
    }
    
    /**
     * Sniff videos from a player URL.
     * Uses WebView to detect video sources.
     */
    suspend fun sniffVideos(url: String): List<VideoSource> {
        Log.d(TAG, "Sniffing videos from: $url")
        
        val result = webViewEngine.runSession(
            url = url,
            mode = WebViewEngine.Mode.HEADLESS,
            userAgent = config.userAgent,
            exitCondition = ExitCondition.PageLoaded,
            timeout = 30_000L
        )
        
        return when (result) {
            is WebViewResult.Success -> {
                // Extract video sources from HTML using JWPlayer patterns
                extractVideoSources(result.html)
            }
            is WebViewResult.Timeout -> {
                // Check if timeout due to CF
                if (CloudflareDetector.isCloudflareChallenge(result.partialHtml)) {
                    Log.i(TAG, "CF detected during video sniff, trying FULLSCREEN")
                    
                    // Clear cookies and retry fullscreen
                    val domain = extractDomain(url)
                    cookieStore.clear(domain, "CF during video sniff")
                    
                    val retryResult = webViewEngine.runSession(
                        url = url,
                        mode = WebViewEngine.Mode.FULLSCREEN,
                        userAgent = config.userAgent,
                        exitCondition = ExitCondition.PageLoaded,
                        timeout = 120_000L
                    )
                    
                    if (retryResult is WebViewResult.Success) {
                        extractVideoSources(retryResult.html)
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }
    
    /**
     * Extract video sources from HTML using common patterns.
     */
    private fun extractVideoSources(html: String): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()
        
        // JWPlayer pattern: file: "url"
        val jwplayerRegex = """file:\s*["']([^"']+)["']""".toRegex()
        jwplayerRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            if (url.contains(".m3u8") || url.contains(".mp4")) {
                sources.add(VideoSource(url, extractLabel(url), emptyMap()))
            }
        }
        
        // sources: [...] pattern
        val sourcesArrayRegex = """sources:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        sourcesArrayRegex.findAll(html).forEach { match ->
            val sourcesJson = match.groupValues[1]
            val fileRegex = """["']?file["']?\s*:\s*["']([^"']+)["']""".toRegex()
            val labelRegex = """["']?label["']?\s*:\s*["']([^"']+)["']""".toRegex()
            
            fileRegex.findAll(sourcesJson).forEach { fileMatch ->
                val url = fileMatch.groupValues[1]
                val label = labelRegex.find(sourcesJson)?.groupValues?.get(1) ?: extractLabel(url)
                
                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    sources.add(VideoSource(url, label, emptyMap()))
                }
            }
        }
        
        Log.d(TAG, "Extracted ${sources.size} video sources")
        return sources.distinctBy { it.url }
    }
    
    private fun extractLabel(url: String): String {
        return when {
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            else -> "Auto"
        }
    }
    
    // ==================== PUBLIC API: Low-level ====================
    
    /**
     * Get raw HTML content (queued).
     */
    suspend fun get(url: String): String? {
        val result = requestQueue.enqueue(url)
        return result.html
    }
    
    /**
     * Get parsed Document (queued).
     */
    suspend fun getDocument(url: String, checkDomainChange: Boolean = false): Document? {
        val result = requestQueue.enqueue(url)
        
        if (result.success && checkDomainChange) {
            domainManager.checkDomainChange(url, result.finalUrl)
        }
        
        return result.html?.let { Jsoup.parse(it) }
    }
    
    /**
     * Get headers for loading images.
     */
    fun getImageHeaders(): Map<String, String> {
        val cookies = cookieStore.buildHeader(currentDomain)
        return buildMap {
            put("User-Agent", config.userAgent)
            put("Referer", "https://$currentDomain/")
            cookies?.let { put("Cookie", it) }
        }
    }
    
    /**
     * Force session invalidation.
     */
    fun invalidateSession(reason: String) {
        cookieStore.clear(currentDomain, reason)
        Log.i(TAG, "Session invalidated: $reason")
    }
    
    // ==================== INTERNAL: Request execution ====================
    
    /**
     * Execute a direct HTTP request (no queuing).
     */
    internal suspend fun executeDirectRequest(url: String): RequestResult {
        return try {
            val cookies = cookieStore.retrieve(extractDomain(url))
            val headers = buildHeaders(cookies)
            
            val response = app.get(url, headers = headers)
            val html = response.text
            val code = response.code
            
            when {
                CloudflareDetector.isBlocked(code, html) -> {
                    RequestResult.cloudflareBlocked(code, response.url)
                }
                response.isSuccessful -> {
                    RequestResult.success(html, code, response.url)
                }
                else -> {
                    RequestResult.failure("HTTP $code", code)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct request failed: ${e.message}")
            RequestResult.failure(e)
        }
    }
    
    /**
     * Solve CF challenge and return the HTML.
     */
    internal suspend fun solveCloudflareThenRequest(url: String): RequestResult {
        val domain = extractDomain(url)
        
        // Clear old cookies before WebView attempt
        cookieStore.clear(domain, "Preparing for CF solve")
        
        // Try headless first
        Log.i(TAG, "Attempting HEADLESS CF solve for: $url")
        var result = webViewEngine.runSession(
            url = url,
            mode = WebViewEngine.Mode.HEADLESS,
            userAgent = config.userAgent,
            exitCondition = ExitCondition.PageLoaded,
            timeout = 30_000L
        )
        
        when (result) {
            is WebViewResult.Success -> {
                Log.i(TAG, "HEADLESS solve SUCCESS")
                return RequestResult.success(result.html, 200, result.finalUrl)
            }
            is WebViewResult.Timeout -> {
                // Check if it's still CF
                if (CloudflareDetector.isCloudflareChallenge(result.partialHtml)) {
                    Log.i(TAG, "HEADLESS timeout, CF detected, trying FULLSCREEN")
                    
                    // Try fullscreen (user interaction)
                    result = webViewEngine.runSession(
                        url = url,
                        mode = WebViewEngine.Mode.FULLSCREEN,
                        userAgent = config.userAgent,
                        exitCondition = ExitCondition.PageLoaded,
                        timeout = 120_000L
                    )
                    
                    return when (result) {
                        is WebViewResult.Success -> {
                            Log.i(TAG, "FULLSCREEN solve SUCCESS")
                            RequestResult.success(result.html, 200, result.finalUrl)
                        }
                        else -> {
                            Log.e(TAG, "FULLSCREEN solve FAILED")
                            RequestResult.failure("CF bypass failed after fullscreen")
                        }
                    }
                } else {
                    // Timeout but not CF - return partial content
                    Log.w(TAG, "HEADLESS timeout, no CF detected")
                    return RequestResult.failure("Request timeout")
                }
            }
            is WebViewResult.Error -> {
                Log.e(TAG, "WebView error: ${result.reason}")
                return RequestResult.failure(result.reason)
            }
        }
    }
    
    // ==================== HELPERS ====================
    
    private fun buildHeaders(cookies: Map<String, String>): Map<String, String> {
        return buildMap {
            put("User-Agent", config.userAgent)
            put("Referer", "https://$currentDomain/")
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "en-US,en;q=0.5")
            if (cookies.isNotEmpty()) {
                put("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }
    }
    
    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    // ==================== FACTORY ====================
    
    companion object {
        private val instances = mutableMapOf<String, ProviderHttpService>()
        
        /**
         * Create or get existing service instance.
         */
        fun create(
            context: Context,
            config: ProviderConfig,
            parser: BaseParser,
            activityProvider: () -> android.app.Activity?
        ): ProviderHttpService {
            return instances.getOrPut(config.name) {
                // Use shared CookieStore from CoreServices so all providers share session state
                val cookieStore = CoreServices.cookieStore
                
                val domainManager = DomainManager(
                    context = context,
                    providerName = config.name,
                    fallbackDomain = config.fallbackDomain,
                    githubConfigUrl = config.githubConfigUrl,
                    cookieStore = cookieStore,
                    syncWorkerUrl = config.syncWorkerUrl
                )
                
                val webViewEngine = WebViewEngine(
                    cookieStore = cookieStore,
                    activityProvider = activityProvider
                )
                
                ProviderHttpService(
                    config = config,
                    cookieStore = cookieStore,
                    webViewEngine = webViewEngine,
                    domainManager = domainManager,
                    parser = parser
                )
            }
        }
    }
}

/**
 * Configuration for a provider.
 */
data class ProviderConfig(
    val name: String,
    val fallbackDomain: String,
    val githubConfigUrl: String,
    val userAgent: String = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
    val syncWorkerUrl: String? = null
)
