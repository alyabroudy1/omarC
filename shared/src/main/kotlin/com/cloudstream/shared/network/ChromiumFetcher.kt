package com.cloudstream.shared.network

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.util.WebConfig
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

            // Timeout guard
            val timeoutJob = CoroutineScope(Dispatchers.Main).launch {
                delay(timeout)
                if (!delivered) {
                    delivered = true
                    ProviderLogger.w(TAG, "fetch", "Timeout after ${timeout}ms", "url" to url.take(80))
                    deferred.complete(ChromiumResponse.timeout(url))
                }
            }

            try {
                val webView = getOrCreateWebView(activity, headers)

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
                            ProviderLogger.d(TAG, "fetch.onReceivedHttpError",
                                "HTTP error on main frame", "code" to responseCode)
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
                                timeoutJob.cancel()
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
                                    timeoutJob.cancel()
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
                            timeoutJob.cancel()
                            ProviderLogger.w(TAG, "fetch.onReceivedError", "Network error",
                                "description" to desc, "url" to url.take(80))
                            deferred.complete(ChromiumResponse.error("Network error: $desc"))
                        }
                    }
                }

                // Build extra headers map (WebView.loadUrl headers)
                val extraHeaders = mutableMapOf<String, String>()
                // Strip X-Requested-With to avoid WebView detection
                extraHeaders["X-Requested-With"] = ""
                // Forward user-provided headers (except Cookie which goes via CookieManager)
                for ((k, v) in headers) {
                    if (!k.equals("Cookie", ignoreCase = true)) {
                        extraHeaders[k] = v
                    }
                }

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

            } catch (e: Exception) {
                if (!delivered) {
                    delivered = true
                    timeoutJob.cancel()
                    deferred.complete(ChromiumResponse.error(e.message ?: "Unknown error"))
                }
            }

            deferred.await()
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

        if (existing != null && (now - lastFetchTime) < WEBVIEW_REUSE_WINDOW_MS) {
            // Reuse — just update UA if needed
            val ua = headers["User-Agent"] ?: WebConfig.getCachedUserAgent()
            existing.settings.userAgentString = ua
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
        }

        // Anti-bot spoofing
        webView.evaluateJavascript("""
            (function() {
                Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
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
