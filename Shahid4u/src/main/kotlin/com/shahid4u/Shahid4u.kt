package com.shahid4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder

class Shahid4u : MainAPI() {
    override var mainUrl = "https://shaahed4u.net/"
    override var name = "Shahid4u"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val logTag = "Shahid4uProvider"

    private data class Server(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String
    )

    private val TRANSPARENT_PNG_DATA_URI =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg=="

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller


    private fun buildBrowserHeaders(referer: String? = null): Map<String, String> {
        val ref = referer ?: mainUrl
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Referer" to ref,
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document"
        )
    }

    private fun buildMergedHeaders(url: String, referer: String? = null): Map<String, String> {
        val base = buildBrowserHeaders(referer).toMutableMap()

        return try {

            val cloudHeaders = cloudflareKiller.getCookieHeaders(url).toMultimap()
                .mapValues { entry -> entry.value.joinToString("; ") }

            base.putAll(cloudHeaders)
            base
        } catch (e: Exception) {

            base
        }
    }

    private fun makeAbsoluteUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val p = url.trim()
        return when {
            p.startsWith("http://", true) || p.startsWith("https://", true) -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/") -> mainUrl.trimEnd('/') + p
            else -> {

                mainUrl + p
            }
        }
    }

    private suspend fun httpGet(url: String, referer: String? = null): org.jsoup.nodes.Document {
        val headers = buildMergedHeaders(url, referer)
        return app.get(url, referer = referer ?: mainUrl, headers = headers, interceptor = cfInterceptor).document
    }

    private fun parseCard(element: Element): SearchResponse? {

        val linkElement = element.selectFirst("a.show.card, a.glide_post, a")
        if (linkElement == null) {

            return null
        }
        val href = linkElement.attr("href").ifBlank { linkElement.absUrl("href") }

        val mainTitle = element.selectFirst("p.title")?.text()?.trim()
        val description = element.selectFirst("p.description")?.text()?.trim()
        val title = if (!mainTitle.isNullOrBlank()) {
            if (!description.isNullOrBlank()) "$mainTitle - $description" else mainTitle
        } else {
            element.selectFirst("div.card-content")?.text()?.trim()
                ?: element.selectFirst("h3")?.text()?.trim()
        }
        if (title.isNullOrBlank()) {

            return null
        }

        val posterStyle = linkElement.attr("style")
        var posterUrl = Regex("""url\(['"]?(.*?)['"]?\)""").find(posterStyle)?.groupValues?.get(1)
        if (posterUrl.isNullOrBlank()) posterUrl = element.selectFirst("img")?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = element.selectFirst("img")?.attr("src")
        posterUrl = makeAbsoluteUrl(posterUrl) ?: TRANSPARENT_PNG_DATA_URI

        val isTvSeries = element.selectFirst(".ep_num, .الحلقة") != null || href.contains("/episode/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        if (request.data.isNotEmpty()) {
            val categoryUrl = "${request.data}?page=$page"
            val document = httpGet(categoryUrl, referer = mainUrl)
            val items = document.select("div.shows-container.row div[class*=col-]").mapNotNull {
                parseCard(it)
            }
            val hasNext = document.selectFirst("ul.pagination li.page-item.active + li.page-item a") != null
            return newHomePageResponse(request.name, items, hasNext)
        }

        if (page > 1) return newHomePageResponse(emptyList())

        val homePageList = mutableListOf<HomePageList>()
        val document = httpGet(mainUrl, referer = mainUrl)

        try {
            val sliderItems = document.select("div.glide li.glide__slide:not(.glide__slide--clone)").mapNotNull {
                parseCard(it)
            }
            if (sliderItems.isNotEmpty()) {
                homePageList.add(HomePageList("أبرز العروض", sliderItems))
            }
        } catch (e: Exception) {

        }

        val categories = listOf(
            "أفلام أجنبي" to "${mainUrl}category/افلام-اجنبي",
            "أفلام عربي" to "${mainUrl}category/افلام-عربي",
            "أفلام هندي" to "${mainUrl}category/افلام-هندي",
            "أفلام تركية" to "${mainUrl}category/افلام-تركية",
            "أفلام أسيوية" to "${mainUrl}category/افلام-اسيوية",
            "أفلام انمي" to "${mainUrl}category/افلام-انمي",
            "مسلسلات أجنبي" to "${mainUrl}category/مسلسلات-اجنبي",
            "مسلسلات عربي" to "${mainUrl}category/مسلسلات-عربي",
            "مسلسلات هندية" to "${mainUrl}category/مسلسلات-هندية",
            "مسلسلات تركية" to "${mainUrl}category/مسلسلات-تركية",
            "مسلسلات أسيوية" to "${mainUrl}category/مسلسلات-اسيوية",
            "مسلسلات انمي" to "${mainUrl}category/مسلسلات-انمي",
            "مسلسلات مدبلجة" to "${mainUrl}category/مسلسلات-مدبلجة"
        )

        for ((title, url) in categories) {
            try {
                val doc = httpGet(url, referer = mainUrl)
                val items = doc.select("div.shows-container.row div[class*=col-]").take(40).mapNotNull {
                    parseCard(it)
                }
                if (items.isNotEmpty()) homePageList.add(HomePageList(title, items, true))
            } catch (e: Exception) {

            }
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "${mainUrl}search?s=$encoded"

        return try {
            val document = httpGet(searchUrl, referer = mainUrl)
            val resultItems = document.select("div.shows-container.row div[class*=col-]")

            if (resultItems.isEmpty()) {

                return emptyList()
            }

            resultItems.mapIndexedNotNull { index, element ->

                try {
                    parseCard(element)
                } catch (e: Exception) {

                    null
                }
            }
        } catch (e: Exception) {

            emptyList()
        }
    }



    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("span.title")?.text()?.trim() ?: "غير متوفر"
        val poster = document.selectFirst("div.poster-side img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = document.selectFirst("span.description")?.text()?.trim()
        val tags = document.select("div.qualities span.q-tag a").map { it.text() }

        val seasons = document.select("div.w-100.bg-main.rounded.my-4 a.epss[href*='/season/']")
        val episodes = ArrayList<Episode>()

        if (seasons.isNotEmpty()) {
            seasons.apmap { seasonElement ->
                val seasonUrl = seasonElement.attr("href")
                val seasonDoc = app.get(seasonUrl).document

                seasonDoc.select("div.w-100.bg-main.rounded.my-4 a.epss:not([href*='/season/'])").forEach { episodeElement ->
                    val epName = episodeElement.text().trim()
                    val epUrl = episodeElement.attr("href")
                    val episodeNumber = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                    val seasonNumber = Regex("""الموسم\s*(\d+)""").find(seasonElement.text())?.groupValues?.get(1)?.toIntOrNull()

                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.episode = episodeNumber
                        this.season = seasonNumber
                        this.posterUrl = poster
                    })
                }
            }
        } else {
            document.select("div.w-100.bg-main.rounded.my-4 a.epss:not([href*='/season/'])").forEach { episodeElement ->
                val epName = episodeElement.text().trim()
                val epUrl = episodeElement.attr("href")
                val episodeNumber = Regex("""\d+""").find(epName)?.value?.toIntOrNull()

                episodes.add(newEpisode(epUrl) {
                    this.name = epName
                    this.episode = episodeNumber
                    this.posterUrl = poster
                })
            }
        }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (sortedEpisodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data.replace("/film/", "/watch/").replace("/episode/", "/watch/")

        try {
            val document = httpGet(watchUrl, referer = mainUrl)

            val script = document.select("script").find { it.data().contains("let servers") }?.data()
                ?: return false

            val jsonStringEncoded = script.substringAfter("JSON.parse('").substringBefore("');")
            if (jsonStringEncoded.isBlank()) return false

            val jsonStringDecoded = jsonStringEncoded.replace("\\/", "/")
            val servers = parseJson<List<Server>>(jsonStringDecoded)

            for (server in servers) {
                try {

                    loadExtractor(server.url, watchUrl, subtitleCallback, callback)

                    if (server.name.equals("EarnVids", true) || server.name.equals("StreamHG", true)) {
                        try {
                            val customLink = ExternalEarnVidsExtractor.extract(server.url, mainUrl)
                            if (!customLink.isNullOrBlank()) {
                                val finalLink = customLink.toString()

                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${server.name} (Custom)",
                                        url = finalLink,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = mainUrl
                                    }
                                )

                            } else {

                            }
                        } catch (ex: Exception) {

                        }
                    }

                } catch (e: Exception) {

                }
            }

        } catch (e: Exception) {

            return false
        }

        return true
    }
}


