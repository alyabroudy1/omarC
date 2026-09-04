package com.cloudstream.shared.session

/**
 * The provider's session, which after Wave 2 is only its domain.
 *
 * Cookies used to live here as a flat, unscoped `Map<String,String>`. They now live in
 * `android.webkit.CookieManager` — the one store every WebView already uses and, via
 * [com.cloudstream.shared.core.SystemCookieJar], the one OkHttp uses too. Scope is whatever
 * `Set-Cookie` said.
 *
 * Immutable by design - create new instances via `copy()` or helper methods.
 */
data class SessionState(
    /** Current domain (without protocol, e.g., "asd.pics") */
    val domain: String
) {
    companion object {
        /** Create initial state for a domain */
        fun initial(domain: String): SessionState = SessionState(domain = domain)
    }

    /** Create new state with updated domain */
    fun withDomain(newDomain: String): SessionState {
        if (newDomain == domain) return this
        return copy(domain = newDomain)
    }

    override fun toString(): String = "SessionState(domain=$domain)"
}
