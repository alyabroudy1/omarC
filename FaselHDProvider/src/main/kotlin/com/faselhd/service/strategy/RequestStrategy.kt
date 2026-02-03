package com.faselhd.service.strategy

import com.lagradost.nicehttp.NiceResponse

/**
 * Strategy interface for HTTP request execution.
 * 
 * ## Purpose:
 * Abstracts the HTTP execution mechanism, allowing different
 * strategies (OkHttp, WebView, VideoSniffer) to be used interchangeably.
 * 
 * ## Strategy Pattern:
 * - DirectHttpStrategy: Fast OkHttp requests for valid sessions
 * - WebViewStrategy: Headless WebView for CF solving
 * - VideoSniffingStrategy: WebView with video URL monitoring
 * 
 * ## Selection:
 * Session manager selects strategy based on cookie validity.
 */
interface RequestStrategy {
    
    /**
     * Human-readable strategy name for logging.
     */
    val name: String
    
    /**
     * Executes an HTTP request.
     * 
     * @param request The request to execute
     * @return Response containing HTML content
     */
    suspend fun execute(request: StrategyRequest): StrategyResponse
    
    /**
     * Checks if this strategy can handle the given context.
     * Used for automatic strategy selection.
     * 
     * @param context Request context with session info
     * @return true if this strategy should be used
     */
    fun canHandle(context: RequestContext): Boolean
}

/**
 * Request data for strategy execution.
 */
data class StrategyRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap(),
    val userAgent: String,
    val referer: String? = null
) {
    /**
     * Builds the full headers map including cookies.
     */
    fun buildHeaders(): Map<String, String> {
        val result = headers.toMutableMap()
        result["User-Agent"] = userAgent
        if (referer != null) result["Referer"] = referer
        if (cookies.isNotEmpty()) {
            result["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        return result
    }
}

/**
 * Response from strategy execution.
 */
data class StrategyResponse(
    val success: Boolean,
    val html: String?,
    val responseCode: Int,
    val cookies: Map<String, String> = emptyMap(),
    val finalUrl: String? = null,
    val error: Throwable? = null,
    val durationMs: Long = 0,
    val strategyUsed: String = ""
) {
    companion object {
        fun success(html: String, code: Int, cookies: Map<String, String>, finalUrl: String, duration: Long, strategy: String) =
            StrategyResponse(true, html, code, cookies, finalUrl, null, duration, strategy)
        
        fun failure(code: Int, error: Throwable?, duration: Long, strategy: String) =
            StrategyResponse(false, null, code, emptyMap(), null, error, duration, strategy)
        
        fun cloudflareBlocked(code: Int, duration: Long, strategy: String) =
            StrategyResponse(false, null, code, emptyMap(), null, null, duration, strategy)
    }
    
    /**
     * True if response indicates Cloudflare challenge.
     */
    val isCloudflareChallenge: Boolean
        get() = responseCode in listOf(403, 503)
}

/**
 * Context for strategy selection.
 */
data class RequestContext(
    val url: String,
    val hasValidCookies: Boolean,
    val isVideoPage: Boolean = false,
    val forceWebView: Boolean = false
)
