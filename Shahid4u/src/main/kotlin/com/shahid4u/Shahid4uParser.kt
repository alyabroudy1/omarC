package com.shahid4u

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class Shahid4uParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = "div.shows-container.row div[class*=col-]",
        title = CssSelector(query = "p.title, div.card-content", attr = "text"),
        url = CssSelector(query = "a.show.card", attr = "href"),
        poster = CssSelector(query = "a.show.card", attr = "style", regex = """url\(['"]?(.*?)['"]?\)""")
    )

    override val searchConfig = MainPageConfig(
        container = "div.shows-container.row div[class*=col-]",
        title = CssSelector(query = "p.title, div.card-content", attr = "text"),
        url = CssSelector(query = "a.show.card", attr = "href"),
        poster = CssSelector(query = "a.show.card", attr = "style", regex = """url\(['"]?(.*?)['"]?\)""")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "span.title", attr = "text"),
        poster = CssSelector(query = "div.poster-side img", attr = "src"),
        plot = CssSelector(query = "span.description", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.w-100.bg-main.rounded.my-4 a.epss",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text", regex = """(\d+)""")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "", attr = "src"),
        id = CssSelector(query = "", attr = "id"),
        title = CssSelector(query = "", attr = "text"),
        iframe = CssSelector(query = "", attr = "src")
    )
}
