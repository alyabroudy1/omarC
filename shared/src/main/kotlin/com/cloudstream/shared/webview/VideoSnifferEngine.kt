package com.cloudstream.shared.webview

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_WEBVIEW
import kotlinx.coroutines.*

/**
 * Video sniffing engine using Android WebView in fullscreen dialog mode.
 *
 * Handles [ExitCondition.VideoFound] exit condition. Runs the WebView in a fullscreen
 * dialog with TV remote control integration, auto-play JS injection, DOM polling,
 * and network interception to detect video URLs.
 *
 * Features:
 * - Network request interception for video URLs (.m3u8, .mp4, .mkv, .webm, blob:)
 * - DOM polling for video/source elements and player objects
 * - JS bridge (SnifferBridge) for auto-play, ad-skip, and source extraction
 * - Fullscreen iframe CSS injection for sniffer-as-player mode
 * - Cross-domain redirect blocking with user confirmation dialog
 * - TV remote control integration (TvMouseController)
 * - Smart exit strategy: waits for master M3U8 before exiting
 * - Fingerprint spoofing to mask WebView identity
 *
 * DECOUPLED FROM STATE: This engine does NOT store cookies.
 * It returns cookies in [WebViewResult.Success], and the caller
 * is responsible for updating SessionState.
 */
