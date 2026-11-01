package com.cloudstream.shared.cloudflare

import com.lagradost.nicehttp.NiceResponse

/**
 * Classification of Cloudflare block responses.
 *
 * Distinguishes between:
 * - [NONE]: Normal response (not blocked by Cloudflare).
 * - [PASSIVE_TLS_BLOCK]: Server/WAF rejected the request based on TLS fingerprint (JA3/JA4)
 *   or missing headers, without serving an interactive JS/Turnstile challenge. Can be bypassed
 *   via a single Chrome-TLS fetch (`ChromiumFetcher`).
 * - [ACTIVE_CHALLENGE]: Server returned an interactive Turnstile / JS Proof-of-Work challenge
 *   (`cf-mitigated: challenge`, `_cf_chl_opt`, `challenge-platform`, etc.) that requires
 *   WebView session execution in `CfBypassEngine`.
 */
enum class CfBlockType {
    NONE,
    PASSIVE_TLS_BLOCK,
    ACTIVE_CHALLENGE
}

/**
 * Detects and classifies Cloudflare challenges in HTTP responses.
 */
object CloudflareDetector {

    /**
     * Markers that specifically indicate an active JS / Turnstile / Managed challenge page
     * requiring script execution and/or user interaction in `CfBypassEngine`.
     */
    private val ACTIVE_CHALLENGE_PATTERNS = listOf(
        "challenge-platform",
        "cf-browser-verification",
        "Just a moment",
        "Checking your browser",
        "cf-chl-bypass",
        "_cf_chl_opt",
        "cf_chl_opt",
        "__cf_chl",
        "cf-turnstile"
    )

    /**
     * Passive Cloudflare block/error page markers (e.g. WAF 403 or TLS fingerprint rejection).
     */
    private val PASSIVE_CF_PATTERNS = listOf(
        "cf_clearance",
        "Attention Required",
        "ray_id",
        "cf-wrapper"
    )

    private val CF_HTML_PATTERNS = ACTIVE_CHALLENGE_PATTERNS + PASSIVE_CF_PATTERNS

    private val CF_RESPONSE_CODES = listOf(403, 503, 429)

    /**
     * Classify an HTTP response into a [CfBlockType].
     */
    fun classifyBlock(
        code: Int,
        html: String?,
        cfMitigatedHeader: String? = null,
        serverHeader: String? = null
    ): CfBlockType {
        if (!isCloudflareResponse(code)) return CfBlockType.NONE

        if (cfMitigatedHeader?.contains("challenge", ignoreCase = true) == true) {
            return CfBlockType.ACTIVE_CHALLENGE
        }

        if (!html.isNullOrBlank()) {
            if (ACTIVE_CHALLENGE_PATTERNS.any { html.contains(it, ignoreCase = true) }) {
                return CfBlockType.ACTIVE_CHALLENGE
            }
            if (PASSIVE_CF_PATTERNS.any { html.contains(it, ignoreCase = true) }) {
                return CfBlockType.PASSIVE_TLS_BLOCK
            }
        }

        if (serverHeader?.contains("cloudflare", ignoreCase = true) == true && isBlocked(code, html)) {
            return CfBlockType.PASSIVE_TLS_BLOCK
        }

        return CfBlockType.NONE
    }

    /**
     * True if the response represents an active JS/Turnstile challenge rather than a passive block.
     */
    fun isActiveChallenge(html: String?, cfMitigatedHeader: String? = null): Boolean {
        if (cfMitigatedHeader?.contains("challenge", ignoreCase = true) == true) return true
        if (html.isNullOrBlank()) return false
        return ACTIVE_CHALLENGE_PATTERNS.any { html.contains(it, ignoreCase = true) }
    }

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
