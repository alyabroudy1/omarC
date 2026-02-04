package com.cloudstream.shared.strategy

import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_DIRECT_HTTP
import com.lagradost.cloudstream3.app

/**
 * Direct HTTP request strategy using OkHttp.
 * Fast path for requests when valid session cookies are available.
 */
class DirectHttpStrategy : RequestStrategy {
    
    override val name: String = "DirectHttp"
    
    override suspend fun execute(request: StrategyRequest): StrategyResponse {
        val startTime = System.currentTimeMillis()
        
        ProviderLogger.d(TAG_DIRECT_HTTP, "execute", "Request starting",
            "url" to request.url.take(80), "hasCookies" to request.cookies.isNotEmpty())
        
        return try {
            // Force HTTP/1.1 for better CF compatibility
            val protocols = listOf(okhttp3.Protocol.HTTP_1_1)
            val directClient = app.baseClient.newBuilder()
                .protocols(protocols)
                .build()
            
            val headersMap = request.buildHeaders()
            val headerBuilder = okhttp3.Headers.Builder()
            headersMap.forEach { (k, v) -> headerBuilder.add(k, v) }
            
            val okRequest = okhttp3.Request.Builder()
                .url(request.url)
                .headers(headerBuilder.build())
                .get()
                .build()
            
            val response = directClient.newCall(okRequest).execute()
            val durationMs = System.currentTimeMillis() - startTime
            
            // Check for Cloudflare challenge
            if (response.code in listOf(403, 503)) {
                val serverHeader = response.header("Server") ?: ""
                val isCloudflare = serverHeader.contains("cloudflare", ignoreCase = true)
                
                ProviderLogger.w(TAG_DIRECT_HTTP, "execute", "CF challenge detected",
                    "code" to response.code, "isCloudflare" to isCloudflare)
                
                response.close()
                return StrategyResponse.cloudflareBlocked(response.code, durationMs, name)
            }
            
            val html = response.body?.string() ?: ""
            val finalUrl = response.request.url.toString()
            val responseCookies = extractCookiesFromResponse(response)
            
            ProviderLogger.i(TAG_DIRECT_HTTP, "execute", "Request successful",
                "code" to response.code, "durationMs" to durationMs, "htmlLength" to html.length)
            
            StrategyResponse.success(html, response.code, responseCookies, finalUrl, durationMs, name)
            
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            ProviderLogger.e(TAG_DIRECT_HTTP, "execute", "Request failed", e, "url" to request.url.take(80))
            StrategyResponse.failure(-1, e, durationMs, name)
        }
    }
    
    override fun canHandle(context: RequestContext): Boolean {
        return context.hasValidCookies && !context.forceWebView && !context.isVideoPage
    }
    
    private fun extractCookiesFromResponse(response: okhttp3.Response): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        try {
            response.headers("Set-Cookie").forEach { setCookie ->
                val parts = setCookie.split(";").firstOrNull()?.split("=", limit = 2)
                if (parts != null && parts.size == 2) {
                    val name = parts[0].trim()
                    val value = parts[1].trim()
                    if (name.isNotEmpty()) cookies[name] = value
                }
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG_DIRECT_HTTP, "extractCookies", "Failed to parse cookies", e)
        }
        return cookies
    }
}
