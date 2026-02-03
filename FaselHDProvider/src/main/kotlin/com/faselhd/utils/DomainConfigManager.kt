package com.faselhd.utils

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable Domain Configuration Manager.
 * 
 * This utility allows any CloudStream provider to automatically detect and sync
 * domain changes from a remote JSON config. It's designed to be easily integrated
 * into any provider with minimal code changes.
 * 
 * ## Usage in a Provider:
 * ```kotlin
 * class MyProvider : MainAPI() {
 *     private val domainManager = DomainConfigManager(
 *         providerName = "MyProvider",
 *         configFileName = "myprovider.json",
 *         fallbackDomain = "https://default.domain.com"
 *     )
 *     
 *     override var mainUrl: String
 *         get() = domainManager.currentDomain
 *         set(value) { domainManager.currentDomain = value }
 *     
 *     // Call this in your first request method (e.g., getMainPage)
 *     private suspend fun ensureDomainInitialized() {
 *         if (!domainManager.isInitialized) {
 *             domainManager.initialize()
 *         }
 *     }
 * }
 * ```
 */
class DomainConfigManager(
    private val providerName: String,
    private val configFileName: String,
    private val fallbackDomain: String,
    private val syncApiUrl: String = DEFAULT_SYNC_API_URL
) {
    companion object {
        private const val TAG = "DomainConfigManager"
        
        // Base URL for raw GitHub config files
        private const val CONFIG_BASE_URL = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs"
        
        // Cloudflare Worker URL for syncing
        private const val DEFAULT_SYNC_API_URL = "https://omarstreamcloud.alyabroudy1.workers.dev"
    }

    // Current active domain
    var currentDomain: String = fallbackDomain
        private set
    
    // Config version for sync
    var configVersion: Int = 0
        private set
    
    // Whether the manager has been initialized
    var isInitialized: Boolean = false
        private set
    
    // Last known domain (to detect changes)
    private var lastKnownDomain: String = fallbackDomain
    
    private val initMutex = Mutex()

    /**
     * Initialize the domain manager by fetching the remote config.
     * Call this once when the provider first loads (e.g., in getMainPage).
     */
    suspend fun initialize(): Boolean {
        if (isInitialized) return true
        
        return initMutex.withLock {
            if (isInitialized) return@withLock true
            
            try {
                val configUrl = "$CONFIG_BASE_URL/$configFileName"
                Log.i(TAG, "[$providerName] Fetching domain config from: $configUrl")
                
                val response = withContext(Dispatchers.IO) {
                    app.get(configUrl, timeout = 10).text
                }
                
                val config = parseConfig(response)
                if (config != null) {
                    currentDomain = config.domain
                    configVersion = config.version
                    lastKnownDomain = config.domain
                    Log.i(TAG, "[$providerName] Domain initialized: $currentDomain (v$configVersion)")
                    isInitialized = true
                    true
                } else {
                    Log.w(TAG, "[$providerName] Failed to parse config, using fallback: $fallbackDomain")
                    currentDomain = fallbackDomain
                    isInitialized = true
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$providerName] Config fetch failed: ${e.message}, using fallback: $fallbackDomain")
                currentDomain = fallbackDomain
                isInitialized = true
                false
            }
        }
    }

    /**
     * Check if a response URL indicates a domain redirect.
     * Call this after making HTTP requests to detect domain changes.
     * 
     * @param responseUrl The final URL after following redirects
     * @return true if domain changed and sync was triggered
     */
    suspend fun checkForDomainChange(responseUrl: String): Boolean {
        val newDomain = extractDomain(responseUrl)
        val currentBase = extractDomain(currentDomain)
        
        if (newDomain != currentBase && newDomain.contains("fasel", ignoreCase = true)) {
            Log.w(TAG, "[$providerName] Domain change detected: $currentBase -> $newDomain")
            
            val fullNewDomain = "https://$newDomain"
            currentDomain = fullNewDomain
            
            // Trigger async sync to GitHub
            syncDomainChange(fullNewDomain)
            return true
        }
        return false
    }

    /**
     * Manually update the domain (e.g., from user settings).
     */
    fun updateDomain(newDomain: String) {
        currentDomain = newDomain
        lastKnownDomain = newDomain
    }

    /**
     * Sync the domain change to the remote config via Cloudflare Worker.
     */
    private suspend fun syncDomainChange(newDomain: String) {
        try {
            Log.i(TAG, "[$providerName] Syncing domain change to remote config...")
            
            val payload = JSONObject().apply {
                put("provider", providerName.lowercase())
                put("configFile", configFileName)
                put("newDomain", newDomain)
                put("currentVersion", configVersion)
            }
            
            // Fire and forget - do not block the main thread
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val jsonBody = okhttp3.RequestBody.create(
                        "application/json; charset=utf-8".toMediaTypeOrNull(),
                        payload.toString()
                    )
                    val response = app.post(
                        syncApiUrl,
                        requestBody = jsonBody
                    )
                    
                    if (response.isSuccessful) {
                        val result = JSONObject(response.text)
                        val newVersion = result.optInt("newVersion", configVersion + 1)
                        configVersion = newVersion
                        Log.i(TAG, "[$providerName] Domain synced successfully! New version: $newVersion")
                    } else {
                        Log.e(TAG, "[$providerName] Sync failed: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$providerName] Sync error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$providerName] Sync error: ${e.message}")
        }
    }

    /**
     * Parse the JSON config response.
     */
    private fun parseConfig(json: String): DomainConfig? {
        return try {
            val obj = JSONObject(json)
            DomainConfig(
                domain = obj.getString("domain"),
                version = obj.getInt("version"),
                lastUpdated = obj.optString("lastUpdated", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "[$providerName] Config parse error: ${e.message}")
            null
        }
    }

    /**
     * Extract the host from a URL.
     */
    private fun extractDomain(url: String): String {
        return try {
            URI(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Data class for parsed config.
     */
    data class DomainConfig(
        val domain: String,
        val version: Int,
        val lastUpdated: String
    )
}
