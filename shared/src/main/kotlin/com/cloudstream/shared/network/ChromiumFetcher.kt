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
import org.json.JSONObject
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

        /**
         * Ceiling for [fetchSameOriginText]. Generous because it covers one navigation plus N in-page
         * fetches, and because nothing waits on it: it only ever runs off the critical path.
         */
        private const val SAME_ORIGIN_TIMEOUT_MS = 20_000L

        /** How often to ask the page whether its fetches have settled. */
        private const val SAME_ORIGIN_POLL_MS = 200L
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

    /**
     * Fetches text subresources **from inside a document on their own origin**, using the page's own
     * `fetch()`.
     *
     * Why this exists, and why nothing simpler works. cimanow refuses every HTTP transport this app
     * owns: verified on 2026-08-05 against `/wp-content/themes/Cima Now New/Assets/…` — Android's
     * `HttpURLConnection`, OkHttp, desktop curl and Playwright's Node client all receive the site's own
     * 403 page (`<title>ستوب! المخرج عايز كدة</title>`, `cache-control: private, no-store`), while
     * **any** request issued by a real Chromium network stack gets `200` with the correct
     * `text/css` / `text/javascript`. Headers are not the variable: the same 403 arrives with the device
     * UA, a desktop UA, no UA, with and without cookies, with and without `Referer`, on `cf-cache-status`
     * HIT and MISS alike. Only the transport decides.
     *
     * [fetch] cannot be reused for this. It navigates the WebView *to* the URL and scrapes
     * `document.documentElement.outerHTML`, so a stylesheet comes back wrapped and HTML-escaped inside
     * Chromium's plain-text viewer — a lossy round trip for a file full of `>` selectors. This instead
     * loads one cheap document on the target origin and calls `fetch()` from within it, which is the
     * exact mechanism verified to return `200 text/css`: same origin, same connection, real Chromium
     * TLS, and the bytes arrive unmodified.
     *
     * One origin load serves every URL in [assetUrls], so warming N files costs one navigation.
     *
     * @param originUrl A small document on the same origin as [assetUrls] (e.g. `/robots.txt`).
     * @param requireContentTypeContains When set, only responses whose `Content-Type` contains this
     *   substring are returned. Worth using: a captive portal, a corporate MITM proxy or the site's own
     *   block page will all answer `200` with an HTML body for a `.css` URL, and a `200` alone is not
     *   evidence that what came back is the file that was asked for.
     * @return Map of asset URL → body, containing only entries that came back `200` with a non-empty
     *   body. Never throws; an unreachable origin or a refused asset simply yields no entry.
     */
    suspend fun fetchSameOriginText(
        originUrl: String,
        assetUrls: List<String>,
        headers: Map<String, String> = emptyMap(),
        timeout: Long = SAME_ORIGIN_TIMEOUT_MS,
        requireContentTypeContains: String? = null
    ): Map<String, String> {
        if (assetUrls.isEmpty()) return emptyMap()
        return fetchMutex.withLock {
            withContext(Dispatchers.Main) {
                val activity = activityProvider()
                if (activity == null) {
                    ProviderLogger.w(TAG, "fetchSameOriginText", "No Activity available")
                    return@withContext emptyMap()
                }
                try {
                    val webView = getOrCreateWebView(activity, headers)
                    val loaded = CompletableDeferred<Boolean>()
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            if (!loaded.isCompleted) loaded.complete(true)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true && !loaded.isCompleted) {
                                loaded.complete(false)
                            }
                        }
                    }

                    val extraHeaders = mutableMapOf("X-Requested-With" to "")
                    for ((k, v) in headers) {
                        if (!k.equals("Cookie", ignoreCase = true)) extraHeaders[k] = v
                    }

                    ProviderLogger.d(TAG, "fetchSameOriginText", "Loading origin document",
                        "origin" to originUrl.take(80), "assets" to assetUrls.size)
                    webView.loadUrl(originUrl, extraHeaders)

                    val reachedOrigin = withTimeoutOrNull(timeout) { loaded.await() } ?: false
                    if (!reachedOrigin) {
                        ProviderLogger.w(TAG, "fetchSameOriginText",
                            "Origin document never loaded — nothing fetched",
                            "origin" to originUrl.take(80))
                        return@withContext emptyMap()
                    }

                    // The page's own fetch(), one call per asset, results parked on a global that the
                    // poll below drains once every request has settled. `evaluateJavascript` hands back
                    // a promise object, not its value, so a global plus a poll is what replaces `await`.
                    val urlArray = assetUrls.joinToString(",") { JSONObject.quote(it) }
                    webView.evaluateJavascript(
                        """
                        (function() {
                            var urls = [$urlArray];
                            var out = { done: false, items: {} };
                            window.__csAssets = out;
                            var left = urls.length;
                            urls.forEach(function(u) {
                                fetch(u, { credentials: 'include' }).then(function(r) {
                                    return r.text().then(function(t) {
                                        return {
                                            status: r.status,
                                            ct: r.headers.get('content-type') || '',
                                            body: t
                                        };
                                    });
                                }).catch(function() {
                                    return { status: 0, ct: '', body: '' };
                                }).then(function(res) {
                                    out.items[u] = res;
                                    if (--left <= 0) out.done = true;
                                });
                            });
                            if (urls.length === 0) out.done = true;
                        })();
                        """.trimIndent(),
                        null
                    )

                    val deadline = System.currentTimeMillis() + timeout
                    while (System.currentTimeMillis() < deadline) {
                        delay(SAME_ORIGIN_POLL_MS)
                        // Only stringify once everything has settled — the payload is measured in tens
                        // of KB and there is no reason to move it on every tick.
                        val raw = evaluateForString(
                            webView,
                            "(function(){var a=window.__csAssets;" +
                                "return !a ? '' : (a.done ? JSON.stringify(a.items) : 'PENDING');})();"
                        )
                        if (raw.isEmpty() || raw == "PENDING") continue

                        val items = try { JSONObject(raw) } catch (_: Exception) { null }
                            ?: return@withContext emptyMap()
                        val result = LinkedHashMap<String, String>()
                        for (url in assetUrls) {
                            val entry = items.optJSONObject(url) ?: continue
                            val status = entry.optInt("status", 0)
                            val body = entry.optString("body", "")
                            val contentType = entry.optString("ct", "")
                            val typeOk = requireContentTypeContains == null ||
                                contentType.contains(requireContentTypeContains, ignoreCase = true)
                            if (status == 200 && body.isNotEmpty() && typeOk) {
                                result[url] = body
                                ProviderLogger.i(TAG, "fetchSameOriginText", "✅ Fetched",
                                    "url" to url.takeLast(60),
                                    "bytes" to body.length,
                                    "contentType" to contentType.ifEmpty { "?" })
                            } else if (status == 200 && !typeOk) {
                                ProviderLogger.w(TAG, "fetchSameOriginText",
                                    "200 but the wrong Content-Type — a block page, an interstitial or a " +
                                        "proxy, not the file. Discarded.",
                                    "url" to url.takeLast(60),
                                    "contentType" to contentType.ifEmpty { "?" },
                                    "wanted" to requireContentTypeContains,
                                    "bytes" to body.length)
                            } else {
                                ProviderLogger.w(TAG, "fetchSameOriginText", "Refused",
                                    "url" to url.takeLast(60),
                                    "status" to status,
                                    "contentType" to contentType.ifEmpty { "?" })
                            }
                        }
                        return@withContext result
                    }
                    ProviderLogger.w(TAG, "fetchSameOriginText", "Timed out waiting for in-page fetches",
                        "timeoutMs" to timeout)
                    emptyMap()
                } catch (e: Exception) {
                    ProviderLogger.w(TAG, "fetchSameOriginText", "Failed: ${e.message}")
                    emptyMap()
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun evaluateForString(webView: WebView, js: String): String =
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                try {
                    webView.evaluateJavascript(js) { result ->
                        val value = try {
                            if (result == null || result == "null") ""
                            else JSONTokener(result).nextValue().toString()
                        } catch (_: Exception) { "" }
                        if (cont.isActive) cont.resume(value) {}
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume("") {}
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
