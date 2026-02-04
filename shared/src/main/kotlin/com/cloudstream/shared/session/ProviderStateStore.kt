package com.cloudstream.shared.session

import android.content.Context
import android.content.SharedPreferences
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_STATE_STORE

/**
 * Persists provider session state across app restarts.
 * 
 * Stored Data:
 * - Current domain URL
 * - User-Agent string  
 * - Cookie string (serialized)
 * - Timestamps for freshness checks
 * 
 * Freshness Rules:
 * - Domain: Refresh if > 24 hours old
 * - Cookies: Refresh if > 30 minutes since stored
 */
class ProviderStateStore(
    private val context: Context,
    private val providerName: String
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("${providerName.lowercase()}_state", Context.MODE_PRIVATE)
    }
    
    // Keys
    private val KEY_DOMAIN = "domain"
    private val KEY_DOMAIN_UPDATED = "domain_updated"
    private val KEY_COOKIES = "cookies"
    private val KEY_COOKIES_STORED = "cookies_stored"
    private val KEY_COOKIES_USED = "cookies_used"
    private val KEY_USER_AGENT = "user_agent"
    
    companion object {
        /** Domain freshness threshold: 24 hours */
        const val DOMAIN_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        
        /** Cookie freshness threshold: 30 minutes */
        const val COOKIE_MAX_AGE_MS = 30 * 60 * 1000L
    }
    
    // ========== DOMAIN ==========
    
    fun saveDomain(domain: String) {
        prefs.edit()
            .putString(KEY_DOMAIN, domain)
            .putLong(KEY_DOMAIN_UPDATED, System.currentTimeMillis())
            .apply()
        
        ProviderLogger.i(TAG_STATE_STORE, "saveDomain", "Saved", "domain" to domain)
    }
    
    fun loadDomain(): String? {
        return prefs.getString(KEY_DOMAIN, null)
    }
    
    fun isDomainFresh(): Boolean {
        val updatedAt = prefs.getLong(KEY_DOMAIN_UPDATED, 0)
        val ageMs = System.currentTimeMillis() - updatedAt
        return ageMs < DOMAIN_MAX_AGE_MS
    }
    
    // ========== COOKIES ==========
    
    fun saveCookies(cookies: Map<String, String>) {
        val now = System.currentTimeMillis()
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        prefs.edit()
            .putString(KEY_COOKIES, cookieHeader)
            .putLong(KEY_COOKIES_STORED, now)
            .putLong(KEY_COOKIES_USED, now)
            .apply()
        
        ProviderLogger.i(TAG_STATE_STORE, "saveCookies", "Saved",
            "count" to cookies.size, "keys" to cookies.keys.toString())
    }
    
    fun loadCookies(): Map<String, String> {
        val cookieHeader = prefs.getString(KEY_COOKIES, null) ?: return emptyMap()
        
        val storedAt = prefs.getLong(KEY_COOKIES_STORED, 0)
        val ageMs = System.currentTimeMillis() - storedAt
        
        // Check freshness
        if (ageMs > COOKIE_MAX_AGE_MS) {
            ProviderLogger.w(TAG_STATE_STORE, "loadCookies", "Expired", "ageMs" to ageMs)
            clearCookies()
            return emptyMap()
        }
        
        // Update last-used timestamp
        prefs.edit().putLong(KEY_COOKIES_USED, System.currentTimeMillis()).apply()
        
        return parseCookieString(cookieHeader)
    }
    
    private fun parseCookieString(cookieHeader: String): Map<String, String> {
        return cookieHeader.split(";").associate {
            val parts = it.split("=", limit = 2)
            (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }
    
    fun areCookiesFresh(): Boolean {
        val storedAt = prefs.getLong(KEY_COOKIES_STORED, 0)
        if (storedAt == 0L) return false
        val ageMs = System.currentTimeMillis() - storedAt
        return ageMs < COOKIE_MAX_AGE_MS
    }
    
    fun clearCookies() {
        prefs.edit()
            .remove(KEY_COOKIES)
            .remove(KEY_COOKIES_STORED)
            .remove(KEY_COOKIES_USED)
            .apply()
        ProviderLogger.w(TAG_STATE_STORE, "clearCookies", "Cleared")
    }
    
    // ========== USER-AGENT ==========
    
    fun saveUserAgent(userAgent: String) {
        prefs.edit().putString(KEY_USER_AGENT, userAgent).apply()
    }
    
    fun loadUserAgent(): String? {
        return prefs.getString(KEY_USER_AGENT, null)
    }
    
    // ========== FULL STATE ==========
    
    fun loadState(defaultDomain: String, defaultUserAgent: String): ProviderState {
        val domain = loadDomain() ?: defaultDomain
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
    
    fun saveState(state: ProviderState) {
        saveDomain(state.domain)
        saveUserAgent(state.userAgent)
        saveCookies(state.cookies)
    }
    
    fun clearAll() {
        prefs.edit().clear().apply()
        ProviderLogger.w(TAG_STATE_STORE, "clearAll", "All state cleared")
    }
}

/**
 * Complete provider state snapshot.
 */
data class ProviderState(
    val domain: String,
    val userAgent: String,
    val cookies: Map<String, String>,
    val domainFresh: Boolean,
    val cookiesFresh: Boolean,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val cookieHeader: String
        get() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    
    val hasValidSession: Boolean
        get() = cookies.isNotEmpty() && cookiesFresh
    
    companion object {
        fun empty(domain: String, userAgent: String) = ProviderState(
            domain = domain,
            userAgent = userAgent,
            cookies = emptyMap(),
            domainFresh = false,
            cookiesFresh = false
        )
    }
}
