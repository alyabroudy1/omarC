package com.youtube

import android.content.Intent
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YoutubeProvider"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    private val cookieStore = InMemoryCookieStore()

    // Plugin Context Resources
    var resources: android.content.res.Resources? = null
    var pluginPackageName: String = "com.youtube" // Default fallback

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val trendingUrl = "https://m.youtube.com/results?search_query=%D8%B3%D9%88%D8%B1%D9%8A%D8%A7&sp=EgIIAw%253D%253D#filters"
        var items = fetchAndParse(trendingUrl)
        
        if (items.isEmpty()) {
            com.lagradost.api.Log.w("YoutubeProvider", "Trending feed empty. Falling back to Search Query.")
            val searchUrl = "https://www.youtube.com/results?search_query=%D8%AA%D8%B1%D9%8A%D9%86%D8%AF+%D8%B3%D9%88%D8%B1%D9%8A%D8%A7&sp=CAM%3D" // "Trend Syria" sort by date/relevance
            items = fetchAndParse(searchUrl)
        }

        return newHomePageResponse("Trending - Syria", items)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Encode the query for URL
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        // Use mobile YouTube for better parsing, sp=CAM%3D sorts by upload date
        val searchUrl = "https://m.youtube.com/results?search_query=$encodedQuery&sp=CAM%3D"
        return fetchAndParse(searchUrl)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data passed from load() is the URL, which is what we need for the player
        CommonActivity.activity?.let { activity ->
           activity.runOnUiThread {
               val dialog = YouTubePlayerDialog(activity, data)
               dialog.pluginResources = resources // Pass plugin resources
               dialog.pluginPackageName = pluginPackageName
               dialog.show()
           }
        }
        return true
    }

    private suspend fun fetchAndParse(url: String): List<SearchResponse> {
        // Lazy init WebViewEngine
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
        
        // Check for consent page
        if (output.contains("consent.youtube.com") || output.contains("Before you continue")) {
             return emptyList()
        }

        // Extract ytInitialData
        var jsonText = ""
        if (output.trim().startsWith("{")) {
            jsonText = output
        } else {
             jsonText = output.substringAfter("var ytInitialData = ", "")
                .substringBefore(";</script>", "")
        }
        
        if (jsonText.isEmpty()) return emptyList()

        val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(jsonText)
        val videoRenderers = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        findAllVideoRenderers(json, videoRenderers)
        
        return videoRenderers.mapNotNull { v ->
            var videoId = v["videoId"]?.textValue()
            var title = v["title"]?.get("runs")?.get(0)?.get("text")?.textValue()
            var poster = v["thumbnail"]?.get("thumbnails")?.lastOrNull()?.get("url")?.textValue()

            // Handle lockupViewModel
            if (videoId == null && v.has("contentId")) {
                videoId = v["contentId"]?.textValue()
                title = v["metadata"]?.get("lockupMetadataViewModel")?.get("title")?.get("content")?.textValue()
                // Try to find image
                val imageNode = v["contentImage"]?.get("collectionThumbnailViewModel")?.get("primaryThumbnail")?.get("thumbnailViewModel")?.get("image")?.get("sources") 
                    ?: v["contentImage"]?.get("thumbnailViewModel")?.get("image")?.get("sources")
                poster = imageNode?.lastOrNull()?.get("url")?.textValue()
            }

            if (videoId == null) return@mapNotNull null
            title = title ?: "No Title"
            
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"

            newMovieSearchResponse(title, watchUrl, TvType.Movie) {
               this.posterUrl = poster
            }
        }
    }

    private fun findAllVideoRenderers(node: com.fasterxml.jackson.databind.JsonNode, list: MutableList<com.fasterxml.jackson.databind.JsonNode>) {
        if (node.has("videoRenderer")) {
            list.add(node.get("videoRenderer"))
        }
        if (node.has("gridVideoRenderer")) {
            list.add(node.get("gridVideoRenderer"))
        }
        if (node.has("lockupViewModel")) {
            list.add(node.get("lockupViewModel"))
        }
        
        // RichItemRenderer block removed to prevent duplicates (recursion finds the children)
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

    override suspend fun load(url: String): LoadResponse {
        // Basic load response to allow playback
        val id = url.substringAfter("v=")
        val poster = "https://img.youtube.com/vi/$id/maxresdefault.jpg"
         
        return newMovieLoadResponse("YouTube Video", url, TvType.Movie, url) {
            this.posterUrl = poster
        }
    }


}
