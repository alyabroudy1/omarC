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
     * The current session, for a caller that intends to put it back.
     *
     * Exists because a Cloudflare solve is destructive up front: [solveCloudflareThenRequest] calls
     * `invalidateSession` and `clearSystemCookies` *before* opening the WebView, and returns a plain
     * failure if the user backs out of the dialog. Nothing restores what it threw away, so a
     * cancelled solve leaves the provider with fewer cookies than it had when the request started —
     * and a flow that was only *probably* going to need a solve is now definitely broken.
     *
     * Snapshot before anything that can trigger a solve, restore if it came back empty-handed. See
     * `CimaNowSession.withSessionGuard`.
     */
    fun snapshotSession(): SessionState = sessionState

    /**
     * Puts a [snapshotSession] result back, cookies and all.
     *
     * Only meaningful when the current session is *worse* than the snapshot — a solve that succeeded
     * must not be rolled back over, so callers check first. Re-syncs the system CookieManager, since
     * a failed solve cleared that too.
     *
     * @param reason logged, because a session appearing to travel backwards in time is otherwise
     *   impossible to account for when reading a log.
     */
    @Synchronized
    fun restoreSession(snapshot: SessionState, reason: String) {
        if (snapshot.cookies.isEmpty()) return
        sessionState = snapshot
        sessionStore.save(sessionState)
        SessionProvider.update(sessionState)
        syncCookiesToSystemCookieManager(sessionState.domain, sessionState.cookies)
        for (alias in SessionProvider.getDomainAliases()) {
            syncCookiesToSystemCookieManager(alias, sessionState.cookies)
        }
        ProviderLogger.w(TAG_PROVIDER_HTTP, "restoreSession", "Session restored from snapshot",
            "reason" to reason,
            "cookies" to snapshot.cookies.size,
            "hasClearance" to snapshot.cookies.keys.any { it.equals("cf_clearance", ignoreCase = true) })
    }

    /**
     * Merge cookies a provider obtained out-of-band into the session, as an HTTP response would.
     *
     * For cookies that arrive on a path the service did not drive itself — a WebView response the
     * provider's own interceptor answered, say. Merging (never replacing) so a `cf_clearance` already
     * in hand cannot be dropped by an unrelated `Set-Cookie`.
     */
    fun mergeSessionCookies(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        updateCookies(cookies, fromWebView = false)
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

    /**
     * Applies [ProviderConfig.requestTimeoutMs] when the provider set one.
     *
     * Null leaves CloudStream's `app.baseClient` defaults alone, which is ~10 s — fine for a healthy
     * site and fatal for a slow one. ArabSeed's origin took **40 s to load in a desktop browser**
     * (2026-07-30), so every request died at 10 s: the watch page GET, and all five
     * `get__watch__server/` POSTs in parallel, which surfaced as "No Links Found" ten seconds after
     * pressing play with no fallback attempted.
     *
     * Deliberately opt-in: a longer ceiling also makes genuine failures take longer, and no provider
     * should inherit that because another one is slow.
     *
     * **Use the `(long, TimeUnit)` overloads, never the `java.time.Duration` ones.** The Duration
     * variants compile against the OkHttp we build with and are absent from the one CloudStream ships,
     * so the first version of this threw `NoSuchMethodError: No virtual method
     * connectTimeout(Ljava/time/Duration;)` at runtime and took down every ArabSeed request, including
     * `getMainPage` (2026-07-30). A green build proves nothing about the host app's classpath.
     */
    private fun okhttp3.OkHttpClient.Builder.applyProviderTimeout(): okhttp3.OkHttpClient.Builder = apply {
        config.requestTimeoutMs?.let { ms ->
            val unit = java.util.concurrent.TimeUnit.MILLISECONDS
            connectTimeout(ms, unit)
            readTimeout(ms, unit)
            writeTimeout(ms, unit)
            callTimeout(ms * 2, unit)
        }
    }

    /**
     * A raw [okhttp3.Response] — for callers that need the status line, the headers, or an unparsed
     * body, and will handle the outcome themselves.
     *
     * **This used to send only the headers it was handed**, which made it a trap: a caller passing
     * `User-Agent` and `Accept` got a request with no `cf_clearance`, no client hints, no `Referer` and
     * no Cloudflare handling whatsoever, on a service whose every other method carries the session.
     * CimaNow's token chain hit exactly that (2026-08-03): Cloudflare answered 403 with a 128 KB block
     * page, the caller found no link in it, and the failure surfaced as "the site changed its markup".
     * `load()` had fetched the same URL through [getDocument] seconds earlier and succeeded.
     *
     * So the session **identity** is now attached by default: the cookies for this URL's domain, plus
     * the session `User-Agent` and its matching client hints when the caller did not bring its own UA.
     * Nothing else — `Accept` and `Accept-Language` stay the caller's business, since several callers
     * probe media and JSON endpoints where an HTML `Accept` changes the answer. Caller headers always
     * win, so an explicit `Referer`, `Cookie` or UA overrides the defaults rather than stacking.
     *
     * What this method still cannot do is **solve** a challenge: that means consuming the response and
     * re-issuing it, which would defeat the point of handing back a raw [okhttp3.Response]. It detects
     * one and says so loudly instead. If you see that warning, the fix is to call [getDocument]
     * (`rewriteDomain = true`), which owns the solve-and-retry path.
     *
     * @param useSession pass false for a host that must see an anonymous request — an unauthenticated
     *   CDN probe, or a redirect hop where a stale cookie changes the answer.
     */
    suspend fun getRaw(
        url: String,
        headers: Map<String, String> = emptyMap(),
        useSession: Boolean = true
    ): okhttp3.Response {
        val fullUrl = buildUrl(url)

        val effectiveHeaders = linkedMapOf<String, String>()
        if (useSession) {
            val urlDomain = try { java.net.URL(fullUrl).host } catch (_: Exception) { null }
            val cookiesForDomain = if (urlDomain != null && urlDomain != sessionState.domain) {
                // Returns nothing for a host unrelated to the session, so a third-party request
                // cannot walk off with cf_clearance.
                SessionProvider.getCookiesForDomain(urlDomain)
            } else {
                sessionState.cookies
            }
            if (cookiesForDomain.isNotEmpty()) {
                effectiveHeaders["Cookie"] =
                    cookiesForDomain.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }

            // Identity only — deliberately NOT Accept / Accept-Language.
            //
            // Content negotiation belongs to the caller: several existing callers probe media URLs
            // (`KrmzyProvider` checks an m3u8, `TukTukcima` fetches an Inertia JSON endpoint) without
            // setting `Accept`, and defaulting them to an HTML `Accept` invites a 406 or a different
            // response body for a request that used to work.
            //
            // And the client hints go with the UA or not at all: a caller that supplies its own UA
            // (Krmzy sends a *desktop* Chrome 120) must not be given mobile Android hints derived from
            // the session UA — that mismatch is precisely the fingerprint inconsistency a bot check
            // reads. Either the whole identity is ours, or none of it is.
            val callerSetUserAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
            if (!callerSetUserAgent) {
                effectiveHeaders["User-Agent"] = sessionState.userAgent
                effectiveHeaders["Sec-Ch-Ua"] = WebConfig.buildSecChUa(sessionState.userAgent)
                effectiveHeaders["Sec-Ch-Ua-Mobile"] = "?1"
                effectiveHeaders["Sec-Ch-Ua-Platform"] = "\"Android\""
            }
        }
        // The caller asked for these explicitly; they replace the defaults, never stack with them.
        for ((k, v) in headers) effectiveHeaders[k] = v

        val request = okhttp3.Request.Builder()
            .url(fullUrl)
            .apply { for ((k, v) in effectiveHeaders) { header(k, v) } }
            .build()
        val directClient = app.baseClient.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .applyDnsPolicy()
                .applyProviderTimeout()
            .build()
        val response = directClient.newCall(request).execute()

        // Peeked, not read: `peekBody` buffers a copy and leaves the caller's body intact.
        if (response.code == 403 || response.code == 503 || response.code == 429) {
            val preview = try {
                response.peekBody(CF_PEEK_BYTES).string()
            } catch (_: Exception) { "" }
            if (CloudflareDetector.isBlocked(response.code, preview)) {
                ProviderLogger.e(TAG_PROVIDER_HTTP, "getRaw",
                    "🔒 Cloudflare blocked a getRaw() call — this method cannot solve a challenge, " +
                        "so the body you are about to parse is a block page, not the site. Use " +
                        "getDocument(rewriteDomain = true) for anything behind Cloudflare.",
                    null,
                    "url" to fullUrl.take(100),
                    "code" to response.code.toString(),
                    "sentCookies" to effectiveHeaders.containsKey("Cookie").toString(),
                    "useSession" to useSession.toString())
            } else {
                ProviderLogger.w(TAG_PROVIDER_HTTP, "getRaw", "Non-OK response",
                    "url" to fullUrl.take(100), "code" to response.code.toString())
            }
        }
        return response
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
            val cfBreakerDomain = extractDomain(url)

            // Circuit open: this domain has failed CF-solve repeatedly and recently — don't
            // launch another 30-120s WebView session that's almost certainly going to fail too.
            if (DomainCircuitBreaker.isOpen(cfBreakerDomain)) {
                return doc
            }

            if (config.webViewEnabled) {
                ProviderLogger.w(TAG_PROVIDER_HTTP, "getDocument", "CF blocked - WebView fallback queueing", "url" to url.take(80))

                // CRITICAL FIX: Run the fallback solver through the RequestQueue to respect the domain mutex
                // This prevents parallel search threads from launching simultaneous WebView sessions
                val cfResult = requestQueue.enqueueAction(url) {
                    solveCloudflareThenRequest(url, setOf(
                        extractDomain(url).let { d -> d.split(".").takeLast(2).joinToString(".") }
                    ))
                }

                if (!cfResult.success) {
                    DomainCircuitBreaker.recordFailure(cfBreakerDomain)
                }

                if (cfResult.success && cfResult.html != null) {
                    DomainCircuitBreaker.recordSuccess(cfBreakerDomain)
                    // Cache it, same as the clean path below.
                    //
                    // This used to return without writing `recentPages`, so a CF-solved page was
                    // never remembered — and on a site that yields no clearance cookie there is
                    // nothing else to remember either, so every repeat of the same URL paid for
                    // another WebView session (2026-08-03: one visible dialog per request).
                    // `allowCached` is opt-in per caller, so an extra entry costs nothing to anyone
                    // who does not ask for it.
                    recentPages[url] = CachedPage(
                        cfResult.html, cfResult.finalUrl ?: url, System.currentTimeMillis())
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

        val isCfBlocked = result.isCloudflareBlocked || result.responseCode == 403 ||
            result.html?.contains("403 Forbidden") == true

        // Detect domain redirects, but — mirroring the guard in solveCloudflareThenRequest —
        // never from a CF-blocked response or a resolved host that fails domain validation.
        // A CF challenge can redirect through cloudflare.com / challenges.cloudflare.com on
        // its way to (or instead of) the real site; recording that as the provider domain
        // poisons it permanently (every later request then targets cloudflare.com and 403s).
        if (checkDomainChange && !isCfBlocked) {
            val finalHost = result.finalUrl?.let { extractDomain(it) }
            if (finalHost == null || DomainManager.isValidProviderDomain(finalHost)) {
                checkAndUpdateDomain(url, result.finalUrl)
            } else {
                ProviderLogger.w(TAG_PROVIDER_HTTP, "getDocumentNoFallback",
                    "Skipped domain update — resolved host failed validation",
                    "host" to finalHost, "url" to url.take(80))
            }
        }

        // If CF blocked, throw instead of falling back to WebView
        if (isCfBlocked) {
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
                .applyProviderTimeout()
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
            // www-normalized so this shares failure/success counts with the cfBreakerDomain key
            // used by getDocument()'s CF-solve fallback (extractDomain() below) — otherwise
            // "www.example.com" and "example.com" track two independent circuits for one domain.
            val breakerDomain = (urlDomain ?: sessionState.domain).removePrefix("www.")

            if (!result.isCloudflareBlocked) {
                DomainCircuitBreaker.recordSuccess(breakerDomain)
                return result
            }

            // Circuit open: this domain has failed the WebView fallback chain repeatedly and
            // recently — don't spend another Tier-3 fetch (or later, a full CF solve) on it.
            if (DomainCircuitBreaker.isOpen(breakerDomain)) {
                return result
            }

            // ── Tier 3 TLS Fallback ──
            //
            // A CF block that OkHttp cannot talk its way out of is a **TLS fingerprint** block:
            // Chromium's JA3/JA4 differs from OkHttp's, and no header or cookie changes that.
            // [ChromiumFetcher] re-issues the request through the WebView's own network stack — an
            // invisible, reused WebView, ~1-3 s, no dialog.
            //
            // **It used to require `cookiesForDomain.isNotEmpty()`, which made it unreachable exactly
            // when it was needed.** 2026-08-03, cimanow: every request blocked, session cookie count
            // 0, so the condition was false, so this never ran — and each request fell through to the
            // CF-solve WebView instead, which every provider runs FULLSCREEN
            // (`BaseProvider.skipHeadless = true`). The solve then harvested **zero** cookies
            // (`hasClearance=false`) because the site issues none: it was never challenging us, it was
            // refusing our TLS. So the session stayed empty, the next request repeated it, and the
            // user got a visible WebView per request — several on one page load. A chicken-and-egg:
            // blocked because no cookies, no TLS fallback because no cookies.
            //
            // Cookies are irrelevant to whether a TLS block is worth retrying, so the condition is
            // gone. The cost when it does not help is one invisible fetch on a path that was already
            // heading for a full WebView session.
            if (result.isCloudflareBlocked) {
                ProviderLogger.w(TAG_PROVIDER_HTTP, "executeDirectRequest",
                    "🔒 Tier 3 TLS block — retrying via Chrome TLS stack",
                    "url" to targetUrl.take(80),
                    "hadCookies" to cookiesForDomain.isNotEmpty().toString())

                val chromiumResponse = chromiumFetcher.fetch(targetUrl, headers)
                // Success is a status code, which a block page also has. Check the body too, or a
                // challenge gets handed back as content and parsed as if it were the site.
                val stillBlocked = chromiumResponse.isCloudflareBlocked ||
                    CloudflareDetector.isBlocked(chromiumResponse.statusCode, chromiumResponse.body)
                if (chromiumResponse.success && !stillBlocked) {
                    ProviderLogger.i(TAG_PROVIDER_HTTP, "executeDirectRequest",
                        "✅ Chrome TLS fallback succeeded — no WebView solve needed",
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
                        "Chrome TLS fallback did not get through — leaving it to the CF solve",
                        "code" to chromiumResponse.statusCode,
                        "stillBlocked" to stillBlocked.toString(),
                        "error" to (chromiumResponse.error ?: ""))
                    DomainCircuitBreaker.recordFailure(breakerDomain)
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
                .applyProviderTimeout()
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

        /**
         * How much of a refused body [getRaw] peeks at to recognise a Cloudflare block.
         *
         * Cloudflare's markers are in the `<head>`; a real block page runs to ~128 KB, and buffering
         * that on every 403 to answer a yes/no question would be waste.
         */
        private const val CF_PEEK_BYTES = 32L * 1024L

        /**
         * Per-domain circuit breaker for the WebView/CF-solve fallback chain (ChromiumFetcher
         * Tier 3 + [solveCloudflareThenRequest]). A domain that is genuinely down or
         * definitively blocking us doesn't get better on the 4th, 5th, or 50th retry — each one
         * is a costly WebView session (an invisible Tier-3 fetch, or a full 30-120s CF solve).
         * After a few consecutive hard failures we stop trying for a cooldown window and fail
         * fast instead. Keyed by host, shared across all provider instances in the process.
         */
        internal object DomainCircuitBreaker {
            private const val FAILURE_THRESHOLD = 3
            private const val COOLDOWN_MS = 10 * 60 * 1000L

            private val failureCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
            private val openUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

            /** True if [domain]'s circuit is open — callers should fail fast, no WebView fallback. */
            fun isOpen(domain: String): Boolean {
                if (domain.isBlank()) return false
                val until = openUntil[domain] ?: return false
                if (System.currentTimeMillis() >= until) {
                    // Cooldown elapsed — close the circuit and let the next attempt retry normally.
                    openUntil.remove(domain)
                    failureCounts.remove(domain)
                    return false
                }
                return true
            }

            /** Record a hard 403/CF-solve failure for [domain]; opens the circuit past the threshold. */
            fun recordFailure(domain: String) {
                if (domain.isBlank()) return
                val count = (failureCounts[domain] ?: 0) + 1
                failureCounts[domain] = count
                if (count >= FAILURE_THRESHOLD && openUntil[domain] == null) {
                    openUntil[domain] = System.currentTimeMillis() + COOLDOWN_MS
                    ProviderLogger.w(TAG_PROVIDER_HTTP, "DomainCircuitBreaker",
                        "Circuit opened — failing fast for ${COOLDOWN_MS / 60_000}m",
                        "domain" to domain, "consecutiveFailures" to count)
                }
            }

            /** Reset failure tracking for [domain] after any successful request. */
            fun recordSuccess(domain: String) {
                if (domain.isBlank()) return
                failureCounts.remove(domain)
                openUntil.remove(domain)
            }
        }

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
                val chromiumFetcher = ChromiumFetcher(activityProvider)

                ProviderHttpService(config, sessionStore, cfBypassEngine, videoSnifferEngine, navigationEngine, domainManager, cookieManager, parser, chromiumFetcher)
            }
        }
    }
}
