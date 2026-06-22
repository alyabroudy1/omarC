package com.topcinema

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class TopCinemaParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search/?query=$query&type=all"
    }

    override val mainPageConfig = MainPageConfig(
        container = "section.Two--Items",
        title = CssSelector(query = ".Title--Box h3", attr = "text"),
        url = CssSelector(query = ".Small--Box a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src")
    )

    override val searchConfig = MainPageConfig(
        container = ".Posts--List .Small--Box",
        title = CssSelector(query = "a", attr = "title"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.post-title a", attr = "text"),
        poster = CssSelector(query = ".MainSingle .left .image img", attr = "src"),
        plot = CssSelector(query = ".story p", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".allepcont .row > a",
        title = CssSelector(query = "h2", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = ".epnum", attr = "text", regex = """الحلقة\s*(\d+)""")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = ".player--iframe iframe", attr = "src"),
        id = CssSelector(query = ".watch--servers--list li.server--item", attr = "data-id"),
        title = CssSelector(query = ".watch--servers--list li.server--item", attr = "text"),
        iframe = CssSelector(query = "iframe", attr = "src")
    )
}
