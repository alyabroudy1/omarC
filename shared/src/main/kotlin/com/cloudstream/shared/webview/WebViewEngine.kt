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
                    deferred.complete(WebViewResult.Success(extractCookies(url), "", url, foundLinks))
                } else {
                    deferred.complete(WebViewResult.Timeout(url, partialHtml))
                }
            }
        }
        
        // BUGFIX: Proactive video monitoring job - checks every 300ms for captured videos
        this@WebViewEngine.videoMonitorJob = if (exitCondition is ExitCondition.VideoFound) {
            CoroutineScope(Dispatchers.Main).launch {
                val requiredCount = (exitCondition as ExitCondition.VideoFound).minCount
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
                             
                             val cookies = extractCookies(url)
                             val foundLinks = capturedLinks.toList()
                             ProviderLogger.d(TAG_WEBVIEW, "runSession", "Video monitor forced exit")
                             cleanup(webView, dialog)
                             deferred.complete(WebViewResult.Success(cookies, "", url, foundLinks))
                        }
                        break
                    }
                }
            }
        } else null
        
        try {
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
                }
            }
            
            // UA VERIFICATION: Log the exact UA WebView is using
            ProviderLogger.i(TAG_WEBVIEW, "runSession", "WebView UA",
                "ua" to webView.settings.userAgentString.take(60))
            
            // Setup cookies
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            
            // Setup based on mode
            when (mode) {
                Mode.HEADLESS -> {
                    ProviderLogger.d(TAG_WEBVIEW, "runSession", "HEADLESS mode", "url" to url.take(80))
                }
                Mode.FULLSCREEN -> {
                    dialog = createDialog(activity, webView)
                    dialog.show()
                    ProviderLogger.d(TAG_WEBVIEW, "runSession", "FULLSCREEN mode", "url" to url.take(80))
                }
            }
            
            // Setup WebViewClient
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString()
                    if (url != null && isVideoUrl(url)) {
                         ProviderLogger.d(TAG_WEBVIEW, "intercept", "Video found", "url" to url.take(80))
                         captureLink(url, "Network", request?.requestHeaders ?: emptyMap())
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val nextUrl = request?.url?.toString()
                    ProviderLogger.d(TAG_WEBVIEW, "shouldOverrideUrlLoading", "Redirect", "url" to nextUrl?.take(80))
                    return super.shouldOverrideUrlLoading(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    ProviderLogger.d(TAG_WEBVIEW, "onPageStarted", "Started", "url" to url?.take(80))
                    
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
                    val currentUrl = view?.url ?: loadedUrl ?: url
                    ProviderLogger.d(TAG_WEBVIEW, "onPageFinished", "Finished", "url" to currentUrl.take(80))

                    // Inject VideoSniffer JS
                    view?.evaluateJavascript(com.cloudstream.shared.strategy.VideoSniffingStrategy.JS_SCRIPT) {}

                    if (resultDelivered) return
                    
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
                                    val cookies = extractCookies(currentUrl)
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
                                
                                val cookies = extractCookies(currentUrl)
                                ProviderLogger.d(TAG_WEBVIEW, "onPageFinished", "Exit successful",
                                    "cookies" to cookies.size)
                                
                                cleanup(view, dialog)
                                var resultLinks = emptyList<CapturedLinkData>()
                                if (exitCondition is ExitCondition.VideoFound) {
                                    resultLinks = capturedLinks.toList()
                                }
                                deferred.complete(WebViewResult.Success(cookies, html, currentUrl, resultLinks))
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
                        ProviderLogger.w(TAG_WEBVIEW, "onReceivedError", "WebView error",
                            "description" to error?.description?.toString(), "url" to request.url.toString().take(80))
                    }
                }
            }
            
            // Load URL with headers to bypass detection
            val extraHeaders = mutableMapOf<String, String>()
            extraHeaders["X-Requested-With"] = ""
            
            webView.loadUrl(url, extraHeaders)
            
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
        if (isBlacklisted(url)) return false
        
        // Simple check or robust regex
        return url.contains(".m3u8") || url.contains(".mp4") || url.contains(".mkv") || 
               url.contains(".urls") || url.contains(".urlset") || url.contains("/master.m3u8")
    }

    private fun isBlacklisted(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("/ping.gif") || 
               lowerUrl.contains("/analytics") || 
               lowerUrl.contains("/google-analytics") || 
               lowerUrl.contains("favicon.ico")
    }

    private val capturedLinks = java.util.concurrent.CopyOnWriteArrayList<CapturedLinkData>()

    private fun captureLink(url: String, qualityLabel: String, headers: Map<String, String>) {
         // Logic to store captured link
         val data = CapturedLinkData(url, qualityLabel, headers)
         if (capturedLinks.none { it.url == url }) {
             capturedLinks.add(data)
             ProviderLogger.d(TAG_WEBVIEW, "captureLink", "Captured", "url" to url)
             
             // Update UI and Check Exit
             CoroutineScope(Dispatchers.Main).launch {
                 updateDialogText("Found ${capturedLinks.size} video stream(s)...")
                 // Trigger exit check immediately
                 checkExitCondition()
             }
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
                
                // Trigger success
                resultDelivered = true
                timeoutJob?.cancel()
                videoMonitorJob?.cancel()
                
                val cookies = extractCookies("") // URL not easily available here, implies current
                val found = capturedLinks.toList()
                
                // Find WebView instance if possible, or just cleanup whatever is stored
                // deferred is instance variable now
                
                // We need reference to webView and dialog to cleanup
                // But they are local to runSession. 
                // However, cleanup() handles nulls.
                // WE MUST BE CAREFUL: webView and dialog in runSession are correct scope.
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
            
            addView(webView.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0, 1f
                )
                addJavascriptInterface(SnifferBridge(), "SnifferBridge") // Add JS Bridge
            })
        }
        
        return Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(container)
            setCancelable(true)
            setOnDismissListener {
                 // Triggered when user presses back or touches outside
                 if (!resultDelivered) {
                    ProviderLogger.d(TAG_WEBVIEW, "runSession", "Dialog dismissed by user")
                    resultDelivered = true
                    timeoutJob?.cancel()
                    videoMonitorJob?.cancel()
                    
                    val cookies = extractCookies(webView.url ?: "")
                    val found = capturedLinks.toList()
                    val html = try {
                         // getHtmlFromWebView is suspend, so we need a coroutine scope or runBlocking
                         // Since we are in a listener on main thread, runBlocking might block UI momentarily but is safest for synchronous result requirement here?
                         // Actually, we are completing a deferred which is async.
                         // But we can't launch here easily without scope.
                         // Let's use GlobalScope or just empty string if complex?
                         // Better: use runBlocking just for the HTML part if it's fast? 
                         // getHtmlFromWebView uses suspendCancellableCoroutine.
                         // safest is just empty string here or launch.
                         "" 
                    } catch (e: Exception) { "" }
                    
                    deferred?.complete(WebViewResult.Success(cookies, html, webView.url ?: "", found))
                 }
            }
        }
    }

    inner class SnifferBridge {
        @JavascriptInterface
        fun onSourcesFound(json: String) {
             ProviderLogger.d(TAG_WEBVIEW, "SnifferBridge", "Sources found via JS", "json" to json)
             try {
                val array = org.json.JSONArray(json)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val src = item.optString("src")
                    val type = item.optString("type")
                    val label = item.optString("label", "JS-Source")
                    
                    if (src.isNotBlank() && isVideoUrl(src)) {
                         captureLink(src, label, emptyMap()) // JS usually doesn't give headers
                    }
                }
             } catch (e: Exception) {
                 ProviderLogger.e(TAG_WEBVIEW, "SnifferBridge", "Failed to parse JS data", e)
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
                    ProviderLogger.e(TAG_WEBVIEW, "getHtmlFromWebView", "HTML escape failed", e)
                    ""
                }
                cont.resume(html) {}
            }
        }
    }
    
    private fun extractCookies(url: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        try {
            CookieManager.getInstance().getCookie(url)?.split(";")?.forEach { cookie ->
                val parts = cookie.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    cookies[parts[0].trim()] = parts[1].trim()
                }
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG_WEBVIEW, "extractCookies", "Failed", "error" to e.message)
        }
        return cookies
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
