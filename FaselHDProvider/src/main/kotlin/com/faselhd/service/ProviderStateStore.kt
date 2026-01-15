package com.faselhd.service

import android.content.Context
import android.content.SharedPreferences
import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_STATE_STORE

/**
 * Persists provider session state across app restarts.
 * 
 * ## Stored Data:
 * - Current domain URL
 * - User-Agent string
 * - Cookie string (serialized)
 * - Timestamps for freshness checks
 * 
 * ## Storage: SharedPreferences
 * - Survives app restarts
 * - Fast read/write
 * - No external dependencies
 * 
 * ## Freshness Rules:
 * - Domain: Refresh if > 24 hours old
 * - Cookies: Refresh if > 30 minutes since last use
 * 
 * @param context Android context for SharedPreferences
 * @param providerName Provider identifier for preference keys
 */
class ProviderStateStore(
    private val context: Context,
    private val providerName: String
) {
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("${providerName}_state", Context.MODE_PRIVATE)
    }
    
    // ========== KEYS ==========
    private val KEY_DOMAIN = "${providerName}_domain"
    private val KEY_DOMAIN_UPDATED = "${providerName}_domain_updated"
    private val KEY_COOKIES = "${providerName}_cookies"
    private val KEY_COOKIES_STORED = "${providerName}_cookies_stored"
    private val KEY_COOKIES_USED = "${providerName}_cookies_used"
    private val KEY_USER_AGENT = "${providerName}_user_agent"
    
    // ========== CONSTANTS ==========
    companion object {
        /** Domain freshness threshold: 24 hours */
        const val DOMAIN_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        
        /** Cookie freshness threshold: 30 minutes */
        const val COOKIE_MAX_AGE_MS = 30 * 60 * 1000L
    }
    
    // ========== DOMAIN ==========
    
    /**
     * Saves the current domain.
     */
    fun saveDomain(domain: String) {
        prefs.edit()
            .putString(KEY_DOMAIN, domain)
            .putLong(KEY_DOMAIN_UPDATED, System.currentTimeMillis())
            .apply()
        
        ProviderLogger.i(TAG_STATE_STORE, "saveDomain", "Domain saved",
            "domain" to domain
        )
    }
    
    /**
     * Loads the saved domain, or null if not set.
     */
    fun loadDomain(): String? {
        val domain = prefs.getString(KEY_DOMAIN, null)
        val updatedAt = prefs.getLong(KEY_DOMAIN_UPDATED, 0)
        val ageMs = System.currentTimeMillis() - updatedAt
        
        ProviderLogger.d(TAG_STATE_STORE, "loadDomain", "Domain loaded",
            "domain" to (domain ?: "null"),
            "ageMs" to ageMs
        )
        
        return domain
    }
    
    /**
     * Checks if the saved domain is fresh (< 24 hours old).
     */
    fun isDomainFresh(): Boolean {
        val updatedAt = prefs.getLong(KEY_DOMAIN_UPDATED, 0)
        val ageMs = System.currentTimeMillis() - updatedAt
        val isFresh = ageMs < DOMAIN_MAX_AGE_MS
        
        ProviderLogger.d(TAG_STATE_STORE, "isDomainFresh", "Domain freshness check",
            "isFresh" to isFresh,
            "ageMs" to ageMs,
            "maxAgeMs" to DOMAIN_MAX_AGE_MS
        )
        
        return isFresh
    }
    
    // ========== COOKIES ==========
    
    /**
     * Saves cookies as a serialized string.
     * Accepts Map and serializes to "key=value; key2=value2" format.
     * 
     * @param cookies Map of cookie name to value
     */
    fun saveCookies(cookies: Map<String, String>) {
        val now = System.currentTimeMillis()
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        prefs.edit()
            .putString(KEY_COOKIES, cookieHeader)
            .putLong(KEY_COOKIES_STORED, now)
            .putLong(KEY_COOKIES_USED, now)
            .apply()
        
        ProviderLogger.i(TAG_STATE_STORE, "saveCookies", "Cookies saved to prefs",
            "count" to cookies.size,
            "keys" to cookies.keys.toString()
        )
    }
    
    /**
     * Loads saved cookies, or empty map if not set or expired.
     * Updates last-used timestamp on successful load.
     * Returns Map instead of String for consistency with ProviderState.
     */
    fun loadCookies(): Map<String, String> {
        val cookieHeader = prefs.getString(KEY_COOKIES, null)
        
        if (cookieHeader == null) {
            ProviderLogger.d(TAG_STATE_STORE, "loadCookies", "No cookies in prefs")
            return emptyMap()
        }
        
        val storedAt = prefs.getLong(KEY_COOKIES_STORED, 0)
        val ageMs = System.currentTimeMillis() - storedAt
        
        // Check freshness
        if (ageMs > COOKIE_MAX_AGE_MS) {
            ProviderLogger.w(TAG_STATE_STORE, "loadCookies", "Cookies expired in prefs",
                "ageMs" to ageMs,
                "maxAgeMs" to COOKIE_MAX_AGE_MS
            )
            clearCookies()
            return emptyMap()
        }
        
        // Update last-used timestamp
        prefs.edit()
            .putLong(KEY_COOKIES_USED, System.currentTimeMillis())
            .apply()
        
        // Parse cookie header to map
        val cookieMap = parseCookieString(cookieHeader)
        ProviderLogger.i(TAG_STATE_STORE, "loadCookies", "Cookies loaded from prefs",
            "count" to cookieMap.size,
            "ageMs" to ageMs
        )
        
        return cookieMap
    }
    
    private fun parseCookieString(cookieHeader: String): Map<String, String> {
        return cookieHeader.split(";").associate {
            val parts = it.split("=", limit = 2)
            (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }
    
    /**
     * Checks if saved cookies are fresh (< 30 minutes old).
     */
    fun areCookiesFresh(): Boolean {
        val cookies = prefs.getString(KEY_COOKIES, null)
        if (cookies == null) return false
        
        val storedAt = prefs.getLong(KEY_COOKIES_STORED, 0)
        val ageMs = System.currentTimeMillis() - storedAt
        val isFresh = ageMs < COOKIE_MAX_AGE_MS
        
        ProviderLogger.d(TAG_STATE_STORE, "areCookiesFresh", "Cookie freshness check",
            "isFresh" to isFresh,
            "ageMs" to ageMs,
            "maxAgeMs" to COOKIE_MAX_AGE_MS,
            "remainingMs" to (COOKIE_MAX_AGE_MS - ageMs)
        )
        
        return isFresh
    }
    
    /**
     * Clears saved cookies.
     */
    fun clearCookies() {
        prefs.edit()
            .remove(KEY_COOKIES)
            .remove(KEY_COOKIES_STORED)
            .remove(KEY_COOKIES_USED)
            .apply()
        
        ProviderLogger.w(TAG_STATE_STORE, "clearCookies", "Cookies cleared from prefs")
    }
    
    // ========== USER-AGENT ==========
    
    /**
     * Saves the User-Agent string.
     */
    fun saveUserAgent(userAgent: String) {
        prefs.edit()
            .putString(KEY_USER_AGENT, userAgent)
            .apply()
        
        ProviderLogger.d(TAG_STATE_STORE, "saveUserAgent", "UA saved",
            "ua" to userAgent.take(50)
        )
    }
    
    /**
     * Loads the saved User-Agent, or null if not set.
     */
    fun loadUserAgent(): String? {
        return prefs.getString(KEY_USER_AGENT, null)
    }
    
    // ========== FULL STATE ==========
    
    /**
     * Loads the complete provider state.
     * 
     * @param defaultUserAgent Fallback UA if none stored
     */
    fun loadState(defaultUserAgent: String): ProviderState? {
        val domain = loadDomain() ?: return null
        val userAgent = loadUserAgent() ?: defaultUserAgent
        val cookies = loadCookies()
        
        return ProviderState(
            domain = domain,
            userAgent = userAgent,
            cookies = cookies,
            domainFresh = isDomainFresh(),
            cookiesFresh = areCookiesFresh()
        )
    }
    
    /**
     * Saves the complete provider state.
     */
    fun saveState(state: ProviderState) {
        saveDomain(state.domain)
        saveUserAgent(state.userAgent)
        saveCookies(state.cookies)
        ProviderLogger.i(TAG_STATE_STORE, "saveState", "Full state saved",
            "domain" to state.domain,
            "cookieCount" to state.cookies.size
        )
    }
    
    /**
     * Clears all stored state.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        ProviderLogger.w(TAG_STATE_STORE, "clearAll", "All state cleared")
    }
}

/**
 * Complete provider state - single source of truth.
 * Generic abstraction that can be used by any provider.
 */
data class ProviderState(
    val domain: String,
    val userAgent: String,
    val cookies: Map<String, String>,
    val domainFresh: Boolean,
    val cookiesFresh: Boolean,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /**
     * Cookie header string for HTTP requests.
     */
    val cookieHeader: String
        get() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    
    /**
     * Complete headers map for HTTP requests.
     */
    val headers: Map<String, String>
        get() = buildMap {
            put("User-Agent", userAgent)
            if (cookies.isNotEmpty()) {
                put("Cookie", cookieHeader)
            }
            put("Referer", domain)
        }
    
    /**
     * True if cookies are present and fresh.
     * No longer requires cf_clearance - any valid cookies work.
     */
    val hasValidSession: Boolean
        get() = cookies.isNotEmpty() && cookiesFresh
    
    companion object {
        /**
         * Create empty state with fallback values.
         */
        fun empty(domain: String, userAgent: String) = ProviderState(
            domain = domain,
            userAgent = userAgent,
            cookies = emptyMap(),
            domainFresh = false,
            cookiesFresh = false
        )
    }
}

