package com.youtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.api.Log
import com.youtube.innertube.InnerTubeClient
import com.youtube.innertube.InnerTubeConfig
import com.youtube.innertube.InnerTubeParser
import com.youtube.innertube.YouTubeSearchResult
import com.youtube.innertube.YouTubeStreamFormat

/**
 * YouTube provider powered by InnerTube API.
 *
 * Architecture:
 * - InnerTubeClient: HTTP POST calls to YouTube's internal API
 * - InnerTubeParser: JSON → domain model conversion
 * - YoutubeProvider: orchestrates client + parser, maps to CloudStream types
 */
class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YoutubeProvider"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    // Plugin context resources (for YouTubePlayerDialog)
    var resources: android.content.res.Resources? = null
    var pluginPackageName: String = "com.youtube"

    companion object {
        private const val TAG = "YoutubeProvider"
    }

    // ==================== MAIN PAGE ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d(TAG, "getMainPage: page=$page")

        // Use search for a default query (trending content for Arabic audience)
        val json = InnerTubeClient.search("سوريا", params = InnerTubeConfig.SearchFilters.VIDEOS_ONLY)
        if (json == null) {
            Log.e(TAG, "getMainPage: InnerTube search returned null")
            return null
        }

        val results = InnerTubeParser.parseSearchResults(json)
        val items = results.mapNotNull { it.toSearchResponse() }

        Log.d(TAG, "getMainPage: ${items.size} items")
        return newHomePageResponse("Syria - Trending Videos", items)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse>? {
        Log.d(TAG, "search: query='$query'")

        val results = mutableListOf<SearchResponse>()

        // Search videos
        val videoJson = InnerTubeClient.search(query, params = InnerTubeConfig.SearchFilters.VIDEOS_ONLY)
        if (videoJson != null) {
            val videoResults = InnerTubeParser.parseSearchResults(videoJson)
            results.addAll(videoResults.mapNotNull { it.toSearchResponse() })
        }

        // Search playlists
        val playlistJson = InnerTubeClient.search(query, params = InnerTubeConfig.SearchFilters.PLAYLISTS_ONLY)
        if (playlistJson != null) {
            val playlistResults = InnerTubeParser.parseSearchResults(playlistJson)
            results.addAll(playlistResults.mapNotNull { it.toSearchResponse() })
        }

        Log.d(TAG, "search: ${results.size} total results")
        return results
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        return if (url.contains("playlist?list=")) {
            loadPlaylist(url)
        } else {
            loadVideo(url)
        }
    }

    private suspend fun loadVideo(url: String): LoadResponse {
        val videoId = url.substringAfter("v=").substringBefore("&")
        val poster = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

        var title = "YouTube Video"
        var description: String? = null

        try {
            val json = InnerTubeClient.getVideoDetails(videoId)
            if (json != null) {
                val video = InnerTubeParser.parseVideoDetails(json)
                if (video != null) {
                    title = video.title
                }
                description = InnerTubeParser.parseVideoDescription(json)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch video details: ${e.message}")
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun loadPlaylist(url: String): LoadResponse {
        val playlistId = url.substringAfter("list=").substringBefore("&")
        var title = "YouTube Playlist"
        var poster: String? = null
        val episodes = mutableListOf<Episode>()

        try {
            val json = InnerTubeClient.getPlaylistVideos(playlistId)
            if (json != null) {
                // Parse header
                val header = InnerTubeParser.parsePlaylistHeader(json)
                if (header != null) {
                    title = header.title
                }

                // Parse videos
                val videos = InnerTubeParser.parsePlaylistVideos(json)
                videos.forEachIndexed { index, video ->
                    if (index == 0) {
                        poster = video.thumbnail ?: video.maxResThumbnail
                    }

                    val displayTitle = if (video.duration != null) {
                        "${video.title} [${video.duration}]"
                    } else {
                        video.title
                    }

                    episodes.add(
                        newEpisode(video.watchUrl) {
                            this.name = displayTitle
                            this.episode = index + 1
                            this.posterUrl = video.thumbnail
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load playlist: ${e.message}")
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster ?: episodes.firstOrNull()?.let {
                val id = it.data.substringAfter("v=").substringBefore("&")
                "https://img.youtube.com/vi/$id/maxresdefault.jpg"
            }
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: data=$data")

        val videoId = data.substringAfter("v=").substringBefore("&")
        Log.d(TAG, "loadLinks: videoId=$videoId")

        // Call InnerTube /player API with ANDROID client
        val playerJson = InnerTubeClient.getPlayer(videoId)
        if (playerJson == null) {
            Log.e(TAG, "loadLinks: Player API returned null, falling back to WebView")
            launchWebViewPlayer(data)
            return true
        }

        // Check playability
        val status = playerJson.path("playabilityStatus").path("status").textValue()
        if (status != "OK") {
            val reason = playerJson.path("playabilityStatus").path("reason").textValue() ?: "Unknown"
            Log.w(TAG, "loadLinks: Not playable: $status — $reason, falling back to WebView")
            launchWebViewPlayer(data)
            return true
        }

        // Parse streaming data
        val result = InnerTubeParser.parseStreamingData(playerJson)

        // 1. HLS manifest for live streams — single M3U8 with all qualities
        if (result.hlsManifestUrl != null) {
            callback(
                newExtractorLink(
                    source = "YouTube",
                    name = "YouTube Live",
                    url = result.hlsManifestUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://www.youtube.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
                    )
                }
            )
            Log.d(TAG, "loadLinks: Added HLS manifest for live stream")
            return true
        }

        // 2. DASH manifest from adaptive formats — high quality with ABR
        if (result.adaptiveFormats.isNotEmpty()) {
            val dashUri = com.youtube.innertube.DashManifestGenerator.generateUrl(result.adaptiveFormats)
            if (dashUri != null) {
                callback(
                    newExtractorLink(
                        source = "YouTube",
                        name = "YouTube DASH",
                        url = dashUri,
                        type = ExtractorLinkType.DASH
                    ) {
                        this.referer = "https://www.youtube.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
                        )
                    }
                )
                Log.d(TAG, "loadLinks: Added DASH manifest with ${result.adaptiveFormats.size} adaptive streams")
            }
        }

        // 3. Muxed format streams as fallback (itag 18=360p, 22=720p)
        for (stream in result.muxedFormats) {
            val qualityValue = mapQuality(stream.qualityLabel)

            callback(
                newExtractorLink(
                    source = "YouTube",
                    name = "YouTube ${stream.qualityLabel ?: "Auto"}",
                    url = stream.url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://www.youtube.com/"
                    this.quality = qualityValue
                    this.headers = mapOf(
                        "User-Agent" to "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
                    )
                }
            )
            Log.d(TAG, "loadLinks: Added muxed itag=${stream.itag} quality=${stream.qualityLabel}")
        }

        if (result.adaptiveFormats.isEmpty() && result.muxedFormats.isEmpty()) {
            Log.w(TAG, "loadLinks: No formats found, falling back to WebView")
            launchWebViewPlayer(data)
        }

        Log.d(TAG, "loadLinks: Done — ${result.adaptiveFormats.size} adaptive, ${result.muxedFormats.size} muxed")
        return true
    }

    /**
     * Map YouTube quality label to CloudStream Qualities value.
     */
    private fun mapQuality(label: String?): Int = when {
        label == null -> Qualities.Unknown.value
        label.contains("2160") -> Qualities.P2160.value
        label.contains("1440") -> Qualities.P1440.value
        label.contains("1080") -> Qualities.P1080.value
        label.contains("720") -> Qualities.P720.value
        label.contains("480") -> Qualities.P480.value
        label.contains("360") -> Qualities.P360.value
        label.contains("240") -> Qualities.P240.value
        label.contains("144") -> Qualities.P144.value
        else -> Qualities.Unknown.value
    }

    /**
     * Fallback: launch WebView player for videos that can't provide direct URLs
     * (DRM, age-restricted, or when Player API fails).
     */
    private fun launchWebViewPlayer(url: String) {
        CommonActivity.activity?.let { activity ->
            activity.runOnUiThread {
                val dialog = com.cloudstream.shared.ui.WebViewPlayerDialog(activity, url)
                dialog.pluginResources = resources
                dialog.pluginPackageName = pluginPackageName
                dialog.show()
            }
        }
    }

    // ==================== MAPPING ====================

    /**
     * Convert InnerTube search result to CloudStream SearchResponse.
     */
    private fun YouTubeSearchResult.toSearchResponse(): SearchResponse? {
        return when (this) {
            is YouTubeSearchResult.Video -> {
                val v = this.video
                val displayTitle = buildString {
                    append(v.title)
                    if (v.duration != null) append(" [${v.duration}]")
                }
                newMovieSearchResponse(displayTitle, v.watchUrl, TvType.Movie) {
                    this.posterUrl = v.thumbnail ?: v.maxResThumbnail
                }
            }
            is YouTubeSearchResult.Playlist -> {
                val p = this.playlist
                val displayTitle = if (p.videoCount != null) {
                    "${p.title} (${p.videoCount})"
                } else {
                    p.title
                }
                newTvSeriesSearchResponse(displayTitle, p.url, TvType.TvSeries) {
                    this.posterUrl = p.thumbnail
                }
            }
        }
    }
}
