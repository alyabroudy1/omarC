package com.cloudstream.shared.network

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.cloudstream.shared.cloudflare.CloudflareDetector
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.util.WebConfig
import com.cloudstream.shared.webview.RequestedWithHeaderControl
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP client that uses Android WebView's Chromium stack for requests.
 *
 * PURPOSE: Guarantees Chrome-identical TLS fingerprint (JA3/JA4) for HTTP requests.
 * OkHttp produces a distinct TLS handshake that CDNs like Cloudflare can fingerprint
 * and reject (Tier 3 blocks). This client routes requests through Chromium's networking
 * stack, producing the same JA3 fingerprint as a real Chrome browser.
 *
 * USAGE: Fallback client when OkHttp gets a 403 that persists after cookie/header
 * parity is verified (indicating a TLS-level block).
 *
 * CONSTRAINTS:
 * - Must run on the Main thread (Android WebView requirement)
 * - Higher latency than OkHttp (~1-3s per request)
 * - Not suitable for streaming media (use CroNet DataSource for ExoPlayer)
 * - One request at a time per instance (serialized via mutex)
 */
class ChromiumFetcher(
    private val activityProvider: () -> android.app.Activity?
) {
    companion object {
        private const val TAG = "ChromiumFetcher"

        /** Max time to wait for a single fetch (ms) */
        private const val DEFAULT_TIMEOUT_MS = 15_000L

        /** Reuse threshold — don't create a new WebView if we fetched within this window */
        private const val WEBVIEW_REUSE_WINDOW_MS = 30_000L
    }

    private val fetchMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Domains where a passive Chrome-TLS fetch without `cf_clearance` received a 403/503 challenge.
     * Subsequent requests without a clearance cookie skip [ChromiumFetcher] and proceed directly
     * to `CfBypassEngine`, eliminating the redundant double-WebView penalty on TV.
     */
    private val domainsRequiringActiveSolve: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Cached WebView for reuse within the reuse window */
    @Volatile
    private var cachedWebView: WebView? = null
    @Volatile
    private var lastFetchTime = 0L

    /**
     * Returns true if [domain] already rejected a passive Chrome-TLS fetch and no `cf_clearance`
     * cookie is currently available, meaning an active `CfBypassEngine` solve is required.
     */
    fun requiresActiveSolve(domain: String, hasClearanceCookie: Boolean): Boolean {
        if (hasClearanceCookie || domain.isBlank()) return false
        return domainsRequiringActiveSolve.contains(domain.removePrefix("www."))
    }

    /**
     * Clears the active-solve requirement for [domain] after `CfBypassEngine` succeeds.
     */
    fun markChallengeSolved(domain: String) {
        if (domain.isNotBlank()) {
            domainsRequiringActiveSolve.remove(domain.removePrefix("www."))
        }
    }

    /**
     * Fetch a URL using Chromium's TLS stack.
     *
     * @param url The URL to fetch
     * @param headers Custom headers (User-Agent, Cookie, Referer, etc.)
     * @param timeout Max time to wait in milliseconds
     * @return [ChromiumResponse] with status, body, cookies, and final URL
     */
    suspend fun fetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Long = DEFAULT_TIMEOUT_MS
    ): ChromiumResponse = fetchMutex.withLock {
        withContext(Dispatchers.Main) {
            val activity = activityProvider()
            if (activity == null || activity.isFinishing ||
                (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)
            ) {
                ProviderLogger.e(TAG, "fetch", "No active Activity available")
                return@withContext ChromiumResponse.error("No active Activity context")
            }

            val deferred = CompletableDeferred<ChromiumResponse>()
            var delivered = false
            var webViewRef: WebView? = null

            try {
                val webView = getOrCreateWebView(activity, headers)
                webViewRef = webView
                try { webView.stopLoading() } catch (_: Exception) {}

                ProviderLogger.d(TAG, "fetch", "Starting Chrome-TLS fetch",
                    "url" to url.take(80), "headerCount" to headers.size)

                // Intercept the response to capture status code
                webView.webViewClient = object : WebViewClient() {
                    private var responseCode = 200

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        if (request?.isForMainFrame == true) {
                            responseCode = errorResponse?.statusCode ?: -1
                            val failedUrl = request.url?.toString() ?: url
                            ProviderLogger.d(TAG, "fetch.onReceivedHttpError",
                                "HTTP error on main frame", "code" to responseCode,
                                "url" to url.take(80), "requestUrl" to failedUrl.take(80))

                            // Fail-fast on Cloudflare HTTP challenge/block codes (403/503/429):
                            // ChromiumFetcher is a passive single-shot fetcher whose success criterion is
                            // `responseCode in 200..399`. Waiting for `onPageFinished` on a 403/503 wastes
                            // 5–15s on TV compiling and executing Turnstile challenge scripts only to return
                            // `success = false` and launch a second WebView in `CfBypassEngine`.
                            if (!delivered && CloudflareDetector.isCloudflareResponse(responseCode)) {
                                delivered = true
                                lastFetchTime = System.currentTimeMillis()
                                val host = try { URI(failedUrl).host?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }
                                if (host.isNotBlank()) {
                                    domainsRequiringActiveSolve.add(host)
                                }
                                try {
                                    view?.webViewClient = WebViewClient()
                                    view?.stopLoading()
                                } catch (_: Exception) {}
                                deferred.complete(ChromiumResponse.cloudflareBlocked(responseCode, failedUrl))
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (delivered || loadedUrl == "about:blank") return
                        val currentUrl = view?.url ?: loadedUrl ?: url

                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                val html = extractHtml(view!!)
                                val cookies = extractCookies(currentUrl)

                                if (delivered) return@launch
                                delivered = true
                                lastFetchTime = System.currentTimeMillis()

                                // On Android 5.0/5.1 (API 21–22), `onReceivedHttpError` (API 23+) is not
                                // invoked by the system WebView, so `responseCode` stays 200 even on a 403
                                // challenge page. Detect CF challenge markers in HTML to handle pre-API-23 TVs.
                                val isChallengeHtml = CloudflareDetector.isCloudflareChallenge(html, responseCode)
                                if (isChallengeHtml || CloudflareDetector.isCloudflareResponse(responseCode)) {
                                    val host = try { URI(currentUrl).host?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }
                                    if (host.isNotBlank()) {
                                        domainsRequiringActiveSolve.add(host)
                                    }
                                }

                                val effectiveCode = if (isChallengeHtml && responseCode in 200..399) 403 else responseCode

                                ProviderLogger.i(TAG, "fetch", "Chrome-TLS fetch complete",
                                    "code" to effectiveCode,
                                    "htmlLength" to html.length,
                                    "cookieCount" to cookies.size,
                                    "finalUrl" to currentUrl.take(80))

                                deferred.complete(ChromiumResponse(
                                    success = effectiveCode in 200..399 && !isChallengeHtml,
                                    statusCode = effectiveCode,
                                    body = html,
                                    cookies = cookies,
                                    finalUrl = currentUrl
                                ))
                            } catch (e: Exception) {
                                if (!delivered) {
                                    delivered = true
                                    deferred.complete(ChromiumResponse.error(e.message ?: "HTML extraction failed"))
                                }
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true && !delivered) {
                            val desc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                error?.description?.toString()
                            } else error?.toString()

                            delivered = true
                            ProviderLogger.w(TAG, "fetch.onReceivedError", "Network error",
                                "description" to desc, "url" to url.take(80))
                            deferred.complete(ChromiumResponse.error("Network error: $desc"))
                        }
                    }
                }

                // Build extra headers map via RequestedWithHeaderControl so we never send
                // the `X-Requested-With: ""` bot signature or conflict with profile-level control.
                val extraHeaders = RequestedWithHeaderControl.sanitizeLoadHeaders(headers)

                // Inject cookies via CookieManager (WebView ignores Cookie header in loadUrl)
                headers["Cookie"]?.let { cookieHeader ->
                    val cm = CookieManager.getInstance()
                    cookieHeader.split(";").forEach { cookie ->
                        val trimmed = cookie.trim()
                        if (trimmed.isNotEmpty()) {
                            cm.setCookie(url, "$trimmed; Path=/; Secure")
                        }
                    }
                    cm.flush()
                }

                webView.loadUrl(url, extraHeaders)

                // Structured timeout: cancelling this suspension (on timeout, or because the
                // caller itself was cancelled) no longer leaves a detached delay() coroutine
                // running independent of the fetch — it was previously a bare
                // `CoroutineScope(Dispatchers.Main).launch { delay(timeout) }` that outlived
                // caller cancellation and kept completing the (already-abandoned) deferred.
                val response = withTimeoutOrNull(timeout) { deferred.await() }
                if (response != null) {
                    response
                } else {
                    delivered = true
                    ProviderLogger.w(TAG, "fetch", "Timeout after ${timeout}ms", "url" to url.take(80))
                    ChromiumResponse.timeout(url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!delivered) {
                    delivered = true
                }
                ChromiumResponse.error(e.message ?: "Unknown error")
            } finally {
                // Detach client and stop any in-flight load if timed out or cancelled before delivery.
                try { webViewRef?.webViewClient = WebViewClient() } catch (_: Exception) {}
                if (!deferred.isCompleted) {
                    try { webViewRef?.stopLoading() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Lightweight HEAD-like check: loads the URL and returns whether it's accessible.
     * Faster than full fetch — stops loading as soon as we get status code.
     */
    suspend fun isAccessible(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Long = 8_000L
    ): Boolean {
        val response = fetch(url, headers, timeout)
        return response.success
    }

    /**
     * Get or create a WebView instance. Reuses the cached instance if within the reuse window.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(
        activity: android.app.Activity,
        headers: Map<String, String>
    ): WebView {
        val now = System.currentTimeMillis()
        val existing = cachedWebView

        if (existing != null && existing.context === activity && (now - lastFetchTime) < WEBVIEW_REUSE_WINDOW_MS) {
            // Reuse — just update UA if needed
            val ua = headers["User-Agent"] ?: WebConfig.getCachedUserAgent()
            if (existing.settings.userAgentString != ua) {
                existing.settings.userAgentString = ua
                RequestedWithHeaderControl.suppress(existing)
            }
            return existing
        }

        // Create fresh WebView
        existing?.let { old ->
            try {
                old.stopLoading()
                old.loadUrl("about:blank")
                old.destroy()
            } catch (_: Exception) {}
        }

        val webView = WebView(activity).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = headers["User-Agent"] ?: WebConfig.getCachedUserAgent()
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Block media to speed up page loads (we only want HTML)
                mediaPlaybackRequiresUserGesture = true
                blockNetworkImage = true
                loadsImagesAutomatically = false
            }
            // Suppress X-Requested-With: <package> and spoof Chrome sec-ch-ua brands globally
            RequestedWithHeaderControl.suppress(this)
        }

        // Anti-bot spoofing
        webView.evaluateJavascript("""
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                } catch(e) {}
                
                // DisableDevtool Anti-Bot Bypass
                try {
                    var originalDisableDevtool;
                    Object.defineProperty(window, 'DisableDevtool', {
                        get: function() {
                            return function(options) {
                                options = options || {};
                                options.ignore = function() { return true; };
                                options.url = "";
                                options.timeOutUrl = "";
                                options.ondevtoolopen = function() {};
                                if (originalDisableDevtool) {
                                    try {
                                        return originalDisableDevtool(options);
                                    } catch(err) {}
                                }
                            };
                        },
                        set: function(val) {
                            originalDisableDevtool = val;
                        },
                        configurable: true
                    });
                } catch(e) {}
            })();
        """.trimIndent(), null)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        cachedWebView = webView
        return webView
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun extractHtml(webView: WebView): String = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(
                "(function() { return document.documentElement.outerHTML; })();"
            ) { result ->
                val html = try {
                    if (result == null || result == "null") ""
                    else JSONTokener(result).nextValue().toString()
                } catch (e: Exception) { "" }
                if (cont.isActive) cont.resume(html) {}
            }
        }
    }

    private fun extractCookies(url: String): Map<String, String> {
        return try {
            val raw = CookieManager.getInstance().getCookie(url) ?: return emptyMap()
            raw.split(";").associate { part ->
                val kv = part.split("=", limit = 2)
                (kv.getOrNull(0)?.trim() ?: "") to (kv.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() }
        } catch (_: Exception) { emptyMap() }
    }

    /**
     * Release the cached WebView. Call when the provider is being torn down.
     */
    fun release() {
        try {
            cachedWebView?.let { wv ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.destroy()
                    } catch (_: Exception) {}
                }
            }
            cachedWebView = null
        } catch (_: Exception) {}
    }
}

/**
 * Response from a ChromiumFetcher request.
 */
data class ChromiumResponse(
    val success: Boolean,
    val statusCode: Int,
    val body: String,
    val cookies: Map<String, String>,
    val finalUrl: String?,
    val error: String? = null
) {
    val isCloudflareBlocked: Boolean
        get() = (CloudflareDetector.isCloudflareResponse(statusCode) && (
            body.isEmpty() ||
            body.contains("cloudflare", ignoreCase = true) ||
            body.contains("cf-browser-verification", ignoreCase = true) ||
            CloudflareDetector.isCloudflareChallenge(body, statusCode)
        )) || CloudflareDetector.isCloudflareChallenge(body, statusCode)

    companion object {
        fun cloudflareBlocked(statusCode: Int, finalUrl: String?) = ChromiumResponse(
            success = false, statusCode = statusCode, body = "", cookies = emptyMap(),
            finalUrl = finalUrl, error = "HTTP $statusCode (Cloudflare challenge)"
        )

        fun error(message: String) = ChromiumResponse(
            success = false, statusCode = -1, body = "", cookies = emptyMap(),
            finalUrl = null, error = message
        )

        fun timeout(url: String) = ChromiumResponse(
            success = false, statusCode = -2, body = "", cookies = emptyMap(),
            finalUrl = url, error = "Timeout"
        )
    }
}
