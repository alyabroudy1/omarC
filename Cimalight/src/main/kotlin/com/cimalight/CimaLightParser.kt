package com.cimalight

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class CimaLightParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search.php?keywords=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = "div.pm-section",
        title = CssSelector(query = "h3 a, div.title", attr = "text"),
        url = CssSelector(query = "a:not(.pm-watch-later-add)", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    override val searchConfig = MainPageConfig(
        container = "ul#pm-grid li",
        title = CssSelector(query = "h3 a, div.title", attr = "text"),
        url = CssSelector(query = "a:not(.pm-watch-later-add)", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1[itemprop=name]", attr = "text"),
        poster = CssSelector(query = "div.video-bibplayer-poster", attr = "style", regex = """url\(['"]?(.*?)['"]?\)"""),
        plot = CssSelector(query = "div[itemprop=description] p", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.SeasonsEpisodesMain div.tabcontent ul a",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "a.xtgo", attr = "href"),
        id = CssSelector(query = "", attr = "data-embed"),
        title = CssSelector(query = "", attr = "text"),
        iframe = CssSelector(query = "", attr = "src")
    )
}
