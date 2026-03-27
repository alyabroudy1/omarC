package com.cimanow

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import kotlin.text.Regex
import kotlin.text.contains
import kotlin.text.substringAfter
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
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
        try {
            val html = doc.outerHtml()
            val scriptMatch = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
                ?: return doc
            
            val jsCode = scriptMatch.groupValues[1]
            if (!jsCode.contains("_0x")) return doc

            val atobPolyfill = """
                var document = {
                    written: "",
                    open: function() {},
                    write: function(str) { this.written += str; },
                    close: function() {}
                };
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                function atob(input) {
                    var str = String(input).replace(/=+${'$'}/, '');
                    var output = '';
                    for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                        buffer = chars.indexOf(buffer);
                    }
                    return output;
                }
            """.trimIndent()

            val scriptToRun = atobPolyfill + "\n" + jsCode
            
            val rhino = Context.enter()
            val result = try {
                rhino.optimizationLevel = -1
                val scope = rhino.initSafeStandardObjects()
                scope.put("window", scope, scope)
                rhino.evaluateString(scope, scriptToRun, "JavaScript", 1, null)
                val documentObj = scope.get("document", scope) as NativeObject
                documentObj.get("written", documentObj).toString()
            } finally {
                Context.exit()
            }

            if (result.isNotBlank() && (result.contains("<ul") || result.contains("<li") || result.contains("<div"))) {
                Log.d("CimanowParser", "Successfully decoded HTML using Rhino JS evaluator.")
                return Jsoup.parse(result)
            }
        } catch (e: Exception) {
            Log.e("CimanowParser", "JS decode format error: ${e.message}")
        }
        
        return doc
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
                        
                    for (link in tab.select("a[href*='episode'], a[href*='/selary/']")) {
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
            for (link in episodeLinks) {
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
        for (link in decodedDoc.select("a[data-src]")) {
            val dataSrc = link.attr("data-src")
            if (dataSrc.isNotBlank() && (dataSrc.startsWith("http") || dataSrc.startsWith("//"))) {
                urls.add(dataSrc)
            }
        }
        
        // Method 2: From iframes (data-src)
        for (iframe in decodedDoc.select("iframe[data-src]")) {
            val dataSrc = iframe.attr("data-src")
            if (dataSrc.isNotBlank()) {
                urls.add(dataSrc)
            }
        }
        
        // Method 3: From inline iframes
        for (iframe in decodedDoc.select("iframe[src]")) {
            val src = iframe.attr("src")
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                urls.add(src)
            }
        }
        
        // Method 4: From data-id (sometimes URLs are there)
        for (link in decodedDoc.select("a[data-id]")) {
            val dataId = link.attr("data-id")
            if (dataId.isNotBlank() && (dataId.startsWith("http") || dataId.startsWith("//"))) {
                urls.add(dataId)
            }
        }
        
        return urls.distinct()
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
