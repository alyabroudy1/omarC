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
    
    override val mainPageConfig = MainPageConfig(
        container = "div.MovieBlock, div.postDiv, article, div.col-md-2, div.item",
        title = CssSelector(query = "h3 a, .news-title a, a[title], .entry-title a, .movie-title a, h2 a", attr = "text"),
        url = CssSelector(query = "h3 a, .news-title a, a[title], .entry-title a, .movie-title a, h2 a", attr = "href"),
        poster = CssSelector(query = "img[data-src], img.lazy, img.wp-post-image, img", attr = "data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = "div.MovieBlock, div.postDiv, article, div.col-md-2",
        title = CssSelector(query = "h1.BottomTitle, h3 a, .news-title a, a[title]", attr = "text"),
        url = CssSelector(query = "a[href]", attr = "href"),
        poster = CssSelector(query = "img[data-src], img.lazy, img.wp-post-image, img", attr = "data-src, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.BottomTitle, h1, .single-post-title, .title", attr = "text"),
        poster = CssSelector(query = "meta[property='og:image'], .poster img, img.wp-post-image", attr = "content, src"),
        plot = CssSelector(
            query = "meta[name='description'], meta[property='og:description'], .description, .story, .post-content", 
            attr = "content, text"
        ),
        seriesIndicator = CssSelector(
            query = "span.cat_name, .breadcrumb li:contains(مسلسلات), .breadcrumb li:contains(المسلسلات)", 
            attr = "text"
        ),
        parentSeriesUrl = CssSelector(query = "a[href*='/season/'], a[href*='/series/']", attr = "href"),
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.episodes-list li, ul.episodes li, div.season-episode a, .episode-item a, div.EpsList li a",
        title = CssSelector(query = "a, .episode-title, span", attr = "text, title"),
        url = CssSelector(query = "a", attr = "href"),
        episode = CssSelector(query = "a, span", attr = "text, title", regex = "(\\d+)")
    )

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()

        // Method 1: Episodes List
        var episodeLinks = doc.select("div.embed-list a, ul.embeds li a, .episodes-list a, .episode-list a, div.EpsList li a")
        
        if (episodeLinks.isEmpty()) {
            // Method 2: Check for season/episode tabs
            val seasonTabs = doc.select(".seasons-list .season-tab, .season-item, .seasons .season")
            if (seasonTabs.isNotEmpty()) {
                for (tab in seasonTabs) {
                    val tabTitle = tab.selectFirst(".season-title, h4, .season-name")?.text() ?: ""
                    val actualSeason = Regex("""(\d+)""").find(tabTitle)?.groupValues?.get(1)?.toIntOrNull() 
                        ?: seasonNum ?: 1
                        
                    tab.select("a[href*='episode'], a[href*='/selary/']").forEach { link ->
                        val epUrl = link.attr("href").trim()
                        val epTitle = link.text().trim()
                        
                        if (epUrl.isNotBlank()) {
                            val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                                ?: Regex("""(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                                ?: 0

                            episodes.add(
                                ParserInterface.ParsedEpisode(
                                    name = epTitle.ifBlank { "Episode $epNum" },
                                    url = epUrl,
                                    season = actualSeason,
                                    episode = epNum
                                )
                            )
                        }
                    }
                }
                return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
            }
        }
        
        // Method 1: Direct episode links
        if (episodeLinks.isNotEmpty()) {
            episodeLinks.forEach { link ->
                val epUrl = link.attr("href").trim()
                val epTitle = link.attr("title").trim().ifBlank { link.text().trim() }
                
                if (epUrl.isNotBlank()) {
                    val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                        ?: Regex("""(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0

                    episodes.add(
                        ParserInterface.ParsedEpisode(
                            name = epTitle,
                            url = epUrl,
                            season = seasonNum ?: 1,
                            episode = epNum
                        )
                    )
                }
            }
            return episodes.sortedBy { it.episode }
        }
        
        return super.parseEpisodes(doc, seasonNum)
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

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (url.contains("/selary/") || url.contains("/series/") || url.contains("/mosalsal/") || url.contains("/season/")) {
            return true
        }
        
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم") || title.contains("season", ignoreCase = true)) {
            return true
        }
        
        if (title.contains("فيلم") || title.contains("film", ignoreCase = true) || title.contains("movie", ignoreCase = true)) {
            return false
        }
        
        if (element != null) {
            val parentText = element.parent()?.text() ?: ""
            val grandparentText = element.parent()?.parent()?.text() ?: ""
            if (parentText.contains("مسلسلات") || parentText.contains("المسلسلات") || 
                grandparentText.contains("مسلسلات") || grandparentText.contains("المسلسلات")) {
                return true
            }
        }
        
        return false
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        val playLink = doc.selectFirst("a[href*='/watch/']")?.attr("href")
            ?: doc.selectFirst("a[href*='/player/']")?.attr("href")
            ?: doc.selectFirst("a[href*='/selary/']")?.attr("href")
            ?: doc.selectFirst(".play-button, .watch-button")?.attr("href")
        
        return playLink
    }
}
