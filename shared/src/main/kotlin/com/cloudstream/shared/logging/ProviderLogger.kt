package com.cloudstream.shared.logging

import com.lagradost.api.Log

/**
 * Standardized logging utility for provider services.
 * 
 * Log Format: `[TAG] [METHOD] message | key=value, key=value`
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
    const val TAG_QUEUE = "RequestQueue"
    const val TAG_PROVIDER_HTTP = "ProviderHttp"
    
    // ========== LOG LEVELS ==========
    
    fun d(tag: String, method: String, message: String, vararg params: Pair<String, Any?>) {
        Log.d(tag, "[$method] $message${formatParams(params)}")
    }
    
    fun i(tag: String, method: String, message: String, vararg params: Pair<String, Any?>) {
        Log.i(tag, "[$method] $message${formatParams(params)}")
    }
    
    fun w(tag: String, method: String, message: String, vararg params: Pair<String, Any?>) {
        Log.w(tag, "[$method] $message${formatParams(params)}")
    }
    
    fun e(tag: String, method: String, message: String, error: Throwable? = null, vararg params: Pair<String, Any?>) {
        val errorMsg = error?.let { " | error=${it.message}" } ?: ""
        Log.e(tag, "[$method] $message${formatParams(params)}$errorMsg")
    }
    
    // ========== COOKIE LIFECYCLE ==========
    
    fun logCookieStore(domain: String, cookies: Map<String, String>, source: String) {
        i(TAG_COOKIE, "store", "Cookies stored",
            "domain" to domain, "source" to source, "count" to cookies.size,
            "hasClearance" to cookies.containsKey("cf_clearance"))
    }
    
    fun logCookieRetrieve(domain: String, found: Boolean, cookieCount: Int, age: Long?) {
        if (found) i(TAG_COOKIE, "retrieve", "Cookies found", "domain" to domain, "count" to cookieCount)
        else w(TAG_COOKIE, "retrieve", "No cookies found", "domain" to domain)
    }
    
    fun logCookieFreshness(domain: String, isValid: Boolean, ageMs: Long, maxAgeMs: Long) {
        if (isValid) d(TAG_COOKIE, "freshness", "Cookies valid", "domain" to domain, "remaining" to (maxAgeMs - ageMs))
        else w(TAG_COOKIE, "freshness", "Cookies expired", "domain" to domain, "ageMs" to ageMs)
    }
    
    fun logCookieInvalidate(domain: String, reason: String) {
        w(TAG_COOKIE, "invalidate", "Cookies invalidated", "domain" to domain, "reason" to reason)
    }
    
    // ========== SESSION ==========
    
    fun logSessionState(newState: String, previousState: String?, trigger: String) {
        i(TAG_SESSION, "stateChange", "Session state changed",
            "from" to (previousState ?: "INIT"), "to" to newState, "trigger" to trigger)
    }
    
    fun logRequestStart(url: String, strategy: String, hasValidCookies: Boolean) {
        d(TAG_SESSION, "request", "Request starting",
            "url" to url.take(80), "strategy" to strategy, "hasValidCookies" to hasValidCookies)
    }
    
    fun logRequestComplete(url: String, responseCode: Int, durationMs: Long, strategy: String) {
        if (responseCode in 200..299) i(TAG_SESSION, "request", "Request complete", "code" to responseCode, "durationMs" to durationMs, "strategy" to strategy)
        else w(TAG_SESSION, "request", "Request complete with error", "code" to responseCode, "durationMs" to durationMs, "strategy" to strategy)
    }
    
    private fun formatParams(params: Array<out Pair<String, Any?>>): String {
        if (params.isEmpty()) return ""
        return " | " + params.joinToString(", ") { (k, v) -> "$k=$v" }
    }
}
