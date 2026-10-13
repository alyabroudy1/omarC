package com.wecima

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

import org.jsoup.nodes.Element
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.logging.ProviderLogger

class WecimaParser : NewBaseParser() {

    // Strategy layers (newest first, legacy kept as fallback — the site
    // rotates markup often, e.g. div.GridItem -> div.Wecima-Card in 2026).
    // Jsoup comma-selectors union, so all generations parse simultaneously.
    override val mainPageConfig = MainPageConfig(
        container = "div.Wecima-Card, div.GridItem",
        title = CssSelector("strong.Wecima-Card__title, h2, strong", "text"),
        url = CssSelector("a.Wecima-Card__link, a.film-poster, a", "abs:href, href"),
        poster = CssSelector(
            "span.Wecima-Card__img, meta[itemprop=thumbnailUrl], span.BG--GridItem",
            "style, content, data-src, data-lazy-style",
            // Greedy prefix: some cards list a placeholder first
            // (--image) and the real poster second (background-image).
            regex = """.*url\(['"]?(.*?)['"]?\)"""
        )
    )

    override fun parseItem(element: Element, config: com.cloudstream.shared.parsing.MainPageConfig): ParserInterface.ParsedItem? {
        val item = super.parseItem(element, config)
        if (item != null) {
            val pUrl = item.posterUrl ?: ""
            if (pUrl.isBlank() || pUrl.contains("wecima.webp") || pUrl.contains("placeholder")) {
                ProviderLogger.e("WecimaParser", "parseItem", "Failed to extract valid poster. Parsed URL: '$pUrl' | Container HTML Dump:\n${element.outerHtml()}")
            }
        }
        return item
    }

    override val searchConfig = mainPageConfig

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector("div.Title--Content--Single-begin h1", "text"),
        poster = CssSelector("wecima.separated--top", "data-lazy-style, style", regex = """url\(['"]?(.*?)['"]?\)"""),
        plot = CssSelector("div.StoryMovieContent", "text"),
        year = CssSelector("ul.Terms--Content--Single-begin li:contains(السنة) p", "text"),
        rating = CssSelector("", "text"),
        tags = CssSelector("", "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".EpisodesList.Full--Width a",
        title = CssSelector("episodetitle", "text"),
        url = CssSelector("a", "href"),
        episode = CssSelector("", "text")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector("", ""),
        id = CssSelector("", ""),
        title = CssSelector("", ""),
        iframe = CssSelector("", "")
    )

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }
}
