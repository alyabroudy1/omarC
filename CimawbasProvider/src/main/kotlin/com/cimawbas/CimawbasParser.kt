package com.cimawbas

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document

class CimawbasParser : NewBaseParser() {

    override val mainPageConfig = MainPageConfig(
        container = "ul#pm-grid li",
        title = CssSelector("div.caption h3 a", "text"),
        url = CssSelector("div.caption h3 a", "href"),
        poster = CssSelector("img", "data-echo, data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = "ul#pm-grid li",
        title = CssSelector("div.caption h3 a", "text"),
        url = CssSelector("div.caption h3 a", "href"),
        poster = CssSelector("img", "data-echo, data-src, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector("h1[itemprop='name'], h1.PostTitle, .single-title h1, .watch-title h1", "text"),
        poster = CssSelector("meta[property='og:image'], .poster img, .watch-poster img", "content, data-echo, data-src, src"),
        plot = CssSelector(".StoryArea p, .watch-description, .single-description", "text"),
        year = CssSelector(".TaxContent a[href*='release-year'], .year, .watch-year", "text"),
        rating = CssSelector(".imdbR span, .rating", "text"),
        tags = CssSelector(".TaxContent .genre a, .genres a, .Tags a", "text"),
        parentSeriesUrl = CssSelector(".Series--Section a, a.single-serie-btn, .series-link a", "href")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".serie_eps .tabcontent a, .allepcont .row a, .EpisodesList a",
        title = CssSelector("", "title"),
        url = CssSelector("", "href"),
        episode = CssSelector("", "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector("li", "data-embed"),
        id = CssSelector("li", "id"),
        title = CssSelector("strong", "text"),
        iframe = CssSelector("li", "data-embed")
    )

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val seasonMap = mutableMapOf<String, Int>()
        for (btn in doc.select(".tablinks")) {
            val onclick = btn.attr("onclick")
            val divId = Regex("""openCity\s*\([^,]+,\s*'(\w+)'""").find(onclick)?.groupValues?.get(1)
            val sn = Regex("""الموسم (\d+)""").find(btn.text())?.groupValues?.get(1)?.toIntOrNull()
            if (divId != null && sn != null) {
                seasonMap[divId] = sn
            }
        }
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        for (tab in doc.select(".serie_eps .tabcontent")) {
            val season = seasonMap[tab.id()] ?: seasonNum ?: 1
            for (a in tab.select("a")) {
                val title = a.attr("title").ifBlank { a.text() }
                val url = a.attr("href")
                val epNum = Regex("(\\d+)").find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                episodes.add(ParserInterface.ParsedEpisode(name = title, url = url, season = season, episode = epNum))
            }
        }
        return if (episodes.isNotEmpty()) episodes else super.parseEpisodes(doc, seasonNum)
    }

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search.php?keywords=$query&video-id="
    }
}
