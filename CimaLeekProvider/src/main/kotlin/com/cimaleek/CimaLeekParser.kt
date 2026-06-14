package com.cimaleek

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Element
import com.cloudstream.shared.parsing.ParserInterface

class CimaLeekParser : NewBaseParser() {

    override val mainPageConfig = MainPageConfig(
        container = ".posts_items .item, .swiper-slide .item, .item",
        title = CssSelector(".title", "text"),
        url = CssSelector("a.film-poster, a", "href"),
        poster = CssSelector("img.lazy, img", "data-src, src"),
        isMovie = { title, url, _ ->
            url.contains("/movies/") || title.contains("فيلم")
        }
    )

    override val searchConfig = mainPageConfig

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector("h1.ps_-name, h2.film-name, .title", "text"),
        plot = CssSelector(".film-description .text, .film-description, .story, .plot", "text"),
        poster = CssSelector(".film-poster img, .poster img", "src, data-src"),
        year = CssSelector(".inside-poster .year a, .inside-poster .year", "text"),
        parentSeriesUrl = CssSelector("a#detail, .right-side a", "href")
    )

    override val episodeConfig = EpisodeConfig(
        container = "ul.episodios li.episodesList a, .episodios a, .episodesList a",
        title = CssSelector("a", "title"),
        url = CssSelector("a", "href")
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
