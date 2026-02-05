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
        
        val deferred = CompletableDeferred<WebViewResult>()
        var resultDelivered = false
        var dialog: Dialog? = null
        var webView: WebView? = null
        
        // BUGFIX: Clear capturedLinks at the start of each session
        capturedLinks.clear()
        ProviderLogger.d(TAG_WEBVIEW, "runSession", "Session started, capturedLinks cleared")
        
        // Timeout handler
        val timeoutJob = CoroutineScope(Dispatchers.Main).launch {
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
        val videoMonitorJob = if (exitCondition is ExitCondition.VideoFound) {
            CoroutineScope(Dispatchers.Main).launch {
                val requiredCount = (exitCondition as ExitCondition.VideoFound).minCount
                ProviderLogger.d(TAG_WEBVIEW, "runSession", "Video monitor started", "requiredCount" to requiredCount)
                while (!resultDelivered) {
                    delay(300)
                    if (capturedLinks.size >= requiredCount) {
                        resultDelivered = true
                        timeoutJob.cancel()
                        val cookies = extractCookies(url)
                        val foundLinks = capturedLinks.toList()
                        ProviderLogger.d(TAG_WEBVIEW, "runSession", "Video monitor found ${foundLinks.size} videos - exiting early!")
                        cleanup(webView, dialog)
                        deferred.complete(WebViewResult.Success(cookies, "", url, foundLinks))
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
                                timeoutJob.cancel()
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
            timeoutJob.cancel()
            videoMonitorJob?.cancel()
            cleanup(webView, dialog)
            deferred.complete(WebViewResult.Error(e.message ?: "Unknown error"))
        }
        
        deferred.await()
    }
    
    private fun isVideoUrl(url: String): Boolean {
        // Simple check or robust regex
        return url.contains(".m3u8") || url.contains(".mp4") || url.contains(".mkv")
    }

    private val capturedLinks = java.util.concurrent.CopyOnWriteArrayList<CapturedLinkData>()

    private fun captureLink(url: String, qualityLabel: String, headers: Map<String, String>) {
         // Logic to store captured link
         val data = CapturedLinkData(url, qualityLabel, headers)
         if (capturedLinks.none { it.url == url }) {
             capturedLinks.add(data)
             ProviderLogger.d(TAG_WEBVIEW, "captureLink", "Captured", "url" to url)
         }
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
            
            addView(TextView(activity).apply {
                text = "Looking for video streams..." // Updated subtitle
                textSize = 14f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(16, 0, 16, 16)
            })
            
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
                // If dismiss, we might want to return what we found so far?
                // But deferred is inside runSession.
            }
        }
    }

    inner class SnifferBridge {
        @JavascriptInterface
        fun onSourcesFound(json: String) {
             // Parse JSON similar to VideoSniffingStrategy
             // For brevity, let's assume simple parsing or delegate
             ProviderLogger.d(TAG_WEBVIEW, "SnifferBridge", "Sources found via JS", "json" to json)
             // Parsing logic here...
             // captureLink(...)
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
