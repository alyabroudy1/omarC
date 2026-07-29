package com.cloudstream.shared.webview

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.network.pinToIpv4
import com.cloudstream.shared.network.reportPeerAddress
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

        /**
         * Hosts whose player refuses to load the stream when it detects an ad blocker — for these
         * we deliberately let the ads through, because a blocked ad means no video at all.
         * upns.online ships "Adblock Detected" / "Please disable AdBlock to watch this video" /
         * `allowAdblock` in its bundle, and stalls without ever calling its own /api/v1/video.
         */
        private val ADBLOCK_SENSITIVE_HOSTS = listOf("upns.online", "upnshare")

        fun isAdblockSensitive(url: String): Boolean {
            val lower = url.lowercase()
            return ADBLOCK_SENSITIVE_HOSTS.any { lower.contains(it) }
        }

        /**
         * Hosts that mint stream URLs pinned to the caller's IP while serving the media from an
         * IPv4-only CDN. The page load for these MUST go out over IPv4, or the token embeds an
         * IPv6 address the player can never present and every stream 403s.
         *
         * The WebView has no DNS knob, so for these hosts the page request is re-issued through
         * OkHttp with [com.cloudstream.shared.network.PreferIpv4Dns] and handed back to the
         * WebView. FaselHD is the reference case: `www.fasel-hd.cam` is dual-stack, `c.scdns.io`
         * has no AAAA, and the minted path looks like
         *   …/stream/v1/hls/<id>/<exp>/www.fasel-hd.cam/all/2001:16b8:…/yes/…
         */
        private val IPV4_TOKEN_HOSTS = listOf("fasel-hd.cam", "faselhd")

        fun isIpv4TokenHost(url: String): Boolean {
            val lower = url.lowercase()
            return IPV4_TOKEN_HOSTS.any { lower.contains(it) }
        }

        /**
         * JS snippet to make iframes fullscreen for sniffer-as-player mode.
         *
         * The CSS MUST live in a backtick template literal: a single-quoted JS string cannot span
         * lines, so writing it with `'` made the whole snippet fail to parse
         * ("Uncaught SyntaxError: Invalid or unexpected token") and silently do nothing on every
         * page — evaluateJavascript reports parse errors only to the console, never to the caller.
         */
        val FULLSCREEN_IFRAME_JS = """
            (function() {
                var style = document.createElement('style');
                style.textContent = `
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
                `;
                document.head.appendChild(style);
            })()
        """.trimIndent()
    }

    // Instance variables to share state with helper methods
    private var deferred: CompletableDeferred<WebViewResult>? = null
    private var resultDelivered = false
    private var timeoutJob: Job? = null
    private var videoMonitorJob: Job? = null
    /**
     * The DOM-scrape poller for the page currently loaded.
     *
     * Held so a reload can cancel the previous one. Every `onPageFinished` starts a poller, and a
     * host that bounces the page — a Cloudflare managed challenge does it twice — otherwise leaves
     * one running per load, all polling the same WebView and racing to deliver the same result.
     * The `activeWebView != view` guard cannot catch this: it is the same WebView reloading.
     */
    private var domPollJob: Job? = null
    private var exitConditionReference: ExitCondition? = null
    private var activeWebView: WebView? = null
    private var activeDialog: Dialog? = null
    private var statusTextView: TextView? = null
    private var tvMouseController: com.cloudstream.shared.ui.TvMouseController? = null
    
    // Skip Server Overlay state
    private var skipOverlayContainer: android.widget.LinearLayout? = null
    private var skipCountdownText: TextView? = null
    private var skipCountdownJob: Job? = null
    private var skipButton: android.widget.Button? = null

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

        // Decided once per session from the target URL: ad blocking is counter-productive on hosts
        // that withhold the stream when they detect it.
        val adblockSensitive = isAdblockSensitive(url)
        if (adblockSensitive) {
            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession",
                "Adblock-sensitive host — ad blocking disabled for this session", "url" to url.take(60))
        }

        // Same-host page requests get re-issued over IPv4 for hosts that pin stream tokens to the
        // caller's IP (see IPV4_TOKEN_HOSTS) — otherwise the WebView resolves IPv6-first and the
        // resulting links are unplayable.
        val forceIpv4 = isIpv4TokenHost(url)
        val sessionHost = try { android.net.Uri.parse(url).host } catch (_: Exception) { null }
        val ipv4Client: okhttp3.OkHttpClient by lazy {
            com.lagradost.cloudstream3.app.baseClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .pinToIpv4()
                .reportPeerAddress()
                .build()
        }
        if (forceIpv4) {
            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession",
                "IP-pinned host — serving $sessionHost requests over IPv4 so the stream token matches the player",
                "url" to url.take(60))
        }
        var dialog: Dialog? = null
        var webView: WebView? = null

        // BUGFIX: Clear capturedLinks at the start of each session
        capturedLinks.clear()
        firstLinkTime = 0L
        android.util.Log.d("VideoSnifferEngine", "runSession: Session started, capturedLinks cleared. URL: $url")
        ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Session started, capturedLinks cleared")

        // Timeout handler — with player mode detection
        this@VideoSnifferEngine.timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(timeout)
            if (!resultDelivered) {
                // Include any captured links on timeout
                val foundLinks = capturedLinks.toList()
                if (foundLinks.isNotEmpty()) {
                    resultDelivered = true
                    ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Timeout with ${foundLinks.size} captured links")
                    val cookies = webView?.let { extractCookies(it, url) } ?: emptyMap()
                    cleanup(webView, dialog)
                    deferred.complete(WebViewResult.Success(cookies, "", url, foundLinks))
                } else {
                    // Check if video is already playing in WebView (DRM / unsniffable)
                    val wv = webView
                    if (wv != null && dialog != null) {
                        wv.evaluateJavascript("(function(){ return window.__snifferIsVideoPlaying ? window.__snifferIsVideoPlaying() : false; })()") { isPlaying ->
                            if (!resultDelivered) {
                                if (isPlaying == "true") {
                                    // Video IS playing — keep WebView open as player
                                    resultDelivered = true
                                    ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "Timeout: video playing in WebView, transitioning to player mode")
                                    updateDialogText("") // Hide status text

                                    // Attach cleanup listener for when user exits the player
                                    dialog!!.setOnDismissListener {
                                        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.runSession", "WebView player dialog dismissed, cleaning up")

                                        // Cleanup TV mouse controller
                                        tvMouseController?.detach()
                                        tvMouseController = null

                                        try {
                                            webView?.stopLoading()
                                            webView?.loadUrl("about:blank")
                                            webView?.destroy()
                                        } catch (e: Exception) {
                                            android.util.Log.w("VideoSnifferEngine", "Cleanup on dismiss error: ${e.message}")
                                        }
                                        activeWebView = null
                                        activeDialog = null
                                    }

                                    deferred.complete(WebViewResult.PlayingInWebView(dialog!!))
                                } else {
                                    // Nothing playing — genuine timeout
                                    resultDelivered = true
                                    cleanup(webView, dialog)
                                    deferred.complete(WebViewResult.Timeout(url, null))
                                }
                            }
                        }
                    } else {
                        resultDelivered = true
                        cleanup(webView, dialog)
                        deferred.complete(WebViewResult.Timeout(url, null))
                    }
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
                // CRITICAL FOR TV MOUSE: Prevent WebView from stealing D-Pad focus.
                // If focusable, Cloudflare checkboxes and HTML inputs will trap the D-pad
                // and freeze the TvMouseController.
                isFocusable = false
                isFocusableInTouchMode = false
                
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false // Block JS popups (ad windows)
                    setSupportMultipleWindows(true) // Keep true to intercept and block in onCreateWindow
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
                        // A sniffing WebView renders nothing, so its favicon is pure cost. Worse,
                        // it is a same-host GET, so it used to be re-issued through the IPv4 branch
                        // below and counted against the site's rate limit — four of them came back
                        // 429 while a Cloudflare challenge was in flight.
                        if (request.url?.path?.endsWith("/favicon.ico", ignoreCase = true) == true) {
                            return android.webkit.WebResourceResponse(
                                "image/x-icon", "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        // LAYER 0: IPv4 pinning for hosts that bake the caller's IP into stream
                        // tokens. The page request must leave over IPv4 or the token is minted for
                        // an IPv6 address the IPv4-only media edge can never match (verified: the
                        // master playlist returns 200 and happily hands out variant URLs carrying
                        // the IPv6, and every one of those variants 403s). The WebView has no DNS
                        // setting, so the request is re-issued through OkHttp instead.
                        // Cloudflare's own challenge machinery is deliberately NOT re-issued through
                        // OkHttp. Its TLS fingerprint is what these sites block in the first place
                        // (that is why ProviderHttpService needs a Chrome-TLS tier at all), so
                        // serving the challenge from it invites a re-challenge. Let Chrome's stack
                        // answer its own challenge.
                        //
                        // Safe for the IPv4 pin: /cdn-cgi/* mints no stream token. The token is
                        // embedded in the video_player document, which is a same-host GET and still
                        // takes the IPv4 path below, and the media hosts (*.scdns.io) are different
                        // hosts that never enter this branch at all.
                        val isCfChallengeAsset =
                            request.url?.path?.startsWith("/cdn-cgi/", ignoreCase = true) == true

                        if (forceIpv4 && sessionHost != null && !isCfChallengeAsset &&
                            request.method.equals("GET", ignoreCase = true) &&
                            request.url?.host?.equals(sessionHost, ignoreCase = true) == true
                        ) {
                            try {
                                val rb = okhttp3.Request.Builder().url(requestUrl)
                                request.requestHeaders?.forEach { (k, v) ->
                                    // Host is computed by OkHttp; Accept-Encoding must stay
                                    // unset so it can transparently handle gzip for us.
                                    if (!k.equals("Host", true) && !k.equals("Accept-Encoding", true)) {
                                        try { rb.header(k, v) } catch (_: Exception) {}
                                    }
                                }
                                try {
                                    CookieManager.getInstance().getCookie(requestUrl)
                                        ?.let { rb.header("Cookie", it) }
                                } catch (_: Exception) {}

                                val resp = ipv4Client.newCall(rb.build()).execute()
                                resp.headers("Set-Cookie").forEach {
                                    try { CookieManager.getInstance().setCookie(requestUrl, it) } catch (_: Exception) {}
                                }
                                val ctype = resp.header("content-type")?.substringBefore(";")?.trim()
                                    ?: "text/html"
                                val enc = resp.header("content-type")
                                    ?.substringAfter("charset=", "")?.takeIf { it.isNotBlank() } ?: "utf-8"
                                // Report the address actually connected, never the intent — this
                                // request mints the IP-pinned token, so the family it went out on
                                // is the single most important fact in the log.
                                val peer = resp.header(
                                    com.cloudstream.shared.network.PEER_ADDRESS_HEADER
                                ) ?: "unknown"
                                if (peer.contains(':')) {
                                    ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.intercept",
                                        "IPv4 pin FAILED — served over IPv6, any minted token will be unplayable",
                                        "url" to requestUrl.take(90), "peer" to peer, "code" to resp.code)
                                } else {
                                    ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.intercept",
                                        "Served over IPv4", "url" to requestUrl.take(90),
                                        "peer" to peer, "code" to resp.code)
                                }
                                return android.webkit.WebResourceResponse(
                                    ctype, enc, resp.body?.byteStream()
                                )
                            } catch (e: Exception) {
                                ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.intercept",
                                    "IPv4 fetch failed, falling back to WebView stack (token may be IPv6-pinned)",
                                    "url" to requestUrl.take(90), "err" to (e.message ?: ""))
                            }
                        }

                        // LAYER 1: Ad blocking — block known ad domains at network level.
                        // Skipped for hosts that refuse to play when ads don't load.
                        if (!adblockSensitive && AdBlocker.shouldBlockRequest(requestUrl)) {
                            ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.intercept", "Ad blocked", "url" to requestUrl.take(80))
                            return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream("".toByteArray()))
                        }

                        // Log requests for debugging
                        if (requestCounter % 10 == 0 || requestUrl.contains(".m3u8") || requestUrl.contains(".mp4") || requestUrl.contains("video") || requestUrl.contains("stream")) {
                            android.util.Log.d("VideoSnifferEngine", "intercept: Request #$requestCounter url=${requestUrl.take(100)}")
                            ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.intercept", "Request #$requestCounter",
                                "url" to requestUrl.take(100),
                                "method" to (request?.method ?: "?"),
                                "isMainFrame" to (request?.isForMainFrame ?: false)
                            )
                        }

                        // Check if it's a video URL
                        if (VideoUrlClassifier.isVideoUrl(requestUrl)) {
                             ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.intercept", "VIDEO URL DETECTED!",
                                 "url" to requestUrl.take(100),
                                 "requestNum" to requestCounter
                             )
                             captureLink(requestUrl, "Network", request?.requestHeaders ?: emptyMap())
                        } else if (requestUrl.contains(".m3u8") || requestUrl.contains(".mp4")) {
                            // Debug, not warning, and only for things that really look like media:
                            // the bare substrings "video"/"stream" match the embed document itself
                            // and most ad scripts, so every page load used to emit warnings that
                            // buried the real diagnostics.
                            ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.intercept", "URL rejected",
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

                    // Block non-HTTP schemes (intent://, market://, tg://, whatsapp://, etc.)
                    val scheme = request?.url?.scheme?.lowercase()
                    if (scheme != null && scheme != "http" && scheme != "https") {
                        ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.shouldOverrideUrlLoading",
                            "Blocked non-HTTP redirect", "scheme" to scheme, "url" to nextUrl.take(80))
                        return true
                    }

                    // Auto-accept redirects to embed page URLs (/e/, /v/, /f/ within first 70 chars).
                    // Embed hosts use these path patterns for their video player pages (e.g., 
                    // mixdrop.ag/e/XXXXX, filelions.to/v/XXXXX, ds2play.com/e/XXXXX).
                    // Without this, cross-domain embed redirects get stuck behind the user
                    // confirmation dialog which auto-rejects after 8s — too slow for multi-server fallback.
                    val first70 = nextUrl.take(70)
                    if ((nextUrl.startsWith("http://") || nextUrl.startsWith("https://")) &&
                        (first70.contains("/e/") || first70.contains("/v/") || first70.contains("/f/"))) {
                        ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.shouldOverrideUrlLoading",
                            "Auto-accepting embed redirect", "url" to nextUrl.take(80))
                        return false
                    }

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
                            
                            // 2. Fingerprint Spoofing (Match Desktop UA & Hide Automation)
                            try {
                                Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                            } catch(e) {}
                            
                            if (navigator.userAgent.indexOf("Windows") !== -1) {
                                try {
                                    Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; } });
                                } catch(e) {}
                                try {
                                    Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; } });
                                } catch(e) {}
                            }

                            // 2b. Headless tells that a plain Android WebView fails even though it
                            //     is a real browser engine: no window.chrome, and empty
                            //     plugins/mimeTypes/languages. Players that gate on "Headless
                            //     Detected" (upns.online does — the string is in its bundle) check
                            //     exactly these, then silently never request the stream.
                            try {
                                if (typeof window.chrome === 'undefined') {
                                    window.chrome = { runtime: {}, app: { isInstalled: false }, csi: function(){}, loadTimes: function(){} };
                                }
                            } catch(e) {}
                            // NB: the fakes must be array-LIKE, never real Arrays. A genuine
                            // navigator.plugins is a PluginArray, so `Array.isArray(navigator.plugins)`
                            // is false in every real browser — sites probe exactly that to catch
                            // spoofers (CimaNow ships `Array.isArray(navigator.plugins) &&
                            // navigator.plugins[0] === 1` as a bot signal). Using an array literal
                            // here would hand them the tell we are trying to hide.
                            try {
                                if (!navigator.plugins || navigator.plugins.length === 0) {
                                    var fakePlugins = Object.create(null);
                                    var pluginList = [
                                        { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                                        { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                                        { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' }
                                    ];
                                    for (var pi = 0; pi < pluginList.length; pi++) fakePlugins[pi] = pluginList[pi];
                                    fakePlugins.length = pluginList.length;
                                    fakePlugins.item = function(i) { return this[i] || null; };
                                    fakePlugins.namedItem = function(n) { for (var i=0;i<this.length;i++) if (this[i].name===n) return this[i]; return null; };
                                    fakePlugins.refresh = function() {};
                                    Object.defineProperty(navigator, 'plugins', { get: function() { return fakePlugins; } });
                                }
                            } catch(e) {}
                            try {
                                if (!navigator.mimeTypes || navigator.mimeTypes.length === 0) {
                                    var fakeMimes = Object.create(null);
                                    fakeMimes[0] = { type: 'application/pdf', suffixes: 'pdf', description: '' };
                                    fakeMimes.length = 1;
                                    fakeMimes.item = function(i) { return this[i] || null; };
                                    fakeMimes.namedItem = function(t) { return this[0].type === t ? this[0] : null; };
                                    Object.defineProperty(navigator, 'mimeTypes', { get: function() { return fakeMimes; } });
                                }
                            } catch(e) {}
                            try {
                                if (!navigator.languages || navigator.languages.length === 0) {
                                    Object.defineProperty(navigator, 'languages', { get: function() { return ['en-US', 'en']; } });
                                }
                            } catch(e) {}
                            
                            // 3. DisableDevtool Anti-Bot Bypass
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
                        """.trimIndent(), null
                    )

                    super.onPageStarted(view, pageUrl, favicon)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (resultDelivered) return
                    val currentUrl = view?.url ?: loadedUrl ?: url
                    android.util.Log.i("VideoSnifferEngine", "onPageFinished: url=${currentUrl.take(80)}")
                    ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Page finished", "url" to currentUrl.take(80))

                    // Inject fullscreen iframe CSS — ONLY in sniffer-as-player mode, which is what
                    // it was written for (visible session held open with SNIFFER_PLAYER_TIMEOUT_MS
                    // because nothing extractable was found).
                    //
                    // It used to be injected on every page, but the snippet had a syntax error and
                    // never actually executed, so nothing was ever hidden. Now that it parses, the
                    // rule it installs — `body > *:not(iframe) { display: none }` — would blank the
                    // content of every ordinary sniff target, including SPA players that are not
                    // iframes (Upnshare), breaking auto-click and DOM extraction. Keep it scoped.
                    val isPlayerMode = mode != Mode.HEADLESS && timeout >= SNIFFER_PLAYER_TIMEOUT_MS
                    if (isPlayerMode) {
                        view?.evaluateJavascript(FULLSCREEN_IFRAME_JS, null)
                        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Injected fullscreen iframe CSS (player mode)")
                    }

                    // LAYER 2+3: Inject ad blocking CSS and JS — unless this host refuses to play
                    // when it spots an ad blocker (see ADBLOCK_SENSITIVE_HOSTS). Blocking ads there
                    // costs us the whole stream.
                    if (adblockSensitive) {
                        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished",
                            "Ad blocker SKIPPED — host gates playback on adblock detection", "url" to currentUrl.take(60))
                    } else {
                        view?.evaluateJavascript(AdBlocker.AD_BLOCK_CSS, null)
                        view?.evaluateJavascript(AdBlocker.AD_BLOCK_JS, null)
                        ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished", "Injected ad blocker CSS+JS")
                    }

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

                    // === IMMEDIATE ERROR PAGE DETECTION ===
                    // Check page title, body text, and URL for error patterns right away
                    // instead of waiting 2s for the DOM poll cycle.
                    view?.evaluateJavascript("""
                        (function() {
                            var title = document.title ? document.title.toLowerCase().substring(0, 500) : '';
                            var body = document.body ? document.body.innerText.toLowerCase().substring(0, 3000) : '';
                            var url = window.location.href.toLowerCase();
                            var combined = title + ' ' + body + ' ' + url;
                            var errorPatterns = [
                                'file was deleted', 'video not found', '404 not found',
                                'no longer available', 'file not found',
                                "we're sorry, this video is no longer available",
                                'file deleted', 'video removed', 'content removed',
                                'this video has been removed', 'page not found',
                                'the file you requested has been deleted',
                                'تم حذف الملف', 'الملف غير موجود', 'الصفحة غير موجودة',
                                'هذا الفيديو غير متاح', 'تم الحذف', 'غير موجود',
                                'الملف المطلوب غير موجود',
                                'error 404', '404 error', '410 error',
                                'this video does not exist',
                                'access denied', 'blocked'
                            ];
                            for (var i = 0; i < errorPatterns.length; i++) {
                                if (combined.indexOf(errorPatterns[i]) !== -1) {
                                    return JSON.stringify({detected: true, pattern: errorPatterns[i]});
                                }
                            }
                            return JSON.stringify({detected: false});
                        })()
                    """) { result ->
                        if (!resultDelivered && !result.isNullOrBlank() && result != "null") {
                            try {
                                val jsonString = org.json.JSONTokener(result).nextValue().toString()
                                val jsonObj = org.json.JSONObject(jsonString)
                                if (jsonObj.optBoolean("detected", false)) {
                                    val pattern = jsonObj.optString("pattern", "unknown")
                                    ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished",
                                        "Error page detected immediately", "pattern" to pattern)
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(1500)
                                        showSkipOverlay(view)
                                    }
                                }
                            } catch (e: Exception) {
                                ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.onPageFinished",
                                    "Error parsing immediate error check", e)
                            }
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
                                is ExitCondition.PageLoaded -> false
                                is ExitCondition.CookiesPresent -> false
                                is ExitCondition.ElementsFound -> false
                                is ExitCondition.UrlMatches -> false
                                is ExitCondition.AfterDelay -> false
                            }

                            if (shouldExit) {
                                resultDelivered = true
                                timeoutJob?.cancel()
                                videoMonitorJob?.cancel()
                                domPollJob?.cancel()

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

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true && !resultDelivered) {
                        val statusCode = errorResponse?.statusCode ?: 0
                        ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.onReceivedHttpError",
                            "HTTP error on main frame",
                            "statusCode" to statusCode,
                            "url" to (request.url?.toString()?.take(80) ?: ""))
                        // 404/410/403/451 = page/file not found or access denied → skip immediately
                        if (statusCode == 404 || statusCode == 410 || statusCode == 403 || statusCode == 451) {
                            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.onReceivedHttpError",
                                "Dead/corrupted page detected via HTTP $statusCode, scheduling skip")
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(1500) // Brief grace period for page content to settle
                                showSkipOverlay(view)
                            }
                        }
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
            domPollJob?.cancel()
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
                domPollJob?.cancel()
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
         // Analytics/telemetry never counts as a stream. This guard belongs HERE, not only at the
         // network interceptor: the injected JS reports fetch/XHR URLs straight to the bridge with
         // no classification, so a tracker beacon used to be captured, satisfy the exit condition
         // and end the session with garbage before the real manifest was ever requested.
         if (VideoUrlClassifier.isBlacklisted(url)) {
             android.util.Log.d("VideoSnifferEngine", "[captureLink] Tracker rejected | url=${url.take(80)}")
             ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.captureLink", "Tracker/analytics rejected",
                 "url" to url.take(100), "label" to qualityLabel)
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
             if (VideoUrlClassifier.isMasterM3u8(url) || url.startsWith("blob:")) {
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
             android.util.Log.d("VideoSnifferEngine", "[captureLink] Duplicate URL captured (This is expected if page reloads or loops) | url=${url.take(80)}")
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
                val hasMaster = capturedLinks.any { VideoUrlClassifier.isMasterM3u8(it.url) }
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
                domPollJob?.cancel()

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
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
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

        // TV-Friendly Skip Server Overlay (hidden by default)
        val skipContainer = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT) // Let webview show through
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // The 'Card' holding the content
            val card = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(64, 64, 64, 64)
                
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#E61E1E1E")) // 90% opaque dark surface
                    cornerRadius = 32f
                }
                
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // Title
            card.addView(TextView(activity).apply {
                text = "Invalid Page or Missing Video"
                textSize = 22f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            })

            // Countdown Subtitle
            skipCountdownText = TextView(activity).apply {
                text = "Skipping server in 5s..."
                textSize = 16f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 48)
            }
            card.addView(skipCountdownText)

            // Buttons Layout
            val buttonLayout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            // Skip Now Button
            skipButton = android.widget.Button(activity).apply {
                text = "Skip Now"
                isFocusable = true
                isFocusableInTouchMode = true
                setTextColor(Color.WHITE)
                setPadding(48, 24, 48, 24)
                
                val defaultBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#D32F2F")) // Red for skip/cancel
                    cornerRadius = 16f
                }
                val focusBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F44336"))
                    cornerRadius = 16f
                    setStroke(6, Color.WHITE) // White stroke to highlight focus for TV
                }
                
                background = defaultBg
                
                setOnFocusChangeListener { _, hasFocus ->
                    background = if (hasFocus) focusBg else defaultBg
                }

                setOnClickListener {
                    skipCountdownJob?.cancel()
                    handleSkipServer(webView, activeDialog)
                }
            }
            buttonLayout.addView(skipButton)

            // Wait Button
            val waitButton = android.widget.Button(activity).apply {
                text = "Wait"
                isFocusable = true
                isFocusableInTouchMode = true
                setTextColor(Color.WHITE)
                setPadding(48, 24, 48, 24)
                
                val defaultBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#444444")) // Neutral dark gray
                    cornerRadius = 16f
                }
                val focusBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#6C757D"))
                    cornerRadius = 16f
                    setStroke(6, Color.WHITE)
                }
                
                background = defaultBg
                
                setOnFocusChangeListener { _, hasFocus ->
                    background = if (hasFocus) focusBg else defaultBg
                }

                setOnClickListener {
                    skipCountdownJob?.cancel()
                    skipOverlayContainer?.visibility = android.view.View.GONE
                }
            }
            
            val marginParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 32
            }
            buttonLayout.addView(waitButton, marginParams)

            card.addView(buttonLayout)
            addView(card)
        }
        skipOverlayContainer = skipContainer
        webViewContainer.addView(skipContainer)

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
                    domPollJob?.cancel()

                    // User aborted, so we immediately complete with an error to stop execution
                    cleanup(webView, null)
                    deferred?.complete(WebViewResult.Error("User cancelled sniffing"))
                 }

                 // Cleanup Mouse
                 tvMouseController?.detach()
                 tvMouseController = null
                 skipCountdownJob?.cancel()
            }
        }
    }

    private fun showSkipOverlay(view: WebView?) {
        if (resultDelivered || skipOverlayContainer?.visibility == android.view.View.VISIBLE) return

        skipOverlayContainer?.visibility = android.view.View.VISIBLE
        
        // Request focus on the skip button for TV remotes
        skipButton?.requestFocus()

        skipCountdownJob?.cancel()
        skipCountdownJob = CoroutineScope(Dispatchers.Main).launch {
            for (i in 5 downTo 1) {
                if (resultDelivered) break
                skipCountdownText?.text = "Skipping server in ${i}s..."
                delay(1000)
            }
            if (!resultDelivered && skipOverlayContainer?.visibility == android.view.View.VISIBLE) {
                android.util.Log.i("VideoSnifferEngine", "[showSkipOverlay] Countdown finished, auto-skipping.")
                handleSkipServer(view, activeDialog)
            }
        }
    }

    private fun handleSkipServer(view: WebView?, dialog: Dialog?) {
        if (resultDelivered) return
        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine", "Skipping server (user/auto cancelled)")
        resultDelivered = true
        timeoutJob?.cancel()
        videoMonitorJob?.cancel()
        domPollJob?.cancel()
        skipCountdownJob?.cancel()

        cleanup(view, dialog)
        deferred?.complete(WebViewResult.Error("User cancelled sniffing"))
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

        // One poller per page: a reload supersedes the poller it replaces.
        domPollJob?.cancel()

        // Poll every 2 seconds to extract video sources from DOM
        domPollJob = CoroutineScope(Dispatchers.Main).launch {
            var attempts = 0
            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.startDomVideoExtraction", "Polling started", "maxAttempts" to 30)

            while (!resultDelivered && attempts < 30) { // Max 60 seconds
                delay(2000)
                attempts++
                
                if (activeWebView != view) {
                    ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.startDomVideoExtraction", "WebView changed or destroyed, stopping polling")
                    break
                }

                ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.startDomVideoExtraction", "Polling attempt", "attempt" to attempts)

                try {
                    view.evaluateJavascript("""
                    (function() {
                        console.log('[VideoSnifferEngine] DOM extraction running...');
                        var sources = [];
                        var videoCount = 0;
                        var sourceCount = 0;

                        // Shadow-DOM-aware query. Web-component players (vidstack — upns.online)
                        // keep their <video> and controls inside shadow roots, where plain
                        // document.querySelectorAll cannot reach them.
                        function deepQueryAll(selector, root, out, depth) {
                            out = out || []; root = root || document; depth = depth || 0;
                            if (depth > 8) return out;
                            try {
                                var f = root.querySelectorAll(selector);
                                for (var i = 0; i < f.length; i++) out.push(f[i]);
                            } catch(e) {}
                            try {
                                var all = root.querySelectorAll('*');
                                for (var j = 0; j < all.length; j++) {
                                    if (all[j].shadowRoot) deepQueryAll(selector, all[j].shadowRoot, out, depth + 1);
                                }
                            } catch(e) {}
                            return out;
                        }
                        function deepText() {
                            var t = document.body ? (document.body.innerText || '') : '';
                            try {
                                var all = document.querySelectorAll('*');
                                for (var i = 0; i < all.length; i++) {
                                    if (all[i].shadowRoot) t += ' ' + (all[i].shadowRoot.textContent || '');
                                }
                            } catch(e) {}
                            return t;
                        }

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
                        var videos = deepQueryAll('video');
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
                        var sourceElems = deepQueryAll('source');
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
                        
                        // 5. Check for Server Error / Deleted File texts
                            var invalidPageDetected = false;
                            var titleText = document.title ? document.title.toLowerCase() : "";
                            var bodyText = deepText().toLowerCase();
                            var urlText = window.location.href.toLowerCase();
                            var textContent = (titleText + " " + bodyText + " " + urlText).substring(0, 5000);
                            if (textContent.length > 20) {
                                var errorTexts = [
                                    "file was deleted", "video not found", "404 not found",
                                    "no longer available", "file not found",
                                    "we're sorry, this video is no longer available",
                                    "file deleted", "video removed", "content removed",
                                    "this video has been removed", "page not found",
                                    "the file you requested has been deleted",
                                    "تم حذف الملف", "الملف غير موجود", "الصفحة غير موجودة",
                                    "هذا الفيديو غير متاح", "تم الحذف", "غير موجود",
                                    "الملف المطلوب غير موجود",
                                    "error 404", "404 error", "410 error",
                                    "this video does not exist",
                                    "access denied", "blocked"
                                ];
                                for (var i = 0; i < errorTexts.length; i++) {
                                    if (textContent.indexOf(errorTexts[i]) !== -1) {
                                        invalidPageDetected = true;
                                        break;
                                    }
                                }
                            }
                        
                        // 6. Anti-automation gates. Players that refuse to load a stream because
                        //    they detected us say so IN THE PAGE and otherwise look identical to a
                        //    slow load (video elements present, no sources, no network activity).
                        //    Surfacing the reason turns a 20s silent timeout into a named failure.
                        var blockReason = "";
                        var gates = [
                            "headless browser is not allowed", "headless detected",
                            "sandboxed our player is not allowed",
                            "please disable adblock", "adblock detected",
                            "not allowed"
                        ];
                        for (var gi = 0; gi < gates.length; gi++) {
                            if (textContent.indexOf(gates[gi]) !== -1) { blockReason = gates[gi]; break; }
                        }
                        var pageText = deepText().replace(/\s+/g, " ").trim().substring(0, 200);

                        // 7. Why is the player idle? Distinguishes "waiting for a gesture" from
                        //    "refused to load" from "wrong embed context" — all of which look
                        //    identical (3 empty <video> elements) from the outside.
                        var vstate = [];
                        try {
                            videos.forEach(function(v) {
                                vstate.push({
                                    rs: v.readyState, ns: v.networkState, paused: v.paused,
                                    muted: v.muted, cs: (v.currentSrc || "").substring(0, 60),
                                    inShadow: !!(v.getRootNode && v.getRootNode() !== document)
                                });
                            });
                        } catch(e) {}
                        var ctx = {};
                        try {
                            ctx.ref = (document.referrer || "").substring(0, 80);
                            ctx.isTop = (window.top === window.self);
                            ctx.ancestors = (location.ancestorOrigins ? location.ancestorOrigins.length : -1);
                            ctx.bodyLen = document.body ? document.body.innerHTML.length : 0;
                            ctx.shadowHosts = 0;
                            var all = document.querySelectorAll('*');
                            for (var k = 0; k < all.length; k++) if (all[k].shadowRoot) ctx.shadowHosts++;
                        } catch(e) {}

                        console.log('[VideoSnifferEngine] Extraction complete. Videos:', videoCount, 'Sources:', sourceCount, 'Found:', sources.length, 'Invalid:', invalidPageDetected, 'Block:', blockReason);
                        return JSON.stringify({videoCount: videoCount, sourceCount: sourceCount, sources: sources, invalidPageDetected: invalidPageDetected, blockReason: blockReason, pageText: pageText, vstate: vstate, ctx: ctx});
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
                            val invalidPageDetected = jsonObj.optBoolean("invalidPageDetected", false)

                            val blockReason = jsonObj.optString("blockReason", "")
                            val pageText = jsonObj.optString("pageText", "")

                            ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Page analysis",
                                "videos" to videoCount,
                                "sources" to sourceCount,
                                "foundUrls" to (sourcesArray?.length() ?: 0),
                                "invalidPage" to invalidPageDetected
                            )

                            if (blockReason.isNotBlank()) {
                                ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction",
                                    "🚫 PLAYER REFUSED TO LOAD — anti-automation gate hit",
                                    "reason" to blockReason, "pageText" to pageText.take(160))
                            } else if ((sourcesArray?.length() ?: 0) == 0 && videoCount > 0 && attempts == 3) {
                                // One-shot breadcrumb: player present but idle, and no gate message.
                                // vstate/ctx say whether it is waiting on a gesture (rs=0 ns=0),
                                // stuck loading (ns=2), or in an embed context it rejects.
                                ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction",
                                    "Player idle, no gate text",
                                    "vstate" to jsonObj.optJSONArray("vstate")?.toString()?.take(300),
                                    "ctx" to jsonObj.optJSONObject("ctx")?.toString()?.take(200),
                                    "pageText" to pageText.take(120))
                            }
                            
                            if (invalidPageDetected && (sourcesArray == null || sourcesArray.length() == 0)) {
                                ProviderLogger.w(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Invalid page detected! Initiating skip server.")
                                CoroutineScope(Dispatchers.Main).launch {
                                    showSkipOverlay(view)
                                }
                            }

                            if (sourcesArray != null && sourcesArray.length() > 0) {
                                ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Found video sources!", "count" to sourcesArray.length())

                                for (i in 0 until sourcesArray.length()) {
                                    val obj = sourcesArray.getJSONObject(i)
                                    val src = obj.optString("src")
                                    val type = obj.optString("type")

                                    if (src.isNotBlank() && src.length > 20) {
                                        ProviderLogger.i(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "Capturing URL", "url" to src.take(100), "type" to type)
                                        captureLink(src, type, emptyMap<String, String>())
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
                } catch (e: Exception) {
                    ProviderLogger.e(TAG_WEBVIEW, "VideoSnifferEngine.DOM Extraction", "evaluateJavascript execution failed", e)
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
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activeWebView?.loadUrl(redirectUrl)
                }
            } else {
                android.util.Log.i("VideoSnifferEngine", "Redirect blocked by user: ${redirectUrl.take(80)}")
            }
        }

        /**
         * Native click dispatch — called from JS to produce isTrusted=true touch events.
         * Uses WebView.dispatchTouchEvent(MotionEvent) which the browser trusts as real user input.
         *
         * @param x Physical pixel X (JS should multiply CSS pixels by devicePixelRatio)
         * @param y Physical pixel Y
         */
        @JavascriptInterface
        fun requestNativeClick(x: Float, y: Float) {
            android.util.Log.d("VideoSnifferEngine", "requestNativeClick: x=$x, y=$y")
            Handler(Looper.getMainLooper()).post {
                val wv = activeWebView ?: return@post
                try {
                    val downTime = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                    wv.dispatchTouchEvent(down)
                    down.recycle()

                    val upTime = downTime + 50L
                    val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
                    wv.dispatchTouchEvent(up)
                    up.recycle()

                    ProviderLogger.d(TAG_WEBVIEW, "VideoSnifferEngine.SnifferBridge", "Native click dispatched", "x" to x, "y" to y)
                } catch (e: Exception) {
                    android.util.Log.w("VideoSnifferEngine", "requestNativeClick failed: ${e.message}")
                }
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
        // Last line of defence: the poller outlives the WebView it scrapes otherwise.
        domPollJob?.cancel()
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
