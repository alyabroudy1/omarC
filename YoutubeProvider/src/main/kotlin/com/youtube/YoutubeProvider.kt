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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "https://www.youtube.com/feed/trending?gl=SY&hl=ar"
        val response = app.get(url).text
        // Extract ytInitialData
        val jsonText = response.substringAfter("var ytInitialData = ", "")
            .substringBefore(";</script>", "")
        
        if (jsonText.isEmpty()) return null

        val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(jsonText)
        val videoRenderers = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        
        // Recursive search for robustness
        findAllVideoRenderers(json, videoRenderers)

        val items = videoRenderers.mapNotNull { v ->
            val videoId = v["videoId"]?.textValue() ?: return@mapNotNull null
            val title = v["title"]?.get("runs")?.get(0)?.get("text")?.textValue() ?: "No Title"
            val poster = v["thumbnail"]?.get("thumbnails")?.lastOrNull()?.get("url")?.textValue()
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"

            newMovieSearchResponse(title, watchUrl, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse("Trending - Syria", items)
    }

    private fun findAllVideoRenderers(node: com.fasterxml.jackson.databind.JsonNode, list: MutableList<com.fasterxml.jackson.databind.JsonNode>) {
        if (node.has("videoRenderer")) {
            list.add(node.get("videoRenderer"))
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

    override suspend fun load(url: String): LoadResponse {
        // Basic load response to allow playback
        // In a real app we might fetch metadata here, but for now we rely on the passed URL
         val id = url.substringAfter("v=")
         val poster = "https://img.youtube.com/vi/$id/maxresdefault.jpg"
         
        return newMovieLoadResponse("YouTube Video", url, TvType.Movie, url) {
            this.posterUrl = poster
        }
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
               YouTubePlayerDialog(activity, data).show()
           }
        }
        return true
    }
}
