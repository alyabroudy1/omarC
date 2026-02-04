package com.cloudstream.shared.strategy

import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * Abstract request strategy interface.
 */
interface RequestStrategy {
    val name: String
    suspend fun execute(request: StrategyRequest): StrategyResponse
    fun canHandle(context: RequestContext): Boolean
}

/**
 * Request context for strategy selection.
 */
data class RequestContext(
    val url: String,
    val hasValidCookies: Boolean,
    val forceWebView: Boolean = false,
    val isVideoPage: Boolean = false
)

/**
 * Request data for strategies.
 */
data class StrategyRequest(
    val url: String,
    val userAgent: String,
    val cookies: Map<String, String>,
    val referer: String,
    val headers: Map<String, String> = emptyMap()
) {
    fun buildHeaders(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // Standard browser headers
        result["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        result["Accept-Language"] = "en-US,en;q=0.9"
        result["Upgrade-Insecure-Requests"] = "1"
        result["Sec-Fetch-Dest"] = "document"
        result["Sec-Fetch-Mode"] = "navigate"
        result["Sec-Fetch-Site"] = "none"
        result["Sec-Fetch-User"] = "?1"
        
        // Request-specific headers
        result.putAll(headers)
        result["User-Agent"] = userAgent
        if (referer.isNotBlank()) result["Referer"] = referer
        
        // Cookies
        if (cookies.isNotEmpty()) {
            result["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        
        return result
    }
}

/**
 * Strategy execution response.
 */
data class StrategyResponse(
    val success: Boolean,
    val html: String?,
    val responseCode: Int,
    val cookies: Map<String, String>,
    val finalUrl: String?,
    val isCloudflareChallenge: Boolean,
    val durationMs: Long,
    val strategy: String,
    val error: Throwable? = null
) {
    companion object {
        fun success(html: String, code: Int, cookies: Map<String, String>, finalUrl: String?, duration: Long, strategy: String) =
            StrategyResponse(true, html, code, cookies, finalUrl, false, duration, strategy)
        
        fun failure(code: Int, error: Throwable, duration: Long, strategy: String) =
            StrategyResponse(false, null, code, emptyMap(), null, false, duration, strategy, error)
        
        fun cloudflareBlocked(code: Int, duration: Long, strategy: String) =
            StrategyResponse(false, null, code, emptyMap(), null, true, duration, strategy)
    }
}

/**
 * Captured video source.
 */
data class VideoSource(
    val url: String,
    val label: String,
    val headers: Map<String, String>,
    val type: ExtractorLinkType
)
