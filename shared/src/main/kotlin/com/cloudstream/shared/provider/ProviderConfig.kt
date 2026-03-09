package com.cloudstream.shared.provider

/**
 * Provider configuration for shared infrastructure.
 */
data class ProviderConfig(
    /** Provider name for logging and storage */
    val name: String,
    
    /** Default domain if no config available */
    val fallbackDomain: String,
    
    /** GitHub URL for domain config (optional) */
    val githubConfigUrl: String? = null,
    
    /** CloudFlare Worker URL for domain sync (optional) */
    val syncWorkerUrl: String? = null,
    
    /** Custom User-Agent (if null, uses UNIFIED_USER_AGENT) */
    val userAgent: String? = null,
    
    /** Whether to enable WebView fallback for CF bypass */
    val webViewEnabled: Boolean = true,
    
    /** Skip headless WebView, use visible instead */
    val skipHeadless: Boolean = false,
    
    /** Trusted domain substrings for private server detection */
    val trustedDomains: List<String> = emptyList(),
    
    /** Content validation strings to verify correct domain */
    val validateWithContent: List<String> = emptyList(),
    
    /** Cookie max age in ms (default: 30 minutes) */
    val cookieMaxAgeMs: Long = 30 * 60 * 1000,
    
    /** Request timeout in ms */
    val requestTimeoutMs: Long = 30_000,
    
    /** Video sniff timeout in ms */
    val videoSniffTimeoutMs: Long = 35_000
)

/**
 * Unified User-Agent: Single source of truth.
 * 
 * Now dynamically resolved via [com.cloudstream.shared.util.WebConfig].
 * Uses the device's REAL WebView User-Agent to prevent Cloudflare
 * fingerprint mismatch (old Chrome/65 UA vs actual Chrome/131+ engine).
 * 
 * Cloudflare binds cookies to the User-Agent.
 */
val UNIFIED_USER_AGENT: String
    get() = com.cloudstream.shared.util.WebConfig.getCachedUserAgent()
