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
        // Use a standard User-Agent to reduce bot detection
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Accept-Language" to "ar,en-US;q=0.9",
            "Cookie" to "CONSENT=YES+; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg"
        )
        val output = app.get(url, headers = headers).text
        
        com.lagradost.api.Log.d("YoutubeProvider", "Fetched ${output.length} chars from $url")

        // Check for consent page
        if (output.contains("consent.youtube.com") || output.contains("Before you continue")) {
             com.lagradost.api.Log.e("YoutubeProvider", "Consent Page Detected! HTML Dump: ${output.take(1000)}")
             return newHomePageResponse("Trending - Consent Blocked", emptyList())
        }

        // Extract ytInitialData
        val jsonText = output.substringAfter("var ytInitialData = ", "")
            .substringBefore(";</script>", "")
        
        if (jsonText.isEmpty()) {
            com.lagradost.api.Log.e("YoutubeProvider", "ytInitialData NOT FOUND. HTML Dump: ${output.take(1000)}")
            return newHomePageResponse("Trending - Parse Fail", emptyList())
        }
        
        com.lagradost.api.Log.d("YoutubeProvider", "Found ytInitialData (Length: ${jsonText.length})")

        val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(jsonText)
        val videoRenderers = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        
        // Recursive search for robustness
        findAllVideoRenderers(json, videoRenderers)
        
        com.lagradost.api.Log.d("YoutubeProvider", "Found ${videoRenderers.size} video renderers")

        val items = videoRenderers.mapNotNull { v ->
            val videoId = v["videoId"]?.textValue() ?: return@mapNotNull null
            val title = v["title"]?.get("runs")?.get(0)?.get("text")?.textValue() ?: "No Title"
            val poster = v["thumbnail"]?.get("thumbnails")?.lastOrNull()?.get("url")?.textValue()
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"

            newMovieSearchResponse(title, watchUrl, TvType.Movie) {
               this.posterUrl = poster
            }
        }
        
        if (items.isEmpty()) {
             com.lagradost.api.Log.e("YoutubeProvider", "Items list is empty after mapping! Dump of first item: ${videoRenderers.firstOrNull()?.toPrettyString()}")
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
        CommonActivity.activity?.let { activity ->
           activity.runOnUiThread {
               YouTubePlayerDialog(activity, data).show()
           }
        }
        return true
    }
}
