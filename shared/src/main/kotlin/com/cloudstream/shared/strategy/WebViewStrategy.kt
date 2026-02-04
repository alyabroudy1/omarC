package com.cloudstream.shared.strategy

import com.cloudstream.shared.cookie.CookieLifecycleManager
import com.cloudstream.shared.cloudflare.CloudflareDetector
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_WEBVIEW
import com.cloudstream.shared.webview.WebViewEngine
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.WebViewResult

/**
 * WebView-based request strategy using WebViewEngine.
 * 
 * Flow:
 * 1. First try HEADLESS mode (no UI, background WebView)
 * 2. If CF still blocks, retry with FULLSCREEN mode (user-visible dialog)
 * 3. Extract cookies on success for future DirectHttp requests
 * 
 * @param cookieManager Cookie lifecycle manager
 * @param webViewEngine WebViewEngine instance (provided by provider)
 * @param userAgent User-Agent string to use
 * @param skipHeadless If true, skip headless and go straight to fullscreen
 */
class WebViewStrategy(
    private val cookieManager: CookieLifecycleManager,
    private val webViewEngine: WebViewEngine,
    private val userAgent: String,
    private val skipHeadless: Boolean = false
) : RequestStrategy {
    
    override val name: String = "WebView"
    
    /** Maximum retries for CF solving */
    private val maxRetries = 2
    
    /** Timeout for headless mode (shorter) */
    private val headlessTimeout = 30_000L
    
    /** Timeout for fullscreen mode (longer for user interaction) */
    private val fullscreenTimeout = 120_000L
    
    override suspend fun execute(request: StrategyRequest): StrategyResponse {
        val startTime = System.currentTimeMillis()
        
        ProviderLogger.i(TAG_WEBVIEW, "execute", "Starting WebView strategy",
            "url" to request.url.take(80), "skipHeadless" to skipHeadless)
        
        // Step 1: Try HEADLESS first (unless skipped)
        if (!skipHeadless) {
            val headlessResult = tryWebView(request, WebViewEngine.Mode.HEADLESS, headlessTimeout)
            
            if (headlessResult.success) {
                val durationMs = System.currentTimeMillis() - startTime
                ProviderLogger.i(TAG_WEBVIEW, "execute", "HEADLESS succeeded",
                    "durationMs" to durationMs)
                return headlessResult
            }
            
            ProviderLogger.d(TAG_WEBVIEW, "execute", "HEADLESS failed, trying FULLSCREEN")
        }
        
        // Step 2: Try FULLSCREEN (with retries)
        var lastResult: StrategyResponse? = null
        
        repeat(maxRetries) { attempt ->
            val result = tryWebView(request, WebViewEngine.Mode.FULLSCREEN, fullscreenTimeout)
            
            if (result.success) {
                val durationMs = System.currentTimeMillis() - startTime
                ProviderLogger.i(TAG_WEBVIEW, "execute", "FULLSCREEN succeeded",
                    "attempt" to (attempt + 1), "durationMs" to durationMs)
                return result
            }
            
            lastResult = result
            ProviderLogger.w(TAG_WEBVIEW, "execute", "FULLSCREEN attempt failed",
                "attempt" to (attempt + 1))
        }
        
        val duration = System.currentTimeMillis() - startTime
        return lastResult ?: StrategyResponse.failure(
            code = -1,
            error = Exception("WebView strategy exhausted all attempts"),
            duration = duration,
            strategy = name
        )
    }
    
    private suspend fun tryWebView(
        request: StrategyRequest,
        mode: WebViewEngine.Mode,
        timeout: Long
    ): StrategyResponse {
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = webViewEngine.runSession(
                url = request.url,
                mode = mode,
                userAgent = userAgent,
                exitCondition = ExitCondition.PageLoaded,
                timeout = timeout
            )
            
            val duration = System.currentTimeMillis() - startTime
            
            when (result) {
                is WebViewResult.Success -> {
                    // Store cookies
                    if (result.cookies.isNotEmpty()) {
                        cookieManager.store(request.url, result.cookies, "WebView-$mode")
                    }
                    
                    StrategyResponse.success(
                        html = result.html,
                        code = 200,
                        cookies = result.cookies,
                        finalUrl = result.finalUrl,
                        duration = duration,
                        strategy = "$name-$mode"
                    )
                }
                
                is WebViewResult.Timeout -> {
                    // Check if partial HTML still shows CF - use object method
                    val isCf = result.partialHtml?.let { 
                        CloudflareDetector.isCloudflareChallenge(it)
                    } ?: true
                    
                    if (isCf) {
                        StrategyResponse.cloudflareBlocked(
                            code = 403,
                            duration = duration,
                            strategy = "$name-$mode"
                        )
                    } else {
                        // Timeout but got content - partial success
                        StrategyResponse.success(
                            html = result.partialHtml ?: "",
                            code = 200,
                            cookies = emptyMap(),
                            finalUrl = result.lastUrl,
                            duration = duration,
                            strategy = "$name-$mode"
                        )
                    }
                }
                
                is WebViewResult.Error -> {
                    StrategyResponse.failure(
                        code = -1,
                        error = Exception(result.reason),
                        duration = duration,
                        strategy = "$name-$mode"
                    )
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            ProviderLogger.e(TAG_WEBVIEW, "tryWebView", "Exception", e)
            StrategyResponse.failure(-1, e, duration, "$name-$mode")
        }
    }
    
    override fun canHandle(context: RequestContext): Boolean {
        // WebView handles when cookies are invalid or forceWebView is set
        return !context.hasValidCookies || context.forceWebView
    }
}
