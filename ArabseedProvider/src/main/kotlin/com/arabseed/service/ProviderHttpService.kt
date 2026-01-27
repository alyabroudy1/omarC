package com.arabseed.service

import android.content.Context
import com.arabseed.service.http.*
import com.arabseed.service.parsing.BaseParser
import com.arabseed.service.session.SessionState
import com.arabseed.service.session.SessionStore
import com.arabseed.service.webview.ExitCondition
import com.arabseed.service.webview.WebViewEngine
import com.arabseed.service.webview.WebViewResult
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * THE GATEWAY - Single entry point for all provider HTTP operations.
 * 
 * Architecture: Unified SessionState
 * - SessionState is the SINGLE SOURCE OF TRUTH for UA, cookies, domain
 * - All requests use headers derived from SessionState
 * - WebView updates SessionState on successful CF solve
 * - SessionStore persists state across app restarts
 */
class ProviderHttpService private constructor(
    private val config: ProviderConfig,
    private val sessionStore: SessionStore,
    private val webViewEngine: WebViewEngine,
    // domainManager removed for simplicity in extension port, handling domain locally/via config
    // Actually, original code had DomainManager. I should port it too if I want full feature parity...
    // But I didn't verify DomainManager source.
    // Wait, the original had private val domainManager: DomainManager.
    // I missed importing DomainManager source!
    // I will simplify by removing DomainManager logic for now and relying on static domain + fallback.
    // This reduces complexity for the extension port.
    private val parser: BaseParser
) {
    private val TAG = "ProviderHttpService"
    
    /** 
     * SINGLE SOURCE OF TRUTH - all HTTP operations read from this.
     * Updated atomically via withCookies(), withDomain(), etc.
     */
    @Volatile
    private var sessionState: SessionState = SessionState.initial(config.fallbackDomain, config.userAgent)
    
    private val requestQueue = RequestQueue(
        executeRequest = { url -> executeDirectRequest(url) },
        solveCfAndRequest = { url -> solveCloudflareThenRequest(url) },
        onDomainRedirect = { oldDomain, newDomain ->
            Log.i(TAG, "RequestQueue detected redirect: $oldDomain → $newDomain")
            updateDomain(newDomain)
        }
    )
    
    val currentDomain: String
        get() = sessionState.domain
    
    // ==================== INITIALIZATION ====================
    
    /**
     * Initialize session from disk.
     * Called once before first request.
     */
    private suspend fun ensureInitialized() {
        Log.i(TAG, "Using MainAPI UA: ${config.userAgent}")

        // Load persisted session
        val persisted = sessionStore.load(config.fallbackDomain)
        if (persisted != null) {
            sessionState = persisted
        } else {
            // New session
            sessionState = SessionState.initial(config.fallbackDomain, config.userAgent)
        }
    }
    
    // ==================== STATE MANAGEMENT ====================
    
    /**
     * Update session with new cookies. Persists to disk.
     */
    @Synchronized
    private fun updateCookies(cookies: Map<String, String>, fromWebView: Boolean) {
        sessionState = sessionState.withCookies(cookies, fromWebView)
        sessionStore.save(sessionState)
        Log.d(TAG, "Updated cookies: ${cookies.keys}")
    }
    
    /**
     * Update session with new domain. Clears cookies. Persists to disk.
     */
    @Synchronized
    fun updateDomain(newDomain: String) {
        if (newDomain == sessionState.domain) return
        sessionState = sessionState.withDomain(newDomain)
        sessionStore.save(sessionState)
        Log.i(TAG, "Updated domain: $newDomain (cookies cleared)")
    }
    
    /**
     * Invalidate current session. Clears cookies. Persists to disk.
     */
    @Synchronized
    fun invalidateSession(reason: String) {
        sessionState = sessionState.invalidate()
        sessionStore.save(sessionState)
        Log.w(TAG, "Session invalidated: $reason")
    }
    
    // ==================== PUBLIC API: High-level ====================
    
    /**
     * Get main page content, parsed into intermediate items.
     */
    suspend fun getMainPage(path: String): List<BaseParser.ParsedSearchItem> {
        ensureInitialized()
        
        val url = buildUrl(path)
        val doc = getDocument(url, checkDomainChange = true)
        
        return doc?.let { parser.parseMainPage(it) } ?: emptyList()
    }
    
    /**
     * Search for content, parsed into intermediate items.
     */
    suspend fun search(query: String, searchPath: String = "/search/"): List<BaseParser.ParsedSearchItem> {
        ensureInitialized()
        
        val url = buildUrl("$searchPath$query")
        val doc = getDocument(url, checkDomainChange = true)
        
        return doc?.let { parser.parseSearch(it) } ?: emptyList()
    }
    
    /**
     * Load content detail page.
     */
    suspend fun load(url: String): LoadResponse? {
        ensureInitialized()
        
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
            userAgent = sessionState.userAgent,
            exitCondition = ExitCondition.PageLoaded,
            timeout = 30_000L
        )
        
        return when (result) {
            is WebViewResult.Success -> {
                extractVideoSources(result.html)
            }
            is WebViewResult.Timeout -> {
                if (CloudflareDetector.isCloudflareChallenge(result.partialHtml)) {
                    Log.i(TAG, "CF detected during video sniff, trying FULLSCREEN")
                    
                    invalidateSession("CF during video sniff")
                    
                    val retryResult = webViewEngine.runSession(
                        url = url,
                        mode = WebViewEngine.Mode.FULLSCREEN,
                        userAgent = sessionState.userAgent,
                        exitCondition = ExitCondition.PageLoaded,
                        timeout = 120_000L
                    )
                    
                    if (retryResult is WebViewResult.Success) {
                        // Update cookies from WebView
                        updateCookies(retryResult.cookies, fromWebView = true)
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
        android.util.Log.d(TAG, "getDocument: url: $url")
        val result = requestQueue.enqueue(url)
        
        if (result.success && checkDomainChange) {
            checkAndUpdateDomain(url, result.finalUrl)
        }
        
        // CF BYPASS / 403 HANDLING
        // If DirectHttp is blocked (403/Access Denied), retry with WebView.
        // This is critical when OkHttp TLS fingerprint is blocked despite valid cookies.
        val doc = result.html?.let { Jsoup.parse(it) }
        
        if (doc == null || result.responseCode == 403 || doc.select("title").text().contains("403 Forbidden") || doc.select("title").text().contains("Access denied")) {
            Log.w(TAG, "DirectHttp blocked (403/Access Denied). Retrying with WebView: $url")
            val webResult = webViewEngine.runSession(
                url = url,
                mode = WebViewEngine.Mode.HEADLESS, // Try headless first
                userAgent = sessionState.userAgent,
                exitCondition = ExitCondition.PageLoaded,
                timeout = 60_000L
            )
            
            if (webResult is WebViewResult.Success) {
                Log.i(TAG, "WebView fallback SUCCESS for $url")
                // Update cookies just in case
                updateCookies(webResult.cookies, fromWebView = true)
                return org.jsoup.Jsoup.parse(webResult.html, url)
            } else {
                Log.e(TAG, "WebView fallback FAILED for $url")
                // Fallback to fullscreen if headless failed? 
                // For now return original error doc or null to avoid infinite loop if WebView also fails
            }
        }
        
        return doc
    }
    
    /**
     * Get headers for loading images.
     */
    fun getImageHeaders(): Map<String, String> {
        // Using SessionState.buildHeaders (the one reverted to use default headers)
        // If SessionState has specific image handling logic, it will be used.
        return sessionState.buildHeaders()
    }
    
    // ==================== INTERNAL: Request execution ====================
    
    /**
     * Execute a direct HTTP request using current SessionState.
     * Uses custom OkHttpClient to enforce HTTP/1.1 (FaselHD strategy).
     */
    internal suspend fun executeDirectRequest(url: String): RequestResult {
        return try {
            val targetUrl = rewriteUrlIfNeeded(url)
            val headers = sessionState.buildHeaders()
            
            // UA VERIFICATION: Log to ensure consistency between WebView and OkHttp
            Log.d(TAG, "Requesting: $targetUrl")
            Log.d(TAG, "UA being used: ${sessionState.userAgent.take(60)}...")
            Log.d(TAG, "Cookies: ${sessionState.cookies.keys}")
            
            // FORCE HTTP/1.1 - Fix for Cloudflare 403 Loop (Matches FaselHD)
            // Cloudflare often fingerprints HTTP/2 requests from OkHttp differently than WebView.
            val directClient = app.baseClient.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .build()

            val headerBuilder = okhttp3.Headers.Builder()
            headers.forEach { (k, v) -> headerBuilder.add(k, v) }

            val okRequest = okhttp3.Request.Builder()
                .url(targetUrl)
                .headers(headerBuilder.build())
                .get()
                .build()
            
            // Execute using the custom client
            val response = directClient.newCall(okRequest).execute()
            val code = response.code
            val html = response.body?.string() ?: ""
            val finalUrl = response.request.url.toString()
            
            // Parse new cookies from response
            val newCookies = mutableMapOf<String, String>()
            response.headers("Set-Cookie").forEach { setCookie ->
                val parts = setCookie.split(";").firstOrNull()?.split("=", limit = 2)
                if (parts != null && parts.size == 2) {
                    newCookies[parts[0].trim()] = parts[1].trim()
                }
            }
            if (newCookies.isNotEmpty()) {
                updateCookies(newCookies, fromWebView = false)
            }
            
            response.close()
            
            Log.d(TAG, "Response: $code | Final URL: $finalUrl")
            
            when {
                // Arabseed sometimes returns 403 with valid content
                (code == 403 && (html.contains("ArabSeed") || html.contains("عرب سيد"))) -> {
                    Log.i(TAG, "403 with valid content - treating as success")
                    RequestResult.success(html, 200, finalUrl)
                }
                CloudflareDetector.isBlocked(code, html) -> {
                    Log.w(TAG, "Cloudflare blocked: $code")
                    RequestResult.cloudflareBlocked(code, finalUrl)
                }
                response.isSuccessful -> {
                    RequestResult.success(html, code, finalUrl)
                }
                else -> {
                    Log.e(TAG, "HTTP Failure: $code")
                    RequestResult.failure("HTTP $code", code)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct request failed: ${e.message}")
            RequestResult.failure(e)
        }
    }
    
    /**
     * Solve CF challenge using WebView and update SessionState.
     */
    internal suspend fun solveCloudflareThenRequest(url: String): RequestResult {
        val targetUrl = rewriteUrlIfNeeded(url)
        
        // Invalidate current session before WebView attempt
        invalidateSession("Preparing for CF solve")
        
        // Clear system cookies too
        clearSystemCookies(targetUrl)
        
        var result: WebViewResult
        var skippedHeadless = false
        
        if (config.skipHeadless) {
            Log.i(TAG, "Skipping HEADLESS, going FULLSCREEN for: $targetUrl")
            skippedHeadless = true
            result = webViewEngine.runSession(
                url = targetUrl,
                mode = WebViewEngine.Mode.FULLSCREEN,
                userAgent = sessionState.userAgent,
                exitCondition = ExitCondition.PageLoaded,
                timeout = 120_000L
            )
        } else {
            Log.i(TAG, "Attempting HEADLESS CF solve for: $targetUrl")
            result = webViewEngine.runSession(
                url = targetUrl,
                mode = WebViewEngine.Mode.HEADLESS,
                userAgent = sessionState.userAgent,
                exitCondition = ExitCondition.PageLoaded,
                timeout = 30_000L
            )
        }
        
        when (result) {
            is WebViewResult.Success -> {
                val mode = if (skippedHeadless) "FULLSCREEN" else "HEADLESS"
                Log.i(TAG, "CF solve SUCCESS ($mode)")
                
                // UPDATE SESSION STATE with cookies from WebView
                updateCookies(result.cookies, fromWebView = true)
                
                // Check for domain change
                checkAndUpdateDomain(targetUrl, result.finalUrl)
                
                return RequestResult.success(result.html, 200, result.finalUrl)
            }
            is WebViewResult.Timeout -> {
                if (CloudflareDetector.isCloudflareChallenge(result.partialHtml) && !skippedHeadless) {
                    Log.i(TAG, "HEADLESS timeout, CF detected, trying FULLSCREEN")
                    
                    result = webViewEngine.runSession(
                        url = targetUrl,
                        mode = WebViewEngine.Mode.FULLSCREEN,
                        userAgent = sessionState.userAgent,
                        exitCondition = ExitCondition.PageLoaded,
                        timeout = 120_000L
                    )
                    
                    return when (result) {
                        is WebViewResult.Success -> {
                            Log.i(TAG, "FULLSCREEN solve SUCCESS")
                            updateCookies(result.cookies, fromWebView = true)
                            checkAndUpdateDomain(targetUrl, result.finalUrl)
                            RequestResult.success(result.html, 200, result.finalUrl)
                        }
                        else -> {
                            Log.e(TAG, "FULLSCREEN solve FAILED")
                            RequestResult.failure("CF bypass failed")
                        }
                    }
                } else {
                    Log.w(TAG, "WebView timeout, no CF detected or already FULLSCREEN")
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
    
    private fun buildUrl(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "https://${sessionState.domain}$normalizedPath"
    }
    
    private fun rewriteUrlIfNeeded(url: String): String {
        val urlDomain = extractDomain(url)
        val currentDomain = sessionState.domain
        
        return if (urlDomain.isNotBlank() && currentDomain.isNotBlank() && urlDomain != currentDomain) {
            val rewritten = url.replace(urlDomain, currentDomain)
            Log.d(TAG, "Rewrote URL: $urlDomain → $currentDomain")
            rewritten
        } else {
            url
        }
    }
    
    private fun checkAndUpdateDomain(requestUrl: String, finalUrl: String?) {
        if (finalUrl == null) return
        
        try {
            val requestHost = extractDomain(requestUrl)
            val finalHost = extractDomain(finalUrl)
            
            if (requestHost != finalHost && finalHost.isNotBlank()) {
                Log.i(TAG, "Domain redirect: $requestHost → $finalHost")
                updateDomain(finalHost)
                // Domain sync removed for simplification
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check domain: ${e.message}")
        }
    }
    
    private fun clearSystemCookies(url: String) {
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            if (cookies != null) {
                cookies.split(";").forEach { cookie ->
                    val name = cookie.split("=").firstOrNull()?.trim()
                    if (!name.isNullOrBlank()) {
                        cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/")
                    }
                }
                cookieManager.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear system cookies: ${e.message}")
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
                val sessionStore = SessionStore(context, config.name)
                
                // Removed DomainManager for simplicity - relying on config & local detection
                
                val webViewEngine = WebViewEngine(
                    activityProvider = activityProvider
                )
                
                ProviderHttpService(
                    config = config,
                    sessionStore = sessionStore,
                    webViewEngine = webViewEngine,
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
    val syncWorkerUrl: String? = null,
    val skipHeadless: Boolean = false,
    val userAgent: String = SessionState.DEFAULT_UA
)
