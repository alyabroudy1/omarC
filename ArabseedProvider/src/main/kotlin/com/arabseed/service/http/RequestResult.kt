package com.arabseed.service.http

/**
 * Result of an HTTP request through the service.
 */
data class RequestResult(
    val success: Boolean,
    val html: String?,
    val responseCode: Int,
    val finalUrl: String?,
    val error: Throwable? = null,
    val isCloudflareBlocked: Boolean = false
) {
    companion object {
        fun success(html: String, code: Int = 200, finalUrl: String) = RequestResult(
            success = true,
            html = html,
            responseCode = code,
            finalUrl = finalUrl
        )
        
        fun cloudflareBlocked(code: Int, finalUrl: String? = null) = RequestResult(
            success = false,
            html = null,
            responseCode = code,
            finalUrl = finalUrl,
            isCloudflareBlocked = true
        )
        
        fun failure(reason: String, code: Int = -1) = RequestResult(
            success = false,
            html = null,
            responseCode = code,
            finalUrl = null,
            error = Exception(reason)
        )
        
        fun failure(error: Throwable, code: Int = -1) = RequestResult(
            success = false,
            html = null,
            responseCode = code,
            finalUrl = null,
            error = error
        )
    }
}
