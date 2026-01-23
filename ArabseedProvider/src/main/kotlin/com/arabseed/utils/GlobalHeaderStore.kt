package com.arabseed.utils

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.util.Log // Replaced com.lagradost.api.Log
import okhttp3.Headers
import java.util.concurrent.ConcurrentHashMap

// Removed import com.lagradost.nicehttp.getCookies as it seems unused or unavailable

object GlobalHeaderStore {
    private const val TAG = "GlobalHeaderStore"

    @Volatile
    var unifiedUserAgent: String? = null
        private set

    private val cachedCookies = ConcurrentHashMap<String, Map<String, String>>()

    fun initUserAgent(context: Context) {
        if (unifiedUserAgent != null) return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val webView = WebView(context)
                unifiedUserAgent = webView.settings.userAgentString
                Log.i(TAG, "Unified UA initialized: ${unifiedUserAgent?.take(60)}...")
                webView.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init UA: ${e.message}")
        }
    }
    
    suspend fun initUserAgentSync(context: Context) {
        if (unifiedUserAgent != null) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            try {
                val webView = WebView(context)
                unifiedUserAgent = webView.settings.userAgentString
                Log.i(TAG, "Unified UA initialized (sync): ${unifiedUserAgent?.take(60)}...")
                webView.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init UA sync: ${e.message}")
            }
        }
    }

    fun getCookiesForHost(host: String): Map<String, String> {
        if (!cachedCookies.containsKey(host)) {
             hydrateCookiesFromManager(host)
        }

        return cachedCookies[host] 
            ?: cachedCookies["www.$host"] 
            ?: cachedCookies[host.removePrefix("www.")] 
            ?: emptyMap()
    }

    fun setCookiesForHost(host: String, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        
        val cleanHost = host.removePrefix("https://").removePrefix("http://").split("/").first()
        val key = if (cleanHost.startsWith("www.")) cleanHost.removePrefix("www.") else cleanHost

        cachedCookies[key] = cookies
        cachedCookies["www.$key"] = cookies
        
        Log.i(TAG, "Updated/Cached ${cookies.size} cookies for $cleanHost")
        
        try {
            val cookieManager = CookieManager.getInstance()
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            cookieManager.setCookie(cleanHost, cookieString)
            cookieManager.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting cookies: ${e.message}")
        }
    }

    private fun hydrateCookiesFromManager(host: String) {
        try {
           val cookieManager = CookieManager.getInstance()
           val url = "https://$host"
           val cookieString = cookieManager.getCookie(url) ?: return
           
           val cookies = parseCookies(cookieString)
           if (cookies.isNotEmpty()) {
               val cleanHost = host.removePrefix("www.")
               cachedCookies[cleanHost] = cookies
           }
        } catch (e: Exception) {
        }
    }
    
    private fun parseCookies(cookieString: String): Map<String, String> {
        if (cookieString.isBlank()) return emptyMap()
        return cookieString.split(";").associate {
            val parts = it.split("=", limit = 2)
            val key = parts.getOrNull(0)?.trim() ?: ""
            val value = parts.getOrNull(1)?.trim() ?: ""
            key to value
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }

    private fun getDefaultUserAgent(): String {
        return System.getProperty("http.agent") 
            ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    fun buildHeaders(host: String, currentHeaders: Headers, currentCookies: Map<String, String>): Headers {
        val ua = unifiedUserAgent ?: getDefaultUserAgent()
        val cached = getCookiesForHost(host)
        
        val finalCookies = cached + currentCookies 
        
        val cookieHeader = finalCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        val builder = currentHeaders.newBuilder()
        builder.set("User-Agent", ua)
        if (finalCookies.isNotEmpty()) {
            builder.set("Cookie", cookieHeader)
        }
        
        if (currentHeaders["Accept"] == null) {
            builder.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        }
        if (currentHeaders["Accept-Language"] == null) {
            builder.set("Accept-Language", "en-US,en;q=0.9")
        }
        
        return builder.build()
    }
}
