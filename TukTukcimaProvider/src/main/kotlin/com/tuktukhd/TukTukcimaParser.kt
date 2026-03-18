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
        container = ".allepcont a",
        title = CssSelector(query = ".ep-info h2", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        episode = CssSelector(query = ".epnum", attr = "text", regex = "(\\d+)")
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

        val type = if (doc.select(".allepcont, .allseasonss").isEmpty()) TvType.Movie else TvType.TvSeries

        // Check for direct episodes on the loaded page
        val episodes = if (type == TvType.TvSeries) {
            val list = mutableListOf<ParserInterface.ParsedEpisode>()
            doc.select(".allepcont a").forEach { ep ->
                val epTitle = ep.select(".ep-info h2").text().trim()
                val epHref = ep.attr("href")
                val epNumStr = ep.select(".epnum").text()
                val epNum = epNumStr.filter { it.isDigit() }.toIntOrNull()
                val epThumb = ep.select("img").let { img ->
                    img?.attr("data-src").takeIf { !it.isNullOrBlank() } ?: img?.attr("src")
                }
                list.add(
                    ParserInterface.ParsedEpisode(
                        url = epHref,
                        name = epTitle,
                        season = 1,
                        episode = epNum ?: 0
                    )
                )
            }
            list
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
        doc.select(".allepcont a").forEach { ep ->
            val epTitle = ep.select(".ep-info h2").text().trim()
            val epHref = ep.attr("href")
            val epNumStr = ep.select(".epnum").text()
            val epNum = epNumStr.filter { it.isDigit() }.toIntOrNull()
            list.add(
                ParserInterface.ParsedEpisode(
                    url = epHref,
                    name = epTitle,
                    season = seasonNum ?: 1,
                    episode = epNum ?: 0
                )
            )
        }
        return list
    }


}
