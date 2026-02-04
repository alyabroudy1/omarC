package com.youtube

import android.content.Intent
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YoutubeProvider"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries) // Support both videos and playlists
    override var lang = "en"
    override val hasMainPage = true

    private val cookieStore = InMemoryCookieStore()

    // Plugin Context Resources
    var resources: android.content.res.Resources? = null
    var pluginPackageName: String = "com.youtube" // Default fallback

    companion object {
        // YouTube search params: 
        // sp=CAISAhAB = Filter: Video + Sort: Upload Date (excludes shorts, playlists, channels)
        // sp=CAISBAgBEAE = Video + Upload Date + This Hour
        // sp=CAMSAhAB = Sort: Upload Date + Filter: Video
        const val SEARCH_FILTER_VIDEO_BY_DATE = "CAISAhAB" // Video type, sorted by upload date
        const val SEARCH_FILTER_VIDEO_ONLY = "EgIQAQ%3D%3D" // Video type only
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Use search with filter: Video type only (excludes Shorts, Lives, Playlists)
        // Default sorting (Relevance) is better than strict upload date
        val searchQuery = java.net.URLEncoder.encode("سوريا", "UTF-8")
        val mainUrl = "https://m.youtube.com/results?search_query=$searchQuery&sp=$SEARCH_FILTER_VIDEO_ONLY"
        
        val items = fetchAndParseVideos(mainUrl)
        
        return newHomePageResponse("Syria - Trending Videos", items)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        // sp=EgIQAQ%3D%3D = Filter: Video only (relevance sort)
        val searchUrl = "https://m.youtube.com/results?search_query=$encodedQuery&sp=$SEARCH_FILTER_VIDEO_ONLY"
        
        val results = mutableListOf<SearchResponse>()
        
        // Fetch videos
        results.addAll(fetchAndParseVideos(searchUrl))
        
        // Also try to fetch playlists separately
        val playlistUrl = "https://m.youtube.com/results?search_query=$encodedQuery&sp=EgIQAw%3D%3D" // Playlist filter
        results.addAll(fetchAndParsePlaylists(playlistUrl))
        
        return results
    }

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

    private suspend fun fetchAndParseVideos(url: String): List<SearchResponse> {
        val json = fetchYouTubeJson(url) ?: return emptyList()
        
        val videoRenderers = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        findAllVideoRenderers(json, videoRenderers)
        
        return videoRenderers.mapNotNull { v ->
            parseVideoRenderer(v)
        }
    }

    private suspend fun fetchAndParsePlaylists(url: String): List<SearchResponse> {
        val json = fetchYouTubeJson(url) ?: return emptyList()
        
        val playlistRenderers = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        findAllPlaylistRenderers(json, playlistRenderers)
        
        return playlistRenderers.mapNotNull { p ->
            parsePlaylistRenderer(p)
        }
    }

    private suspend fun fetchYouTubeJson(url: String): com.fasterxml.jackson.databind.JsonNode? {
        val webViewEngine = WebViewEngine(
            cookieStore,
            { com.lagradost.cloudstream3.CommonActivity.activity }
        )

        val result = webViewEngine.runSession(
            url = url,
            mode = WebViewEngine.Mode.HEADLESS,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            exitCondition = ExitCondition.PageLoaded,
            timeout = 90_000L
        )
        
        val output = when (result) {
            is WebViewResult.Success -> result.html
            is WebViewResult.Timeout -> result.partialHtml ?: ""
            else -> ""
        }
        
        if (output.contains("consent.youtube.com") || output.contains("Before you continue")) {
            return null
        }

        var jsonText = ""
        if (output.trim().startsWith("{")) {
            jsonText = output
        } else {
            jsonText = output.substringAfter("var ytInitialData = ", "")
                .substringBefore(";</script>", "")
        }
        
        if (jsonText.isEmpty()) return null

        return try {
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(jsonText)
        } catch (e: Exception) {
            com.lagradost.api.Log.e("YoutubeProvider", "Failed to parse JSON: ${e.message}")
            null
        }
    }

    private fun parseVideoRenderer(v: com.fasterxml.jackson.databind.JsonNode): SearchResponse? {
        var videoId = v["videoId"]?.textValue()
        var title = v["title"]?.get("runs")?.get(0)?.get("text")?.textValue()
            ?: v["title"]?.get("simpleText")?.textValue()
        var poster = v["thumbnail"]?.get("thumbnails")?.lastOrNull()?.get("url")?.textValue()
        
        // Extract duration (e.g., "12:34")
        var duration = v["lengthText"]?.get("simpleText")?.textValue()
            ?: v["lengthText"]?.get("runs")?.get(0)?.get("text")?.textValue()
        
        // Extract published time (e.g., "2 hours ago")
        var publishedTime = v["publishedTimeText"]?.get("simpleText")?.textValue()
            ?: v["publishedTimeText"]?.get("runs")?.get(0)?.get("text")?.textValue()
        
        // Skip shorts (usually under 60 seconds, or have #Shorts in title)
        if (duration != null) {
            val parts = duration.split(":")
            if (parts.size == 1) {
                // Just seconds, likely a Short
                val seconds = parts[0].toIntOrNull() ?: 0
                if (seconds < 61) return null
            } else if (parts.size == 2) {
                // MM:SS - check if under 1 minute
                val minutes = parts[0].toIntOrNull() ?: 0
                if (minutes == 0 && (parts[1].toIntOrNull() ?: 0) < 61) {
                    // Could be a short, but allow if it's a proper video
                }
            }
        }
        
        // Handle lockupViewModel (mobile format)
        if (videoId == null && v.has("contentId")) {
            videoId = v["contentId"]?.textValue()
            title = v["metadata"]?.get("lockupMetadataViewModel")?.get("title")?.get("content")?.textValue()
            val imageNode = v["contentImage"]?.get("collectionThumbnailViewModel")?.get("primaryThumbnail")?.get("thumbnailViewModel")?.get("image")?.get("sources") 
                ?: v["contentImage"]?.get("thumbnailViewModel")?.get("image")?.get("sources")
            poster = imageNode?.lastOrNull()?.get("url")?.textValue()
            
            // Try to get duration from overlay
            duration = v["contentImage"]?.get("thumbnailViewModel")?.get("overlays")?.firstOrNull()
                ?.get("thumbnailOverlayTimeStatusViewModel")?.get("text")?.get("content")?.textValue()
        }

        if (videoId == null) return null
        
        // Skip if title contains #Shorts indicator
        if (title?.contains("#short", ignoreCase = true) == true) return null
        
        title = title ?: "No Title"
        
        // Build display title with metadata
        val displayTitle = buildString {
            append(title)
            if (duration != null) {
                append(" [$duration]")
            }
        }
        
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"

        return newMovieSearchResponse(displayTitle, watchUrl, TvType.Movie) {
            this.posterUrl = poster
            // Add quality tag based on thumbnail quality (rough estimate)
            this.quality = null // Will be determined at playback
        }
    }

    private fun parsePlaylistRenderer(p: com.fasterxml.jackson.databind.JsonNode): SearchResponse? {
        val playlistId = p["playlistId"]?.textValue() ?: return null
        val title = p["title"]?.get("simpleText")?.textValue()
            ?: p["title"]?.get("runs")?.get(0)?.get("text")?.textValue()
            ?: "Playlist"
        val poster = p["thumbnails"]?.get(0)?.get("thumbnails")?.lastOrNull()?.get("url")?.textValue()
            ?: p["thumbnail"]?.get("thumbnails")?.lastOrNull()?.get("url")?.textValue()
        val videoCount = p["videoCount"]?.textValue()
            ?: p["videoCountText"]?.get("runs")?.get(0)?.get("text")?.textValue()
        
        val displayTitle = if (videoCount != null) {
            "$title ($videoCount videos)"
        } else {
            title
        }
        
        val playlistUrl = "https://www.youtube.com/playlist?list=$playlistId"

        // Return as TvSeries so episodes can be listed
        return newTvSeriesSearchResponse(displayTitle, playlistUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun findAllVideoRenderers(node: com.fasterxml.jackson.databind.JsonNode, list: MutableList<com.fasterxml.jackson.databind.JsonNode>) {
        // Check for video-like renderers
        if (node.has("videoRenderer")) {
            val renderer = node.get("videoRenderer")
            // Skip shorts (they have a specific badge or short duration)
            if (!isShort(renderer)) {
                list.add(renderer)
            }
        }
        if (node.has("gridVideoRenderer")) {
            val renderer = node.get("gridVideoRenderer")
            if (!isShort(renderer)) {
                list.add(renderer)
            }
        }
        if (node.has("lockupViewModel")) {
            val renderer = node.get("lockupViewModel")
            // For lockupViewModel, check if it's a video (not a channel, not a short)
            val contentType = renderer["contentType"]?.textValue()
            if (contentType == "VIDEO" || contentType == null) {
                list.add(renderer)
            }
        }
        
        if (node.isArray) {
            for (item in node) {
                findAllVideoRenderers(item, list)
            }
        } else if (node.isObject) {
            for (field in node.fields()) {
                findAllVideoRenderers(field.value, list)
            }
        }
    }

    private fun findAllPlaylistRenderers(node: com.fasterxml.jackson.databind.JsonNode, list: MutableList<com.fasterxml.jackson.databind.JsonNode>) {
        if (node.has("playlistRenderer")) {
            list.add(node.get("playlistRenderer"))
        }
        
        if (node.isArray) {
            for (item in node) {
                findAllPlaylistRenderers(item, list)
            }
        } else if (node.isObject) {
            for (field in node.fields()) {
                findAllPlaylistRenderers(field.value, list)
            }
        }
    }

    private fun isShort(renderer: com.fasterxml.jackson.databind.JsonNode): Boolean {
        // Check for Shorts badge
        val badges = renderer["badges"]
        if (badges != null && badges.isArray) {
            for (badge in badges) {
                val style = badge["metadataBadgeRenderer"]?.get("style")?.textValue()
                if (style == "BADGE_STYLE_TYPE_SHORTS") return true
            }
        }
        
        // Check for shorts overlay
        val overlays = renderer["thumbnailOverlays"]
        if (overlays != null && overlays.isArray) {
            for (overlay in overlays) {
                if (overlay.has("thumbnailOverlayShortsStatusRenderer")) return true
            }
        }
        
        // Check navigation endpoint for shorts
        val navEndpoint = renderer["navigationEndpoint"]?.get("reelWatchEndpoint")
        if (navEndpoint != null) return true
        
        return false
    }

    override suspend fun load(url: String): LoadResponse {
        // Determine if this is a playlist or a video
        return if (url.contains("playlist?list=")) {
            loadPlaylist(url)
        } else {
            loadVideo(url)
        }
    }

    private suspend fun loadVideo(url: String): LoadResponse {
        val id = url.substringAfter("v=").substringBefore("&")
        val poster = "https://img.youtube.com/vi/$id/maxresdefault.jpg"
        
        // Try to fetch actual video info for title
        var title = "YouTube Video"
        var description: String? = null
        
        try {
            val json = fetchYouTubeJson(url)
            if (json != null) {
                // Try to extract title from playerMicroformatRenderer
                val microformat = findNode(json, "playerMicroformatRenderer")
                if (microformat != null) {
                    title = microformat["title"]?.get("simpleText")?.textValue() ?: title
                    description = microformat["description"]?.get("simpleText")?.textValue()
                }
            }
        } catch (e: Exception) {
            com.lagradost.api.Log.w("YoutubeProvider", "Could not fetch video details: ${e.message}")
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
            val json = fetchYouTubeJson(url)
            if (json != null) {
                // Extract playlist title
                val playlistHeader = findNode(json, "playlistHeaderRenderer")
                if (playlistHeader != null) {
                    title = playlistHeader["title"]?.get("simpleText")?.textValue() 
                        ?: playlistHeader["title"]?.get("runs")?.get(0)?.get("text")?.textValue()
                        ?: title
                }
                
                // Extract videos from playlist
                val videoRenderers = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
                findPlaylistVideoRenderers(json, videoRenderers)
                
                videoRenderers.forEachIndexed { index, v ->
                    val videoId = v["videoId"]?.textValue() ?: return@forEachIndexed
                    val videoTitle = v["title"]?.get("runs")?.get(0)?.get("text")?.textValue()
                        ?: v["title"]?.get("simpleText")?.textValue()
                        ?: "Episode ${index + 1}"
                    val videoPoster = v["thumbnail"]?.get("thumbnails")?.lastOrNull()?.get("url")?.textValue()
                    val videoDuration = v["lengthText"]?.get("simpleText")?.textValue()
                    
                    if (index == 0 && poster == null) {
                        poster = videoPoster
                    }
                    
                    val watchUrl = "https://www.youtube.com/watch?v=$videoId"
                    
                    episodes.add(
                        newEpisode(watchUrl) {
                            this.name = if (videoDuration != null) "$videoTitle [$videoDuration]" else videoTitle
                            this.episode = index + 1
                            this.posterUrl = videoPoster
                        }
                    )
                }
            }
        } catch (e: Exception) {
            com.lagradost.api.Log.e("YoutubeProvider", "Failed to load playlist: ${e.message}")
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster ?: "https://img.youtube.com/vi/${episodes.firstOrNull()?.data?.substringAfter("v=")}/maxresdefault.jpg"
        }
    }

    private fun findPlaylistVideoRenderers(node: com.fasterxml.jackson.databind.JsonNode, list: MutableList<com.fasterxml.jackson.databind.JsonNode>) {
        if (node.has("playlistVideoRenderer")) {
            list.add(node.get("playlistVideoRenderer"))
        }
        if (node.has("playlistPanelVideoRenderer")) {
            list.add(node.get("playlistPanelVideoRenderer"))
        }
        
        if (node.isArray) {
            for (item in node) {
                findPlaylistVideoRenderers(item, list)
            }
        } else if (node.isObject) {
            for (field in node.fields()) {
                findPlaylistVideoRenderers(field.value, list)
            }
        }
    }

    private fun findNode(node: com.fasterxml.jackson.databind.JsonNode, name: String): com.fasterxml.jackson.databind.JsonNode? {
        if (node.has(name)) {
            return node.get(name)
        }
        
        if (node.isArray) {
            for (item in node) {
                val found = findNode(item, name)
                if (found != null) return found
            }
        } else if (node.isObject) {
            for (field in node.fields()) {
                val found = findNode(field.value, name)
                if (found != null) return found
            }
        }
        return null
    }
}
