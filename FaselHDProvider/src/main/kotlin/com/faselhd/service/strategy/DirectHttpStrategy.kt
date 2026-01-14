package com.faselhd.service.strategy

import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_DIRECT_HTTP
import com.lagradost.cloudstream3.app

/**
 * Direct HTTP request strategy using OkHttp.
 * 
 * ## Purpose:
 * Fast path for requests when valid session cookies are available.
 * Does NOT solve Cloudflare challenges - fails fast on 403/503.
 * 
 * ## When to Use:
 * - Session cookies are valid and fresh
 * - No CF challenge expected
 * 
 * ## Behavior on CF Challenge:
 * Returns StrategyResponse with isCloudflareChallenge=true.
 * Session manager should fallback to WebViewStrategy.
 * 
 * ## Logging:
 * - Request start with URL and headers
 * - Response code and timing
 * - CF challenge detection
 */
class DirectHttpStrategy : RequestStrategy {
    
    override val name: String = "DirectHttp"
    
    override suspend fun execute(request: StrategyRequest): StrategyResponse {
        val startTime = System.currentTimeMillis()
        
        ProviderLogger.d(TAG_DIRECT_HTTP, "execute", "Request starting",
            "url" to request.url.take(80),
            "hasCookies" to request.cookies.isNotEmpty()
        )
        
        return try {
            val headers = request.buildHeaders()
            
            ProviderLogger.d(TAG_DIRECT_HTTP, "execute", "Headers built",
                "headerCount" to headers.size,
                "hasCookie" to headers.containsKey("Cookie"),
                "hasUA" to headers.containsKey("User-Agent")
            )
            
            val response = app.get(request.url, headers = headers)
            val durationMs = System.currentTimeMillis() - startTime
            
            ProviderLogger.i(TAG_DIRECT_HTTP, "execute", "Response received",
                "code" to response.code,
                "durationMs" to durationMs,
                "url" to request.url.take(80)
            )
            
            // Check for Cloudflare challenge
            if (response.code in listOf(403, 503)) {
                val serverHeader = response.headers["Server"] ?: ""
                val isCloudflare = serverHeader.contains("cloudflare", ignoreCase = true)
                
                ProviderLogger.w(TAG_DIRECT_HTTP, "execute", "CF challenge detected",
                    "code" to response.code,
                    "server" to serverHeader,
                    "isCloudflare" to isCloudflare
                )
                
                return StrategyResponse.cloudflareBlocked(response.code, durationMs, name)
            }
            
            // Success
            val html = response.text
            val finalUrl = response.url
            
            // Extract any new cookies from response
            val responseCookies = extractCookiesFromResponse(response)
            
            ProviderLogger.i(TAG_DIRECT_HTTP, "execute", "Request successful",
                "code" to response.code,
                "durationMs" to durationMs,
                "htmlLength" to html.length,
                "newCookies" to responseCookies.size
            )
            
            StrategyResponse.success(
                html = html,
                code = response.code,
                cookies = responseCookies,
                finalUrl = finalUrl,
                duration = durationMs,
                strategy = name
            )
            
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            
            ProviderLogger.e(TAG_DIRECT_HTTP, "execute", "Request failed", e,
                "url" to request.url.take(80),
                "durationMs" to durationMs
            )
            
            StrategyResponse.failure(
                code = -1,
                error = e,
                duration = durationMs,
                strategy = name
            )
        }
    }
    
    override fun canHandle(context: RequestContext): Boolean {
        // DirectHttp can handle if we have valid cookies and no force WebView
        val canHandle = context.hasValidCookies && !context.forceWebView && !context.isVideoPage
        
        ProviderLogger.d(TAG_DIRECT_HTTP, "canHandle", "Strategy check",
            "canHandle" to canHandle,
            "hasValidCookies" to context.hasValidCookies,
            "forceWebView" to context.forceWebView,
            "isVideoPage" to context.isVideoPage
        )
        
        return canHandle
    }
    
    /**
     * Extracts cookies from response Set-Cookie headers.
     */
    private fun extractCookiesFromResponse(response: com.lagradost.nicehttp.NiceResponse): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        
        try {
            // Get Set-Cookie headers
            response.headers.values("Set-Cookie").forEach { setCookie ->
                // Parse "name=value; path=...; ..."
                val parts = setCookie.split(";").firstOrNull()?.split("=", limit = 2)
                if (parts != null && parts.size == 2) {
                    val name = parts[0].trim()
                    val value = parts[1].trim()
                    if (name.isNotEmpty()) {
                        cookies[name] = value
                    }
                }
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG_DIRECT_HTTP, "extractCookies", "Failed to parse cookies", e)
        }
        
        return cookies
    }
}
