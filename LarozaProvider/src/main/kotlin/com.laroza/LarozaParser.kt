package com.laroza

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element


class LarozaParser : NewBaseParser() {
    
    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search.php?keywords=$query"
    }
    
    override val mainPageConfig = MainPageConfig(
        container = "div.col-md-3 div.thumbnail, div.col-sm-4 div.thumbnail, div.thumbnail",
        // Container IS the item in this loop structure
        title = CssSelector(query = ".caption h3 a, h3 a", attr = "text"),
        url = CssSelector(query = ".caption h3 a, h3 a", attr = "href"),
        poster = CssSelector(query = ".thumbnail.a img, .pm-video-thumb img, img", attr = "data-echo, src")
    )

    override val searchConfig = MainPageConfig(
        container = "ul.pm-ul-browse-videos li",
        title = CssSelector(query = "h3 a", attr = "text"),
        url = CssSelector(query = "h3 a", attr = "href"),
        poster = CssSelector(query = ".pm-video-thumb img", attr = "src, data-echo")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1", attr = "text"),
        poster = CssSelector(query = "meta[property='og:image']", attr = "content"),
        plot = CssSelector(
            query = "div#video-description, meta[name='description'], meta[property='og:description']", 
            attr = "content, text" // Will try content attribute first (for meta), then text (for div)
        ),
        seriesIndicator = CssSelector(query = ".breadcrumb li:contains(مسلسلات)", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "ul.pm-ul-browse-videos li",
        title = CssSelector(query = "h3 a", attr = "text"),
        url = CssSelector(query = "h3 a", attr = "href"),
        episode = CssSelector(query = "h3 a", attr = "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "ul.WatchList li[data-embed-url]", attr = "data-embed-url"),
        id = CssSelector(query = "ul.WatchList li[data-embed-url]", attr = "data-embed-id"),
        title = CssSelector(query = "ul.WatchList li[data-embed-url].strong", attr = "text"),
        iframe = CssSelector(query = ".brooks_player iframe", attr = "src")
    )

    // Unified Series Detection Logic
    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        // 0. Explicit Movie Checks (Fail-fast)
        if (title.contains("فيلم") || title.contains("film", true) || title.contains("movie", true)) return false

        // 1. Element Context Checks (Priority)
        if (element != null) {
            if (element is Document) {
                // Parse Load Page Context
                // Check if episodes exist
                if (element.select(episodeConfig.container).isNotEmpty()) return true
                
                // Check Breadcrumb indicator
                if (element.extract(loadPageConfig.seriesIndicator) != null) return true
            } else {
                // Parse Item Context (Main Page/Search)
                // Check category tag if available
                val categoryText = element.select("span.label").text()
                if (categoryText.contains("مسلسلات")) return true
            }
        }
        
        // 2. Keyword check (Fallback)
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        if (url.contains("series") || url.contains("ramadan")) return true
        
        return false
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        // Look for play page link - class name changes randomly, use multiple selectors
        val playLink = doc.selectFirst("a[href*='play.php?vid=']")?.attr("href")
            ?: doc.selectFirst("#BiBplayer a[href*='play.php']")?.attr("href")
            ?: doc.selectFirst("#video-wrapper a[href*='play.php']")?.attr("href")

        Log.d("LarozaParser", "getPlayerPageUrl: found='$playLink'")
        return playLink
    }
}