package com.arabseed.service.session

/**
 * SINGLE SOURCE OF TRUTH for all session-related data.
 * 
 * Cloudflare binds cookies to User-Agent. This class ensures:
 * 1. UA is NEVER mixed between sources
 * 2. When domain changes, cookies are invalidated
 * 3. All components read from the same immutable state
 * 
 * Immutable by design - create new instances via `copy()` or helper methods.
 */
data class SessionState(
    /** The User-Agent used to acquire the current session */
    val userAgent: String,
    
    /** Cookies for the current domain (keyed by name) */
    val cookies: Map<String, String>,
    
    /** Current domain (without protocol, e.g., "asd.pics") */
    val domain: String,
    
    /** Timestamp when cookies were acquired (System.currentTimeMillis()) */
    val cookieTimestamp: Long,
    
    /** Was this session acquired via WebView CF solve? */
    val fromWebView: Boolean
) {
    companion object {

        /** 
         * UNIFIED USER-AGENT (From FaselHD)
         * Matches modern Android Chrome to pass Cloudflare checks.
         */
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"

        /** Cookie TTL - sessions expire after 30 minutes of inactivity */
        const val COOKIE_TTL_MS = 30 * 60 * 1000L
        
        /** Create initial state for a domain */
        fun initial(domain: String, userAgent: String = DEFAULT_UA): SessionState {
            return SessionState(
                userAgent = userAgent,
                cookies = emptyMap(),
                domain = domain,
                cookieTimestamp = 0L,
                fromWebView = false
            )
        }
    }
    
    /** Check if session cookies are expired */
    fun isExpired(): Boolean {
        if (cookies.isEmpty()) return true
        return System.currentTimeMillis() - cookieTimestamp > COOKIE_TTL_MS
    }
    
    /** Check if we have cf_clearance cookie */
    fun hasClearance(): Boolean = cookies.containsKey("cf_clearance")
    
    /** Check if session appears valid (has cookies and not expired) */
    fun isValid(): Boolean = cookies.isNotEmpty() && !isExpired()
    
    /** Build Cookie header string */
    fun buildCookieHeader(): String? {
        if (cookies.isEmpty()) return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
    
    /** Build all headers for HTTP requests */
    fun buildHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Referer", "https://$domain/")
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        put("Accept-Language", "en-US,en;q=0.9")
        // Critical Client Hints for Chrome 120+ spoofing
        put("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
        put("Sec-Ch-Ua-Mobile", "?1")
        put("Sec-Ch-Ua-Platform", "\"Android\"")
        put("Upgrade-Insecure-Requests", "1")
        put("Sec-Fetch-Dest", "document")
        put("Sec-Fetch-Mode", "navigate")
        put("Sec-Fetch-Site", "none")
        put("Sec-Fetch-User", "?1")
        
        buildCookieHeader()?.let { put("Cookie", it) }
    }
    
    /** Create new state with updated cookies (keeps same UA - critical for CF) */
    fun withCookies(newCookies: Map<String, String>, fromWebView: Boolean = false): SessionState {
        return copy(
            cookies = newCookies,
            cookieTimestamp = System.currentTimeMillis(),
            fromWebView = fromWebView
        )
    }
    
    /** Create new state with updated domain (clears cookies - domain change invalidates session) */
    fun withDomain(newDomain: String): SessionState {
        if (newDomain == domain) return this
        return copy(
            domain = newDomain,
            cookies = emptyMap(),
            cookieTimestamp = 0L,
            fromWebView = false
        )
    }
    
    /** Create new state with merged cookies (useful for incremental cookie updates) */
    fun mergeCookies(additionalCookies: Map<String, String>, fromWebView: Boolean = false): SessionState {
        return copy(
            cookies = cookies + additionalCookies,
            cookieTimestamp = System.currentTimeMillis(),
            fromWebView = fromWebView
        )
    }
    
    /** Clear cookies (invalidate session) */
    fun invalidate(): SessionState {
        return copy(
            cookies = emptyMap(),
            cookieTimestamp = 0L,
            fromWebView = false
        )
    }
    
    override fun toString(): String {
        return "SessionState(domain=$domain, cookies=${cookies.keys}, valid=${isValid()}, fromWebView=$fromWebView)"
    }
}
