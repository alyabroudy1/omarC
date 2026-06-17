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
     * Accepts: .m3u8, .mp4, .mkv, .webm, blob: URLs, and likely HLS manifests
     * served with non-standard extensions (e.g., master.txt, play.php).
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
               url.contains(".webm", ignoreCase = true) ||
               isLikelyHlsManifest(url)
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
     * Detects HLS manifest URLs served without the standard .m3u8 extension.
     * Many embed CDNs serve master playlists as .txt, .php, or with URL rewriting
     * (e.g., /hls3/.../master.txt) to bypass CORS restrictions on .m3u8 files.
     */
    fun isLikelyHlsManifest(url: String): Boolean {
        val lower = url.lowercase()
        // Must contain HLS path patterns
        if (!lower.contains("/hls") && !lower.contains("/hls3")) return false
        // Not already detected as a standard video format
        if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".webm")) return false
        // Reject segments and non-video assets
        if (isSegmentOrAsset(url)) return false
        return true
    }

    /**
     * Determines if a URL is an HLS Master M3U8 playlist.
     * Master playlists contain multiple sub-streams (qualities) rather than chunks.
     * Also detects non-standard manifests (e.g., master.txt) via [isLikelyHlsManifest].
     */
    fun isMasterM3u8(url: String): Boolean {
        val lower = url.lowercase()
        
        // Standard .m3u8 master detection
        if (lower.contains(".m3u8")) {
            if (lower.contains("master.m3u8") || 
                lower.contains("playlist.m3u8") || 
                lower.contains("manifest.m3u8")) {
                return true
            }
            // Exclude quality-specific sub-playlists
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
        
        // Non-standard extension: check if it's a likely HLS manifest with "master" in the name
        if (isLikelyHlsManifest(lower) && lower.contains("master")) return true
        
        return false
    }
}
