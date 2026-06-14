package com.cloudstream.shared.strategy

import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_DIRECT_HTTP
import com.cloudstream.shared.network.ChromiumFetcher
import com.lagradost.cloudstream3.app

/**
 * Direct HTTP request strategy using OkHttp with Chrome-TLS fallback.
 *
 * Fast path: OkHttp (low latency, standard TLS fingerprint).
 * Fallback: ChromiumFetcher (higher latency, Chrome-identical TLS fingerprint).
 *
 * The fallback triggers when OkHttp returns a 403 from a Cloudflare server
 * AND the request included valid cookies + browser-grade headers. This pattern
 * indicates a Tier 3 TLS fingerprint block — Cloudflare rejected the request
 * purely because OkHttp's TLS handshake doesn't match a real browser.
 */
class DirectHttpStrategy(
    private val chromiumFetcher: ChromiumFetcher? = null
) : RequestStrategy {
    
    override val name: String = "DirectHttp"
    
    override suspend fun execute(request: StrategyRequest): StrategyResponse {
        val startTime = System.currentTimeMillis()
        
        ProviderLogger.d(TAG_DIRECT_HTTP, "execute", "Request starting",
            "url" to request.url.take(80), "hasCookies" to request.cookies.isNotEmpty())
        
        return try {
            val okHttpResult = executeViaOkHttp(request)
            val durationMs = System.currentTimeMillis() - startTime

            // Detect Tier 3 TLS fingerprint block:
            // - Cloudflare 403 despite valid cookies and browser-grade headers
            // - This means OkHttp's TLS handshake was fingerprinted and rejected
            if (okHttpResult.isCloudflareChallenge && request.cookies.isNotEmpty() && chromiumFetcher != null) {
                ProviderLogger.w(TAG_DIRECT_HTTP, "execute",
                    "🔒 Tier 3 TLS block detected — retrying via Chrome TLS stack",
                    "url" to request.url.take(80), "okHttpDurationMs" to durationMs)

                val chromiumResult = executeViaChromium(request, chromiumFetcher)
                val totalDurationMs = System.currentTimeMillis() - startTime

                if (chromiumResult.success) {
                    ProviderLogger.i(TAG_DIRECT_HTTP, "execute",
                        "✅ Chrome TLS fallback succeeded",
                        "url" to request.url.take(80),
                        "totalDurationMs" to totalDurationMs,
                        "htmlLength" to (chromiumResult.html?.length ?: 0))
                    return chromiumResult.copy(strategy = "$name+ChromeTLS")
                } else {
                    ProviderLogger.w(TAG_DIRECT_HTTP, "execute",
                        "Chrome TLS fallback also failed",
                        "code" to chromiumResult.responseCode)
                }
            }
            
            // Return OkHttp result (success or non-TLS failure)
            okHttpResult.copy(durationMs = durationMs)
            
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            ProviderLogger.e(TAG_DIRECT_HTTP, "execute", "Request failed", e, "url" to request.url.take(80))
            StrategyResponse.failure(-1, e, durationMs, name)
        }
    }
    
    override fun canHandle(context: RequestContext): Boolean {
        return context.hasValidCookies && !context.forceWebView && !context.isVideoPage
    }

    // ==================== OkHttp Path ====================

    private fun executeViaOkHttp(request: StrategyRequest): StrategyResponse {
        val protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        val directClient = app.baseClient.newBuilder()
            .protocols(protocols)
            .build()
        
        val headersMap = request.buildHeaders()
        val headerBuilder = okhttp3.Headers.Builder()
        for ((k, v) in headersMap) {
            headerBuilder.add(k, v)
        }
        
        val okRequest = okhttp3.Request.Builder()
            .url(request.url)
            .headers(headerBuilder.build())
            .get()
            .build()
        
        val response = directClient.newCall(okRequest).execute()
        
        // Check for Cloudflare challenge
        if (response.code in listOf(403, 503)) {
            val serverHeader = response.header("Server") ?: ""
            val isCloudflare = serverHeader.contains("cloudflare", ignoreCase = true)
            
            ProviderLogger.w(TAG_DIRECT_HTTP, "executeViaOkHttp", "CF challenge detected",
                "code" to response.code, "isCloudflare" to isCloudflare)
            
            response.close()
            return StrategyResponse.cloudflareBlocked(response.code, 0, name)
        }
        
        val html = response.body?.string() ?: ""
        val finalUrl = response.request.url.toString()
        val responseCookies = extractCookiesFromResponse(response)
        
        ProviderLogger.i(TAG_DIRECT_HTTP, "executeViaOkHttp", "Request successful",
            "code" to response.code, "htmlLength" to html.length)
        
        return StrategyResponse.success(html, response.code, responseCookies, finalUrl, 0, name)
    }

    // ==================== Chrome TLS Fallback Path ====================

    private suspend fun executeViaChromium(
        request: StrategyRequest,
        fetcher: ChromiumFetcher
    ): StrategyResponse {
        val headers = request.buildHeaders()
        val response = fetcher.fetch(request.url, headers)

        return if (response.success) {
            StrategyResponse.success(
                response.body, response.statusCode,
                response.cookies, response.finalUrl, 0, "$name+ChromeTLS"
            )
        } else if (response.isCloudflareBlocked) {
            StrategyResponse.cloudflareBlocked(response.statusCode, 0, "$name+ChromeTLS")
        } else {
            StrategyResponse.failure(
                response.statusCode,
                Exception(response.error ?: "ChromiumFetcher failed"),
                0, "$name+ChromeTLS"
            )
        }
    }

    // ==================== Helpers ====================
    
    private fun extractCookiesFromResponse(response: okhttp3.Response): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        try {
            val cookieHeaders = response.headers("Set-Cookie")
            for (setCookie in cookieHeaders) {
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
