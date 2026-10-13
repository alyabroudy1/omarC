package com.wecima

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.parsing.ParserInterface
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URLEncoder

class Wecima : BaseProvider() {
    override val baseDomain get() = "wecima.ac"
    override var name = "وي سيما"
    override val providerName get() = "Wecima"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/wecima.json"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/movies/" to "الافلام",
        "/seriestv" to "المسلسلات",
        "/category/anime-movies" to "افلام انمي",
        "/category/anime-series" to "مسلسلات انمي"
    )

    override fun getParser(): NewBaseParser = WecimaParser()

    private suspend fun searchPost(query: String): List<SearchResponse> {
        val jsonText = httpService.postText(
            "$mainUrl/search", mapOf("q" to query),
            referer = mainUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            rewriteDomain = true
        )
        if (jsonText.isNullOrBlank()) return emptyList()

        val json = JSONObject(jsonText)
        if (!json.optBoolean("status", false)) return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        val items = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val title = item.optString("title", "") ?: continue
            val slug = item.optString("slug", "") ?: continue
            if (title.isBlank() || slug.isBlank()) continue
            val posterUrl = item.optString("image").ifBlank { null }
            val isTv = item.optInt("istv", 0) == 1
            val url = if (isTv) "$mainUrl/series/$slug" else "$mainUrl/watch/$slug"

            items.add(
                newMovieSearchResponse(title, url, if (isTv) TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                }
            )
        }
        return items
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        try {
            httpService.ensureInitialized()
            return searchPost(query)
        } catch (e: Exception) {
            Log.e("[Wecima] [searchNormal]", "Error: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        return searchPost(query)
    }

    override suspend fun fetchExtraEpisodes(
        doc: org.jsoup.nodes.Document,
        url: String,
        data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        val seasonsEl = doc.select("div.List--Seasons--Episodes a.SeasonsEpisodes")
        if (seasonsEl.isEmpty()) return data.episodes ?: emptyList()
        
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        seasonsEl.forEach { seasonEl ->
            val seasonNum = Regex("الموسم (\\d+)").find(seasonEl.text())?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val dataId = seasonEl.attr("data-id")
            val dataSeason = seasonEl.attr("data-season")
            try {
                val seasonHtml = httpService.postText("$mainUrl/ajax/Episode", mapOf("post_id" to dataId, "season" to dataSeason), url)
                if (seasonHtml != null) {
                    val seasonDocParsed = Jsoup.parse(seasonHtml)
                    seasonDocParsed.select("a.hoverable.activable").forEach { epEl ->
                        val epName = epEl.selectFirst("episodetitle")?.text() ?: "Episode"
                        val epNum = Regex("الحلقة (\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        episodes.add(
                            ParserInterface.ParsedEpisode(
                                url = epEl.attr("href"),
                                name = epName,
                                season = seasonNum,
                                episode = epNum
                            )
                        )
                    }
                }
            } catch (e: Exception) {}
        }
        return episodes
    }

    private fun decodeWecimaUrl(encodedStr: String): String? {
        try {
            if (encodedStr.isBlank()) return null
            val cleanedStr = encodedStr.replace("+", "").trim()
            val finalB64Str = if (!cleanedStr.startsWith("aHR0c")) "aHR0c$cleanedStr" else cleanedStr
            return String(android.util.Base64.decode(finalB64Str, 0), java.nio.charset.StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "[Wecima] [loadLinks]"
        Log.i(methodTag, "START data='$data'")
        val document = httpService.getDocument(data, rewriteDomain = true)
        if (document == null) {
            Log.e(methodTag, "Failed to fetch detail document")
            return false
        }

        var delivered = 0
        fun countingCallback(link: ExtractorLink) {
            delivered++
            callback(link)
        }

        val serverBtns = document.select("ul.WatchServersList li btn")
        Log.d(methodTag, "Found ${serverBtns.size} server buttons")
        serverBtns.forEach { serverBtn ->
            val decodedUrl = decodeWecimaUrl(serverBtn.attr("data-url"))
            if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http")) {
                Log.d(methodTag, "Server: ${decodedUrl.take(80)}")
                try {
                    loadExtractor(decodedUrl, subtitleCallback, ::countingCallback)
                } catch (e: Exception) {
                    Log.d(methodTag, "Extractor failed for $decodedUrl: ${e.message}")
                }
            } else {
                Log.w(methodTag, "Undecodable server btn: '${serverBtn.attr("data-url").take(60)}'")
            }
        }

        val dlBtns = document.select(".openLinkDown")
        Log.d(methodTag, "Found ${dlBtns.size} download buttons")
        dlBtns.forEach { downloadBtn ->
            val decodedUrl = decodeWecimaUrl(downloadBtn.attr("data-href"))
            if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http")) {
                Log.d(methodTag, "Download: ${decodedUrl.take(80)}")
                try {
                    loadExtractor(decodedUrl, subtitleCallback, ::countingCallback)
                } catch (e: Exception) {
                    Log.d(methodTag, "Extractor failed for $decodedUrl: ${e.message}")
                }
            }
        }

        Log.i(methodTag, "END delivered=$delivered")
        return delivered > 0
    }
}
