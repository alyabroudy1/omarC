package com.eseek

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GessehParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = "article.post, article.postEp",
        title = CssSelector(query = "div.title", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(
            query = "div.imgBg, div.imgSer, img",
            attr = "style, data-src, data-lazy-src, src",
            regex = """url\(['"]?([^'")]+)['"]?\)"""
        ),
        isMovie = { _, url, _ -> url.contains("/movies/") }
    )

    override val searchConfig = MainPageConfig(
        container = "article.post, article.postEp",
        title = CssSelector(query = "div.title", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(
            query = "div.imgBg, div.imgSer, img",
            attr = "style, data-src, data-lazy-src, src",
            regex = """url\(['"]?([^'")]+)['"]?\)"""
        ),
        isMovie = { _, url, _ -> url.contains("/movies/") }
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "div.info h1", attr = "text"),
        poster = CssSelector(
            query = "div.cover div.img, div.cover img",
            attr = "style, data-src, src",
            regex = """url\(['"]?([^'")]+)['"]?\)"""
        ),
        plot = CssSelector(query = "div.story", attr = "text"),
        seriesIndicator = CssSelector(query = "article.postEp", attr = "text"),
        parentSeriesUrl = CssSelector(query = "div.singleSeries div.info h1 a", attr = "href")
    )

    override val episodeConfig = EpisodeConfig(
        container = "article.postEp",
        title = CssSelector(query = "div.title", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        episode = CssSelector(query = "div.episodeNum span:last-child", attr = "text"),
        poster = CssSelector(
            query = "div.imgSer, img",
            attr = "style, data-src, src",
            regex = """url\(['"]?([^'")]+)['"]?\)"""
        )
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "a.fullscreen-clickable", attr = "href")
    )

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        return !url.contains("/movies/")
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        return null
    }

}
