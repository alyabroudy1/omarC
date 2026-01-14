package com.faselhd.service

import android.content.Context
import com.faselhd.service.cloudflare.CloudflareDetector
import com.faselhd.service.cookie.CookieLifecycleManager
import com.faselhd.service.strategy.*
import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_SESSION
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Central session manager for provider HTTP requests.
 * 
 * ## Responsibilities:
 * - Maintains provider state (domain, cookies, User-Agent)
 * - Selects appropriate request strategy
 * - Manages cookie lifecycle (store, refresh, invalidate)
 * - Orchestrates CF challenge solving
 * 
 * ## Request Flow:
 * 1. Check if cookies are valid
 * 2. If valid: Use DirectHttpStrategy
 * 3. If invalid or CF blocked: Use WebViewStrategy
 * 4. Store cookies after successful request
 * 5. Return HTML content
 * 
 * ## Thread Safety:
 * - Uses mutex to prevent concurrent CF challenges
 * - Volatile state properties
 * 
 * @param context Android context
 * @param providerName Provider identifier
 * @param userAgent Static User-Agent (MUST match across WebView and OkHttp)
 * @param fallbackDomain Default domain if no config available
 */
class ProviderSessionManager(
    private val context: Context,
    val providerName: String,
    val userAgent: String,
    private val fallbackDomain: String
) {
    
    /** Cookie lifecycle manager */
    val cookieManager = CookieLifecycleManager()
    
    /** Cloudflare detector */
    private val cfDetector = CloudflareDetector()
    
    /** Persistent state store */
    private val stateStore = ProviderStateStore(context, providerName)
    
    /** Request strategies */
    private val directHttpStrategy = DirectHttpStrategy()
    private val webViewStrategy by lazy { 
        WebViewStrategy(context, cookieManager, cfDetector) 
    }
    private val videoSniffingStrategy by lazy {
        VideoSniffingStrategy(context, cookieManager, cfDetector)
    }
    
    /** Mutex to prevent concurrent CF challenges */
    private val cfMutex = Mutex()
    
    /** Current domain (volatile for thread safety) */
    @Volatile
    var currentDomain: String = fallbackDomain
        private set
    
    /** Current session state */
    enum class SessionState {
        UNINITIALIZED,
        VALID,
        EXPIRED,
        SOLVING
    }
    
    @Volatile
    private var sessionState: SessionState = SessionState.UNINITIALIZED
    
    init {
        // Load persisted state on init
        loadPersistedState()
    }
    
    // ========== PUBLIC API ==========
    
    /**
     * Makes an HTTP request with automatic CF solving and cookie management.
     * 
     * @param url The URL to request
     * @return StrategyResponse with HTML content
     */
    suspend fun request(url: String): StrategyResponse {
        val startTime = System.currentTimeMillis()
        val hasValidCookies = cookieManager.isValid(url)
        
        ProviderLogger.logRequestStart(url, 
            if (hasValidCookies) "DirectHttp" else "WebView",
            hasValidCookies
        )
        
        // Determine strategy
        val context = RequestContext(
            url = url,
            hasValidCookies = hasValidCookies
        )
        
        // Try DirectHttp first if cookies are valid
        if (directHttpStrategy.canHandle(context)) {
            val response = directHttpStrategy.execute(buildRequest(url))
            
            if (response.success) {
                // Update cookie last-used timestamp
                cookieManager.retrieve(url)
                
                ProviderLogger.logRequestComplete(url, response.responseCode, 
                    System.currentTimeMillis() - startTime, "DirectHttp")
                
                return response
            }
            
            if (response.isCloudflareChallenge) {
                ProviderLogger.w(TAG_SESSION, "request", "CF challenge, falling back to WebView",
                    "url" to url.take(80)
                )
                
                // Invalidate cookies
                cookieManager.invalidate(url, "CF challenge on DirectHttp")
            }
        }
        
        // Use WebView (with mutex to prevent concurrent challenges)
        return cfMutex.withLock {
            updateSessionState(SessionState.SOLVING, "CF challenge")
            
            val response = webViewStrategy.execute(buildRequest(url))
            
            if (response.success && response.cookies.isNotEmpty()) {
                // Store cookies
                cookieManager.store(url, response.cookies, "WebView")
                stateStore.saveCookies(response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                
                updateSessionState(SessionState.VALID, "CF solved")
            } else {
                updateSessionState(SessionState.EXPIRED, "CF solve failed")
            }
            
            ProviderLogger.logRequestComplete(url, response.responseCode,
                System.currentTimeMillis() - startTime, "WebView")
            
            response
        }
    }
    
    /**
     * Sniffs video URLs from a player page.
     * Uses VideoSniffingStrategy which inherits all cookie management.
     * 
     * @param url Player page URL
     * @return List of captured video sources
     */
    suspend fun sniffVideos(url: String): List<VideoSource> {
        ProviderLogger.i(TAG_SESSION, "sniffVideos", "Starting video sniff",
            "url" to url.take(80)
        )
        
        val cookies = cookieManager.retrieve(url) ?: emptyMap()
        
        return cfMutex.withLock {
            videoSniffingStrategy.sniff(url, userAgent, cookies)
        }
    }
    
    /**
     * Updates the current domain.
     */
    fun updateDomain(domain: String) {
        val previousDomain = currentDomain
        currentDomain = domain
        stateStore.saveDomain(domain)
        
        ProviderLogger.i(TAG_SESSION, "updateDomain", "Domain updated",
            "from" to previousDomain,
            "to" to domain
        )
        
        // Invalidate cookies if domain changed
        if (previousDomain != domain) {
            cookieManager.invalidate(previousDomain, "domain changed")
        }
    }
    
    /**
     * Forces session invalidation.
     */
    fun invalidateSession(reason: String) {
        cookieManager.invalidate(currentDomain, reason)
        stateStore.clearCookies()
        updateSessionState(SessionState.EXPIRED, reason)
        
        ProviderLogger.w(TAG_SESSION, "invalidateSession", "Session invalidated",
            "reason" to reason
        )
    }
    
    /**
     * Checks if the session is currently valid.
     */
    fun hasValidSession(): Boolean {
        val isValid = cookieManager.isValid(currentDomain)
        
        ProviderLogger.d(TAG_SESSION, "hasValidSession", "Session check",
            "isValid" to isValid,
            "state" to sessionState.name
        )
        
        return isValid
    }
    
    /**
     * Gets headers for image requests (posterHeaders).
     */
    fun getImageHeaders(): Map<String, String> {
        val cookies = cookieManager.retrieve(currentDomain) ?: emptyMap()
        val headers = mutableMapOf("User-Agent" to userAgent)
        
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        headers["Referer"] = currentDomain
        
        return headers
    }
    
    // ========== PRIVATE HELPERS ==========
    
    private fun buildRequest(url: String): StrategyRequest {
        val cookies = cookieManager.retrieve(url) ?: emptyMap()
        
        return StrategyRequest(
            url = url,
            userAgent = userAgent,
            cookies = cookies,
            referer = currentDomain
        )
    }
    
    private fun updateSessionState(newState: SessionState, trigger: String) {
        val previousState = sessionState
        sessionState = newState
        
        ProviderLogger.logSessionState(newState.name, previousState.name, trigger)
    }
    
    private fun loadPersistedState() {
        ProviderLogger.d(TAG_SESSION, "loadPersistedState", "Loading persisted state")
        
        // Load domain
        val savedDomain = stateStore.loadDomain()
        if (savedDomain != null && stateStore.isDomainFresh()) {
            currentDomain = savedDomain
            ProviderLogger.i(TAG_SESSION, "loadPersistedState", "Restored domain",
                "domain" to savedDomain
            )
        }
        
        // Load cookies
        val savedCookies = stateStore.loadCookies()
        if (savedCookies != null && stateStore.areCookiesFresh()) {
            // Parse and store in memory
            val cookieMap = parseCookieString(savedCookies)
            if (cookieMap.isNotEmpty()) {
                cookieManager.store(currentDomain, cookieMap, "Restored")
                updateSessionState(SessionState.VALID, "restored from prefs")
                
                ProviderLogger.i(TAG_SESSION, "loadPersistedState", "Restored cookies",
                    "cookieCount" to cookieMap.size,
                    "hasClearance" to cookieMap.containsKey("cf_clearance")
                )
            }
        } else {
            updateSessionState(SessionState.UNINITIALIZED, "no persisted state")
        }
    }
    
    private fun parseCookieString(cookieHeader: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        
        cookieHeader.split(";").forEach { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) {
                cookies[parts[0].trim()] = parts[1].trim()
            }
        }
        
        return cookies
    }
}
