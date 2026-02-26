package com.iptv

import com.cloudstream.shared.parsing.*

class IPTVParser : NewBaseParser() {
    
    override fun getSearchUrl(domain: String, query: String): String {
        return ""
    }
    
    override val mainPageConfig = MainPageConfig(
        container = "",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        poster = CssSelector(query = "", attr = "src")
    )

    override val searchConfig = MainPageConfig(
        container = "",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        poster = CssSelector(query = "", attr = "src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "", attr = "text"),
        poster = CssSelector(query = "", attr = "src"),
        plot = CssSelector(query = "", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "", attr = "src"),
        id = CssSelector(query = "", attr = "id"),
        title = CssSelector(query = "", attr = "text"),
        iframe = CssSelector(query = "", attr = "src")
    )
}
