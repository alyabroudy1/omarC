package com.cloudstream.shared.webview

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import com.cloudstream.shared.cloudflare.CloudflareDetector
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_WEBVIEW
import kotlinx.coroutines.*

/**
 * Unified WebView engine for CF bypass and video sniffing.
 * 
 * DECOUPLED FROM STATE: This engine does NOT store cookies.
 * It returns cookies in WebViewResult.Success, and the caller
 * (ProviderHttpService) is responsible for updating SessionState.
 */
class WebViewEngine(
    private val activityProvider: () -> android.app.Activity?
) {
    // Instance variables to share state with helper methods
    private var deferred: CompletableDeferred<WebViewResult>? = null
    private var resultDelivered = false
    private var timeoutJob: Job? = null
    private var videoMonitorJob: Job? = null
    
    enum class Mode {
        HEADLESS,    // No UI, runs in background
        FULLSCREEN   // User-visible dialog for CAPTCHA
    }
    
    /**
     * Run a WebView session and return the result.
     * 
     * @param preSniffJavaScript Optional JavaScript to execute after page load but before video sniffing.
     *        Useful for clicking server buttons or other interactions needed before player loads.
     * @param referer Optional referer URL to send with requests (e.g., https://laroza.cfd/)
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
            ProviderLogger.e(TAG_WEBVIEW, "runSession", "No Activity available")
            return@withContext WebViewResult.Error("No Activity context")
        }
        
        this@WebViewEngine.deferred = CompletableDeferred<WebViewResult>()
        val deferred = this@WebViewEngine.deferred!!
        this@WebViewEngine.exitConditionReference = exitCondition
        resultDelivered = false
        var dialog: Dialog? = null
        var webView: WebView? = null
        
        // BUGFIX: Clear capturedLinks at the start of each session
        capturedLinks.clear()
        android.util.Log.d("WebViewEngine", "runSession: Session started, capturedLinks cleared. URL: $url")
        ProviderLogger.d(TAG_WEBVIEW, "runSession", "Session started, capturedLinks cleared")
        
        // Timeout handler
        this@WebViewEngine.timeoutJob = CoroutineScope(Dispatchers.Main).launch {
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
                    ProviderLogger.d(TAG_WEBVIEW, "runSession", "Timeout with ${foundLinks.size} captured links")
                    // extractCookies is suspend, need webView
                    val cookies = webView?.let { extractCookies(it, url) } ?: emptyMap()
                    deferred.complete(WebViewResult.Success(cookies, "", url, foundLinks))
                } else {
                    deferred.complete(WebViewResult.Timeout(url, partialHtml))
                }
            }
        }
        
        // BUGFIX: Proactive video monitoring job - checks every 300ms for captured videos
        this@WebViewEngine.videoMonitorJob = if (exitCondition is ExitCondition.VideoFound) {
            CoroutineScope(Dispatchers.Main).launch {
                val requiredCount = (exitCondition as ExitCondition.VideoFound).minCount
                android.util.Log.d("WebViewEngine", "videoMonitorJob: Started. requiredCount=$requiredCount")
                ProviderLogger.d(TAG_WEBVIEW, "runSession", "Video monitor started", "requiredCount" to requiredCount)
                while (!resultDelivered) {
                    delay(300)
                    if (capturedLinks.size >= requiredCount) {
                        // BUGFIX: Add delay to ensure all headers/cookies are captured
                        ProviderLogger.d(TAG_WEBVIEW, "runSession", "Videos found, waiting for headers to sync...")
                        delay(500)  // Give time for headers to be captured
                        
                        checkExitCondition() // Trigger exit via helper
                        
                        // Fallback if checkExitCondition didn't trigger for some reason
                        if (!resultDelivered) {
                             resultDelivered = true
                             timeoutJob?.cancel()
                             
                             // extractCookies is now suspend and needs the webView instance
                             val cookies = webView?.let { extractCookies(it, url) } ?: emptyMap()
                             val foundLinks = capturedLinks.toList()
                             android.util.Log.i("WebViewEngine", "[videoMonitorJob] FALLBACK EXIT with ${foundLinks.size} links")
                             android.util.Log.i("WebViewEngine", "[videoMonitorJob] First link: ${foundLinks.firstOrNull()?.url?.take(100)}")
                             ProviderLogger.d(TAG_WEBVIEW, "runSession", "Video monitor forced exit")
                             cleanup(webView, dialog)
                             android.util.Log.i("WebViewEngine", "[videoMonitorJob] Completing deferred with ${foundLinks.size} links")
                             deferred.complete(WebViewResult.Success(cookies, "", url, foundLinks))
                             android.util.Log.i("WebViewEngine", "[videoMonitorJob] Deferred completed!")
                        }
                        break
                    }
                }
            }
        } else null
        
        try {
            ProviderLogger.i(TAG_WEBVIEW, "runSession", "=== STEP 1: Creating WebView ===", "url" to url.take(80))
            
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
            this@WebViewEngine.activeWebView = webView
            
            // UA VERIFICATION: Log the exact UA WebView is using
            ProviderLogger.i(TAG_WEBVIEW, "runSession", "WebView UA",
                "ua" to webView.settings.userAgentString)
            
            // Setup cookies
            ProviderLogger.i(TAG_WEBVIEW, "runSession", "=== STEP 2: Setting up cookies ===")
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            
            // Setup based on mode
            ProviderLogger.i(TAG_WEBVIEW, "runSession", "=== STEP 3: Setting up mode ===", "mode" to mode.name)
            when (mode) {
                Mode.HEADLESS -> {
                    ProviderLogger.d(TAG_WEBVIEW, "runSession", "HEADLESS mode", "url" to url.take(80))
                }
                Mode.FULLSCREEN -> {
                    dialog = createDialog(activity, webView)
                    this@WebViewEngine.activeDialog = dialog
                    dialog.show()
                    ProviderLogger.d(TAG_WEBVIEW, "runSession", "FULLSCREEN mode", "url" to url.take(80))
                }
            }
            
            
            // Add JS Interface here to support all modes
            webView.addJavascriptInterface(SnifferBridge(), "SnifferBridge")

            // Setup WebViewClient
            webView.webViewClient = object : WebViewClient() {
                private var requestCounter = 0
                
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    if (resultDelivered) return null
                    val url = request?.url?.toString()
                    requestCounter++
                    
                    if (url != null) {
                        // Log ALL requests for debugging
                        if (requestCounter % 10 == 0 || url.contains(".m3u8") || url.contains(".mp4") || url.contains("video") || url.contains("stream")) {
                            android.util.Log.d("WebViewEngine", "intercept: Request #$requestCounter url=${url.take(100)}")
                            ProviderLogger.d(TAG_WEBVIEW, "intercept", "Request #$requestCounter", 
                                "url" to url.take(100),
                                "method" to (request?.method ?: "?"),
                                "isMainFrame" to (request?.isForMainFrame ?: false)
                            )
                        }
                        
                        // Check if it's a video URL with detailed logging
                        if (isVideoUrl(url)) {
                             ProviderLogger.i(TAG_WEBVIEW, "intercept", "VIDEO URL DETECTED!", 
                                 "url" to url.take(100),
                                 "requestNum" to requestCounter
                             )
                             captureLink(url, "Network", request?.requestHeaders ?: emptyMap())
                        } else if (url.contains("m3u8") || url.contains("mp4") || url.contains("video") || url.contains("stream")) {
                            // Log why it was rejected
                            ProviderLogger.w(TAG_WEBVIEW, "intercept", "URL rejected", 
                                "url" to url.take(100),
                                "reason" to "Failed video pattern check",
                                "isBlacklisted" to isBlacklisted(url)
                            )
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val nextUrl = request?.url?.toString()
                    ProviderLogger.d(TAG_WEBVIEW, "shouldOverrideUrlLoading", "Redirect", "url" to nextUrl?.take(80))
                    return super.shouldOverrideUrlLoading(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (resultDelivered) return
                    android.util.Log.i("WebViewEngine", "onPageStarted: url=${url?.take(80)}")
                    ProviderLogger.i(TAG_WEBVIEW, "onPageStarted", "=== PAGE STARTED ===", "url" to url?.take(80))
                    
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
                    
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (resultDelivered) return
                    val currentUrl = view?.url ?: loadedUrl ?: url
                    android.util.Log.i("WebViewEngine", "onPageFinished: url=${currentUrl.take(80)}")
                    ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "=== PAGE FINISHED ===", "url" to currentUrl.take(80))

                    // Inject VideoSniffer JS & Start DOM extraction ONLY if we are looking for video
                    if (exitCondition is ExitCondition.VideoFound) {
                        ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "=== STEP 4: Pre-sniff JavaScript ===")
                        
                        // Execute pre-sniff JavaScript if provided (e.g., click server button)
                        if (!preSniffJavaScript.isNullOrBlank()) {
                            android.util.Log.i("WebViewEngine", "onPageFinished: Executing pre-sniff JavaScript")
                            ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "Executing pre-sniff JavaScript", "jsLength" to preSniffJavaScript.length)
                            view?.evaluateJavascript(preSniffJavaScript) { result ->
                                android.util.Log.i("WebViewEngine", "onPageFinished: Pre-sniff JS result: $result")
                                ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "Pre-sniff JavaScript result", "result" to (result ?: "null"))
                            }
                            
                            // Wait for player to load after clicking, then inject video sniffer
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(3000) // Give 3s for player to initialize after click
                                ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "=== STEP 5: Injecting Video Sniffer (after pre-sniff) ===")
                                view?.evaluateJavascript(com.cloudstream.shared.strategy.VideoSniffingStrategy.JS_SCRIPT) { result ->
                                    ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "Video sniffer injection result", "result" to (result ?: "null"))
                                }
                                ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "=== STEP 6: Starting DOM extraction ===")
                                startDomVideoExtraction(view)
                            }
                        } else {
                            // No pre-sniff JS, inject video sniffer immediately
                            ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "=== STEP 5: Injecting Video Sniffer ===")
                            view?.evaluateJavascript(com.cloudstream.shared.strategy.VideoSniffingStrategy.JS_SCRIPT) { result ->
                                ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "Video sniffer injection result", "result" to (result ?: "null"))
                            }
                            ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "=== STEP 6: Starting DOM extraction ===")
                            startDomVideoExtraction(view)
                        }
                    } else {
                        ProviderLogger.d(TAG_WEBVIEW, "onPageFinished", "Skipping sniffer injection (Not in VideoFound mode)")
                    }

                    if (resultDelivered) {
                        ProviderLogger.w(TAG_WEBVIEW, "onPageFinished", "Result already delivered, skipping")
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
                                is ExitCondition.PageLoaded -> {
                                    val isCf = CloudflareDetector.isCloudflareChallenge(html)
                                    if (isCf) ProviderLogger.d(TAG_WEBVIEW, "onPageFinished", "Still in CF challenge")
                                    !isCf
                                }
                                is ExitCondition.CookiesPresent -> {
                                    val cookies = extractCookies(view, currentUrl)
                                    exitCondition.keys.all { key -> cookies.containsKey(key) }
                                }
                                is ExitCondition.VideoFound -> {
                                    val count = capturedLinks.size
                                    if (count >= exitCondition.minCount) true else false
                                }
                            }
                            
                            if (shouldExit) {
                                resultDelivered = true
                                timeoutJob?.cancel()
                                videoMonitorJob?.cancel()
                                
                                val cookies = extractCookies(view, currentUrl)
                                val found = capturedLinks.toList()
                                android.util.Log.i("WebViewEngine", "[onPageFinished] EXITING! Sending ${found.size} links to deferred.")
                                found.forEach { android.util.Log.d("WebViewEngine", " > Link: ${it.url}") }
                                
                                cleanup(view, dialog)
                                deferred.complete(WebViewResult.Success(cookies, html, currentUrl, found))
                                android.util.Log.i("WebViewEngine", "[onPageFinished] Deferred completed.")
                            }
                        } catch (e: Exception) {
                            ProviderLogger.e(TAG_WEBVIEW, "onPageFinished", "Error", e)
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
                        ProviderLogger.w(TAG_WEBVIEW, "onReceivedError", "WebView error",
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
                        ProviderLogger.d(TAG_WEBVIEW, "WebView", "Loading progress", "percent" to newProgress)
                    }
                }
                
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    val newWebView = WebView(view?.context ?: return false)
                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString()
                            if (!url.isNullOrBlank()) {
                                ProviderLogger.d(TAG_WEBVIEW, "onCreateWindow", "Redirecting popup to main WebView", "url" to url)
                                webView.loadUrl(url)
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
            ProviderLogger.i(TAG_WEBVIEW, "runSession", "=== STEP 6: Loading URL ===", "url" to url.take(80))
            
            val extraHeaders = mutableMapOf<String, String>()
            extraHeaders["X-Requested-With"] = ""
            
            // Add referer if provided (critical for embed servers like qq.okprime.site)
            if (!referer.isNullOrBlank()) {
                extraHeaders["Referer"] = referer
                ProviderLogger.i(TAG_WEBVIEW, "runSession", "Added Referer header", "referer" to referer)
            }
            
            ProviderLogger.i(TAG_WEBVIEW, "runSession", "Loading URL with headers", 
                "url" to url.take(80),
                "headers" to extraHeaders.keys.joinToString(",")
            )
            
            webView.loadUrl(url, extraHeaders)
            
            ProviderLogger.i(TAG_WEBVIEW, "runSession", "=== STEP 7: URL loaded, waiting for callbacks ===")
            
        } catch (e: Exception) {
            resultDelivered = true
            timeoutJob?.cancel()
            videoMonitorJob?.cancel()
            cleanup(webView, dialog)
            deferred.complete(WebViewResult.Error(e.message ?: "Unknown error"))
        }
        
        deferred.await()
    }
    
    private fun isVideoUrl(url: String): Boolean {
        if (isBlacklisted(url)) {
            ProviderLogger.d(TAG_WEBVIEW, "isVideoUrl", "URL blacklisted", "url" to url.take(80))
            return false
        }
        
        // Check for BLOB URLs (WebRTC/MediaSource streams)
        if (url.startsWith("blob:")) {
            ProviderLogger.i(TAG_WEBVIEW, "isVideoUrl", "BLOB URL detected", "url" to url.take(80))
            return true
        }
        
        // Check for video patterns
        val hasM3u8 = url.contains(".m3u8", ignoreCase = true)
        val hasMp4 = url.contains(".mp4", ignoreCase = true)
        val hasMkv = url.contains(".mkv", ignoreCase = true)
        // val hasUrls = url.contains(".urls", ignoreCase = true) || url.contains(".urlset", ignoreCase = true) // Too broad?
        val hasMaster = url.contains("/master.m3u8", ignoreCase = true)
        val hasWebm = url.contains(".webm", ignoreCase = true)
        
        // Explicitly reject segments if they don't look like master playlists
        if (url.contains(".ts", ignoreCase = true) || 
            url.contains(".key", ignoreCase = true) || 
            url.contains(".png", ignoreCase = true) || 
            url.contains(".jpg", ignoreCase = true) || 
            url.contains("favicon")) {
            return false
        }
        
        val isVideo = hasM3u8 || hasMp4 || hasMkv || hasMaster || hasWebm
        
        if (!isVideo && (url.contains("video") || url.contains("stream") || url.contains("media"))) {
            ProviderLogger.d(TAG_WEBVIEW, "isVideoUrl", "URL looks like video but pattern not matched",
                "url" to url.take(80),
                "checks" to "m3u8=$hasM3u8, mp4=$hasMp4, mkv=$hasMkv"
            )
        }
        
        return isVideo
    }

    private fun isBlacklisted(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("/ping.gif") || 
               lowerUrl.contains("/analytics") || 
               lowerUrl.contains("/google-analytics") || 
               lowerUrl.contains("favicon.ico")
    }

    private val capturedLinks = java.util.concurrent.CopyOnWriteArrayList<CapturedLinkData>()
    private var firstLinkTime: Long = 0L
    private val SMART_WAIT_TIME_MS = 2500L

    private fun captureLink(url: String, qualityLabel: String, headers: Map<String, String>) {
         // Logic to store captured link
         val data = CapturedLinkData(url, qualityLabel, headers)

         // Filter out segment files and non-video assets
         if (url.contains(".ts") || url.contains(".key") || url.contains(".png") || 
             url.contains(".jpg") || url.contains(".gif") || url.contains(".css") || 
             url.contains(".js") || url.contains("favicon")) {
             android.util.Log.d("WebViewEngine", "[captureLink] Ignored segment/asset link | url=${url.take(80)}")
             return
         }

         if (capturedLinks.none { it.url == url }) {
             capturedLinks.add(data)
             if (firstLinkTime == 0L) firstLinkTime = System.currentTimeMillis()
             
             android.util.Log.i("WebViewEngine", "[captureLink] LINK CAPTURED #$capturedLinks.size | url=${url.take(80)}")
             ProviderLogger.i(TAG_WEBVIEW, "captureLink", "LINK CAPTURED SUCCESSFULLY!", 
                  "url" to url.take(100),
                  "quality" to qualityLabel,
                  "totalLinks" to capturedLinks.size
             )
             
             // If we found a master m3u8 or blob, we might want to finish early or shortly
             if (url.contains("master.m3u8") || url.startsWith("blob:")) {
                  android.util.Log.i("WebViewEngine", "[captureLink] High confidence link found, suggesting exit.")
             }

             // Update UI and Check Exit
             CoroutineScope(Dispatchers.Main).launch {
                 updateDialogText("Found ${capturedLinks.size} video stream(s)...")
                 android.util.Log.i("WebViewEngine", "[captureLink] Updated UI: Found ${capturedLinks.size} video stream(s)")
                 // Trigger exit check immediately
                 checkExitCondition()
             }
         } else {
             android.util.Log.w("WebViewEngine", "[captureLink] Duplicate URL captured (This is expected if page reloads or loops) | url=${url.take(80)}")
         }
    }
    
    // Instance accessible exit check
    private fun checkExitCondition() {
        if (resultDelivered) return
        
        // We only auto-exit for VideoFound condition from here
        // Other conditions are checked in onPageFinished
        val currentExitCondition = exitConditionReference ?: return
        
        if (currentExitCondition is ExitCondition.VideoFound) {
            val count = capturedLinks.size
            if (count >= currentExitCondition.minCount) {
                ProviderLogger.i(TAG_WEBVIEW, "checkExitCondition", "Exit condition met!", "count" to count)
                
                
                // === SMART EXIT STRATEGY ===
                // Check if we should wait for a Master M3U8
                val hasMaster = capturedLinks.any { it.url.contains("master.m3u8", ignoreCase = true) }
                val timeSinceFirst = System.currentTimeMillis() - firstLinkTime
                
                if (!hasMaster && timeSinceFirst < SMART_WAIT_TIME_MS) {
                     ProviderLogger.d(TAG_WEBVIEW, "checkExitCondition", "Soft waiting for Master M3U8...", 
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
                    
                    android.util.Log.i("WebViewEngine", "[checkExitCondition] EXITING! Sending ${found.size} links to deferred.")
                    found.forEach { android.util.Log.d("WebViewEngine", " > Link: ${it.url}") }
                    
                    // Cleanup AFTER extraction but before completion (or let deferred complete first?)
                    // Cleanup destroys WebView, so we must extract first.
                    
                    ProviderLogger.i(TAG_WEBVIEW, "checkExitCondition", "Cleaning up UI before exit")
                    cleanup(activeWebView, activeDialog)
                    
                    deferred?.complete(WebViewResult.Success(cookies, "", "", found))
                    android.util.Log.i("WebViewEngine", "[checkExitCondition] Deferred completed.")
                }
                // We cannot access them easily unless we make them instance variables or pass them.
                // BUT runSession waits for deferred.complete.
                // So if we complete deferred, runSession continues? 
                // No, runSession IS waiting. 
                
                // WAIT: If we complete deferred here, runSession's cleanup might not happen if it's AFTER await?
                // runSession code:
                // deferred.await()
                // So cleanup happens inside the callbacks/jobs before complete.
                
                // We need to trigger the completion which allows runSession to return.
                // But we should cleanup first.
                // Since we can't access webView/dialog here easily without major Refactor 2.0...
                // Strategy: We will just complete deferred. 
                // The caller (SnifferExtractor) gets result.
                // But the Dialog might remain open if cleanup isn't called?
                // YES. 
                
                // FIX: Let's rely on videoMonitorJob/polling for the cleanup access?
                // OR make webView/dialog instance variables?
                // runSession is "suspend". We can have multiple concurrent sessions theoretically?
                // No, usually single sniffer.
                // Providing proper cleanup is critical.
                
                // REVERT STRATEGY for checkExitCondition: 
                // Better to just notify the polling loop? 
                // Or make webView/dialog valid instance variables for the duration of runSession.
            }
        }
    }
    
    // Helper property to store current exit condition for checkExitCondition
    private var exitConditionReference: ExitCondition? = null
    
    // To handle cleanup properly, we'll keep references for the active session
    private var activeWebView: WebView? = null
    private var activeDialog: Dialog? = null
    
    private var statusTextView: TextView? = null
    
    private fun updateDialogText(text: String) {
        try {
            statusTextView?.text = text
        } catch (e: Exception) {}
    }
    
    private var tvMouseController: com.cloudstream.shared.ui.TvMouseController? = null

    private fun createDialog(activity: android.app.Activity, webView: WebView): Dialog {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            
            addView(TextView(activity).apply {
                text = "Video Search" // Updated title
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(16, 32, 16, 16)
            })
            
            statusTextView = TextView(activity).apply {
                text = "Looking for video streams..." // Updated subtitle
                textSize = 14f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(16, 0, 16, 16)
            }
            addView(statusTextView)
            
            // TV MOUSE INTEGRATION: Wrap WebView in FrameLayout to support Overlay
            val webViewContainer = android.widget.FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0, 1f
                )
            }
            
            // Add WebView to FrameLayout
            webViewContainer.addView(webView.apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
            
            addView(webViewContainer)
            
            // Initialize Mouse Controller
            tvMouseController = com.cloudstream.shared.ui.TvMouseController(activity, webView)
            tvMouseController?.attach(webViewContainer)
        }
        
        return Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(container)
            setCancelable(true)
            
            // Forward Key Events to Mouse Controller
            setOnKeyListener { _, keyCode, event ->
                tvMouseController?.onKeyEvent(event) ?: false
            }

            setOnDismissListener {
                 // Triggered when user presses back or touches outside
                 if (!resultDelivered) {
                    ProviderLogger.d(TAG_WEBVIEW, "runSession", "Dialog dismissed by user")
                    resultDelivered = true
                    timeoutJob?.cancel()
                    videoMonitorJob?.cancel()
                    
                    // extractCookies is suspend, so we need a coroutine
                    CoroutineScope(Dispatchers.Main).launch {
                         val cookies = extractCookies(webView, webView.url ?: "")
                         val found = capturedLinks.toList()
                         val html = try {
                              "" 
                         } catch (e: Exception) { "" }
                         
                         deferred?.complete(WebViewResult.Success(cookies, html, webView.url ?: "", found))
                    }
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
            ProviderLogger.e(TAG_WEBVIEW, "startDomVideoExtraction", "WebView is null, cannot extract")
            return
        }
        
        ProviderLogger.i(TAG_WEBVIEW, "startDomVideoExtraction", "=== Starting DOM video extraction polling ===")
        
        // Poll every 2 seconds to extract video sources from DOM
        CoroutineScope(Dispatchers.Main).launch {
            var attempts = 0
            ProviderLogger.i(TAG_WEBVIEW, "startDomVideoExtraction", "Polling started", "maxAttempts" to 30)
            
            while (!resultDelivered && attempts < 30) { // Max 60 seconds
                delay(2000)
                attempts++
                
                ProviderLogger.d(TAG_WEBVIEW, "startDomVideoExtraction", "Polling attempt", "attempt" to attempts)
                
                view.evaluateJavascript("""
                    (function() {
                        console.log('[WebViewEngine] DOM extraction running...');
                        var sources = [];
                        var videoCount = 0;
                        var sourceCount = 0;
                        
                        // 1. Check video elements
                        var videos = document.querySelectorAll('video');
                        videoCount = videos.length;
                        videos.forEach(function(v) {
                            console.log('[WebViewEngine] Video found:', v.src || 'no-src', 'currentSrc:', v.currentSrc || 'no-currentSrc');
                            if (v.src && v.src.length > 20) {
                                sources.push({src: v.src, type: 'video.src'});
                            }
                            if (v.currentSrc && v.currentSrc.length > 20) {
                                sources.push({src: v.currentSrc, type: 'video.currentSrc'});
                            }
                        });
                        
                        // 2. Check source elements
                        var sources = document.querySelectorAll('source');
                        sourceCount = sources.length;
                        sources.forEach(function(s) {
                            console.log('[WebViewEngine] Source found:', s.src || 'no-src', 'type:', s.type || 'no-type');
                            if (s.src && s.src.length > 20) {
                                sources.push({src: s.src, type: 'source'});
                            }
                        });
                        
                        // 3. Check for MediaSource extensions
                        if (window.MediaSource && window.MediaSource.isTypeSupported) {
                            console.log('[WebViewEngine] MediaSource is available');
                        }
                        
                        // 4. Check common player objects
                        try {
                            if (window.player && window.player.src) {
                                console.log('[WebViewEngine] window.player.src:', window.player.src);
                                sources.push({src: window.player.src, type: 'window.player'});
                            }
                            if (window.videoPlayer && window.videoPlayer.src) {
                                console.log('[WebViewEngine] window.videoPlayer.src:', window.videoPlayer.src);
                                sources.push({src: window.videoPlayer.src, type: 'window.videoPlayer'});
                            }
                            if (window.hls && window.hls.url) {
                                console.log('[WebViewEngine] window.hls.url:', window.hls.url);
                                sources.push({src: window.hls.url, type: 'window.hls'});
                            }
                        } catch(e) {
                            console.log('[WebViewEngine] Error checking player objects:', e);
                        }
                        
                        console.log('[WebViewEngine] Extraction complete. Videos:', videoCount, 'Sources:', sourceCount, 'Found:', sources.length);
                        return JSON.stringify({videoCount: videoCount, sourceCount: sourceCount, sources: sources});
                    })()
                """) { result ->
                    try {
                        ProviderLogger.d(TAG_WEBVIEW, "DOM Extraction", "Raw result received", "result" to (result ?: "null"))
                        
                        
                        if (!result.isNullOrBlank() && result != "null") {
                            // FIX: Unescape the JSON string first
                            val jsonString = org.json.JSONTokener(result).nextValue().toString()
                            val jsonObj = org.json.JSONObject(jsonString)
                            val videoCount = jsonObj.optInt("videoCount", 0)
                            val sourceCount = jsonObj.optInt("sourceCount", 0)
                            val sourcesArray = jsonObj.optJSONArray("sources")
                            
                            ProviderLogger.i(TAG_WEBVIEW, "DOM Extraction", "Page analysis", 
                                "videos" to videoCount, 
                                "sources" to sourceCount,
                                "foundUrls" to (sourcesArray?.length() ?: 0)
                            )
                            
                            if (sourcesArray != null && sourcesArray.length() > 0) {
                                ProviderLogger.i(TAG_WEBVIEW, "DOM Extraction", "Found video sources!", "count" to sourcesArray.length())
                                
                                for (i in 0 until sourcesArray.length()) {
                                    val obj = sourcesArray.getJSONObject(i)
                                    val src = obj.optString("src")
                                    val type = obj.optString("type")
                                    
                                    if (src.isNotBlank() && src.length > 20) {
                                        ProviderLogger.i(TAG_WEBVIEW, "DOM Extraction", "Capturing URL", "url" to src.take(100), "type" to type)
                                        captureLink(src, type, emptyMap())
                                    }
                                }
                            } else {
                                ProviderLogger.d(TAG_WEBVIEW, "DOM Extraction", "No video sources found in this poll")
                            }
                        } else {
                            ProviderLogger.w(TAG_WEBVIEW, "DOM Extraction", "Empty or null result from JS")
                        }
                    } catch (e: Exception) {
                        ProviderLogger.e(TAG_WEBVIEW, "DOM Extraction", "Error parsing result", e)
                    }
                }
            }
            
            if (resultDelivered) {
                ProviderLogger.i(TAG_WEBVIEW, "startDomVideoExtraction", "=== Polling ended (result delivered) ===", "totalAttempts" to attempts)
            } else if (attempts >= 30) {
                ProviderLogger.w(TAG_WEBVIEW, "startDomVideoExtraction", "=== Polling ended (max attempts reached) ===", "totalAttempts" to attempts)
            }
        }
    }

    inner class SnifferBridge {
        @JavascriptInterface
        fun onSourcesFound(json: String) {
             if (resultDelivered) return
             android.util.Log.i("WebViewEngine", "SnifferBridge: Sources found! jsonLen=${json.length}")
             ProviderLogger.i(TAG_WEBVIEW, "SnifferBridge", "=== JS Bridge: Sources found! ===", "jsonLength" to json.length)
             try {
                val array = org.json.JSONArray(json)
                ProviderLogger.i(TAG_WEBVIEW, "SnifferBridge", "Parsing JS sources", "count" to array.length())
                
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
                    
                    ProviderLogger.i(TAG_WEBVIEW, "SnifferBridge", "Processing source #$i", 
                        "src" to (finalSrc.take(80) ?: "empty"),
                        "type" to type,
                        "label" to label
                    )
                    
                    if (finalSrc.isNotBlank() && finalSrc.length > 20) {
                         ProviderLogger.i(TAG_WEBVIEW, "SnifferBridge", "Capturing source from JS", 
                             "url" to finalSrc.take(100),
                             "label" to label
                         )
                         captureLink(finalSrc, label, emptyMap())
                    } else {
                        ProviderLogger.w(TAG_WEBVIEW, "SnifferBridge", "Source rejected - too short or empty", 
                            "length" to finalSrc.length
                        )
                    }
                }
             } catch (e: Exception) {
                 ProviderLogger.e(TAG_WEBVIEW, "SnifferBridge", "Failed to parse JS data", e)
             }
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("VideoSnifferJS", "BRIDGE: " + message)
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
                    ProviderLogger.e(TAG_WEBVIEW, "getHtmlFromWebView", "HTML escape failed", e)
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
                    // Actually, for CF, JS cookies are what matter most.
                    val merged = HashMap<String, String>()
                    merged.putAll(cmMap)
                    merged.putAll(jsMap) // JS overwrites
                    
                    ProviderLogger.d(TAG_WEBVIEW, "extractCookies", "Cookie extraction complete",
                        "cmCount" to cmMap.size,
                        "jsCount" to jsMap.size,
                        "total" to merged.size,
                        "hasClearance" to merged.containsKey("cf_clearance")
                    )
                    
                    if (cont.isActive) cont.resume(merged) {}
                    
                } catch (e: Exception) {
                    ProviderLogger.e(TAG_WEBVIEW, "extractCookies", "JS parse failed", e)
                    if (cont.isActive) cont.resume(cmMap) {}
                }
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG_WEBVIEW, "extractCookies", "Extraction failed", e)
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
                view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                // Defer destroy to ensure pending callbacks complete
                view.post { 
                    try { view.destroy() } catch (e: Exception) { /* ignore */ } 
                }
            }
            // Clear active references
            if (activeWebView == webView) activeWebView = null
            if (activeDialog == dialog) activeDialog = null
        } catch (e: Exception) {
            ProviderLogger.w(TAG_WEBVIEW, "cleanup", "Error", "error" to e.message)
        }
    }
}

/**
 * Exit conditions for WebView sessions.
 */
sealed class ExitCondition {
    /** Exit when page loads without CF challenge */
    object PageLoaded : ExitCondition()
    
    /** Exit when specific cookies are present */
    data class CookiesPresent(val keys: List<String>) : ExitCondition()
    
    /** Exit when video URLs are found */
    data class VideoFound(val minCount: Int = 1) : ExitCondition()
}

/**
 * Result of a WebView session.
 */
sealed class WebViewResult {
    data class Success(
        val cookies: Map<String, String>,
        val html: String,
        val finalUrl: String,
        val foundLinks: List<CapturedLinkData> = emptyList()
    ) : WebViewResult()
    
    data class Timeout(
        val lastUrl: String,
        val partialHtml: String?
    ) : WebViewResult()
    
    data class Error(val reason: String) : WebViewResult()
}

data class CapturedLinkData(
    val url: String,
    val qualityLabel: String,
    val headers: Map<String, String>
)
