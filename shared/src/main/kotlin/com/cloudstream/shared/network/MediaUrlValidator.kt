package com.cloudstream.shared.network

import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.session.SessionState
import com.cloudstream.shared.util.WebConfig
import com.cloudstream.shared.webview.VideoUrlClassifier

/**
 * Pre-validates media URLs to detect TLS-fingerprint-based CDN blocks
 * BEFORE they reach ExoPlayer.
 *
 * PROBLEM: Video URLs captured by WebView (Chrome TLS) may return 403
 * when ExoPlayer requests them with OkHttp's different TLS fingerprint.
 * By that point the user sees a playback error with no graceful fallback.
 *
 * SOLUTION: Issue a lightweight HEAD/GET request via OkHttp first. If it
 * returns 403, mark the source as `tlsBlocked` so the provider can:
 *   (a) Fall back to sniffer-as-player mode (WebView plays directly)
 *   (b) Try a different server/quality
 *   (c) Route through ChromiumFetcher for the manifest fetch
 *
 * NOTE: This validator uses OkHttp intentionally — we WANT to test with
 * the same TLS stack that ExoPlayer will use. If OkHttp gets 403,
 * ExoPlayer will too.
 */
class MediaUrlValidator {
    companion object {
        private const val TAG = "MediaUrlValidator"

        /** Timeout for validation HEAD request (ms) */
        private const val VALIDATION_TIMEOUT_MS = 8_000L

        /** CDN response codes that indicate TLS/fingerprint block */
        private val BLOCK_CODES = setOf(403, 503)

        /** Skip validation for these domains (known to be always-open) */
        private val SKIP_DOMAINS = setOf(
            "googlevideo.com",
            "youtube.com",
            "googleapis.com",
            "akamaized.net",
        )
    }

    /**
     * Validate a list of video sources, marking each as accessible or blocked.
     *
     * @param sources List of video source URLs to validate
     * @param sessionState Current session for building headers
     * @return List of [ValidatedSource] with accessibility status
     */
    suspend fun validateSources(
        sources: List<VideoSourceCandidate>,
        sessionState: SessionState
    ): List<ValidatedSource> {
        if (sources.isEmpty()) return emptyList()

        return sources.map { source ->
            // Skip validation for known-good domains
            if (shouldSkipValidation(source.url)) {
                return@map ValidatedSource(source, accessible = true, skipReason = "trusted_domain")
            }

            // Skip validation for blob: URLs (can't be validated externally)
            if (source.url.startsWith("blob:")) {
                return@map ValidatedSource(source, accessible = true, skipReason = "blob_url")
            }

            try {
                val result = validateSingleUrl(source.url, source.headers, sessionState)
                ValidatedSource(
                    source = source,
                    accessible = result.accessible,
                    statusCode = result.statusCode,
                    tlsBlocked = result.isTlsBlock,
                    skipReason = null
                )
            } catch (e: Exception) {
                ProviderLogger.w(TAG, "validateSources", "Validation failed",
                    "url" to source.url.take(80), "error" to e.message)
                // On error, assume accessible (don't block playback on validation failure)
                ValidatedSource(source, accessible = true, skipReason = "validation_error")
            }
        }
    }

    /**
     * Quick single-URL check. Returns true if the URL is likely accessible by ExoPlayer.
     */
    suspend fun isAccessible(
        url: String,
        headers: Map<String, String>,
        sessionState: SessionState
    ): Boolean {
        if (shouldSkipValidation(url)) return true
        if (url.startsWith("blob:")) return true

        return try {
            val result = validateSingleUrl(url, headers, sessionState)
            result.accessible
        } catch (_: Exception) {
            true // Assume accessible on error
        }
    }

    private suspend fun validateSingleUrl(
        url: String,
        sourceHeaders: Map<String, String>,
        sessionState: SessionState
    ): ValidationResult {
        // Build headers matching what ExoPlayer would send
        val headers = buildExoPlayerHeaders(sourceHeaders, sessionState)

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(VALIDATION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(VALIDATION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()

        val requestBuilder = okhttp3.Request.Builder().url(url)

        // Use HEAD for efficiency — we don't need the body
        // Some CDNs don't support HEAD, so we fall back to Range GET
        requestBuilder.addHeader("Range", "bytes=0-0")

        for ((k, v) in headers) {
            requestBuilder.addHeader(k, v)
        }

        val request = requestBuilder.build()

        val response = client.newCall(request).execute()
        val code = response.code
        val serverHeader = response.header("Server") ?: ""
        response.close()

        val isBlock = code in BLOCK_CODES
        val isCloudflare = serverHeader.contains("cloudflare", ignoreCase = true)
        val isTlsBlock = isBlock && isCloudflare

        ProviderLogger.d(TAG, "validateSingleUrl", "Validation result",
            "url" to url.take(80),
            "code" to code,
            "server" to serverHeader.take(30),
            "accessible" to !isBlock,
            "tlsBlock" to isTlsBlock)

        return ValidationResult(
            statusCode = code,
            accessible = !isBlock,
            isTlsBlock = isTlsBlock
        )
    }

    /**
     * Build headers that match what ExoPlayer's DefaultHttpDataSource would send.
     * This ensures the validation accurately predicts ExoPlayer's outcome.
     */
    private fun buildExoPlayerHeaders(
        sourceHeaders: Map<String, String>,
        sessionState: SessionState
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        // ExoPlayer sends these by default
        headers["User-Agent"] = sourceHeaders["User-Agent"]
            ?: sessionState.userAgent
        headers["Accept-Encoding"] = "identity" // ExoPlayer default for media
        headers["Accept"] = "*/*"

        // Forward source-specific headers (Referer, Cookie, etc.)
        for ((k, v) in sourceHeaders) {
            headers[k] = v
        }

        // If no cookie in source headers, inject session cookies
        if (!headers.containsKey("Cookie") && sessionState.cookies.isNotEmpty()) {
            headers["Cookie"] = sessionState.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        return headers
    }

    private fun shouldSkipValidation(url: String): Boolean {
        return try {
            val host = java.net.URI(url).host?.lowercase() ?: return false
            SKIP_DOMAINS.any { host.endsWith(it) }
        } catch (_: Exception) { false }
    }

    private data class ValidationResult(
        val statusCode: Int,
        val accessible: Boolean,
        val isTlsBlock: Boolean
    )
}

/**
 * A video source candidate for validation.
 */
data class VideoSourceCandidate(
    val url: String,
    val quality: String = "Auto",
    val headers: Map<String, String> = emptyMap()
)

/**
 * Result of media URL validation.
 */
data class ValidatedSource(
    val source: VideoSourceCandidate,
    val accessible: Boolean,
    val statusCode: Int = 0,
    val tlsBlocked: Boolean = false,
    val skipReason: String? = null
) {
    /** Should the provider fall back to WebView-based playback? */
    val needsWebViewPlayback: Boolean
        get() = !accessible && tlsBlocked
}
