package com.cloudstream.shared.webview

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_WEBVIEW
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A fullscreen WebView the user surfs by hand, which reports the video URLs it sees on the wire.
 *
 * This is the deliberate opposite of [VideoSnifferEngine]: it puts **nothing** into the page. No
 * `addJavascriptInterface`, no `evaluateJavascript`, no fingerprint spoofing, no ad-block CSS/JS, no
 * auto-clicking of play buttons, no DOM polling. The page's own JavaScript runs exactly as it would
 * in Chrome, and the only thing we do is read [WebResourceRequest]s as they pass through
 * [WebViewClient.shouldInterceptRequest] — which requires no page cooperation at all and is
 * invisible from inside the document.
 *
 * That constraint is the whole point. Sites like CimaNow ship an anti-bot that greps inline scripts
 * for injected markers, checks `Function.prototype.toString` on `document.write`, sniffs `window`
 * for bridge objects, and walks the call stack for `evaluatejavascript` — every one of those trips
 * on an *instrumented* WebView and none of them can see a passive request observer. So instead of
 * fighting the gate for the decrypted server list, we let the user click through the site's normal
 * flow in a real browser and pick the stream up off the network.
 *
 * The captured URL is returned with the request's own headers, which is what makes the hand-off to
 * ExoPlayer work: tokenised CDNs check `Referer`/`Origin`, and the values the page used are the
 * only ones guaranteed to pass.
 */
