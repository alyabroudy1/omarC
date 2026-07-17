package com.cimawbas

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class CimawbasParser : NewBaseParser() {

    override val mainPageConfig = MainPageConfig(
        container = "ul#pm-grid li",
        title = CssSelector("div.caption h3 a", "text"),
        url = CssSelector("div.caption h3 a", "href"),
        poster = CssSelector("img", "data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = "ul#pm-grid li",
        title = CssSelector("div.caption h3 a", "text"),
        url = CssSelector("div.caption h3 a", "href"),
        poster = CssSelector("img", "data-src, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector("h1.PostTitle, .single-title h1, .watch-title h1", "text"),
        poster = CssSelector(".poster img, .watch-poster img, .single-poster img", "data-src, src"),
        plot = CssSelector(".StoryArea p, .watch-description, .single-description", "text"),
        year = CssSelector(".TaxContent a[href*='release-year'], .year, .watch-year", "text"),
        rating = CssSelector(".imdbR span, .rating", "text"),
        tags = CssSelector(".TaxContent .genre a, .genres a, .Tags a", "text"),
        parentSeriesUrl = CssSelector(".Series--Section a, a.single-serie-btn, .series-link a", "href")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".allepcont .row a, .EpisodesList a, .episodes-list a, .series-episodes a",
        title = CssSelector(".ep-info h2, episodetitle, .episode-title", "text"),
        url = CssSelector("", "href"),
        episode = CssSelector(".epnum, episodetitle, .episode-num", "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector("li", "data-embed"),
        id = CssSelector("li", "id"),
        title = CssSelector("strong", "text"),
        iframe = CssSelector("li", "data-embed")
    )

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search.php?keywords=$query&video-id="
    }
}
