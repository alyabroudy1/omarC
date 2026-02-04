package com.youtube

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

import com.lagradost.api.Log
import kotlinx.coroutines.*
import java.net.URI

class WebViewEngine(
    private val cookieStore: CookieStore,
    private val activityProvider: () -> android.app.Activity?
) {
    private val TAG = "WebViewEngine"
    
    enum class Mode {
        HEADLESS,    // No UI, runs in background
        FULLSCREEN   // User-visible dialog for CAPTCHA
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun runSession(
        url: String,
        mode: Mode,
        userAgent: String,
        exitCondition: ExitCondition,
        timeout: Long = 60_000L
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
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            
            // Setup cookies
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            
            when (mode) {
                Mode.HEADLESS -> Log.d(TAG, "Running HEADLESS session for: $url")
                Mode.FULLSCREEN -> {
                    dialog = createDialog(activity, webView)
                    dialog.show()
                    Log.d(TAG, "Running FULLSCREEN session for: $url")
                }
            }
            
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    val currentUrl = view?.url ?: loadedUrl ?: url
                    Log.d(TAG, "WebView Page Finished: $currentUrl")

                    // Inject Consent Bypass
                    view?.evaluateJavascript("""
                        (function() {
                            var b = document.querySelectorAll('button');
                            for (var i = 0; i < b.length; i++) {
                                if (b[i].innerText.includes('Accept all') || b[i].innerText.includes('I agree') || b[i].innerText.includes('قبول الكل')) {
                                    b[i].click();
                                    return 'Clicked Consent';
                                }
                            }
                            // Form based consent (e.g. mobile)
                            var f = document.querySelector('form[action*="consent"]');
                            if (f) {
                                var s = f.querySelector('button') || f.querySelector('input[type="submit"]');
                                if (s) { s.click(); return 'Submitted Consent Form'; }
                            }
                            return 'No Consent Found';
                        })();
                    """) { res -> Log.d(TAG, "Consent Check Result: $res") }

                    if (resultDelivered) return
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(2000) // Wait specifically for consent reload if clicked
                        if (resultDelivered) return@launch
                        try {
                            val html = getHtmlFromWebView(view!!)
                            
                            val shouldExit = when (exitCondition) {
                                is ExitCondition.PageLoaded -> {
                                    // Check if we are still on consent page
                                    val isConsent = html.contains("consent.youtube.com") || html.contains("Before you continue")
                                    !isConsent
                                }
                                is ExitCondition.CookiesPresent -> {
                                    val cookies = extractCookies(currentUrl)
                                    exitCondition.keys.all { key -> cookies.containsKey(key) }
                                }
                                is ExitCondition.VideoFound -> false
                            }
                            
                            if (shouldExit) {
                                resultDelivered = true
                                timeoutJob.cancel()
                                val cookies = extractCookies(currentUrl)
                                cleanup(view, dialog)
                                deferred.complete(WebViewResult.Success(cookies, html, currentUrl))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in onPageFinished: ${e.message}")
                        }
                    }
                }
            }
            
            webView.loadUrl(url)
            
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
                text = "Processing..."
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(16, 32, 16, 16)
            })
            addView(webView.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
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
            try {
                webView.evaluateJavascript(
                    "(function() { return (window.ytInitialData ? JSON.stringify(window.ytInitialData) : document.documentElement.outerHTML); })();"
                ) { result ->
                    val html = result
                        ?.removeSurrounding("\"")
                        ?.replace("\\n", "\n")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                        ?: ""
                    if (cont.isActive) cont.resume(html) {}
                }
            } catch (t: Throwable) {
                Log.e(TAG, "WebView evaluateJavascript failed: ${t.message}")
                if (cont.isActive) cont.resume("") {}
            }
        }
    }
    
    private fun extractCookies(url: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        try {
            CookieManager.getInstance().getCookie(url)?.split(";")?.forEach { cookie ->
                val parts = cookie.trim().split("=", limit = 2)
                if (parts.size == 2) cookies[parts[0].trim()] = parts[1].trim()
            }
        } catch (e: Exception) { Log.w(TAG, "Failed to extract cookies") }
        return cookies
    }
    
    private fun cleanup(webView: WebView?, dialog: Dialog?) {
        try {
            dialog?.dismiss()
            webView?.stopLoading()
            webView?.destroy()
        } catch (e: Exception) {}
    }
}

sealed class ExitCondition {
    object PageLoaded : ExitCondition()
    data class CookiesPresent(val keys: List<String>) : ExitCondition()
    data class VideoFound(val patterns: List<Regex>) : ExitCondition()
}

sealed class WebViewResult {
    data class Success(val cookies: Map<String, String>, val html: String, val finalUrl: String) : WebViewResult()
    data class Timeout(val lastUrl: String, val partialHtml: String?) : WebViewResult()
    data class Error(val reason: String) : WebViewResult()
}
