package com.anime4up

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anime4UpParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = ".main-widget",
        item = ".themexblock, .anime-card-container",
        title = CssSelector(query = "h3", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "img, .image", attr = "data-image, data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = "div.anime-grid div.anime-card-themex",
        title = CssSelector(query = ".anime-card-title h3 a", attr = "text"),
        url = CssSelector(query = ".anime-card-title h3 a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-image, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.anime-details-title", attr = "text"),
        poster = CssSelector(query = ".anime-thumbnail img", attr = "data-image, data-src, src"),
        plot = CssSelector(query = "p.anime-story", attr = "text"),
        tags = CssSelector(query = "ul.anime-genres li a", attr = "text"),
        year = CssSelector(query = ".anime-info", attr = "text", regex = "(\\d{4})"),
        seriesIndicator = CssSelector(query = "#episodesList", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "ul.all-episodes-list li a, #episodesList .themexblock",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "ul#episode-servers li[data-watch]", attr = "data-watch"),
        iframe = CssSelector(query = "div.download-list table.table tbody tr td.td-link a", attr = "href")
    )

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val title = doc.selectFirst("h1.anime-details-title")?.text() ?: return null
        val poster = doc.selectFirst(".anime-thumbnail img")?.let {
            it.attr("data-image").ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }
        }
        val plot = doc.selectFirst("p.anime-story")?.text()
        val tags = doc.select("ul.anime-genres li a").mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
        val year = doc.select(".anime-info").firstOrNull {
            it.text().contains("بداية العرض")
        }?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val typeText = doc.select(".anime-info").text()
        val type = if (typeText.contains("Movie", true) || typeText.contains("فيلم"))
            com.lagradost.cloudstream3.TvType.AnimeMovie
        else
            com.lagradost.cloudstream3.TvType.Anime

        return ParserInterface.ParsedLoadData(
            title = title,
            url = url,
            posterUrl = poster ?: "",
            plot = plot,
            year = year,
            type = type,
            tags = tags
        )
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        val firstEpLink = doc.selectFirst(".anime-external-links a.anime-first-ep")?.attr("href")
            ?: doc.selectFirst("#episodesList .themexblock a")?.attr("href")
        return firstEpLink
    }

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        return true
    }
}
