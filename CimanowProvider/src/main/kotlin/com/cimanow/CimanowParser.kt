package com.cimanow

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import kotlin.text.Regex
import kotlin.text.contains
import kotlin.text.substringAfter
import kotlin.text.substringBeforeLast


class CimanowParser : NewBaseParser() {
    
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

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        // Cimanow evaluates obfuscated episodes asynchronously via WebView in Cimanow.kt fetchExtraEpisodes.
        // We return empty here to bypass synchronous extraction limits.
        return emptyList()
    }


    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "div.embed-list a[data-src], ul.embeds li[data-src], .server-link[data-src], a[data-id]", attr = "data-src"),
        id = CssSelector(query = "div.embed-list a, ul.embeds li, .server-link", attr = "data-id"),
        title = CssSelector(query = "div.embed-list a span, ul.embeds li span, .server-link span, a", attr = "text"),
        iframe = CssSelector(query = "iframe[data-src]", attr = "data-src")
    )

    override fun extractWatchServersUrls(doc: Document): List<String> {
        // Cimanow evaluates obfuscated server tags completely asynchronously inside Cimanow.kt loadLinks.
        return emptyList()
    }

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        // URL-decode for Arabic pattern matching (URLs may contain encoded Arabic)
        val decodedUrl = try { java.net.URLDecoder.decode(url, "UTF-8") } catch (e: Exception) { url }
        
        // Check URL patterns (matching original Braflix: /selary/ is the main series path)
        if (decodedUrl.contains("/selary/") || decodedUrl.contains("مسلسل") || 
            url.contains("/series/") || url.contains("/mosalsal/") || url.contains("/season/")) {
            return true
        }
        
        // Check title keywords
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم") || title.contains("season", ignoreCase = true)) {
            return true
        }
        
        // Check for movie keyword (not series)
        if (title.contains("فيلم") || decodedUrl.contains("فيلم") ||
            title.contains("film", ignoreCase = true) || title.contains("movie", ignoreCase = true)) {
            return false
        }
        
        // Original Braflix uses ul.info li[aria-label=tab] text for series detection
        // Check this on the element itself (for main page article elements)
        if (element != null) {
            val tabText = element.select("ul.info li[aria-label=tab]").text()
            if (tabText.contains("مسلسلات") || tabText.contains("موسم")) {
                return true
            }
            // Also check parent/grandparent context
            val parentText = element.parent()?.text() ?: ""
            val grandparentText = element.parent()?.parent()?.text() ?: ""
            if (parentText.contains("مسلسلات") || parentText.contains("المسلسلات") || 
                grandparentText.contains("مسلسلات") || grandparentText.contains("المسلسلات")) {
                return true
            }
        }
        
        // For Document (load page), check page title like the original: doc.title().contains("فيلم")
        if (element is Document) {
            val pageTitle = element.title()
            if (pageTitle.contains("فيلم")) return false  // It's a movie
            if (pageTitle.contains("مسلسل") || pageTitle.contains("موسم")) return true
        }
        
        return false
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        // In the original Braflix, the watch URL is constructed as data+"watching/" directly in loadLinks.
        // The Cimanow.loadLinks() override handles this, so we return null here.
        // The BaseProvider.loadLinks() fallback won't need this since the custom loadLinks runs first.
        return null
    }
}
