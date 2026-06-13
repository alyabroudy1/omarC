package com.kooralive

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document

class KooraLiveParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = ".AF_Match.AF_EvItem",
        title = CssSelector(query = ".AF_TeamName", attr = "text"),
        url = CssSelector(query = "a.AF_EventMask", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    override val searchConfig = MainPageConfig(
        container = ".AF_Match.AF_EvItem",
        title = CssSelector(query = ".AF_TeamName", attr = "text"),
        url = CssSelector(query = "a.AF_EventMask", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "meta[property='og:title']", attr = "content"),
        poster = CssSelector(query = "meta[property='og:image']", attr = "content"),
        plot = CssSelector(query = "meta[property='og:description']", attr = "content")
    )

    override val episodeConfig = EpisodeConfig(
        container = "",
        title = CssSelector(query = "", attr = ""),
        url = CssSelector(query = "", attr = "")
    )
    
    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "iframe", attr = "src"),
        id = CssSelector(query = "iframe", attr = "src"),
        title = CssSelector(query = "iframe", attr = "title"),
        iframe = CssSelector(query = "iframe", attr = "src")
    )
}
