package com.cloudstream.shared.service

import android.content.Context
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.cloudflare.CloudflareDetector
import com.cloudstream.shared.domain.DomainManager
import com.cloudstream.shared.cookie.CookieLifecycleManager
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_PROVIDER_HTTP
import com.cloudstream.shared.provider.ProviderConfig
import com.cloudstream.shared.queue.RequestQueue
import com.cloudstream.shared.queue.RequestResult
import com.cloudstream.shared.session.SessionState
import com.cloudstream.shared.session.SessionStore
import com.cloudstream.shared.session.SessionProvider
import com.cloudstream.shared.strategy.VideoSource
import com.cloudstream.shared.util.WebConfig
import com.cloudstream.shared.webview.CfBypassEngine
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.VideoSnifferEngine
import com.cloudstream.shared.webview.WebViewResult
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * THE GATEWAY - Single entry point for all provider HTTP operations.
 * 
 * Uses shared module components for CloudflareDetector, RequestQueue,
 * SessionState, SessionStore, CfBypassEngine, VideoSnifferEngine, DomainManager.
 */
class ProviderHttpService private constructor(
    private val config: ProviderConfig,
    private val sessionStore: SessionStore,
    private val cfBypassEngine: CfBypassEngine,
    private val videoSnifferEngine: VideoSnifferEngine,
    private val domainManager: DomainManager,
    private val cookieManager: CookieLifecycleManager,
    private val parser: ParserInterface
) {
    @Volatile
    private var sessionState: SessionState = SessionState.initial(config.fallbackDomain)
    
    @Volatile
    private var initialized = false
    private val initMutex = Mutex()

    private val requestQueue = RequestQueue(
        executeRequest = { url, headers -> executeDirectRequest(url, headers) },
        solveCfAndRequest = { url, allowedDomains -> solveCloudflareThenRequest(url, allowedDomains) },
        onDomainRedirect = { oldDomain, newDomain ->
            updateDomain(newDomain)
            domainManager.updateDomain(newDomain)
            domainManager.syncToRemote()
        },
        getCurrentDomain = { sessionState.domain }
    )
    
    val currentDomain: String
        get() = sessionState.domain

    val mainUrl: String
        get() = "https://$currentDomain"
    
    val userAgent: String
        get() = sessionState.userAgent

    val cookies: Map<String, String>
        get() = sessionState.cookies
        
    val snifferEngine: VideoSnifferEngine
        get() = videoSnifferEngine
    
    suspend fun ensureInitialized() {
        if (initialized) return
        
        initMutex.withLock {
            if (initialized) return@withLock
            
            // Only load and initialize SessionProvider if not already valid
            // This prevents repetitive disk reads and "Session initialized" logging
            if (!SessionProvider.hasValidSession()) {
                val persisted = sessionStore.load(config.fallbackDomain)
                sessionState = persisted ?: SessionState.initial(config.fallbackDomain)
                SessionProvider.initialize(sessionState)
            }
            
            // Always run these (lightweight, handles domain changes)
            domainManager.ensureInitialized()
            val remoteDomain = domainManager.currentDomain
            if (remoteDomain != sessionState.domain) {
                updateDomain(remoteDomain)
            }
            initialized = true
        }
    }
    
    @Synchronized
    private fun updateCookies(cookies: Map<String, String>, fromWebView: Boolean) {
        // CRITICAL: WebView CF solve = full replace (fresh session)
        // HTTP response Set-Cookie = merge into existing (don't destroy CF cookies)
        sessionState = if (fromWebView) {
            sessionState.withCookies(cookies, fromWebView = true)
        } else {
            sessionState.mergeCookies(cookies, fromWebView = false)
        }
        sessionStore.save(sessionState)
        
        // CRITICAL: Update SessionProvider so SnifferExtractor gets same cookies
        SessionProvider.update(sessionState)
        
        // Also update cookie manager for domain
        if (cookies.isNotEmpty()) {
            cookieManager.store("https://${sessionState.domain}", cookies, 
                if (fromWebView) "webview" else "http")
        }
        
        // CRITICAL: Inject cookies into Android's system CookieManager for WebView/Glide sharing.
        // Sync to the current domain and ALL known aliases to ensure cross-domain requests
        // (like images or old-domain links) have the required cookies.
        if (cookies.isNotEmpty()) {
            syncCookiesToSystemCookieManager(sessionState.domain, sessionState.cookies)
            for (alias in SessionProvider.getDomainAliases()) {
                syncCookiesToSystemCookieManager(alias, sessionState.cookies)
            }
        }
    }

    /**
     * Publicly expose cookie storage for CDN domains captured during extractions.
     */
    fun storeCdnCookies(url: String, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        cookieManager.store(url, cookies, "sniffer")
        ProviderLogger.d(TAG_PROVIDER_HTTP, "storeCdnCookies", "Stored cookies for CDN", "url" to url.take(60), "count" to cookies.size)
    }
    
    @Synchronized
    fun updateDomain(newDomain: String) {
        if (newDomain == sessionState.domain) return
        val oldDomain = sessionState.domain
        
        // CRITICAL: Always preserve cookies on domain change.
        // Domains change unpredictably (faselhd.biz → faselhdx.xyz, arabseed.show → asd.pics)
        // CF cookies are UA-bound, not domain-bound, so they remain valid.
        sessionState = sessionState.withDomainKeepCookies(newDomain)
        sessionStore.save(sessionState)
        
        // CRITICAL: Register the old domain as an alias.
        // HTML content from the new domain may still reference old-domain URLs
        // (e.g., season/episode links: w312x.faselhdx.xyz when current is w318x).
        // Adding as alias ensures cookies are shared for requests to the old domain.
        SessionProvider.addDomainAlias(oldDomain)
        
        // CRITICAL: Sync current cookies to BOTH the new domain and the old domain (alias).
        // WebView/Glide sub-requests to new-domain URLs need cookies immediately.
        if (sessionState.cookies.isNotEmpty()) {
            syncCookiesToSystemCookieManager(newDomain, sessionState.cookies)
            syncCookiesToSystemCookieManager(oldDomain, sessionState.cookies)
        }
        
        ProviderLogger.i(TAG_PROVIDER_HTTP, "updateDomain", "Domain changed, old domain added as alias",
            "old" to oldDomain, "new" to newDomain, "aliases" to SessionProvider.getDomainAliases().size)
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
    
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), skipRewrite: Boolean = false): String? {
        val fullUrl = buildUrl(url)
        val result = executeDirectRequest(fullUrl, headers, skipRewrite)
        return result.html
    }

    suspend fun getPlayerUrls(url: String): List<String> {
        val doc = getDocument(url) ?: return emptyList()
        return parser.extractWatchServersUrls(doc)
    }

    suspend fun getRaw(url: String, headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val fullUrl = buildUrl(url)
        val request = okhttp3.Request.Builder()
            .url(fullUrl)
            .apply { for ((k, v) in headers) { addHeader(k, v) } }
            .build()
        val directClient = app.baseClient.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
        return directClient.newCall(request).execute()
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
    
    /**
     * DEBUG: Post request with full result details for troubleshooting
     */
    suspend fun postDebug(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap()): RequestResult {
        val fullUrl = buildUrl(url)
        return executePostRequest(fullUrl, data, referer, headers)
    }

    suspend fun sniffVideos(url: String): List<VideoSource> {
        val result = cfBypassEngine.runSession(
            url = url,
            mode = Mode.HEADLESS,
            userAgent = sessionState.userAgent,
            exitCondition = ExitCondition.PageLoaded,
            timeout = 30_000L
        )

        val sources = when (result) {
            is WebViewResult.Success -> extractVideoSources(result.html)
            is WebViewResult.Timeout -> {
                if (CloudflareDetector.isCloudflareChallenge(result.partialHtml)) {
                     val retry = cfBypassEngine.runSession(
                         url = url,
                         mode = Mode.FULLSCREEN,
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

    suspend fun sniffVideosVisible(url: String, headers: Map<String, String> = emptyMap()): List<VideoSource> {
        val result = videoSnifferEngine.runSession(
            url = url,
            mode = Mode.FULLSCREEN,
            userAgent = sessionState.userAgent,
            exitCondition = ExitCondition.VideoFound(minCount = 1),
            timeout = 60_000L,
            referer = headers["Referer"]
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
                 // VideoSnifferEngine currently doesn't return partial found links in Timeout.
                 // We might need to update WebViewResult.Timeout to include foundLinks too?
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
        
        // Check for meta-refresh domain redirect (e.g., LaRoza returns 200 + meta-refresh)
        if (doc != null && result.success) {
            val redirected = handleMetaRefreshRedirect(doc, result.finalUrl ?: url)
            if (redirected != null) return redirected
        }
        
        // Only fall back to WebView if status is 403 AND the HTML contains actual CF markers.
        // This prevents non-CF 403s (like Akwam's anti-bot) from triggering useless CF solve loops.
        val hasCfMarkers = result.html?.let { CloudflareDetector.isCloudflareChallenge(it) } == true
        val isDirectCfBlock = (result.responseCode == 403 && hasCfMarkers)
        // Don't re-enqueue if the queue already attempted CF solve and failed
        // This prevents parallel CF solve thundering herd
        val isQueueLevelFailure = result.error?.message?.contains("Cookie verification") == true ||
                                  result.error?.message?.contains("CF solve failed") == true ||
                                  result.error?.message?.contains("CF re-solve failed") == true ||
                                  result.error?.message?.contains("CF Bypass failed") == true
        
        if (isDirectCfBlock && !isQueueLevelFailure) {
            
            if (config.webViewEnabled) {
                ProviderLogger.w(TAG_PROVIDER_HTTP, "getDocument", "CF blocked - WebView fallback queueing", "url" to url.take(80))
                
                // CRITICAL FIX: Run the fallback solver through the RequestQueue to respect the domain mutex
                // This prevents parallel search threads from launching simultaneous WebView sessions
                val cfResult = requestQueue.enqueueAction(url) {
                    solveCloudflareThenRequest(url, setOf(
                        extractDomain(url).let { d -> d.split(".").takeLast(2).joinToString(".") }
                    ))
                }
                
                if (cfResult.success && cfResult.html != null) {
                    // CRITICAL: Disable domain change checks for WebView CF solve strategy.
                    // CF challenges often involve intermediate URLs or temporary subdomains.
                    // We DO NOT want these to trigger a permanent provider domain change.
                    // The domain manager should only update on definitive main-site redirects.
                    if (false /* disabled for webview strategy */) {
                        checkAndUpdateDomain(url, cfResult.finalUrl)
                    }
                    return Jsoup.parse(cfResult.html, cfResult.finalUrl ?: url)
                }
            }
        }
        
        return doc
    }

    /**
     * Like [getDocument] but does NOT fall back to WebView CF solve.
     * Instead, throws [CloudflareBlockedSearchException] if CF is detected.
     * Used by lazy search to avoid WebView popups during global search.
     */
    suspend fun getDocumentNoFallback(url: String, headers: Map<String, String> = emptyMap(), checkDomainChange: Boolean = false): Document? {
        // CRITICAL FIX: Bypass requestQueue to avoid the automatic CF solver loop
        val result = executeDirectRequest(url, headers)
        
        // Detect domain redirects from ALL responses (including CF-blocked ones).
        // Previously, redirects from CF-blocked responses were discarded, losing
        // the redirect info (e.g., faselhdx.xyz → fasel-hd.cam) before the bypass search.
        if (checkDomainChange) {
            checkAndUpdateDomain(url, result.finalUrl)
        }
        
        // If CF blocked, throw instead of falling back to WebView
        if (result.isCloudflareBlocked || result.responseCode == 403 || 
            result.html?.contains("403 Forbidden") == true) {
            ProviderLogger.i(TAG_PROVIDER_HTTP, "getDocumentNoFallback", 
                "CF detected — throwing for lazy search", "url" to url.take(80))
            throw CloudflareBlockedSearchException(config.name, sessionState.domain)
        }
        
        val doc = result.html?.let { Jsoup.parse(it, url) }
        
        // Check for meta-refresh domain redirect (e.g., LaRoza returns 200 + meta-refresh)
        if (doc != null && result.success) {
            val redirected = handleMetaRefreshRedirect(doc, result.finalUrl ?: url)
            if (redirected != null) return redirected
        }
        
        return doc
    }

    fun getImageHeaders(targetDomain: String? = null): Map<String, String> {
        val domain = targetDomain ?: sessionState.domain
        
        // Get cookies for the specific domain (handles aliases)
        val cookies = if (targetDomain != null && targetDomain != sessionState.domain) {
            com.cloudstream.shared.session.SessionProvider.getCookiesForDomain(targetDomain)
        } else {
            sessionState.cookies
        }
        
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = sessionState.userAgent
        
        // Add cookies if available
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        
        headers["Referer"] = "https://$domain/"
        
//        ProviderLogger.d(TAG_PROVIDER_HTTP, "getImageHeaders", "Building image headers",
//            "targetDomain" to domain,
//            "sessionDomain" to sessionState.domain,
//            "isAlias" to (targetDomain != null && targetDomain != sessionState.domain),
//            "hasCookies" to (headers["Cookie"] != null),
//            "cookieCount" to cookies.size
//        )
        return headers
    }

    // ==================== INTERNAL ====================

    internal suspend fun executeDirectRequest(url: String, customHeaders: Map<String, String> = emptyMap(), skipRewrite: Boolean = false): RequestResult {
        return try {
            val targetUrl = if (skipRewrite) url else rewriteUrlIfNeeded(url)
            
            // Check if URL domain is an alias and get appropriate cookies
            val urlDomain = try {
                java.net.URL(targetUrl).host
            } catch (e: Exception) {
                null
            }
            
            // Use domain-aware cookie retrieval
            val cookiesForDomain = if (urlDomain != null && urlDomain != sessionState.domain) {
                com.cloudstream.shared.session.SessionProvider.getCookiesForDomain(urlDomain)
            } else {
                sessionState.cookies
            }
            
            // Build headers with correct cookies
            val headers = buildMap {
                put("User-Agent", sessionState.userAgent)
                put("Referer", "https://${urlDomain ?: sessionState.domain}/")
                put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                put("Accept-Language", "en-US,en;q=0.9")
                put("Sec-Ch-Ua", WebConfig.buildSecChUa(sessionState.userAgent))
                put("Sec-Ch-Ua-Mobile", "?1")
                put("Sec-Ch-Ua-Platform", "\"Android\"")
                put("Upgrade-Insecure-Requests", "1")
                put("Sec-Fetch-Dest", "document")
                put("Sec-Fetch-Mode", "navigate")
                put("Sec-Fetch-Site", "none")
                put("Sec-Fetch-User", "?1")
                
                // Add cookies if available
                if (cookiesForDomain.isNotEmpty()) {
                    put("Cookie", cookiesForDomain.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }.toMutableMap()
            
            // Add custom headers
            for ((k, v) in customHeaders) {
                headers[k] = v
            }
            
            ProviderLogger.d(TAG_PROVIDER_HTTP, "executeDirectRequest", "Executing HTTP request",
                "url" to targetUrl.take(80),
                "urlDomain" to (urlDomain ?: "same"),
                "sessionDomain" to sessionState.domain,
                "isAlias" to (urlDomain != null && urlDomain != sessionState.domain),
                "hasCookie" to (headers["Cookie"] != null),
                "cookieCount" to cookiesForDomain.size
            )
            
            val directClient = app.baseClient.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .build()

            val headerBuilder = okhttp3.Headers.Builder()
            for ((k, v) in headers) {
                headerBuilder.add(k, v)
            }

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

    internal suspend fun executePostRequest(url: String, data: Map<String, String>, referer: String? = null, customHeaders: Map<String, String> = emptyMap(), skipRewrite: Boolean = false): RequestResult {
        return try {
            val targetUrl = if (skipRewrite) url else rewriteUrlIfNeeded(url)
            val headers = sessionState.buildHeaders().toMutableMap()
            if (referer != null) headers["Referer"] = referer
            for ((k, v) in customHeaders) {
                headers[k] = v
            }
            
            ProviderLogger.d(TAG_PROVIDER_HTTP, "executePostRequest", "Executing POST request",
                "url" to targetUrl.take(80),
                "domain" to sessionState.domain,
                "hasCookie" to (headers["Cookie"] != null)
            )

            val formBody = okhttp3.FormBody.Builder().apply {
                for ((k, v) in data) {
                    add(k, v)
                }
            }.build()

            val directClient = app.baseClient.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .build()

            val headerBuilder = okhttp3.Headers.Builder()
            for ((k, v) in headers) {
                headerBuilder.add(k, v)
            }

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
            val cookieHeaders = response.headers("Set-Cookie")
            for (setCookie in cookieHeaders) {
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
    
    internal suspend fun solveCloudflareThenRequest(url: String, allowedDomains: Set<String> = emptySet()): RequestResult {
        if (!config.webViewEnabled) return RequestResult.failure("WebView disabled")
        
        val targetUrl = rewriteUrlIfNeeded(url)
        
        // Guard: if valid cookies already exist from a very recent solve (< 10s),
        // skip re-invalidation — a concurrent solve likely just succeeded.
        val cookieAge = System.currentTimeMillis() - sessionState.cookieTimestamp
        if (sessionState.cookies.isNotEmpty() && cookieAge < 10_000L) {
            ProviderLogger.i(TAG_PROVIDER_HTTP, "solveCloudflareThenRequest",
                "Skipping CF solve — fresh cookies exist (${cookieAge}ms old), retrying HTTP",
                "domain" to sessionState.domain)
            val retryResult = executeDirectRequest(targetUrl)
            if (retryResult.success) return retryResult
            // If retry still fails (cookies expired or invalid), fall through to full CF solve
            ProviderLogger.d(TAG_PROVIDER_HTTP, "solveCloudflareThenRequest",
                "Retry with existing cookies failed, proceeding with full CF solve")
        }

        // Invalidate current session before WebView attempt
        invalidateSession("Preparing for CF solve")
        
        // Clear system cookies too
        clearSystemCookies(targetUrl)
        
        val mode = if (config.skipHeadless) Mode.FULLSCREEN else Mode.HEADLESS
        
        val result = cfBypassEngine.runSession(
            url = targetUrl,
            mode = mode,
            userAgent = sessionState.userAgent,
            exitCondition = ExitCondition.PageLoaded,
            timeout = if (mode == Mode.FULLSCREEN) 120_000L else 30_000L,
            allowedDomains = allowedDomains
        )
        
        return when (result) {
            is WebViewResult.Success -> {
                updateCookies(result.cookies, fromWebView = true)
                checkAndUpdateDomain(targetUrl, result.finalUrl)
                // Return the WebView HTML directly — this is critical for non-CF sites
                // (like Akwam) where the HTTP client always gets 403 but WebView works fine.
                // Previously we retried HTTP here, but that just 403'd again on non-CF sites.
                RequestResult.success(result.html, 200, result.finalUrl)
            }
            else -> RequestResult.failure("CF Bypass failed")
        }
    }
    
    private fun buildUrl(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http")) return pathOrUrl
        val normalizedPath = if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
        return "https://${sessionState.domain}$normalizedPath"
    }
    
    private fun rewriteUrlIfNeeded(url: String): String {
        val urlDomain = extractDomain(url)
        val currentDomain = sessionState.domain

        return if (urlDomain.isNotBlank() && currentDomain.isNotBlank() && urlDomain != currentDomain) {
            val rewritten = url.replace(urlDomain, currentDomain)
            ProviderLogger.d(TAG_PROVIDER_HTTP, "rewriteUrlIfNeeded", "Rewrote URL",
                "from" to urlDomain, "to" to currentDomain)
            rewritten
        } else {
            url
        }
    }
    
    private fun checkAndUpdateDomain(requestUrl: String, finalUrl: String?) {
        if (finalUrl == null) return
        val requestHost = extractDomain(requestUrl)
        val finalHost = extractDomain(finalUrl)
        
        // Already on this domain — nothing to do
        if (finalHost == sessionState.domain) return
        
        if (requestHost != finalHost && finalHost.isNotBlank()) {
            // Accept any trusted redirect — domains change unpredictably
            // (e.g. faselhd.biz → faselhdx.xyz, arabseed.show → asd.pics)
            // OkHttp redirect policy already prevents ad-redirect hijacking
            ProviderLogger.i(TAG_PROVIDER_HTTP, "checkAndUpdateDomain", "Domain redirect detected and updated",
                "from" to requestHost, "to" to finalHost)
            updateDomain(finalHost)
            domainManager.updateDomain(finalHost)
            domainManager.syncToRemote()
        }
    }
    
    /**
     * Detects meta-refresh redirects (e.g., LaRoza returns HTTP 200 + `<META HTTP-EQUIV="Refresh">`).
     * If the meta-refresh points to a different domain, updates the domain and follows the redirect.
     * Returns the new document if a cross-domain meta-refresh was detected, null otherwise.
     */
    private suspend fun handleMetaRefreshRedirect(doc: Document, currentUrl: String): Document? {
        val metaRefreshUrl = extractMetaRefreshUrl(doc) ?: return null
        
        val currentHost = extractDomain(currentUrl)
        val refreshHost = extractDomain(metaRefreshUrl)
        
        // Only act on cross-domain meta-refresh (same-domain refresh is just a page reload)
        if (refreshHost.isBlank() || refreshHost == currentHost) return null
        
        ProviderLogger.i(TAG_PROVIDER_HTTP, "handleMetaRefreshRedirect",
            "Meta-refresh domain redirect detected",
            "from" to currentHost, "to" to refreshHost, "targetUrl" to metaRefreshUrl.take(100))
        
        // Update domain before following the redirect
        checkAndUpdateDomain(currentUrl, metaRefreshUrl)
        
        // Follow the redirect — use requestQueue so leader/follower logic applies
        val result = requestQueue.enqueue(metaRefreshUrl)
        return if (result.success && result.html != null) {
            Jsoup.parse(result.html, metaRefreshUrl)
        } else {
            ProviderLogger.w(TAG_PROVIDER_HTTP, "handleMetaRefreshRedirect",
                "Failed to follow meta-refresh redirect", "url" to metaRefreshUrl.take(100))
            null
        }
    }
    
    /**
     * Extracts the target URL from a `<meta http-equiv="Refresh" content="0;URL=...">` tag.
     * Returns null if no meta-refresh is found.
     */
    private fun extractMetaRefreshUrl(doc: Document): String? {
        val refreshMeta = doc.selectFirst("meta[http-equiv=Refresh]") ?: return null
        val content = refreshMeta.attr("content") ?: return null
        // Format: "0;URL=https://example.com/path" or "0; URL=https://example.com/path"
        val match = Regex("URL=(.+)", RegexOption.IGNORE_CASE).find(content)
        return match?.groupValues?.get(1)?.trim()
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
            ProviderLogger.w(TAG_PROVIDER_HTTP, "clearSystemCookies", "Failed to clear system cookies: ${e.message}")
        }
    }
    
    /**
     * Sync cookies to Android's system CookieManager for a specific domain.
     * This ensures WebView/Glide sub-requests to this domain have the cf_clearance cookie.
     *
     * Called for both the current domain and all alias domains (old domains that
     * may still appear in HTML links, e.g. w312x.faselhdx.xyz after migrating to w318x).
     */
    private fun syncCookiesToSystemCookieManager(domain: String, cookies: Map<String, String>) {
        try {
            val systemCookieManager = android.webkit.CookieManager.getInstance()
            systemCookieManager.setAcceptCookie(true)
            val cookieUrl = "https://$domain"
            for ((key, value) in cookies) {
                systemCookieManager.setCookie(cookieUrl, "$key=$value; path=/; secure")
            }
            systemCookieManager.flush()
            ProviderLogger.d(TAG_PROVIDER_HTTP, "syncCookiesToSystemCookieManager", 
                "Injected ${cookies.size} cookies", "domain" to domain)
        } catch (e: Exception) {
            ProviderLogger.w(TAG_PROVIDER_HTTP, "syncCookiesToSystemCookieManager", 
                "Failed to inject cookies", "domain" to domain, "error" to e.message)
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
            // Initialize dynamic User-Agent from the real system WebView
            WebConfig.getUserAgent(context)
            
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
                val cfBypassEngine = CfBypassEngine(activityProvider)
                val videoSnifferEngine = VideoSnifferEngine(activityProvider)
                
                ProviderHttpService(config, sessionStore, cfBypassEngine, videoSnifferEngine, domainManager, cookieManager, parser)
            }
        }
    }
}
