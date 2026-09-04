package com.cloudstream.shared.network

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.webview.WebViewFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONTokener
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
/**
 * Header names the WebView owns for itself. Each is dropped from the `loadUrl` extra headers:
 * the WebView sets them from the Fingerprint UA that `WebViewFactory` installed, and a
 * Kotlin-supplied copy would either be ignored or fight the real one. `Cookie` is dropped here
 * because it goes via `CookieManager` instead. `X-Requested-With` is dropped because
 * `RequestedWithHeaderControl.suppress` owns that policy (an empty value is the detectable
 * mistake documented in `RequestedWithHeaderControl.kt:110-126`).
 *
 * The set covers every identity and fetch-metadata header the browser generates for itself:
 * `Accept`, `Accept-Language`, `Accept-Encoding`, the `sec-ch-ua*` client hints, the whole
 * `Sec-Fetch-*` family and `Upgrade-Insecure-Requests` are all dropped, even when the caller set
 * them deliberately — a `Sec-Fetch-Dest: iframe` supplied here would contradict the value Chromium
 * derives from the actual navigation. What survives is `Referer` plus any header outside this list
 * (caller-specific ones such as `Authorization` or an `X-` API header).
 *
 * Matching is by exact (case-insensitive) name, not by prefix.
 */
private val LOAD_URL_DROPPED_HEADERS = setOf(
    "user-agent",
    "cookie",
    "x-requested-with",
    "accept",
    "accept-language",
    "accept-encoding",
    "sec-ch-ua",
    "sec-ch-ua-mobile",
    "sec-ch-ua-platform",
    "sec-fetch-site",
    "sec-fetch-mode",
    "sec-fetch-dest",
    "sec-fetch-user",
    "upgrade-insecure-requests"
)

/**
 * Narrows a request header map to what may be passed as `WebView.loadUrl` extra headers.
 * `Referer` and caller-specific headers survive; the identity headers in
 * [LOAD_URL_DROPPED_HEADERS] are dropped. Pure — no Android classes touched.
 */
internal fun filterLoadUrlHeaders(headers: Map<String, String>): Map<String, String> =
    headers.filterKeys { it.lowercase() !in LOAD_URL_DROPPED_HEADERS }

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

    /** Cached WebView for reuse within the reuse window */
    @Volatile
    private var cachedWebView: WebView? = null
    @Volatile
    private var lastFetchTime = 0L

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
            if (activity == null) {
                ProviderLogger.e(TAG, "fetch", "No Activity available")
                return@withContext ChromiumResponse.error("No Activity context")
            }

            val deferred = CompletableDeferred<ChromiumResponse>()
            var delivered = false
            var webViewRef: WebView? = null

            try {
                val webView = getOrCreateWebView(activity)
                webViewRef = webView

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
                            // Carries the URL/host so a bare "code=403" in logcat can be tied back
                            // to a caller and a domain — without it, a burst of these lines is
                            // indistinguishable from an unguarded call site vs. several legitimate,
                            // breaker-guarded fetches for different domains.
                            ProviderLogger.d(TAG, "fetch.onReceivedHttpError",
                                "HTTP error on main frame", "code" to responseCode,
                                "url" to url.take(80), "requestUrl" to request?.url?.toString()?.take(80))
                        }
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (delivered) return
                        val currentUrl = view?.url ?: loadedUrl ?: url

                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                val html = extractHtml(view!!)
                                val cookies = extractCookies(currentUrl)

                                delivered = true
                                lastFetchTime = System.currentTimeMillis()

                                ProviderLogger.i(TAG, "fetch", "Chrome-TLS fetch complete",
                                    "code" to responseCode,
                                    "htmlLength" to html.length,
                                    "cookieCount" to cookies.size,
                                    "finalUrl" to currentUrl.take(80))

                                deferred.complete(ChromiumResponse(
                                    success = responseCode in 200..399,
                                    statusCode = responseCode,
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

                // Build extra headers map (WebView.loadUrl headers): Referer and
                // caller-specific headers only. See LOAD_URL_DROPPED_HEADERS for what is dropped
                // and why. Cookie goes via CookieManager below.
                val extraHeaders = filterLoadUrlHeaders(headers)

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
                // Timed out or cancelled before delivery — stop the in-flight load so the
                // WebView doesn't keep running detached from the (now-gone) caller.
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
        activity: android.app.Activity
    ): WebView {
        val now = System.currentTimeMillis()
        val existing = cachedWebView

        if (existing != null && (now - lastFetchTime) < WEBVIEW_REUSE_WINDOW_MS) {
            // Reuse as-is. The UA is the one WebViewFactory installed from the Fingerprint; there
            // is no per-request UA to re-apply any more.
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

        val webView = WebViewFactory.create(activity).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Block media to speed up page loads (we only want HTML)
                mediaPlaybackRequiresUserGesture = true
                blockNetworkImage = true
                loadsImagesAutomatically = false
            }
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
        get() = statusCode == 403 && (
            body.contains("cloudflare", ignoreCase = true) ||
            body.contains("cf-browser-verification", ignoreCase = true)
        )

    companion object {
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
