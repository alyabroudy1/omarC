package com.cimanow

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Standalone WebView test that mirrors the demo app's [WebViewNavigationServiceImpl]
 * exactly. No dependencies on [NavigationEngine], [WebViewFlowHelper], or CloudStream
 * abstractions. Purpose: isolate whether the navigation flow works inside the
 * CloudStream plugin environment.
 *
 * Triggered by searching "!test" in CimaNow provider — a dialog shows the visible
 * WebView, executes the demo step list, and logs everything to Logcat (tag "CimaNowTest").
 */
class CimaNowWebViewTest(private val activity: android.app.Activity) {

    private var webView: WebView? = null
    private val container: FrameLayout
    private var allowedDomains: List<String> = emptyList()
    private var destinationLockPatterns: List<String> = emptyList()
    private var isOnDestination: Boolean = false

    init {
        val pair = createInitialWebView()
        webView = pair.first
        container = pair.second
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createInitialWebView(): Pair<WebView, FrameLayout> {
        val wv = WebView(activity)
        val c = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(wv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            blockNetworkImage = false
            loadsImagesAutomatically = true
            allowFileAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        hideXRequestedWithHeader(wv)
        return Pair(wv, c)
    }

    private fun hideXRequestedWithHeader(wv: WebView) {
        fun tryClear(obj: Any?): Boolean {
            if (obj == null) return false
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField("mXRequestedWithHeader")
                    f.isAccessible = true
                    f.set(obj, "")
                    Log.i("CimaNowTest", "[init] Cleared mXRequestedWithHeader via ${cls.name}")
                    return true
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            return false
        }

        if (tryClear(wv)) return

        try {
            val pf = wv.javaClass.getDeclaredField("mProvider")
            pf.isAccessible = true
            if (tryClear(pf.get(wv))) return
        } catch (_: Exception) {}

        Log.w("CimaNowTest", "[init] Reflection failed — X-Requested-With may leak")
    }

    fun getContainer(): FrameLayout = container

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(
        steps: List<TestStep>,
        userAgent: String,
        overallTimeoutMs: Long = 120_000L,
        allowedDomains: List<String> = emptyList(),
        destinationLockPatterns: List<String> = emptyList()
    ): TestResult = withContext(Dispatchers.Main) {
        var currentUrl = ""
        val extractedHtml = mutableMapOf<String, String>()
        var completedSteps = 0
        var failedStep: Int? = null
        var errorMsg: String? = null

        try {
            this@CimaNowWebViewTest.allowedDomains = allowedDomains
            this@CimaNowWebViewTest.destinationLockPatterns = destinationLockPatterns
            isOnDestination = false
            ensureWebViewInitialized()
            webView?.settings?.userAgentString = userAgent
            setupWebViewClient()
        } catch (e: Exception) {
            Log.e("CimaNowTest", "[run] Setup failed: ${e.message}")
            return@withContext TestResult(
                success = false, finalUrl = "",
                extractedHtml = emptyMap(), completedSteps = 0,
                totalSteps = steps.size, failedAtStep = 0,
                error = "Setup failed: ${e.message}"
            )
        }

        for ((index, step) in steps.withIndex()) {
            logStep(index, "Starting: ${step.javaClass.simpleName}")
            try {
                when (step) {
                    is TestStep.LoadUrl -> {
                        currentUrl = step.url
                        loadUrl(step.url, step.referer, step.extraHeaders)
                    }
                    is TestStep.ClickElement -> {
                        val clicked = clickElement(step.selector, step.timeoutMs)
                        if (!clicked) {
                            logStep(index, "FAILED: ClickElement $step.selector")
                            if (step.abortOnFailure) {
                                failedStep = index
                                errorMsg = "ClickElement failed: ${step.selector}"
                                break
                            }
                        } else {
                            logStep(index, "OK: ClickElement $step.selector")
                        }
                    }
                    is TestStep.ClickCoordinates -> {
                        dispatchNativeClick(step.x, step.y)
                        delay(150)
                        logStep(index, "OK: ClickCoordinates (${step.x}, ${step.y})")
                    }
                    is TestStep.ExecuteJs -> {
                        val jsResult = executeJs(step.javascript)
                        delay(300)
                        val preview = jsResult?.take(200) ?: ""
                        logStep(index, "OK: ExecuteJs -> $preview")
                        if (jsResult != null && jsResult.startsWith("[")) {
                            extractedHtml["_js_result_$completedSteps"] = jsResult
                        }
                    }
                    is TestStep.WaitForSelector -> {
                        val found = waitForSelector(step.selector, step.timeoutMs)
                        if (!found) {
                            logStep(index, "FAILED: WaitForSelector $step.selector")
                            if (step.abortOnFailure) {
                                failedStep = index
                                errorMsg = "WaitForSelector timed out: ${step.selector}"
                                break
                            }
                        } else {
                            logStep(index, "OK: WaitForSelector $step.selector")
                        }
                    }
                    is TestStep.WaitForUrl -> {
                        val found = waitForUrl(step.urlPattern, step.timeoutMs)
                        if (!found) {
                            logStep(index, "FAILED: WaitForUrl ${step.urlPattern}")
                            if (step.abortOnFailure) {
                                failedStep = index
                                errorMsg = "WaitForUrl timed out: ${step.urlPattern}"
                                break
                            }
                        } else {
                            logStep(index, "OK: WaitForUrl ${step.urlPattern}")
                        }
                    }
                    is TestStep.WaitForDelay -> {
                        delay(step.delayMs)
                        logStep(index, "OK: WaitForDelay ${step.delayMs}ms")
                    }
                    is TestStep.WaitForDomCondition -> {
                        val met = waitForDomCondition(step.jsCondition, step.timeoutMs, step.pollIntervalMs)
                        if (!met) {
                            logStep(index, "FAILED: WaitForDomCondition")
                            if (step.abortOnFailure) {
                                failedStep = index
                                errorMsg = "WaitForDomCondition timed out"
                                break
                            }
                        } else {
                            logStep(index, "OK: WaitForDomCondition")
                        }
                    }
                    is TestStep.ExtractHtml -> {
                        val html = extractHtml(step.selector)
                        extractedHtml[step.key] = html ?: ""
                        logStep(index, "OK: ExtractHtml ${step.key} -> ${html?.length ?: 0} chars")
                    }
                }
                completedSteps = index + 1
                currentUrl = getCurrentUrl() ?: currentUrl
            } catch (e: Exception) {
                logStep(index, "ERROR: ${e.message}")
                failedStep = index
                errorMsg = e.message
                break
            }
        }

        TestResult(
            success = failedStep == null,
            finalUrl = currentUrl,
            extractedHtml = extractedHtml,
            completedSteps = completedSteps,
            totalSteps = steps.size,
            failedAtStep = failedStep,
            error = errorMsg
        )
    }

    private fun logStep(index: Int, message: String) {
        Log.i("CimaNowTest", "[step $index] $message")
    }

    private fun setupWebViewClient() {
        webView?.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                Log.i("CimaNowTest", "[onPageStarted] $url")
                super.onPageStarted(view, url, favicon)
                if (url != null && destinationLockPatterns.any { url.contains(it) }) {
                    isOnDestination = true
                    Log.i("CimaNowTest", "[onPageStarted] Destination reached: $url — navigation locked")
                }
                view?.evaluateJavascript(SPOOFING_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i("CimaNowTest", "[onPageFinished] $url")
                super.onPageFinished(view, url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (isOnDestination && !destinationLockPatterns.any { url.contains(it) }) {
                    Log.w("CimaNowTest", "[shouldOverride] BLOCKED nav away from destination: $url")
                    return true
                }
                if (allowedDomains.isNotEmpty()) {
                    val host = try { request?.url?.host?.lowercase()?.removePrefix("www.") } catch (_: Exception) { null }
                    if (host != null && allowedDomains.any {
                        host == it.lowercase().removePrefix("www.") || host.endsWith(".$it")
                    }) {
                        return false
                    }
                    Log.w("CimaNowTest", "[shouldOverride] BLOCKED nav to: $url (host=$host, allowed=$allowedDomains)")
                    return true
                }
                return false
            }
        }
        webView?.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg?.let {
                    val level = when (it.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "E"
                        ConsoleMessage.MessageLevel.WARNING -> "W"
                        else -> "D"
                    }
                    android.util.Log.println(android.util.Log.INFO, "CimaNowTestJS",
                        "[$level] ${it.message()} [${it.sourceId()}:${it.lineNumber()}]")
                }
                return true
            }
        }
    }

