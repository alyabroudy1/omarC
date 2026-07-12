package com.cloudstream.shared.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
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

    /** Set by the request interceptor when get-link.php returns the watching URL */
    @Volatile
    var interceptedWatchingUrl: String? = null

    /** Set when a redirect is pending user approval via the confirmation dialog */
    @Volatile
    var pendingRedirectUrl: String? = null

    /**
     * The raw HTML body of the last intercepted main-frame request (cimanow.cc /watching/).
     * Populated by shouldInterceptRequest when it intercepts a main-frame text/html response.
     * Consumed after execute() completes to parse server data directly from the HTTP response
     * bytes, bypassing the WebView's JS environment entirely.
     */
    @Volatile
    var capturedMainFrameHtml: String? = null
    var autoApproveAllRedirects: Boolean = false

    /** Last baseUrl used by a LoadHtml step — used as the Referer when navigating
     *  to the watching URL (the real browser sends the timer-page referer). */
    var lastHtmlBaseUrl: String? = null

    /** Actual playable video stream URLs captured from network traffic (e.g. VK CDN
     *  vkuser.net) while the WebView plays the embedded video. Returned as a bonus
     *  alongside the extracted HTML so the provider can use them directly. */
    val capturedVideoUrls = java.util.concurrent.CopyOnWriteArrayList<String>()

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
            // Reset intercepted state for this session
            interceptedWatchingUrl = null
            pendingRedirectUrl = null
            autoApproveAllRedirects = false
            lastHtmlBaseUrl = null
            capturedMainFrameHtml = null
            capturedVideoUrls.clear()

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
                        error = "Overall timeout",
                        capturedVideoUrls = capturedVideoUrls.toList()
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
                            is NavigationStep.LoadHtml -> {
                                ProviderLogger.w(TAG, "execute", "Step $index: LoadHtml",
                                    "baseUrl" to step.baseUrl.take(100),
                                    "htmlLen" to step.html.length.toString())
                                currentUrl = step.baseUrl
                                lastHtmlBaseUrl = step.baseUrl
                                webView.loadDataWithBaseURL(step.baseUrl, step.html, "text/html", "UTF-8", null)
                                ProviderLogger.d(TAG, "execute", "Step $index: LoadHtml — loadDataWithBaseURL called, waiting 2s for initial render")
                                delay(2000)
                            }
                            is NavigationStep.ClickElement -> {
                                val clicked = clickElementInWebView(webView, step.selector, step.timeoutMs, currentUrl)
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
                                val found = waitForSelector(webView, step.selector, step.timeoutMs, currentUrl)
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
                            is NavigationStep.WaitForDomConditionAndSnapshot -> {
                                val snap = waitForDomConditionAndSnapshot(
                                    webView, step.jsCondition,
                                    step.snapshotJs, step.timeoutMs, step.pollIntervalMs
                                )
                                extractedHtml[step.key] = snap ?: ""
                                if (snap == null) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: WaitForDomConditionAndSnapshot timed out")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "WaitForDomConditionAndSnapshot timed out"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.ExtractHtml -> {
                                val html = extractHtmlFromWebView(webView, step.selector)
                                val key = step.key.ifBlank { step.selector ?: "full_page_${index}" }
                                extractedHtml[key] = html ?: ""
                                val len = html?.length ?: 0
                                ProviderLogger.i(TAG, "execute", "Step $index: ExtractHtml ${key.take(40)} -> $len chars")
                                activityProvider()?.let { ctx ->
                                    try {
                                        val file = java.io.File(ctx.cacheDir, "cimanow_html_${key}.html")
                                        file.writeText(html.orEmpty())
                                        ProviderLogger.i("CimaNowHtmlDump", "writeHtml", "HTML $key written to ${file.absolutePath} ($len bytes)")
                                    } catch (e: Exception) {
                                        ProviderLogger.w("CimaNowHtmlDump", "writeHtml", "Failed to write HTML $key: ${e.message}")
                                    }
                                    try {
                                        val dlDir = ctx.externalCacheDir ?: ctx.cacheDir
                                        dlDir.mkdirs()
                                        val dlFile = java.io.File(dlDir, "cimanow_html_${key}.html")
                                        dlFile.writeText(html.orEmpty())
                                        ProviderLogger.i("CimaNowHtmlDump", "writeHtml", "HTML $key written to EXTCACHE ${dlFile.absolutePath} ($len bytes)")
                                    } catch (e: Exception) {
                                        ProviderLogger.w("CimaNowHtmlDump", "writeHtml", "Failed to write HTML $key to Downloads: ${e.message}")
                                    }
                                }
                            }
                            is NavigationStep.NavigateToWatchingUrl -> {
                                // Enable auto-approve for the redirect chain through ad domains
                                this@NavigationEngine.autoApproveAllRedirects = true
                                // Referer for the watching request should be the timer page
                                // (lastHtmlBaseUrl). currentUrl is reset to "about:blank" after
                                // loadDataWithBaseURL, so prefer lastHtmlBaseUrl and never send about:blank.
                                val refererForWatching = (lastHtmlBaseUrl ?: currentUrl)
                                    .takeIf { it.isNotBlank() && it != "about:blank" }
                                val watchUrl = this@NavigationEngine.interceptedWatchingUrl
                                if (!watchUrl.isNullOrBlank()) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: Navigating to watching URL: ${watchUrl.take(120)}")
                                    ProviderLogger.w(TAG, "execute", "Step $index: Watching Referer = ${refererForWatching ?: "<none>"}")
                                    // Pre-approve the redirect so no confirmation dialog appears
                                    this@NavigationEngine.pendingRedirectUrl = watchUrl
                                    // Use the timer page URL (blog-post.html) as Referer to prevent hotlink blocking
                                    loadUrlInWebView(webView, watchUrl, refererForWatching, emptyMap())
                                    currentUrl = watchUrl
                                } else {
                                    ProviderLogger.w(TAG, "execute", "Step $index: No watching URL captured yet, polling...")
                                    val deadline = System.currentTimeMillis() + 15_000L
                                    var polled = false
                                    while (System.currentTimeMillis() < deadline) {
                                        val url = this@NavigationEngine.interceptedWatchingUrl
                                        if (!url.isNullOrBlank()) {
                                            ProviderLogger.w(TAG, "execute", "Step $index: Watching URL appeared after polling: ${url.take(120)}")
                                            ProviderLogger.w(TAG, "execute", "Step $index: Watching Referer = ${refererForWatching ?: "<none>"}")
                                            // Pre-approve the redirect so no confirmation dialog appears
                                            this@NavigationEngine.pendingRedirectUrl = url
                                            loadUrlInWebView(webView, url, refererForWatching, emptyMap())
                                            currentUrl = url
                                            polled = true
                                            break
                                        }
                                        delay(500)
                                    }
                                    if (!polled) {
                                        ProviderLogger.w(TAG, "execute", "Step $index: No watching URL captured within timeout")
                                        if (step.abortOnFailure) {
                                            failedStep = index
                                            errorMsg = "No watching URL captured"
                                            break
                                        }
                                    }
                                }
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
                    
                    val isSuccess = failedStep == null && errorMsg == null
                    if (!isSuccess && webView != null) {
                        try {
                            val html = extractHtmlFromWebView(webView, null)
                            val len = html?.length ?: 0
                            val dumpKey = "failure_step_${failedStep ?: completedSteps}"
                            activityProvider()?.let { ctx ->
                                val file = java.io.File(ctx.cacheDir, "cimanow_html_${dumpKey}.html")
                                file.writeText(html.orEmpty())
                                ProviderLogger.e(TAG, "execute", "FAILURE DUMP: HTML written to ${file.absolutePath} ($len bytes)")
                                try {
                                    val dlDir = ctx.externalCacheDir ?: ctx.cacheDir
                                    dlDir.mkdirs()
                                    val dlFile = java.io.File(dlDir, "cimanow_html_${dumpKey}.html")
                                    dlFile.writeText(html.orEmpty())
                                    ProviderLogger.e(TAG, "execute", "FAILURE DUMP: HTML written to EXTCACHE ${dlFile.absolutePath} ($len bytes)")
                                } catch (e: Exception) {
                                    ProviderLogger.w("CimaNowHtmlDump", "writeHtml", "Failed to write failure dump to Downloads: ${e.message}")
                                }
                            }
                        } catch (de: Exception) {
                            ProviderLogger.w(TAG, "execute", "Failed to dump HTML on failure: ${de.message}")
                        }
                    }

                    cleanupWebView(webView, dialog)
                    result.complete(NavigationResult(
                        success = isSuccess,
                        finalUrl = currentUrl,
                        cookies = cookies,
                        extractedHtml = extractedHtml,
                        completedSteps = completedSteps,
                        failedAtStep = failedStep,
                        error = errorMsg,
                        capturedVideoUrls = capturedVideoUrls.toList(),
                        mainFrameHtml = capturedMainFrameHtml
                    ))
                }
            } catch (e: Exception) {
                if (!delivered) {
                    delivered = true
                    timeoutJob.cancel()
                    if (webView != null) {
                        try {
                            val html = extractHtmlFromWebView(webView, null)
                            val len = html?.length ?: 0
                            activityProvider()?.let { ctx ->
                                val file = java.io.File(ctx.cacheDir, "cimanow_html_failure_exception.html")
                                file.writeText(html.orEmpty())
                                ProviderLogger.e(TAG, "execute", "EXCEPTION DUMP: HTML written to ${file.absolutePath} ($len bytes)")
                                try {
                                    val dlDir = ctx.externalCacheDir ?: ctx.cacheDir
                                    dlDir.mkdirs()
                                    val dlFile = java.io.File(dlDir, "cimanow_html_failure_exception.html")
                                    dlFile.writeText(html.orEmpty())
                                    ProviderLogger.e(TAG, "execute", "EXCEPTION DUMP: HTML written to EXTCACHE ${dlFile.absolutePath} ($len bytes)")
                                } catch (e: Exception) {
                                    ProviderLogger.w("CimaNowHtmlDump", "writeHtml", "Failed to write exception dump to Downloads: ${e.message}")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    cleanupWebView(webView, dialog)
                    result.complete(NavigationResult(
                        success = false, finalUrl = currentUrl,
                        cookies = emptyMap(), extractedHtml = extractedHtml,
                        completedSteps = completedSteps,
                        failedAtStep = completedSteps, error = e.message,
                        capturedVideoUrls = capturedVideoUrls.toList()
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
                // Allow the page to open popups (e.g. the player window launched when a
                // server tab is clicked). Required so the page's JS runs fully and the
                // decrypted player iframe gets injected into the DOM.
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
            }
            // THE NUCLEAR SOLUTION: Hide the package name from ALL WebView requests natively.
            hideXRequestedWithHeader(this)
        }
    }

    private fun hideXRequestedWithHeader(webView: WebView) {
        try {
            // Approach 1: Direct method on WebView itself (newer Chrome WebViews)
            try {
                val method = WebView::class.java.getMethod("setXRequestedWithHeader", String::class.java)
                method.invoke(webView, "")
                ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via WebView.setXRequestedWithHeader method")
                return
            } catch (_: NoSuchMethodException) {}

            // Approach 2: Method on mProvider
            try {
                val providerField = WebView::class.java.getDeclaredField("mProvider")
                providerField.isAccessible = true
                val provider = providerField.get(webView)
                try {
                    val method = provider.javaClass.getMethod("setXRequestedWithHeader", String::class.java)
                    method.invoke(provider, "")
                    ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via provider.setXRequestedWithHeader method")
                    return
                } catch (_: NoSuchMethodException) {}

                // Approach 3: Field mXRequestedWithHeader on provider hierarchy
                var cls: Class<*>? = provider.javaClass
                while (cls != null) {
                    try {
                        val f = cls.getDeclaredField("mXRequestedWithHeader")
                        f.isAccessible = true
                        f.set(provider, "")
                        ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via field mXRequestedWithHeader on provider")
                        return
                    } catch (_: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }

                // Approach 4: Field xRequestedWithHeader (camelCase, no m-prefix)
                cls = provider.javaClass
                while (cls != null) {
                    try {
                        val f = cls.getDeclaredField("xRequestedWithHeader")
                        f.isAccessible = true
                        f.set(provider, "")
                        ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via field xRequestedWithHeader on provider")
                        return
                    } catch (_: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }
            } catch (e: Exception) {
                ProviderLogger.w(TAG, "hideXRequestedWithHeader", "Provider access failed: ${e.message}")
            }

            // Approach 5: Try AwContents fields through WebViewChromium
            try {
                val providerField = WebView::class.java.getDeclaredField("mProvider")
                providerField.isAccessible = true
                val provider = providerField.get(webView)
                val awContentsField = provider.javaClass.getDeclaredField("mAwContents")
                awContentsField.isAccessible = true
                val awContents = awContentsField.get(provider)
                var cls2: Class<*>? = awContents.javaClass
                while (cls2 != null) {
                    try {
                        val f = cls2.getDeclaredField("mXRequestedWithHeader")
                        f.isAccessible = true
                        f.set(awContents, "")
                        ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via AwContents.mXRequestedWithHeader")
                        return
                    } catch (_: NoSuchFieldException) {
                        cls2 = cls2.superclass
                    }
                }
            } catch (e: Exception) {
                ProviderLogger.w(TAG, "hideXRequestedWithHeader", "AwContents approach failed: ${e.message}")
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "hideXRequestedWithHeader", "Reflection failed: ${e.message}")
        }
        ProviderLogger.w(TAG, "hideXRequestedWithHeader", "All reflection approaches failed — X-Requested-With may leak — using interceptor as fallback")
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
                        autoApproveAllRedirects = false
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

                val host = request.url?.host?.lowercase() ?: ""
                val path = request.url?.path?.lowercase() ?: ""
                val reqHeaders = request.requestHeaders ?: emptyMap()
                val isMain = request.isForMainFrame

                android.util.Log.d("NavEngineNet", "shouldInterceptRequest: url=$reqUrl main=$isMain headers=${reqHeaders.entries.joinToString(", ") { "${it.key}=${it.value}" }}")

                // === COMPREHENSIVE HEADER LOG ===
                val interceptHeaderSummary = reqHeaders.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }
                ProviderLogger.d(TAG, "shouldInterceptRequest", "REQUEST DETECTED",
                    "url" to reqUrl.take(150),
                    "main" to isMain.toString(),
                    "headers" to interceptHeaderSummary)
                android.util.Log.w("NavEngineRequest", "URL: $reqUrl\nMAIN: $isMain\nHEADERS: ${reqHeaders.entries.joinToString("\n  ") { "${it.key}=${it.value}" }}")

                // === CAPTURE ACTUAL VIDEO STREAM URLS ===
                // The WebView may auto-play the embedded video (e.g. VK CDN vkuser.net).
                // Capture these signed stream URLs so they can be returned directly as links.
                val isVideoStream = host.endsWith("vkuser.net")
                        || host.endsWith("vkontakte.ru") || host.endsWith("userapi.net")
                        || path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".ts")
                        || (host.contains("okcdn.ru") && reqUrl.contains("type="))
                        || (host.contains("vkcdn") && path.contains("video"))
                if (isVideoStream) {
                    // Strip byte-range param so we keep the canonical URL (range requests are the same file)
                    val clean = reqUrl.substringBefore("&bytes=")
                    if (!capturedVideoUrls.contains(clean)) {
                        capturedVideoUrls.add(clean)
                        ProviderLogger.i(TAG, "shouldInterceptRequest", "🎬 CAPTURED VIDEO URL: ${clean.take(160)}")
                    }
                }

                // NEVER intercept Cloudflare challenge scripts — they must execute in the
                // original WebView context to properly solve the JS challenge and set cookies.
                val isCfChallenge = path.contains("/cdn-cgi/")
                val isFreeDomain = host.contains("freex2line.online")
                val isCimaDomain = host.contains("cimanow.cc")
                val isProtectedDomain = isFreeDomain || isCimaDomain

                // Detect CDN scripts loaded via document.write that Chrome may block
                // (jquery-cookie, sweetalert2, lazyload on cdnjs.cloudflare.com / cdn.jsdelivr.net)
                val requiresInterventionBypass = (host == "cdnjs.cloudflare.com" || host == "cdn.jsdelivr.net")
                    && (path.endsWith(".js") || path.endsWith(".css")) && !isCfChallenge

                // CRITICAL: Never intercept Cloudflare challenge scripts — they must execute in the
                // original WebView context to properly solve the JS challenge and set cookies.
                // Intercept main-frame for ALL protected domains (including cimanow.cc) to
                // spoof sec-ch-ua headers and hide the WebView fingerprint. Without this,
                // cimanow.cc detects sec-ch-ua="Android WebView" and redirects to home.
                if (request.isForMainFrame && (!isProtectedDomain || reqUrl.contains("/cdn-cgi/"))) return null

                // Identify requests that will leak the package name or are blocked AJAX endpoints
                val hasLeakedHeader = reqHeaders["X-Requested-With"]?.isNotBlank() == true
                val isGetLink = path.contains("get-link.php") && !isCfChallenge
                val isAjaxEndpoint = path.contains("core.php") && !isCfChallenge
                val isAsset = (path.endsWith(".js") || path.endsWith(".css")) && !isCfChallenge

                // Intercept get-link.php with spoofed headers so the page's JS gets the
                // watching URL. Also intercept assets, AJAX calls, header leaks, and
                // main-frame requests for protected domains to clean headers.
                // Also intercept CDN scripts (cdnjs.cloudflare.com, cdn.jsdelivr.net) that are
                // loaded via document.write — Chrome blocks cross-origin document.write in
                // WebView, breaking the server list extraction.
                if ((isProtectedDomain || requiresInterventionBypass) && (isGetLink || isAsset || isAjaxEndpoint || hasLeakedHeader || request.isForMainFrame)) {
                    // Explicit confirmation of the Referer the WebView sent for the watching page
                    // request. A wrong/blank Referer (e.g. about:blank) causes cimanow.cc to
                    // redirect /watching/ -> /home.
                    if (request.isForMainFrame && isCimaDomain) {
                        val webViewReferer = reqHeaders["Referer"]
                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                            "WATCHING PAGE main-frame | Referer from WebView = ${webViewReferer ?: "<none>"}")
                    }
                    try {
                        val conn = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
                        // Follow redirects internally so we get the final content from the
                        // redirect target (e.g. blog-post.html → blog-post.html/). Our spoofed
                        // sec-ch-ua headers stay on the connection through the redirect chain,
                        // so Cloudflare doesn't block the redirected request. The WebView's URL
                        // tracker stays at the original URL (no trailing slash), but our regex
                        // `blog-post\.html(/|$|\?)` matches both forms.
                        conn.instanceFollowRedirects = true

                        // Copy all headers EXCEPT X-Requested-With and the sec-ch-ua fingerprint headers
                        // (we override these below to mask that we're a WebView)
                        reqHeaders.forEach { (key, value) ->
                            if (!key.equals("X-Requested-With", true) &&
                                !key.equals("sec-ch-ua", true) &&
                                !key.equals("sec-ch-ua-mobile", true) &&
                                !key.equals("sec-ch-ua-platform", true)) {
                                conn.setRequestProperty(key, value)
                            }
                        }

                        // Explicitly send an empty string to overwrite the package name
                        conn.setRequestProperty("X-Requested-With", "")

                        // CRITICAL: Set a proper browser User-Agent — HttpURLConnection defaults to "Java/1.x"
                        conn.setRequestProperty("User-Agent", userAgent)

                        // SPOOF sec-ch-ua headers to look like a real Chrome browser, not a WebView.
                        // Cloudflare fingerprints "Android WebView" in sec-ch-ua and blocks it.
                        // Use the actual Chrome version from the User-Agent to keep headers consistent.
                        val chromeVersion = Regex("""Chrome/(\d+)""").find(userAgent)?.groupValues?.getOrNull(1) ?: "131"
                        conn.setRequestProperty("sec-ch-ua", "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$chromeVersion\", \"Chromium\";v=\"$chromeVersion\"")
                        conn.setRequestProperty("sec-ch-ua-mobile", "?1")
                        conn.setRequestProperty("sec-ch-ua-platform", "\"Android\"")

                        // Set Referer if the original request had one (anti-hotlink protection)
                        val originalReferer = reqHeaders["Referer"]
                        if (!originalReferer.isNullOrBlank()) {
                            conn.setRequestProperty("Referer", originalReferer)
                        }

                        // Standard browser accept header — use HTML accept for main-frame, */* for assets
                        if (request.isForMainFrame) {
                            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                        } else {
                            conn.setRequestProperty("Accept", "*/*")
                        }

                        // Pass the Cloudflare cookie that the main frame acquired
                        val cookies = CookieManager.getInstance().getCookie(reqUrl)
                        if (!cookies.isNullOrBlank()) {
                            conn.setRequestProperty("Cookie", cookies)
                        }
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000

                        val code = conn.responseCode

                        if (code == 200) {
                            val ct = conn.contentType ?: "application/octet-stream"
                            val reportedMime = ct.substringBefore(";").trim()
                            val encodingStr = ct.substringAfter("charset=", "utf-8").trim()
                            val charset = try { Charset.forName(encodingStr) } catch (e: Exception) { Charsets.UTF_8 }

                            // Special handling for get-link.php — capture the watching URL
                            // from the response body, then return it so the page's JS can
                            // update #downloadbtn.href and navigate the main frame.
                            if (isGetLink) {
                                val body = try { conn.inputStream.bufferedReader(charset).readText() } catch (_: Exception) { "" }
                                // Strip UTF-8 BOM (U+FEFF / EF BB BF) that some servers prepend
                                val cleanBody = body.trimStart('\uFEFF').trimStart('\u00BB').trim()
                                if (cleanBody.isNotBlank() && (cleanBody.startsWith("http://") || cleanBody.startsWith("https://"))) {
                                    interceptedWatchingUrl = cleanBody
                                    ProviderLogger.w(TAG, "shouldInterceptRequest", "✅ Captured watching URL: ${cleanBody}",
                                        "rawPrefix" to body.take(20).replace("\uFEFF", "{BOM}").replace("\u00BB", "{»}"),
                                        "length" to cleanBody.length.toString())
                                } else {
                                    ProviderLogger.w(TAG, "shouldInterceptRequest", "⚠️ get-link.php response is not a URL: ${body.take(120)}")
                                }
                                val bodyBytes = body.toByteArray(charset)
                                return WebResourceResponse("text/plain", charset.name(), 200, "OK", emptyMap(), java.io.ByteArrayInputStream(bodyBytes))
                            }

                            // Override wrong MIME types — server may return text/html for JS/CSS
                            // to block scrapers. Force correct type based on file extension.
                            val mime = when {
                                reportedMime == "text/html" && path.endsWith(".js") -> "application/javascript"
                                reportedMime == "text/html" && path.endsWith(".css") -> "text/css"
                                reportedMime == "text/html" && path.endsWith(".json") -> "application/json"
                                reportedMime == "text/html" && path.endsWith(".svg") -> "image/svg+xml"
                                reportedMime == "text/html" && path.endsWith(".woff2") -> "font/woff2"
                                reportedMime == "text/html" && path.endsWith(".woff") -> "font/woff"
                                reportedMime == "text/html" && path.endsWith(".png") -> "image/png"
                                reportedMime == "text/html" && path.endsWith(".jpg") -> "image/jpeg"
                                reportedMime == "text/html" && path.endsWith(".jpeg") -> "image/jpeg"
                                reportedMime == "text/html" && path.endsWith(".gif") -> "image/gif"
                                reportedMime == "text/html" && path.endsWith(".webp") -> "image/webp"
                                else -> reportedMime
                            }

                            val mimeLog = if (mime != reportedMime) "$reportedMime -> $mime" else mime
                            ProviderLogger.d(TAG, "shouldInterceptRequest", "INTERCEPTED ${reqUrl.take(80)} ($mimeLog)")

                            // Detect CDN scripts that were previously loaded via document.write
                            if (requiresInterventionBypass) {
                                ProviderLogger.w(TAG, "shouldInterceptRequest", "CDN bypass: ${reqUrl.take(80)}")
                            }

                            // Rewrite HTML for cimanow.cc main-frame: replace document.write('<script src="...")>
                            // and document.write('<link rel="stylesheet" href="...")> with direct tags
                            // to bypass Chrome's cross-origin document.write intervention
                            // (which blocks sweetalert2, jquery-cookie, lazyload).
                            val bodyStream: java.io.InputStream = if (request.isForMainFrame && isCimaDomain && mime == "text/html") {
                                val html = conn.inputStream.bufferedReader(charset).readText()
                                // Profi #5: Capture the raw server-rendered HTML before any
                                // anti-bot JS can clear/patch it. This is parsed in Kotlin
                                // after execute() completes, bypassing the WebView JS entirely.
                                this@NavigationEngine.capturedMainFrameHtml = html
                                val scriptCount = Regex("""document\.write\s*\(\s*'<script[^>]*src=["']([^"']+)["'][^>]*><\\/script>\s*'\s*\)""").findAll(html).count()
                                val linkCount = Regex("""document\.write\s*\(\s*'<link[^>]*href=["']([^"']+)["'][^>]*>\s*'\s*\)""").findAll(html).count()
                                var rewritten = html.replace(
                                    Regex("""document\.write\s*\(\s*'<script[^>]*src=["']([^"']+)["'][^>]*><\\/script>\s*'\s*\)"""),
                                    """<script src="$1"></script>"""
                                )
                                rewritten = rewritten.replace(
                                    Regex("""document\.write\s*\(\s*'<link[^>]*href=["']([^"']+)["'][^>]*>\s*'\s*\)"""),
                                    """<link rel="stylesheet" href="$1">"""
                                )
                                // Inject document.write interceptor at the start of <head> so it runs
                                // before the page's own JS (including the decryption/anti-bot scripts).
                                // Captures the decrypted HTML string BEFORE the anti-bot strips it.
                                // Must be done AFTER document.write rewriting (above) to keep injection clean.
                                val antiBotTag = "<script>$ANTI_ANTI_BOT_JS</script>"
                                val injected = if (rewritten.contains("<head>")) {
                                    rewritten.replaceFirst("<head>", "<head>$antiBotTag")
                                } else {
                                    // No <head> tag — prepend so the browser still parses it first
                                    "$antiBotTag$rewritten"
                                }
                                ProviderLogger.w(TAG, "shouldInterceptRequest",
                                    "Injected document.write interceptor for cimanow.cc main-frame (${antiBotTag.length} chars)")
                                val total = scriptCount + linkCount
                                val countLog = if (total > 0) " (rewrote $total document.write calls)" else " (no document.write found)"
                                ProviderLogger.d(TAG, "shouldInterceptRequest", "HTML ${html.length} chars for cimanow.cc main-frame$countLog")
                                java.io.ByteArrayInputStream(injected.toByteArray(charset))
                            } else {
                                conn.inputStream
                            }
                            return WebResourceResponse(mime, charset.name(), bodyStream)
                        } else {
                            ProviderLogger.w(TAG, "shouldInterceptRequest", "Intercept non-200 ($code) for ${reqUrl.take(80)}")
                            return null
                        }
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG, "shouldInterceptRequest", "Intercept failed: ${e.message}")
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
                val method = try { request?.method ?: "GET" } catch (_: Exception) { "GET" }
                val reqHeaders = request.requestHeaders ?: emptyMap()

                // === USER-APPROVED REDIRECT CHECK ===
                val pending = this@NavigationEngine.pendingRedirectUrl
                if (pending != null && pending == nextUrl) {
                    this@NavigationEngine.pendingRedirectUrl = null
                    ProviderLogger.i(TAG, "shouldOverrideUrlLoading", "USER APPROVED REDIRECT",
                        "url" to nextUrl.take(120), "host" to nextHost, "mainFrame" to isMainFrame.toString())
                    return false
                }

                // === AUTO-APPROVE ALL REDIRECTS DURING WATCHING PHASE ===
                // The watching URL goes through dynamic ad domains (viiqkzqv.com, etc.)
                // that change per session. We must allow all redirects to reach the video.
                if (autoApproveAllRedirects && isMainFrame) {
                    ProviderLogger.i(TAG, "shouldOverrideUrlLoading", "AUTO-APPROVED (watching phase)",
                        "url" to nextUrl.take(120), "host" to nextHost)
                    // Stop auto-approving once we return to freex2line.online (the video player page)
                    if (nextHost.contains("freex2line.online") && nextUrl.contains("/pig/watching/")) {
                        autoApproveAllRedirects = false
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "🎬 Reached video player on freex2line",
                            "url" to nextUrl.take(120))
                    }
                    return false
                }

                // === COMPREHENSIVE HEADER/REQUEST LOG ===
                val headerSummary = reqHeaders.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }
                ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "== REDIRECT DETECTED ==",
                    "url" to nextUrl.take(150),
                    "host" to nextHost,
                    "mainFrame" to isMainFrame.toString(),
                    "method" to method,
                    "headers" to headerSummary)
                android.util.Log.d("NavEngineNet", "shouldOverrideUrlLoading: nextUrl=$nextUrl host=$nextHost main=$isMainFrame method=$method headers=$headerSummary")
                android.util.Log.w("NavEngineRedirect", "URL: $nextUrl\nHOST: $nextHost\nMAIN: $isMainFrame\nMETHOD: $method\nHEADERS: $headerSummary")

                if (isMainFrame && isOnDestination) {
                    ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "DESTINATION LOCK BLOCK", "url" to nextUrl, "host" to nextHost)
                    return true
                }

                var isBlockedByDomain = false
                if (allowedDomains.isNotEmpty()) {
                    val allowed = allowedDomains.any { allowedDomain ->
                        nextHost == allowedDomain || nextHost.endsWith(".$allowedDomain")
                    }
                    if (!allowed) {
                        isBlockedByDomain = true
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "DOMAIN BLOCK", "url" to nextUrl, "host" to nextHost, "allowed" to allowedDomains.joinToString(","))
                    }
                }

                // === REDIRECT CONFIRMATION DIALOG (main-frame only) ===
                if (isMainFrame) {
                    val activity = activityProvider()
                    if (activity != null) {
                        val hostInfo = nextHost.ifBlank { "unknown" }
                        val methodInfo = method.ifBlank { "GET" }
                        val blockedInfo = if (isBlockedByDomain) "\n\n⚠ Domain would be blocked by policy!" else ""
                        val destInfo = if (isOnDestination) "\n\n🔒 Destination lock active!" else ""
                        val headerInfo = reqHeaders.entries.joinToString("\n") { "  ${it.key}: ${it.value.take(100)}" }

                        android.app.AlertDialog.Builder(activity)
                            .setTitle("🔄 Redirect Confirmation")
                            .setMessage(
                                "Target URL:\n$nextUrl\n\n" +
                                "Host: $hostInfo\n" +
                                "Method: $methodInfo\n" +
                                "Main Frame: $isMainFrame\n" +
                                "$blockedInfo$destInfo\n\n" +
                                "--- Request Headers ---\n$headerInfo"
                            )
                            .setPositiveButton("✅ Allow") { _, _ ->
                                this@NavigationEngine.pendingRedirectUrl = nextUrl
                                view?.post { view?.loadUrl(nextUrl) }
                                ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "USER ALLOWED REDIRECT", "url" to nextUrl.take(120))
                            }
                            .setNegativeButton("❌ Block") { _, _ ->
                                ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "USER BLOCKED REDIRECT", "url" to nextUrl.take(120))
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "No Activity available for redirect confirmation dialog", "url" to nextUrl.take(120))
                    }
                    return true
                }

                ProviderLogger.i(TAG, "shouldOverrideUrlLoading", "ALLOWED (sub-frame)", "url" to nextUrl.take(120), "host" to nextHost, "mainFrame" to isMainFrame.toString())
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

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Allow the page to open popups (e.g. the player window launched when a
                // server tab is clicked) so its JS runs fully and the decrypted player
                // iframe gets injected into the main-frame DOM. We do NOT follow/capture
                // the popup — the content we need is already in the main-frame HTML.
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
        timeoutMs: Long,
        expectedUrl: String = ""
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val expectedHost = try { java.net.URI(expectedUrl).host?.lowercase() } catch(_: Exception) { null }

        while (System.currentTimeMillis() < deadline) {
            if (expectedHost != null) {
                val currentWebviewUrl = withContext(Dispatchers.Main) { webView.url ?: "" }
                val currentHost = try { java.net.URI(currentWebviewUrl).host?.lowercase() } catch(_: Exception) { null }
                if (currentHost != null && currentHost != expectedHost) {
                    ProviderLogger.i(TAG, "clickElement", "URL host changed from $expectedHost to $currentHost. Breaking early.")
                    return false
                }
            }

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
        timeoutMs: Long,
        expectedUrl: String = ""
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pollCount = 0
        val expectedHost = try { java.net.URI(expectedUrl).host?.lowercase() } catch(_: Exception) { null }

        while (System.currentTimeMillis() < deadline) {
            pollCount++

            if (expectedHost != null) {
                val currentWebviewUrl = withContext(Dispatchers.Main) { webView.url ?: "" }
                val currentHost = try { java.net.URI(currentWebviewUrl).host?.lowercase() } catch(_: Exception) { null }
                if (currentHost != null && currentHost != expectedHost) {
                    ProviderLogger.i(TAG, "waitForSelector", "URL host changed from $expectedHost to $currentHost. Breaking early.")
                    return false
                }
            }

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

    /**
     * Atomically polls a DOM condition in the same evaluateJavascript that captures the
     * snapshot — eliminating the race window between "condition met" and "read innerHTML"
     * that anti-bot scripts (like cimanow's 0CYA6X1KhKIS.js) exploit.
     *
     * The [snapshotJs] should return a non-empty string when the condition is met,
     * or an empty/false string when the condition has not yet been satisfied.
     *
     * @return the snapshot string when condition was met, or null on timeout/error.
     */
    private suspend fun waitForDomConditionAndSnapshot(
        webView: WebView,
        jsCondition: String,
        snapshotJs: String,
        timeoutMs: Long,
        pollIntervalMs: Long
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            val combinedJs = """
                (function(){
                    try {
                        if (!($jsCondition)) { return ''; }
                        $snapshotJs
                    } catch(e) { return 'raw_html_error:' + e.message; }
                })();
            """.trimIndent()
            val result = executeJsInWebView(webView, combinedJs)
            val snapshot = result ?: ""
            ProviderLogger.d(TAG, "waitForDomConditionAndSnapshot",
                "poll#$pollCount condition=${jsCondition.take(60)} snapshot=${snapshot.take(100)} remaining=${deadline - System.currentTimeMillis()}ms")
            if (snapshot.isNotBlank() && !snapshot.startsWith("raw_html_error:")) {
                ProviderLogger.i(TAG, "waitForDomConditionAndSnapshot", "MET after ${pollCount} polls, snapshot ${snapshot.length} chars")
                return snapshot
            }
            delay(pollIntervalMs)
        }
        ProviderLogger.w(TAG, "waitForDomConditionAndSnapshot", "TIMEOUT after ${pollCount} polls")
        return null
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

    /**
     * Creates a sandboxed WebView (no network), loads about:blank, and executes JavaScript.
     * Returns the result of the JS execution. The WebView is destroyed after execution.
     * Safe to call from any coroutine context.
     */
    suspend fun executeJsSandbox(javascript: String): String? {
        val activity = activityProvider()?.let {
            if (it.isFinishing) null else it
        } ?: run {
            ProviderLogger.e(TAG, "executeJsSandbox", "No activity available")
            return null
        }
        val webView = createWebView(activity, "")
        try {
            webView.settings.blockNetworkLoads = true
            Handler(Looper.getMainLooper()).post {
                webView.loadUrl("about:blank")
            }
            // Ensure about:blank is loaded before evaluating JS
            delay(100)
            return executeJsInWebView(webView, "(function() { $javascript })();")
        } finally {
            Handler(Looper.getMainLooper()).post {
                try {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.removeAllViews()
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView.destroy()
                } catch (_: Exception) {}
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

        /**
         * Anti-anti-bot script injected at the top of <head> for cimanow.cc.
         * Runs before the page's own JS (including the anti-bot) and makes critical
         * prototype properties immutable, preventing the anti-bot from overriding
         * them to strip/clear server entries from the DOM.
         *
         * Protects:
         *   - DOMParser.prototype.parseFromString  (anti-bot strips <li> from parsed docs)
         *   - Element.prototype.querySelectorAll   (anti-bot returns empty for #watch queries)
         *   - Element.prototype.setAttribute       (anti-bot intercepts data-index writes)
         *   - Element.prototype.getAttribute       (anti-bot hides data-index reads)
         *   - Element.prototype.remove             (anti-bot removes server LIs)
         *   - HTMLElement.prototype.innerHTML      (anti-bot clears #watch.innerHTML)
         */
        /**
         * Anti-anti-bot script injected at the top of <head> for cimanow.cc.
         * Runs before the page's own JS and uses a THREE-STRATEGY approach:
         *
         * (A) DOMParser.prototype.parseFromString – getter/setter trap.
         *     The anti-bot script (3578-line inline block) contains BOTH the
         *     DOMParser override AND the legitimate decryption code in the same
         *     script. Freezing the property would crash the script (TypeError)
         *     and prevent the decryption from ever running.
         *     Instead, we install a getter that ALWAYS returns the original
         *     parseFromString, and a setter that silently discards any
         *     override attempt. The anti-bot assignment "succeeds" (no error),
         *     the script continues, and the legitimate code gets the real
         *     parseFromString that preserves <li> elements.
         *
         * (B) Element.prototype (querySelectorAll, setAttribute, getAttribute) – frozen.
         *     The anti-bot overrides these in separate scripts or in the external
         *     0CYA6X1KhKIS.js file. Freezing them prevents subsequent overrides.
         *     These are safe to freeze because they're in DIFFERENT script tags
         *     from the DOMParser trap (no crash cascade).
         *
         * (C) Element.prototype.remove + innerHTML – hooked.
         *     The anti-bot's MutationObserver walks the DOM and calls .remove()
         *     on LI[data-index] elements and sets #watch.innerHTML=''. These
         *     hooks block those cleanup actions, preserving the server entries
         *     in the DOM for the WaitForDomConditionAndSnapshot poll.
         */
        /**
         * Intercepts document.write to capture the decrypted page HTML (which contains
         * <li data-index=".." data-id=".."> server entries) BEFORE the anti-bot
         * generated script can strip them. The captured HTML is stored in
         * window.__decryptedHtml and consumed by the provider's snapshot step.
         */
        private val ANTI_ANTI_BOT_JS = """
            (function(){
                try {
                    var _origWrite = document.write.bind(document);
                    var _captured = false;
                    document.write = function(html) {
                        if (!_captured && html && typeof html === 'string' && html.length > 500) {
                            _captured = true;
                            window.__decryptedHtml = html;
                            console.log('[CW] Captured decrypted HTML: ' + html.length + ' chars');
                            console.log('[CW] Has data-index: ' + (html.indexOf('data-index') !== -1));
                            console.log('[CW] Has li tag: ' + (html.indexOf('<li') !== -1));
                        }
                        return _origWrite(html);
                    };
                    // Spoof native function signature — the decryption script checks
                    // document.write.toString().indexOf('[native code]') and bails if false.
                    document.write.toString = function() { return 'function write() { [native code] }'; };
                    try { Object.defineProperty(document.write, 'name', { value: 'write', configurable: true }); } catch(e) {}
                    try { Object.defineProperty(document.write, 'length', { value: 1, configurable: true }); } catch(e) {}
                    console.log('[CW] document.write hook active');
                } catch(e) {
                    console.error('[CW] document.write hook failed: ' + e.message);
                }
                // Backup: block LI.remove in case the above doesn't capture (e.g.
                // if the page uses innerHTML instead of document.write).
                try {
                    var _remove = Element.prototype.remove;
                    Element.prototype.remove = function() {
                        if (this.tagName === 'LI' && (this.hasAttribute('data-index') || this.hasAttribute('data-id'))) {
                            console.log('[CW] Blocked LI remove (backup)');
                            return;
                        }
                        return _remove.call(this);
                    };
                    Object.defineProperty(Element.prototype, 'remove', {
                        configurable: false, writable: false, value: Element.prototype.remove
                    });
                } catch(e) {}
            })();
        """.trimIndent()

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