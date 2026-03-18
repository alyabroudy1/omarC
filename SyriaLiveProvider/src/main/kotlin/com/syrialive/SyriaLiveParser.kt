package com.syrialive

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document

class SyriaLiveParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = ".AY-PItem",
        title = CssSelector(query = ".AY-PostTitle a", attr = "text"),
        url = CssSelector(query = ".AY-PostTitle a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = ".AY-PItem",
        title = CssSelector(query = ".AY-PostTitle a", attr = "text"),
        url = CssSelector(query = ".AY-PostTitle a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = ".EntryTitle", attr = "text"),
        poster = CssSelector(query = "meta[property='og:image'], .teamlogo", attr = "content, data-src, src"),
        plot = CssSelector(query = ".entry-content p", attr = "text")
    )

    // Unused but required by NewBaseParser architecture
    override val episodeConfig = EpisodeConfig(
        container = "",
        title = CssSelector(query = "", attr = ""),
        url = CssSelector(query = "", attr = "")
    )
    
    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = ".video-serv a", attr = "href"),
        id = CssSelector(query = ".video-serv a", attr = "href"), // No unique ID available
        title = CssSelector(query = ".video-serv a", attr = "text"),
        iframe = CssSelector(query = ".entry-content iframe", attr = "src")
    )
}
