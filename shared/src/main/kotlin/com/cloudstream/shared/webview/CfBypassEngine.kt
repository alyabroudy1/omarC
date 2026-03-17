package com.cloudstream.shared.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.cloudstream.shared.cloudflare.CloudflareDetector
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_WEBVIEW
import kotlinx.coroutines.*

/**
 * Cloudflare bypass engine using Android WebView.
 *
 * Handles [ExitCondition.PageLoaded] and [ExitCondition.CookiesPresent] exit conditions.
 * Runs the WebView in HEADLESS mode (no UI) to solve CF challenges and extract cookies.
 *
 * DECOUPLED FROM STATE: This engine does NOT store cookies.
 * It returns cookies in [WebViewResult.Success], and the caller
 * (ProviderHttpService) is responsible for updating SessionState.
 */
class CfBypassEngine(
    private val activityProvider: () -> android.app.Activity?
) {
    private var deferred: CompletableDeferred<WebViewResult>? = null
    private var resultDelivered = false
    private var timeoutJob: Job? = null
    private var tvMouseController: com.cloudstream.shared.ui.TvMouseController? = null
    private var sessionStartTime: Long = 0L
    
    /** Minimum time (ms) to dwell in WebView before allowing exit during CF bypass.
     *  Gives CF Turnstile time to complete challenge and set cf_clearance cookie. */
    private val MIN_DWELL_TIME_MS = 1000L

    /**
     * Run a WebView session for CF bypass.
     *
     * @param url The URL to load
     * @param mode HEADLESS or FULLSCREEN (FULLSCREEN shows a dialog for manual CAPTCHA)
     * @param userAgent The user agent string to use
     * @param exitCondition When to stop: [ExitCondition.PageLoaded] or [ExitCondition.CookiesPresent]
     * @param timeout Maximum time to wait in milliseconds
     * @param delayMs Optional delay after page load before checking exit condition
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun runSession(
        url: String,
        mode: Mode,
        userAgent: String,
        exitCondition: ExitCondition,
        timeout: Long = 60_000L,
        delayMs: Long = 0L
    ): WebViewResult = withContext(Dispatchers.Main) {

        val activity = activityProvider()
        if (activity == null) {
            ProviderLogger.e(TAG_WEBVIEW, "CfBypassEngine.runSession", "No Activity available")
            return@withContext WebViewResult.Error("No Activity context")
        }

        this@CfBypassEngine.deferred = CompletableDeferred<WebViewResult>()
        val deferred = this@CfBypassEngine.deferred!!
        resultDelivered = false
        sessionStartTime = System.currentTimeMillis()
        var dialog: android.app.Dialog? = null
        var webView: WebView? = null

        // Timeout handler
        this@CfBypassEngine.timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(timeout)
            if (!resultDelivered) {
                resultDelivered = true
                val partialHtml = try {
                    webView?.let { getHtmlFromWebView(it) }
                } catch (e: Exception) { null }

                cleanup(webView, dialog)
                deferred.complete(WebViewResult.Timeout(url, partialHtml))
            }
        }

        try {
            ProviderLogger.i(TAG_WEBVIEW, "CfBypassEngine.runSession", "Creating WebView", "url" to url.take(80))

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
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                }
            }

            // UA VERIFICATION: Log the exact UA WebView is using
            ProviderLogger.i(TAG_WEBVIEW, "CfBypassEngine.runSession", "WebView UA",
                "ua" to webView.settings.userAgentString)

            // Setup cookies
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            // Setup based on mode
            when (mode) {
                Mode.HEADLESS -> {
                    ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine.runSession", "HEADLESS mode", "url" to url.take(80))
                }
                Mode.FULLSCREEN -> {
                    dialog = createCfDialog(activity, webView)
                    dialog.show()
                    ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine.runSession", "FULLSCREEN mode", "url" to url.take(80))
                }
            }

            // Setup WebViewClient
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (resultDelivered) return
                    ProviderLogger.i(TAG_WEBVIEW, "CfBypassEngine.onPageStarted", "Page started", "url" to url?.take(80))

                    // Inject anti-bot spoofing (hide WebView/automation markers)
                    view?.evaluateJavascript(
                        """
                        (function() {
                            // Hide webdriver flag (CF checks this for headless bot detection)
                            Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                            
                            // Polyfill for sites that expect object__info
                            if (typeof window.object__info === 'undefined') {
                                window.object__info = {};
                            }
                        })();
                        """.trimIndent(), null
                    )

                    super.onPageStarted(view, url, favicon)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val nextUrl = request?.url?.toString()
                    if (nextUrl.isNullOrBlank()) return super.shouldOverrideUrlLoading(view, request)

                    // Block non-HTTP schemes
                    val scheme = request?.url?.scheme?.lowercase()
                    if (scheme != null && scheme != "http" && scheme != "https") {
                        ProviderLogger.w(TAG_WEBVIEW, "CfBypassEngine.shouldOverrideUrlLoading",
                            "Blocked non-HTTP redirect", "scheme" to scheme, "url" to nextUrl.take(80))
                        return true
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
                            // Same domain redirect — allow (legitimate redirects like canonical URLs)
                            ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine.shouldOverrideUrlLoading", "Same-domain redirect (allowed)", "url" to nextUrl.take(80))
                            return false
                        }

                        // Cross-domain redirect — BLOCK in CF bypass mode to prevent ad hijacking
                        android.util.Log.w("CfBypassEngine", "Cross-domain redirect BLOCKED: $targetBase → $nextBase ($nextUrl)")
                        ProviderLogger.w(TAG_WEBVIEW, "CfBypassEngine.shouldOverrideUrlLoading", "Cross-domain redirect BLOCKED",
                            "from" to targetBase, "to" to nextBase, "url" to nextUrl.take(100)
                        )
                        return true
                    } catch (e: Exception) {
                        android.util.Log.w("CfBypassEngine", "Error in redirect check: ${e.message}")
                        ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine.shouldOverrideUrlLoading", "Redirect (parse error, allowing)", "url" to nextUrl.take(80))
                        return false
                    }
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (resultDelivered) return
                    val currentUrl = view?.url ?: loadedUrl ?: url
                    ProviderLogger.i(TAG_WEBVIEW, "CfBypassEngine.onPageFinished", "Page finished", "url" to currentUrl.take(80))

                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            if (delayMs > 0) {
                                delay(delayMs)
                            }

                            val html = getHtmlFromWebView(view!!)

                            // Check exit condition
                            val shouldExit = when (exitCondition) {
                                is ExitCondition.PageLoaded -> {
                                    val isCf = CloudflareDetector.isCloudflareChallenge(html)
                                    if (isCf) {
                                        ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine.onPageFinished", "Still in CF challenge")
                                        false
                                    } else {
                                        // Minimum dwell time: don't exit too fast, give CF Turnstile
                                        // time to complete its challenge and set cf_clearance cookie
                                        val elapsed = System.currentTimeMillis() - sessionStartTime
                                        if (elapsed < MIN_DWELL_TIME_MS) {
                                            ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine.onPageFinished",
                                                "Dwell time not met, waiting", "elapsed" to elapsed, "min" to MIN_DWELL_TIME_MS)
                                            // Schedule a re-check after the remaining dwell time
                                            CoroutineScope(Dispatchers.Main).launch {
                                                delay(MIN_DWELL_TIME_MS - elapsed)
                                                if (!resultDelivered) {
                                                    // Re-evaluate with fresh cookies after dwell
                                                    val freshHtml = getHtmlFromWebView(view!!)
                                                    val stillCf = CloudflareDetector.isCloudflareChallenge(freshHtml)
                                                    val isReal = CloudflareDetector.isRealContent(freshHtml)
                                                    if (!stillCf && isReal) {
                                                        resultDelivered = true
                                                        timeoutJob?.cancel()
                                                        val freshCookies = extractCookies(view, currentUrl)
                                                        ProviderLogger.i(TAG_WEBVIEW, "CfBypassEngine.onPageFinished", "Exit after dwell time",
                                                            "cookies" to freshCookies.size, "hasClearance" to freshCookies.containsKey("cf_clearance"))
                                                        cleanup(view, dialog)
                                                        deferred.complete(WebViewResult.Success(freshCookies, freshHtml, currentUrl))
                                                    } else {
                                                        ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine.onPageFinished", "After dwell: still blocked or not real content")
                                                    }
                                                }
                                            }
                                            false // Don't exit yet, the delayed re-check will handle it
                                        } else {
                                            // Dwell time met — do secondary content validation
                                            val isReal = CloudflareDetector.isRealContent(html)
                                            if (!isReal) {
                                                ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine.onPageFinished",
                                                    "Page passed CF check but looks like error page", "htmlLength" to html.length)
                                            }
                                            isReal
                                        }
                                    }
                                }
                                is ExitCondition.CookiesPresent -> {
                                    val cookies = extractCookies(view, currentUrl)
                                    exitCondition.keys.all { key -> cookies.containsKey(key) }
                                }
                                is ExitCondition.VideoFound -> {
                                    // CfBypassEngine does not handle VideoFound — always false
                                    false
                                }
                            }

                            if (shouldExit) {
                                resultDelivered = true
                                timeoutJob?.cancel()

                                val cookies = extractCookies(view, currentUrl)
                                ProviderLogger.i(TAG_WEBVIEW, "CfBypassEngine.onPageFinished", "Exit condition met",
                                    "cookies" to cookies.size, "hasClearance" to cookies.containsKey("cf_clearance"))

                                cleanup(view, dialog)
                                deferred.complete(WebViewResult.Success(cookies, html, currentUrl))
                            }
                        } catch (e: Exception) {
                            ProviderLogger.e(TAG_WEBVIEW, "CfBypassEngine.onPageFinished", "Error", e)
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
                        ProviderLogger.w(TAG_WEBVIEW, "CfBypassEngine.onReceivedError", "WebView error",
                            "description" to description, "url" to request.url.toString().take(80))
                    }
                }
            }

            // Add WebChromeClient for console logging
            webView.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        val msg = "${it.message()} [${it.sourceId()}:${it.lineNumber()}]"
                        when (it.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR ->
                                android.util.Log.e("CfBypassJS", msg)
                            android.webkit.ConsoleMessage.MessageLevel.WARNING ->
                                android.util.Log.w("CfBypassJS", msg)
                            else ->
                                android.util.Log.d("CfBypassJS", msg)
                        }
                    }
                    return true
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress % 20 == 0) {
                        ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine", "Loading progress", "percent" to newProgress)
                    }
                }
            }

            // Load URL
            ProviderLogger.i(TAG_WEBVIEW, "CfBypassEngine.runSession", "Loading URL", "url" to url.take(80))

            val extraHeaders = mutableMapOf<String, String>()
            extraHeaders["X-Requested-With"] = ""

            webView.loadUrl(url, extraHeaders)

        } catch (e: Exception) {
            resultDelivered = true
            timeoutJob?.cancel()
            cleanup(webView, dialog)
            deferred.complete(WebViewResult.Error(e.message ?: "Unknown error"))
        }

        // Wait for result with cancellation support
        try {
            deferred.await()
        } finally {
            if (!resultDelivered) {
                resultDelivered = true
                timeoutJob?.cancel()
                ProviderLogger.w(TAG_WEBVIEW, "CfBypassEngine.runSession", "Parent coroutine cancelled, forcing cleanup")
                cleanup(webView, dialog)
            }
        }
    }

    /**
     * Creates a fullscreen dialog for manual CAPTCHA solving.
     * Includes TV mouse controller so users can navigate and click
     * the Cloudflare/Turnstile challenge using a D-pad remote.
     */
    private fun createCfDialog(activity: android.app.Activity, webView: WebView): android.app.Dialog {
        val container = android.widget.FrameLayout(activity).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        container.addView(webView.apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        // TV MOUSE: Attach cursor overlay so D-pad can navigate the CF challenge
        tvMouseController = com.cloudstream.shared.ui.TvMouseController(activity, webView)
        tvMouseController?.attach(container)

        return android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
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

            // Forward D-pad key events to the TV mouse controller
            setOnKeyListener { _, _, event ->
                tvMouseController?.onKeyEvent(event) ?: false
            }

            setOnDismissListener {
                // Cleanup TV mouse controller
                tvMouseController?.detach()
                tvMouseController = null

                if (!resultDelivered) {
                    ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine", "Dialog dismissed by user")
                    resultDelivered = true
                    timeoutJob?.cancel()
                    cleanup(webView, null)
                    deferred?.complete(WebViewResult.Error("User cancelled CF bypass"))
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
                    ProviderLogger.e(TAG_WEBVIEW, "CfBypassEngine.getHtmlFromWebView", "HTML escape failed", e)
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

                    ProviderLogger.d(TAG_WEBVIEW, "CfBypassEngine.extractCookies", "Cookie extraction complete",
                        "cmCount" to cmMap.size,
                        "jsCount" to jsMap.size,
                        "total" to merged.size,
                        "hasClearance" to merged.containsKey("cf_clearance")
                    )

                    if (cont.isActive) cont.resume(merged) {}

                } catch (e: Exception) {
                    ProviderLogger.e(TAG_WEBVIEW, "CfBypassEngine.extractCookies", "JS parse failed", e)
                    if (cont.isActive) cont.resume(cmMap) {}
                }
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG_WEBVIEW, "CfBypassEngine.extractCookies", "Extraction failed", e)
            if (cont.isActive) cont.resume(emptyMap()) {}
        }
    }

    private fun parseCookieString(cookie: String): Map<String, String> {
        return cookie.split(";").associate {
            val parts = it.split("=", limit = 2)
            (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() }
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
                        view.removeAllViews()
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        view.destroy()
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG_WEBVIEW, "CfBypassEngine.cleanup", "Error", "error" to e.message)
                    }
                }
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG_WEBVIEW, "CfBypassEngine.cleanup", "Error", "error" to e.message)
        }
    }
}
