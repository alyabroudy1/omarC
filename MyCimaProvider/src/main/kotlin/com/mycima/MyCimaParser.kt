package com.mycima

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class MyCimaParser : NewBaseParser() {

    override val mainPageConfig = MainPageConfig(
        container = "div.Grid--WecimaPosts div.GridItem",
        title = CssSelector("h2, strong", "text"),
        url = CssSelector("a", "href"),
        poster = CssSelector("span.BG--GridItem", "data-lazy-style, data-src, style", regex = """url\(['"]?(.*?)['"]?\)""")
    )

    override val searchConfig = mainPageConfig

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector("div.Title--Content--Single-begin h1", "text"),
        poster = CssSelector("wecima.separated--top", "data-lazy-style, style", regex = """url\(['"]?(.*?)['"]?\)"""),
        plot = CssSelector("div.StoryMovieContent", "text"),
        year = CssSelector("ul.Terms--Content--Single-begin li:contains(السنة) p", "text"),
        rating = CssSelector("", "text"),
        tags = CssSelector("", "text"),
        parentSeriesUrl = CssSelector(".Series--Section a", "href")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".EpisodesList a",
        title = CssSelector("episodetitle", "text"),
        url = CssSelector("", "href"),
        episode = CssSelector("episodetitle", "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector("", ""),
        id = CssSelector("", ""),
        title = CssSelector("", ""),
        iframe = CssSelector("", "")
    )

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search"
    }

    override fun isSeries(title: String, url: String, element: org.jsoup.nodes.Element?): Boolean {
        if (title.contains("فيلم") || title.contains("film", true) || title.contains("movie", true)) return false
        if (element != null) {
            if (element is org.jsoup.nodes.Document) {
                if (element.select(episodeConfig.container).isNotEmpty()) return true
                if (element.extract(loadPageConfig.seriesIndicator) != null) return true
                if (element.select(".Series--Section").isNotEmpty()) return true
                if (element.select(".SeasonsList").isNotEmpty()) return true
            }
        }
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        if (url.contains("series")) return true
        if (url.contains("مسلسل")) return true
        if (url.contains("%d9%85%d8%b3%d9%84%d8%b3%d9%84", ignoreCase=true)) return true
        return false
    }
}
