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
    
    /** Domain aliases - domains that share cookies with main domain */
    @Volatile
    private var domainAliases: MutableSet<String> = mutableSetOf()
    
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
     * Extract base domain name from full domain.
     * Strips subdomains and TLD to get the core name.
     * Examples:
     * - "qq.laroza.cfd" → "laroza"
     * - "a.b.c.laroza.cfd" → "laroza"
     * - "www.example.com" → "example"
     * - "example.co.uk" → "example"
     */
    fun extractBaseDomain(domain: String): String {
        // Remove protocol if present
        var cleanDomain = domain.removePrefix("https://").removePrefix("http://")
        
        // Remove port if present
        cleanDomain = cleanDomain.substringBefore(":")
        
        // Split by dots
        val parts = cleanDomain.split(".")
        
        return when {
            parts.size >= 2 -> {
                // Handle multi-part TLDs like .co.uk
                val tld = parts.takeLast(2).joinToString(".")
                if (tld in MULTI_PART_TLDS && parts.size >= 3) {
                    // For example.co.uk, return "example"
                    parts[parts.size - 3]
                } else {
                    // For example.com, return "example"
                    parts[parts.size - 2]
                }
            }
            parts.size == 1 -> parts[0]
            else -> cleanDomain
        }
    }
    
    /** Multi-part TLDs that need special handling */
    private val MULTI_PART_TLDS = setOf(
        "co.uk", "co.jp", "co.kr", "co.nz", "co.za",
        "com.au", "com.br", "com.mx", "com.sg", "com.hk",
        "org.uk", "net.uk", "gov.uk", "ac.uk",
        "or.jp", "ne.jp", "go.jp", "ac.jp"
    )
    
    /**
     * Check if two domains are related (share same base domain).
     * Examples:
     * - "qq.laroza.cfd" and "laroza.cfd" → true
     * - "a.b.laroza.cfd" and "www.laroza.cfd" → true
     * - "example.com" and "other.com" → false
     */
    fun areDomainsRelated(domain1: String, domain2: String): Boolean {
        val base1 = extractBaseDomain(domain1)
        val base2 = extractBaseDomain(domain2)
        val related = base1 == base2 && base1.isNotEmpty()
        
        ProviderLogger.d(TAG, "areDomainsRelated", "Domain comparison",
            "domain1" to domain1,
            "domain2" to domain2,
            "base1" to base1,
            "base2" to base2,
            "related" to related)
        
        return related
    }
    
    /**
     * Add a domain alias. Aliases share cookies with the main domain.
     * Called when a different subdomain is detected (e.g., qq.laroza.cfd vs laroza.cfd).
     */
    fun addDomainAlias(aliasDomain: String) {
        val mainDomain = currentSession?.domain
        if (mainDomain == null) {
            ProviderLogger.w(TAG, "addDomainAlias", "No main domain, cannot add alias",
                "alias" to aliasDomain)
            return
        }
        
        // Don't add if it's the same as main domain
        if (aliasDomain == mainDomain) return
        
        // Check if related
        if (!areDomainsRelated(aliasDomain, mainDomain)) {
            ProviderLogger.w(TAG, "addDomainAlias", "Domains not related, not adding alias",
                "alias" to aliasDomain,
                "main" to mainDomain)
            return
        }
        
        if (domainAliases.add(aliasDomain)) {
            ProviderLogger.i(TAG, "addDomainAlias", "Added domain alias",
                "alias" to aliasDomain,
                "main" to mainDomain,
                "totalAliases" to domainAliases.size)
        }
    }
    
    /**
     * Get cookies for a specific domain.
     * If domain is main domain or an alias, returns the session cookies.
     */
    fun getCookiesForDomain(domain: String): Map<String, String> {
        val session = currentSession ?: return emptyMap()
        val mainDomain = session.domain
        
        // Check if requesting domain is main domain or an alias
        val shouldReturnCookies = when {
            domain == mainDomain -> true
            domain in domainAliases -> true
            areDomainsRelated(domain, mainDomain) -> {
                // Auto-add as alias if related but not yet tracked
                addDomainAlias(domain)
                true
            }
            else -> false
        }
        
        if (shouldReturnCookies) {
            ProviderLogger.d(TAG, "getCookiesForDomain", "Returning cookies",
                "requestDomain" to domain,
                "mainDomain" to mainDomain,
                "isAlias" to (domain in domainAliases),
                "cookieCount" to session.cookies.size)
            return session.cookies
        }
        
        ProviderLogger.w(TAG, "getCookiesForDomain", "Domain not related to session",
            "requestDomain" to domain,
            "mainDomain" to mainDomain)
        return emptyMap()
    }
    
    /**
     * Get all domain aliases.
     */
    fun getDomainAliases(): Set<String> = domainAliases.toSet()
    
    /**
     * Clear all domain aliases.
     */
    fun clearDomainAliases() {
        val count = domainAliases.size
        domainAliases.clear()
        ProviderLogger.d(TAG, "clearDomainAliases", "Cleared all aliases", "count" to count)
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
     * For specific domain cookies, use getCookiesForDomain(domain).
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
