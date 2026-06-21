package com.asia2tv

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Asia2TvParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search?category=series&s=$query&show=free&"
    }

    override val mainPageConfig = MainPageConfig(
        container = "div.home-tvsries",
        item = "div.postmovie",
        title = CssSelector(query = "h4 a", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "div.postmovie-thumb-bg img, div.postmovie a img", attr = "data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = ".row-movies .col-lg-3, .row-movies .col-md-4",
        title = CssSelector(query = "h4 a", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "img, div.postmovie-thumb-bg img", attr = "data-src, src"),
        isMovie = { _, url, _ -> url.contains("/movie/") }
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.mb-0", attr = "text"),
        poster = CssSelector(query = "div.single-thumb-bg img, meta[property=og:image]", attr = "data-src, content"),
        plot = CssSelector(query = "p.mb-3, meta[name=description]", attr = "text, content"),
        tags = CssSelector(query = "div.post_tags a", attr = "text"),
        year = CssSelector(query = "ul.mb-2 li:contains(سنة العرض) a", attr = "text", regex = "(\\d{4})"),
        movieIndicator = CssSelector(query = "meta[property*=movie]", attr = "content"),
        seriesIndicator = CssSelector(query = "div.loop-episode", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.loop-episode a",
        title = CssSelector(query = "div.titlepisode", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "div.titlepisode", attr = "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "li.getplay a", attr = "data-code"),
        iframe = CssSelector(query = "iframe", attr = "src")
    )

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (url.contains("/movie/")) return false
        return true
    }

    override fun getPlayerPageUrl(doc: Document): String? = null
}
