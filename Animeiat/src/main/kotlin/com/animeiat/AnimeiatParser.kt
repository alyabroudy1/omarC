package com.animeiat

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class AnimeiatParser : NewBaseParser() {

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
