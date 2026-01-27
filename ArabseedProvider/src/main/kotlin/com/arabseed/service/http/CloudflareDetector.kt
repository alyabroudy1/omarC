package com.arabseed.service.http

/**
 * Detects Cloudflare challenges from response codes and HTML patterns.
 */
object CloudflareDetector {
    private val CF_HTML_PATTERNS = listOf(
        "challenge-platform",
        "cf-browser-verification",
        "Just a moment",
        "Checking your browser",
        "cf-chl-bypass",
        "cf_clearance",
        "Attention Required",
        "_cf_chl_opt"
    )
    
    private val CF_RESPONSE_CODES = listOf(403, 503, 429)
    
    /**
     * Check if HTML content indicates a Cloudflare challenge page.
     */
    fun isCloudflareChallenge(html: String?): Boolean {
        if (html == null) return false
        return CF_HTML_PATTERNS.any { pattern ->
            html.contains(pattern, ignoreCase = true)
        }
    }
    
    /**
     * Check if response code indicates potential Cloudflare blocking.
     */
    fun isCloudflareResponse(code: Int): Boolean {
        return code in CF_RESPONSE_CODES
    }
    
    /**
     * Combined check using both code and HTML.
     */
    fun isBlocked(code: Int, html: String?): Boolean {
        return isCloudflareResponse(code) || isCloudflareChallenge(html)
    }
}
