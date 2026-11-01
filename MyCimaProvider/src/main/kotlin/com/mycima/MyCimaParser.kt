package com.mycima

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class MyCimaParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/filtering/?keywords=$query"
    }

    override fun getSearchUrl(domain: String, query: String, page: Int): String {
        if (page <= 1) return getSearchUrl(domain, query)
        return "$domain/filtering/?keywords=$query&page=$page"
    }

    override val supportsSearchPagination: Boolean get() = true

    override val mainPageConfig = MainPageConfig(
        container = "div.GridItem",
        title = CssSelector("h2, strong", "text"),
        url = CssSelector("a", "href"),
        poster = CssSelector("span.BG--GridItem", "data-lazy-style, data-src, style", regex = """url\(['"]?(.*?)['"]?\)""")
    )

    override val searchConfig = mainPageConfig

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector("div.Title--Content--Single-begin h1", "text"),
        poster = CssSelector("wecima.separated--top", "data-lazy-style, style", regex = """url\(['"]?(.*?)['"]?\)"""),
        plot = CssSelector("div.StoryMovieContent", "text"),
        year = CssSelector("ul.Terms--Content--Single-begin li:contains(السنة) p", "text"),
        rating = CssSelector("", "text"),
        tags = CssSelector("", "text"),
        parentSeriesUrl = CssSelector(".Series--Section a", "href")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".EpisodesList a",
        title = CssSelector("episodetitle", "text"),
        url = CssSelector("", "href"),
        episode = CssSelector("episodetitle", "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector("", ""),
        id = CssSelector("", ""),
        title = CssSelector("", ""),
        iframe = CssSelector("", "")
    )

    override fun isSeries(title: String, url: String, element: org.jsoup.nodes.Element?): Boolean {
        if (title.contains("فيلم") || title.contains("film", true) || title.contains("movie", true)) return false
        if (element != null) {
            if (element is org.jsoup.nodes.Document) {
                if (element.select(episodeConfig.container).isNotEmpty()) return true
                if (element.extract(loadPageConfig.seriesIndicator) != null) return true
                if (element.select(".Series--Section").isNotEmpty()) return true
                if (element.select(".SeasonsList").isNotEmpty()) return true
            }
        }
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        if (url.contains("series")) return true
        if (url.contains("مسلسل")) return true
        if (url.contains("%d9%85%d8%b3%d9%84%d8%b3%d9%84", ignoreCase=true)) return true
        return false
    }

    private fun decodeWatchUrl(encodedStr: String): String? {
        try {
            if (encodedStr.isBlank()) return null
            val trimmed = encodedStr.trim()

            // 1. If it's a URL with query params, check query params for embedded base64 URL (e.g. ?mycimafsd=aHR0c..., ?url=aHR0c...)
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                if (trimmed.contains("?")) {
                    val query = trimmed.substringAfter("?")
                    for (param in query.split("&")) {
                        val value = param.substringAfter("=", "")
                        if (value.contains("aHR0c")) {
                            val b64Part = Regex("""aHR0c[A-Za-z0-9+/=]+""").find(value)?.value
                            if (b64Part != null) {
                                try {
                                    val dec = String(android.util.Base64.decode(b64Part, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8).trim()
                                    if (dec.startsWith("http://") || dec.startsWith("https://")) {
                                        return dec
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                return trimmed
            }

            if (trimmed.startsWith("//")) {
                return "https:$trimmed"
            }

            // 2. Pure Base64 decode
            try {
                val dec = String(android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8).trim()
                if (dec.startsWith("http://") || dec.startsWith("https://")) {
                    return dec
                }
            } catch (_: Exception) {}

            // 3. Legacy fallback: Base64 without 'http' prefix (prepend 'aHR0c')
            try {
                val withPre = if (trimmed.startsWith("aHR0c")) trimmed else "aHR0c$trimmed"
                val dec = String(android.util.Base64.decode(withPre, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8).trim()
                if (dec.startsWith("http://") || dec.startsWith("https://")) {
                    return dec
                }
            } catch (_: Exception) {}

            return null
        } catch (e: Exception) {
            return null
        }
    }

    override fun extractWatchServersUrls(doc: org.jsoup.nodes.Document): List<String> {
        val urls = mutableListOf<String>()
        
        doc.select("ul.WatchServersList li[data-watch], ul#watch li[data-watch], .WatchServersList li[data-watch], .WatchServersList li, .Links--Content ul li, li[data-watch]").forEach { serverBtn ->
            val dataWatch = serverBtn.attr("data-watch").ifEmpty { serverBtn.attr("data-url") }.ifEmpty { serverBtn.selectFirst("a")?.attr("data-url") ?: "" }
            val decodedUrl = decodeWatchUrl(dataWatch)
            if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http")) {
                urls.add(decodedUrl)
            }
        }
        
        doc.select(".List--Download--Wecima--Single li a[href], .openLinkDown").forEach { downloadBtn ->
            val dataHref = downloadBtn.attr("href").ifEmpty { downloadBtn.attr("data-href") }
            val decodedUrl = decodeWatchUrl(dataHref)
            if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http")) {
                urls.add(decodedUrl)
            }
        }
        
        return urls.distinct()
    }
}
