package com.cloudstream.shared.util

import android.content.Context
import android.webkit.WebSettings
import com.cloudstream.shared.logging.ProviderLogger

/**
 * Centralized User-Agent configuration for WebView and HTTP requests.
 *
 * Uses the system's REAL WebView User-Agent to prevent Cloudflare fingerprint mismatch.
 * Cloudflare cross-checks the UA header against the actual browser JS engine capabilities
 * (TLS fingerprint, navigator.userAgentData, etc.). A forged/outdated UA triggers bot detection.
 *
 * Modeled after omerFlex5's WebConfig.java which successfully bypasses CF.
 */
object WebConfig {
    private const val TAG = "WebConfig"

    @Volatile
    private var cachedUserAgent: String? = null

    /** Fallback only if WebView is not available (e.g., instant-run, test harness) */
    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * Returns the device's real WebView User-Agent, cached after first call.
     * Ensures "Mobile" is present for consistent mobile-site CF challenges.
     */
    fun getUserAgent(context: Context): String {
        cachedUserAgent?.let { return it }

        return try {
            var systemUa = WebSettings.getDefaultUserAgent(context)

            // Force "Mobile" if missing (ensures mobile Cloudflare challenges)
            if (!systemUa.contains("Mobile")) {
                systemUa = systemUa.replace("Safari", "Mobile Safari")
            }

            // Remove "wv" marker that identifies Android WebView to CF
            // e.g. "Chrome/131.0.6778.200" stays, but "; wv)" becomes ")"
            systemUa = systemUa.replace("; wv)", ")")

            cachedUserAgent = systemUa
            ProviderLogger.i(TAG, "getUserAgent", "System UA resolved",
                "ua" to systemUa.take(120))
            systemUa
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "getUserAgent", "Failed to get system UA, using fallback",
                "error" to e.message)
            cachedUserAgent = FALLBACK_USER_AGENT
            FALLBACK_USER_AGENT
        }
    }

    /**
     * Returns the cached User-Agent if already initialized, or the fallback.
     * Use this when no Context is available (e.g., from SessionProvider).
     */
    fun getCachedUserAgent(): String {
        return cachedUserAgent ?: FALLBACK_USER_AGENT
    }

    /**
     * Extracts the Chrome major version from this UA string.
     * e.g. "...Chrome/131.0.6778.200..." → "131"
     * Returns null if no Chrome version found.
     */
    fun extractChromeVersion(ua: String): String? {
        val match = Regex("""Chrome/(\d+)""").find(ua)
        return match?.groupValues?.getOrNull(1)
    }

    /**
     * Builds Sec-Ch-Ua header value matching the actual Chrome version.
     * e.g. for Chrome/131 → "Not_A Brand";v="8", "Chromium";v="131", "Google Chrome";v="131"
     */
    fun buildSecChUa(ua: String): String {
        val version = extractChromeVersion(ua) ?: "131"
        return "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"$version\", \"Google Chrome\";v=\"$version\""
    }
}
