package com.faselhd.service.strategy

import com.lagradost.cloudstream3.app
import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_WEBVIEW
import com.faselhd.service.cookie.CookieLifecycleManager
import com.faselhd.service.cloudflare.CloudflareDetector
import com.faselhd.ConfigurableCloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebView-based request strategy using ConfigurableCloudflareKiller.
 * 
 * ## Why ConfigurableCloudflareKiller?
 * - Uses ConfigurableWebViewResolver which properly handles CF Turnstile
 * - Battle-tested implementation that works in production
 * - Automatically manages cookie extraction and storage
 * - Supports third-party cookies required by CF
 * 
 * ## Flow:
 * 1. Make request with ConfigurableCloudflareKiller as interceptor
 * 2. If CF challenge detected, CFK triggers visible WebView (not headless)
 * 3. After solve, CFK stores cookies for future requests
 * 4. Returns response with HTML and cookies
 * 
 * @param cookieManager Cookie lifecycle manager for additional tracking
 * @param cfDetector Cloudflare detection utility
 */
class WebViewStrategy(
    private val cookieManager: CookieLifecycleManager,
    private val userAgent: String? = null,
    private val cfDetector: CloudflareDetector = CloudflareDetector()
) : RequestStrategy {
    
    override val name: String = "WebView"
    
    /** 
     * The ConfigurableCloudflareKiller instance.
     * This handles all CF solving internally using ConfigurableWebViewResolver.
     */
    private val cfKiller = ConfigurableCloudflareKiller(
        userAgent = userAgent,
        blockNonHttp = true,
        allowThirdPartyCookies = true
    )
    
    /** Maximum retries for CF solving */
    private val maxRetries = 3
    
    /** Delay between retries in ms */
    private val retryDelayMs = 2000L
    
    override suspend fun execute(request: StrategyRequest): StrategyResponse {
        val startTime = System.currentTimeMillis()
        
        ProviderLogger.i(TAG_WEBVIEW, "execute", "Starting request with ConfigurableCloudflareKiller",
            "url" to request.url.take(80),
            "maxRetries" to maxRetries
        )
        
        var lastException: Exception? = null
        var attempt = 0
        
        while (attempt < maxRetries) {
            attempt++
            
            try {
                val result = executeWithCfKiller(request, startTime, attempt)
                
                if (result.success) {
                    // Store cookies in our lifecycle manager for tracking
                    if (result.cookies.isNotEmpty()) {
                        cookieManager.store(request.url, result.cookies, "WebView-CFK")
                    }
                    return result
                }
                
                // If CF blocked, retry after delay
                if (result.isCloudflareChallenge) {
                    ProviderLogger.w(TAG_WEBVIEW, "execute", "CF challenge detected, retrying",
                        "attempt" to attempt,
                        "maxRetries" to maxRetries
                    )
                    
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(retryDelayMs)
                    }
                    continue
                }
                
                // Non-CF failure, don't retry
                return result
                
            } catch (e: Exception) {
                lastException = e
                ProviderLogger.e(TAG_WEBVIEW, "execute", "Request failed", e,
                    "attempt" to attempt
                )
                
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(retryDelayMs)
                }
            }
        }
        
        val durationMs = System.currentTimeMillis() - startTime
        ProviderLogger.e(TAG_WEBVIEW, "execute", "All retries exhausted", lastException,
            "attempts" to attempt,
            "durationMs" to durationMs
        )
        
        return StrategyResponse.failure(
            code = -1,
            error = lastException ?: Exception("All retries exhausted"),
            duration = durationMs,
            strategy = name
        )
    }
    
    /**
     * Execute a single request attempt using ConfigurableCloudflareKiller.
     */
    private suspend fun executeWithCfKiller(
        request: StrategyRequest,
        startTime: Long,
        attempt: Int
    ): StrategyResponse = withContext(Dispatchers.IO) {
        
        try {
            // Build headers (includes headers from request, cookies, UA, Referer)
            val headers = request.buildHeaders()
            
            ProviderLogger.d(TAG_WEBVIEW, "executeWithCfKiller", "Making request with CFK",
                "url" to request.url.take(80),
                "attempt" to attempt,
                "headerCount" to headers.size
            )
            
            // Make request with cfKiller as interceptor
            // This will automatically trigger WebView CF solving if needed
            val response: NiceResponse = app.get(
                url = request.url,
                headers = headers,
                interceptor = cfKiller
            )
            
            val durationMs = System.currentTimeMillis() - startTime
            val html = response.text
            
            // Check if still CF blocked after the request
            if (cfDetector.isCloudflareChallenge(response)) {
                ProviderLogger.w(TAG_WEBVIEW, "executeWithCfKiller", "CF still blocking after CFK attempt",
                    "code" to response.code,
                    "attempt" to attempt
                )
                return@withContext StrategyResponse.cloudflareBlocked(
                    code = response.code,
                    duration = durationMs,
                    strategy = name
                )
            }
            
            // Extract cookies from cfKiller's saved cookies
            val extractedCookies = extractCookiesFromCfKiller(request.url)
            
            ProviderLogger.i(TAG_WEBVIEW, "executeWithCfKiller", "Request successful",
                "code" to response.code,
                "htmlLength" to html.length,
                "cookieCount" to extractedCookies.size,
                "hasClearance" to extractedCookies.containsKey("cf_clearance"),
                "durationMs" to durationMs
            )
            
            StrategyResponse.success(
                html = html,
                code = response.code,
                cookies = extractedCookies,
                finalUrl = response.url,
                duration = durationMs,
                strategy = name
            )
            
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            ProviderLogger.e(TAG_WEBVIEW, "executeWithCfKiller", "Exception during request", e,
                "attempt" to attempt
            )
            StrategyResponse.failure(
                code = -1,
                error = e,
                duration = durationMs,
                strategy = name
            )
        }
    }
    
    /**
     * Extract cookies from ConfigurableCloudflareKiller's savedCookies.
     */
    private fun extractCookiesFromCfKiller(url: String): Map<String, String> {
        return try {
            val host = java.net.URI(url).host ?: return emptyMap()
            
            // Try cfKiller's saved cookies first
            val cfkCookies = cfKiller.savedCookies[host]
            if (!cfkCookies.isNullOrEmpty()) {
                ProviderLogger.d(TAG_WEBVIEW, "extractCookiesFromCfKiller", "Got cookies from CFK",
                    "host" to host,
                    "count" to cfkCookies.size
                )
                return cfkCookies
            }
            
            // Fallback: try to get from getCookieHeaders
            val headers = cfKiller.getCookieHeaders(url)
            val cookieHeader = headers["Cookie"]
            if (!cookieHeader.isNullOrEmpty()) {
                ProviderLogger.d(TAG_WEBVIEW, "extractCookiesFromCfKiller", "Got cookies from headers",
                    "host" to host
                )
                return ConfigurableCloudflareKiller.parseCookieMap(cookieHeader)
            }
            
            emptyMap()
        } catch (e: Exception) {
            ProviderLogger.e(TAG_WEBVIEW, "extractCookiesFromCfKiller", "Failed to extract cookies", e)
            emptyMap()
        }
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
    
    /**
     * Clears cookies from ConfigurableCloudflareKiller and System CookieManager.
     */
    fun clearCookies(url: String) {
        cfKiller.clearCookies(url)
        ProviderLogger.w(TAG_WEBVIEW, "clearCookies", "Cookies cleared",
            "url" to url.take(80)
        )
    }
}
