package com.animerco

import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document

class AnimercoParser : NewBaseParser() {
    override val mainPageConfig = MainPageConfig(
        container = "div.featured-slider div.anime-card",
        title = CssSelector(query = "div.info a h3", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "a.image", attr = "data-src")
    )
    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "div.media-title h1", attr = "text"),
        poster = CssSelector(query = "div.anime-card div.image", attr = "data-src"),
        plot = CssSelector(query = "div.media-story div.content p", attr = "text")
    )
    override val episodeConfig = EpisodeConfig(
        container = "ul.episodes-lists#filter li",
        title = CssSelector(query = "a.title h3", attr = "text"),
        url = CssSelector(query = "a.title", attr = "href"),
        episode = CssSelector(query = "", attr = "data-number", regex = "(\\d+)")
    )
    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "ul.server-list li a.option span.server", attr = "text")
    )
}
