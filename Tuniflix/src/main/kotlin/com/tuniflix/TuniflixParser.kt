package com.lagradost.cloudstream3.plugins

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class TuniflixParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = "article.TPost.B",
        title = CssSelector(query = ".Title", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = ".Image img", attr = "data-src")
    )

    override val searchConfig = MainPageConfig(
        container = "article.TPost.B",
        title = CssSelector(query = ".Title", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = ".Image img", attr = "data-src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.Title", attr = "text"),
        poster = CssSelector(query = ".Image img.TPostBg", attr = "src"),
        plot = CssSelector(query = ".Description p", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".TPTblCn table tr",
        title = CssSelector(query = ".MvTbTtl a", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        episode = CssSelector(query = ".Num", attr = "text")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "iframe", attr = "src"),
        id = CssSelector(query = "", attr = "id"),
        title = CssSelector(query = "", attr = "text"),
        iframe = CssSelector(query = "iframe", attr = "src")
    )
}
