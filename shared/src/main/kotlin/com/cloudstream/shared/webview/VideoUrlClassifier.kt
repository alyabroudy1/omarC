package com.cloudstream.shared.webview

/**
 * Shared URL classification logic for video sniffing.
 *
 * Centralizes all URL validation rules used by both CfBypassEngine and VideoSnifferEngine,
 * eliminating duplication between WebViewEngine and SnifferExtractor.
 */
object VideoUrlClassifier {

    /** Domains whose streams are DRM-protected and cannot be played in ExoPlayer. */
    private val DRM_DOMAIN_KEYWORDS = listOf("mbc")

    /** File extensions that indicate DRM manifests (unplayable without Widevine). */
    private val DRM_EXTENSIONS = listOf(".mpd")

    /** Segment/asset patterns that should never be captured as video links. */
    private val SEGMENT_EXTENSIONS = listOf(".ts", ".key", ".png", ".jpg", ".gif", ".css", ".js")
    private val SEGMENT_KEYWORDS = listOf("favicon")

    /**
     * Determines if a URL is an extractable video stream.
     * Rejects: blacklisted URLs, DRM-protected streams, and segment/asset files.
     * Accepts: .m3u8, .mp4, .mkv, .webm, blob: URLs.
     */
    fun isVideoUrl(url: String): Boolean {
        if (isBlacklisted(url)) return false

        // Blob URLs (WebRTC/MediaSource) are always considered video
        if (url.startsWith("blob:")) return true

        // Reject DRM-protected streams (DASH manifests, known DRM CDN domains)
        if (isDrmProtected(url)) return false

        // Reject segments and non-video assets
        if (isSegmentOrAsset(url)) return false

        // Check for video file patterns
        return url.contains(".m3u8", ignoreCase = true) ||
               url.contains(".mp4", ignoreCase = true) ||
               url.contains(".mkv", ignoreCase = true) ||
               url.contains(".webm", ignoreCase = true)
    }

    /** Check if a URL belongs to a DRM-protected domain or uses a DRM format. */
    fun isDrmProtected(url: String): Boolean {
        val lower = url.lowercase()
        return DRM_EXTENSIONS.any { lower.contains(it) } ||
               DRM_DOMAIN_KEYWORDS.any { lower.contains(it) }
    }

    /** Check if a URL is a segment/asset that should be filtered out. */
    fun isSegmentOrAsset(url: String): Boolean {
        val lower = url.lowercase()
        return SEGMENT_EXTENSIONS.any { lower.contains(it) } ||
               SEGMENT_KEYWORDS.any { lower.contains(it) }
    }

    /** Check if a URL is a known analytics/tracking endpoint to ignore. */
    fun isBlacklisted(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("/ping.gif") ||
               lowerUrl.contains("/analytics") ||
               lowerUrl.contains("/google-analytics") ||
               lowerUrl.contains("favicon.ico")
    }

    /**
     * Determines if a URL is an HLS Master M3U8 playlist.
     * Master playlists contain multiple sub-streams (qualities) rather than chunks.
     */
    fun isMasterM3u8(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.contains(".m3u8")) return false
        if (lower.contains("master.m3u8") || 
            lower.contains("playlist.m3u8") || 
            lower.contains("manifest.m3u8")) {
            return true
        }
        
        // Exclude common patterns/keywords representing specific sub-playlists or chunks
        val hasQualityPattern = lower.contains("360") || 
                               lower.contains("480") || 
                               lower.contains("720") || 
                               lower.contains("1080") || 
                               lower.contains("240") || 
                               lower.contains("low") || 
                               lower.contains("mobile") ||
                               lower.contains("chunklist")
        return !hasQualityPattern
    }
}
