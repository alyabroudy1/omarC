package com.arabseed.service.webview

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
import com.arabseed.service.http.CloudflareDetector
import com.lagradost.api.Log
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
    private val TAG = "WebViewEngine"
    
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
            Log.e(TAG, "No Activity available")
            return@withContext WebViewResult.Error("No Activity context")
        }
        
        val deferred = CompletableDeferred<WebViewResult>()
        var resultDelivered = false
        var dialog: Dialog? = null
        var webView: WebView? = null
        
        // Timeout handler
        val timeoutJob = CoroutineScope(Dispatchers.Main).launch {
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
            Log.i(TAG, "WebView UA: ${webView.settings.userAgentString.take(60)}...")
            
            // Setup cookies
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            
            // Setup based on mode
            when (mode) {
                Mode.HEADLESS -> {
                    // Headless - no dialog, webview not visible
                    Log.d(TAG, "Running HEADLESS session for: $url")
                }
                Mode.FULLSCREEN -> {
                    // Show dialog with webview
                    dialog = createDialog(activity, webView)
                    dialog.show()
                    Log.d(TAG, "Running FULLSCREEN session for: $url")
                }
            }
            
            // Setup WebViewClient
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val nextUrl = request?.url?.toString()
                    Log.d(TAG, "WebView Redirecting to: $nextUrl")
                    return super.shouldOverrideUrlLoading(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Log.d(TAG, "WebView Page Started: $url")
                    
                    // Inject Advanced Polyfill & Fingerprint Spoofing
                    // 1. Fix Arabseed mobile site bug (object__info)
                    // 2. Spoof Windows Platform to match MainAPI.USER_AGENT (if forced)
                    view?.evaluateJavascript(
                        """
                        (function() {
                            // 1. Polyfill
                            if (typeof window.object__info === 'undefined') {
                                window.object__info = {};
                            }
                            
                            // 2. Fingerprint Spoofing (Match Desktop UA)
                            // Cloudflare checks platform vs UA. Android WebView says 'Linux armv8l'.
                            // If UA is Windows, we MUST say platform is Win32.
                            if (navigator.userAgent.indexOf("Windows") !== -1) {
                                Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; } });
                                Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; } });
                                Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                                console.log('Spoof: Platform mocked as Win32');
                            }
                        })();
                        """.trimIndent(), null
                    )
                    
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    val currentUrl = view?.url ?: loadedUrl ?: url
                    Log.d(TAG, "WebView Page Finished: $currentUrl")

                    if (resultDelivered) return
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            if (delayMs > 0) {
                                Log.d(TAG, "Waiting ${delayMs}ms for dynamic content...")
                                delay(delayMs)
                            }
                            
                            val html = getHtmlFromWebView(view!!)
                            
                            // Check exit condition
                            val shouldExit = when (exitCondition) {
                                is ExitCondition.PageLoaded -> {
                                    val isCf = CloudflareDetector.isCloudflareChallenge(html)
                                    if (isCf) Log.d(TAG, "WebView still in Cloudflare challenge...")
                                    !isCf
                                }
                                is ExitCondition.CookiesPresent -> {
                                    val cookies = extractCookies(currentUrl)
                                    exitCondition.keys.all { key -> cookies.containsKey(key) }
                                }
                                is ExitCondition.VideoFound -> {
                                    // Video found is handled separately via network interception
                                    false
                                }
                            }
                            
                            if (shouldExit) {
                                resultDelivered = true
                                timeoutJob.cancel()
                                
                                // Extract cookies - caller will update SessionState
                                val cookies = extractCookies(currentUrl)
                                Log.d(TAG, "WebView exit: extracted ${cookies.size} cookies")
                                
                                cleanup(view, dialog)
                                deferred.complete(WebViewResult.Success(cookies, html, currentUrl))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in onPageFinished: ${e.message}")
                        }
                    }
                }
                
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        Log.e(TAG, "WebView error: ${error?.description} for ${request.url}")
                    }
                }
            }
            
            // Load URL
            // Headers to bypass detection
            val extraHeaders = mutableMapOf<String, String>()
            // Removing X-Requested-With is critical for some CF challenges
            extraHeaders["X-Requested-With"] = ""
            
            webView.loadUrl(url, extraHeaders)
            
        } catch (e: Exception) {
            resultDelivered = true
            timeoutJob.cancel()
            cleanup(webView, dialog)
            deferred.complete(WebViewResult.Error(e.message ?: "Unknown error"))
        }
        
        deferred.await()
    }
    
    private fun createDialog(activity: android.app.Activity, webView: WebView): Dialog {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            
            addView(TextView(activity).apply {
                text = "🔒 Security Check"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(16, 32, 16, 16)
            })
            
            addView(TextView(activity).apply {
                text = "Please complete the check to continue..."
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
            })
        }
        
        return Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(container)
            setCancelable(true)
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
                    Log.e(TAG, "Failed to unescape HTML: ${e.message}")
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
            Log.w(TAG, "Failed to extract cookies: ${e.message}")
        }
        return cookies
    }
    
    private fun cleanup(webView: WebView?, dialog: Dialog?) {
        try {
            dialog?.dismiss()
            webView?.let { view ->
                view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                // Defer destroy to ensure pending callbacks (like onPageFinished internal logic) complete
                // Fixes: "Application attempted to call on a destroyed WebView"
                view.post { 
                    try { view.destroy() } catch (e: Exception) { /* ignore */ } 
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error: ${e.message}")
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
    
    /** Exit when video URLs matching patterns are found */
    data class VideoFound(val patterns: List<Regex>) : ExitCondition()
}

/**
 * Result of a WebView session.
 */
sealed class WebViewResult {
    data class Success(
        val cookies: Map<String, String>,
        val html: String,
        val finalUrl: String
    ) : WebViewResult()
    
    data class Timeout(
        val lastUrl: String,
        val partialHtml: String?
    ) : WebViewResult()
    
    data class Error(val reason: String) : WebViewResult()
}
