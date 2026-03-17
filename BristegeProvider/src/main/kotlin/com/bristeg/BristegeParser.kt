package com.bristeg

import com.cloudstream.shared.parsing.*
import com.lagradost.cloudstream3.TvType
import com.lagradost.api.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class BristegeParser : NewBaseParser() {

    override val mainPageConfig = MainPageConfig(
        container = "ul[class*='pm-ul-browse-videos'] > li, ul[class*='pm-ul-carousel-videos'] > li",
        title = CssSelector("div.caption h3 a", "title, text"),
        url = CssSelector("div.caption h3 a", "abs:href"),
        poster = CssSelector("div.pm-video-thumb img", "data-echo, data-original, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector("div.pm-video-heading h1"),
        plot = CssSelector("div.pm-video-description > div.txtv"),
        poster = CssSelector("meta[property=og:image]", "content"),
        tags = CssSelector("dl.dl-horizontal p strong:contains(اقسام) ~ span a span, dl.dl-horizontal strong:contains(الكلمات الدلالية) ~ a"),
        seriesIndicator = CssSelector("div.SeasonsBox")
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.SeasonsEpisodes[data-serie] a",
        url = CssSelector("", "abs:href"),
        title = CssSelector("", "title, text"),
        episode = CssSelector("em")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector("div#WatchServers button.watchButton, div#WatchServers button.watchbutton", "data-embed-url, data-embed"),
        iframe = CssSelector("div#Playerholder iframe", "src")
    )

    override fun parseMainPage(doc: Document): List<ParserInterface.ParsedItem> {
        val items = super.parseMainPage(doc)
        return items.map { it.copy(posterUrl = cleanPosterUrl(it.posterUrl), isMovie = !isSeries(it.title, it.url, null)) }
    }

    override fun parseSearch(doc: Document): List<ParserInterface.ParsedItem> {
        val items = super.parseSearch(doc)
        return items.map { it.copy(posterUrl = cleanPosterUrl(it.posterUrl), isMovie = !isSeries(it.title, it.url, null)) }
    }

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val data = super.parseLoadPage(doc, url) ?: return null
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        
        val hasSeasonBox = doc.selectFirst("div.SeasonsBox") != null
        
        // Refine isMovie based on tags if available
        val allTags = data.tags ?: emptyList()
        val isMovieByTag = allTags.any { it.contains("افلام", true) || it.contains("فلم", true) }
        
        if (hasSeasonBox && !isMovieByTag) {
            doc.select("div.SeasonsBoxUL ul li").forEach { seasonLi ->
                val seasonId = seasonLi.attr("data-serie")
                val seasonNum = seasonId.toIntOrNull() ?: 1
                doc.select("div.SeasonsEpisodes[data-serie='$seasonId'] a").forEach { epEl ->
                    val epUrl = epEl.attr("abs:href")
                    val epName = epEl.attr("title").ifBlank { epEl.text() }
                    val epNum = epEl.selectFirst("em")?.text()?.toIntOrNull()
                    episodes.add(
                        ParserInterface.ParsedEpisode(
                            name = epName,
                            url = epUrl,
                            season = seasonNum,
                            episode = epNum ?: 0
                        )
                    )
                }
            }
        }

        // Distinct and sort episodes
        val finalEpisodes = episodes.distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        val responseType = if (isMovieByTag) {
            TvType.Movie
        } else if (hasSeasonBox && finalEpisodes.isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return data.copy(
            posterUrl = cleanPosterUrl(data.posterUrl) ?: "",
            episodes = finalEpisodes,
            type = responseType
        )
    }

    override fun getSearchUrl(mainUrl: String, query: String): String {
        return "$mainUrl/search.php?keywords=$query"
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        return doc.selectFirst("a.xtgo")?.attr("href")
    }

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (url.contains("series1.php") || url.contains("view-serie.php") || url.contains("season")) return true
        return super.isSeries(title, url, element)
    }

    private fun cleanPosterUrl(url: String?): String? {
        if (url == null || url.isBlank()) return null
        var u = url.trim()
        
        val wpRegex = Regex("https?://i\\d+\\.wp\\.com/(.+)")
        wpRegex.find(u)?.let { 
            u = it.groupValues[1]
            if (!u.startsWith("http")) u = "https://$u"
        }
        
        u = u.substringBefore("?")
        if (u.startsWith("//")) u = "https:$u"
        
        return u
    }
}
