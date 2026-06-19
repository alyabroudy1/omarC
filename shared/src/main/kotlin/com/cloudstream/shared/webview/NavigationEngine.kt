package com.cloudstream.shared.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.*
import com.cloudstream.shared.logging.ProviderLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Multi-step WebView navigation engine with trusted touch simulation.
 *
 * Simulates real user interactions (load page, click elements, wait for
 * conditions, extract HTML) in a headless or fullscreen WebView.
 * Each step produces isTrusted=true touch events via dispatchTouchEvent,
 * which anti-bot scripts cannot distinguish from real user input.
 *
 * Architecture mirrors [CfBypassEngine] and [VideoSnifferEngine]:
 * - No stored state (cookies returned to caller)
 * - Activity obtained via [activityProvider]
 * - Runs on [Dispatchers.Main] for WebView thread safety
 *
 * Typical flow for CimaNow:
 *   1. LoadUrl(movieDetailPage)        — open the movie page
 *   2. WaitForSelector("a.shine")      — wait for watch button
 *   3. ClickElement("a.shine")         — click watch (trusted touch)
 *   4. WaitForUrl("freex2line")         — wait for redirect to shortlink
 *   5. WaitForDomCondition(btnEnabled)  — wait for 10s counter
 *   6. ClickElement("#watch-btn")       — click watch+download
 *   7. WaitForUrl("cimanow.cc/watching")— wait for watch page
 *   8. ExtractHtml()                   — grab rendered server HTML
 */
