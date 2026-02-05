package com.cloudstream.shared.service

import android.content.Context
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.cloudflare.CloudflareDetector
import com.cloudstream.shared.domain.DomainManager
import com.cloudstream.shared.cookie.CookieLifecycleManager
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_PROVIDER_HTTP
import com.cloudstream.shared.provider.ProviderConfig
import com.cloudstream.shared.provider.UNIFIED_USER_AGENT
import com.cloudstream.shared.queue.RequestQueue
import com.cloudstream.shared.queue.RequestResult
import com.cloudstream.shared.session.SessionState
import com.cloudstream.shared.session.SessionStore
import com.cloudstream.shared.strategy.VideoSource
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.WebViewEngine
import com.cloudstream.shared.webview.WebViewResult
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * THE GATEWAY - Single entry point for all provider HTTP operations.
 * 
 * Uses shared module components for CloudflareDetector, RequestQueue,
 * SessionState, SessionStore, WebViewEngine, DomainManager.
 */
class ProviderHttpService private constructor(
    private val config: ProviderConfig,
    private val sessionStore: SessionStore,
    private val webViewEngine: WebViewEngine,
    private val domainManager: DomainManager,
    private val cookieManager: CookieLifecycleManager,
    private val parser: ParserInterface
) {
    @Volatile
    private var sessionState: SessionState = SessionState.initial(config.fallbackDomain)
    
    private val requestQueue = RequestQueue(
        executeRequest = { url, headers -> executeDirectRequest(url, headers) },
        solveCfAndRequest = { url -> solveCloudflareThenRequest(url) },
        onDomainRedirect = { oldDomain, newDomain ->
            updateDomain(newDomain)
            domainManager.updateDomain(newDomain)
        }
    )
    
    val currentDomain: String
        get() = sessionState.domain

    val userAgent: String
        get() = sessionState.userAgent

    val cookies: Map<String, String>
        get() = sessionState.cookies
        
    val engine: WebViewEngine
        get() = webViewEngine
    
    suspend fun ensureInitialized() {
        val persisted = sessionStore.load(config.fallbackDomain)
        if (persisted != null) {
            sessionState = persisted
        } else {
            sessionState = SessionState.initial(config.fallbackDomain)
        }
        
        domainManager.ensureInitialized()
        val remoteDomain = domainManager.currentDomain
        if (remoteDomain != sessionState.domain) {
            updateDomain(remoteDomain)
        }
    }
    
    @Synchronized
    private fun updateCookies(cookies: Map<String, String>, fromWebView: Boolean) {
        sessionState = sessionState.withCookies(cookies, fromWebView)
        sessionStore.save(sessionState)
        
        // Also update cookie manager for domain
        if (cookies.isNotEmpty()) {
            cookieManager.store("https://${sessionState.domain}", cookies, 
                if (fromWebView) "webview" else "http")
        }
    }
    
    @Synchronized
    fun updateDomain(newDomain: String) {
        if (newDomain == sessionState.domain) return
        sessionState = sessionState.withDomain(newDomain)
        sessionStore.save(sessionState)
    }
    
    @Synchronized
    fun invalidateSession(reason: String) {
        sessionState = sessionState.invalidate()
        sessionStore.save(sessionState)
        ProviderLogger.i(TAG_PROVIDER_HTTP, "invalidateSession", reason)
    }
    
    // ==================== PUBLIC API ====================
    
    suspend fun getMainPage(path: String): List<ParserInterface.ParsedItem> {
        val url = buildUrl(path)
        val doc = getDocument(url, checkDomainChange = true)
        return doc?.let { parser.parseMainPage(it) }.orEmpty()
    }
    
    suspend fun search(query: String): List<ParserInterface.ParsedItem> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = buildUrl("/?s=$encoded")
        val doc = getDocument(url, checkDomainChange = true)
        return doc?.let { parser.parseSearch(it) }.orEmpty()
    }
    
    suspend fun getPlayerUrls(url: String): List<String> {
        val doc = getDocument(url) ?: return emptyList()
        return parser.extractPlayerUrls(doc)
    }

    suspend fun post(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap()): Document? {
        val fullUrl = buildUrl(url)
        val result = executePostRequest(fullUrl, data, referer, headers)
        return result.html?.let { Jsoup.parse(it, fullUrl) }
    }

    suspend fun postText(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap()): String? {
        val fullUrl = buildUrl(url)
        val result = executePostRequest(fullUrl, data, referer, headers)
        return result.html
    }

    suspend fun sniffVideos(url: String): List<VideoSource> {
        val result = webViewEngine.runSession(
            url = url,
            mode = WebViewEngine.Mode.HEADLESS,
            userAgent = sessionState.userAgent,
            exitCondition = ExitCondition.PageLoaded,
            timeout = 30_000L
        )

        val sources = when (result) {
            is WebViewResult.Success -> extractVideoSources(result.html)
            is WebViewResult.Timeout -> {
                if (CloudflareDetector.isCloudflareChallenge(result.partialHtml)) {
                     val retry = webViewEngine.runSession(
                         url = url,
                         mode = WebViewEngine.Mode.FULLSCREEN,
                         userAgent = sessionState.userAgent,
                         exitCondition = ExitCondition.PageLoaded, // Still PageLoaded for CF bypass
                         timeout = 120_000L
                     )
                     if (retry is WebViewResult.Success) {
                         updateCookies(retry.cookies, fromWebView = true)
                         extractVideoSources(retry.html)
                     } else emptyList()
                } else emptyList()
            }
            else -> emptyList()
        }
        return sources.distinctBy { it.url }
    }

    suspend fun sniffVideosVisible(url: String): List<VideoSource> {
        val result = webViewEngine.runSession(
            url = url,
            mode = WebViewEngine.Mode.FULLSCREEN,
            userAgent = sessionState.userAgent,
            exitCondition = ExitCondition.VideoFound(minCount = 1),
            timeout = 60_000L
        )

        return when (result) {
            is WebViewResult.Success -> {
                 if (result.foundLinks.isNotEmpty()) {
                     result.foundLinks.map { 
                         VideoSource(it.url, it.qualityLabel, it.headers) 
                     }
                 } else {
                     extractVideoSources(result.html)
                 }
            }
            is WebViewResult.Timeout -> {
                 // Return whatever we found so far? 
                 // WebViewEngine currently doesn't return partial found links in Timeout.
                 // We might need to update WebViewEngineResult.Timeout to include foundLinks too?
                 // For now, assume empty.
                 emptyList()
            }
            else -> emptyList()
        }
    }

    private fun extractVideoSources(html: String): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()
        Regex("""file:\s*["']([^"']+)["']""").findAll(html).forEach { match ->
            val url = match.groupValues[1]
            if (url.contains(".m3u8") || url.contains(".mp4")) {
                sources.add(VideoSource(url, "Auto"))
            }
        }
        return sources
    }
    
    // ==================== LOW LEVEL ====================

    suspend fun getDocument(url: String, headers: Map<String, String> = emptyMap(), checkDomainChange: Boolean = false): Document? {
        val result = requestQueue.enqueue(url, headers)
        
        if (result.success && checkDomainChange) {
            checkAndUpdateDomain(url, result.finalUrl)
        }
        
        val doc = result.html?.let { Jsoup.parse(it, url) }
        
        if (doc == null || result.responseCode == 403 || 
            (doc.select("title").text().contains("403 Forbidden"))) {
            
            if (config.webViewEnabled) {
                ProviderLogger.w(TAG_PROVIDER_HTTP, "getDocument", "403 - WebView fallback", "url" to url.take(80))
                val cfResult = solveCloudflareThenRequest(url)
                if (cfResult.success && cfResult.html != null) {
                    return Jsoup.parse(cfResult.html, cfResult.finalUrl ?: url)
                }
            }
        }
        
        return doc
    }

    fun getImageHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = sessionState.userAgent
        sessionState.buildCookieHeader()?.let { headers["Cookie"] = it }
        headers["Referer"] = "https://${sessionState.domain}/"
        
        ProviderLogger.d(TAG_PROVIDER_HTTP, "getImageHeaders", "Building image headers",
            "domain" to sessionState.domain,
            "hasCookies" to (headers["Cookie"] != null),
            "cookieKeys" to sessionState.cookies.keys.toString()
        )
        return headers
    }

    // ==================== INTERNAL ====================

    internal suspend fun executeDirectRequest(url: String, customHeaders: Map<String, String> = emptyMap()): RequestResult {
        return try {
            val targetUrl = rewriteUrlIfNeeded(url)
            val headers = sessionState.buildHeaders().toMutableMap()
            customHeaders.forEach { (k, v) -> headers[k] = v }
            
            ProviderLogger.d(TAG_PROVIDER_HTTP, "executeDirectRequest", "Executing HTTP request",
                "url" to targetUrl.take(80),
                "domain" to sessionState.domain,
                "hasCookie" to (headers["Cookie"] != null),
                "cookieKeys" to sessionState.cookies.keys.toString()
            )
            
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
            
            executeRequestHelper(directClient, okRequest)
        } catch (e: Exception) {
            ProviderLogger.e(TAG_PROVIDER_HTTP, "executeDirectRequest", "Failed", e, "url" to url.take(80))
            RequestResult.failure(e)
        }
    }

    internal suspend fun executePostRequest(url: String, data: Map<String, String>, referer: String? = null, customHeaders: Map<String, String> = emptyMap()): RequestResult {
        return try {
            val targetUrl = rewriteUrlIfNeeded(url)
            val headers = sessionState.buildHeaders().toMutableMap()
            if (referer != null) headers["Referer"] = referer
            customHeaders.forEach { (k, v) -> headers[k] = v }

            val formBody = okhttp3.FormBody.Builder().apply {
                data.forEach { (k, v) -> add(k, v) }
            }.build()

            val directClient = app.baseClient.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .build()

            val headerBuilder = okhttp3.Headers.Builder()
            headers.forEach { (k, v) -> headerBuilder.add(k, v) }

            val okRequest = okhttp3.Request.Builder()
                .url(targetUrl)
                .headers(headerBuilder.build())
                .post(formBody)
                .build()

            executeRequestHelper(directClient, okRequest)
        } catch (e: Exception) {
            RequestResult.failure(e)
        }
    }

    private fun executeRequestHelper(client: okhttp3.OkHttpClient, request: okhttp3.Request): RequestResult {
        val response = client.newCall(request).execute()
        val code = response.code
        val html = response.body?.string() ?: ""
        val finalUrl = response.request.url.toString()
        
        val responseDomain = extractDomain(finalUrl)
        val isProviderDomain = responseDomain.contains(sessionState.domain) || 
                              config.trustedDomains.any { responseDomain.contains(it) }
                              
        if (isProviderDomain) {
            val newCookies = mutableMapOf<String, String>()
            response.headers("Set-Cookie").forEach { setCookie ->
                val parts = setCookie.split(";").firstOrNull()?.split("=", limit = 2)
                if (parts != null && parts.size == 2) {
                    newCookies[parts[0].trim()] = parts[1].trim()
                }
            }
            if (newCookies.isNotEmpty()) updateCookies(newCookies, fromWebView = false)
        }
        
        response.close()
        
        if (CloudflareDetector.isBlocked(code, html)) {
            return RequestResult.cloudflareBlocked(code, finalUrl)
        } else {
            return RequestResult.success(html, code, finalUrl)
        }
    }
    
    internal suspend fun solveCloudflareThenRequest(url: String): RequestResult {
        if (!config.webViewEnabled) return RequestResult.failure("WebView disabled")
        
        val targetUrl = rewriteUrlIfNeeded(url)
        invalidateSession("Preparing for CF solve")
        
        val mode = if (config.skipHeadless) WebViewEngine.Mode.FULLSCREEN else WebViewEngine.Mode.HEADLESS
        
        val result = webViewEngine.runSession(
            url = targetUrl,
            mode = mode,
            userAgent = sessionState.userAgent,
            exitCondition = ExitCondition.PageLoaded,
            timeout = if (mode == WebViewEngine.Mode.FULLSCREEN) 120_000L else 30_000L
        )
        
        return when (result) {
            is WebViewResult.Success -> {
                updateCookies(result.cookies, fromWebView = true)
                checkAndUpdateDomain(targetUrl, result.finalUrl)
                RequestResult.success(result.html, 200, result.finalUrl)
            }
            else -> RequestResult.failure("CF Bypass failed")
        }
    }
    
    private fun buildUrl(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "https://${sessionState.domain}$normalizedPath"
    }
    
    private fun rewriteUrlIfNeeded(url: String): String {
        val urlDomain = extractDomain(url)
        val currentDomain = sessionState.domain
        val isTrusted = config.trustedDomains.any { urlDomain.contains(it) }
        
        return if (urlDomain != currentDomain && isTrusted && currentDomain.isNotBlank()) {
            url.replace(urlDomain, currentDomain)
        } else {
            url
        }
    }
    
    private fun checkAndUpdateDomain(requestUrl: String, finalUrl: String?) {
        if (finalUrl == null) return
        val requestHost = extractDomain(requestUrl)
        val finalHost = extractDomain(finalUrl)
        
        if (requestHost != finalHost && finalHost.isNotBlank()) {
            updateDomain(finalHost)
            domainManager.syncToRemote()
        }
    }
    
    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) { "" }
    }
    
    companion object {
        private val instances = mutableMapOf<String, ProviderHttpService>()
        
        fun create(
            context: Context,
            config: ProviderConfig,
            parser: ParserInterface,
            activityProvider: () -> android.app.Activity?
        ): ProviderHttpService {
            return instances.getOrPut(config.name) {
                val sessionStore = SessionStore(context, config.name)
                val cookieManager = CookieLifecycleManager()
                val domainManager = DomainManager(
                    context = context,
                    providerName = config.name,
                    fallbackDomain = config.fallbackDomain,
                    githubConfigUrl = config.githubConfigUrl,
                    syncWorkerUrl = config.syncWorkerUrl
                )
                val webViewEngine = WebViewEngine(activityProvider)
                
                ProviderHttpService(config, sessionStore, webViewEngine, domainManager, cookieManager, parser)
            }
        }
    }
}
