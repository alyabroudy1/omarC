package com.youtube

import android.content.Intent
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.api.Log
import com.youtube.innertube.InnerTubeClient
import com.youtube.innertube.InnerTubeConfig
import com.youtube.innertube.InnerTubeParser
import com.youtube.innertube.YouTubeSearchResult

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
        CommonActivity.activity?.let { activity ->
            activity.runOnUiThread {
                val dialog = YouTubePlayerDialog(activity, data)
                dialog.pluginResources = resources
                dialog.pluginPackageName = pluginPackageName
                dialog.show()
            }
        }
        return true
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
