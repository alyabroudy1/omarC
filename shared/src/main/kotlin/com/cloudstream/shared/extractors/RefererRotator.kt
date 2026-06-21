package com.cloudstream.shared.extractors

import com.lagradost.api.Log

/**
 * Referer rotation utility for providers that need to try multiple referers
 * until one returns a successful response.
 */
object RefererRotator {
    private const val TAG = "RefererRotator"

    /**
     * Try multiple referers for a URL and return the first successful one.
     * The check is done via a HEAD request (checking response code).
     *
     * @param url The URL to test
     * @param candidates List of referers to try in order
     * @param httpGetDocument (url, referer) -> Int function that returns HTTP status code
     * @return The first working referer, or the first candidate if none work
     */
    suspend fun findWorkingReferer(
        url: String,
        candidates: List<String>,
        httpCheckStatus: suspend (String, String) -> Int?
    ): String {
        Log.d(TAG, "Checking working referer for: $url")
        for (ref in candidates) {
            try {
                val code = httpCheckStatus(url, ref)
                if (code != null && code in 200..399) {
                    Log.d(TAG, "Referer works ($code): $ref")
                    return ref
                } else {
                    Log.d(TAG, "Referer failed ($code): $ref")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Referer check error for $ref: ${e.message}")
            }
        }
        Log.d(TAG, "All checks failed, defaulting to: ${candidates.firstOrNull()}")
        return candidates.firstOrNull() ?: ""
    }
}
