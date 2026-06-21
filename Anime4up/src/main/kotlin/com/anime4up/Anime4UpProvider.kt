package com.anime4up

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.extractors.MegaboxResolver
import org.jsoup.nodes.Document

class Anime4Up : BaseProvider() {
    override val baseDomain get() = "w1.anime4up.rest"
    override val providerName get() = "Anime4Up"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/anime4up.json"

    override val mainPage = mainPageOf(
        "$mainUrl" to "الرئيسية"
    )

    override fun getParser(): NewBaseParser {
        return Anime4UpParser()
    }

    override suspend fun load(url: String): LoadResponse? {
        httpService.ensureInitialized()
        var animeUrl = url
        var animeDoc = httpService.getDocument(url) ?: return null

        if (url.contains("/episode/")) {
            val parentAnimeLink = animeDoc.selectFirst(".anime-page-link a")?.attr("href")
            if (!parentAnimeLink.isNullOrBlank()) {
                animeUrl = parentAnimeLink
                animeDoc = httpService.getDocument(animeUrl) ?: return null
            }
        }

        val title = animeDoc.selectFirst("h1.anime-details-title")?.text() ?: "Unknown"
        val poster = animeDoc.selectFirst(".anime-thumbnail img")?.let {
            it.attr("data-image").ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }
        }
        val plot = animeDoc.selectFirst("p.anime-story")?.text()
        val tags = animeDoc.select("ul.anime-genres li a").mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
        val year = animeDoc.select(".anime-info").firstOrNull {
            it.text().contains("بداية العرض")
        }?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val typeText = animeDoc.select(".anime-info").text()
        val type = if (typeText.contains("Movie", true) || typeText.contains("فيلم"))
            TvType.AnimeMovie else TvType.Anime

        val episodes = loadEpisodes(animeDoc, animeUrl)
        return newTvSeriesLoadResponse(title, animeUrl, type, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    private suspend fun loadEpisodes(animeDoc: Document, animeUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val poster = animeDoc.selectFirst(".anime-thumbnail img")?.let {
            it.attr("data-image").ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }
        }

        val firstEpLink = animeDoc.selectFirst(".anime-external-links a.anime-first-ep")?.attr("href")
            ?: animeDoc.selectFirst("#episodesList .themexblock a")?.attr("href")

        if (!firstEpLink.isNullOrBlank()) {
            val epDoc = httpService.getDocument(firstEpLink)
            if (epDoc != null) {
                val sidebarEpisodes = epDoc.select("ul.all-episodes-list li a")
                if (sidebarEpisodes.isNotEmpty()) {
                    sidebarEpisodes.forEach { element ->
                        val epUrl = element.attr("href")
                        val epName = element.text().trim()
                        val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
                        episodes.add(newEpisode(epUrl) {
                            name = epName
                            posterUrl = poster
                            episode = epNum
                        })
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            animeDoc.select("#episodesList .themexblock").forEach { element ->
                val epUrl = element.selectFirst("a")?.attr("href") ?: return@forEach
                val epName = element.selectFirst(".badge.light-soft span")?.text() ?: "Episode"
                val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
                episodes.add(newEpisode(epUrl) {
                    name = epName
                    posterUrl = poster
                    episode = epNum
                })
            }
        }

        return episodes.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        httpService.ensureInitialized()

        val doc = httpService.getDocument(data) ?: return false
        val seenLinks = mutableSetOf<String>()

        for (li in doc.select("ul#episode-servers li[data-watch]")) {
            val serverUrl = li.attr("data-watch")

            val linksToProcess = if (serverUrl.contains("share4max") || serverUrl.contains("megamax")) {
                MegaboxResolver.process(serverUrl, data)
            } else {
                listOf(serverUrl)
            }

            for (link in linksToProcess) {
                if (link.isNotBlank() && seenLinks.add(link)) {
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            }
        }

        for (tr in doc.select("div.download-list table.table tbody tr")) {
            val downloadLink = tr.selectFirst("td.td-link a")?.attr("href")
            if (!downloadLink.isNullOrBlank() && seenLinks.add(downloadLink)) {
                loadExtractor(downloadLink, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
