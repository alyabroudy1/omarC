package com.wecima

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.parsing.ParserInterface
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class Wecima : BaseProvider() {
    override val baseDomain get() = "wecima.ac"
    override val providerName get() = "Wecima"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/wecima.json"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/movies" to "الافلام",
        "/seriestv" to "المسلسلات",
        "/anime" to "افلام انمي",
        "/category/dubbed-movies" to "مسلسلات انمي"
    )

    override fun getParser(): NewBaseParser = WecimaParser()

    data class WecimaSearchResponseJson(
        val status: Boolean? = null,
        val results: List<WecimaSearchItem>? = null
    )

    data class WecimaSearchItem(
        val title: String? = null,
        val slug: String? = null,
        val image: String? = null,
        val year: String? = null,
        val istv: Int? = null
    )

    private fun parseSearchResponse(responseText: String): List<SearchResponse> {
        try {
            val parsed = AppUtils.parseJson<WecimaSearchResponseJson>(responseText)
            return parsed.results?.mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val slug = item.slug ?: return@mapNotNull null
                val type = if (item.istv == 0) TvType.Movie else TvType.TvSeries
                val prefix = if (item.istv == 0) "/watch/" else "/series/"
                
                val encodedSlug = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
                val itemUrl = if (slug.startsWith("http")) slug else "$mainUrl$prefix$encodedSlug"
                
                newMovieSearchResponse(title, itemUrl, type) {
                    this.posterUrl = item.image
                    this.year = item.year?.toIntOrNull()
                    this.posterHeaders = httpService.getImageHeaders()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        val url = "$mainUrl/search"
        val responseText = httpService.postText(url, mapOf("q" to query), mainUrl) ?: return emptyList()
        return parseSearchResponse(responseText)
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        val url = "$mainUrl/search"
        val result = httpService.postDebug(url, mapOf("q" to query), mainUrl)
        
        if (result.isCloudflareBlocked || result.responseCode == 403 || result.html?.contains("403 Forbidden") == true) {
            throw com.cloudstream.shared.service.CloudflareBlockedSearchException(providerName, httpService.currentDomain)
        }
        
        val responseText = result.html ?: return emptyList()
        return parseSearchResponse(responseText)
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
            val seasonNum = Regex("الموسم (\\\\d+)").find(seasonEl.text())?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val dataId = seasonEl.attr("data-id")
            val dataSeason = seasonEl.attr("data-season")
            try {
                val seasonHtml = httpService.postText("$mainUrl/ajax/Episode", mapOf("post_id" to dataId, "season" to dataSeason), url)
                if (seasonHtml != null) {
                    val seasonDocParsed = Jsoup.parse(seasonHtml)
                    seasonDocParsed.select("a.hoverable.activable").forEach { epEl ->
                        val epName = epEl.selectFirst("episodetitle")?.text() ?: "Episode"
                        val epNum = Regex("الحلقة (\\\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
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
        var linksFound = false
        val document = httpService.getDocument(data) ?: return false
        
        document.select("ul.WatchServersList li btn").forEach { serverBtn ->
            val decodedUrl = decodeWecimaUrl(serverBtn.attr("data-url"))
            if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http")) {
                linksFound = true
                loadExtractor(decodedUrl, subtitleCallback, callback)
            }
        }
        
        document.select(".openLinkDown").forEach { downloadBtn ->
            val decodedUrl = decodeWecimaUrl(downloadBtn.attr("data-href"))
            if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http")) {
                linksFound = true
                loadExtractor(decodedUrl, subtitleCallback, callback)
            }
        }
        
        return linksFound
    }
}
