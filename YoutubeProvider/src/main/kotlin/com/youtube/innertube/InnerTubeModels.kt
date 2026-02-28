package com.youtube.innertube

/**
 * Domain models for YouTube content parsed from InnerTube API responses.
 * Clean data classes — no JSON/parsing logic here.
 */

data class YouTubeVideo(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val duration: String? = null,
    val publishedTime: String? = null,
    val channelName: String? = null,
    val viewCount: String? = null,
    val isShort: Boolean = false
) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$id"
    val maxResThumbnail: String get() = "https://img.youtube.com/vi/$id/maxresdefault.jpg"
}

data class YouTubePlaylist(
    val id: String,
    val title: String,
    val thumbnail: String? = null,
    val videoCount: String? = null,
    val channelName: String? = null
) {
    val url: String get() = "https://www.youtube.com/playlist?list=$id"
}

/**
 * Union type for search results — either a video or playlist.
 */
sealed class YouTubeSearchResult {
    data class Video(val video: YouTubeVideo) : YouTubeSearchResult()
    data class Playlist(val playlist: YouTubePlaylist) : YouTubeSearchResult()
}

/**
 * Result from a browse request (e.g., trending page).
 */
data class YouTubeBrowseResult(
    val title: String,
    val items: List<YouTubeSearchResult>
)

/**
 * A playable stream format from the /player API.
 *
 * Two categories:
 *   - Muxed (itag 18, 22): video+audio combined in a single MP4.
 *   - Adaptive: video-only OR audio-only at a specific quality.
 *     Adaptive streams carry [initRange]/[indexRange] for DASH SegmentBase
 *     and a parsed [codecs] string extracted from the compound mimeType.
 */
data class YouTubeStreamFormat(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val qualityLabel: String?,     // e.g., "720p", "360p"
    val quality: String?,          // e.g., "hd720", "medium"
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val contentLength: Long? = null,

    // Adaptive-only fields (null for muxed formats)
    val codecs: String? = null,           // e.g., "avc1.64001F", "mp4a.40.2"
    val initRange: String? = null,        // e.g., "0-740"
    val indexRange: String? = null,       // e.g., "741-1192"
    val approxDurationMs: Long? = null    // e.g., 215000
) {
    /** True when this is a video track (not audio). */
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** True when this is an audio track. */
    val isAudio: Boolean get() = mimeType.startsWith("audio/")

    /** The base mime type without the codecs parameter, e.g. "video/mp4". */
    val baseMimeType: String get() = mimeType.substringBefore(";").trim()
}

/**
 * Result from parseStreamingData containing all available streams.
 *
 * - [hlsManifestUrl]: for live streams — single M3U8 with all qualities.
 * - [muxedFormats]: video+audio combined (itag 18/22), always playable.
 * - [adaptiveFormats]: video-only + audio-only streams for DASH manifest generation.
 */
data class StreamingResult(
    val hlsManifestUrl: String? = null,
    val muxedFormats: List<YouTubeStreamFormat> = emptyList(),
    val adaptiveFormats: List<YouTubeStreamFormat> = emptyList(),
    val isLive: Boolean = false
)
