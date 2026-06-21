package com.cimatn

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CimaTnParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search?q=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = "#holder a.itempost",
        title = CssSelector(query = "#item-name", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    override val searchConfig = MainPageConfig(
        container = "#holder a.itempost",
        title = CssSelector(query = "#item-name", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.PostTitle", attr = "text"),
        poster = CssSelector(query = "#poster img", attr = "src"),
        plot = CssSelector(query = ".StoryArea, .SingleContent", attr = "text"),
        tags = CssSelector(query = ".RightTaxContent a", attr = "text"),
        year = CssSelector(query = ".RightTaxContent .fa-calendar", attr = "text", regex = "(\\d{4})"),
        seriesIndicator = CssSelector(query = "section.allepcont, section.allseasonss", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".allepcont .row a, section.allepcont .row a",
        title = CssSelector(query = "h2", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "video source", attr = "src"),
        iframe = CssSelector(query = "iframe", attr = "src")
    )

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        return url.contains("/series/") || url.contains("/مسلسل-") ||
                (!url.contains("film-") && !url.contains("/p/"))
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        return null
    }
}