class NavigationEngine(
    private val activityProvider: () -> android.app.Activity?
) {
    private val sessionMutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun execute(
        steps: List<NavigationStep>,
        userAgent: String,
        mode: Mode = Mode.HEADLESS,
        overallTimeoutMs: Long = 120_000L,
        requestInterceptor: ((
            view: WebView,
            request: WebResourceRequest
        ) -> WebResourceResponse?)? = null,
        /** Only navigation to these domains (or subdomains) is allowed. Empty = allow all. */
        allowedDomains: Set<String> = emptySet(),
        /** Once the URL matches one of these regex patterns, all main-frame navigation away is blocked. */
        destinationLockPatterns: List<Regex> = emptyList()
    ): NavigationResult = withContext(Dispatchers.Main) {
        sessionMutex.withLock {
            val activity = activityProvider()
            if (activity == null) {
                ProviderLogger.e(TAG, "execute", "No Activity available")
                return@withContext NavigationResult(
                    success = false, finalUrl = "", cookies = emptyMap(),
                    extractedHtml = emptyMap(), completedSteps = 0,
                    failedAtStep = 0, error = "No Activity context"
                )
            }

            var webView: WebView? = null
            var dialog: android.app.Dialog? = null
            var currentUrl = ""
            val extractedHtml = mutableMapOf<String, String>()
            var completedSteps = 0
            var failedStep: Int? = null
            var errorMsg: String? = null
            val result = CompletableDeferred<NavigationResult>()
            var delivered = false

            val timeoutJob = launch {
                delay(overallTimeoutMs)
                if (!delivered) {
                    delivered = true
                    ProviderLogger.w(TAG, "execute", "Overall timeout after ${overallTimeoutMs}ms")
                    cleanupWebView(webView, dialog)
                    result.complete(NavigationResult(
                        success = false, finalUrl = currentUrl,
                        cookies = extractCookiesFromManager(currentUrl),
                        extractedHtml = extractedHtml,
                        completedSteps = completedSteps,
                        failedAtStep = completedSteps,
                        error = "Overall timeout"
                    ))
                }
            }

            try {
                webView = createWebView(activity, userAgent)
                setupWebViewClient(webView, requestInterceptor, allowedDomains, destinationLockPatterns)

                if (mode == Mode.FULLSCREEN) {
                    dialog = createDialog(activity, webView)
                    dialog.show()
                }

                for ((index, step) in steps.withIndex()) {
                    if (delivered) break

                    ProviderLogger.i(TAG, "execute", "Step $index: ${step.javaClass.simpleName}")
                    try {
                        when (step) {
                            is NavigationStep.LoadUrl -> {
                                currentUrl = step.url
                                loadUrlInWebView(webView, step.url, step.referer, step.extraHeaders)
                            }
                            is NavigationStep.ClickElement -> {
                                val clicked = clickElementInWebView(webView, step.selector, step.timeoutMs)
                                if (!clicked) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: ClickElement failed for selector: ${step.selector}")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "ClickElement failed: ${step.selector}"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.ClickCoordinates -> {
                                dispatchNativeClick(webView, step.x, step.y)
                                delay(150)
                            }
                            is NavigationStep.ExecuteJs -> {
                                val jsResult = executeJsInWebView(webView, step.javascript)
                                if (step.key.isNotBlank()) {
                                    extractedHtml[step.key] = jsResult ?: ""
                                    ProviderLogger.d(TAG, "execute", "JS result stored in extractedHtml['${step.key}']: ${(jsResult ?: "").take(100)}")
                                }
                                delay(300)
                            }
                            is NavigationStep.WaitForSelector -> {
                                val found = waitForSelector(webView, step.selector, step.timeoutMs)
                                if (!found) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: WaitForSelector timed out: ${step.selector}")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "WaitForSelector timed out: ${step.selector}"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.WaitForUrl -> {
                                val found = waitForUrl(webView, step.urlPattern, step.timeoutMs)
                                if (!found) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: WaitForUrl timed out: ${step.urlPattern}")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "WaitForUrl timed out: ${step.urlPattern}"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.WaitForDelay -> {
                                delay(step.delayMs)
                            }
                            is NavigationStep.WaitForDomCondition -> {
                                val met = waitForDomCondition(webView, step.jsCondition, step.timeoutMs, step.pollIntervalMs)
                                if (!met) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: WaitForDomCondition timed out")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "WaitForDomCondition timed out"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.ExtractHtml -> {
                                val html = extractHtmlFromWebView(webView, step.selector)
                                val key = step.key.ifBlank { step.selector ?: "full_page_${index}" }
                                extractedHtml[key] = html ?: ""
                                ProviderLogger.i(TAG, "execute", "Step $index: ExtractHtml ${key.take(40)} -> ${html?.length ?: 0} chars")
                            }
                        }
                        completedSteps = index + 1
                        currentUrl = getCurrentUrlFromWebView(webView) ?: currentUrl
                    } catch (e: Exception) {
                        ProviderLogger.e(TAG, "execute", "Step $index failed", e)
                        failedStep = index
                        errorMsg = e.message
                        break
                    }
                }

                if (!delivered) {
                    delivered = true
                    timeoutJob.cancel()
                    val cookies = extractCookiesFromManager(currentUrl)
                    currentUrl = getCurrentUrlFromWebView(webView) ?: currentUrl
                    cleanupWebView(webView, dialog)
                    result.complete(NavigationResult(
                        success = failedStep == null && errorMsg == null,
                        finalUrl = currentUrl,
                        cookies = cookies,
                        extractedHtml = extractedHtml,
                        completedSteps = completedSteps,
                        failedAtStep = failedStep,
                        error = errorMsg
                    ))
                }
            } catch (e: Exception) {
                if (!delivered) {
                    delivered = true
                    timeoutJob.cancel()
                    cleanupWebView(webView, dialog)
                    result.complete(NavigationResult(
                        success = false, finalUrl = currentUrl,
                        cookies = emptyMap(), extractedHtml = extractedHtml,
                        completedSteps = completedSteps,
                        failedAtStep = completedSteps, error = e.message
                    ))
                }
            }

            result.await()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(activity: android.app.Activity, userAgent: String): WebView {
        return WebView(activity).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = userAgent
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = true
                blockNetworkImage = false
                loadsImagesAutomatically = true
                @Suppress("DEPRECATION")
                allowFileAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }
        }
    }

    private fun setupWebViewClient(
        webView: WebView,
        requestInterceptor: ((WebView, WebResourceRequest) -> WebResourceResponse?)?,
        allowedDomains: Set<String> = emptySet(),
        destinationLockPatterns: List<Regex> = emptyList()
    ) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        var isOnDestination = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null) {
                    ProviderLogger.d(TAG, "onPageStarted", url.take(80))
                    // Update destination lock state
                    if (destinationLockPatterns.any { it.containsMatchIn(url) }) {
                        if (!isOnDestination) {
                            ProviderLogger.i(TAG, "onPageStarted", "Destination lock engaged for URL matching pattern", "url" to url.take(80))
                        }
                        isOnDestination = true
                    }
                }
                view?.evaluateJavascript(SPOOFING_JS, null)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                ProviderLogger.d(TAG, "onPageFinished", (url ?: "").take(80))
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null || view == null) return null
                val reqUrl = request.url?.toString() ?: return null
                val scheme = request.url?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null

                if (requestInterceptor != null) {
                    return requestInterceptor.invoke(view, request)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val nextUrl = request?.url?.toString() ?: return super.shouldOverrideUrlLoading(view, request)
                val scheme = request.url?.scheme?.lowercase()
                if (scheme != null && scheme != "http" && scheme != "https") return true

                // Destination lock: once on destination, block all main-frame nav away
                if (request?.isForMainFrame == true && isOnDestination) {
                    ProviderLogger.i(TAG, "shouldOverrideUrlLoading", "Destination lock BLOCK nav away from target", "url" to nextUrl.take(80))
                    return true
                }

                // Domain allowlist: if non-empty, only allow navigations to allowed domains
                if (allowedDomains.isNotEmpty()) {
                    val nextHost = try { java.net.URI(nextUrl).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                    val allowed = allowedDomains.any { allowedDomain ->
                        nextHost == allowedDomain || nextHost.endsWith(".$allowedDomain")
                    }
                    if (!allowed) {
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "BLOCKED navigation to non-allowed domain", "url" to nextUrl.take(80), "host" to nextHost)
                        return true
                    }
                }

                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val desc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        error?.description?.toString()
                    } else error?.toString()
                    ProviderLogger.w(TAG, "onReceivedError", desc ?: "unknown", "url" to (request.url?.toString()?.take(80) ?: ""))
                }
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let {
                    val level = when (it.messageLevel()) {
                        android.webkit.ConsoleMessage.MessageLevel.ERROR -> "E"
                        android.webkit.ConsoleMessage.MessageLevel.WARNING -> "W"
                        else -> "D"
                    }
                    android.util.Log.println(android.util.Log.INFO, "NavEngineJS", "[$level] ${it.message()} [${it.sourceId()}:${it.lineNumber()}]")
                }
                return true
            }
        }
    }

    private fun loadUrlInWebView(
        webView: WebView,
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>
    ) {
        if (referer != null || extraHeaders.isNotEmpty()) {
            val headers = mutableMapOf<String, String>()
            if (referer != null) headers["Referer"] = referer
            headers.putAll(extraHeaders)
            webView.loadUrl(url, headers)
        } else {
            webView.loadUrl(url)
        }
    }

    /**
     * Click an element found by CSS selector.
     * First tries native touch event at element coordinates.
     * If element has zero bounding rect (hidden/detached), falls back to JS click.
     */
    private suspend fun clickElementInWebView(
        webView: WebView,
        selector: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val coords = findElementCoordinates(webView, selector)
            if (coords != null) {
                dispatchNativeClick(webView, coords.first, coords.second)
                ProviderLogger.i(TAG, "clickElement", "Native click $selector at (${coords.first}, ${coords.second})")
                return true
            }
            // Try JS click fallback (handles display:none / zero-rect elements)
            val jsClicked = jsClickElement(webView, selector)
            if (jsClicked) {
                ProviderLogger.i(TAG, "clickElement", "JS click fallback $selector")
                return true
            }
            delay(500)
        }
        ProviderLogger.w(TAG, "clickElement", "Element not found: $selector within ${timeoutMs}ms")
        return false
    }

    /**
     * Fallback click using JavaScript's el.click().
     * Works on elements with zero bounding rect that can't receive native touch events.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun jsClickElement(webView: WebView, selector: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (el) { el.click(); return true; }
                        return false;
                    })();
                """.trimIndent()) { result ->
                    if (cont.isActive) cont.resume(result == "true") {}
                }
            }
        }
    }

    /**
     * Find an element's center coordinates in physical pixels via JS.
     * Returns null if the element is not found or not visible.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun findElementCoordinates(webView: WebView, selector: String): Pair<Float, Float>? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (!el) return null;
                        var rect = el.getBoundingClientRect();
                        if (rect.width <= 0 || rect.height <= 0) return null;
                        var d = window.devicePixelRatio || 1;
                        return JSON.stringify({
                            x: (rect.left + rect.width / 2) * d,
                            y: (rect.top + rect.height / 2) * d
                        });
                    })();
                """.trimIndent()) { result ->
                    try {
                        if (result != null && result != "null" && result != "\"\"") {
                            val parsed = org.json.JSONTokener(result).nextValue()
                            if (parsed is org.json.JSONObject) {
                                val x = parsed.getDouble("x").toFloat()
                                val y = parsed.getDouble("y").toFloat()
                                if (cont.isActive) cont.resume(Pair(x, y)) {}
                                return@evaluateJavascript
                            }
                        }
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG, "findElementCoordinates", "Parse error: ${e.message}")
                    }
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        }
    }

    /**
     * Dispatch a trusted native touch event (ACTION_DOWN + ACTION_UP) at physical pixel coordinates.
     * The browser treats this as a real user touch (isTrusted=true in JS).
     */
    private fun dispatchNativeClick(webView: WebView, x: Float, y: Float) {
        Handler(Looper.getMainLooper()).post {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            webView.dispatchTouchEvent(down)
            down.recycle()

            Handler(Looper.getMainLooper()).postDelayed({
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
                webView.dispatchTouchEvent(up)
                up.recycle()
            }, 50)
        }
    }

    private suspend fun waitForSelector(
        webView: WebView,
        selector: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = checkSelectorExists(webView, selector)
            if (found) return true
            delay(500)
        }
        return false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun checkSelectorExists(webView: WebView, selector: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        return document.querySelector('$safeSelector') !== null;
                    })();
                """.trimIndent()) { result ->
                    if (cont.isActive) cont.resume(result == "true") {}
                }
            }
        }
    }

    private suspend fun waitForUrl(
        webView: WebView,
        urlPattern: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val regex = Regex(urlPattern)
        while (System.currentTimeMillis() < deadline) {
            val currentUrl = getCurrentUrlFromWebView(webView) ?: ""
            if (regex.containsMatchIn(currentUrl)) return true
            delay(500)
        }
        return false
    }

    private suspend fun waitForDomCondition(
        webView: WebView,
        jsCondition: String,
        timeoutMs: Long,
        pollIntervalMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val met = evaluateDomCondition(webView, jsCondition)
            if (met) return true
            delay(pollIntervalMs)
        }
        return false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun evaluateDomCondition(webView: WebView, jsCondition: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript("""
                    (function() {
                        try { return !!($jsCondition); }
                        catch(e) { return false; }
                    })();
                """.trimIndent()) { result ->
                    if (cont.isActive) cont.resume(result == "true") {}
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun executeJsInWebView(webView: WebView, javascript: String): String? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(javascript) { result ->
                    val cleaned = try {
                        if (result == null || result == "null") null
                        else org.json.JSONTokener(result).nextValue().toString()
                    } catch (_: Exception) { result }
                    if (cont.isActive) cont.resume(cleaned) {}
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun extractHtmlFromWebView(webView: WebView, selector: String?): String? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val js = if (selector != null) {
                    val safeSelector = selector.replace("'", "\\'")
                    "(function(){ var el = document.querySelector('$safeSelector'); return el ? el.outerHTML : null; })();"
                } else {
                    "(function(){ return document.documentElement.outerHTML; })();"
                }
                webView.evaluateJavascript(js) { result ->
                    val html = try {
                        if (result == null || result == "null") null
                        else org.json.JSONTokener(result).nextValue().toString()
                    } catch (e: Exception) {
                        ProviderLogger.e(TAG, "extractHtml", "Parse error", e)
                        null
                    }
                    if (cont.isActive) cont.resume(html) {}
                }
            }
        }
    }

    private fun getCurrentUrlFromWebView(webView: WebView?): String? {
        return try { webView?.url } catch (_: Exception) { null }
    }

    private fun extractCookiesFromManager(url: String): Map<String, String> {
        if (url.isBlank()) return emptyMap()
        return try {
            val raw = CookieManager.getInstance().getCookie(url)
            if (!raw.isNullOrBlank()) parseCookieString(raw) else emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun parseCookieString(cookie: String): Map<String, String> {
        return cookie.split(";").associate {
            val parts = it.split("=", limit = 2)
            (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() }
    }

    private fun createDialog(activity: android.app.Activity, webView: WebView): android.app.Dialog {
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
            setOnDismissListener {
                ProviderLogger.d(TAG, "createDialog", "Dialog dismissed")
            }
        }
    }

    private fun cleanupWebView(webView: WebView?, dialog: android.app.Dialog?) {
        try {
            dialog?.dismiss()
            webView?.let { wv ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.clearHistory()
                        wv.removeAllViews()
                        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                        wv.destroy()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "NavigationEngine"

        private val SPOOFING_JS = """
            (function(){
                try { Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } }); } catch(e) {}
                try {
                    var od;
                    Object.defineProperty(window, 'DisableDevtool', {
                        get: function() {
                            return function(o) { o = o || {}; o.ignore = function() { return true; }; o.url = ""; o.timeOutUrl = ""; o.ondevtoolopen = function() {}; if (od) try { return od(o); } catch(e) {} };
                        },
                        set: function(v) { od = v; },
                        configurable: true
                    });
                } catch(e) {}
                try { Object.defineProperty(navigator, 'plugins', { get: function() { return [1,2,3,4,5]; } }); } catch(e) {}
                try { Object.defineProperty(navigator, 'languages', { get: function() { return ['ar-SA','en-US','en']; } }); } catch(e) {}
            })();
        """.trimIndent()
    }
}
