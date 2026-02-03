package com.faselhd.service.cookie

import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_COOKIE
import java.net.URI

/**
 * Manages the complete cookie lifecycle for a provider.
 * 
 * ## Responsibilities:
 * - Store cookies with timestamps
 * - Retrieve cookies by normalized domain key
 * - Validate cookie freshness (expiry tracking)
 * - Invalidate cookies on demand
 * - Normalize domain keys (www.example.com → example.com)
 * 
 * ## Cookie Freshness Rules:
 * - Cookies older than [maxAgeMs] are considered expired
 * - Default max age: 30 minutes (Cloudflare cf_clearance typical lifetime)
 * - Last-use timestamp updated on each successful request
 * 
 * ## Thread Safety:
 * - All operations are synchronized
 * - Uses @Volatile for visibility
 * 
 * @param maxAgeMs Maximum cookie age in milliseconds (default: 30 minutes)
 */
class CookieLifecycleManager(
    private val maxAgeMs: Long = 30 * 60 * 1000 // 30 minutes default
) {
    
    /**
     * In-memory cookie store: normalizedDomain → CookieEntry
     */
    private val cookieStore = mutableMapOf<String, CookieEntry>()
    
    /**
     * Stores cookies for a domain.
     * 
     * @param url The URL to derive the domain key from
     * @param cookies Map of cookie name → value
     * @param source Description of where cookies came from (e.g., "WebView", "DirectHttp")
     */
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
    
    /**
     * Retrieves cookies for a domain if they exist and are fresh.
     * Updates last-used timestamp on successful retrieval.
     * 
     * @param url The URL to derive the domain key from
     * @return Map of cookies if valid, null if expired or not found
     */
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
        
        // ProviderLogger.logCookieRetrieve(key, found = true, cookieCount = entry.cookies.size, age = ageMs)
        // ProviderLogger.logCookieFreshness(key, isValid = true, ageMs = ageMs, maxAgeMs = maxAgeMs)
        
        return entry.cookies
    }
    
    /**
     * Checks if cookies for a domain are valid (exist and not expired).
     * Does NOT update last-used timestamp.
     * 
     * @param url The URL to derive the domain key from
     * @return true if cookies exist and are fresh
     */
    @Synchronized
    fun isValid(url: String): Boolean {
        val key = normalizeKey(url)
        val entry = cookieStore[key] ?: return false
        return isValid(entry)
    }
    
    /**
     * Checks if a cookie entry is valid based on age.
     */
    private fun isValid(entry: CookieEntry): Boolean {
        val ageMs = System.currentTimeMillis() - entry.storedAt
        return ageMs < maxAgeMs && entry.cookies.containsKey("cf_clearance")
    }
    
    /**
     * Invalidates cookies for a domain.
     * 
     * @param url The URL to derive the domain key from
     * @param reason Description of why cookies are being invalidated
     */
    @Synchronized
    fun invalidate(url: String, reason: String) {
        val key = normalizeKey(url)
        if (cookieStore.remove(key) != null) {
            ProviderLogger.logCookieInvalidate(key, reason)
        }
    }
    
    /**
     * Clears all stored cookies.
     */
    @Synchronized
    fun clear() {
        val count = cookieStore.size
        cookieStore.clear()
        ProviderLogger.w(TAG_COOKIE, "clear", "All cookies cleared", "count" to count)
    }
    
    /**
     * Gets cookie age in milliseconds, or null if not found.
     */
    @Synchronized
    fun getAgeMs(url: String): Long? {
        val key = normalizeKey(url)
        val entry = cookieStore[key] ?: return null
        return System.currentTimeMillis() - entry.storedAt
    }
    
    /**
     * Builds a Cookie header string from stored cookies.
     * 
     * @param url The URL to derive the domain key from
     * @return Cookie header value or null
     */
    @Synchronized
    fun buildCookieHeader(url: String): String? {
        val cookies = retrieve(url) ?: return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
    
    /**
     * Normalizes a URL to a consistent domain key.
     * Strips protocol and 'www.' prefix.
     * 
     * Examples:
     * - https://www.faselhds.biz/page/1 → faselhds.biz
     * - https://faselhds.biz/movies → faselhds.biz
     * - http://cdn.example.com/video.mp4 → cdn.example.com
     * 
     * @param url Full URL
     * @return Normalized domain key
     */
    fun normalizeKey(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) {
            ProviderLogger.e(TAG_COOKIE, "normalizeKey", "Failed to parse URL", e, "url" to url.take(50))
            ""
        }
    }
    
    /**
     * Gets debug info about a domain's cookies.
     */
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

/**
 * Represents a stored cookie entry with metadata.
 */
data class CookieEntry(
    val cookies: Map<String, String>,
    val storedAt: Long,
    val lastUsedAt: Long,
    val source: String
)
