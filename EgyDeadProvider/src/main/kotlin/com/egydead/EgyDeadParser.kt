package com.egydead

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.text.contains


class EgyDeadParser : NewBaseParser() {
    
    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }
    
    // Reference: li.movieItem container, h1.BottomTitle title, img src, a href
    // Same selectors for both main page and search
    override val mainPageConfig = MainPageConfig(
        container = "li.movieItem",
        title = CssSelector(query = "h1.BottomTitle", attr = "text"),
        url = CssSelector(query = "a[href]", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    // Search uses exact same selectors as main page (from reference)
    override val searchConfig = MainPageConfig(
        container = "li.movieItem",
        title = CssSelector(query = "h1.BottomTitle", attr = "text"),
        url = CssSelector(query = "a[href]", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    // Reference: div.singleTitle em for title, div.single-thumbnail > img for poster
    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "div.singleTitle em", attr = "text"),
        poster = CssSelector(query = "div.single-thumbnail > img", attr = "src"),
        plot = CssSelector(
            query = "div.extra-content:contains(القصه) p", 
            attr = "text"
        ),
        // Not used for type detection - we override isSeries instead
        seriesIndicator = null,
        parentSeriesUrl = null,
    )

    // Reference: div.EpsList > li > a for episodes
    override val episodeConfig = EpisodeConfig(
        container = "div.EpsList > li > a",
        title = CssSelector(query = "", attr = "title"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text", regex = "(\\d+)")
    )

    /**
     * Parse episodes from a document.
     * Reference: div.EpsList > li > a, with href and title attrs
     */
    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()

        val episodeLinks = doc.select("div.EpsList > li > a")
        Log.d("EgyDeadParser", "parseEpisodes: found ${episodeLinks.size} episode links (season=$seasonNum)")
        
        for (link in episodeLinks) {
            val epUrl = link.attr("href").trim()
            val epTitle = link.attr("title").trim().ifBlank { link.text().trim() }
            
            if (epUrl.isNotBlank()) {
                val epNum = Regex("""(\d+)""").find(link.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0

                episodes.add(
                    ParserInterface.ParsedEpisode(
                        name = epTitle.ifBlank { "Episode $epNum" },
                        url = epUrl,
                        season = seasonNum ?: 1,
                        episode = epNum
                    )
                )
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "div.embed-list a[data-src], ul.embeds li[data-src], .server-link[data-src], a[data-id]", attr = "data-src"),
        id = CssSelector(query = "div.embed-list a, ul.embeds li, .server-link", attr = "data-id"),
        title = CssSelector(query = "div.embed-list a span, ul.embeds li span, .server-link span, a", attr = "text"),
        iframe = CssSelector(query = "iframe[data-src]", attr = "data-src")
    )

    override fun extractWatchServersUrls(doc: Document): List<String> {
        val urls = mutableListOf<String>()
        
        // Method 1: From server list links (data-src)
        doc.select("a[data-src]").forEach { link ->
            val dataSrc = link.attr("data-src")
            if (dataSrc.isNotBlank() && (dataSrc.startsWith("http") || dataSrc.startsWith("//"))) {
                urls.add(dataSrc)
            }
        }
        
        // Method 2: From iframes (data-src)
        doc.select("iframe[data-src]").forEach { iframe ->
            val dataSrc = iframe.attr("data-src")
            if (dataSrc.isNotBlank()) {
                urls.add(dataSrc)
            }
        }
        
        // Method 3: From inline iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                urls.add(src)
            }
        }
        
        // Method 4: From data-id
        doc.select("a[data-id]").forEach { link ->
            val dataId = link.attr("data-id")
            if (dataId.isNotBlank() && (dataId.startsWith("http") || dataId.startsWith("//"))) {
                urls.add(dataId)
            }
        }
        
        return urls.distinct()
    }

    /**
     * Type detection matching decompiled reference exactly:
     * isMovie = !Regex("/serie/|/season/").containsMatchIn(url)
     * 
     * So: if URL contains /serie/ or /season/ → it's a series (NOT a movie)
     * Everything else is a movie.
     */
    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        // Reference: isMovie = !Regex("/serie/|/season/").containsMatchIn(url)
        // So isSeries when URL matches /serie/ or /season/
        if (Regex("/serie/|/season/").containsMatchIn(url)) {
            return true
        }
        return false
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        // EgyDead doesn't use a separate player page - loadLinks POSTs directly
        return null
    }
    
    /**
     * Override parseSearch to filter out individual episode URLs
     * Reference: search filters out results where a[href] contains "/episode/"
     * Safety: if filtering removes ALL results, return unfiltered so user sees something
     */
    override fun parseSearch(doc: Document): List<ParserInterface.ParsedItem> {
        val items = super.parseSearch(doc)
        val filtered = items.filter { !it.url.contains("/episode/") }
        return if (filtered.isNotEmpty()) filtered else items
    }
}

