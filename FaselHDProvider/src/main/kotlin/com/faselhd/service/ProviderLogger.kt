package com.faselhd.service

import com.lagradost.api.Log

/**
 * Standardized logging utility for the Provider HTTP Service.
 * 
 * All components use this logger for consistent formatting and easy filtering.
 * 
 * ## Log Format:
 * `[TAG] [METHOD] message | key=value, key=value`
 * 
 * ## Log Levels:
 * - DEBUG (D): Detailed flow information for development
 * - INFO (I): Important state changes and milestones
 * - WARN (W): Recoverable issues, fallbacks triggered
 * - ERROR (E): Failures that need attention
 * 
 * ## Tags:
 * - `ProviderSession`: Session lifecycle events
 * - `DirectHttp`: OkHttp request/response
 * - `WebViewStrategy`: WebView loading, CF solving
 * - `VideoSniffer`: Video URL detection
 * - `CookieLifecycle`: Cookie store/retrieve/expire
 * - `CFDetector`: Cloudflare challenge detection
 * - `DomainManager`: Domain auto-detection
 */
object ProviderLogger {
    
    // ========== TAGS ==========
    const val TAG_SESSION = "ProviderSession"
    const val TAG_DIRECT_HTTP = "DirectHttp"
    const val TAG_WEBVIEW = "WebViewStrategy"
    const val TAG_VIDEO_SNIFFER = "VideoSniffer"
    const val TAG_COOKIE = "CookieLifecycle"
    const val TAG_CF_DETECTOR = "CFDetector"
    const val TAG_DOMAIN = "DomainManager"
    const val TAG_STATE_STORE = "StateStore"
    
    // ========== DEBUG ==========
    
    /**
     * Log detailed debug information.
     * Use for flow tracing and development.
     */
    fun d(tag: String, method: String, message: String, vararg params: Pair<String, Any?>) {
        val paramStr = formatParams(params)
        Log.d(tag, "[$method] $message$paramStr")
    }
    
    // ========== INFO ==========
    
    /**
     * Log important state changes and milestones.
     * Use for key events that should always be visible.
     */
    fun i(tag: String, method: String, message: String, vararg params: Pair<String, Any?>) {
        val paramStr = formatParams(params)
        Log.i(tag, "[$method] $message$paramStr")
    }
    
    // ========== WARN ==========
    
    /**
     * Log recoverable issues or fallbacks.
     * Use when something unexpected happens but can be handled.
     */
    fun w(tag: String, method: String, message: String, vararg params: Pair<String, Any?>) {
        val paramStr = formatParams(params)
        Log.w(tag, "[$method] $message$paramStr")
    }
    
    // ========== ERROR ==========
    
    /**
     * Log errors that need attention.
     * Use for failures that may require investigation.
     */
    fun e(tag: String, method: String, message: String, error: Throwable? = null, vararg params: Pair<String, Any?>) {
        val paramStr = formatParams(params)
        val errorMsg = error?.let { " | error=${it.message}" } ?: ""
        Log.e(tag, "[$method] $message$paramStr$errorMsg")
    }
    
    // ========== COOKIE LIFECYCLE SPECIFIC ==========
    
    /**
     * Log cookie storage event.
     */
    fun logCookieStore(domain: String, cookies: Map<String, String>, source: String) {
        val cookieNames = cookies.keys.joinToString(",")
        val hasClearance = cookies.containsKey("cf_clearance")
        i(TAG_COOKIE, "store", "Cookies stored",
            "domain" to domain,
            "source" to source,
            "count" to cookies.size,
            "hasClearance" to hasClearance,
            "keys" to cookieNames
        )
    }
    
    /**
     * Log cookie retrieval event.
     */
    fun logCookieRetrieve(domain: String, found: Boolean, cookieCount: Int, age: Long?) {
        if (found) {
            i(TAG_COOKIE, "retrieve", "Cookies found",
                "domain" to domain,
                "found" to found,
                "count" to cookieCount,
                "ageMs" to (age ?: "N/A")
            )
        } else {
            w(TAG_COOKIE, "retrieve", "No cookies found",
                "domain" to domain,
                "found" to found,
                "count" to cookieCount,
                "ageMs" to (age ?: "N/A")
            )
        }
    }
    
    /**
     * Log cookie expiration check.
     */
    fun logCookieFreshness(domain: String, isValid: Boolean, ageMs: Long, maxAgeMs: Long) {
        if (isValid) {
            d(TAG_COOKIE, "freshness", "Cookies valid",
                "domain" to domain,
                "isValid" to isValid,
                "ageMs" to ageMs,
                "maxAgeMs" to maxAgeMs,
                "remaining" to (maxAgeMs - ageMs)
            )
        } else {
            w(TAG_COOKIE, "freshness", "Cookies expired",
                "domain" to domain,
                "isValid" to isValid,
                "ageMs" to ageMs,
                "maxAgeMs" to maxAgeMs,
                "remaining" to (maxAgeMs - ageMs)
            )
        }
    }
    
    /**
     * Log cookie invalidation.
     */
    fun logCookieInvalidate(domain: String, reason: String) {
        w(TAG_COOKIE, "invalidate", "Cookies invalidated",
            "domain" to domain,
            "reason" to reason
        )
    }
    
    // ========== SESSION LIFECYCLE SPECIFIC ==========
    
    /**
     * Log session state change.
     */
    fun logSessionState(newState: String, previousState: String?, trigger: String) {
        i(TAG_SESSION, "stateChange", "Session state changed",
            "from" to (previousState ?: "INIT"),
            "to" to newState,
            "trigger" to trigger
        )
    }
    
    /**
     * Log request start.
     */
    fun logRequestStart(url: String, strategy: String, hasValidCookies: Boolean) {
        d(TAG_SESSION, "request", "Request starting",
            "url" to url.take(80),
            "strategy" to strategy,
            "hasValidCookies" to hasValidCookies
        )
    }
    
    /**
     * Log request completion.
     */
    fun logRequestComplete(url: String, responseCode: Int, durationMs: Long, strategy: String) {
        if (responseCode in 200..299) {
            i(TAG_SESSION, "request", "Request complete",
                "url" to url.take(80),
                "code" to responseCode,
                "durationMs" to durationMs,
                "strategy" to strategy
            )
        } else {
            w(TAG_SESSION, "request", "Request complete",
                "url" to url.take(80),
                "code" to responseCode,
                "durationMs" to durationMs,
                "strategy" to strategy
            )
        }
    }
    
    // ========== HELPER ==========
    
    private fun formatParams(params: Array<out Pair<String, Any?>>): String {
        if (params.isEmpty()) return ""
        return " | " + params.joinToString(", ") { (k, v) -> "$k=$v" }
    }
}
