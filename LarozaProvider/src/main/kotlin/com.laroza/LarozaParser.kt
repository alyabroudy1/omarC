package com.laroza

import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.cloudstream.shared.extractors.LinkResolvers
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.cloudstream.shared.parsing.BaseParser
import com.cloudstream.shared.parsing.ParserInterface.ParsedItem
import com.cloudstream.shared.parsing.ParserInterface.ParsedLoadData
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode

class LarozaParser : NewBaseParser() {
    protected open val mainPageContainerSelectors = listOf("div.thumbnail")

    override fun parseMainPage(doc: Document): List<ParsedItem> {
        // Find all item containers on the main grid
        return doc.select(".thumbnail").map { element ->
            ParsedItem(
                title = element.selectFirst("a[title]")?.attr("title") ?:  "",
                url = element.selectFirst("a[title]")?.attr("href") ?: "",
                posterUrl = element.selectFirst("img")?.attr("src"),
                isMovie = true,
            )
        }
    }

    override fun parseSearch(doc: Document): List<ParsedItem> {
        // Similar to main page, but usually targeting search result rows
        return doc.select(".search-result-row").map { element ->
            ParsedItem(
                title = element.select("h2").text(),
                url = element.select("a").attr("abs:href"),
                posterUrl = "",
                isMovie = true,
            )
        }
    }

    override fun parseLoadPage(doc: Document, url: String): ParsedLoadData? {
        // Extracting detail-level info (description, genres, etc.)
        val title = doc.select(".detail-title").text() ?: return null
        return ParsedLoadData(
            title = title,
            plot = doc.select(".description-text").text(),
            posterUrl = doc.select(".poster-img").attr("src"),
            url = url,
            type = TvType.Movie,
            year = doc.select(".year").text().toIntOrNull(),
        )
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode> {
        // Filter by season if necessary, otherwise map episode list
        return doc.select(".episode-list-item").map { element ->
            ParsedEpisode(
                name = element.select(".ep-name").text(),
                url = element.select("a").attr("abs:href"),
                season = seasonNum ?: 1,
                episode = element.select(".ep-number").text().toInt(),
            )
        }
    }

    override fun extractPlayerUrls(doc: Document): List<String> {
        // Extract iframe sources or video links
        return doc.select("iframe[src], video source[src]").map {
            it.attr("abs:src")
        }.filter { it.isNotEmpty() }
    }
}