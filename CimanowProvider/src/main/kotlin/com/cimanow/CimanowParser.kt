package com.cimanow

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.util.Base64
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

    private fun decodeObfuscatedHtml(doc: Document): Document {
        val scriptData = doc.select("script").firstOrNull()?.data() ?: return doc
        if (!scriptData.contains("hide_my_HTML_")) {
            return doc
        }

        val hideMyHtmlContent = Regex("['+\\n\" ]")
            .replace(
                scriptData.substringAfter("var hide_my_HTML_").substring(3)
                    .substringAfter(" =").substringBeforeLast("';").trim(),
                ""
            )

        val lastNumber = Regex("-\\d+").findAll(scriptData)
            .lastOrNull()?.value?.toIntOrNull() ?: 0

        val decodedHtml1 = decodeObfuscatedString(hideMyHtmlContent, lastNumber)
        val encodedHtml = String(decodedHtml1.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        return Jsoup.parse(encodedHtml)
    }

    private fun decodeObfuscatedString(concatenated: String, lastNumber: Int): String {
        val output = StringBuilder()
        var start = 0
        val length = concatenated.length

        for (i in 0 until length) {
            if (concatenated[i] == '.') {
                val segment = concatenated.substring(start, i)
                decodeAndAppend(output, lastNumber, segment)
                start = i + 1
            }
        }
        val lastSegment = concatenated.substring(start)
        decodeAndAppend(output, lastNumber, lastSegment)
        return output.toString()
    }

    private fun decodeAndAppend(output: StringBuilder, lastNumber: Int, segment: String) {
        try {
            val decoded = String(Base64.getDecoder().decode(segment), Charsets.UTF_8)
            val digits = decoded.filter { it.isDigit() }
            if (digits.isNotEmpty()) {
                output.append((digits.toInt() + lastNumber).toChar())
            }
        } catch (e: Exception) {
            Log.e("CimanowParser", "Error decoding segment: ${e.message}")
        }
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val decodedDoc = decodeObfuscatedHtml(doc)
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()

        // Method 1: Episodes List
        var episodeLinks = decodedDoc.select("div.embed-list a, ul.embeds li a, .episodes-list a, .episode-list a")
        
        if (episodeLinks.isEmpty()) {
            // Method 2: Check for season/episode tabs
            val seasonTabs = decodedDoc.select(".seasons-list .season-tab, .season-item, .seasons .season")
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
                                    name = epTitle.ifBlank { "الحلة $epNum" },
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
                val epTitle = link.text().trim()
                
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
        
        // Fallback to parent
        return super.parseEpisodes(decodedDoc, seasonNum)
    }

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "div.embed-list a[data-src], ul.embeds li[data-src], .server-link[data-src], a[data-id]", attr = "data-src"),
        id = CssSelector(query = "div.embed-list a, ul.embeds li, .server-link", attr = "data-id"),
        title = CssSelector(query = "div.embed-list a span, ul.embeds li span, .server-link span, a", attr = "text"),
        iframe = CssSelector(query = "iframe[data-src]", attr = "data-src")
    )

    override fun extractWatchServersUrls(doc: Document): List<String> {
        val decodedDoc = decodeObfuscatedHtml(doc)
        val urls = mutableListOf<String>()
        
        // Method 1: From server list links (data-src)
        decodedDoc.select("a[data-src]").forEach { link ->
            val dataSrc = link.attr("data-src")
            if (dataSrc.isNotBlank() && (dataSrc.startsWith("http") || dataSrc.startsWith("//"))) {
                urls.add(dataSrc)
            }
        }
        
        // Method 2: From iframes (data-src)
        decodedDoc.select("iframe[data-src]").forEach { iframe ->
            val dataSrc = iframe.attr("data-src")
            if (dataSrc.isNotBlank()) {
                urls.add(dataSrc)
            }
        }
        
        // Method 3: From inline iframes
        decodedDoc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                urls.add(src)
            }
        }
        
        // Method 4: From data-id (sometimes URLs are there)
        decodedDoc.select("a[data-id]").forEach { link ->
            val dataId = link.attr("data-id")
            if (dataId.isNotBlank() && (dataId.startsWith("http") || dataId.startsWith("//"))) {
                urls.add(dataId)
            }
        }
        
        return urls.distinct()
    }

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        // Check URL patterns
        if (url.contains("/selary/") || url.contains("/series/") || url.contains("/mosalsal/") || url.contains("/season/")) {
            return true
        }
        
        // Check title keywords
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم") || title.contains("season", ignoreCase = true)) {
            return true
        }
        
        // Check for movie keyword (not series)
        if (title.contains("فيلم") || title.contains("film", ignoreCase = true) || title.contains("movie", ignoreCase = true)) {
            return false
        }
        
        // Check element context
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
        val decodedDoc = decodeObfuscatedHtml(doc)
        
        // Try to find direct play link
        val playLink = decodedDoc.selectFirst("a[href*='/watch/']")?.attr("href")
            ?: decodedDoc.selectFirst("a[href*='/player/']")?.attr("href")
            ?: decodedDoc.selectFirst("a[href*='/selary/']")?.attr("href")
            ?: decodedDoc.selectFirst(".play-button, .watch-button")?.attr("href")
        
        return playLink
    }
}
