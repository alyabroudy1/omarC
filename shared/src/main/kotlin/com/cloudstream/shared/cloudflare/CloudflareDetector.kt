package com.cloudstream.shared.cloudflare

import com.lagradost.nicehttp.NiceResponse

/**
 * Detects Cloudflare challenges in HTTP responses.
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
        "_cf_chl_opt",
        "cf_chl_opt",
        "__cf_chl",
        "ray_id",
        "cf-wrapper"
    )
    
    private val CF_RESPONSE_CODES = listOf(403, 503, 429)
    
    /**
     * Check if response indicates a Cloudflare challenge.
     */
    fun isCloudflareChallenge(response: NiceResponse): Boolean {
        val code = response.code
        val html = try { response.text } catch (e: Exception) { "" }
        
        // Check response code
        if (code !in CF_RESPONSE_CODES) return false
        
        // Check Server header
        val isCloudflareServer = response.headers["Server"]?.contains("cloudflare", ignoreCase = true) == true
        
        // Check HTML content for CF markers
        val hasCfMarkers = CF_HTML_PATTERNS.any { html.contains(it, ignoreCase = true) }
        
        return isCloudflareServer || hasCfMarkers
    }
    
    /**
     * Check if HTML content indicates CF challenge without response object.
     */
    fun isCloudflareChallenge(html: String?, responseCode: Int = 403): Boolean {
        if (html.isNullOrBlank()) return false
        
        // If response code doesn't suggest CF, check patterns anyway (for WebView usage)
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
     * Requires BOTH a CF response code AND CF markers in HTML.
     * This prevents non-CF 403s (like Akwam's anti-bot) from triggering CF solve.
     */
    fun isBlocked(code: Int, html: String?): Boolean {
        if (!isCloudflareResponse(code)) return false
        // Must also have CF-specific markers in the HTML
        return isCloudflareChallenge(html)
    }
    
    /**
     * Check if HTML indicates successful page load (not blocked).
     */
    fun isSuccessfulLoad(html: String, validationStrings: List<String>): Boolean {
        if (html.isBlank()) return false
        
        // Check for any validation string
        return validationStrings.any { html.contains(it, ignoreCase = true) }
    }
    
    /**
     * Check if HTML looks like real provider content vs an error/blocked page.
     * Used as secondary validation after CF challenge check passes.
     * Short pages or pages containing block indicators are considered non-real.
     */
    fun isRealContent(html: String): Boolean {
        if (html.length < 512) return false
        // Real pages have meaningful DOM structure and don't contain block indicators
        return html.contains("<div", ignoreCase = true) &&
               !html.contains("Access Denied", ignoreCase = true)
    }
}
