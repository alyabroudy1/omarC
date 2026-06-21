package com.dt

import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class DimaToon : BaseProvider() {
    override val baseDomain get() = "www.dima-toon.com"
    override val providerName get() = "Dima Toon"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/dima-toon.json"

    override val mainPage = mainPageOf(
        "series" to "المسلسلات المضافة حديثًا",
        "episodes" to "الحلقات المضافة حديثًا"
    )

    override val supportedTypes = setOf(TvType.Cartoon)

    override fun getParser(): NewBaseParser {
        return DimaToonParser()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        httpService.ensureInitialized()
        val doc = httpService.getDocument(mainUrl) ?: return null

        val home = when (request.data) {
            "series" -> {
                doc.select("div#cartoon-list div.cartoon-item a").mapNotNull { element ->
                    val href = element.attr("href")
                    if (href.isBlank()) return@mapNotNull null
                    val title = element.selectFirst("p, .episode-title")?.text()?.trim() ?: return@mapNotNull null
                    val posterUrl = element.selectFirst("img")?.attr("src")
                    newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
                        this.posterUrl = posterUrl
                    }
                }
            }
            "episodes" -> {
                doc.select("div#cartoon-episodes-container div.episode-card a").mapNotNull { element ->
                    val href = element.attr("href")
                    if (href.isBlank()) return@mapNotNull null
                    val title = element.selectFirst("p, .episode-title")?.text()?.trim() ?: return@mapNotNull null
                    val posterUrl = element.selectFirst("img")?.attr("src")
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                }
            }
            else -> emptyList()
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        httpService.ensureInitialized()

        if (url.contains("/cartoon-episode/")) {
            val doc = httpService.getDocument(url) ?: return null
            val title = doc.selectFirst("h1.xpro-post-title")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim() ?: return null
            val poster = doc.selectFirst("div.elementor-element-e7ee95b img")?.attr("src")
            val plot = doc.selectFirst("div.elementor-element-024e1d8")?.text()?.trim()
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val doc = httpService.getDocument(url) ?: return null
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
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
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
        httpService.ensureInitialized()
        val doc = httpService.getDocument(data) ?: return false
        val videoSource = doc.selectFirst("video.easy-video-player > source")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: return false

        if (videoSource.endsWith(".mp4") || videoSource.endsWith(".m3u8")) {
            callback.invoke(
                newExtractorLink(source = this.name, name = this.name, url = videoSource) {
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