class SurfSnifferEngine(
    private val activityProvider: () -> android.app.Activity?
) {
    companion object {
        private const val LOG_TAG = "SurfSniffer"

        /** How long the user is given to surf before we give up, unless the caller says otherwise. */
        const val DEFAULT_TIMEOUT_MS = 300_000L

        /**
         * Time to keep watching after the first hit.
         *
         * A player rarely requests exactly one URL: an HLS master is followed within a few hundred
         * milliseconds by its variant playlists, and those carry the per-quality tokens. Delivering
         * on the very first request would throw the quality ladder away.
         */
        const val DEFAULT_GRACE_MS = 2_500L
    }

    /** One video URL as it appeared on the wire, with the headers the page actually sent. */
    data class SurfCapture(
        val url: String,
        val headers: Map<String, String>,
        /** The document that issued the request — the natural Referer for playback. */
        val pageUrl: String,
        /** `Cookie` header value from the CookieManager for this URL, or null if it had none. */
        val cookies: String?
    )

    sealed class SurfResult {
        /** At least [SurfCapture] one video URL was seen. [finalUrl] is where the surf ended up. */
        data class Found(val captures: List<SurfCapture>, val finalUrl: String) : SurfResult()

        /** The user closed the dialog. [partial] may still hold hits from just before the dismiss. */
        data class Cancelled(val reason: String, val partial: List<SurfCapture>) : SurfResult()

        /** [DEFAULT_TIMEOUT_MS] elapsed with nothing playable. */
        data class Timeout(val lastUrl: String, val partial: List<SurfCapture>) : SurfResult()

        data class Error(val reason: String) : SurfResult()
    }

    private var deferred: CompletableDeferred<SurfResult>? = null
    private var resultDelivered = false
    private var timeoutJob: Job? = null
    private var graceJob: Job? = null
    private var tvMouseController: com.cloudstream.shared.ui.TvMouseController? = null
    private var statusView: android.widget.TextView? = null

    /** Captures in arrival order. Written from the WebView's IO threads, read from Main. */
    private val captures = java.util.Collections.synchronizedList(mutableListOf<SurfCapture>())
    private val seenUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var currentPageUrl: String = ""

    /**
     * Show a fullscreen WebView at [url] and return once a video URL has been seen on the network.
     *
     * @param url where to start surfing — normally the site's own movie/episode page, so the whole
     *   redirect/token chain runs in the browser instead of being reproduced over HTTP.
     * @param userAgent UA for the WebView; pass the session UA so cookies stay valid.
     * @param referer optional `Referer` for the initial navigation only.
     * @param timeoutMs how long the user may surf before we give up.
     * @param graceMs how long to keep collecting after the first hit (see [DEFAULT_GRACE_MS]).
     * @param statusHint short line shown along the top edge; null hides the overlay entirely.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun surf(
        url: String,
        userAgent: String,
        referer: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        graceMs: Long = DEFAULT_GRACE_MS,
        statusHint: String? = "اختر سيرفر وشغّل الفيديو…"
    ): SurfResult = withContext(Dispatchers.Main) {

        val activity = activityProvider()
        if (activity == null) {
            ProviderLogger.e(TAG_WEBVIEW, "SurfSnifferEngine.surf", "No Activity available")
            return@withContext SurfResult.Error("No Activity context")
        }

        this@SurfSnifferEngine.deferred = CompletableDeferred()
        val deferred = this@SurfSnifferEngine.deferred!!
        resultDelivered = false
        captures.clear()
        seenUrls.clear()
        currentPageUrl = url

        var dialog: android.app.Dialog? = null
        var webView: WebView? = null

        timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(timeoutMs)
            if (!resultDelivered) {
                val partial = snapshot()
                ProviderLogger.w(TAG_WEBVIEW, "SurfSnifferEngine.surf", "Timed out",
                    "timeoutMs" to timeoutMs, "partial" to partial.size)
                finish(webView, dialog, deferred, SurfResult.Timeout(currentPageUrl, partial))
            }
        }

        try {
            webView = WebView(activity).apply {
                settings.apply {
                    // The page's own JS must run — that is what "normal surf" means. What we never
                    // do is add any of *ours*.
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // The user is right there and may well press play themselves, but embeds that
                    // autoplay should be allowed to — either way the request is what we're after.
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    // Single window: `target=_blank` and `window.open` then load in this same
                    // WebView instead of hitting onCreateWindow and dying. Several of these
                    // redirect chains hand off through a _blank link, and a blocked popup is a
                    // dead end the user cannot recover from.
                    setSupportMultipleWindows(false)
                }
                // Unlike the sniffer's WebView this one is meant to be touched.
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    // Purely observational: always returns null so Chromium performs the request
                    // itself, unaltered. Nothing here is detectable from the page.
                    val requestUrl = request?.url?.toString() ?: return null
                    try {
                        if (VideoUrlClassifier.isVideoUrl(requestUrl)) {
                            capture(requestUrl, request.requestHeaders ?: emptyMap(), graceMs,
                                webView, dialog, deferred)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(LOG_TAG, "Capture check failed: ${e.message}")
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val next = request?.url ?: return false
                    val scheme = next.scheme?.lowercase()
                    // intent:// and market:// would throw the user out of the app mid-surf. Every
                    // http(s) navigation is allowed — the user is browsing, and the chain crosses
                    // several unrelated domains by design.
                    if (scheme != null && scheme != "http" && scheme != "https") {
                        android.util.Log.i(LOG_TAG, "Blocked non-HTTP navigation: $next")
                        return true
                    }
                    android.util.Log.d(LOG_TAG, "Navigating: ${next.toString().take(140)}")
                    return false
                }

                override fun onPageStarted(
                    view: WebView?,
                    pageUrl: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    if (!pageUrl.isNullOrBlank()) currentPageUrl = pageUrl
                    android.util.Log.i(LOG_TAG, "Page started: ${pageUrl?.take(140)}")
                    // No injection here. See the class doc.
                    super.onPageStarted(view, pageUrl, favicon)
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    if (!pageUrl.isNullOrBlank()) currentPageUrl = pageUrl
                    android.util.Log.i(LOG_TAG, "Page finished: ${pageUrl?.take(140)}")
                    super.onPageFinished(view, pageUrl)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        val description =
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                                error?.description?.toString() else error?.toString()
                        android.util.Log.w(LOG_TAG, "Main-frame error: $description @ ${request.url}")
                    }
                }
            }

            webView.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                    // Read-only. We never write to the console and never depend on it as a channel.
                    msg?.let { android.util.Log.d("$LOG_TAG-JS", "${it.message()} [${it.lineNumber()}]") }
                    return true
                }
            }

            dialog = createSurfDialog(activity, webView, statusHint, deferred)
            dialog.show()

            ProviderLogger.i(TAG_WEBVIEW, "SurfSnifferEngine.surf", "Surf started",
                "url" to url.take(120), "timeoutMs" to timeoutMs)

            if (referer.isNullOrBlank()) {
                webView.loadUrl(url)
            } else {
                webView.loadUrl(url, mapOf("Referer" to referer))
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG_WEBVIEW, "SurfSnifferEngine.surf", "Setup failed", e)
            finish(webView, dialog, deferred, SurfResult.Error(e.message ?: "Unknown error"))
        }

        try {
            deferred.await()
        } finally {
            // Parent cancelled (user backed out of the player screen) — the dialog must not survive.
            if (!resultDelivered) {
                resultDelivered = true
                timeoutJob?.cancel()
                graceJob?.cancel()
                ProviderLogger.w(TAG_WEBVIEW, "SurfSnifferEngine.surf", "Caller cancelled — cleaning up")
                cleanup(webView, dialog)
            }
        }
    }

    /**
     * Record a video URL and arm the grace window.
     *
     * Called on a Chromium worker thread, so the state it touches is synchronized and everything
     * that needs the main thread is posted there.
     */
    private fun capture(
        url: String,
        requestHeaders: Map<String, String>,
        graceMs: Long,
        webView: WebView?,
        dialog: android.app.Dialog?,
        deferred: CompletableDeferred<SurfResult>
    ) {
        if (resultDelivered) return
        if (!seenUrls.add(url)) return

        val cookies = try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) { null }

        val capture = SurfCapture(
            url = url,
            headers = requestHeaders,
            pageUrl = currentPageUrl,
            cookies = cookies?.takeIf { it.isNotBlank() }
        )
        captures.add(capture)

        android.util.Log.i(LOG_TAG, "🎬 VIDEO #${captures.size}: ${url.take(160)}")
        android.util.Log.d(LOG_TAG, "   page=${capture.pageUrl.take(120)} headers=${requestHeaders.keys}")

        // Arming is synchronized because captures arrive on several Chromium worker threads at once
        // (a master playlist and its variants are requested in parallel) and the window must be
        // opened exactly once.
        synchronized(this) {
            if (graceJob != null) return  // already running; this hit just joins the window
            graceJob = armGrace(graceMs, webView, dialog, deferred)
        }
    }

    private fun armGrace(
        graceMs: Long,
        webView: WebView?,
        dialog: android.app.Dialog?,
        deferred: CompletableDeferred<SurfResult>
    ): Job =
        CoroutineScope(Dispatchers.Main).launch {
            setStatus("✔ تم العثور على الفيديو — جاري التشغيل…")
            delay(graceMs)
            if (!resultDelivered) {
                val found = snapshot()
                ProviderLogger.i(TAG_WEBVIEW, "SurfSnifferEngine.capture", "Delivering captures",
                    "count" to found.size, "first" to found.firstOrNull()?.url?.take(100))
                finish(webView, dialog, deferred, SurfResult.Found(found, currentPageUrl))
            }
        }

    private fun snapshot(): List<SurfCapture> = synchronized(captures) { captures.toList() }

    private fun finish(
        webView: WebView?,
        dialog: android.app.Dialog?,
        deferred: CompletableDeferred<SurfResult>,
        result: SurfResult
    ) {
        if (resultDelivered) return
        resultDelivered = true
        timeoutJob?.cancel()
        graceJob?.cancel()
        cleanup(webView, dialog)
        deferred.complete(result)
    }

    private fun setStatus(text: String) {
        val view = statusView ?: return
        try {
            view.post {
                view.text = text
                view.visibility = android.view.View.VISIBLE
            }
        } catch (_: Exception) { }
    }

    /**
     * Fullscreen dialog holding the WebView, with the TV cursor attached so a D-pad remote can click
     * server buttons and play controls — this flow is driven entirely by the user's own clicks.
     */
    private fun createSurfDialog(
        activity: android.app.Activity,
        webView: WebView,
        statusHint: String?,
        deferred: CompletableDeferred<SurfResult>
    ): android.app.Dialog {
        val container = android.widget.FrameLayout(activity).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        container.addView(
            webView,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        if (statusHint != null) {
            statusView = android.widget.TextView(activity).apply {
                text = statusHint
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.argb(160, 0, 0, 0))
                textSize = 12f
                setPadding(16, 8, 16, 8)
                // Must never eat a tap meant for the page underneath it.
                isClickable = false
                isFocusable = false
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP
                )
            }
            container.addView(statusView)
        }

        // The TV mouse is not a convenience here, it is the input path.
        //
        // Server buttons and play controls have to be clicked, and this engine is forbidden from
        // injecting the JS that would do it (an `element.click()` produces an event with
        // `isTrusted === false`, and a play started that way is exactly what the site's checks look
        // for). TvMouseController instead dispatches real MotionEvents into the WebView, so the page
        // receives genuine touch input indistinguishable from a finger — which is also the only way
        // a D-pad remote can drive this screen at all.
        tvMouseController = com.cloudstream.shared.ui.TvMouseController(activity, webView)
        tvMouseController?.attach(container)

        return android.app.Dialog(
            activity,
            android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen
        ).apply {
            setContentView(container)
            setCancelable(true)

            window?.let { w ->
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            setOnKeyListener { _, _, event ->
                // Back goes back through the surf history before it closes the dialog, so a wrong
                // click on an ad is recoverable.
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_UP &&
                    webView.canGoBack()
                ) {
                    webView.goBack()
                    true
                } else {
                    tvMouseController?.onKeyEvent(event) ?: false
                }
            }

            setOnDismissListener {
                tvMouseController?.detach()
                tvMouseController = null
                statusView = null

                if (!resultDelivered) {
                    val partial = snapshot()
                    ProviderLogger.d(TAG_WEBVIEW, "SurfSnifferEngine", "Dialog dismissed by user",
                        "partial" to partial.size)
                    resultDelivered = true
                    timeoutJob?.cancel()
                    graceJob?.cancel()
                    cleanup(webView, null)
                    deferred.complete(SurfResult.Cancelled("User closed the surf window", partial))
                }
            }
        }
    }

    private fun cleanup(webView: WebView?, dialog: android.app.Dialog?) {
        try {
            dialog?.dismiss()
            webView?.let { view ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        view.stopLoading()
                        view.loadUrl("about:blank")
                        view.clearHistory()
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        view.removeAllViews()
                        view.destroy()
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG_WEBVIEW, "SurfSnifferEngine.cleanup", "Error",
                            "error" to e.message)
                    }
                }
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG_WEBVIEW, "SurfSnifferEngine.cleanup", "Error", "error" to e.message)
        }
    }
}
