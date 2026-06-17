package com.dima

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class DimaToonProvider : MainAPI() {
    override var mainUrl = "https://www.dima-toon.com"
    override var name = "Dima Toon"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Cartoon)

    override val mainPage = mainPageOf(
        "series" to "المسلسلات المضافة حديثًا",
        "episodes" to "الحلقات المضافة حديثًا"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val document = app.get(mainUrl).document
        val home = when (request.data) {
            "series" -> {
                document.select("div#cartoon-list div.cartoon-item a").mapNotNull {
                    it.toSearchResponse()
                }
            }
            "episodes" -> {
                document.select("div#cartoon-episodes-container div.episode-card a").mapNotNull {
                    it.toSearchResponse()
                }
            }
            else -> emptyList()
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null

        val title = this.selectFirst("p, .episode-title")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return if (href.contains("/cartoon-episode/")) {

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("div#cartoon-list div.cartoon-item a, div#cartoon-episodes-container div.episode-card a").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        if (url.contains("/cartoon-episode/")) {
            val doc = app.get(url).document
            val title = doc.selectFirst("h1.xpro-post-title")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim() ?: return null
            val poster = doc.selectFirst("div.elementor-element-e7ee95b img")?.attr("src")
            val plot = doc.selectFirst("div.elementor-element-024e1d8")?.text()?.trim()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val doc = app.get(url).document

        val title = doc.selectFirst("h1.anime-title")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("div.cartoon-image img")?.attr("src")
        val plot = doc.selectFirst("div.brief-story p")?.text()?.trim()

        val episodes = doc.select("div.episodes-grid div.episode-box a").mapNotNull { el ->
            val href = el.attr("href")
            val name = el.text()

            val episodeNumber = Regex("""\s(\d+)$""").find(name)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = name
                this.episode = episodeNumber
            }
        }.reversed() // Episodes are usually listed from newest to oldest

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        val videoSource = doc.selectFirst("video.easy-video-player > source")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: return false

        if (videoSource.endsWith(".mp4") || videoSource.endsWith(".m3u8")) {

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoSource,
                ) {
                    referer = mainUrl
                    quality = Qualities.Unknown.value
                }
            )

        } else {
            loadExtractor(videoSource, data, subtitleCallback, callback)
        }

        return true
    }

}