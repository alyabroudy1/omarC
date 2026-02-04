package com.cloudstream.shared.session

import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_SESSION
import com.cloudstream.shared.provider.UNIFIED_USER_AGENT

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
        /** Cookie TTL - sessions expire after 30 minutes of inactivity */
        const val COOKIE_TTL_MS = 30 * 60 * 1000L
        
        /** Create initial state for a domain */
        fun initial(domain: String, userAgent: String = UNIFIED_USER_AGENT): SessionState {
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
    fun buildHeaders(): Map<String, String> {
        ProviderLogger.d(TAG_SESSION, "buildHeaders", "Building request headers",
            "domain" to domain,
            "cookieCount" to cookies.size,
            "hasClearance" to hasClearance(),
            "isValid" to isValid(),
            "uaHash" to userAgent.hashCode()
        )
        
        return buildMap {
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
            
            buildCookieHeader()?.let { 
                put("Cookie", it) 
                ProviderLogger.d(TAG_SESSION, "buildHeaders", "Cookie header set", "keys" to cookies.keys.toString())
            }
        }
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

/**
 * Snapshot for external state communication.
 */
data class SessionSnapshot(
    val domain: String,
    val hasCookies: Boolean,
    val hasClearance: Boolean,
    val cookieAgeMs: Long?,
    val isValid: Boolean
) {
    companion object {
        fun from(state: SessionState) = SessionSnapshot(
            domain = state.domain,
            hasCookies = state.cookies.isNotEmpty(),
            hasClearance = state.hasClearance(),
            cookieAgeMs = if (state.cookieTimestamp > 0) System.currentTimeMillis() - state.cookieTimestamp else null,
            isValid = state.isValid()
        )
    }
}
