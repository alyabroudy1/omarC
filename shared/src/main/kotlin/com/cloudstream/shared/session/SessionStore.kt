package com.cloudstream.shared.session

import android.content.Context
import android.content.SharedPreferences
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_SESSION
import com.cloudstream.shared.provider.UNIFIED_USER_AGENT
import org.json.JSONObject

/**
 * Persistence layer for SessionState.
 * 
 * Uses SharedPreferences for simple key-value storage.
 * Thread-safe via SharedPreferences' internal synchronization.
 */
class SessionStore(
    context: Context,
    private val providerName: String
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "session_${providerName.lowercase()}", 
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_COOKIES = "cookies_json"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_COOKIE_TIMESTAMP = "cookie_timestamp"
        private const val KEY_FROM_WEBVIEW = "from_webview"
    }
    
    /**
     * Save session state to disk.
     */
    fun save(state: SessionState) {
        try {
            val cookiesJson = JSONObject().apply {
                for ((key, value) in state.cookies) {
                    put(key, value)
                }
            }.toString()
            
            prefs.edit()
                .putString(KEY_USER_AGENT, state.userAgent)
                .putString(KEY_COOKIES, cookiesJson)
                .putString(KEY_DOMAIN, state.domain)
                .putLong(KEY_COOKIE_TIMESTAMP, state.cookieTimestamp)
                .putBoolean(KEY_FROM_WEBVIEW, state.fromWebView)
                .apply()
            
            ProviderLogger.d(TAG_SESSION, "save", "Saved session",
                "domain" to state.domain, "cookies" to state.cookies.size)
        } catch (e: Exception) {
            ProviderLogger.e(TAG_SESSION, "save", "Failed", e)
        }
    }
    
    /**
     * Load session state from disk.
     * Returns null if no session is persisted.
     */
    fun load(fallbackDomain: String): SessionState? {
        return try {
            val userAgent = prefs.getString(KEY_USER_AGENT, null) ?: UNIFIED_USER_AGENT
            val cookiesJson = prefs.getString(KEY_COOKIES, null) ?: "{}"
            val domain = prefs.getString(KEY_DOMAIN, fallbackDomain) ?: fallbackDomain
            val cookieTimestamp = prefs.getLong(KEY_COOKIE_TIMESTAMP, 0L)
            val fromWebView = prefs.getBoolean(KEY_FROM_WEBVIEW, false)
            
            val cookies = mutableMapOf<String, String>()
            val json = JSONObject(cookiesJson)
            json.keys().forEach { key ->
                cookies[key] = json.getString(key)
            }
            
            // If we have no cookies, consider session empty/null so we re-initialize
            if (cookies.isEmpty()) {
                ProviderLogger.d(TAG_SESSION, "load", "No cookies, treating as null")
                return null
            }
            
            val state = SessionState(
                userAgent = userAgent,
                cookies = cookies,
                domain = domain,
                cookieTimestamp = cookieTimestamp,
                fromWebView = fromWebView
            )
            
            ProviderLogger.d(TAG_SESSION, "load", "Loaded session",
                "domain" to domain, "cookies" to cookies.size, "valid" to state.isValid())
            state
        } catch (e: Exception) {
            ProviderLogger.w(TAG_SESSION, "load", "Failed", "error" to e.message)
            null
        }
    }
    
    /**
     * Clear persisted session.
     */
    fun clear() {
        prefs.edit().clear().apply()
        ProviderLogger.d(TAG_SESSION, "clear", "Cleared session")
    }
    
    /**
     * Check if a session is persisted.
     */
    fun hasSession(): Boolean {
        return prefs.contains(KEY_USER_AGENT) && prefs.contains(KEY_DOMAIN)
    }
}
