package com.tuktukhd

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.WatchServerSelector
import com.cloudstream.shared.parsing.CssSelector
import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TukTukcimaParser : NewBaseParser() {

    override val mainPageConfig = MainPageConfig(
        container = "li.Small--Box, div.Block--Item",
        title = CssSelector(query = ".title", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val searchConfig = mainPageConfig

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.post-title a, h1", attr = "text"),
        poster = CssSelector(query = ".MainSingle .left .image img", attr = "src"),
        plot = CssSelector(query = ".story p", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "a.SeriesEpisodeCard, .allepcont a",
        title = CssSelector(query = ".SeriesEpisodeInfo h3, .ep-info h2, a[title]", attr = "title, text"),
        url = CssSelector(query = "", attr = "abs:href, href"),
        episode = CssSelector(query = ".SeriesEpisodeNumber strong, .epnum", attr = "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "iframe#main-video-frame", attr = "data-crypt"),
        id = CssSelector(query = "", attr = ""),
        title = CssSelector(query = "", attr = ""),
        iframe = CssSelector(query = "", attr = "")
    )

    override fun parseMainPage(doc: Document): List<ParserInterface.ParsedItem> {
        return doc.select("li.Small--Box, div.Block--Item").mapNotNull { toSearchResult(it) }
    }

    override fun parseSearch(doc: Document): List<ParserInterface.ParsedItem> {
        return doc.select("li.Small--Box, div.Block--Item").mapNotNull { toSearchResult(it) }
    }

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query&page=1"
    }

    private fun toSearchResult(it: Element): ParserInterface.ParsedItem? {
        val linkTag = it.selectFirst("a") ?: return null
        val titleTag = it.selectFirst(".title")
        val title = titleTag?.text() ?: linkTag.attr("title").takeIf { it.isNotBlank() } ?: ""
        val href = linkTag.attr("href")

        val imgTag = it.selectFirst("img")
        val posterUrl = imgTag?.attr("data-src").takeIf { !it.isNullOrBlank() }
            ?: imgTag?.attr("src")

        val isMovie = !(title.contains("مسلسل") || title.contains("حلقة") || href.contains("series"))

        return ParserInterface.ParsedItem(
            title = title,
            url = href,
            posterUrl = posterUrl,
            isMovie = isMovie
        )
    }

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val title = doc.selectFirst("h1.post-title a")?.text() 
            ?: doc.selectFirst("h1")?.text() 
            ?: "Unknown"

        val cleanTitle = title.replace(Regex("\\s*(الحلقة\\s*\\d+|مترجم|مدبلج).*"), "").trim()
        val plot = doc.select(".story p").text()
        
        val posterElem = doc.selectFirst(".MainSingle .left .image img")
        val posterUrl = posterElem?.attr("src") 
            ?: doc.selectFirst(".homepage__bg")?.attr("style")?.substringAfter("url(")?.substringBefore(")") 
            ?: ""

        val yearStr = doc.select(".RightTaxContent a[href*='release-year']").text()
        val year = yearStr.filter { it.isDigit() }.toIntOrNull()

        val type = if (doc.select(".allepcont a, .allseasonss, a.SeriesEpisodeCard").isEmpty()) TvType.Movie else TvType.TvSeries

        // Check for direct episodes on the loaded page (new SeriesEpisodeCard cards + legacy allepcont)
        val episodes = if (type == TvType.TvSeries) {
            parseEpisodes(doc, 1).takeIf { it.isNotEmpty() }
        } else null

        return ParserInterface.ParsedLoadData(
            title = cleanTitle,
            url = url,
            posterUrl = posterUrl,
            plot = plot,
            year = year,
            type = type,
            watchUrl = url,
            episodes = episodes
        )
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val list = mutableListOf<ParserInterface.ParsedEpisode>()
        // New layout: season pages list a.SeriesEpisodeCard
        doc.select("a.SeriesEpisodeCard").forEach { ep ->
            val epHref = ep.attr("abs:href").ifBlank { ep.attr("href") }.trim()
            if (epHref.isBlank()) return@forEach
            val epTitle = ep.attr("title").ifBlank {
                ep.selectFirst(".SeriesEpisodeInfo h3")?.text().orEmpty()
            }.trim()
            val epNum = ep.selectFirst(".SeriesEpisodeNumber strong")?.text()
                ?.filter { it.isDigit() }?.toIntOrNull()
                ?: Regex("(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            list.add(
                ParserInterface.ParsedEpisode(
                    url = epHref,
                    name = epTitle.ifBlank { "الحلقة $epNum" },
                    season = seasonNum ?: 1,
                    episode = epNum
                )
            )
        }
        // Legacy layout fallback
        doc.select(".allepcont a").forEach { ep ->
            val epTitle = ep.select(".ep-info h2").text().trim()
            val epHref = ep.attr("abs:href").ifBlank { ep.attr("href") }.trim()
            if (epHref.isBlank()) return@forEach
            val epNumStr = ep.select(".epnum").text()
            val epNum = epNumStr.filter { it.isDigit() }.toIntOrNull()
            list.add(
                ParserInterface.ParsedEpisode(
                    url = epHref,
                    name = epTitle.ifBlank { "الحلقة ${epNum ?: 0}" },
                    season = seasonNum ?: 1,
                    episode = epNum ?: 0
                )
            )
        }
        return list.distinctBy { it.url }
    }


}
