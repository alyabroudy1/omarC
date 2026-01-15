package com.faselhd.service

import android.content.Context
import com.lagradost.cloudstream3.app
import com.faselhd.utils.DomainConfigManager
import com.faselhd.service.cloudflare.CloudflareDetector
import com.faselhd.service.cookie.CookieLifecycleManager
import com.faselhd.service.strategy.*
import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_SESSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * 2. If valid: Use DirectHttpStrategy (fast path)
 * 3. If invalid: Acquire mutex, double-check cookies, use WebView
 * 4. Store cookies after successful CF solve
 * 5. Release mutex, other requests proceed with new cookies
 * 
 * ## Request Serialization:
 * - First request triggers CF solving (acquires mutex)
 * - Concurrent requests wait on mutex
 * - After CF solved, all requests use DirectHttp with valid cookies
 * 
 * ## Double-Check Locking:
 * - Check cookies BEFORE mutex (fast path for valid cookies)
 * - Check cookies AFTER mutex acquisition (another request may have solved)
 * - Only solve CF if still needed after acquiring mutex
 * 
 * @param context Android context
 * @param providerName Provider identifier
 * @param rawUserAgent Static User-Agent from system
 * @param fallbackDomain Default domain if no config available
 */
class ProviderSessionManager(
    private val context: Context,
    val providerName: String,
    private val rawUserAgent: String,
    private val fallbackDomain: String
) {
    
    /** 
     * Sanitized User-Agent.
     * Removes "wv" and "Version/x.x" to look like a standard Chrome browser.
     * This is critical to avoid Cloudflare detecting mismatch between UA (webview) 
     * and TLS fingerprint (OkHttp).
     */
    
    companion object {
        /**
         * UNIFIED USER-AGENT: The ONE and ONLY source of truth.
         * 
         * This EXACT string is used by:
         * - DirectHttpStrategy (OkHttp requests)
         * - WebViewStrategy (ConfigurableCloudflareKiller)
         * - ConfigurableWebViewResolver (WebView.settings.userAgentString)
         * 
         * It is a sanitized Chrome Mobile UA WITHOUT WebView markers ("wv", "Version/4.0").
         * Cloudflare binds cookies to the User-Agent. If ANY component uses a different UA,
         * the cookie will be rejected and we get 403.
         */
        const val UNIFIED_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
    }
    
    /** 
     * User-Agent for all requests. 
     * Always uses UNIFIED_USER_AGENT - never null, never from store, never from system.
     */
    val userAgent: String = UNIFIED_USER_AGENT

    /** Cookie lifecycle manager */
    val cookieManager = CookieLifecycleManager()
    
    /** Cloudflare detector */
    private val cfDetector = CloudflareDetector()
    
    /** Persistent state store */
    private val stateStore = ProviderStateStore(context, providerName)
    
    /** Domain configuration manager - handles GitHub fetch and Worker sync */
    private val domainConfigManager = DomainConfigManager(
        providerName = providerName,
        configFileName = "${providerName.lowercase()}.json",
        fallbackDomain = fallbackDomain
    )
    
    /** Request strategies */
    private val directHttpStrategy = DirectHttpStrategy()
    private val webViewStrategy by lazy { 
        WebViewStrategy(cookieManager, userAgent, cfDetector) 
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
    
    /** Whether initialize() has been called */
    @Volatile
    var isInitialized: Boolean = false
        private set
    
    init {
        ProviderLogger.d(TAG_SESSION, "init", "ProviderSessionManager created",
            "provider" to providerName,
            "fallback" to fallbackDomain
        )
    }
    
    // ========== INITIALIZATION ==========
    
    /**
     * Initialize the session manager.
     * Call this ONCE when the provider first loads (e.g., in getMainPage).
     * 
     * Flow:
     * 1. Load persisted state from disk
     * 2. Fetch latest domain from GitHub
     * 3. Update state if domain changed
     */
    suspend fun initialize() {
        if (isInitialized) return
        
        ProviderLogger.i(TAG_SESSION, "initialize", "Starting initialization")
        
        // 1. Load persisted state
        loadPersistedState()
        
        // 2. Fetch latest domain from GitHub
        try {
            domainConfigManager.initialize()
            val remoteDomain = domainConfigManager.currentDomain
            
            if (remoteDomain != currentDomain) {
                ProviderLogger.i(TAG_SESSION, "initialize", "Domain from GitHub differs",
                    "local" to currentDomain,
                    "remote" to remoteDomain
                )
                updateDomain(remoteDomain)
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG_SESSION, "initialize", "GitHub fetch failed: ${e.message}")
        }
        
        isInitialized = true
        ProviderLogger.i(TAG_SESSION, "initialize", "Initialization complete",
            "domain" to currentDomain,
            "hasSession" to (sessionState == SessionState.VALID)
        )
    }
    
    // ========== DOMAIN CHANGE HANDLING ==========
    
    /**
     * Called when a domain change is detected (from WebView or HTTP redirect).
     * Updates local state AND syncs to Cloudflare Worker.
     */
    fun onDomainChanged(oldDomain: String, newDomain: String) {
        val cleanNewDomain = if (newDomain.startsWith("http")) newDomain else "https://$newDomain"
        
        if (cleanNewDomain == currentDomain) return
        
        ProviderLogger.i(TAG_SESSION, "onDomainChanged", "Domain change detected",
            "from" to oldDomain,
            "to" to cleanNewDomain
        )
        
        // Update local state
        updateDomain(cleanNewDomain)
        
        // Sync to Cloudflare Worker (async, fire-and-forget)
        CoroutineScope(Dispatchers.IO).launch {
            domainConfigManager.checkForDomainChange(cleanNewDomain)
        }
    }
    
    /**
     * Called when cookies are extracted (from WebView).
     * Updates ProviderState and persists to disk.
     */
    fun onCookiesExtracted(domain: String, cookies: Map<String, String>) {
        val targetDomain = if (domain.startsWith("http")) domain else "https://$domain"
        
        ProviderLogger.i(TAG_SESSION, "onCookiesExtracted", "Cookies received",
            "domain" to targetDomain,
            "count" to cookies.size,
            "keys" to cookies.keys.toString()
        )
        
        // Store in memory
        cookieManager.store(targetDomain, cookies, "WebView")
        
        // Persist to disk
        stateStore.saveCookies(cookies)
        
        updateSessionState(SessionState.VALID, "cookies extracted")
    }
    
    // ========== PUBLIC API ==========
    
    /**
     * Makes an HTTP request with automatic CF solving and cookie management.
     * 
     * Implements double-check locking for efficient concurrent request handling:
     * 1. First check: Are cookies valid? If yes, use DirectHttp (no lock)
     * 2. If not valid, acquire mutex
     * 3. Second check: Are cookies valid now? (Another request may have solved)
     * 4. If still not valid, solve CF
     * 5. Release mutex, return response
     * 
     * @param url The URL to request
     * @return StrategyResponse with HTML content
     */
    suspend fun request(url: String): StrategyResponse {
        val startTime = System.currentTimeMillis()
        
        // ====== OPTIMISTIC DIRECT HTTP (Always try first) ======
        // We assume whatever cookies we have (or none) might work.
        val cookies = cookieManager.retrieve(url) ?: emptyMap()
        
        if (directHttpStrategy.canHandle(RequestContext(url, hasValidCookies = true))) {
             val response = directHttpStrategy.execute(buildRequest(url))
             
             if (response.success) {
                 cookieManager.retrieve(url) // Update timestamp
                 ProviderLogger.logRequestComplete(url, response.responseCode, 
                     System.currentTimeMillis() - startTime, "DirectHttp-Optimistic")
                 return response
             }
             
             // If NOT a 403/503 (e.g. 404, 500), return as is.
             if (!response.isCloudflareChallenge) {
                 return response
             }
             
             ProviderLogger.w(TAG_SESSION, "request", "DirectHttp failed (CF/403), triggering refresh",
                 "code" to response.responseCode
             )
        }
        
        // ====== REFRESH FLOW (Mutex Locked) ======
        return cfMutex.withLock {
            
            // ====== DOUBLE-CHECK LOCKING ======
            // A previous request might have just solved the challenge while we were waiting.
            // Before nuking the session, let's see if we have fresh cookies.
            val currentCookies = cookieManager.retrieve(url)
            var startFresh = true // Default to clearing everything unless we find reason to keep

            if (!currentCookies.isNullOrEmpty()) {
                ProviderLogger.d(TAG_SESSION, "request", "Double-check: Potential fresh cookies found",
                     "count" to currentCookies.size
                )
                
                // Try DirectHttp one more time with these new cookies
                if (directHttpStrategy.canHandle(RequestContext(url, hasValidCookies = true))) {
                    val retryResponse = directHttpStrategy.execute(buildRequest(url))
                    
                    // If it works now, great! We saved a full WebView load.
                    if (retryResponse.success) {
                        ProviderLogger.logRequestComplete(url, retryResponse.responseCode,
                            System.currentTimeMillis() - startTime, "DirectHttp-DoubleCheck")
                        return@withLock retryResponse
                    }
                    
                    // If it's NOT a 403 (e.g. 404), return it.
                    if (!retryResponse.isCloudflareChallenge) {
                        return@withLock retryResponse
                    }
                    
                    // If 403, we failed DirectHttp but cookies MIGHT be good for WebView (CFKiller).
                    // So we DO NOT clear them. We let WebViewStrategy try them first.
                    ProviderLogger.w(TAG_SESSION, "request", "Double-check failed (still 403), falling back to WebViewStrategy",
                        "code" to retryResponse.responseCode
                    )
                    startFresh = false
                }
            }

            if (startFresh) {
                // CLEAR EVERYTHING (Safety Net)
                cookieManager.invalidate(url, "403 Refresh Trigger")
                webViewStrategy.clearCookies(url) // Clear memory & system cookies
                updateSessionState(SessionState.SOLVING, "Refresh triggered")
                
                ProviderLogger.i(TAG_SESSION, "request", "Starting fresh WebView solve",
                    "url" to url.take(80)
                )
            } else {
                 updateSessionState(SessionState.SOLVING, "Soft Refresh (Cookies preserved)")
                 ProviderLogger.i(TAG_SESSION, "request", "Starting WebView solve (Cookies preserved)",
                    "url" to url.take(80)
                )
            }
            
            val response = webViewStrategy.execute(buildRequest(url))
            
            if (response.success && response.cookies.isNotEmpty()) {
                // Store new cookies
                cookieManager.store(url, response.cookies, "WebView-Fresh")
                stateStore.saveCookies(response.cookies)
                
                updateSessionState(SessionState.VALID, "Fresh solve")
                
                ProviderLogger.i(TAG_SESSION, "request", "Fresh solve successful",
                    "cookieCount" to response.cookies.size
                )
                
                // UA is now a CONSTANT (UNIFIED_USER_AGENT) - no sync needed.
                // Cookies are already stored by WebViewStrategy.
            } else {
                updateSessionState(SessionState.EXPIRED, "Fresh solve failed")
            }
            
            ProviderLogger.logRequestComplete(url, response.responseCode,
                System.currentTimeMillis() - startTime, "WebView-Fresh")
            
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
        
        // Player pages need JS execution for JWPlayer extraction
        // Skip DirectHttp and go straight to WebView with JS injection
        val cookies = cookieManager.retrieve(url) ?: emptyMap()
        
        return cfMutex.withLock {
            videoSniffingStrategy.sniff(url, userAgent ?: rawUserAgent, cookies)
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
        
        // Migrate cookies if domain changed
        if (previousDomain != domain && previousDomain.isNotEmpty()) {
            val oldCookies = cookieManager.retrieve(previousDomain)
            if (!oldCookies.isNullOrEmpty()) {
                 cookieManager.store(domain, oldCookies, "Migrated from $previousDomain")
                 stateStore.saveCookies(oldCookies) // Persist for new domain
                 
                 ProviderLogger.i(TAG_SESSION, "updateDomain", "Cookies migrated",
                     "count" to oldCookies.size
                 )
            }
            // User request: Do NOT invalidate cookies.
            // keeping them available for the new domain is sufficient.
            // cookieManager.invalidate(previousDomain, "domain changed") 
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
        val headers = mutableMapOf("User-Agent" to (userAgent ?: rawUserAgent))
        
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        headers["Referer"] = currentDomain
        
        return headers
    }
    
    // ========== PRIVATE HELPERS ==========
    
    private fun buildRequest(url: String): StrategyRequest {
        val cookies = cookieManager.retrieve(url) ?: emptyMap()
        
        // Inject standard browser headers to match WebView/Chrome
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1"
        )
        
        return StrategyRequest(
            url = url,
            userAgent = userAgent ?: rawUserAgent,
            cookies = cookies,
            referer = currentDomain,
            headers = headers
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
        
        // Load cookies (now returns Map directly)
        val savedCookies = stateStore.loadCookies()
        if (savedCookies.isNotEmpty() && stateStore.areCookiesFresh()) {
            cookieManager.store(currentDomain, savedCookies, "Restored")
            updateSessionState(SessionState.VALID, "restored from prefs")
            
            ProviderLogger.i(TAG_SESSION, "loadPersistedState", "Restored cookies",
                "cookieCount" to savedCookies.size
            )
        } else {
            updateSessionState(SessionState.UNINITIALIZED, "no persisted state")
        }
    }
}
