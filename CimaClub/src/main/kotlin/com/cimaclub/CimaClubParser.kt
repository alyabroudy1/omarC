package com.cimaclub

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import com.lagradost.api.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CimaClubParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = "div.BlocksHolder > div.Small--Box",
        title = CssSelector(query = "inner--title > h2", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = "div.BlocksHolder > div.Small--Box",
        title = CssSelector(query = "inner--title > h2", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.PostTitle", attr = "text"),
        poster = CssSelector(query = ".MainSingle .left .image img", attr = "src"),
        plot = CssSelector(query = ".StoryArea p", attr = "text"),
        tags = CssSelector(query = ".TaxContent a[href*='/genre/']", attr = "text"),
        year = CssSelector(query = ".TaxContent a[href*='/release-year/']", attr = "text", regex = "(\\d{4})"),
        seriesIndicator = CssSelector(query = "section.allepcont, section.allseasonss", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "section.allepcont .row a",
        title = CssSelector(query = ".ep-info h2", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = ".epnum", attr = "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        iframe = CssSelector(query = "ul#watch li", attr = "data-watch"),
        url = CssSelector(query = ".ServersList.Download a", attr = "href")
    )

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val title = doc.selectFirst("h1.PostTitle")?.text()?.trim() ?: return null
        val poster = doc.selectFirst(".MainSingle .left .image img")?.attr("src")?.ifBlank { null }
        var plot = doc.selectFirst(".StoryArea p")?.text()?.trim()
        plot = plot?.replace("قصة العرض", "")?.trim()
        val tags = doc.select(".TaxContent a[href*='/genre/']").mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
        val year = doc.selectFirst(".TaxContent a[href*='/release-year/']")?.text()?.toIntOrNull()

        val isSeries = url.contains("/series/") || url.contains("/مسلسل-") ||
                doc.select("section.allepcont .row a").size > 1

        val episodes = if (isSeries) {
            parseSeriesEpisodes(doc, url)
        } else emptyList()

        val watchUrl = if (isSeries) url else "${url.removeSuffix("/")}/watch/"

        return ParserInterface.ParsedLoadData(
            title = title,
            url = url,
            posterUrl = poster ?: "",
            plot = plot,
            year = year,
            type = if (isSeries) com.lagradost.cloudstream3.TvType.TvSeries else com.lagradost.cloudstream3.TvType.Movie,
            tags = tags,
            watchUrl = watchUrl,
            episodes = episodes
        )
    }

    private fun parseSeriesEpisodes(doc: Document, url: String): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        val seasons = doc.select("section.allseasonss .Small--Box a")

        if (seasons.isNotEmpty()) {
            for (seasonLink in seasons) {
                val seasonUrl = seasonLink.attr("href")
                val seasonDoc = if (seasonUrl == url) doc else null
                val seasonNumText = seasonLink.selectFirst(".epnum span")?.nextSibling()?.toString()?.trim()
                val seasonNum = seasonNumText?.toIntOrNull()

                seasonDoc?.select("section.allepcont .row a")?.mapTo(episodes) { ep ->
                    ParserInterface.ParsedEpisode(
                        url = ep.attr("href"),
                        name = ep.selectFirst(".ep-info h2")?.text() ?: "",
                        season = seasonNum ?: 1,
                        episode = ep.selectFirst(".epnum")?.ownText()?.trim()?.toIntOrNull() ?: 0
                    )
                }
            }
        } else {
            doc.select("section.allepcont .row a").mapTo(episodes) { ep ->
                ParserInterface.ParsedEpisode(
                    url = ep.attr("href"),
                    name = ep.selectFirst(".ep-info h2")?.text() ?: "",
                    season = 1,
                    episode = ep.selectFirst(".epnum")?.ownText()?.trim()?.toIntOrNull() ?: 0
                )
            }
        }

        return episodes
    }

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (url.contains("/series/") || url.contains("/مسلسل-")) return true
        if (element != null && element.selectFirst(".number") != null) return true
        return super.isSeries(title, url, element)
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        return null
    }
}
