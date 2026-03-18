package com.cimawbas

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class CimawbasParser : NewBaseParser() {

    override val mainPageConfig = MainPageConfig(
        container = "li.Small--Box",
        title = CssSelector(".title", "text"),
        url = CssSelector("a", "href"),
        poster = CssSelector(".Poster img", "data-src, src")
    )

    override val searchConfig = mainPageConfig

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector("h1.PostTitle", "text"),
        poster = CssSelector(".left .image img", "data-src, src"),
        plot = CssSelector(".StoryArea p", "text"),
        year = CssSelector(".TaxContent a[href*='release-year']", "text"),
        rating = CssSelector(".imdbR span", "text"),
        tags = CssSelector(".TaxContent .genre a", "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".allepcont .row a",
        title = CssSelector(".ep-info h2", "text"),
        url = CssSelector("a", "href"),
        episode = CssSelector(".epnum", "text", "([^0-9])")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector("", ""),
        id = CssSelector("", ""),
        title = CssSelector("", ""),
        iframe = CssSelector("", "")
    )

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query&page=1"
    }
}
