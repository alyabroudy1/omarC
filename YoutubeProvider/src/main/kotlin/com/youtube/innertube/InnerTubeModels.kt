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
