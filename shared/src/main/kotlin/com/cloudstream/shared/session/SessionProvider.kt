package com.cloudstream.shared.session

import com.cloudstream.shared.logging.ProviderLogger

/**
 * SINGLE SOURCE OF TRUTH for all session data across the provider.
 * 
 * This singleton ensures that ALL components (HttpService, LazyExtractor, SnifferExtractor)
 * use the EXACT same User-Agent and cookies when making requests.
 * 
 * Cloudflare binds cookies to User-Agent, so any mismatch causes 403 errors.
 */
object SessionProvider {
    private const val TAG = "SessionProvider"
    
    @Volatile
    private var currentSession: SessionState? = null
    
    /**
     * Initialize the session provider with a session state.
     * Called by ProviderHttpService after CF challenge is solved.
     */
    fun initialize(session: SessionState) {
        ProviderLogger.d(TAG, "initialize", "Session initialized",
            "domain" to session.domain,
            "hasCookies" to session.cookies.isNotEmpty(),
            "uaHash" to session.userAgent.hashCode())
        currentSession = session
    }
    
    /**
     * Update the current session with new data (e.g., after WebView sniffing).
     */
    fun update(session: SessionState) {
        ProviderLogger.d(TAG, "update", "Session updated",
            "domain" to session.domain,
            "hasCookies" to session.cookies.isNotEmpty())
        currentSession = session
    }
    
    /**
     * Get the current session state.
     * Returns null if not initialized.
     */
    fun getSession(): SessionState? = currentSession
    
    /**
     * Get the current User-Agent.
     * Falls back to unified UA if no session.
     */
    fun getUserAgent(): String {
        return currentSession?.userAgent ?: run {
            ProviderLogger.w(TAG, "getUserAgent", "No session, using default UA")
            com.cloudstream.shared.provider.UNIFIED_USER_AGENT
        }
    }
    
    /**
     * Get the current cookies map.
     * Returns empty map if no session.
     */
    fun getCookies(): Map<String, String> {
        return currentSession?.cookies ?: emptyMap()
    }
    
    /**
     * Build a Cookie header string from current cookies.
     */
    fun buildCookieHeader(): String? {
        val cookies = currentSession?.cookies
        if (cookies.isNullOrEmpty()) return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
    
    /**
     * Get the current domain.
     */
    fun getDomain(): String? = currentSession?.domain
    
    /**
     * Check if we have a valid session with cookies.
     */
    fun hasValidSession(): Boolean {
        val session = currentSession
        return session != null && session.cookies.isNotEmpty() && !session.isExpired()
    }
    
    /**
     * Clear the current session.
     */
    fun clear() {
        ProviderLogger.d(TAG, "clear", "Session cleared")
        currentSession = null
    }
}
