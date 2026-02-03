package com.faselhd.service

import android.content.Context
import com.faselhd.service.strategy.VideoSource
import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_SESSION
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Public facade for the Provider HTTP Service.
 * 
 * ## Purpose:
 * Provides a simple, high-level API that hides the complexity of
 * session management, strategy selection, and cookie lifecycle.
 * 
 * ## Usage:
 * ```kotlin
 * class FaselHD : MainAPI() {
 *     private val http = ProviderHttpService.create(
 *         context = PluginContext.context!!,
 *         providerName = "FaselHD",
 *         userAgent = "...",
 *         fallbackDomain = "https://www.faselhds.biz"
 *     )
 *     
 *     override suspend fun getMainPage(...) {
 *         val doc = http.getDocument("$mainUrl/all-movies")
 *         // Parse document...
 *     }
 *     
 *     override suspend fun loadLinks(...) {
 *         val videos = http.sniffVideos(playerUrl)
 *         // Create ExtractorLinks...
 *     }
 * }
 * ```
 * 
 * ## Features:
 * - Automatic CF challenge solving
 * - Cookie persistence across requests
 * - Video sniffing for player pages
 * - Comprehensive logging
 */
class ProviderHttpService private constructor(
    private val sessionManager: ProviderSessionManager
) {
    
    companion object {
        /**
         * Creates a new ProviderHttpService instance.
         * 
         * @param context Android context
         * @param providerName Provider identifier (used for prefs keys)
         * @param userAgent Static User-Agent (CRITICAL: must match across components)
         * @param fallbackDomain Default domain if no config available
         */
        fun create(
            context: Context,
            providerName: String,
            userAgent: String,
            fallbackDomain: String
        ): ProviderHttpService {
            ProviderLogger.i(TAG_SESSION, "create", "Creating ProviderHttpService",
                "provider" to providerName,
                "fallbackDomain" to fallbackDomain
            )
            
            val sessionManager = ProviderSessionManager(
                context = context,
                providerName = providerName,
                rawUserAgent = userAgent,
                fallbackDomain = fallbackDomain
            )
            
            return ProviderHttpService(sessionManager)
        }
    }
    
    // ========== PROPERTIES ==========
    
    /**
     * Current provider domain.
     */
    val currentDomain: String
        get() = sessionManager.currentDomain
    
    /**
     * The static User-Agent used by this service.
     */
    val userAgent: String?
        get() = sessionManager.userAgent
    
    /**
     * Whether the service has been initialized.
     */
    val isInitialized: Boolean
        get() = sessionManager.isInitialized
    
    // ========== INITIALIZATION ==========
    
    /**
     * Initialize the service.
     * Call this ONCE when the provider first loads (e.g., in getMainPage).
     * 
     * Flow:
     * 1. Load persisted state from disk
     * 2. Fetch latest domain from GitHub
     * 3. Update state if domain changed
     */
    suspend fun initialize() {
        sessionManager.initialize()
    }
    
    // ========== PUBLIC API ==========
    
    /**
     * Makes an HTTP GET request and returns the HTML content.
     * Handles CF challenges automatically.
     * 
     * @param url The URL to request
     * @return HTML content as String, or null on failure
     */
    suspend fun get(url: String): String? {
        val response = sessionManager.request(url)
        return if (response.success) response.html else null
    }
    
    /**
     * Makes an HTTP GET request and returns a parsed Jsoup Document.
     * Handles CF challenges automatically.
     * 
     * @param url The URL to request
     * @return Parsed Document, or null on failure
     */
    suspend fun getDocument(url: String): Document? {
        val html = get(url) ?: return null
        return try {
            Jsoup.parse(html, url)
        } catch (e: Exception) {
            ProviderLogger.e(TAG_SESSION, "getDocument", "Failed to parse HTML", e,
                "url" to url.take(80)
            )
            null
        }
    }
    
    /**
     * Sniffs video URLs from a player page.
     * Uses WebView with video monitoring and JWPlayer extraction.
     * 
     * @param url The player page URL
     * @return List of captured video sources
     */
    suspend fun sniffVideos(url: String): List<VideoSource> {
        return sessionManager.sniffVideos(url)
    }
    
    /**
     * Updates the current domain.
     * Call this when domain changes are detected (e.g., from redirect or config).
     * 
     * @param domain New domain URL
     */
    fun updateDomain(domain: String) {
        sessionManager.updateDomain(domain)
    }
    
    /**
     * Gets headers for image requests (e.g., posterHeaders).
     * Includes User-Agent, cookies, and referer.
     * 
     * @return Headers map suitable for image loading
     */
    fun getImageHeaders(): Map<String, String> {
        return sessionManager.getImageHeaders()
    }
    
    /**
     * Checks if there's a valid session (fresh cookies).
     * 
     * @return true if cookies are valid and not expired
     */
    fun hasValidSession(): Boolean {
        return sessionManager.hasValidSession()
    }
    
    /**
     * Forces session invalidation.
     * Use when you detect issues and want to force a fresh CF solve.
     * 
     * @param reason Description for logging
     */
    fun invalidateSession(reason: String) {
        sessionManager.invalidateSession(reason)
    }
    
    /**
     * Gets debug info about current session state.
     * Useful for logging and troubleshooting.
     */
    fun getDebugInfo(): String {
        val cookieInfo = sessionManager.cookieManager.getDebugInfo(currentDomain)
        return "Provider: ${sessionManager.providerName}, Domain: $currentDomain, $cookieInfo"
    }
}