class VideoSnifferEngine(
    private val activityProvider: () -> android.app.Activity?
) {
    companion object {
        /**
         * Timeout for sniffer-as-player mode (3 hours).
         * When no extractable video is found (DRM content), the sniffer stays open
         * as a fullscreen player. This timeout must be long enough for a full movie.
         */
        const val SNIFFER_PLAYER_TIMEOUT_MS = 10_800_000L // 3 hours

        /** JS snippet to make iframes fullscreen for sniffer-as-player mode. */
        val FULLSCREEN_IFRAME_JS = """
            (function() {
                var style = document.createElement('style');
                style.textContent = '
                    iframe {
                        position: fixed !important;
                        top: 0 !important;
                        left: 0 !important;
                        width: 100vw !important;
                        height: 100vh !important;
                        z-index: 99999 !important;
                        border: none !important;
                    }
                    body > *:not(iframe) {
                        display: none !important;
                    }
                ';
                document.head.appendChild(style);
            })()
        """.trimIndent()
    }

    // Instance variables to share state with helper methods
    private var deferred: CompletableDeferred<WebViewResult>? = null
    private var resultDelivered = false
    private var timeoutJob: Job? = null
    private var videoMonitorJob: Job? = null
    private var exitConditionReference: ExitCondition? = null
    private var activeWebView: WebView? = null
    private var activeDialog: Dialog? = null
    private var statusTextView: TextView? = null
    private var tvMouseController: com.cloudstream.shared.ui.TvMouseController? = null

    private val capturedLinks = java.util.concurrent.CopyOnWriteArrayList<CapturedLinkData>()
    private var firstLinkTime: Long = 0L
    private val SMART_WAIT_TIME_MS = 2500L

    /**
     * Run a WebView session for video sniffing.
     *
     * @param url The URL to load
     * @param mode HEADLESS or FULLSCREEN
     * @param userAgent The user agent string to use
     * @param exitCondition Must be [ExitCondition.VideoFound]
     * @param timeout Maximum time to wait in milliseconds
     * @param delayMs Optional delay after page load before checking exit condition
     * @param preSniffJavaScript Optional JavaScript to execute after page load but before video sniffing.
     *        Useful for clicking server buttons or other interactions needed before player loads.
     * @param referer Optional referer URL to send with requests
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun runSession(
        url: String,
        mode: Mode,
        userAgent: String,
        exitCondition: ExitCondition,
        timeout: Long = 60_000L,
        delayMs: Long = 0L,
        preSniffJavaScript: String? = null,
        referer: String? = null
    ): WebViewResult = withContext(Dispatchers.Main) {

        val activity = activityProvider()
        if (activity == null) {
            ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "No Activity available")
            return@withContext WebViewResult.Error("No Activity context")
        }

        this@VideoSnifferEngine.deferred = CompletableDeferred<WebViewResult>()
        val deferred = this@VideoSnifferEngine.deferred!!
        this@VideoSnifferEngine.exitConditionReference = exitCondition
        resultDelivered = false
        var dialog: Dialog? = null
        var webView: WebView? = null

        // BUGFIX: Clear capturedLinks at the start of each session
        capturedLinks.clear()
        firstLinkTime = 0L
        android.util.Log.d("VideoSnifferEngine", "runSession: Session started, capturedLinks cleared. URL: $url")
        ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Session started, capturedLinks cleared")

        // Timeout handler
        this@VideoSnifferEngine.timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(timeout)
            if (!resultDelivered) {
                resultDelivered = true
                val partialHtml = try {
                    webView?.let { getHtmlFromWebView(it) }
                } catch (e: Exception) { null }

                cleanup(webView, dialog)
                // Include any captured links on timeout
                val foundLinks = capturedLinks.toList()
                if (foundLinks.isNotEmpty()) {
                    ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Timeout with ${foundLinks.size} captured links")
                    val cookies = webView?.let { extractCookies(it, url) } ?: emptyMap()
                    deferred.complete(WebViewResult.Success(cookies, "", url, foundLinks))
                } else {
                    deferred.complete(WebViewResult.Timeout(url, partialHtml))
                }
            }
        }

        // BUGFIX: Proactive video monitoring job - checks every 300ms for captured videos
        this@VideoSnifferEngine.videoMonitorJob = if (exitCondition is ExitCondition.VideoFound) {
            CoroutineScope(Dispatchers.Main).launch {
                val requiredCount = (exitCondition as ExitCondition.VideoFound).minCount
                android.util.Log.d("VideoSnifferEngine", "videoMonitorJob: Started. requiredCount=$requiredCount")
                ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Video monitor started", "requiredCount" to requiredCount)
                while (!resultDelivered) {
                    delay(300)
                    if (capturedLinks.size >= requiredCount) {
                        // BUGFIX: Add delay to ensure all headers/cookies are captured
                        ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Videos found, waiting for headers to sync...")
                        delay(500)  // Give time for headers to be captured

                        checkExitCondition() // Trigger exit via helper

                        // Fallback if checkExitCondition didn't trigger for some reason
                        if (!resultDelivered) {
                             resultDelivered = true
                             timeoutJob?.cancel()

                             val cookies = webView?.let { extractCookies(it, url) } ?: emptyMap()
                             val foundLinks = capturedLinks.toList()
                             android.util.Log.i("VideoSnifferEngine", "[videoMonitorJob] FALLBACK EXIT with ${foundLinks.size} links")
                             android.util.Log.i("VideoSnifferEngine", "[videoMonitorJob] First link: ${foundLinks.firstOrNull()?.url?.take(100)}")
                             ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Video monitor forced exit")
                             cleanup(webView, dialog)
                             android.util.Log.i("VideoSnifferEngine", "[videoMonitorJob] Completing deferred with ${foundLinks.size} links")
                             deferred.complete(WebViewResult.Success(cookies, "", url, foundLinks))
                             android.util.Log.i("VideoSnifferEngine", "[videoMonitorJob] Deferred completed!")
                        }
                        break
                    }
                }
            }
        } else null

        try {
            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Creating WebView", "url" to url.take(80))

            // Create WebView
            webView = WebView(activity).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = true // Allow JS to open windows (needed for some popups)
                    setSupportMultipleWindows(true)
                }
            }
            this@VideoSnifferEngine.activeWebView = webView

            // UA VERIFICATION: Log the exact UA WebView is using
            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "WebView UA",
                "ua" to webView.settings.userAgentString)

            // Setup cookies
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            // Setup based on mode
            when (mode) {
                Mode.HEADLESS -> {
                    ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "HEADLESS mode", "url" to url.take(80))
                }
                Mode.FULLSCREEN -> {
                    dialog = createDialog(activity, webView)
                    this@VideoSnifferEngine.activeDialog = dialog
                    dialog.show()
                    ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "FULLSCREEN mode", "url" to url.take(80))
                }
            }

            // Add JS Interface to support all modes
            webView.addJavascriptInterface(SnifferBridge(), "SnifferBridge")

            // Setup WebViewClient
            webView.webViewClient = object : WebViewClient() {
                private var requestCounter = 0

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    if (resultDelivered) return null
                    val requestUrl = request?.url?.toString()
                    requestCounter++

                    if (requestUrl != null) {
                        // Log ALL requests for debugging
                        if (requestCounter % 10 == 0 || requestUrl.contains(".m3u8") || requestUrl.contains(".mp4") || requestUrl.contains("video") || requestUrl.contains("stream")) {
                            android.util.Log.d("VideoSnifferEngine", "intercept: Request #$requestCounter url=${requestUrl.take(100)}")
                            ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.intercept", "Request #$requestCounter",
                                "url" to requestUrl.take(100),
                                "method" to (request?.method ?: "?"),
                                "isMainFrame" to (request?.isForMainFrame ?: false)
                            )
                        }

                        // Check if it's a video URL with detailed logging
                        if (VideoUrlClassifier.isVideoUrl(requestUrl)) {
                             ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.intercept", "VIDEO URL DETECTED!",
                                 "url" to requestUrl.take(100),
                                 "requestNum" to requestCounter
                             )
                             captureLink(requestUrl, "Network", request?.requestHeaders ?: emptyMap())
                        } else if (requestUrl.contains("m3u8") || requestUrl.contains("mp4") || requestUrl.contains("video") || requestUrl.contains("stream")) {
                            // Log why it was rejected
                            ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.intercept", "URL rejected",
                                "url" to requestUrl.take(100),
                                "reason" to "Failed video pattern check",
                                "isBlacklisted" to VideoUrlClassifier.isBlacklisted(requestUrl)
                            )
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val nextUrl = request?.url?.toString()
                    if (nextUrl.isNullOrBlank()) return super.shouldOverrideUrlLoading(view, request)

                    // Check if this redirect goes to a DIFFERENT domain than the target URL
                    try {
                        val nextHost = java.net.URI(nextUrl).host?.lowercase() ?: ""
                        val targetHost = java.net.URI(url).host?.lowercase() ?: ""

                        fun baseDomain(host: String): String {
                            val parts = host.split(".")
                            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
                        }

                        val nextBase = baseDomain(nextHost)
                        val targetBase = baseDomain(targetHost)

                        if (nextBase == targetBase) {
                            // Same domain redirect (e.g., www.X.com → X.com) — allow silently
                            ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.shouldOverrideUrlLoading", "Same-domain redirect (allowed)", "url" to nextUrl.take(80))
                            return false
                        }

                        // Cross-domain redirect — block and show user confirmation
                        android.util.Log.w("VideoSnifferEngine", "Cross-domain redirect detected: $targetBase → $nextBase ($nextUrl)")
                        ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.shouldOverrideUrlLoading", "Cross-domain redirect BLOCKED, asking user",
                            "from" to targetBase, "to" to nextBase, "url" to nextUrl.take(100)
                        )

                        // Show native Android dialog for redirect confirmation
                        view?.post {
                            try {
                                val ctx = view.context
                                val shortUrl = if (nextUrl.length > 80) nextUrl.take(77) + "..." else nextUrl
                                val alertDialog = android.app.AlertDialog.Builder(ctx)
                                    .setTitle("Redirect Detected")
                                    .setMessage("Allow redirect to:\n$shortUrl")
                                    .setPositiveButton("Allow") { d, _ ->
                                        d.dismiss()
                                        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.redirectDialog", "User ALLOWED redirect", "url" to nextUrl.take(80))
                                        activeWebView?.loadUrl(nextUrl)
                                    }
                                    .setNegativeButton("Block") { d, _ ->
                                        d.dismiss()
                                        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.redirectDialog", "User BLOCKED redirect", "url" to nextUrl.take(80))
                                    }
                                    .setCancelable(false)
                                    .create()
                                alertDialog.show()

                                // Auto-reject after 8 seconds
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    if (alertDialog.isShowing) {
                                        alertDialog.dismiss()
                                        ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.redirectDialog", "Auto-rejected after 8s", "url" to nextUrl.take(80))
                                    }
                                }, 8000)
                            } catch (e: Exception) {
                                android.util.Log.w("VideoSnifferEngine", "Failed to show redirect dialog: ${e.message}")
                            }
                        }

                        return true // Block the redirect

                    } catch (e: Exception) {
                        android.util.Log.w("VideoSnifferEngine", "Error in redirect check: ${e.message}")
                        ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.shouldOverrideUrlLoading", "Redirect (parse error, allowing)", "url" to nextUrl.take(80))
                        return false
                    }
                }

                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                    if (resultDelivered) return
                    android.util.Log.i("VideoSnifferEngine", "onPageStarted: url=${pageUrl?.take(80)}")
                    ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageStarted", "Page started", "url" to pageUrl?.take(80))

                    // Inject Advanced Polyfill & Fingerprint Spoofing
                    view?.evaluateJavascript(
                        """
                        (function() {
                            // 1. Polyfill for sites that expect object__info
                            if (typeof window.object__info === 'undefined') {
                                window.object__info = {};
                            }
                            
                            // 2. Fingerprint Spoofing (Match Desktop UA)
                            if (navigator.userAgent.indexOf("Windows") !== -1) {
                                Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; } });
                                Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; } });
                                Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                            }
                        })();
                        """.trimIndent(), null
                    )

                    super.onPageStarted(view, pageUrl, favicon)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (resultDelivered) return
                    val currentUrl = view?.url ?: loadedUrl ?: url
                    android.util.Log.i("VideoSnifferEngine", "onPageFinished: url=${currentUrl.take(80)}")
                    ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Page finished", "url" to currentUrl.take(80))

                    // Inject fullscreen iframe CSS for sniffer-as-player mode
                    view?.evaluateJavascript(FULLSCREEN_IFRAME_JS, null)
                    ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Injected fullscreen iframe CSS")

                    // Inject VideoSniffer JS & Start DOM extraction
                    if (exitCondition is ExitCondition.VideoFound) {
                        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Pre-sniff JavaScript phase")

                        // Execute pre-sniff JavaScript if provided (e.g., click server button)
                        if (!preSniffJavaScript.isNullOrBlank()) {
                            android.util.Log.i("VideoSnifferEngine", "onPageFinished: Executing pre-sniff JavaScript")
                            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Executing pre-sniff JavaScript", "jsLength" to preSniffJavaScript.length)
                            view?.evaluateJavascript(preSniffJavaScript) { result ->
                                android.util.Log.i("VideoSnifferEngine", "onPageFinished: Pre-sniff JS result: $result")
                                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Pre-sniff JavaScript result", "result" to (result ?: "null"))
                            }

                            // Wait for player to load after clicking, then inject video sniffer
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(3000) // Give 3s for player to initialize after click
                                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Injecting Video Sniffer (after pre-sniff)")
                                view?.evaluateJavascript(VideoSnifferJs.JS_SCRIPT) { result ->
                                    ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Video sniffer injection result", "result" to (result ?: "null"))
                                }
                                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Starting DOM extraction")
                                startDomVideoExtraction(view)
                            }
                        } else {
                            // No pre-sniff JS, inject video sniffer immediately
                            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Injecting Video Sniffer")
                            view?.evaluateJavascript(VideoSnifferJs.JS_SCRIPT) { result ->
                                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Video sniffer injection result", "result" to (result ?: "null"))
                            }
                            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Starting DOM extraction")
                            startDomVideoExtraction(view)
                        }
                    }

                    if (resultDelivered) {
                        ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Result already delivered, skipping")
                        return
                    }

                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            if (delayMs > 0) {
                                delay(delayMs)
                            }

                            val html = getHtmlFromWebView(view!!)

                            // Check exit condition
                            val shouldExit = when (exitCondition) {
                                is ExitCondition.VideoFound -> {
                                    val count = capturedLinks.size
                                    if (count >= exitCondition.minCount) true else false
                                }
                                is ExitCondition.PageLoaded -> false // Not handled by this engine
                                is ExitCondition.CookiesPresent -> false // Not handled by this engine
                            }

                            if (shouldExit) {
                                resultDelivered = true
                                timeoutJob?.cancel()
                                videoMonitorJob?.cancel()

                                val cookies = extractCookies(view, currentUrl)
                                val found = capturedLinks.toList()
                                android.util.Log.i("VideoSnifferEngine", "[onPageFinished] EXITING! Sending ${found.size} links to deferred.")
                                found.forEach { android.util.Log.d("VideoSnifferEngine", " > Link: ${it.url}") }

                                cleanup(view, dialog)
                                deferred.complete(WebViewResult.Success(cookies, html, currentUrl, found))
                                android.util.Log.i("VideoSnifferEngine", "[onPageFinished] Deferred completed.")
                            }
                        } catch (e: Exception) {
                            ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Error", e)
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        val description = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            error?.description?.toString()
                        } else {
                            error?.toString()
                        }
                        ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.onReceivedError", "WebView error",
                            "description" to description, "url" to request.url.toString().take(80))
                    }
                }
            }

            // Add WebChromeClient to capture console logs and handle popups
            webView.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        val msg = "${it.message()} [${it.sourceId()}:${it.lineNumber()}]"
                        when (it.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR ->
                                android.util.Log.e("VideoSnifferJS", msg)
                            android.webkit.ConsoleMessage.MessageLevel.WARNING ->
                                android.util.Log.w("VideoSnifferJS", msg)
                            else ->
                                android.util.Log.d("VideoSnifferJS", msg)
                        }
                    }
                    return true
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress % 20 == 0) {
                        ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine", "Loading progress", "percent" to newProgress)
                    }
                }

                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    val newWebView = WebView(view?.context ?: return false)
                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val popupUrl = request?.url?.toString()
                            if (!popupUrl.isNullOrBlank()) {
                                ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.onCreateWindow", "Blocked popup from hijacking main WebView", "url" to popupUrl.take(80))
                                android.util.Log.i("VideoSnifferEngine", "Blocked popup: ${popupUrl.take(80)}")
                            }
                            return true
                        }
                    }
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = newWebView
                    resultMsg?.sendToTarget()
                    return true
                }
            }

            // Load URL with headers to bypass detection
            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Loading URL", "url" to url.take(80))

            val extraHeaders = mutableMapOf<String, String>()
            extraHeaders["X-Requested-With"] = ""

            // Add referer if provided (critical for embed servers like qq.okprime.site)
            if (!referer.isNullOrBlank()) {
                extraHeaders["Referer"] = referer
                // Sec-Fetch headers are CRITICAL: servers like play.aboyounes.net
                // return 301 redirect without these, even with a valid Referer.
                // They validate that the request originates from an iframe context.
                extraHeaders["sec-fetch-dest"] = "iframe"
                extraHeaders["sec-fetch-mode"] = "navigate"
                extraHeaders["sec-fetch-site"] = "cross-site"
                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Added Referer + Sec-Fetch headers", "referer" to referer)
            }

            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Loading URL with headers",
                "url" to url.take(80),
                "headers" to extraHeaders.keys.joinToString(",")
            )

            webView.loadUrl(url, extraHeaders)

            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "URL loaded, waiting for callbacks")

        } catch (e: Exception) {
            resultDelivered = true
            timeoutJob?.cancel()
            videoMonitorJob?.cancel()
            cleanup(webView, dialog)
            deferred.complete(WebViewResult.Error(e.message ?: "Unknown error"))
        }

        // CRITICAL: Wrap await in try/finally so that if the parent coroutine is cancelled
        // (e.g., user presses back in GeneratorPlayer), we still clean up the WebView,
        // dialog, and all child jobs. Without this, standalone CoroutineScope jobs
        // (timeoutJob, videoMonitorJob) keep running indefinitely.
        try {
            deferred.await()
        } finally {
            if (!resultDelivered) {
                resultDelivered = true
                timeoutJob?.cancel()
                videoMonitorJob?.cancel()
                android.util.Log.i("VideoSnifferEngine", "runSession: Parent coroutine cancelled, cleaning up WebView and dialog")
                ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Parent coroutine cancelled, forcing cleanup")
                cleanup(webView, dialog)
            }
        }
    }

    /**
     * Stores a captured video link if it passes validation.
     * Called from both network interception (shouldInterceptRequest) and JS bridge (SnifferBridge).
     * Delegates all URL filtering to [VideoUrlClassifier] to avoid duplication.
     */
    private fun captureLink(url: String, qualityLabel: String, headers: Map<String, String>) {
         // Reject non-video URLs (segments, assets, DRM) — single source of truth
         if (VideoUrlClassifier.isDrmProtected(url) || VideoUrlClassifier.isSegmentOrAsset(url)) {
             android.util.Log.d("VideoSnifferEngine", "[captureLink] Filtered out | url=${url.take(80)}")
             return
         }

         val data = CapturedLinkData(url, qualityLabel, headers)

         if (capturedLinks.none { it.url == url }) {
             capturedLinks.add(data)
             if (firstLinkTime == 0L) firstLinkTime = System.currentTimeMillis()

             android.util.Log.i("VideoSnifferEngine", "[captureLink] LINK CAPTURED #$capturedLinks.size | url=${url.take(80)}")
             ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.captureLink", "LINK CAPTURED SUCCESSFULLY!",
                  "url" to url.take(100),
                  "quality" to qualityLabel,
                  "totalLinks" to capturedLinks.size
             )

             // If we found a master m3u8 or blob, we might want to finish early or shortly
             if (url.contains("master.m3u8") || url.startsWith("blob:")) {
                  android.util.Log.i("VideoSnifferEngine", "[captureLink] High confidence link found, suggesting exit.")
             }

             // Update UI and Check Exit
             CoroutineScope(Dispatchers.Main).launch {
                 updateDialogText("Found ${capturedLinks.size} video stream(s)...")
                 android.util.Log.i("VideoSnifferEngine", "[captureLink] Updated UI: Found ${capturedLinks.size} video stream(s)")
                 // Trigger exit check immediately
                 checkExitCondition()
             }
         } else {
             android.util.Log.w("VideoSnifferEngine", "[captureLink] Duplicate URL captured (This is expected if page reloads or loops) | url=${url.take(80)}")
         }
    }

    // Instance accessible exit check
    private fun checkExitCondition() {
        if (resultDelivered) return

        // We only auto-exit for VideoFound condition from here
        val currentExitCondition = exitConditionReference ?: return

        if (currentExitCondition is ExitCondition.VideoFound) {
            val count = capturedLinks.size
            if (count >= currentExitCondition.minCount) {
                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.checkExitCondition", "Exit condition met!", "count" to count)

                // === SMART EXIT STRATEGY ===
                // Check if we should wait for a Master M3U8
                val hasMaster = capturedLinks.any { it.url.contains("master.m3u8", ignoreCase = true) }
                val timeSinceFirst = System.currentTimeMillis() - firstLinkTime

                if (!hasMaster && timeSinceFirst < SMART_WAIT_TIME_MS) {
                     ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.checkExitCondition", "Soft waiting for Master M3U8...",
                         "elapsed" to timeSinceFirst,
                         "limit" to SMART_WAIT_TIME_MS)

                     // Schedule a re-check after the remaining time
                     CoroutineScope(Dispatchers.Main).launch {
                         delay(SMART_WAIT_TIME_MS - timeSinceFirst + 100) // Small buffer
                         checkExitCondition()
                     }
                     return
                }

                // Trigger success
                resultDelivered = true
                timeoutJob?.cancel()
                videoMonitorJob?.cancel()

                // Launch coroutine to get cookies safely
                CoroutineScope(Dispatchers.Main).launch {
                    val cookies = activeWebView?.let { extractCookies(it, "") } ?: emptyMap()
                    val found = capturedLinks.toList()

                    android.util.Log.i("VideoSnifferEngine", "[checkExitCondition] EXITING! Sending ${found.size} links to deferred.")
                    found.forEach { android.util.Log.d("VideoSnifferEngine", " > Link: ${it.url}") }

                    ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.checkExitCondition", "Cleaning up UI before exit")
                    cleanup(activeWebView, activeDialog)

                    deferred?.complete(WebViewResult.Success(cookies, "", "", found))
                    android.util.Log.i("VideoSnifferEngine", "[checkExitCondition] Deferred completed.")
                }
            }
        }
    }

    private fun updateDialogText(text: String) {
        try {
            statusTextView?.text = text
        } catch (e: Exception) {}
    }

    private fun createDialog(activity: android.app.Activity, webView: WebView): Dialog {
        // TV MOUSE INTEGRATION: Wrap WebView in FrameLayout to support Overlay
        val webViewContainer = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Add WebView to FrameLayout — fills entire screen
        webViewContainer.addView(webView.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        // Status text overlay (hidden by default, shown briefly during search)
        statusTextView = TextView(activity).apply {
            text = "Looking for video streams..."
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#80000000"))
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
        }
        webViewContainer.addView(statusTextView)

        // Initialize Mouse Controller
        tvMouseController = com.cloudstream.shared.ui.TvMouseController(activity, webView)
        tvMouseController?.attach(webViewContainer)

        return Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(webViewContainer)
            setCancelable(true)

            // Make truly immersive — hide status bar and navigation bar
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

            // Forward Key Events to Mouse Controller
            setOnKeyListener { _, keyCode, event ->
                tvMouseController?.onKeyEvent(event) ?: false
            }

            setOnDismissListener {
                 // Triggered when user presses back or touches outside
                 if (!resultDelivered) {
                    ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Dialog dismissed by user")
                    resultDelivered = true
                    timeoutJob?.cancel()
                    videoMonitorJob?.cancel()

                    // User aborted, so we immediately complete with an error to stop execution
                    cleanup(webView, null)
                    deferred?.complete(WebViewResult.Error("User cancelled sniffing"))
                 }

                 // Cleanup Mouse
                 tvMouseController?.detach()
                 tvMouseController = null
            }
        }
    }

    private fun startDomVideoExtraction(view: WebView?) {
        if (resultDelivered) return
        // Double check intent (should be covered by caller, but safe to check)
        if (exitConditionReference !is ExitCondition.VideoFound) return

        if (view == null) {
            ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.startDomVideoExtraction", "WebView is null, cannot extract")
            return
        }

        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.startDomVideoExtraction", "Starting DOM video extraction polling")

        // Poll every 2 seconds to extract video sources from DOM
        CoroutineScope(Dispatchers.Main).launch {
            var attempts = 0
            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.startDomVideoExtraction", "Polling started", "maxAttempts" to 30)

            while (!resultDelivered && attempts < 30) { // Max 60 seconds
                delay(2000)
                attempts++

                ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.startDomVideoExtraction", "Polling attempt", "attempt" to attempts)

                view.evaluateJavascript("""
                    (function() {
                        console.log('[VideoSnifferEngine] DOM extraction running...');
                        var sources = [];
                        var videoCount = 0;
                        var sourceCount = 0;
                        
                        // Helper function to filter out blob URLs and segment URLs
                        function isSegmentUrl(url) {
                            if (!url) return true;
                            // Check for blob URLs
                            if (url.indexOf('blob:') === 0) return true;
                            // Check for segment/chunk patterns
                            if (/seg\d+|segment\d+|chunk\d+|part\d+|fragment\d+/i.test(url)) return true;
                            // Check for init.mp4 and .m4s files
                            if (url.indexOf('init.mp4') !== -1) return true;
                            if (url.indexOf('.m4s') !== -1) return true;
                            // Check for byte range requests
                            if (url.indexOf('byte=') !== -1 || url.indexOf('range=') !== -1) return true;
                            // Check for numbered segment paths (e.g., /001/, /segment/0/, etc.)
                            if (/\d{3,}\/[^\/]+\.mp4$/.test(url)) return true;
                            return false;
                        }
                        
                        // 1. Check video elements
                        var videos = document.querySelectorAll('video');
                        videoCount = videos.length;
                        videos.forEach(function(v) {
                            if (v.src && v.src.length > 20 && !isSegmentUrl(v.src)) {
                                console.log('[VideoSnifferEngine] Video source added:', v.src);
                                sources.push({src: v.src, type: 'video.src'});
                            }
                            if (v.currentSrc && v.currentSrc.length > 20 && !isSegmentUrl(v.currentSrc)) {
                                console.log('[VideoSnifferEngine] Video currentSrc added:', v.currentSrc);
                                sources.push({src: v.currentSrc, type: 'video.currentSrc'});
                            }
                        });
                        
                        // 2. Check source elements
                        var sourceElems = document.querySelectorAll('source');
                        sourceCount = sourceElems.length;
                        sourceElems.forEach(function(s) {
                            if (s.src && s.src.length > 20 && !isSegmentUrl(s.src)) {
                                console.log('[VideoSnifferEngine] Source element added:', s.src);
                                sources.push({src: s.src, type: 'source'});
                            }
                        });
                        
                        // 3. Check for MediaSource extensions
                        if (window.MediaSource && window.MediaSource.isTypeSupported) {
                            console.log('[VideoSnifferEngine] MediaSource is available');
                        }
                        
                        // 4. Check common player objects
                        try {
                            if (window.player && window.player.src && !isSegmentUrl(window.player.src)) {
                                console.log('[VideoSnifferEngine] window.player.src:', window.player.src);
                                sources.push({src: window.player.src, type: 'window.player'});
                            }
                            if (window.videoPlayer && window.videoPlayer.src && !isSegmentUrl(window.videoPlayer.src)) {
                                console.log('[VideoSnifferEngine] window.videoPlayer.src:', window.videoPlayer.src);
                                sources.push({src: window.videoPlayer.src, type: 'window.videoPlayer'});
                            }
                            if (window.hls && window.hls.url && !isSegmentUrl(window.hls.url)) {
                                console.log('[VideoSnifferEngine] window.hls.url:', window.hls.url);
                                sources.push({src: window.hls.url, type: 'window.hls'});
                            }
                        } catch(e) {
                            console.log('[VideoSnifferEngine] Error checking player objects:', e);
                        }
                        
                        console.log('[VideoSnifferEngine] Extraction complete. Videos:', videoCount, 'Sources:', sourceCount, 'Found:', sources.length);
                        return JSON.stringify({videoCount: videoCount, sourceCount: sourceCount, sources: sources});
                    })()
                """) { result ->
                    try {
                        ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Raw result received", "result" to (result ?: "null"))

                        if (!result.isNullOrBlank() && result != "null") {
                            // FIX: Unescape the JSON string first
                            val jsonString = org.json.JSONTokener(result).nextValue().toString()
                            val jsonObj = org.json.JSONObject(jsonString)
                            val videoCount = jsonObj.optInt("videoCount", 0)
                            val sourceCount = jsonObj.optInt("sourceCount", 0)
                            val sourcesArray = jsonObj.optJSONArray("sources")

                            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Page analysis",
                                "videos" to videoCount,
                                "sources" to sourceCount,
                                "foundUrls" to (sourcesArray?.length() ?: 0)
                            )

                            if (sourcesArray != null && sourcesArray.length() > 0) {
                                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Found video sources!", "count" to sourcesArray.length())

                                for (i in 0 until sourcesArray.length()) {
                                    val obj = sourcesArray.getJSONObject(i)
                                    val src = obj.optString("src")
                                    val type = obj.optString("type")

                                    if (src.isNotBlank() && src.length > 20) {
                                        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Capturing URL", "url" to src.take(100), "type" to type)
                                        captureLink(src, type, emptyMap())
                                    }
                                }
                            } else {
                                ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "No video sources found in this poll")
                            }
                        } else {
                            ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Empty or null result from JS")
                        }
                    } catch (e: Exception) {
                        ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Error parsing result", e)
                    }
                }
            }

            if (resultDelivered) {
                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.startDomVideoExtraction", "Polling ended (result delivered)", "totalAttempts" to attempts)
            } else if (attempts >= 30) {
                ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.startDomVideoExtraction", "Polling ended (max attempts reached)", "totalAttempts" to attempts)
            }
        }
    }

    inner class SnifferBridge {
        @JavascriptInterface
        fun onSourcesFound(json: String) {
             if (resultDelivered) return
             android.util.Log.i("VideoSnifferEngine", "SnifferBridge: Sources found! jsonLen=${json.length}")
             ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.SnifferBridge", "JS Bridge: Sources found!", "jsonLength" to json.length)
             try {
                val array = org.json.JSONArray(json)
                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.SnifferBridge", "Parsing JS sources", "count" to array.length())

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val src = item.optString("src")
                    val url = item.optString("url") // Alternative key
                    val file = item.optString("file") // JWPlayer uses 'file'
                    val type = item.optString("type")
                    val label = item.optString("label", "JS-Source")

                    // Try all possible source keys
                    val finalSrc = when {
                        src.isNotBlank() -> src
                        url.isNotBlank() -> url
                        file.isNotBlank() -> file
                        else -> ""
                    }

                    ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.SnifferBridge", "Processing source #$i",
                        "src" to (finalSrc.take(80) ?: "empty"),
                        "type" to type,
                        "label" to label
                    )

                    if (finalSrc.isNotBlank() && finalSrc.length > 20) {
                         ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.SnifferBridge", "Capturing source from JS",
                             "url" to finalSrc.take(100),
                             "label" to label
                         )
                         captureLink(finalSrc, label, emptyMap())
                    } else {
                        ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.SnifferBridge", "Source rejected - too short or empty",
                            "length" to finalSrc.length
                        )
                    }
                }
             } catch (e: Exception) {
                 ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.SnifferBridge", "Failed to parse JS data", e)
             }
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("VideoSnifferJS", "BRIDGE: " + message)
        }

        @JavascriptInterface
        fun onRedirectChoice(redirectUrl: String, allowed: Boolean) {
            android.util.Log.i("VideoSnifferEngine", "Redirect choice: allowed=$allowed, url=${redirectUrl.take(80)}")
            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.SnifferBridge", "Redirect choice", "allowed" to allowed, "url" to redirectUrl.take(80))
            if (allowed) {
                // User chose to follow the redirect — load the URL in the WebView
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activeWebView?.loadUrl(redirectUrl)
                }
            } else {
                android.util.Log.i("VideoSnifferEngine", "Redirect blocked by user: ${redirectUrl.take(80)}")
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun getHtmlFromWebView(webView: WebView): String = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(
                "(function() { return document.documentElement.outerHTML; })();"
            ) { result ->
                val html = try {
                    if (result == null || result == "null") ""
                    else org.json.JSONTokener(result).nextValue().toString()
                } catch (e: Exception) {
                    ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.getHtmlFromWebView", "HTML escape failed", e)
                    ""
                }
                cont.resume(html) {}
            }
        }
    }

    /**
     * Extracts cookies using JavaScript to get exactly what the page sees.
     * This is critical for Cloudflare which binds cookies to the specific JS context.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun extractCookies(webView: WebView, url: String): Map<String, String> = suspendCancellableCoroutine { cont ->
        try {
            // 1. Try to get from CookieManager first (fast path)
            val cmCookies = CookieManager.getInstance().getCookie(url)
            val cmMap = if (!cmCookies.isNullOrBlank()) {
                parseCookieString(cmCookies)
            } else emptyMap()

            // 2. Execute JS to get document.cookie (source of truth)
            webView.evaluateJavascript("(function() { return document.cookie; })();") { result ->
                try {
                    val jsCookieString = if (result != null && result != "null") {
                        result.removeSurrounding("\"")
                    } else ""

                    val jsMap = parseCookieString(jsCookieString)

                    // Merge: JS wins on conflict, but keep CM cookies that JS might miss (HttpOnly)
                    val merged = HashMap<String, String>()
                    merged.putAll(cmMap)
                    merged.putAll(jsMap) // JS overwrites

                    ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.extractCookies", "Cookie extraction complete",
                        "cmCount" to cmMap.size,
                        "jsCount" to jsMap.size,
                        "total" to merged.size,
                        "hasClearance" to merged.containsKey("cf_clearance")
                    )

                    if (cont.isActive) cont.resume(merged) {}

                } catch (e: Exception) {
                    ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.extractCookies", "JS parse failed", e)
                    if (cont.isActive) cont.resume(cmMap) {}
                }
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.extractCookies", "Extraction failed", e)
            if (cont.isActive) cont.resume(emptyMap()) {}
        }
    }

    private fun parseCookieString(cookie: String): Map<String, String> {
        return cookie.split(";").associate {
            val parts = it.split("=", limit = 2)
            (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() }
    }

    private fun cleanup(webView: WebView?, dialog: Dialog?) {
        try {
            dialog?.dismiss()
            webView?.let { view ->
                // Must run on main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        view.stopLoading()
                        view.loadUrl("about:blank")
                        view.clearHistory()
                        view.removeAllViews()
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.cleanup", "Error", "error" to e.message)
                    }
                }
            }
            // Clear active references
            if (activeWebView == webView) activeWebView = null
            if (activeDialog == dialog) activeDialog = null
        } catch (e: Exception) {
            ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.cleanup", "Error", "error" to e.message)
        }
    }
}
