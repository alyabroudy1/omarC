package com.cimanow

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class CimaNowParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = "article[aria-label='post'], article, div.MovieBlock, div.item, figure, div.col-md-2.col-xs-6",
        title = CssSelector(query = "li[aria-label='title'], h3 a, .news-title a, a[href*='/movie/'], a[href*='/selary/'], a[title]", attr = "text"),
        url = CssSelector(query = "article a, h3 a, .news-title a, a[href*='/movie/'], a[href*='/selary/']", attr = "href"),
        poster = CssSelector(query = "img[data-src], img.lazy, img", attr = "data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = "article[aria-label='post'], article, div.search-page div.item, div.MovieBlock, figure.search-page-item, div.col-md-2",
        title = CssSelector(query = "li[aria-label='title'], h3 a, .news-title a, a[title]", attr = "text"),
        url = CssSelector(query = "article a, h3 a, .news-title a, a[title]", attr = "href"),
        poster = CssSelector(query = "img[data-src], img.lazy, img", attr = "data-src, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1, .single-post-title, .title", attr = "text"),
        poster = CssSelector(query = "meta[property='og:image'], .poster img", attr = "content, src"),
        plot = CssSelector(
            query = "meta[name='description'], meta[property='og:description'], .description, .story, .post-content", 
            attr = "content, text"
        ),
        seriesIndicator = CssSelector(
            query = ".breadcrumb li:contains(مسلسلات), .breadcrumb li:contains(المسلسلات), .breadcrumb li:contains(السي сезон), .term-name", 
            attr = "text"
        ),
        parentSeriesUrl = CssSelector(query = "a[href*='/season/'], a[href*='/series/'], a[href*='/selary/']", attr = "href"),
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.episodes-list li, ul.episodes li, div.season-episode a, .episode-item a, div.episode a",
        title = CssSelector(query = "a, .episode-title, span", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        episode = CssSelector(query = "a, span", attr = "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "div.embed-list a[data-src], ul.embeds li[data-src], .server-link[data-src], a[data-id]", attr = "data-src"),
        id = CssSelector(query = "div.embed-list a, ul.embeds li, .server-link", attr = "data-id"),
        title = CssSelector(query = "div.embed-list a span, ul.embeds li span, .server-link span, a", attr = "text"),
        iframe = CssSelector(query = "iframe[data-src]", attr = "data-src")
    )
}
