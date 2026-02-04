package com.cloudstream.shared.cookie

import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_COOKIE
import java.net.URI

/**
 * Manages the complete cookie lifecycle for a provider.
 * 
 * - Store cookies with timestamps
 * - Validate cookie freshness (expiry tracking)
 * - Normalize domain keys
 * 
 * @param maxAgeMs Maximum cookie age in milliseconds (default: 30 minutes)
 */
class CookieLifecycleManager(
    private val maxAgeMs: Long = 30 * 60 * 1000
) {
    private val cookieStore = mutableMapOf<String, CookieEntry>()
    
    @Synchronized
    fun store(url: String, cookies: Map<String, String>, source: String) {
        val key = normalizeKey(url)
        cookieStore[key] = CookieEntry(
            cookies = cookies,
            storedAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            source = source
        )
        ProviderLogger.logCookieStore(key, cookies, source)
    }
    
    @Synchronized
    fun retrieve(url: String): Map<String, String>? {
        val key = normalizeKey(url)
        val entry = cookieStore[key] ?: run {
            ProviderLogger.logCookieRetrieve(key, found = false, cookieCount = 0, age = null)
            return null
        }
        
        val ageMs = System.currentTimeMillis() - entry.storedAt
        if (!isValid(entry)) {
            ProviderLogger.logCookieFreshness(key, isValid = false, ageMs = ageMs, maxAgeMs = maxAgeMs)
            invalidate(url, "expired")
            return null
        }
        
        cookieStore[key] = entry.copy(lastUsedAt = System.currentTimeMillis())
        return entry.cookies
    }
    
    @Synchronized
    fun isValid(url: String): Boolean {
        val key = normalizeKey(url)
        return cookieStore[key]?.let { isValid(it) } ?: false
    }
    
    private fun isValid(entry: CookieEntry): Boolean {
        val ageMs = System.currentTimeMillis() - entry.storedAt
        return ageMs < maxAgeMs && entry.cookies.containsKey("cf_clearance")
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
        return cookieStore[key]?.let { System.currentTimeMillis() - it.storedAt }
    }
    
    @Synchronized
    fun buildCookieHeader(url: String): String? {
        return retrieve(url)?.entries?.joinToString("; ") { "${it.key}=${it.value}" }
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
        val entry = cookieStore[key] ?: return "domain=$key, NO_COOKIES"
        val ageMs = System.currentTimeMillis() - entry.storedAt
        return "domain=$key, age=${ageMs}ms, hasClearance=${entry.cookies.containsKey("cf_clearance")}, source=${entry.source}"
    }
}

data class CookieEntry(
    val cookies: Map<String, String>,
    val storedAt: Long,
    val lastUsedAt: Long,
    val source: String
)