    private fun loadUrl(url: String, referer: String?, extraHeaders: Map<String, String>) {
        val headers = mutableMapOf<String, String>()
        headers["X-Requested-With"] = ""
        if (referer != null) headers["Referer"] = referer
        headers.putAll(extraHeaders)
        Log.i("CimaNowTest", "[loadUrl] url=$url headers=${headers.entries.joinToString(",") { "${it.key}=${it.value.take(20)}" }}")
        webView?.loadUrl(url, headers)
    }

    private suspend fun clickElement(selector: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val coords = findElementCoordinates(selector)
            if (coords != null) {
                dispatchNativeClick(coords.first, coords.second)
                Log.i("CimaNowTest", "[clickElement] dispatched touch at (${coords.first}, ${coords.second})")
                return true
            }
            val jsClicked = jsClickElement(selector)
            if (jsClicked) {
                Log.i("CimaNowTest", "[clickElement] JS click succeeded for $selector")
                return true
            }
            delay(500)
        }
        return false
    }

    private suspend fun jsClickElement(selector: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView?.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (!el) { return 'no_element'; }
                        console.log('[CimaNowTest] jsClick: trying el.click() on', el.tagName, el.className);
                        try { el.click(); return 'clicked'; }
                        catch(e) { return 'error:' + e.message; }
                    })();
                """.trimIndent()) { result ->
                    val clicked = result == "\"clicked\"" || result == "clicked"
                    if (!clicked) {
                        Log.w("CimaNowTest", "[jsClickElement] result: $result")
                    }
                    if (cont.isActive) cont.resume(clicked)
                }
            }
        }
    }

    private suspend fun findElementCoordinates(selector: String): Pair<Float, Float>? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView?.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (!el) { console.log('[CimaNowTest] findCoords: no element for', '$safeSelector'); return null; }
                        var rect = el.getBoundingClientRect();
                        var cs = window.getComputedStyle(el);
                        var info = {tag: el.tagName, id: el.id, className: el.className,
                            rect_w: rect.width, rect_h: rect.height,
                            display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
                            position: cs.position, offsetParent: !!el.offsetParent};
                        console.log('[CimaNowTest] findCoords:', JSON.stringify(info));
                        if (rect.width <= 0 || rect.height <= 0) {
                            console.log('[CimaNowTest] findCoords: zero rect');
                            return JSON.stringify({_debug: info});
                        }
                        var d = window.devicePixelRatio || 1;
                        return JSON.stringify({
                            x: (rect.left + rect.width / 2) * d,
                            y: (rect.top + rect.height / 2) * d,
                            _debug: info
                        });
                    })();
                """.trimIndent()) { result ->
                    try {
                        if (result != null && result != "null" && result != "\"\"") {
                            val parsed = JSONTokener(result).nextValue()
                            if (parsed is org.json.JSONObject) {
                                if (parsed.has("_debug")) {
                                    val dbg = parsed.getJSONObject("_debug")
                                    Log.w("CimaNowTest", "[findCoords] debug: $dbg")
                                }
                                if (parsed.has("x")) {
                                    val x = parsed.getDouble("x").toFloat()
                                    val y = parsed.getDouble("y").toFloat()
                                    if (cont.isActive) cont.resume(Pair(x, y))
                                    return@evaluateJavascript
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun dispatchNativeClick(x: Float, y: Float) {
        val wv = webView ?: return
        Handler(Looper.getMainLooper()).post {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            wv.dispatchTouchEvent(down)
            down.recycle()
            Handler(Looper.getMainLooper()).postDelayed({
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
                wv.dispatchTouchEvent(up)
                up.recycle()
            }, 50)
        }
    }

    private suspend fun waitForSelector(selector: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = checkSelectorExists(selector)
            if (found) {
                logElementDebug(selector)
                return true
            }
            delay(500)
        }
        return false
    }

    private fun logElementDebug(selector: String) {
        val safeSelector = selector.replace("'", "\\'")
        Handler(Looper.getMainLooper()).post {
            webView?.evaluateJavascript("""
                (function() {
                    var el = document.querySelector('$safeSelector');
                    if (!el) return;
                    var rect = el.getBoundingClientRect();
                    var cs = window.getComputedStyle(el);
                    console.log('[CimaNowTest] waitForSelector FOUND:', JSON.stringify({
                        tag: el.tagName, id: el.id, classes: el.className,
                        rect_w: rect.width, rect_h: rect.height,
                        display: cs.display, visibility: cs.visibility,
                        offsetParent: !!el.offsetParent
                    }));
                })();
            """.trimIndent(), null)
        }
    }

    private suspend fun checkSelectorExists(selector: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView?.evaluateJavascript("""
                    document.querySelector('$safeSelector') !== null
                """.trimIndent()) { result ->
                    if (cont.isActive) cont.resume(result == "true")
                }
            }
        }
    }

    private suspend fun waitForUrl(urlPattern: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val regex = Regex(urlPattern)
        while (System.currentTimeMillis() < deadline) {
            val currentUrl = getCurrentUrl() ?: ""
            if (regex.containsMatchIn(currentUrl)) return true
            delay(500)
        }
        return false
    }

    private suspend fun waitForDomCondition(jsCondition: String, timeoutMs: Long, pollIntervalMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val met = evaluateDomCondition(jsCondition)
            if (met) return true
            delay(pollIntervalMs)
        }
        return false
    }

    private suspend fun evaluateDomCondition(jsCondition: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                webView?.evaluateJavascript("""
                    (function() { try { return !!($jsCondition); } catch(e) { return false; } })();
                """.trimIndent()) { result ->
                    if (cont.isActive) cont.resume(result == "true")
                }
            }
        }
    }

    private suspend fun executeJs(javascript: String): String? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                webView?.evaluateJavascript(javascript) { result ->
                    val cleaned = try {
                        if (result == null || result == "null") null
                        else JSONTokener(result).nextValue().toString()
                    } catch (_: Exception) { result }
                    if (cont.isActive) cont.resume(cleaned)
                }
            }
        }
    }

    private suspend fun extractHtml(selector: String?): String? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val js = if (selector != null) {
                    val safeSelector = selector.replace("'", "\\'")
                    "(function(){ var el = document.querySelector('$safeSelector'); return el ? el.outerHTML : null; })();"
                } else {
                    "(function(){ return document.documentElement.outerHTML; })();"
                }
                webView?.evaluateJavascript(js) { result ->
                    val html = try {
                        if (result == null || result == "null") null
                        else JSONTokener(result).nextValue().toString()
                    } catch (_: Exception) { null }
                    if (cont.isActive) cont.resume(html)
                }
            }
        }
    }

    private fun getCurrentUrl(): String? {
        return try { webView?.url } catch (_: Exception) { null }
    }

    private fun ensureWebViewInitialized() {
        if (webView != null) return
        val wv = WebView(activity)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            blockNetworkImage = false
            loadsImagesAutomatically = true
            allowFileAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        hideXRequestedWithHeader(wv)
        container.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        webView = wv
    }

    fun cleanup() {
        webView?.let { wv ->
            Handler(Looper.getMainLooper()).post {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
        webView = null
    }

    fun showInDialog() {
        Handler(Looper.getMainLooper()).post {
            val dialog = android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
                setContentView(container)
                setCancelable(true)
                window?.let { w ->
                    @Suppress("DEPRECATION")
                    w.decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                setOnDismissListener {
                    cleanup()
                    Log.i("CimaNowTest", "[dialog] Dismissed — cleaned up")
                }
            }
            dialog.show()
        }
    }

    companion object {
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

// ==================== Data model (mirrors demo's NavigationStep/NavigationResult) ====================

// ==================== Step definitions (mirrors demo's runCimaNowFlow exactly) ====================

private val DISMISS_CONSENT_JS = """
(function() {
    var found = false;
    var candidates = document.querySelectorAll('button, a, .btn, [role="button"], .modal-footer a, .modal-footer button, .popup-content a, .popup-content button');
    var keywords = ['continue', 'accept', 'allow', 'agree', 'confirm', 'close', 'dismiss', 'ok', 'got it', '\u0623\u0648\u0627\u0641\u0642', '\u0645\u062a\u0627\u0628\u0639\u0629', '\u0645\u0648\u0627\u0641\u0642'];
    for (var i = 0; i < candidates.length; i++) {
        var el = candidates[i];
        var text = (el.innerText || el.textContent || '').trim().toLowerCase();
        if (text.length === 0 || text.length > 30) continue;
        for (var k = 0; k < keywords.length; k++) {
            if (text.indexOf(keywords[k]) !== -1) {
                var rect = el.getBoundingClientRect();
                var visible = rect.width > 0 && rect.height > 0 && el.offsetParent !== null;
                console.log('[CimaNowTest] consent candidate:', el.tagName, 'text="' + text + '" visible=' + visible);
                if (visible) {
                    try { el.click(); console.log('[CimaNowTest] clicked consent:', text); found = true; } catch(e) { console.log('[CimaNowTest] click error:', e.message); }
                }
                break;
            }
        }
    }
    if (!found) {
        var closeSelectors = ['.close', '.close-btn', '.modal-close', '.popup-close', '[onclick*="close"]', '.fc-cta-consent', '.fc-button', '.cc-btn', '.agree-btn', '#continue', '.continue', '.accept-btn'];
        for (var i = 0; i < closeSelectors.length; i++) {
            var els = document.querySelectorAll(closeSelectors[i]);
            for (var j = 0; j < els.length; j++) {
                var rect = els[j].getBoundingClientRect();
                if (rect.width > 0 && rect.height > 0 && els[j].offsetParent !== null) {
                    try { els[j].click(); console.log('[CimaNowTest] clicked close btn:', closeSelectors[i]); found = true; } catch(e) {}
                    break;
                }
            }
            if (found) break;
        }
    }
    return found ? 'consent_dismissed' : 'no_consent';
})();
"""

private val FIND_SERVER_LINK_JS = """
(function() {
    var links = document.querySelectorAll('a[href*="get-link"], a[href*="download"], a[href*="watch"], a.continue-btn, a[href*="server"], a[href*="link"]');
    for (var i = 0; i < links.length; i++) {
        var rect = links[i].getBoundingClientRect();
        var visible = rect.width > 0 && rect.height > 0 && links[i].offsetParent !== null;
        console.log('[CimaNowTest] server link candidate:', links[i].href, 'visible=' + visible);
        if (visible) {
            var href = links[i].href || '';
            if (href.indexOf('viiqkzqv') !== -1 || href.indexOf('wildsino') !== -1) {
                console.log('[CimaNowTest] skipping ad link:', href);
                continue;
            }
            console.log('[CimaNowTest] clicking server link:', href);
            try { links[i].click(); return 'clicked:' + href; } catch(e) { return 'error:' + e.message; }
        }
    }
    var allLinks = document.querySelectorAll('a[href]:not([href=""]):not([href^="javascript"])');
    for (var i = 0; i < allLinks.length; i++) {
        var rect = allLinks[i].getBoundingClientRect();
        var href = allLinks[i].href || '';
        if (rect.width > 0 && rect.height > 0 && allLinks[i].offsetParent !== null && href.indexOf('viiqkzqv') === -1 && href.indexOf('wildsino') === -1) {
            console.log('[CimaNowTest] fallback clicking:', href);
            try { allLinks[i].click(); return 'clicked_fallback:' + href; } catch(e) {}
        }
    }
    return 'no_server_link';
})();
"""

/**
 * Runs the complete CimaNow test flow in a standalone visible WebView dialog.
 * This mirrors the demo app's [runCimaNowFlow] exactly — same steps, same JS, same settings.
 *
 * Call from within CloudStream's coroutine context (e.g. from loadLinks in a plugin).
 */
suspend fun runCimaNowTest(
    activity: android.app.Activity,
    movieUrl: String = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-%d9%81%d8%a7%d9%85%d9%8a%d9%84%d9%8a-%d8%a8%d9%8a%d8%b2%d9%86%d8%b3-2026/"
): TestResult {
    val test = CimaNowWebViewTest(activity)

    // Show dialog so the WebView is visible (mirrors demo's AndroidView in Compose)
    test.showInDialog()

    try {
        val result = test.run(
            steps = listOf(
                TestStep.LoadUrl(movieUrl),
                TestStep.WaitForSelector("a.shine[href*='freex2line'], a[href*='freex2line']", timeoutMs = 45_000L),
                TestStep.ClickElement("a.shine[href*='freex2line'], a[href*='freex2line']", timeoutMs = 5_000L),
                TestStep.WaitForUrl("blog-post\\.html", timeoutMs = 60_000L),
                TestStep.WaitForDelay(8_000L),
                TestStep.ExecuteJs(DISMISS_CONSENT_JS),
                TestStep.WaitForDelay(2_000L),
                TestStep.WaitForDomCondition(
                    jsCondition = """
                        (function(){
                            try {
                                var links = document.querySelectorAll('a[href*="get-link"], a[href*="download"], a[href*="watch"], a.continue-btn, a[href*="server"], a[href*="link"]');
                                for (var i = 0; i < links.length; i++) {
                                    var r = links[i].getBoundingClientRect();
                                    var href = links[i].href || '';
                                    if (r.width > 0 && r.height > 0 && links[i].offsetParent !== null
                                        && href.indexOf('viiqkzqv') === -1 && href.indexOf('wildsino') === -1) {
                                        console.log('[CimaNowTest] found visible server link:', href);
                                        return true;
                                    }
                                }
                                return false;
                            } catch(e) { return false; }
                        })()
                    """.trimIndent(),
                    timeoutMs = 15_000L,
                    pollIntervalMs = 1000L
                ),
                TestStep.ExecuteJs(FIND_SERVER_LINK_JS),
                TestStep.WaitForUrl("/watching/", timeoutMs = 15_000L),
                TestStep.WaitForDelay(8_000L),
                TestStep.ExecuteJs("""
                    (function(){
                        var uls = document.querySelectorAll('ul');
                        console.log('[CimaNowTest] UL count:', uls.length);
                        for (var i = 0; i < uls.length; i++) {
                            console.log('[CimaNowTest] UL #'+i+': id="'+uls[i].id+'" class="'+uls[i].className+'" children='+uls[i].children.length);
                        }
                        var iframes = document.querySelectorAll('iframe');
                        console.log('[CimaNowTest] IFRAME count:', iframes.length);
                        for (var i = 0; i < iframes.length; i++) {
                            console.log('[CimaNowTest] IFRAME #'+i+': src="'+(iframes[i].src||'none')+'"');
                        }
                        var watchLis = document.querySelectorAll('[data-index], li[class*="server"], li[class*="watch"]');
                        console.log('[CimaNowTest] Server LI count:', watchLis.length);
                        for (var i = 0; i < watchLis.length; i++) {
                            var a = watchLis[i].querySelector('a');
                            console.log('[CimaNowTest] Server #'+i+': text="'+(watchLis[i].textContent||'').trim().slice(0,50)+'" href="'+(a?a.href:'none')+'"');
                        }
                        return 'debug_done';
                    })();
                """.trimIndent()),
                TestStep.ExecuteJs("""
                    (function(){
                        var items = document.querySelectorAll('#watch li, li[data-index], [data-index]');
                        var servers = [];
                        for (var i = 0; i < items.length; i++) {
                            var idx = items[i].getAttribute('data-index') || '';
                            var id = items[i].getAttribute('data-id') || '';
                            var name = (items[i].textContent || '').trim().slice(0, 60);
                            servers.push({index: idx, id: id, name: name});
                            console.log('[CimaNowTest] Server #'+i+': idx='+idx+' id='+id+' name="'+name+'"');
                        }
                        return JSON.stringify(servers);
                    })();
                """.trimIndent()),
                TestStep.ExecuteJs("""
                    (function(){
                        var items = document.querySelectorAll('#watch li, li[data-index], [data-index]');
                        var baseUrl = window.location.origin;
                        var results = [];
                        var done = 0;
                        for (var i = 0; i < Math.min(items.length, 10); i++) {
                            var idx = items[i].getAttribute('data-index') || '';
                            var id = items[i].getAttribute('data-id') || '';
                            var name = (items[i].textContent || '').trim().slice(0, 60);
                            var ajaxUrl = baseUrl + '/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=' + idx + '&id=' + id;
                            console.log('[CimaNowTest] Fetching server ' + name + ': ' + ajaxUrl);
                            (function(srvName, srvIdx, url) {
                                fetch(url, {credentials: 'include', headers: {'X-Requested-With': 'XMLHttpRequest', 'Referer': window.location.href}})
                                    .then(function(r) { return r.text(); })
                                    .then(function(html) {
                                        var iframeSrc = '';
                                        var match = html.match(/<iframe[^>]+src=["']([^"']+)["']/);
                                        if (match) iframeSrc = match[1];
                                        console.log('[CimaNowTest] Server ' + srvName + ' iframe: ' + iframeSrc);
                                        results.push({name: srvName, index: srvIdx, iframe: iframeSrc, responseLength: html.length});
                                        done++;
                                        if (done === Math.min(document.querySelectorAll('#watch li, li[data-index], [data-index]').length, 10)) {
                                            window._serverResults = JSON.stringify(results);
                                        }
                                    })
                                    .catch(function(err) {
                                        console.log('[CimaNowTest] Server ' + srvName + ' error: ' + err.message);
                                        results.push({name: srvName, index: srvIdx, iframe: '', error: err.message});
                                        done++;
                                    });
                            })(name, idx, ajaxUrl);
                        }
                        return 'fetching_' + Math.min(items.length, 10) + '_servers';
                    })();
                """.trimIndent()),
                TestStep.WaitForDelay(5_000L),
                TestStep.ExecuteJs("""
                    (function(){
                        return window._serverResults || '[]';
                    })();
                """.trimIndent()),
                TestStep.ExecuteJs("""
                    (function(){
                        var links = document.querySelectorAll('#download li a[href], a[href*="download"], a[href*="dl"], .download-links a[href]');
                        var downloads = [];
                        for (var i = 0; i < links.length; i++) {
                            var name = (links[i].textContent || '').trim().slice(0, 60);
                            var href = links[i].href || '';
                            if (href && name) {
                                downloads.push({name: name, url: href});
                                console.log('[CimaNowTest] Download #'+i+': name="'+name+'" url="'+href+'"');
                            }
                        }
                        return JSON.stringify(downloads);
                    })();
                """.trimIndent()),
                TestStep.ExtractHtml(key = "watch_page_raw")
            ),
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/UQ1A.240205.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36",
            overallTimeoutMs = 120_000L,
            allowedDomains = listOf(
                "cimanow.cc",
                "freex2line.online",
                "rm.freex2line.online",
                "href.li"
            ),
            destinationLockPatterns = listOf("/watching/")
        )

        Log.i("CimaNowTest", "================ TEST COMPLETE ================")
        Log.i("CimaNowTest", "Success: ${result.success}, completed=${result.completedSteps}/${result.totalSteps}, failedAt=${result.failedAtStep}")
        Log.i("CimaNowTest", "Final URL: ${result.finalUrl}")
        Log.i("CimaNowTest", "Error: ${result.error ?: "none"}")
        result.extractedHtml.forEach { (key, value) ->
            Log.i("CimaNowTest", "  [extracted] $key -> ${value.take(200)}${if (value.length > 200) " ... [${value.length} chars]" else ""}")
        }

        return result
    } finally {
        // Keep dialog visible for inspection; user dismisses to cleanup
    }
}

// ==================== Data model (mirrors demo's NavigationStep/NavigationResult) ====================

sealed class TestStep {
    data class LoadUrl(
        val url: String,
        val referer: String? = null,
        val extraHeaders: Map<String, String> = emptyMap()
    ) : TestStep()

    data class ClickElement(
        val selector: String,
        val timeoutMs: Long = 10_000L,
        val abortOnFailure: Boolean = true
    ) : TestStep()

    data class ClickCoordinates(
        val x: Float,
        val y: Float
    ) : TestStep()

    data class ExecuteJs(
        val javascript: String,
        val key: String = ""
    ) : TestStep()

    data class WaitForSelector(
        val selector: String,
        val timeoutMs: Long = 45_000L,
        val abortOnFailure: Boolean = true
    ) : TestStep()

    data class WaitForUrl(
        val urlPattern: String,
        val timeoutMs: Long = 30_000L,
        val abortOnFailure: Boolean = true
    ) : TestStep()

    data class WaitForDelay(
        val delayMs: Long
    ) : TestStep()

    data class WaitForDomCondition(
        val jsCondition: String,
        val timeoutMs: Long = 15_000L,
        val pollIntervalMs: Long = 1000L,
        val abortOnFailure: Boolean = true
    ) : TestStep()

    data class ExtractHtml(
        val selector: String? = null,
        val key: String = ""
    ) : TestStep()
}

data class TestResult(
    val success: Boolean,
    val finalUrl: String,
    val extractedHtml: Map<String, String>,
    val completedSteps: Int,
    val totalSteps: Int,
    val failedAtStep: Int?,
    val error: String?
)
