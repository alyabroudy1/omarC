package com.cloudstream.shared.service

import android.content.Context
import com.cloudstream.shared.cloudflare.CloudflareDetector
import com.cloudstream.shared.domain.DomainManager
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_PROVIDER_HTTP
import com.cloudstream.shared.core.AndroidCookieStorage
import com.cloudstream.shared.core.Fingerprint
import com.cloudstream.shared.core.SystemCookieJar
import com.cloudstream.shared.core.expireCookiesFor
import com.cloudstream.shared.core.FingerprintInterceptor
import com.cloudstream.shared.core.RequestKind
import com.cloudstream.shared.network.ChromiumFetcher
import com.cloudstream.shared.provider.ProviderConfig
import com.cloudstream.shared.core.FetchOutcome
import com.cloudstream.shared.core.classify
import com.cloudstream.shared.core.needsCfSolve
import com.cloudstream.shared.core.bodyOrNull
import com.cloudstream.shared.queue.RequestQueue
import com.cloudstream.shared.session.SessionState
import com.cloudstream.shared.session.SessionProvider
import com.cloudstream.shared.strategy.VideoSource
import com.cloudstream.shared.webview.CfBypassEngine
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.NavigationEngine
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
 * SessionState, CfBypassEngine, VideoSnifferEngine, DomainManager.
 */
