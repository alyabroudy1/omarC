package com.youtube

import android.webkit.CookieManager
import com.lagradost.api.Log
import java.net.URI

/**
 * Single source of truth for cookies across all components.
 * Handles both in-memory storage and Android CookieManager sync.
 */
interface CookieStore {
    fun store(domain: String, cookies: Map<String, String>, source: String)
    fun retrieve(domain: String): Map<String, String>
    fun clear(domain: String, reason: String)
    fun buildHeader(domain: String): String?
}

/**
 * Thread-safe in-memory cookie store with CookieManager integration.
 */
class InMemoryCookieStore : CookieStore {
    private val TAG = "CookieStore"
    private val store = mutableMapOf<String, CookieEntry>()
    
    data class CookieEntry(
        val cookies: Map<String, String>,
        val storedAt: Long,
        val source: String
    )
    
    @Synchronized
    override fun store(domain: String, cookies: Map<String, String>, source: String) {
        val normalizedDomain = normalizeDomain(domain)
        store[normalizedDomain] = CookieEntry(
            cookies = cookies,
            storedAt = System.currentTimeMillis(),
            source = source
        )
        Log.i(TAG, "Stored ${cookies.size} cookies for $normalizedDomain from $source")
    }
    
    @Synchronized
    override fun retrieve(domain: String): Map<String, String> {
        val normalizedDomain = normalizeDomain(domain)
        return store[normalizedDomain]?.cookies ?: emptyMap()
    }
    
    @Synchronized
    override fun clear(domain: String, reason: String) {
        val normalizedDomain = normalizeDomain(domain)
        
        // Clear from our store
        if (store.remove(normalizedDomain) != null) {
            Log.i(TAG, "Cleared cookies for $normalizedDomain: $reason")
        }
        
        // Also clear from Android's CookieManager
        try {
            CookieManager.getInstance().apply {
                val url = "https://$normalizedDomain"
                getCookie(url)?.split(";")?.forEach { cookie ->
                    val name = cookie.split("=").firstOrNull()?.trim()
                    if (!name.isNullOrBlank()) {
                        setCookie(url, "$name=; Max-Age=0; Path=/")
                    }
                }
                flush()
            }
            Log.d(TAG, "Cleared CookieManager cookies for $normalizedDomain")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear CookieManager: ${e.message}")
        }
    }
    
    @Synchronized
    override fun buildHeader(domain: String): String? {
        val cookies = retrieve(domain)
        return if (cookies.isNotEmpty()) {
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else null
    }
    
    private fun normalizeDomain(domain: String): String {
        return try {
            val host = if (domain.startsWith("http")) {
                URI(domain).host
            } else {
                domain
            }
            host?.removePrefix("www.") ?: domain
        } catch (e: Exception) {
            domain.removePrefix("www.")
        }
    }
}
