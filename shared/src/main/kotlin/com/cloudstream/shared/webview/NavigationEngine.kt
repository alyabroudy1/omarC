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
import java.nio.charset.Charset

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
        allowedDomains: Set<String> = emptySet(),
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
                setupWebViewClient(webView, userAgent, requestInterceptor, allowedDomains, destinationLockPatterns)

                if (mode == Mode.FULLSCREEN) {
                    dialog = createDialog(activity, webView)
                    dialog.show()
                }

                for ((index, step) in steps.withIndex()) {
                    if (delivered) break

                    ProviderLogger.i(TAG, "execute", "Step $index: ${step.javaClass.simpleName}")
                    val stepStartMs = SystemClock.uptimeMillis()
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
                        val stepMs = SystemClock.uptimeMillis() - stepStartMs
                        currentUrl = getCurrentUrlFromWebView(webView) ?: currentUrl
                        ProviderLogger.d(TAG, "execute", "Step $index done in ${stepMs}ms, currentUrl=${currentUrl.take(80)}")
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
            // THE NUCLEAR SOLUTION: Hide the package name from ALL WebView requests natively.
            hideXRequestedWithHeader(this)
        }
    }

    private fun hideXRequestedWithHeader(webView: WebView) {
        try {
            val providerField = WebView::class.java.getDeclaredField("mProvider")
            providerField.isAccessible = true
            val provider = providerField.get(webView)

            // Try calling the hidden method setXRequestedWithHeader
            try {
                val method = provider.javaClass.getMethod("setXRequestedWithHeader", String::class.java)
                method.invoke(provider, "")
                ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via setXRequestedWithHeader method")
                return
            } catch (_: NoSuchMethodException) {}

            // Fallback: try setting the field directly on the provider
            var cls: Class<*>? = provider.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField("mXRequestedWithHeader")
                    f.isAccessible = true
                    f.set(provider, "")
                    ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via field on provider")
                    return
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "hideXRequestedWithHeader", "Provider reflection failed: ${e.message}")
        }
        ProviderLogger.w(TAG, "hideXRequestedWithHeader", "All reflection approaches failed — X-Requested-With may leak")
    }

    private fun setupWebViewClient(
        webView: WebView,
        userAgent: String,
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
                    ProviderLogger.i(TAG, "onPageStarted", "URL=${url}")
                    if (destinationLockPatterns.any { it.containsMatchIn(url) }) {
                        if (!isOnDestination) {
                            ProviderLogger.i(TAG, "onPageStarted", "Destination lock engaged for URL matching pattern", "url" to url)
                        }
                        isOnDestination = true
                    }
                }
                view?.evaluateJavascript("(function(){ return document.title; })();", null)
                view?.evaluateJavascript(SPOOFING_JS, null)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                ProviderLogger.i(TAG, "onPageFinished", "URL=${url}")
                view?.evaluateJavascript("(function(){ return document.title; })();") { result ->
                    val title = try { org.json.JSONTokener(result).nextValue().toString() } catch (_: Exception) { result }
                    ProviderLogger.i(TAG, "onPageFinished", "title=$title")
                }
                view?.evaluateJavascript("(function(){ return document.body.innerHTML.length; })();") { result ->
                    ProviderLogger.i(TAG, "onPageFinished", "bodyLength=$result")
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                val reqUrl = request.url?.toString() ?: return null
                val scheme = request.url?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null

                // CRITICAL: Never intercept Main Frame. Let Android WebView natively load
                // the main HTML so it can execute Cloudflare JS challenges.
                if (request.isForMainFrame) return null

                val host = request.url?.host?.lowercase() ?: ""
                val path = request.url?.path?.lowercase() ?: ""

                // As a fallback for sub-resources (JS/CSS) if the reflection failed to hide the package name,
                // we strip it here to prevent MIME type errors on cimanow.cc assets.
                val isProtectedDomain = host.contains("cimanow.cc") || host.contains("freex2line.online")
                val isAsset = path.endsWith(".js") || path.endsWith(".css")

                if (isProtectedDomain && isAsset) {
                    try {
                        val conn = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
                        conn.instanceFollowRedirects = true

                        request.requestHeaders?.forEach { (key, value) ->
                            if (!key.equals("X-Requested-With", true)) {
                                conn.setRequestProperty(key, value)
                            }
                        }
                        conn.setRequestProperty("X-Requested-With", "")

                        val cookies = CookieManager.getInstance().getCookie(reqUrl)
                        if (!cookies.isNullOrBlank()) {
                            conn.setRequestProperty("Cookie", cookies)
                        }
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000

                        if (conn.responseCode == 200) {
                            val ct = conn.contentType ?: "application/octet-stream"
                            val mime = ct.substringBefore(";").trim()
                            val encodingStr = ct.substringAfter("charset=", "utf-8").trim()
                            val charset = try { Charset.forName(encodingStr) } catch (e: Exception) { Charsets.UTF_8 }
                            ProviderLogger.d(TAG, "shouldInterceptRequest", "INTERCEPT ASSET ${reqUrl.take(100)}")
                            return WebResourceResponse(mime, charset.name(), conn.inputStream)
                        } else {
                            ProviderLogger.w(TAG, "shouldInterceptRequest", "Asset intercept non-200 (${conn.responseCode}) for ${reqUrl.take(80)}")
                            return null
                        }
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG, "shouldInterceptRequest", "Asset intercept failed: ${e.message}")
                    }
                }

                if (requestInterceptor != null && view != null) {
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

                val isMainFrame = request.isForMainFrame
                val nextHost = try { java.net.URI(nextUrl).host?.lowercase() ?: "" } catch (_: Exception) { "" }

                // NUCLEAR BYPASS FOR freex2line.online
                // We intercept the redirect to freex2line, decode the base64 link, and jump straight to cimanow/watching/
                // We fake the Referer header so CimaNow's anti-leech protection thinks we came from freex2line.
                if (isMainFrame && nextUrl.contains("freex2line.online/loadon")) {
                    val linkParam = request.url?.getQueryParameter("link")
                    if (linkParam != null) {
                        try {
                            val decodedBytes = android.util.Base64.decode(linkParam, android.util.Base64.DEFAULT)
                            val decodedUrl = String(decodedBytes, Charsets.UTF_8)

                            ProviderLogger.i(TAG, "shouldOverrideUrlLoading", "Bypassing freex2line -> decoded URL: ${decodedUrl.take(80)}")

                            val headers = mutableMapOf<String, String>()
                            headers["X-Requested-With"] = ""
                            // THE MAGIC TRICK: Fake the referer so CimaNow accepts us
                            headers["Referer"] = nextUrl

                            view?.loadUrl(decodedUrl, headers)
                            return true
                        } catch (e: Exception) {
                            ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "Base64 decode failed, falling back", "error" to e.message)
                        }
                    }
                }

                if (isMainFrame && isOnDestination) {
                    ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "DESTINATION LOCK BLOCK", "url" to nextUrl, "host" to nextHost)
                    return true
                }

                if (allowedDomains.isNotEmpty()) {
                    val allowed = allowedDomains.any { allowedDomain ->
                        nextHost == allowedDomain || nextHost.endsWith(".$allowedDomain")
                    }
                    if (!allowed) {
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "DOMAIN BLOCK", "url" to nextUrl, "host" to nextHost, "allowed" to allowedDomains.joinToString(","))
                        return true
                    }
                }

                ProviderLogger.i(TAG, "shouldOverrideUrlLoading", "ALLOWED", "url" to nextUrl.take(120), "host" to nextHost, "mainFrame" to isMainFrame.toString())
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
                    ProviderLogger.w(TAG, "onReceivedError", desc ?: "unknown", "url" to (request.url?.toString()?.take(120) ?: ""))
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
        val headers = mutableMapOf<String, String>()
        headers["X-Requested-With"] = ""
        if (referer != null) headers["Referer"] = referer
        headers.putAll(extraHeaders)
        ProviderLogger.i(TAG, "loadUrl", "url=$url headers=${headers.entries.joinToString(",") { "${it.key}=${it.value.take(20)}" }}")
        webView.loadUrl(url, headers)
    }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun jsClickElement(webView: WebView, selector: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (!el) return JSON.stringify({clicked: false, reason: 'not found'});
                        try {
                            el.click();
                            return JSON.stringify({clicked: true, tag: el.tagName, id: el.id || '', classes: (el.className || '').substring(0, 100)});
                        } catch(e) {
                            return JSON.stringify({clicked: false, reason: e.message});
                        }
                    })();
                """.trimIndent()) { result ->
                    val clicked = try {
                        if (result != null && result != "null") {
                            val parsed = org.json.JSONTokener(result).nextValue()
                            if (parsed is org.json.JSONObject) {
                                ProviderLogger.d(TAG, "jsClickElement", "selector=$safeSelector result=$parsed")
                                parsed.optBoolean("clicked")
                            } else result == "true"
                        } else false
                    } catch (_: Exception) {
                        ProviderLogger.w(TAG, "jsClickElement", "raw result=$result")
                        result == "true"
                    }
                    ProviderLogger.i(TAG, "jsClickElement", "selector=$safeSelector clicked=$clicked")
                    if (cont.isActive) cont.resume(clicked) {}
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun findElementCoordinates(webView: WebView, selector: String): Pair<Float, Float>? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (!el) return JSON.stringify({found: false});
                        var rect = el.getBoundingClientRect();
                        var cs = window.getComputedStyle(el);
                        return JSON.stringify({
                            found: true,
                            tag: el.tagName,
                            id: el.id || '',
                            classes: el.className || '',
                            rect: {left: rect.left, top: rect.top, width: rect.width, height: rect.height},
                            display: cs.display,
                            visibility: cs.visibility,
                            offsetParent: !!el.offsetParent,
                            rects: el.getClientRects().length,
                            dpr: window.devicePixelRatio || 1
                        });
                    })();
                """.trimIndent()) { result ->
                    try {
                        if (result != null && result != "null" && result != "\"\"") {
                            val parsed = org.json.JSONTokener(result).nextValue()
                            if (parsed is org.json.JSONObject) {
                                if (!parsed.optBoolean("found")) {
                                    ProviderLogger.w(TAG, "findElementCoordinates", "Element not found for $safeSelector")
                                    if (cont.isActive) cont.resume(null) {}
                                    return@evaluateJavascript
                                }
                                ProviderLogger.d(TAG, "findElementCoordinates", "selector=$safeSelector tag=${parsed.optString("tag")} classes=${parsed.optString("classes")} rect=${parsed.optJSONObject("rect")} display=${parsed.optString("display")} visibility=${parsed.optString("visibility")} offsetParent=${parsed.optBoolean("offsetParent")}")
                                val rect = parsed.optJSONObject("rect")
                                val w = rect?.optDouble("width") ?: 0.0
                                val h = rect?.optDouble("height") ?: 0.0
                                if (w > 0 && h > 0) {
                                    val dpr = parsed.optDouble("dpr", 1.0)
                                    val x = (rect.optDouble("left") + w / 2) * dpr
                                    val y = (rect.optDouble("top") + h / 2) * dpr
                                    ProviderLogger.i(TAG, "findElementCoordinates", "Valid rect for $safeSelector -> coords=($x, $y) dpr=$dpr")
                                    if (cont.isActive) cont.resume(Pair(x.toFloat(), y.toFloat())) {}
                                    return@evaluateJavascript
                                }
                                ProviderLogger.w(TAG, "findElementCoordinates", "Zero rect for $safeSelector w=$w h=$h")
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
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            val found = checkSelectorExists(webView, selector)
            ProviderLogger.d(TAG, "waitForSelector", "poll#$pollCount selector=$selector found=$found remaining=${deadline - System.currentTimeMillis()}ms")
            if (found) {
                ProviderLogger.i(TAG, "waitForSelector", "FOUND selector=$selector after ${pollCount} polls")
                return true
            }
            delay(500)
        }
        ProviderLogger.w(TAG, "waitForSelector", "TIMEOUT selector=$selector after ${pollCount} polls")
        return false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun checkSelectorExists(webView: WebView, selector: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        return JSON.stringify({
                            exists: el !== null,
                            tag: el ? el.tagName : null,
                            id: el ? (el.id || '') : null,
                            classes: el ? (el.className || '') : null,
                            display: el ? window.getComputedStyle(el).display : null,
                            visible: el ? (el.offsetWidth > 0 || el.offsetHeight > 0 || el.getClientRects().length > 0) : false
                        });
                    })();
                """.trimIndent()) { result ->
                    val exists = try {
                        if (result != null && result != "null") {
                            val parsed = org.json.JSONTokener(result).nextValue()
                            if (parsed is org.json.JSONObject) {
                                ProviderLogger.d(TAG, "checkSelectorExists", "selector=$safeSelector exists=${parsed.optBoolean("exists")} tag=${parsed.optString("tag")} id=${parsed.optString("id")} classes=${parsed.optString("classes")} display=${parsed.optString("display")} visible=${parsed.optBoolean("visible")}")
                                parsed.optBoolean("exists")
                            } else result == "true"
                        } else false
                    } catch (_: Exception) { result == "true" }
                    if (cont.isActive) cont.resume(exists) {}
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
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            val currentUrl = getCurrentUrlFromWebView(webView) ?: ""
            ProviderLogger.d(TAG, "waitForUrl", "poll#$pollCount pattern=$urlPattern currentUrl=${currentUrl.take(120)} match=${regex.containsMatchIn(currentUrl)} remaining=${deadline - System.currentTimeMillis()}ms")
            if (regex.containsMatchIn(currentUrl)) {
                ProviderLogger.i(TAG, "waitForUrl", "MATCHED pattern=$urlPattern after ${pollCount} polls, url=${currentUrl.take(120)}")
                return true
            }
            delay(500)
        }
        ProviderLogger.w(TAG, "waitForUrl", "TIMEOUT pattern=$urlPattern after ${pollCount} polls")
        return false
    }

    private suspend fun waitForDomCondition(
        webView: WebView,
        jsCondition: String,
        timeoutMs: Long,
        pollIntervalMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            val met = evaluateDomCondition(webView, jsCondition)
            ProviderLogger.d(TAG, "waitForDomCondition", "poll#$pollCount condition=${jsCondition.take(60)} met=$met remaining=${deadline - System.currentTimeMillis()}ms")
            if (met) {
                ProviderLogger.i(TAG, "waitForDomCondition", "MET after ${pollCount} polls")
                return true
            }
            delay(pollIntervalMs)
        }
        ProviderLogger.w(TAG, "waitForDomCondition", "TIMEOUT after ${pollCount} polls")
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