package com.cloudstream.shared.service

import android.content.Context
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.cloudflare.CloudflareDetector
import com.cloudstream.shared.domain.DomainManager
import com.cloudstream.shared.cookie.CookieLifecycleManager
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_PROVIDER_HTTP
import com.cloudstream.shared.provider.UNIFIED_USER_AGENT
import com.cloudstream.shared.network.ChromiumFetcher
import com.cloudstream.shared.network.MediaUrlValidator
import com.cloudstream.shared.network.ValidatedSource
import com.cloudstream.shared.network.VideoSourceCandidate
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
import com.cloudstream.shared.webview.NavigationEngine
import com.cloudstream.shared.webview.NavigationResult
import com.cloudstream.shared.webview.NavigationStep
import com.cloudstream.shared.webview.SurfSnifferEngine
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
    val navigationEngine: NavigationEngine,
    /** Fullscreen WebView the user surfs by hand; injects nothing, only watches the network. */
    val surfSnifferEngine: SurfSnifferEngine,
    private val domainManager: DomainManager,
    private val cookieManager: CookieLifecycleManager,
    private val parser: ParserInterface,
    /** Chrome-TLS HTTP client for Tier 3 TLS fingerprint fallback */
    val chromiumFetcher: ChromiumFetcher
) {
    @Volatile
    private var sessionState: SessionState = SessionState.initial(config.fallbackDomain)
    
    @Volatile
    private var initialized = false
    private val initMutex = Mutex()

    /** Validates media URLs for TLS-based CDN blocks before ExoPlayer */
    val mediaValidator = MediaUrlValidator()

    private val requestQueue = RequestQueue(
        executeRequest = { url, headers -> executeDirectRequest(url, headers, rewriteDomain = true) },
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
                sessionState = persisted ?: SessionState.initial(config.fallbackDomain, config.userAgent ?: UNIFIED_USER_AGENT)
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
        val doc = getDocument(url, checkDomainChange = true, rewriteDomain = true)
        return doc?.let { parser.parseMainPage(it) }.orEmpty()
    }
    
    suspend fun search(query: String): List<ParserInterface.ParsedItem> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = buildUrl("/?s=$encoded")
        val doc = getDocument(url, checkDomainChange = true, rewriteDomain = true)
        return doc?.let { parser.parseSearch(it) }.orEmpty()
    }
    
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): String? {
        val fullUrl = buildUrl(url)
        val result = executeDirectRequest(fullUrl, headers, rewriteDomain)
        return result.html
    }

    suspend fun getPlayerUrls(url: String): List<String> {
        val doc = getDocument(url) ?: return emptyList()
        return parser.extractWatchServersUrls(doc)
    }

    /**
     * The address-family policy for every request this service makes, or null for system default.
     *
     * This must be the SAME policy used by anything that mints an IP-pinned token for this
     * provider (e.g. a WebView interceptor's OkHttp client) — see [ProviderConfig.preferIpv4].
     * IPv4 wins when both flags are set: it is the restrictive choice, and the one that keeps a
     * token usable by ExoPlayer, whose HTTP stack we cannot configure.
     */
    fun dnsPolicy(): okhttp3.Dns? = when {
        config.preferIpv4 -> com.cloudstream.shared.network.PreferIpv4Dns()
        config.preferIpv6 -> com.cloudstream.shared.network.PreferIpv6Dns()
        else -> null
    }

    /**
     * Applies [dnsPolicy] to a client builder.
     *
     * Setting the resolver is not enough on its own: OkHttp 5 races address families against each
     * other (fast fallback / Happy Eyeballs) no matter what order the resolver returned, so a
     * policy expressed only as DNS ordering is advisory at best. Turn the race off whenever a
     * family has been pinned. See PreferIpv4Dns.
     */
    private fun okhttp3.OkHttpClient.Builder.applyDnsPolicy(): okhttp3.OkHttpClient.Builder = apply {
        dnsPolicy()?.let { dns(it) }
        if (config.preferIpv4 || config.preferIpv6) fastFallback(false)
    }

    suspend fun getRaw(url: String, headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val fullUrl = buildUrl(url)
        val request = okhttp3.Request.Builder()
            .url(fullUrl)
            .apply { for ((k, v) in headers) { addHeader(k, v) } }
            .build()
        val directClient = app.baseClient.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .applyDnsPolicy()
            .build()
        return directClient.newCall(request).execute()
    }

    suspend fun post(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): Document? {
        val fullUrl = buildUrl(url)
        val result = executePostRequest(fullUrl, data, referer, headers, rewriteDomain)
        return result.html?.let { Jsoup.parse(it, fullUrl) }
    }

    suspend fun postText(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): String? {
        val fullUrl = buildUrl(url)
        val result = executePostRequest(fullUrl, data, referer, headers, rewriteDomain)
        return result.html
    }
    
    /**
     * DEBUG: Post request with full result details for troubleshooting
     */
    suspend fun postDebug(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): RequestResult {
        val fullUrl = buildUrl(url)
        return executePostRequest(fullUrl, data, referer, headers, rewriteDomain)
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

    /**
     * Open [url] in a fullscreen WebView the user surfs by hand, and return the video URLs seen on
     * the network. See [SurfSnifferEngine] for why nothing is injected into the page.
     *
     * Runs inside the provider session rather than beside it, which is the point of going through
     * the gateway: the surf inherits the session User-Agent (cookies minted for another UA are
     * refused), starts with the session's cookies already in the system CookieManager so a
     * cf_clearance we already hold is not re-solved by hand, and hands back whatever the surf
     * established so later HTTP calls are not stuck with the older set.
     *
     * @param url start point. For token-gated watch links this must be the freshly minted link.
     * @param referer `Referer` for the first navigation — load-bearing on pages that check it.
     */
    suspend fun surfForVideo(
        url: String,
        referer: String? = null,
        timeoutMs: Long = SurfSnifferEngine.DEFAULT_TIMEOUT_MS,
        graceMs: Long = SurfSnifferEngine.DEFAULT_GRACE_MS
    ): List<SurfSnifferEngine.SurfCapture> {
        ensureInitialized()

        // Hand the WebView the cookies we already have (host + provider domain + aliases), so the
        // user is not asked to re-solve a challenge this session already passed.
        val targetHost = extractDomain(url)
        if (sessionState.cookies.isNotEmpty()) {
            if (targetHost.isNotBlank()) syncCookiesToSystemCookieManager(targetHost, sessionState.cookies)
            syncCookiesToSystemCookieManager(sessionState.domain, sessionState.cookies)
            for (alias in SessionProvider.getDomainAliases()) {
                syncCookiesToSystemCookieManager(alias, sessionState.cookies)
            }
        }

        val result = surfSnifferEngine.surf(
            url = url,
            userAgent = sessionState.userAgent,
            referer = referer,
            timeoutMs = timeoutMs,
            graceMs = graceMs
        )

        val captures = when (result) {
            is SurfSnifferEngine.SurfResult.Found -> result.captures
            // A dismiss or timeout right after playback began still leaves usable hits.
            is SurfSnifferEngine.SurfResult.Cancelled -> result.partial
            is SurfSnifferEngine.SurfResult.Timeout -> result.partial
            is SurfSnifferEngine.SurfResult.Error -> emptyList()
        }

        // Fold whatever the surf collected back into the session. Merge, never replace: the surf
        // browses third-party embed domains, and its cookie jar for those must not evict the
        // provider-domain set we arrived with.
        try {
            val surfed = android.webkit.CookieManager.getInstance().getCookie(url)
            if (!surfed.isNullOrBlank()) {
                val parsed = surfed.split(";").mapNotNull { pair ->
                    val trimmed = pair.trim()
                    val name = trimmed.substringBefore("=", "")
                    val value = trimmed.substringAfter("=", "")
                    if (name.isBlank()) null else name to value
                }.toMap()
                if (parsed.isNotEmpty()) updateCookies(parsed, fromWebView = false)
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG_PROVIDER_HTTP, "surfForVideo",
                "Could not fold surf cookies back into the session", "error" to e.message)
        }

        ProviderLogger.i(TAG_PROVIDER_HTTP, "surfForVideo", "Surf finished",
            "result" to result::class.simpleName, "captures" to captures.size,
            "first" to captures.firstOrNull()?.url?.take(100))

        return captures
    }

    /**
     * Execute a multi-step WebView navigation flow with trusted touch simulation.
     *
     * Simulates real user interactions (load URL, click elements, wait for
     * conditions, extract HTML) with isTrusted=true touch events.
     * Ideal for sites with anti-bot protection that requires real user flow.
     *
     * @param steps Ordered list of navigation steps to execute
     * @param mode HEADLESS (no UI) or FULLSCREEN (visible dialog)
     * @param overallTimeoutMs Maximum time for the entire flow
     * @param requestInterceptor Optional interceptor for shouldInterceptRequest
     * @return NavigationResult with cookies, extracted HTML, and step completion info
     */
    suspend fun navigateWithSteps(
        steps: List<NavigationStep>,
        mode: Mode = Mode.HEADLESS,
        overallTimeoutMs: Long = 120_000L,
        requestInterceptor: ((android.webkit.WebView, android.webkit.WebResourceRequest) -> android.webkit.WebResourceResponse?)? = null,
        allowedDomains: Set<String> = emptySet(),
        destinationLockPatterns: List<Regex> = emptyList()
    ): NavigationResult {
        return navigationEngine.execute(
            steps = steps,
            userAgent = sessionState.userAgent,
            mode = mode,
            overallTimeoutMs = overallTimeoutMs,
            requestInterceptor = requestInterceptor,
            allowedDomains = allowedDomains,
            destinationLockPatterns = destinationLockPatterns
        )
    }

    /**
     * Validate media URLs before handing them to ExoPlayer.
     *
     * Tests each URL with OkHttp (same TLS stack as ExoPlayer) to detect
     * CDN 403 blocks caused by TLS fingerprint mismatch. Returns results
     * indicating which sources are accessible and which need WebView playback.
     *
     * @param sources Video sources to validate (URL + headers)
     * @return List of validated sources with accessibility status
     */
    suspend fun validateMediaUrls(sources: List<VideoSource>): List<ValidatedSource> {
        if (sources.isEmpty()) return emptyList()

        val candidates = sources.map { vs ->
            VideoSourceCandidate(
                url = vs.url,
                quality = vs.quality,
                headers = vs.headers
            )
        }

        val validated = mediaValidator.validateSources(candidates, sessionState)

        val blocked = validated.count { it.tlsBlocked }
        if (blocked > 0) {
            ProviderLogger.w(TAG_PROVIDER_HTTP, "validateMediaUrls",
                "⚠️ TLS-blocked media URLs detected",
                "total" to sources.size,
                "blocked" to blocked,
                "accessible" to (sources.size - blocked))
        }

        return validated
    }

    /**
     * Quick check: can ExoPlayer reach this media URL?
     * Returns false if TLS fingerprint mismatch will cause 403.
     */
    suspend fun isMediaAccessible(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        return mediaValidator.isAccessible(url, headers, sessionState)
    }

    /**
     * Fetch a URL using Chrome's TLS stack (WebView-based).
     * Use when OkHttp is TLS-blocked but you need the content programmatically.
     */
    suspend fun fetchViaChromeTls(url: String, headers: Map<String, String> = emptyMap()): String? {
        val allHeaders = sessionState.buildHeaders().toMutableMap()
        allHeaders.putAll(headers)
        val response = chromiumFetcher.fetch(url, allHeaders)
        if (response.success && response.cookies.isNotEmpty()) {
            updateCookies(response.cookies, fromWebView = true)
        }
        return if (response.success) response.body else null
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

    /**
     * A just-fetched page, kept briefly so a second consumer does not refetch it.
     *
     * `load()` and `loadLinks()` request the same detail URL seconds apart, sequentially — the
     * RequestQueue's leader/follower dedup only coalesces *concurrent* requests, so it cannot help.
     * On a domain whose TLS fingerprint Cloudflare rejects, each of those fetches pays a failed
     * OkHttp attempt plus a full Chrome-TLS WebView fetch (~830 ms measured), so the second one is
     * pure waste.
     *
     * Deliberately opt-in per call site ([getDocument]'s `allowCached`), never on by default:
     * `shared` is used by ~40 providers and a page cache is exactly the kind of thing that turns
     * into a stale-content bug somewhere unrelated. Writes happen for everyone; only readers who
     * ask get a hit.
     */
    private class CachedPage(val html: String, val finalUrl: String, val atMs: Long)

    private val recentPages = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedPage>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPage>) = size > 8
        }
    )

    /** Long enough to bridge a load()→play tap, short enough that a stale token is refetched. */
    private val pageCacheTtlMs = 45_000L

    private fun cachedPage(url: String): CachedPage? {
        val hit = recentPages[url] ?: return null
        if (System.currentTimeMillis() - hit.atMs > pageCacheTtlMs) {
            recentPages.remove(url)
            return null
        }
        return hit
    }

    suspend fun getDocument(
        url: String,
        headers: Map<String, String> = emptyMap(),
        checkDomainChange: Boolean = false,
        rewriteDomain: Boolean = false,
        /**
         * Accept a page fetched moments ago instead of refetching. Only pass true where a stale-by-
         * seconds page is definitely acceptable — a detail page being re-read to extract watch
         * links, not a listing being refreshed.
         */
        allowCached: Boolean = false
    ): Document? {
        if (allowCached) {
            cachedPage(url)?.let { hit ->
                ProviderLogger.d(TAG_PROVIDER_HTTP, "getDocument",
                    "Reusing page fetched ${System.currentTimeMillis() - hit.atMs}ms ago",
                    "url" to url.take(80))
                return Jsoup.parse(hit.html, hit.finalUrl)
            }
        }

        val result = if (rewriteDomain) {
            requestQueue.enqueue(url, headers)
        } else {
            requestQueue.enqueueAction(url) { executeDirectRequest(url, headers, rewriteDomain = false) }
        }
        
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

        // Store only a clean, fully-resolved success: anything that went through a meta-refresh or
        // CF fallback has already returned above, so a cache hit can be handed straight back
        // without replaying that logic.
        if (result.success && doc != null) {
            result.html?.let {
                recentPages[url] = CachedPage(it, result.finalUrl ?: url, System.currentTimeMillis())
            }
        }

        return doc
    }

    /**
     * Like [getDocument] but does NOT fall back to WebView CF solve.
     * Instead, throws [CloudflareBlockedSearchException] if CF is detected.
     * Used by lazy search to avoid WebView popups during global search.
     */
    suspend fun getDocumentNoFallback(url: String, headers: Map<String, String> = emptyMap(), checkDomainChange: Boolean = false, rewriteDomain: Boolean = false): Document? {
        // CRITICAL FIX: Bypass requestQueue to avoid the automatic CF solver loop
        val result = executeDirectRequest(url, headers, rewriteDomain)
        
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

    fun getImageHeadersFull(targetDomain: String? = null): Map<String, String> {
        val domain = targetDomain ?: sessionState.domain
        
        val cookies = if (targetDomain != null && targetDomain != sessionState.domain) {
            com.cloudstream.shared.session.SessionProvider.getCookiesForDomain(targetDomain)
        } else {
            sessionState.cookies
        }
        
        return buildMap {
            put("User-Agent", sessionState.userAgent)
            put("Referer", "https://$domain/")
            put("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            put("Accept-Language", "en-US,en;q=0.9")
            put("Sec-Ch-Ua", WebConfig.buildSecChUa(sessionState.userAgent))
            put("Sec-Ch-Ua-Mobile", "?1")
            put("Sec-Ch-Ua-Platform", "\"Android\"")
            put("Sec-Fetch-Dest", "image")
            put("Sec-Fetch-Mode", "no-cors")
            put("Sec-Fetch-Site", "same-site")
            
            if (cookies.isNotEmpty()) {
                put("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }
    }

    // ==================== INTERNAL ====================

    internal suspend fun executeDirectRequest(url: String, customHeaders: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): RequestResult {
        val targetUrl = if (rewriteDomain) rewriteUrlIfNeeded(url) else url
        return try {
            
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
                .applyDnsPolicy()
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
            
            val result = executeRequestHelper(directClient, okRequest)

            // ── Tier 3 TLS Fallback ──
            // If OkHttp got CF-blocked despite having valid cookies + browser headers,
            // this is a TLS fingerprint block. Retry via ChromiumFetcher (Chrome TLS).
            if (result.isCloudflareBlocked && cookiesForDomain.isNotEmpty()) {
                ProviderLogger.w(TAG_PROVIDER_HTTP, "executeDirectRequest",
                    "🔒 Tier 3 TLS block — retrying via Chrome TLS stack",
                    "url" to targetUrl.take(80))

                val chromiumResponse = chromiumFetcher.fetch(targetUrl, headers)
                if (chromiumResponse.success) {
                    ProviderLogger.i(TAG_PROVIDER_HTTP, "executeDirectRequest",
                        "✅ Chrome TLS fallback succeeded",
                        "url" to targetUrl.take(80),
                        "htmlLength" to chromiumResponse.body.length)

                    // Merge any new cookies from the Chrome response
                    if (chromiumResponse.cookies.isNotEmpty()) {
                        updateCookies(chromiumResponse.cookies, fromWebView = true)
                    }

                    return RequestResult.success(
                        chromiumResponse.body,
                        chromiumResponse.statusCode,
                        chromiumResponse.finalUrl ?: targetUrl
                    )
                } else {
                    ProviderLogger.w(TAG_PROVIDER_HTTP, "executeDirectRequest",
                        "Chrome TLS fallback also failed",
                        "code" to chromiumResponse.statusCode,
                        "error" to (chromiumResponse.error ?: ""))
                }
            }

            result
        } catch (e: Exception) {
            ProviderLogger.e(TAG_PROVIDER_HTTP, "executeDirectRequest", "Failed", e, "url" to targetUrl.take(80))
            RequestResult.failure(e)
        }
    }

    internal suspend fun executePostRequest(url: String, data: Map<String, String>, referer: String? = null, customHeaders: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): RequestResult {
        return try {
            val targetUrl = if (rewriteDomain) rewriteUrlIfNeeded(url) else url
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
                .applyDnsPolicy()
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
            val retryResult = executeDirectRequest(targetUrl, rewriteDomain = true)
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
                // CRITICAL: Do NOT call checkAndUpdateDomain here.
                // CF bypass WebView may navigate to cloudflare.com / challenges.cloudflare.com
                // during the challenge. If we detect a domain change from the CF solve's finalUrl,
                // we'd incorrectly save "cloudflare.com" as the provider domain.
                // Domain detection should only happen on normal HTTP redirects.
                RequestResult.success(result.html, 200, result.finalUrl)
            }
            is WebViewResult.Cancelled -> {
                // User pressed back on CF dialog — return failure cleanly, no side effects
                ProviderLogger.i(TAG_PROVIDER_HTTP, "solveCloudflareThenRequest", "User cancelled CF bypass")
                RequestResult.failure("User cancelled CF bypass")
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
            try {
                val uri = URI(url)
                val host = uri.host
                val rewritten = if (host != null) {
                    url.replace(host, currentDomain)
                } else {
                    url.replace(urlDomain, currentDomain)
                }
                ProviderLogger.d(TAG_PROVIDER_HTTP, "rewriteUrlIfNeeded", "Rewrote URL",
                    "from" to urlDomain, "to" to currentDomain)
                rewritten
            } catch (e: Exception) {
                val rewritten = url.replace(urlDomain, currentDomain)
                ProviderLogger.d(TAG_PROVIDER_HTTP, "rewriteUrlIfNeeded", "Rewrote URL",
                    "from" to urlDomain, "to" to currentDomain)
                rewritten
            }
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
    /**
     * Mirrors the session cookies into the system CookieManager, which is what any WebView reads.
     *
     * Each cookie is written ONCE, host-only for [domain]. A second write scoped to
     * `Domain=.<registrable domain>` was tried and reverted: it produced two cookies with the same
     * name, the browser sent both in one header, and Cloudflare rejected the duplicated
     * `cf_clearance` — the challenge then repeated on every server in a run. The caller loops over
     * known aliases, which covers the hosts that matter.
     *
     * A long `Max-Age` is attached because these were session cookies: they vanished whenever the
     * cookie store was cleared and never survived a restart, so a cleared cache always meant meeting
     * Cloudflare again from scratch.
     */
    private fun syncCookiesToSystemCookieManager(domain: String, cookies: Map<String, String>) {
        try {
            val systemCookieManager = android.webkit.CookieManager.getInstance()
            systemCookieManager.setAcceptCookie(true)
            val cookieUrl = "https://$domain"
            val maxAge = 60L * 60L * 24L * 30L // 30 days; the server's own expiry still wins

            for ((key, value) in cookies) {
                // A value carrying ';' or whitespace would truncate or corrupt the attribute list.
                val safe = value.trim()
                if (safe.contains(';') || safe.any { it.isWhitespace() }) {
                    ProviderLogger.w(TAG_PROVIDER_HTTP, "syncCookiesToSystemCookieManager",
                        "Skipping cookie with unsafe value", "name" to key, "domain" to domain)
                    continue
                }
                // ONE cookie per name. Writing it host-only AND with Domain=.registrable created two
                // cookies with the same name at different scopes, and the browser then sends both in
                // a single header ("cf_clearance=X; cf_clearance=X"). Cloudflare rejects a duplicated
                // clearance, which showed up as the challenge repeating on every server in a run
                // (2026-07-29). The caller already loops over known aliases, so per-host writes cover
                // the hosts that matter; a leading-dot scope is not worth breaking the clearance for.
                systemCookieManager.setCookie(cookieUrl, "$key=$safe; path=/; secure; Max-Age=$maxAge")
            }
            systemCookieManager.flush()
            // Names, not just a count: the open question on this provider is whether a Cloudflare
            // clearance token exists at all, and a bare count can never answer it.
            ProviderLogger.d(TAG_PROVIDER_HTTP, "syncCookiesToSystemCookieManager",
                "Injected ${cookies.size} cookies", "domain" to domain,
                "names" to cookies.keys.joinToString(","),
                "hasClearance" to cookies.keys.any { it.equals("cf_clearance", ignoreCase = true) })
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
                val navigationEngine = NavigationEngine(activityProvider)
                val surfSnifferEngine = SurfSnifferEngine(activityProvider)
                val chromiumFetcher = ChromiumFetcher(activityProvider)

                ProviderHttpService(config, sessionStore, cfBypassEngine, videoSnifferEngine, navigationEngine, surfSnifferEngine, domainManager, cookieManager, parser, chromiumFetcher)
            }
        }
    }
}
