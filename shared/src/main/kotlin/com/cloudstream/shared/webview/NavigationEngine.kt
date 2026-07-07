package com.cloudstream.shared.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.cloudstream.shared.logging.ProviderLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Generic, safe, browser-fidelity navigation engine.
 *
 * Important:
 * - No identity-header spoofing/suppression
 * - No anti-bot bypass logic
 * - No main-frame manual request replay
 * - Designed for authorized browser automation/debugging
 */
class NavigationEngine(
    private val activityProvider: () -> Activity?
) {
    private val sessionMutex = Mutex()

    /**
     * Kept only for compatibility with existing NavigationStep.NavigateToWatchingUrl.
     * This engine does NOT auto-populate it from protected endpoints.
     */
    @Volatile
    var interceptedWatchingUrl: String? = null

    private val webViewCounter = AtomicInteger(1)
    private val popupCounter = AtomicInteger(1)

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun execute(
        steps: List<NavigationStep>,
        userAgent: String,
        mode: Mode = Mode.HEADLESS,
        overallTimeoutMs: Long = 120_000L,
        requestInterceptor: ((view: WebView, request: WebResourceRequest) -> WebResourceResponse?)? = null,
        allowedDomains: Set<String> = emptySet(),
        destinationLockPatterns: List<Regex> = emptyList()
    ): NavigationResult = withContext(Dispatchers.Main.immediate) {
        sessionMutex.withLock {
            interceptedWatchingUrl = null

            val activity = activityProvider()
            if (activity == null || activity.isFinishing) {
                logE("execute", "No valid Activity available")
                return@withContext NavigationResult(
                    success = false,
                    finalUrl = "",
                    cookies = emptyMap(),
                    extractedHtml = emptyMap(),
                    completedSteps = 0,
                    failedAtStep = 0,
                    error = "No Activity context"
                )
            }

            var rootWebView: WebView? = null
            var dialog: Dialog? = null
            var container: FrameLayout? = null

            val popupWebViews = Collections.synchronizedList(mutableListOf<WebView>())
            val extractedHtml = mutableMapOf<String, String>()
            var completedSteps = 0
            var failedStep: Int? = null
            var errorMsg: String? = null
            var currentUrl = ""
            val sessionStart = SystemClock.uptimeMillis()

            logI(
                "execute",
                buildString {
                    append("Starting session. ")
                    append("steps=${steps.size}, ")
                    append("mode=$mode, ")
                    append("timeoutMs=$overallTimeoutMs, ")
                    append("allowedDomains=${allowedDomains.joinToString(",")}, ")
                    append("destinationLocks=${destinationLockPatterns.joinToString(" | ") { it.pattern }}")
                }
            )

            try {
                rootWebView = createWebView(activity, userAgent, isPopup = false)
                setupWebView(
                    webView = rootWebView,
                    userAgent = userAgent,
                    requestInterceptor = requestInterceptor,
                    allowedDomains = allowedDomains,
                    destinationLockPatterns = destinationLockPatterns,
                    popupWebViews = popupWebViews,
                    containerProvider = { container },
                    isPopup = false
                )

                if (mode == Mode.FULLSCREEN) {
                    val ui = createDialog(activity, rootWebView)
                    dialog = ui.first
                    container = ui.second
                    dialog.show()
                    logI("execute", "Fullscreen dialog shown")
                } else {
                    logI("execute", "Running in HEADLESS mode")
                }

                withTimeout(overallTimeoutMs) {
                    for ((index, step) in steps.withIndex()) {
                        val stepStart = SystemClock.uptimeMillis()
                        val stepName = step.javaClass.simpleName
                        logI("step[$index]", "BEGIN $stepName currentUrl=${safeTrim(currentUrl, 160)}")

                        try {
                            when (step) {
                                is NavigationStep.LoadUrl -> {
                                    currentUrl = step.url
                                    loadUrlInWebView(rootWebView, step.url, step.referer, step.extraHeaders)
                                    delay(150)
                                }

                                is NavigationStep.ClickElement -> {
                                    val clicked = clickElementInWebView(
                                        webView = rootWebView,
                                        selector = step.selector,
                                        timeoutMs = step.timeoutMs,
                                        expectedUrl = currentUrl
                                    )
                                    if (!clicked) {
                                        logW("step[$index]", "ClickElement failed selector=${step.selector}")
                                        if (step.abortOnFailure) {
                                            failedStep = index
                                            errorMsg = "ClickElement failed: ${step.selector}"
                                            break
                                        }
                                    }
                                }

                                is NavigationStep.ClickCoordinates -> {
                                    dispatchNativeClick(rootWebView, step.x, step.y)
                                    delay(250)
                                }

                                is NavigationStep.ExecuteJs -> {
                                    val jsResult = executeJsInWebView(rootWebView, step.javascript)
                                    if (step.key.isNotBlank()) {
                                        extractedHtml[step.key] = jsResult ?: ""
                                        logD(
                                            "step[$index]",
                                            "ExecuteJs stored key='${step.key}' resultLen=${jsResult?.length ?: 0} preview=${safeTrim(jsResult, 140)}"
                                        )
                                    } else {
                                        logD(
                                            "step[$index]",
                                            "ExecuteJs resultLen=${jsResult?.length ?: 0} preview=${safeTrim(jsResult, 140)}"
                                        )
                                    }
                                    delay(150)
                                }

                                is NavigationStep.WaitForSelector -> {
                                    val found = waitForSelector(
                                        webView = rootWebView,
                                        selector = step.selector,
                                        timeoutMs = step.timeoutMs,
                                        expectedUrl = currentUrl
                                    )
                                    if (!found) {
                                        logW("step[$index]", "WaitForSelector timeout selector=${step.selector}")
                                        if (step.abortOnFailure) {
                                            failedStep = index
                                            errorMsg = "WaitForSelector timed out: ${step.selector}"
                                            break
                                        }
                                    }
                                }

                                is NavigationStep.WaitForUrl -> {
                                    val matched = waitForUrl(rootWebView, step.urlPattern, step.timeoutMs)
                                    if (!matched) {
                                        logW("step[$index]", "WaitForUrl timeout pattern=${step.urlPattern}")
                                        if (step.abortOnFailure) {
                                            failedStep = index
                                            errorMsg = "WaitForUrl timed out: ${step.urlPattern}"
                                            break
                                        }
                                    }
                                }

                                is NavigationStep.WaitForDelay -> {
                                    logD("step[$index]", "Delaying ${step.delayMs}ms")
                                    delay(step.delayMs)
                                }

                                is NavigationStep.WaitForDomCondition -> {
                                    val met = waitForDomCondition(
                                        webView = rootWebView,
                                        jsCondition = step.jsCondition,
                                        timeoutMs = step.timeoutMs,
                                        pollIntervalMs = step.pollIntervalMs
                                    )
                                    if (!met) {
                                        logW("step[$index]", "WaitForDomCondition timeout")
                                        if (step.abortOnFailure) {
                                            failedStep = index
                                            errorMsg = "WaitForDomCondition timed out"
                                            break
                                        }
                                    }
                                }

                                is NavigationStep.ExtractHtml -> {
                                    val html = extractHtmlFromWebView(rootWebView, step.selector)
                                    val key = step.key.ifBlank { step.selector ?: "full_page_$index" }
                                    extractedHtml[key] = html ?: ""
                                    logI("step[$index]", "ExtractHtml key='$key' len=${html?.length ?: 0}")
                                    dumpHtmlToCache(activity, key, html.orEmpty())
                                }

                                is NavigationStep.NavigateToWatchingUrl -> {
                                    val target = interceptedWatchingUrl
                                    if (!target.isNullOrBlank()) {
                                        logI("step[$index]", "NavigateToWatchingUrl -> ${safeTrim(target, 160)}")
                                        val referer = currentUrl.takeIf { it.isNotBlank() }
                                        loadUrlInWebView(rootWebView, target, referer, emptyMap())
                                        currentUrl = target
                                    } else {
                                        logW("step[$index]", "NavigateToWatchingUrl has no intercepted URL")
                                        if (step.abortOnFailure) {
                                            failedStep = index
                                            errorMsg = "No intercepted URL available"
                                            break
                                        }
                                    }
                                }
                            }

                            completedSteps = index + 1
                            currentUrl = getCurrentUrlFromWebView(rootWebView) ?: currentUrl
                            val took = SystemClock.uptimeMillis() - stepStart
                            logI(
                                "step[$index]",
                                "END $stepName took=${took}ms currentUrl=${safeTrim(currentUrl, 160)}"
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            failedStep = index
                            errorMsg = t.message ?: t.javaClass.simpleName
                            logE("step[$index]", "FAILED $stepName: ${errorMsg}", t)
                            break
                        }
                    }
                }
            } catch (t: TimeoutCancellationException) {
                failedStep = failedStep ?: completedSteps
                errorMsg = "Overall timeout after ${overallTimeoutMs}ms"
                logE("execute", errorMsg ?: "timeout", t)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failedStep = failedStep ?: completedSteps
                errorMsg = t.message ?: t.javaClass.simpleName
                logE("execute", "Session exception: $errorMsg", t)
            }

            val success = failedStep == null && errorMsg == null
            currentUrl = getCurrentUrlFromWebView(rootWebView) ?: currentUrl
            val cookies = extractCookiesFromManager(currentUrl)

            if (!success) {
                try {
                    val failureHtml = rootWebView?.let { extractHtmlFromWebView(it, null) }
                    if (!failureHtml.isNullOrBlank()) {
                        dumpHtmlToCache(activity, "failure_step_${failedStep ?: completedSteps}", failureHtml)
                        logW("execute", "Failure HTML dumped len=${failureHtml.length}")
                    }
                } catch (t: Throwable) {
                    logW("execute", "Failed to dump failure HTML: ${t.message}")
                }
            }

            val duration = SystemClock.uptimeMillis() - sessionStart
            logI(
                "execute",
                buildString {
                    append("Session finished. success=$success, ")
                    append("completedSteps=$completedSteps, ")
                    append("failedStep=$failedStep, ")
                    append("durationMs=$duration, ")
                    append("finalUrl=${safeTrim(currentUrl, 160)}, ")
                    append("cookieKeys=${cookies.keys.joinToString(",")}, ")
                    append("htmlKeys=${extractedHtml.keys.joinToString(",")}")
                }
            )

            cleanupWebViews(rootWebView, popupWebViews, dialog)

            NavigationResult(
                success = success,
                finalUrl = currentUrl,
                cookies = cookies,
                extractedHtml = extractedHtml,
                completedSteps = completedSteps,
                failedAtStep = failedStep,
                error = errorMsg
            )
        }
    }

    /**
     * Sandbox JS execution with network blocked.
     * Safe utility for generic parsing/debugging.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun executeJsSandbox(javascript: String): String? {
        val activity = activityProvider()?.takeIf { !it.isFinishing }
        if (activity == null) {
            logE("executeJsSandbox", "No valid Activity available")
            return null
        }

        return withContext(Dispatchers.Main.immediate) {
            val webView = createWebView(activity, userAgent = "", isPopup = false)
            try {
                webView.settings.blockNetworkLoads = true
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = false

                awaitAboutBlank(webView)
                val wrapped = "(function(){ try { $javascript } catch(e) { return 'sandbox_error:' + e.message; } })();"
                val result = executeJsInWebView(webView, wrapped)
                logI("executeJsSandbox", "Completed resultLen=${result?.length ?: 0}")
                result
            } catch (t: Throwable) {
                logE("executeJsSandbox", "Failed: ${t.message}", t)
                null
            } finally {
                cleanupSingleWebView(webView)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // WebView creation / setup
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, userAgent: String, isPopup: Boolean): WebView {
        val id = if (isPopup) "popup#${popupCounter.getAndIncrement()}" else "root#${webViewCounter.getAndIncrement()}"
        val webView = WebView(context)
        return webView.apply {
            tag = id
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                if (userAgent.isNotBlank()) {
                    userAgentString = userAgent
                }
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                loadsImagesAutomatically = true
                blockNetworkImage = false
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = false
                displayZoomControls = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }

            addJavascriptInterface(DebugBridge(this), "NavEngineDebugBridge")

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            logI("createWebView", "Created WebView id=$id uaLen=${userAgent.length}")
        }
    }

    private fun setupWebView(
        webView: WebView,
        userAgent: String,
        requestInterceptor: ((view: WebView, request: WebResourceRequest) -> WebResourceResponse?)?,
        allowedDomains: Set<String>,
        destinationLockPatterns: List<Regex>,
        popupWebViews: MutableList<WebView>,
        containerProvider: () -> FrameLayout?,
        isPopup: Boolean
    ) {
        var isOnDestination = false
        val webViewId = webViewId(webView)

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val u = url.orEmpty()
                logI("onPageStarted/$webViewId", "url=${safeTrim(u, 220)}")

                if (destinationLockPatterns.any { it.containsMatchIn(u) }) {
                    if (!isOnDestination) {
                        logI("onPageStarted/$webViewId", "Destination lock ENGAGED by url=$u")
                    }
                    isOnDestination = true
                }

                super.onPageStarted(view, url, favicon)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                logI("onPageCommitVisible/$webViewId", "url=${safeTrim(url, 220)}")
                injectDebugInstrumentation(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val u = url.orEmpty()
                logI("onPageFinished/$webViewId", "url=${safeTrim(u, 220)}")
                injectDebugInstrumentation(view)

                view?.evaluateJavascript("(function(){return document.readyState;})();") {
                    logD("onPageFinished/$webViewId", "readyState=${decodeJsResult(it)}")
                }
                view?.evaluateJavascript("(function(){return document.title;})();") {
                    logD("onPageFinished/$webViewId", "title=${safeTrim(decodeJsResult(it), 140)}")
                }
                view?.evaluateJavascript("(function(){return document.body ? document.body.innerHTML.length : -1;})();") {
                    logD("onPageFinished/$webViewId", "bodyLength=${decodeJsResult(it)}")
                }

                try {
                    val cookieStr = if (u.isNotBlank()) CookieManager.getInstance().getCookie(u).orEmpty() else ""
                    logD(
                        "onPageFinished/$webViewId",
                        "cookies(len=${cookieStr.length})=${safeTrim(cookieStr, 220)}"
                    )
                } catch (t: Throwable) {
                    logW("onPageFinished/$webViewId", "cookie read failed: ${t.message}")
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                logD("history/$webViewId", "url=${safeTrim(url, 220)} reload=$isReload")
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null || view == null) return null

                val url = request.url?.toString().orEmpty()
                val scheme = request.url?.scheme?.lowercase().orEmpty()
                val host = request.url?.host?.lowercase().orEmpty()
                val path = request.url?.path.orEmpty()
                val isMain = request.isForMainFrame
                val method = request.method.orEmpty()
                val headers = request.requestHeaders ?: emptyMap()

                if (scheme != "http" && scheme != "https") {
                    logD("intercept/$webViewId", "skip non-http url=$url")
                    return null
                }

                val redirectInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    " redirect=${request.isRedirect}"
                } else ""

                logD(
                    "intercept/$webViewId",
                    buildString {
                        append("REQ main=$isMain method=$method host=$host path=${safeTrim(path, 120)}$redirectInfo ")
                        append("headers=${summarizeHeaders(headers)} ")
                        append("url=${safeTrim(url, 220)}")
                    }
                )

                // Safe policy:
                // - Never intercept main-frame documents
                // - Never interfere with normal browser navigation
                if (isMain) {
                    return null
                }

                // Allow optional caller-supplied interception for subresources only.
                return try {
                    requestInterceptor?.invoke(view, request)
                } catch (t: Throwable) {
                    logE("intercept/$webViewId", "Custom requestInterceptor failed: ${t.message}", t)
                    null
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request == null) return false

                val nextUrl = request.url?.toString().orEmpty()
                val scheme = request.url?.scheme?.lowercase().orEmpty()
                val isMain = request.isForMainFrame
                val nextHost = try {
                    URI(nextUrl).host?.lowercase().orEmpty()
                } catch (_: Throwable) {
                    ""
                }

                logI(
                    "shouldOverride/$webViewId",
                    "main=$isMain host=$nextHost currentLocked=$isOnDestination isPopup=$isPopup next=${safeTrim(nextUrl, 220)}"
                )

                if (scheme != "http" && scheme != "https") {
                    logW("shouldOverride/$webViewId", "Blocking non-http scheme url=$nextUrl")
                    return true
                }

                if (!isPopup && isMain && isOnDestination) {
                    logW("shouldOverride/$webViewId", "Destination lock blocked navigation to $nextUrl")
                    return true
                }

                if (!isPopup && isMain && allowedDomains.isNotEmpty()) {
                    val allowed = allowedDomains.any { allowedDomain ->
                        nextHost == allowedDomain || nextHost.endsWith(".$allowedDomain")
                    }
                    if (!allowed) {
                        logW(
                            "shouldOverride/$webViewId",
                            "Allowed-domain block host=$nextHost allowed=${allowedDomains.joinToString(",")}"
                        )
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
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        error?.description?.toString()
                    } else {
                        error?.toString()
                    }
                    logW(
                        "onReceivedError/$webViewId",
                        "mainFrame url=${safeTrim(request.url?.toString(), 220)} desc=${desc.orEmpty()}"
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                logW(
                    "onHttpError/$webViewId",
                    "main=${request?.isForMainFrame} status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} url=${safeTrim(request?.url?.toString(), 220)}"
                )
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                logE("onSslError/$webViewId", "SSL error for url=${error?.url.orEmpty()} -- cancelling")
                handler?.cancel()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                logE(
                    "renderGone/$webViewId",
                    "didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}"
                )
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 0 || newProgress == 100 || newProgress % 25 == 0) {
                    logD("progress/$webViewId", "progress=$newProgress url=${safeTrim(view?.url, 180)}")
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                logD("title/$webViewId", safeTrim(title, 160))
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val lvl = when (it.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "E"
                        ConsoleMessage.MessageLevel.WARNING -> "W"
                        ConsoleMessage.MessageLevel.TIP -> "T"
                        ConsoleMessage.MessageLevel.LOG -> "L"
                        ConsoleMessage.MessageLevel.DEBUG -> "D"
                    }
                    val msg = "[Console/$webViewId/$lvl] ${it.message()} [${it.sourceId()}:${it.lineNumber()}]"
                    logD("console/$webViewId", msg)
                }
                return true
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val parentView = view ?: return false
                val ctx = parentView.context
                val child = createWebView(ctx, userAgent, isPopup = true)

                popupWebViews.add(child)
                setupWebView(
                    webView = child,
                    userAgent = userAgent,
                    requestInterceptor = requestInterceptor,
                    allowedDomains = allowedDomains,
                    destinationLockPatterns = destinationLockPatterns,
                    popupWebViews = popupWebViews,
                    containerProvider = containerProvider,
                    isPopup = true
                )

                val maybeContainer = containerProvider()
                if (maybeContainer != null) {
                    try {
                        maybeContainer.addView(
                            child,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        logI(
                            "popup/$webViewId",
                            "Popup WebView attached id=${webViewId(child)} isDialog=$isDialog isUserGesture=$isUserGesture"
                        )
                    } catch (t: Throwable) {
                        logW("popup/$webViewId", "Failed to attach popup view: ${t.message}")
                    }
                } else {
                    logI(
                        "popup/$webViewId",
                        "Popup WebView created in headless mode id=${webViewId(child)} isDialog=$isDialog isUserGesture=$isUserGesture"
                    )
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport != null) {
                    transport.webView = child
                    resultMsg.sendToTarget()
                    return true
                }

                logW("popup/$webViewId", "Popup transport missing; discarding child")
                cleanupSingleWebView(child)
                popupWebViews.remove(child)
                return false
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                if (window != null) {
                    logI("popup/${webViewId(window)}", "onCloseWindow")
                    popupWebViews.remove(window)
                    cleanupSingleWebView(window)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step helpers
    // ---------------------------------------------------------------------------------------------

    private fun loadUrlInWebView(
        webView: WebView,
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>
    ) {
        val headers = mutableMapOf<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        headers.putAll(extraHeaders)

        logI(
            "loadUrl/${webViewId(webView)}",
            "url=${safeTrim(url, 220)} headers=${summarizeHeaders(headers)}"
        )
        webView.loadUrl(url, headers)
    }

    private suspend fun clickElementInWebView(
        webView: WebView,
        selector: String,
        timeoutMs: Long,
        expectedUrl: String = ""
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val expectedHost = try { URI(expectedUrl).host?.lowercase() } catch (_: Throwable) { null }
        var attempts = 0

        while (System.currentTimeMillis() < deadline) {
            attempts++

            if (expectedHost != null) {
                val currentWebviewUrl = withContext(Dispatchers.Main.immediate) { webView.url.orEmpty() }
                val currentHost = try { URI(currentWebviewUrl).host?.lowercase() } catch (_: Throwable) { null }
                if (currentHost != null && currentHost != expectedHost) {
                    logW(
                        "clickElement/${webViewId(webView)}",
                        "Host changed expected=$expectedHost current=$currentHost; breaking early"
                    )
                    return false
                }
            }

            // 1) JS-first click strategy
            val jsClicked = jsClickElement(webView, selector)
            if (jsClicked) {
                logI("clickElement/${webViewId(webView)}", "JS click success selector=$selector attempts=$attempts")
                return true
            }

            // 2) Native tap fallback
            val coords = findElementCoordinates(webView, selector)
            if (coords != null) {
                dispatchNativeClick(webView, coords.first, coords.second)
                logI(
                    "clickElement/${webViewId(webView)}",
                    "Native click success selector=$selector x=${coords.first} y=${coords.second} attempts=$attempts"
                )
                return true
            }

            delay(400)
        }

        logW("clickElement/${webViewId(webView)}", "Element not clicked selector=$selector timeoutMs=$timeoutMs")
        return false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun jsClickElement(webView: WebView, selector: String): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                val js = """
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (!el) return JSON.stringify({ clicked:false, reason:'not_found' });
                        try {
                            if (el.scrollIntoView) el.scrollIntoView({block:'center', inline:'center'});
                        } catch(e) {}
                        try { if (el.focus) el.focus(); } catch(e) {}

                        try {
                            var rect = el.getBoundingClientRect();
                            var cx = rect.left + rect.width / 2;
                            var cy = rect.top + rect.height / 2;

                            var events = ['pointerdown','mousedown','pointerup','mouseup','click'];
                            for (var i = 0; i < events.length; i++) {
                                try {
                                    var ev = new MouseEvent(events[i], {
                                        view: window,
                                        bubbles: true,
                                        cancelable: true,
                                        clientX: cx,
                                        clientY: cy,
                                        button: 0
                                    });
                                    el.dispatchEvent(ev);
                                } catch(e) {}
                            }
                        } catch(e) {}

                        try { el.click(); } catch(e) {}

                        return JSON.stringify({
                            clicked:true,
                            tag: el.tagName || '',
                            id: el.id || '',
                            classes: (el.className || '').toString().slice(0, 160),
                            href: el.href || '',
                            text: ((el.innerText || el.textContent || '').trim().slice(0, 120))
                        });
                    })();
                """.trimIndent()

                webView.evaluateJavascript(js) { result ->
                    try {
                        val parsed = decodeJsResult(result)
                        val clicked = if (!parsed.isNullOrBlank() && parsed.startsWith("{")) {
                            JSONObject(parsed).optBoolean("clicked", false)
                        } else {
                            parsed == "true"
                        }
                        logD(
                            "jsClick/${webViewId(webView)}",
                            "selector=$selector clicked=$clicked raw=${safeTrim(parsed, 220)}"
                        )
                        if (cont.isActive) cont.resume(clicked)
                    } catch (t: Throwable) {
                        logW("jsClick/${webViewId(webView)}", "Parse failed: ${t.message}")
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun findElementCoordinates(webView: WebView, selector: String): Pair<Float, Float>? {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                val js = """
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (!el) return JSON.stringify({ found:false });
                        try { if (el.scrollIntoView) el.scrollIntoView({block:'center', inline:'center'}); } catch(e) {}
                        var rect = el.getBoundingClientRect();
                        var cs = window.getComputedStyle(el);
                        return JSON.stringify({
                            found: true,
                            tag: el.tagName || '',
                            id: el.id || '',
                            classes: (el.className || '').toString().slice(0, 160),
                            rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
                            display: cs.display,
                            visibility: cs.visibility,
                            opacity: cs.opacity,
                            enabled: !el.disabled,
                            visible: !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)
                        });
                    })();
                """.trimIndent()

                webView.evaluateJavascript(js) { result ->
                    try {
                        val parsed = decodeJsResult(result)
                        if (parsed.isNullOrBlank() || !parsed.startsWith("{")) {
                            if (cont.isActive) cont.resume(null)
                            return@evaluateJavascript
                        }
                        val obj = JSONObject(parsed)
                        if (!obj.optBoolean("found", false)) {
                            logD("coords/${webViewId(webView)}", "selector=$selector not found")
                            if (cont.isActive) cont.resume(null)
                            return@evaluateJavascript
                        }

                        val rect = obj.optJSONObject("rect")
                        val w = rect?.optDouble("width") ?: 0.0
                        val h = rect?.optDouble("height") ?: 0.0
                        val left = rect?.optDouble("left") ?: 0.0
                        val top = rect?.optDouble("top") ?: 0.0

                        logD(
                            "coords/${webViewId(webView)}",
                            "selector=$selector rect=($left,$top,$w,$h) display=${obj.optString("display")} visibility=${obj.optString("visibility")} visible=${obj.optBoolean("visible")}"
                        )

                        if (w > 0.0 && h > 0.0) {
                            // Important: do NOT multiply by DPR here.
                            val x = (left + (w / 2.0)).toFloat()
                            val y = (top + (h / 2.0)).toFloat()
                            if (cont.isActive) cont.resume(Pair(x, y))
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    } catch (t: Throwable) {
                        logW("coords/${webViewId(webView)}", "Parse error: ${t.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    private fun dispatchNativeClick(webView: WebView, x: Float, y: Float) {
        Handler(Looper.getMainLooper()).post {
            try {
                val downTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                webView.dispatchTouchEvent(down)
                down.recycle()

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val upTime = SystemClock.uptimeMillis()
                        val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
                        webView.dispatchTouchEvent(up)
                        up.recycle()
                        logD("nativeClick/${webViewId(webView)}", "dispatched x=$x y=$y")
                    } catch (t: Throwable) {
                        logW("nativeClick/${webViewId(webView)}", "UP event failed: ${t.message}")
                    }
                }, 60)
            } catch (t: Throwable) {
                logE("nativeClick/${webViewId(webView)}", "DOWN event failed: ${t.message}", t)
            }
        }
    }

    private suspend fun waitForSelector(
        webView: WebView,
        selector: String,
        timeoutMs: Long,
        expectedUrl: String = ""
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val expectedHost = try { URI(expectedUrl).host?.lowercase() } catch (_: Throwable) { null }
        var polls = 0

        while (System.currentTimeMillis() < deadline) {
            polls++

            if (expectedHost != null) {
                val currentUrl = withContext(Dispatchers.Main.immediate) { webView.url.orEmpty() }
                val currentHost = try { URI(currentUrl).host?.lowercase() } catch (_: Throwable) { null }
                if (currentHost != null && currentHost != expectedHost) {
                    logW(
                        "waitForSelector/${webViewId(webView)}",
                        "Host changed expected=$expectedHost current=$currentHost; breaking"
                    )
                    return false
                }
            }

            val found = checkSelectorExists(webView, selector)
            logD(
                "waitForSelector/${webViewId(webView)}",
                "poll#$polls selector=$selector found=$found remaining=${deadline - System.currentTimeMillis()}ms"
            )
            if (found) {
                return true
            }
            delay(400)
        }

        logW("waitForSelector/${webViewId(webView)}", "Timeout selector=$selector")
        return false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun checkSelectorExists(webView: WebView, selector: String): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                val js = """
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        return JSON.stringify({
                            exists: !!el,
                            tag: el ? (el.tagName || '') : '',
                            id: el ? (el.id || '') : '',
                            classes: el ? ((el.className || '').toString().slice(0,160)) : '',
                            visible: el ? !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length) : false
                        });
                    })();
                """.trimIndent()

                webView.evaluateJavascript(js) { result ->
                    try {
                        val parsed = decodeJsResult(result)
                        val exists = if (!parsed.isNullOrBlank() && parsed.startsWith("{")) {
                            val obj = JSONObject(parsed)
                            logD(
                                "selectorExists/${webViewId(webView)}",
                                "selector=$selector exists=${obj.optBoolean("exists")} tag=${obj.optString("tag")} id=${obj.optString("id")} visible=${obj.optBoolean("visible")}"
                            )
                            obj.optBoolean("exists", false)
                        } else {
                            parsed == "true"
                        }
                        if (cont.isActive) cont.resume(exists)
                    } catch (t: Throwable) {
                        logW("selectorExists/${webViewId(webView)}", "Parse failed: ${t.message}")
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
        }
    }

    private suspend fun waitForUrl(webView: WebView, urlPattern: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val regex = Regex(urlPattern)
        var polls = 0

        while (System.currentTimeMillis() < deadline) {
            polls++
            val currentUrl = getCurrentUrlFromWebView(webView).orEmpty()
            val matched = regex.containsMatchIn(currentUrl)
            logD(
                "waitForUrl/${webViewId(webView)}",
                "poll#$polls matched=$matched current=${safeTrim(currentUrl, 220)} remaining=${deadline - System.currentTimeMillis()}ms"
            )
            if (matched) return true
            delay(400)
        }

        logW("waitForUrl/${webViewId(webView)}", "Timeout pattern=$urlPattern")
        return false
    }

    private suspend fun waitForDomCondition(
        webView: WebView,
        jsCondition: String,
        timeoutMs: Long,
        pollIntervalMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var polls = 0

        while (System.currentTimeMillis() < deadline) {
            polls++
            val met = evaluateDomCondition(webView, jsCondition)
            logD(
                "waitForDom/${webViewId(webView)}",
                "poll#$polls met=$met remaining=${deadline - System.currentTimeMillis()}ms condition=${safeTrim(jsCondition, 140)}"
            )
            if (met) return true
            delay(pollIntervalMs)
        }

        logW("waitForDom/${webViewId(webView)}", "Timeout")
        return false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun evaluateDomCondition(webView: WebView, jsCondition: String): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val js = """
                    (function() {
                        try { return !!($jsCondition); }
                        catch(e) { return false; }
                    })();
                """.trimIndent()

                webView.evaluateJavascript(js) { result ->
                    if (cont.isActive) cont.resume(result == "true")
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun executeJsInWebView(webView: WebView, javascript: String): String? {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                logD(
                    "executeJs/${webViewId(webView)}",
                    "js=${safeTrim(javascript.replace("\n", " "), 220)}"
                )
                webView.evaluateJavascript(javascript) { result ->
                    val decoded = decodeJsResult(result)
                    logD(
                        "executeJs/${webViewId(webView)}",
                        "resultLen=${decoded?.length ?: 0} preview=${safeTrim(decoded, 180)}"
                    )
                    if (cont.isActive) cont.resume(decoded)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun extractHtmlFromWebView(webView: WebView, selector: String?): String? {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val js = if (selector != null) {
                    val safeSelector = selector.replace("'", "\\'")
                    "(function(){ var el = document.querySelector('$safeSelector'); return el ? el.outerHTML : null; })();"
                } else {
                    "(function(){ return document.documentElement ? document.documentElement.outerHTML : null; })();"
                }

                webView.evaluateJavascript(js) { result ->
                    val html = decodeJsResult(result)
                    logD(
                        "extractHtml/${webViewId(webView)}",
                        "selector=${selector ?: "<document>"} len=${html?.length ?: 0}"
                    )
                    if (cont.isActive) cont.resume(html)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sandbox helper
    // ---------------------------------------------------------------------------------------------

    private suspend fun awaitAboutBlank(webView: WebView) {
        val timeoutMs = 5_000L
        val start = SystemClock.uptimeMillis()

        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun loadBlank() = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            val prev = webView.webViewClient
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    webView.webViewClient = prev
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            webView.loadUrl("about:blank")
        }

        try {
            withTimeout(timeoutMs) { loadBlank() }
        } catch (_: Throwable) {
            logW("awaitAboutBlank", "Timed out after ${SystemClock.uptimeMillis() - start}ms")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Dialog / cleanup
    // ---------------------------------------------------------------------------------------------

    private fun createDialog(activity: Activity, rootWebView: WebView): Pair<Dialog, FrameLayout> {
        val container = FrameLayout(activity).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        if (rootWebView.parent is ViewGroup) {
            (rootWebView.parent as ViewGroup).removeView(rootWebView)
        }

        container.addView(
            rootWebView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(container)
            setCancelable(true)
            window?.let { w ->
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        )
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            setOnDismissListener {
                logI("dialog", "Dismissed")
            }
        }

        return dialog to container
    }

    private fun cleanupWebViews(root: WebView?, popups: List<WebView>, dialog: Dialog?) {
        try {
            dialog?.dismiss()
        } catch (_: Throwable) {
        }

        try {
            popups.toList().forEach { cleanupSingleWebView(it) }
        } catch (_: Throwable) {
        }

        try {
            root?.let { cleanupSingleWebView(it) }
        } catch (_: Throwable) {
        }

        logI("cleanup", "WebViews cleaned up")
    }

    private fun cleanupSingleWebView(webView: WebView) {
        Handler(Looper.getMainLooper()).post {
            try {
                val id = webViewId(webView)
                logD("cleanup/$id", "Begin")
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
                logD("cleanup/$id", "Done")
            } catch (t: Throwable) {
                logW("cleanup/${webViewId(webView)}", "Failed: ${t.message}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    private fun getCurrentUrlFromWebView(webView: WebView?): String? {
        return try {
            webView?.url
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractCookiesFromManager(url: String): Map<String, String> {
        if (url.isBlank()) return emptyMap()
        return try {
            val raw = CookieManager.getInstance().getCookie(url).orEmpty()
            if (raw.isBlank()) emptyMap() else parseCookieString(raw)
        } catch (t: Throwable) {
            logW("cookies", "Failed to read cookies for $url: ${t.message}")
            emptyMap()
        }
    }

    private fun parseCookieString(cookie: String): Map<String, String> {
        return cookie.split(";")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            .filter { it.first.isNotBlank() }
            .toMap()
    }

    private fun dumpHtmlToCache(activity: Activity?, key: String, html: String) {
        if (activity == null) return
        try {
            val file = File(activity.cacheDir, "navengine_${sanitizeFilePart(key)}.html")
            file.writeText(html)
            logI("dumpHtml", "Wrote ${file.absolutePath} (${html.length} chars)")
        } catch (t: Throwable) {
            logW("dumpHtml", "Failed for key=$key: ${t.message}")
        }
    }

    private fun sanitizeFilePart(s: String): String {
        return s.replace(Regex("[^a-zA-Z0-9._-]+"), "_").take(80)
    }

    private fun injectDebugInstrumentation(webView: WebView?) {
        if (webView == null) return
        webView.evaluateJavascript(DEBUG_INSTRUMENTATION_JS, null)
    }

    private fun decodeJsResult(result: String?): String? {
        return try {
            if (result == null || result == "null") null
            else JSONTokener(result).nextValue().toString()
        } catch (_: Throwable) {
            result
        }
    }

    private fun summarizeHeaders(headers: Map<String, String>): String {
        if (headers.isEmpty()) return "{}"
        return headers.entries.joinToString(
            prefix = "{",
            postfix = "}"
        ) { (k, v) ->
            "$k=${safeTrim(v, 60)}"
        }
    }

    private fun safeTrim(value: String?, max: Int): String {
        if (value == null) return "null"
        val clean = value.replace("\n", "\\n").replace("\r", "\\r")
        return if (clean.length <= max) clean else clean.take(max) + "…"
    }

    private fun webViewId(webView: WebView?): String {
        return webView?.tag?.toString() ?: "unknown"
    }

    // ---------------------------------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------------------------------

    private fun logD(method: String, message: String) {
        try { ProviderLogger.d(TAG, method, message) } catch (_: Throwable) {}
        Log.d(TAG, "[$method] $message")
    }

    private fun logI(method: String, message: String) {
        try { ProviderLogger.i(TAG, method, message) } catch (_: Throwable) {}
        Log.i(TAG, "[$method] $message")
    }

    private fun logW(method: String, message: String) {
        try { ProviderLogger.w(TAG, method, message) } catch (_: Throwable) {}
        Log.w(TAG, "[$method] $message")
    }

    private fun logE(method: String, message: String, t: Throwable? = null) {
        val full = if (t != null) "$message\n${Log.getStackTraceString(t)}" else message
        try { ProviderLogger.e(TAG, method, full) } catch (_: Throwable) {}
        Log.e(TAG, "[$method] $full")
    }

    // ---------------------------------------------------------------------------------------------
    // JS bridge (only for logging)
    // ---------------------------------------------------------------------------------------------

    private inner class DebugBridge(webView: WebView) {
        private val ref = WeakReference(webView)

        @JavascriptInterface
        fun log(message: String?) {
            val id = webViewId(ref.get())
            logD("jsBridge/$id", safeTrim(message, 260))
        }
    }

    companion object {
        private const val TAG = "NavigationEngineSafe"

        /**
         * Generic debug instrumentation:
         * - logs link clicks
         * - logs history changes
         * - logs fetch/XHR starts/ends
         * - logs window.open
         *
         * This is for debugging/observability only.
         */
        private val DEBUG_INSTRUMENTATION_JS = """
            (function() {
                try {
                    if (window.__NAV_ENGINE_DEBUG_INSTALLED__) return 'already';
                    window.__NAV_ENGINE_DEBUG_INSTALLED__ = true;

                    var prefix = '[NavEngineJS]';
                    var clog = function() {
                        try {
                            var args = Array.prototype.slice.call(arguments);
                            args.unshift(prefix);
                            console.log.apply(console, args);
                        } catch (e) {}
                    };

                    clog('install', location.href);

                    document.addEventListener('click', function(ev) {
                        try {
                            var el = ev.target;
                            var a = el && el.closest ? el.closest('a[href]') : null;
                            if (a) {
                                var text = ((a.innerText || a.textContent || '').trim() || '').slice(0, 120);
                                clog('click<a>', 'href=' + (a.href || ''), 'target=' + (a.target || ''), 'text=' + text);
                            }
                        } catch(e) {}
                    }, true);

                    try {
                        var origPush = history.pushState;
                        history.pushState = function() {
                            try { clog('pushState', arguments[2] || ''); } catch(e) {}
                            return origPush.apply(this, arguments);
                        };
                    } catch(e) {}

                    try {
                        var origReplace = history.replaceState;
                        history.replaceState = function() {
                            try { clog('replaceState', arguments[2] || ''); } catch(e) {}
                            return origReplace.apply(this, arguments);
                        };
                    } catch(e) {}

                    try {
                        window.addEventListener('hashchange', function() { clog('hashchange', location.href); }, true);
                        window.addEventListener('popstate', function() { clog('popstate', location.href); }, true);
                    } catch(e) {}

                    try {
                        if (window.fetch && !window.__NAV_ENGINE_FETCH_WRAPPED__) {
                            window.__NAV_ENGINE_FETCH_WRAPPED__ = true;
                            var origFetch = window.fetch;
                            window.fetch = function() {
                                var input = arguments[0];
                                var u = '';
                                try {
                                    if (typeof input === 'string') u = input;
                                    else if (input && input.url) u = input.url;
                                } catch(e) {}
                                try { clog('fetch:start', u); } catch(e) {}
                                return origFetch.apply(this, arguments).then(function(resp) {
                                    try { clog('fetch:end', (resp && resp.url) || u, 'status=' + ((resp && resp.status) || '')); } catch(e) {}
                                    return resp;
                                }).catch(function(err) {
                                    try { clog('fetch:error', u, (err && err.message) || ''); } catch(e) {}
                                    throw err;
                                });
                            };
                        }
                    } catch(e) {}

                    try {
                        if (window.XMLHttpRequest && !window.__NAV_ENGINE_XHR_WRAPPED__) {
                            window.__NAV_ENGINE_XHR_WRAPPED__ = true;
                            var xo = XMLHttpRequest.prototype.open;
                            var xs = XMLHttpRequest.prototype.send;

                            XMLHttpRequest.prototype.open = function(method, url) {
                                try {
                                    this.__ne_method = method;
                                    this.__ne_url = url;
                                    clog('xhr:open', method, url);
                                } catch(e) {}
                                return xo.apply(this, arguments);
                            };

                            XMLHttpRequest.prototype.send = function() {
                                try {
                                    var self = this;
                                    self.addEventListener('loadend', function() {
                                        try {
                                            clog('xhr:end', self.__ne_method || '', self.__ne_url || '', 'status=' + self.status);
                                        } catch(e) {}
                                    });
                                } catch(e) {}
                                return xs.apply(this, arguments);
                            };
                        }
                    } catch(e) {}

                    try {
                        if (window.open && !window.__NAV_ENGINE_OPEN_WRAPPED__) {
                            window.__NAV_ENGINE_OPEN_WRAPPED__ = true;
                            var origOpen = window.open;
                            window.open = function(url, name, specs) {
                                try { clog('window.open', url || '', name || '', specs || ''); } catch(e) {}
                                return origOpen.apply(window, arguments);
                            };
                        }
                    } catch(e) {}

                    return 'installed';
                } catch (e) {
                    try { console.log('[NavEngineJS]', 'instrumentation_error', e.message || ''); } catch(_) {}
                    return 'error:' + (e.message || '');
                }
            })();
        """.trimIndent()
    }
}