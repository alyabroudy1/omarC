package com.mycimaclone

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class MyCimaCloneParser : NewBaseParser() {

    override val mainPageConfig = MainPageConfig(
        container = "div.Grid--WecimaPosts div.GridItem",
        title = CssSelector("h2, strong", "text"),
        url = CssSelector("a", "href"),
        poster = CssSelector("span.BG--GridItem", "data-src, style", regex = """url\(['"]?(.*?)['"]?\)""")
    )

    override val searchConfig = mainPageConfig

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector("div.Title--Content--Single-begin h1", "text"),
        poster = CssSelector("wecima.separated--top", "data-lazy-style, style", regex = """url\(['"]?(.*?)['"]?\)"""),
        plot = CssSelector("div.StoryMovieContent", "text"),
        year = CssSelector("ul.Terms--Content--Single-begin li:contains(السنة) p", "text"),
        rating = CssSelector("", "text"),
        tags = CssSelector("", "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".EpisodesList.Full--Width a",
        title = CssSelector("episodetitle", "text"),
        url = CssSelector("a", "href"),
        episode = CssSelector("", "text")
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
}
