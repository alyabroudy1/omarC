package com.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import android.util.Log

class Asia2tvProvider : MainAPI() {
    override var mainUrl = "https://asia2tv.com"
    override var name = "Asia2Tv.Com"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private fun getPosterFromElement(element: Element?): String? {

        if (element == null) return null
        return element.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
    }



    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url = if (page > 1) {
            "$mainUrl/series?page=$page" // رابط عام للفئات
        } else {
            mainUrl
        }
        val document = app.get(url).document
        val homePageList = ArrayList<HomePageList>()

        document.select("div.home-tvsries").forEach { block ->
            val title = block.selectFirst("h4.sec-title")?.text()?.trim() ?: return@forEach
            val items = block.select("div.postmovie").mapNotNull {
                it.toSearchResponse()
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        return HomePageResponse(homePageList.filter { it.list.isNotEmpty() })
    }


    override suspend fun search(query: String): List<SearchResponse> {


        val url = "$mainUrl/search?category=series&s=$query&show=free&"
        val document = app.get(url).document




        return document.select(".row-movies .col-lg-3, .row-movies .col-md-4").mapNotNull { resultCard ->
            resultCard.toSearchResponse()
        }

    }

    private fun Element.toSearchResponse(): SearchResponse? {

        val link = this.selectFirst("a") ?: return null
        val title = this.selectFirst("h4 a")?.text()?.trim()
            ?: link.attr("title").ifBlank { null } ?: return null

        if (title.isBlank()) return null
        val href = link.attr("href")
        val posterUrl = this.selectFirst("div.postmovie-thumb-bg img")?.attr("data-src")
        val type = if (href.contains("/movie/")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }



    private data class EpisodeAjaxResponse(
        val status: Boolean?,
        val newpage: Int?,
        val html: String?,
        val showmore: Boolean?
    )

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.substringBefore("#")

        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Referer" to pageUrl
        )

        val mainPageResponse = app.get(pageUrl, headers = headers)
        val document = mainPageResponse.document

        val title = document.selectFirst("h1.mb-0")?.text()?.trim() ?: "Unknown"
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

        val poster = document.selectFirst("div.single-thumb-bg img")?.attr("data-src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("p.mb-3")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val tags = document.select("div.post_tags a").map { it.text() }
        val year = document.select("ul.mb-2 li:contains(سنة العرض) a").firstOrNull()?.text()?.toIntOrNull()
        val rating = document.selectFirst("div.post_review_avg")?.text()?.toRatingInt()
        val recommendations = document.select(".row .postmovie").mapNotNull { it.toSearchResponse() }
        val allEpisodesElements = document.select("div.loop-episode a").toMutableList()
        val serieId = Regex("""serieid=(\d+)""").find(document.html())?.groupValues?.get(1)

        if (!csrfToken.isNullOrBlank() && !serieId.isNullOrBlank() && document.selectFirst("a.more-episode") != null) {

            headers["X-CSRF-TOKEN"] = csrfToken
            headers["X-XSRF-TOKEN"] = csrfToken
            headers["X-Requested-With"] = "XMLHttpRequest"

            var page = 2
            var hasMore = true
            while (hasMore) {
                try {
                    val response = app.post(
                        "$mainUrl/ajaxGetRequest",
                        headers = headers,
                        cookies = mainPageResponse.cookies,
                        data = mapOf(
                            "action" to "moreepisode",
                            "page" to page.toString(),
                            "serieid" to serieId
                        )
                    ).parsed<EpisodeAjaxResponse>()

                    if (response.status == true && !response.html.isNullOrBlank()) {
                        val newEpisodes = Jsoup.parseBodyFragment(response.html).select("a")
                        allEpisodesElements.addAll(newEpisodes)
                        page = response.newpage ?: (page + 1)
                        hasMore = response.showmore ?: false
                    } else {
                        hasMore = false
                    }
                } catch (e: Exception) {

                    hasMore = false
                }
            }
        }

        val isMovie = url.contains("/movie/")
        if (isMovie || allEpisodesElements.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        } else {


            val episodes = allEpisodesElements.mapNotNull { el ->
                val href = el.attr("href")
                val name = el.selectFirst("div.titlepisode")?.ownText()?.trim()
                if (name.isNullOrBlank()) return@mapNotNull null
                val epNum = name.filter { it.isDigit() }.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.episode = epNum
                    this.posterUrl = poster // إضافة بوستر المسلسل لكل حلقة
                }
            }.sortedBy { it.episode }


            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        }
    }

    private data class ServerAjaxResponse(
        val status: Boolean?,
        val codeplay: String?
    )

    override suspend fun loadLinks(
        data: String, // `data` هنا هو رابط الحلقة
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG = "Asia2tv-LoadLinks"

        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Referer" to data
        )

        val mainPageResponse = app.get(data, headers = headers)
        val document = mainPageResponse.document
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

        if (csrfToken.isNullOrBlank()) {

            throw ErrorLoadingException("Failed to get CSRF token for servers")
        }

        headers["X-CSRF-TOKEN"] = csrfToken
        headers["X-XSRF-TOKEN"] = csrfToken
        headers["X-Requested-With"] = "XMLHttpRequest"

        val serverElements = document.select("li.getplay")

        if (serverElements.isEmpty()) {

        }

        serverElements.apmap { serverLiElement ->
            val serverElement = serverLiElement.selectFirst("a") ?: return@apmap
            val serverName = serverElement.text()?.trim() ?: "Unknown Server"
            val serverCode = serverElement.attr("data-code")

            if (serverCode.isBlank()) {

                return@apmap
            }

            try {


                val postData = mapOf(
                    "action" to "iframe_server", // <-- القيمة الصحيحة
                    "code" to serverCode
                )

                val serverResponse = app.post(
                    "$mainUrl/ajaxGetRequest",
                    headers = headers,
                    cookies = mainPageResponse.cookies,
                    data = postData
                ).parsed<ServerAjaxResponse>()

                val iframeHtml = serverResponse.codeplay
                if (serverResponse.status == true && !iframeHtml.isNullOrBlank()) {
                    val iframeSrc = Jsoup.parseBodyFragment(iframeHtml).selectFirst("iframe")?.attr("src")

                    if (!iframeSrc.isNullOrBlank()) {

                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    } else {

                    }
                }
            } catch (e: Exception) {

            }
        }

        return true
    }
}