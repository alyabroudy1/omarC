package com.arabseed.service.cookie

import com.arabseed.service.ProviderLogger
import com.arabseed.service.ProviderLogger.TAG_COOKIE
import java.net.URI

/**
 * Manages the complete cookie lifecycle for a provider.
 */
class CookieLifecycleManager(
    private val maxAgeMs: Long = 30 * 60 * 1000 // 30 minutes default
) {
    
    /**
     * In-memory cookie store: normalizedDomain → CookieEntry
     */
    private val cookieStore = mutableMapOf<String, CookieEntry>()
    
    @Synchronized
    fun store(url: String, cookies: Map<String, String>, source: String) {
        val key = normalizeKey(url)
        val entry = CookieEntry(
            cookies = cookies,
            storedAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            source = source
        )
        cookieStore[key] = entry
        
        ProviderLogger.logCookieStore(key, cookies, source)
    }
    
    @Synchronized
    fun retrieve(url: String): Map<String, String>? {
        val key = normalizeKey(url)
        val entry = cookieStore[key]
        
        if (entry == null) {
            ProviderLogger.logCookieRetrieve(key, found = false, cookieCount = 0, age = null)
            return null
        }
        
        val ageMs = System.currentTimeMillis() - entry.storedAt
        
        // Check freshness
        if (!isValid(entry)) {
            ProviderLogger.logCookieFreshness(key, isValid = false, ageMs = ageMs, maxAgeMs = maxAgeMs)
            invalidate(url, "expired")
            return null
        }
        
        // Update last-used timestamp
        cookieStore[key] = entry.copy(lastUsedAt = System.currentTimeMillis())
        return entry.cookies
    }
    
    @Synchronized
    fun isValid(url: String): Boolean {
        val key = normalizeKey(url)
        val entry = cookieStore[key] ?: return false
        return isValid(entry)
    }
    
    private fun isValid(entry: CookieEntry): Boolean {
        val ageMs = System.currentTimeMillis() - entry.storedAt
        // Only check time, do not enforce specific cookie keys like cf_clearance
        return ageMs < maxAgeMs
    }
    
    @Synchronized
    fun invalidate(url: String, reason: String) {
        val key = normalizeKey(url)
        if (cookieStore.remove(key) != null) {
            ProviderLogger.logCookieInvalidate(key, reason)
        }
    }
    
    @Synchronized
    fun clear() {
        val count = cookieStore.size
        cookieStore.clear()
        ProviderLogger.w(TAG_COOKIE, "clear", "All cookies cleared", "count" to count)
    }
    
    @Synchronized
    fun getAgeMs(url: String): Long? {
        val key = normalizeKey(url)
        val entry = cookieStore[key] ?: return null
        return System.currentTimeMillis() - entry.storedAt
    }
    
    @Synchronized
    fun buildCookieHeader(url: String): String? {
        val cookies = retrieve(url) ?: return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
    
    fun normalizeKey(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) {
            ProviderLogger.e(TAG_COOKIE, "normalizeKey", "Failed to parse URL", e, "url" to url.take(50))
            ""
        }
    }
    
    @Synchronized
    fun getDebugInfo(url: String): String {
        val key = normalizeKey(url)
        val entry = cookieStore[key]
        return if (entry != null) {
            val ageMs = System.currentTimeMillis() - entry.storedAt
            val hasClearance = entry.cookies.containsKey("cf_clearance")
            "domain=$key, age=${ageMs}ms, hasClearance=$hasClearance, source=${entry.source}"
        } else {
            "domain=$key, NO_COOKIES"
        }
    }
}

data class CookieEntry(
    val cookies: Map<String, String>,
    val storedAt: Long,
    val lastUsedAt: Long,
    val source: String
)