class ProviderHttpService private constructor(
    private val config: ProviderConfig,
    private val cfBypassEngine: CfBypassEngine,
    private val videoSnifferEngine: VideoSnifferEngine,
    val navigationEngine: NavigationEngine,
    private val domainManager: DomainManager,
    /** Chrome-TLS HTTP client for Tier 3 TLS fingerprint fallback */
    val chromiumFetcher: ChromiumFetcher
) : RequestQueue.Host {
    @Volatile
    private var sessionState: SessionState = SessionState.initial(config.fallbackDomain)
    
    @Volatile
    private var initialized = false
    private val initMutex = Mutex()

    private val requestQueue = RequestQueue(this)

    // ── RequestQueue.Host ──

    override suspend fun execute(url: String, headers: Map<String, String>): FetchOutcome =
        executeDirectRequest(url, headers, rewriteDomain = true)

    override suspend fun solveCloudflare(url: String, allowedDomains: Set<String>): FetchOutcome =
        solveCloudflareThenRequest(url, allowedDomains)

    override suspend fun onDomainRedirect(oldHost: String, newHost: String) {
        updateDomain(newHost)
        domainManager.updateDomain(newHost)
        domainManager.syncToRemote()
    }

    override val currentDomain: String
        get() = sessionState.domain

    val mainUrl: String
        get() = "https://$currentDomain"
    
    val userAgent: String
        get() = Fingerprint.current().userAgent

    /**
     * The cookies the system store currently holds for the provider's own host, as a map.
     *
     * Kept for providers that build a header by hand; the store, not this service, is the source.
     */
    val cookies: Map<String, String>
        get() = parseCookieHeader(AndroidCookieStorage.get(mainUrl))
        
    val snifferEngine: VideoSnifferEngine
        get() = videoSnifferEngine
    
    suspend fun ensureInitialized() {
        if (initialized) return
        
        initMutex.withLock {
            if (initialized) return@withLock
            
            // A session is a domain now — cookies live in the system store, which persists itself.
            if (SessionProvider.getDomain() == null) {
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
    fun updateDomain(newDomain: String) {
        if (newDomain == sessionState.domain) return
        val oldDomain = sessionState.domain
        
        // CRITICAL: Always preserve cookies on domain change.
        // Domains change unpredictably (faselhd.biz → faselhdx.xyz, arabseed.show → asd.pics)
        // CF cookies are UA-bound, not domain-bound, so they remain valid.
        sessionState = sessionState.withDomain(newDomain)
        SessionProvider.update(sessionState)
        
        // CRITICAL: Register the old domain as an alias.
        // HTML content from the new domain may still reference old-domain URLs
        // (e.g., season/episode links: w312x.faselhdx.xyz when current is w318x).
        // Adding as alias ensures cookies are shared for requests to the old domain.
        SessionProvider.addDomainAlias(oldDomain)
        
        ProviderLogger.i(TAG_PROVIDER_HTTP, "updateDomain", "Domain changed, old domain added as alias",
            "old" to oldDomain, "new" to newDomain, "aliases" to SessionProvider.getDomainAliases().size)
    }
    
    // ==================== PUBLIC API ====================
    
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): String? {
        val fullUrl = buildUrl(url)
        val result = executeDirectRequest(fullUrl, headers, rewriteDomain)
        return result.bodyOrNull()
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

        // Cookies come from the jar on the client, scoped by whatever Set-Cookie said, and
        // identity from FingerprintInterceptor — neither is assembled here any more.
        val effectiveHeaders = linkedMapOf<String, String>()
        // The caller asked for these explicitly; they replace the defaults, never stack with them.
        for ((k, v) in headers) effectiveHeaders[k] = v

        val request = okhttp3.Request.Builder()
            .url(fullUrl)
            .tag(RequestKind::class.java, RequestKind.Subresource)
            .apply { for ((k, v) in effectiveHeaders) { header(k, v) } }
            .build()
        val directClient = app.baseClient.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            // useSession = false means the host must see an anonymous request, so it gets no jar.
            .cookieJar(if (useSession) SystemCookieJar() else okhttp3.CookieJar.NO_COOKIES)
            .addInterceptor(FingerprintInterceptor)
            .applyDnsPolicy()
                .applyProviderTimeout()
            .build()
        val response = directClient.newCall(request).execute()

        // Peeked, not read: `peekBody` buffers a copy and leaves the caller's body intact.
        if (response.code == 403 || response.code == 503 || response.code == 429) {
            val preview = try {
                response.peekBody(CF_PEEK_BYTES).string()
            } catch (_: Exception) { "" }
            val peeked = classify(response.code, preview, fullUrl, response.header("Server"))
            if (peeked is FetchOutcome.CloudflareBlocked) {
                ProviderLogger.e(TAG_PROVIDER_HTTP, "getRaw",
                    "🔒 Cloudflare blocked a getRaw() call — this method cannot solve a challenge, " +
                        "so the body you are about to parse is a block page, not the site. Use " +
                        "getDocument(rewriteDomain = true) for anything behind Cloudflare.",
                    null,
                    "url" to fullUrl.take(100),
                    "code" to response.code.toString(),
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
        return result.bodyOrNull()?.let { Jsoup.parse(it, fullUrl) }
    }

    suspend fun postText(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): String? {
        val fullUrl = buildUrl(url)
        val result = executePostRequest(fullUrl, data, referer, headers, rewriteDomain)
        return result.bodyOrNull()
    }
    
    /**
     * DEBUG: Post request with full result details for troubleshooting
     */
    suspend fun postDebug(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): FetchOutcome {
        val fullUrl = buildUrl(url)
        return executePostRequest(fullUrl, data, referer, headers, rewriteDomain)
    }

    suspend fun sniffVideos(url: String): List<VideoSource> {
        val result = cfBypassEngine.runSession(
            url = url,
            mode = Mode.HEADLESS,
            userAgent = Fingerprint.current().userAgent,
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
                         userAgent = Fingerprint.current().userAgent,
                         exitCondition = ExitCondition.PageLoaded, // Still PageLoaded for CF bypass
                         timeout = 120_000L
                     )
                     if (retry is WebViewResult.Success) {
                         // The WebView wrote its cookies to the system store itself.
                         extractVideoSources(retry.html)
                     } else emptyList()
                } else emptyList()
            }
            else -> emptyList()
        }
        return sources.distinctBy { it.url }
    }

    /**
     * Fetch a URL using Chrome's TLS stack (WebView-based).
     * Use when OkHttp is TLS-blocked but you need the content programmatically.
     */
    suspend fun fetchViaChromeTls(url: String, headers: Map<String, String> = emptyMap()): String? {
        val allHeaders = mutableMapOf<String, String>("Referer" to "https://${sessionState.domain}/")
        allHeaders.putAll(headers)
        // The fetch runs in a WebView, so its cookies are already in the system store.
        val response = chromiumFetcher.fetch(url, allHeaders)
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
        
        val success = result as? FetchOutcome.Success

        if (success != null && checkDomainChange) {
            checkAndUpdateDomain(url, success.finalUrl)
        }
        
        // A non-CF error page is still handed to the caller's parser, as it always was.
        val body = success?.html ?: (result as? FetchOutcome.HttpError)?.body
        val doc = body?.let { Jsoup.parse(it, success?.finalUrl ?: url) }
        
        // Check for meta-refresh domain redirect (e.g., LaRoza returns 200 + meta-refresh)
        if (doc != null && success != null) {
            val redirected = handleMetaRefreshRedirect(doc, success.finalUrl)
            if (redirected != null) return redirected
        }
        
        // Only fall back to WebView for a real Cloudflare block that no solve has been spent on
        // yet: a non-CF 403 (like Akwam's anti-bot) never reaches here, and a block the queue
        // already tried to solve carries solveAttempted = true, which prevents a thundering herd.
        if (result.needsCfSolve()) {
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

                if (cfResult !is FetchOutcome.Success) {
                    DomainCircuitBreaker.recordFailure(cfBreakerDomain)
                }

                if (cfResult is FetchOutcome.Success) {
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
                        cfResult.html, cfResult.finalUrl, System.currentTimeMillis())
                    return Jsoup.parse(cfResult.html, cfResult.finalUrl)
                }
            }
        }

        // Store only a clean, fully-resolved success: anything that went through a meta-refresh or
        // CF fallback has already returned above, so a cache hit can be handed straight back
        // without replaying that logic.
        if (success != null && doc != null) {
            recentPages[url] = CachedPage(success.html, success.finalUrl, System.currentTimeMillis())
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

        val isCfBlocked = result is FetchOutcome.CloudflareBlocked ||
            (result is FetchOutcome.HttpError &&
                (result.code == 403 || result.body?.contains("403 Forbidden") == true))

        // Detect domain redirects, but — mirroring the guard in solveCloudflareThenRequest —
        // never from a CF-blocked response or a resolved host that fails domain validation.
        // A CF challenge can redirect through cloudflare.com / challenges.cloudflare.com on
        // its way to (or instead of) the real site; recording that as the provider domain
        // poisons it permanently (every later request then targets cloudflare.com and 403s).
        if (checkDomainChange && !isCfBlocked) {
            val resolvedUrl = (result as? FetchOutcome.Success)?.finalUrl
            val finalHost = resolvedUrl?.let { extractDomain(it) }
            if (finalHost == null || DomainManager.isValidProviderDomain(finalHost)) {
                checkAndUpdateDomain(url, resolvedUrl)
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
        
        val success = result as? FetchOutcome.Success
        // A non-CF error page is still handed to the caller's parser, as it always was.
        val body = success?.html ?: (result as? FetchOutcome.HttpError)?.body
        val doc = body?.let { Jsoup.parse(it, url) }
        
        // Check for meta-refresh domain redirect (e.g., LaRoza returns 200 + meta-refresh)
        if (doc != null && success != null) {
            val redirected = handleMetaRefreshRedirect(doc, success.finalUrl)
            if (redirected != null) return redirected
        }
        
        return doc
    }

    /**
     * One shape for images, on every tier: the device UA, the page as `Referer`, an image `Accept`,
     * and whatever cookies that host has. [getImageHeadersFull] is the same map — the two used to
     * differ by nine headers for no reason anyone recorded.
     */
    fun getImageHeaders(targetDomain: String? = null): Map<String, String> {
        val domain = targetDomain ?: sessionState.domain
        val referer = "https://$domain/"

        return Fingerprint.current().imageHeaders(
            referer = referer,
            cookieHeader = AndroidCookieStorage.get(referer).orEmpty()
        )
    }

    fun getImageHeadersFull(targetDomain: String? = null): Map<String, String> =
        getImageHeaders(targetDomain)

    // ==================== INTERNAL ====================

    internal suspend fun executeDirectRequest(url: String, customHeaders: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): FetchOutcome {
        val targetUrl = if (rewriteDomain) rewriteUrlIfNeeded(url) else url
        return try {
            
            // Check if URL domain is an alias and get appropriate cookies
            val urlDomain = try {
                java.net.URL(targetUrl).host
            } catch (e: Exception) {
                null
            }
            
            // Caller-specific headers only — identity comes from FingerprintInterceptor and
            // cookies from the jar on the client below.
            val headers = mutableMapOf(
                "Referer" to "https://${urlDomain ?: sessionState.domain}/"
            )
            
            // Add custom headers
            for ((k, v) in customHeaders) {
                headers[k] = v
            }
            
            ProviderLogger.d(TAG_PROVIDER_HTTP, "executeDirectRequest", "Executing HTTP request",
                "url" to targetUrl.take(80),
                "urlDomain" to (urlDomain ?: "same"),
                "sessionDomain" to sessionState.domain,
                "isAlias" to (urlDomain != null && urlDomain != sessionState.domain)
            )
            
            val directClient = app.baseClient.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .cookieJar(SystemCookieJar())
                .addInterceptor(FingerprintInterceptor)
                .applyDnsPolicy()
                .applyProviderTimeout()
                .build()

            val headerBuilder = okhttp3.Headers.Builder()
            for ((k, v) in headers) {
                headerBuilder.add(k, v)
            }

            val okRequest = okhttp3.Request.Builder()
                .url(targetUrl)
                .tag(RequestKind::class.java, RequestKind.Document)
                .headers(headerBuilder.build())
                .get()
                .build()
            
            val result = executeRequestHelper(directClient, okRequest)
            // www-normalized so this shares failure/success counts with the cfBreakerDomain key
            // used by getDocument()'s CF-solve fallback (extractDomain() below) — otherwise
            // "www.example.com" and "example.com" track two independent circuits for one domain.
            val breakerDomain = (urlDomain ?: sessionState.domain).removePrefix("www.")

            if (result !is FetchOutcome.CloudflareBlocked) {
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
            ProviderLogger.w(TAG_PROVIDER_HTTP, "executeDirectRequest",
                "🔒 Tier 3 TLS block — retrying via Chrome TLS stack",
                "url" to targetUrl.take(80))

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

                return FetchOutcome.Success(
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

            result
        } catch (e: Exception) {
            ProviderLogger.e(TAG_PROVIDER_HTTP, "executeDirectRequest", "Failed", e, "url" to targetUrl.take(80))
            FetchOutcome.Transport(e)
        }
    }

    internal suspend fun executePostRequest(url: String, data: Map<String, String>, referer: String? = null, customHeaders: Map<String, String> = emptyMap(), rewriteDomain: Boolean = false): FetchOutcome {
        return try {
            val targetUrl = if (rewriteDomain) rewriteUrlIfNeeded(url) else url
            // Caller-specific headers only — identity comes from FingerprintInterceptor.
            val headers = mutableMapOf<String, String>()
            headers["Referer"] = referer ?: "https://${sessionState.domain}/"
            for ((k, v) in customHeaders) {
                headers[k] = v
            }
            
            ProviderLogger.d(TAG_PROVIDER_HTTP, "executePostRequest", "Executing POST request",
                "url" to targetUrl.take(80),
                "domain" to sessionState.domain
            )

            val formBody = okhttp3.FormBody.Builder().apply {
                for ((k, v) in data) {
                    add(k, v)
                }
            }.build()

            val directClient = app.baseClient.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .cookieJar(SystemCookieJar())
                .addInterceptor(FingerprintInterceptor)
                .applyDnsPolicy()
                .applyProviderTimeout()
                .build()

            val headerBuilder = okhttp3.Headers.Builder()
            for ((k, v) in headers) {
                headerBuilder.add(k, v)
            }

            val okRequest = okhttp3.Request.Builder()
                .url(targetUrl)
                .tag(RequestKind::class.java, RequestKind.Document)
                .headers(headerBuilder.build())
                .post(formBody)
                .build()

            executeRequestHelper(directClient, okRequest)
        } catch (e: Exception) {
            FetchOutcome.Transport(e)
        }
    }

    private fun executeRequestHelper(client: okhttp3.OkHttpClient, request: okhttp3.Request): FetchOutcome {
        val response = client.newCall(request).execute()
        val code = response.code
        val html = response.body?.string() ?: ""
        val finalUrl = response.request.url.toString()
        val serverHeader = response.header("Server")
        
        // Set-Cookie ingestion is SystemCookieJar's job, on every client built here.
        response.close()
        
        return classify(code, html, finalUrl, serverHeader)
    }
    
    internal suspend fun solveCloudflareThenRequest(url: String, allowedDomains: Set<String> = emptySet()): FetchOutcome {
        if (!config.webViewEnabled) {
            // No WebView means no solve is possible here — mark it attempted so the caller's
            // fallback gate does not queue another one.
            return FetchOutcome.CloudflareBlocked(403, url, solveAttempted = true)
        }
        
        val targetUrl = rewriteUrlIfNeeded(url)
        
        // A clearance is already in the store (another queue or caller may have just solved), so
        // spend one direct request on it before throwing it away; if it is stale the retry is CF-
        // blocked and the solve proceeds.
        if (shouldRetryBeforeSolve(AndroidCookieStorage.get(targetUrl))) {
            val retry = executeDirectRequest(targetUrl, rewriteDomain = true)
            if (retry is FetchOutcome.Success) {
                ProviderLogger.i(TAG_PROVIDER_HTTP, "solveCloudflareThenRequest",
                    "Existing clearance worked — skipping CF solve")
                return retry
            }
        }
        
        // A stale clearance is what the challenge objects to, so expire the cookies for THIS host
        // before solving. Only this host: another provider's session lives in the same store.
        expireCookiesFor(targetUrl, AndroidCookieStorage)
        
        val mode = if (config.skipHeadless) Mode.FULLSCREEN else Mode.HEADLESS
        
        val result = cfBypassEngine.runSession(
            url = targetUrl,
            mode = mode,
            userAgent = Fingerprint.current().userAgent,
            exitCondition = ExitCondition.PageLoaded,
            timeout = if (mode == Mode.FULLSCREEN) 120_000L else 30_000L,
            allowedDomains = allowedDomains
        )
        
        return when (result) {
            is WebViewResult.Success -> {
                // The solve ran in a WebView, which wrote its cookies to the system store itself.
                // CRITICAL: Do NOT call checkAndUpdateDomain here.
                // CF bypass WebView may navigate to cloudflare.com / challenges.cloudflare.com
                // during the challenge. If we detect a domain change from the CF solve's finalUrl,
                // we'd incorrectly save "cloudflare.com" as the provider domain.
                // Domain detection should only happen on normal HTTP redirects.
                FetchOutcome.Success(result.html, 200, result.finalUrl)
            }
            is WebViewResult.Cancelled -> {
                // User pressed back on CF dialog — return failure cleanly, no side effects
                ProviderLogger.i(TAG_PROVIDER_HTTP, "solveCloudflareThenRequest", "User cancelled")
                FetchOutcome.Cancelled
            }
            // The solve ran and did not get through: a further solve for this URL is pointless.
            else -> FetchOutcome.CloudflareBlocked(403, targetUrl, solveAttempted = true)
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
        return if (result is FetchOutcome.Success) {
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
    
    /** Parses a `"a=1; b=2"` store header into a map, for [cookies]. */
    private fun parseCookieHeader(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        return header.split(";").mapNotNull { pair ->
            val name = pair.substringBefore("=").trim()
            val value = pair.substringAfter("=", "").trim()
            if (name.isEmpty()) null else name to value
        }.toMap()
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
            activityProvider: () -> android.app.Activity?
        ): ProviderHttpService {
            // Build the one process-wide fingerprint from the real system WebView.
            Fingerprint.current()
            
            return instances.getOrPut(config.name) {
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

                ProviderHttpService(config, cfBypassEngine, videoSnifferEngine, navigationEngine, domainManager, chromiumFetcher)
            }
        }
    }
}

/**
 * True iff [cookieHeader] carries a cookie *named* `cf_clearance`: the one signal that a solve may
 * already have happened, so a direct request is worth trying before expiring and re-solving.
 * Top-level on purpose — no Android type is touched, so the decision is testable on the JVM.
 */
internal fun shouldRetryBeforeSolve(cookieHeader: String?): Boolean {
    if (cookieHeader.isNullOrBlank()) return false
    return cookieHeader.split(";").any { it.substringBefore("=").trim() == "cf_clearance" }
}
