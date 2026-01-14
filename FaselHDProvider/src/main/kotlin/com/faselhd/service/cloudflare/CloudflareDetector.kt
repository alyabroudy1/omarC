package com.faselhd.service.cloudflare

import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_CF_DETECTOR
import com.lagradost.nicehttp.NiceResponse

/**
 * Detects Cloudflare challenges in responses.
 * 
 * ## Detection Criteria (Language-Independent):
 * - Response code: 403 or 503
 * - Server header contains "cloudflare"
 * - Body contains CF challenge markers
 * - Cookie is missing cf_clearance
 * 
 * ## Usage:
 * ```kotlin
 * val detector = CloudflareDetector()
 * if (detector.isCloudflareChallenge(response)) {
 *     // Trigger WebView solve
 * }
 * ```
 */
class CloudflareDetector {
    
    companion object {
        /** Cloudflare server header markers */
        private val CF_SERVER_MARKERS = listOf("cloudflare", "cloudflare-nginx")
        
        /** Response codes that indicate CF challenge */
        private val CF_RESPONSE_CODES = listOf(403, 503)
        
        /** HTML body markers for CF challenge pages */
        private val CF_BODY_MARKERS = listOf(
            "cdn-cgi/challenge-platform",
            "cf-browser-verification",
            "cf_chl_prog",
            "challenge-running",
            "challenge-form",
            "jschl-answer",
            "_cf_chl_opt"
        )
        
        /** URL path markers for CF challenge */
        private val CF_URL_MARKERS = listOf(
            "/cdn-cgi/challenge-platform",
            "/cdn-cgi/l/chk_jschl"
        )
    }
    
    /**
     * Checks if a response indicates a Cloudflare challenge.
     * 
     * @param response The HTTP response to check
     * @return true if CF challenge detected
     */
    fun isCloudflareChallenge(response: NiceResponse): Boolean {
        val code = response.code
        val serverHeader = response.headers["Server"] ?: ""
        val hasCloudflareServer = CF_SERVER_MARKERS.any { serverHeader.contains(it, ignoreCase = true) }
        
        // Fast path: Check code and server header first
        if (code !in CF_RESPONSE_CODES && !hasCloudflareServer) {
            return false
        }
        
        // Check body for challenge markers
        val body = try { response.text } catch (e: Exception) { "" }
        val hasBodyMarker = CF_BODY_MARKERS.any { body.contains(it, ignoreCase = true) }
        
        val isChallenge = (code in CF_RESPONSE_CODES && hasCloudflareServer) || hasBodyMarker
        
        ProviderLogger.d(TAG_CF_DETECTOR, "isCloudflareChallenge", "Detection result",
            "isChallenge" to isChallenge,
            "code" to code,
            "hasCloudflareServer" to hasCloudflareServer,
            "hasBodyMarker" to hasBodyMarker
        )
        
        return isChallenge
    }
    
    /**
     * Checks if a URL is a Cloudflare challenge URL.
     * 
     * @param url The URL to check
     * @return true if this is a CF challenge URL
     */
    fun isChallengeUrl(url: String): Boolean {
        val isChallenge = CF_URL_MARKERS.any { url.contains(it, ignoreCase = true) }
        
        if (isChallenge) {
            ProviderLogger.d(TAG_CF_DETECTOR, "isChallengeUrl", "Challenge URL detected",
                "url" to url.take(80)
            )
        }
        
        return isChallenge
    }
    
    /**
     * Checks if a cookie string contains cf_clearance.
     * 
     * @param cookies Cookie header string or individual cookie
     * @return true if cf_clearance is present
     */
    fun hasClearanceCookie(cookies: String?): Boolean {
        if (cookies.isNullOrEmpty()) return false
        
        val hasClearance = cookies.contains("cf_clearance")
        
        ProviderLogger.d(TAG_CF_DETECTOR, "hasClearanceCookie", "Clearance check",
            "hasClearance" to hasClearance
        )
        
        return hasClearance
    }
    
    /**
     * Checks if cookies map contains cf_clearance.
     */
    fun hasClearanceCookie(cookies: Map<String, String>): Boolean {
        val hasClearance = cookies.containsKey("cf_clearance")
        
        ProviderLogger.d(TAG_CF_DETECTOR, "hasClearanceCookie", "Clearance check (map)",
            "hasClearance" to hasClearance,
            "cookieCount" to cookies.size
        )
        
        return hasClearance
    }
    
    /**
     * Checks if an HTML body contains Cloudflare challenge markers.
     * Useful for checking WebView content.
     * 
     * @param html The HTML content to check
     * @return true if CF challenge markers found
     */
    fun isChallengePage(html: String): Boolean {
        val isChallenge = CF_BODY_MARKERS.any { html.contains(it, ignoreCase = true) }
        
        ProviderLogger.d(TAG_CF_DETECTOR, "isChallengePage", "Page check",
            "isChallenge" to isChallenge,
            "htmlLength" to html.length
        )
        
        return isChallenge
    }
}
