package com.cloudstream.shared.domain

import android.content.Context
import android.content.SharedPreferences
import com.cloudstream.shared.http.CookieStore
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Manages provider domain with GitHub fetch and persistence.
 */
class DomainManager(
    private val context: Context,
    private val providerName: String,
    private val fallbackDomain: String,
    private val githubConfigUrl: String,
    private val cookieStore: CookieStore,
    private val syncWorkerUrl: String? = null
) {
    private val TAG = "DomainManager"
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "domain_$providerName", 
        Context.MODE_PRIVATE
    )
    
    var currentDomain: String = fallbackDomain
        private set
    
    private var isInitialized = false
    
    private val mutex = Mutex()
    
    /**
     * BLOCKING initialization - called before first request.
     * Fetches latest domain from GitHub and waits for response.
     */
    suspend fun ensureInitialized() {
        if (isInitialized) return
        
        mutex.withLock {
            if (isInitialized) return@withLock
            
            // Load persisted first (fast)
            currentDomain = prefs.getString("domain", fallbackDomain) ?: fallbackDomain
            Log.d(TAG, "Loaded persisted domain: $currentDomain")
            
            // Fetch from GitHub (BLOCKING, with timeout)
            try {
                withTimeout(5000L) {
                    val response = app.get(githubConfigUrl)
                    if (response.isSuccessful) {
                        val config = JSONObject(response.text)
                        val remoteDomain = config.optString("domain", "")
                        
                        val normalizedRemote = remoteDomain
                            .removePrefix("http://")
                            .removePrefix("https://")
                            .trimEnd('/')
                        
                        if (normalizedRemote.isNotBlank() && normalizedRemote != currentDomain) {
                            Log.i(TAG, "Domain updated from GitHub: $currentDomain → $normalizedRemote")
                            
                            // Clear cookies for old domain before updating
                            cookieStore.clear(currentDomain, "Domain changed from GitHub")
                            
                            updateDomain(normalizedRemote)
                        } else {
                            Log.i(TAG, "Domain is up to date. Keeping: $currentDomain")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "GitHub config fetch timed out, using persisted: $currentDomain")
            } catch (e: Exception) {
                Log.w(TAG, "GitHub config fetch failed: ${e.message}, using persisted: $currentDomain")
            }
            
            isInitialized = true
        }
    }
    
    /**
     * Update domain and persist.
     */
    fun updateDomain(newDomain: String) {
        val normalized = newDomain
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        
        if (normalized != currentDomain) {
            Log.i(TAG, "Domain updated: $currentDomain → $normalized")
            currentDomain = normalized
            prefs.edit().putString("domain", normalized).apply()
        }
    }
    
    /**
     * Check if response URL indicates a domain redirect.
     * Only call this for main page / search / load - NOT video sniffing.
     */
    fun checkDomainChange(requestUrl: String, finalUrl: String?) {
        if (finalUrl == null) return
        
        try {
            val requestHost = URI(requestUrl).host?.removePrefix("www.")
            val finalHost = URI(finalUrl).host?.removePrefix("www.")
            
            if (requestHost != finalHost && finalHost != null && finalHost.isNotBlank()) {
                Log.i(TAG, "Domain redirect detected: $requestHost → $finalHost")
                
                // Clear old cookies
                if (requestHost != null) {
                    cookieStore.clear(requestHost, "Domain redirect detected")
                }
                
                updateDomain(finalHost)
                syncToRemote()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check domain change: ${e.message}")
        }
    }
    
    /**
     * Build full URL from path.
     */
    fun buildUrl(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "https://$currentDomain$normalizedPath"
    }

    /**
     * Sync domain change back to GitHub via Cloudflare Worker.
     */
    fun syncToRemote() {
        if (syncWorkerUrl == null) return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Determine config filename from provider name or URL
                // Heuristic: providerName.lowercase() + ".json"
                val configName = "${providerName.lowercase()}.json"
                
                // FaselHD worker expects:
                // { "provider": "name", "configFile": "name.json", "newDomain": "url", "currentVersion": int }
                val payload = JSONObject().apply {
                    put("provider", providerName.lowercase())
                    put("configFile", configName)
                    put("newDomain", "https://$currentDomain")
                    put("currentVersion", 0)
                }
                
                val jsonBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                
                app.post(
                    syncWorkerUrl,
                    requestBody = jsonBody
                )
                
                Log.d(TAG, "Domain synced to remote: $currentDomain")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync domain: ${e.message}")
            }
        }
    }
}
