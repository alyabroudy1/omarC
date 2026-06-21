package com.dt

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DimaToonParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = "div#cartoon-list div.cartoon-item a, div#cartoon-episodes-container div.episode-card a",
        title = CssSelector(query = "p, .episode-title", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    override val searchConfig = MainPageConfig(
        container = "div#cartoon-list div.cartoon-item a, div#cartoon-episodes-container div.episode-card a",
        title = CssSelector(query = "p, .episode-title", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.anime-title, h1.xpro-post-title", attr = "text"),
        poster = CssSelector(query = "div.cartoon-image img, div.elementor-element-e7ee95b img", attr = "src"),
        plot = CssSelector(query = "div.brief-story p, div.elementor-element-024e1d8", attr = "text"),
        seriesIndicator = CssSelector(query = "div.episodes-grid", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.episodes-grid div.episode-box a",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text", regex = """\s(\d+)$""")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "video.easy-video-player > source", attr = "src")
    )

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        return !url.contains("/cartoon-episode/")
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        return null
    }
}
