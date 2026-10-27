package com.egydead

import com.lagradost.api.Log
import com.lagradost.cloudstream3.TvType
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
     * Collection pages (/assembly/...) list movies instead of carrying a player.
     * Expose the contained movies as episodes so the collection is browsable
     * and every entry resolves through the normal movie loadLinks flow.
     */
    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        if (url.contains("/assembly/")) {
            val title = doc.selectFirst("div.singleTitle em")?.text()?.trim()
                ?: doc.selectFirst("div.singleTitle")?.text()?.trim()
                ?: doc.title()?.trim()
                ?: return null
            val poster = doc.selectFirst("div.single-thumbnail > img")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: ""
            val plot = doc.selectFirst("div.extra-content:contains(القصه) p")?.text()?.trim()
            val episodes = doc.select("li.movieItem").mapIndexedNotNull { index, li ->
                val a = li.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                val epUrl = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                if (epUrl.isBlank()) return@mapIndexedNotNull null
                val epTitle = li.selectFirst("h1.BottomTitle")?.text()?.trim()
                    ?: a.attr("title").ifBlank { a.text().trim() }.trim()
                ParserInterface.ParsedEpisode(
                    name = epTitle.ifBlank { "Part ${index + 1}" },
                    url = epUrl,
                    season = 1,
                    episode = index + 1
                )
            }
            return ParserInterface.ParsedLoadData(
                title = title,
                plot = plot,
                posterUrl = poster,
                url = url,
                type = TvType.TvSeries,
                year = null,
                episodes = episodes,
                tags = emptyList()
            )
        }
        return super.parseLoadPage(doc, url)
    }

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
        for (link in doc.select("a[data-src]")) {
            val dataSrc = link.attr("data-src")
            if (dataSrc.isNotBlank() && (dataSrc.startsWith("http") || dataSrc.startsWith("//"))) {
                if (!isTrailerOrJunk(dataSrc)) urls.add(dataSrc)
            }
        }
        
        // Method 2: From iframes (data-src)
        for (iframe in doc.select("iframe[data-src]")) {
            val dataSrc = iframe.attr("data-src")
            if (dataSrc.isNotBlank()) {
                if (!isTrailerOrJunk(dataSrc) && !isHiddenIframe(iframe)) urls.add(dataSrc)
            }
        }
        
        // Method 3: From inline iframes (skips the youtube trailer popup and
        // hidden tracking pixels, which otherwise open a trailer-only webview)
        for (iframe in doc.select("iframe[src]")) {
            val src = iframe.attr("src")
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                if (!isTrailerOrJunk(src) && !isHiddenIframe(iframe)) urls.add(src)
            }
        }
        
        // Method 4: From data-id
        for (link in doc.select("a[data-id]")) {
            val dataId = link.attr("data-id")
            if (dataId.isNotBlank() && (dataId.startsWith("http") || dataId.startsWith("//"))) {
                if (!isTrailerOrJunk(dataId)) urls.add(dataId)
            }
        }
        
        return urls.distinct()
    }

    private fun isTrailerOrJunk(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private fun isHiddenIframe(iframe: Element): Boolean {
        val style = iframe.attr("style").lowercase()
        if (style.contains("display:none") || style.contains("display: none")) return true
        if (style.contains("width: 0") || style.contains("width:0")) return true
        if (style.contains("height: 0") || style.contains("height:0")) return true
        if (style.contains("-1000px") || style.contains("left:-1000")) return true
        if (iframe.attr("width") == "0" || iframe.attr("height") == "0") return true
        return false
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

