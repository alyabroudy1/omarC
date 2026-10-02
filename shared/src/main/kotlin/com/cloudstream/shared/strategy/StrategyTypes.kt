package com.cloudstream.shared.strategy

import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * Captured video source.
 * 
 * @param url The video URL
 * @param quality Quality label (e.g., "720p", "1080p", "Auto")
 * @param headers Optional headers for the video request
 * @param type Optional ExtractorLinkType for advanced uses
 * @param tlsVerified True if this URL was pre-validated to be accessible via OkHttp/ExoPlayer's
 *        TLS stack. False if it's known to be TLS-blocked (CDN rejects non-Chrome fingerprint).
 *        Null if not yet validated.
 */
data class VideoSource(
    val url: String,
    val quality: String = "Auto",
    val headers: Map<String, String> = emptyMap(),
    val type: ExtractorLinkType? = null,
    val tlsVerified: Boolean? = null
) {
    /** Alias for quality to maintain compatibility */
    val label: String get() = quality

    /** True if this source is known to be TLS-blocked and needs WebView playback */
    val needsWebViewPlayback: Boolean get() = tlsVerified == false
}
