package com.cloudstream.shared.domain

import android.content.Context
import android.content.SharedPreferences
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_DOMAIN
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Manages provider domain with GitHub fetch, persistence, and auto-sync.
 * 
 * Simplified version matching Arabseed's implementation.
 */
class DomainManager(
    context: Context,
    private val providerName: String,
    private val fallbackDomain: String,
    private val githubConfigUrl: String?,
    private val syncWorkerUrl: String? = null
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "domain_$providerName", 
        Context.MODE_PRIVATE
    )
    
    var currentDomain: String = fallbackDomain
        private set
    
    private var isInitialized = false
    private val mutex = Mutex()
    
    suspend fun ensureInitialized() {
        if (isInitialized) return
        
        mutex.withLock {
            if (isInitialized) return@withLock
            
            val persisted = prefs.getString("domain", null)
            if (persisted != null) {
                 currentDomain = persisted
            } else {
                 currentDomain = fallbackDomain
            }
            
            if (githubConfigUrl != null) {
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
                                ProviderLogger.i(TAG_DOMAIN, "ensureInitialized", "Remote domain update",
                                    "old" to currentDomain, "new" to normalizedRemote)
                                updateDomain(normalizedRemote)
                            }
                        }
                    }
                } catch (e: Exception) {
                    ProviderLogger.w(TAG_DOMAIN, "ensureInitialized", "GitHub config fetch failed",
                        "error" to e.message)
                }
            }
            
            isInitialized = true
        }
    }
    
    fun updateDomain(newDomain: String) {
        val normalized = newDomain
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        
        if (normalized != currentDomain) {
            currentDomain = normalized
            prefs.edit().putString("domain", normalized).apply()
            ProviderLogger.i(TAG_DOMAIN, "updateDomain", "Updated", "domain" to normalized)
        }
    }

    fun syncToRemote() {
        if (syncWorkerUrl == null) return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val configName = "${providerName.lowercase()}.json"
                val payload = JSONObject().apply {
                    put("provider", providerName.lowercase())
                    put("configFile", configName)
                    put("newDomain", "https://$currentDomain")
                    put("currentVersion", 0)
                }
                
                val jsonBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                app.post(syncWorkerUrl, requestBody = jsonBody)
                
                ProviderLogger.d(TAG_DOMAIN, "syncToRemote", "Synced", "domain" to currentDomain)
            } catch (e: Exception) { 
                ProviderLogger.w(TAG_DOMAIN, "syncToRemote", "Failed", "error" to e.message)
            }
        }
    }
    
    fun buildUrl(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "https://$currentDomain$normalizedPath"
    }
}
