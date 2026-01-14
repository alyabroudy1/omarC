package com.faselhd.service.strategy

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_WEBVIEW
import com.faselhd.service.cloudflare.CloudflareDetector
import com.faselhd.service.cookie.CookieLifecycleManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WebView-based request strategy for solving Cloudflare challenges.
 * 
 * ## Purpose:
 * - Loads pages in a headless WebView
 * - Automatically solves Cloudflare challenges
 * - Extracts cookies after challenge solves
 * - Returns HTML content directly (no retry needed)
 * 
 * ## Features:
 * - Automatic cookie extraction and storage
 * - JS injection support
 * - Configurable timeout
 * - Comprehensive logging
 * 
 * ## Inheritance:
 * Designed to be extended by VideoSniffingStrategy which adds
 * video URL monitoring while inheriting all cookie management.
 * 
 * @param context Android context for WebView creation
 * @param cookieManager Cookie lifecycle manager for storage
 * @param cfDetector Cloudflare detection utility
 */
open class WebViewStrategy(
    protected val context: Context,
    protected val cookieManager: CookieLifecycleManager,
    protected val cfDetector: CloudflareDetector = CloudflareDetector()
) : RequestStrategy {
    
    override val name: String = "WebView"
    
    /** JavaScript to inject after page load. Override in subclasses. */
    protected open val jsInjection: String? = null
    
    /** Timeout for WebView loading in milliseconds. */
    protected open val timeout: Long = 35_000
    
    /** Minimum time to wait for CF challenge to solve (ms). */
    protected open val cfSolveWaitTime: Long = 5_000
    
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun execute(request: StrategyRequest): StrategyResponse {
        val startTime = System.currentTimeMillis()
        val result = CompletableDeferred<StrategyResponse>()
        
        ProviderLogger.i(TAG_WEBVIEW, "execute", "Starting WebView load",
            "url" to request.url.take(80),
            "timeout" to timeout
        )
        
        // Pre-inject cookies into WebView's CookieManager
        preInjectCookies(request)
        
        withContext(Dispatchers.Main) {
            var webView: WebView? = null
            
            try {
                webView = createWebView(request)
                
                var pageLoaded = false
                var challengeDetected = false
                var challengeSolved = false
                var finalHtml: String? = null
                var finalUrl: String? = null
                
                webView.webViewClient = object : WebViewClient() {
                    
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        ProviderLogger.d(TAG_WEBVIEW, "onPageStarted", "Page started",
                            "url" to (url?.take(80) ?: "null")
                        )
                    }
                    
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        finalUrl = url
                        
                        ProviderLogger.d(TAG_WEBVIEW, "onPageFinished", "Page finished",
                            "url" to (url?.take(80) ?: "null")
                        )
                        
                        // Inject custom JS if provided
                        jsInjection?.let { js ->
                            view?.evaluateJavascript(js) { jsResult ->
                                ProviderLogger.d(TAG_WEBVIEW, "jsInjection", "JS executed",
                                    "resultLength" to (jsResult?.length ?: 0)
                                )
                                onJsResult(jsResult)
                            }
                        }
                        
                        // Check for CF challenge
                        view?.evaluateJavascript(
                            "(function() { return document.body.innerHTML; })();"
                        ) { html ->
                            val cleanHtml = html?.removeSurrounding("\"")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                            
                            if (cfDetector.isChallengePage(cleanHtml)) {
                                challengeDetected = true
                                ProviderLogger.w(TAG_WEBVIEW, "onPageFinished", "CF challenge detected, waiting for solve")
                            } else {
                                // Check if we have clearance cookie now
                                val cookies = extractCookies(url ?: request.url)
                                if (cfDetector.hasClearanceCookie(cookies)) {
                                    challengeSolved = true
                                    finalHtml = cleanHtml
                                    pageLoaded = true
                                    ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "CF challenge solved!",
                                        "hasClearance" to true
                                    )
                                } else if (!challengeDetected) {
                                    // No challenge, just regular page
                                    finalHtml = cleanHtml
                                    pageLoaded = true
                                    ProviderLogger.i(TAG_WEBVIEW, "onPageFinished", "Page loaded (no challenge)")
                                }
                            }
                        }
                    }
                    
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        ProviderLogger.d(TAG_WEBVIEW, "shouldOverrideUrlLoading", "Navigation",
                            "url" to url.take(80)
                        )
                        return false
                    }
                }
                
                // Load URL
                webView.loadUrl(request.url)
                
                // Wait for result with timeout
                val timeoutResult = withTimeoutOrNull(timeout) {
                    // Poll for completion
                    while (!pageLoaded && !result.isCompleted) {
                        kotlinx.coroutines.delay(500)
                        
                        // If challenge was detected, wait extra time for solve
                        if (challengeDetected && !challengeSolved) {
                            kotlinx.coroutines.delay(cfSolveWaitTime)
                            
                            // Re-check for cookies after wait
                            val cookies = extractCookies(request.url)
                            if (cfDetector.hasClearanceCookie(cookies)) {
                                challengeSolved = true
                                // Get final HTML
                                webView.evaluateJavascript(
                                    "(function() { return document.body.innerHTML; })();"
                                ) { html ->
                                    finalHtml = html?.removeSurrounding("\"")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                                    pageLoaded = true
                                }
                            }
                        }
                    }
                    
                    // Build response
                    val durationMs = System.currentTimeMillis() - startTime
                    val cookies = extractCookies(finalUrl ?: request.url)
                    
                    // Store cookies
                    if (cookies.isNotEmpty()) {
                        cookieManager.store(finalUrl ?: request.url, cookies, "WebView")
                    }
                    
                    ProviderLogger.i(TAG_WEBVIEW, "execute", "WebView completed",
                        "durationMs" to durationMs,
                        "challengeDetected" to challengeDetected,
                        "challengeSolved" to challengeSolved,
                        "htmlLength" to (finalHtml?.length ?: 0),
                        "cookieCount" to cookies.size
                    )
                    
                    StrategyResponse.success(
                        html = finalHtml ?: "",
                        code = 200,
                        cookies = cookies,
                        finalUrl = finalUrl ?: request.url,
                        duration = durationMs,
                        strategy = name
                    )
                }
                
                if (timeoutResult == null) {
                    val durationMs = System.currentTimeMillis() - startTime
                    ProviderLogger.e(TAG_WEBVIEW, "execute", "WebView timeout", null,
                        "durationMs" to durationMs,
                        "timeout" to timeout
                    )
                    result.complete(StrategyResponse.failure(
                        code = -1,
                        error = Exception("WebView timeout after ${timeout}ms"),
                        duration = durationMs,
                        strategy = name
                    ))
                } else {
                    result.complete(timeoutResult)
                }
                
            } catch (e: Exception) {
                val durationMs = System.currentTimeMillis() - startTime
                ProviderLogger.e(TAG_WEBVIEW, "execute", "WebView error", e,
                    "durationMs" to durationMs
                )
                result.complete(StrategyResponse.failure(
                    code = -1,
                    error = e,
                    duration = durationMs,
                    strategy = name
                ))
            } finally {
                // Cleanup WebView on main thread
                webView?.let { wv ->
                    Handler(Looper.getMainLooper()).post {
                        wv.stopLoading()
                        wv.destroy()
                    }
                }
            }
        }
        
        return result.await()
    }
    
    override fun canHandle(context: RequestContext): Boolean {
        // WebView handles when cookies are invalid or forceWebView is set
        val canHandle = !context.hasValidCookies || context.forceWebView
        
        ProviderLogger.d(TAG_WEBVIEW, "canHandle", "Strategy check",
            "canHandle" to canHandle,
            "hasValidCookies" to context.hasValidCookies,
            "forceWebView" to context.forceWebView
        )
        
        return canHandle
    }
    
    // ========== PROTECTED METHODS FOR SUBCLASSES ==========
    
    /**
     * Called when JS injection returns a result.
     * Override in subclasses to handle custom JS results.
     */
    protected open fun onJsResult(result: String?) {
        // Default: no-op
    }
    
    /**
     * Creates and configures the WebView.
     * Override in subclasses to add custom configuration.
     */
    @SuppressLint("SetJavaScriptEnabled")
    protected open fun createWebView(request: StrategyRequest): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = request.userAgent
            
            // Enable third-party cookies
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            
            ProviderLogger.d(TAG_WEBVIEW, "createWebView", "WebView created",
                "jsEnabled" to true,
                "userAgent" to request.userAgent.take(50)
            )
        }
    }
    
    /**
     * Pre-injects cookies before WebView loads.
     */
    private fun preInjectCookies(request: StrategyRequest) {
        if (request.cookies.isEmpty()) return
        
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        
        request.cookies.forEach { (name, value) ->
            cookieManager.setCookie(request.url, "$name=$value")
        }
        cookieManager.flush()
        
        ProviderLogger.d(TAG_WEBVIEW, "preInjectCookies", "Cookies injected",
            "count" to request.cookies.size
        )
    }
    
    /**
     * Extracts cookies from WebView's CookieManager.
     */
    protected fun extractCookies(url: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        
        try {
            val cookieString = CookieManager.getInstance().getCookie(url) ?: return cookies
            
            cookieString.split(";").forEach { cookie ->
                val parts = cookie.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    cookies[parts[0].trim()] = parts[1].trim()
                }
            }
            
            ProviderLogger.d(TAG_WEBVIEW, "extractCookies", "Cookies extracted",
                "url" to url.take(50),
                "count" to cookies.size,
                "hasClearance" to cookies.containsKey("cf_clearance")
            )
        } catch (e: Exception) {
            ProviderLogger.e(TAG_WEBVIEW, "extractCookies", "Failed to extract cookies", e)
        }
        
        return cookies
    }
}
