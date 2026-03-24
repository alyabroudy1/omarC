package com.mycima

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.parsing.ParserInterface
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class MyCima : BaseProvider() {
    override val baseDomain get() = "mycima.boo"
    override val providerName get() = "MyCima"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/mycima.json"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/" to "احدث الاضافات",
        "/movies/" to "الافلام",
        "/series/" to "المسلسلات",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/" to "افلام انمي",
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/" to "مسلسلات انمي"
    )

    override fun getParser(): NewBaseParser = MyCimaParser()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val response = super.getMainPage(page, request) ?: return null
        val newItems = response.items.map { homePageList ->
            val modifiedList = homePageList.list.map { item ->
                // MyCima URL structure now contains مسلسل for TV series and فيلم for movies
                val isSeries = item.url.contains("مسلسل") || item.url.contains("%d9%85%d8%b3%d9%84%d8%b3%d9%84", ignoreCase = true)
                
                if (isSeries && item is MovieSearchResponse) {
                    newTvSeriesSearchResponse(item.name, item.url, TvType.TvSeries) {
                        this.posterUrl = item.posterUrl
                        this.year = item.year
                        this.posterHeaders = item.posterHeaders
                    }
                } else if (!isSeries && item is TvSeriesSearchResponse) {
                    newMovieSearchResponse(item.name, item.url, TvType.Movie) {
                        this.posterUrl = item.posterUrl
                        this.year = item.year
                        this.posterHeaders = item.posterHeaders
                    }
                } else item
            }
            homePageList.copy(list = modifiedList)
        }
        return response.copy(items = newItems)
    }

    data class MyCimaSearchResponseJson(
        val status: Boolean? = null,
        val results: List<MyCimaSearchItem>? = null
    )

    data class MyCimaSearchItem(
        val title: String? = null,
        val slug: String? = null,
        val image: String? = null,
        val year: String? = null,
        val istv: Int? = null
    )

    private fun parseSearchResponse(responseText: String): List<SearchResponse> {
        try {
            val parsed = AppUtils.parseJson<MyCimaSearchResponseJson>(responseText)
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
        val seasonsEl = doc.select(".SeasonsList ul li a")
        if (seasonsEl.isEmpty()) return data.episodes ?: emptyList()
        
        val postId = doc.selectFirst("input[name=post_id], meta[name=post_id]")?.attr("value")?.ifEmpty {
            doc.selectFirst("meta[name=post_id]")?.attr("content")
        }?.ifEmpty {
            doc.selectFirst("[data-postid]")?.attr("data-postid")
        } ?: Regex("""post_id\s*[:=]\s*['"]?(\d+)""").find(doc.html())?.groupValues?.get(1)

        if (postId == null) return data.episodes ?: emptyList()

        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        seasonsEl.forEach { seasonAnchor ->
            val seasonLabel = seasonAnchor.text()
            val seasonNum = Regex("الموسم (\\d+)").find(seasonLabel)?.groupValues?.get(1)?.toIntOrNull() 
                            ?: Regex("(\\d+)").find(seasonLabel)?.groupValues?.get(1)?.toIntOrNull() 
                            ?: 1
                            
            val dataSeason = seasonAnchor.attr("data-season")
            if (dataSeason.isBlank()) return@forEach

            try {
                val ajaxUrl = "$mainUrl/wp-content/themes/mycima/Ajaxt/Single/Episodes.php"
                val seasonHtml = httpService.postText(ajaxUrl, mapOf("season" to dataSeason, "post_id" to postId), referer = url)
                
                if (seasonHtml != null) {
                    val seasonDoc = Jsoup.parse(seasonHtml)
                    seasonDoc.select("a").forEach { epEl ->
                        val epUrl = epEl.attr("href")
                        val epTitle = epEl.selectFirst("episodetitle")?.text()?.trim() ?: "Episode"
                        val epNum = Regex("الحلقة (\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        
                        episodes.add(
                            ParserInterface.ParsedEpisode(
                                url = epUrl,
                                name = epTitle,
                                season = seasonNum,
                                episode = epNum
                            )
                        )
                    }
                }
            } catch (e: Exception) {}
        }
        
        return if (episodes.isNotEmpty()) episodes else (data.episodes ?: emptyList())
    }

    private fun decodeWatchUrl(encodedStr: String): String? {
        try {
            if (encodedStr.isBlank()) return null
            val match = Regex("""(?:/play/|/embed/)([^/]+)""").find(encodedStr)
            val base64Str = match?.groupValues?.get(1) ?: encodedStr
            val cleanedStr = base64Str.replace("+", "").trim()
            if (cleanedStr.startsWith("http")) return cleanedStr
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
        
        document.select("ul.WatchServersList li[data-watch], ul#watch li[data-watch], ul.WatchServersList li btn").forEach { serverBtn ->
            val dataWatch = serverBtn.attr("data-watch").ifEmpty { serverBtn.attr("data-url") }
            val decodedUrl = decodeWatchUrl(dataWatch)
            if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http")) {
                linksFound = true
                if (decodedUrl.contains("dood", ignoreCase = true)) {
                    val snifferUrl = com.cloudstream.shared.extractors.SnifferExtractor.createSnifferUrl(decodedUrl, mainUrl)
                    com.cloudstream.shared.extractors.SnifferExtractor().getUrl(snifferUrl, mainUrl, subtitleCallback, callback)
                } else {
                    loadExtractor(decodedUrl, subtitleCallback, callback)
                }
            }
        }
        
        document.select(".openLinkDown").forEach { downloadBtn ->
            val decodedUrl = decodeWatchUrl(downloadBtn.attr("data-href"))
            if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http")) {
                linksFound = true
                if (decodedUrl.contains("dood", ignoreCase = true)) {
                    val snifferUrl = com.cloudstream.shared.extractors.SnifferExtractor.createSnifferUrl(decodedUrl, mainUrl)
                    com.cloudstream.shared.extractors.SnifferExtractor().getUrl(snifferUrl, mainUrl, subtitleCallback, callback)
                } else {
                    loadExtractor(decodedUrl, subtitleCallback, callback)
                }
            }
        }
        if (!linksFound) {
            com.lagradost.api.Log.d("MyCima", "No links found on $data. HTML Dump:\n${document.html()}")
        }
        return linksFound
    }
}
