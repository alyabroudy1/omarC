package com.akwam

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AkwamParser : NewBaseParser() {
    
    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search?q=$query"
    }

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(""),
        id = CssSelector(""),
        title = CssSelector(""),
        iframe = CssSelector("")
    )
    
    override val mainPageConfig = MainPageConfig(
        container = "div.col-lg-auto.col-md-4.col-6",
        title = CssSelector(query = "h3.entry-title a", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val searchConfig = mainPageConfig

    override fun parseMainPage(doc: Document): List<ParserInterface.ParsedItem> {
        val items = super.parseMainPage(doc)
        return items.map { it.copy(url = appendPosterFragment(it.url, it.posterUrl)) }
    }

    override fun parseSearch(doc: Document): List<ParserInterface.ParsedItem> {
        val items = super.parseSearch(doc)
        return items.map { it.copy(url = appendPosterFragment(it.url, it.posterUrl)) }
    }

    private fun appendPosterFragment(url: String, poster: String?): String {
        if (poster == null || poster.isBlank()) return url
        return if (url.contains("#")) url else "$url#$poster"
    }

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.entry-title", attr = "text"),
        poster = CssSelector(query = "meta[property='og:image']", attr = "content"),
        plot = CssSelector(
            query = "h2:contains(قصة المسلسل) + div > p, meta[name=description]", 
            attr = "text, content"
        ),
        year = CssSelector(query = "div.font-size-16.text-white a[href*='/year/']", attr = "text"),
        tags = CssSelector(query = "div.font-size-16.text-white a[href*='/genre/'], div.font-size-16.text-white a[href*='/category/']", attr = "text"),
        rating = CssSelector(query = "div.font-size-16.text-white span.px-3", attr = "text"),
        seriesIndicator = CssSelector(query = "div#series-episodes, a.btn[href*='/series/']", attr = "text"),
        parentSeriesUrl = CssSelector(query = "a.btn[href*='/series/']", attr = "href"),
    )

    override val episodeConfig = EpisodeConfig(
        container = "div#series-episodes div.col-lg-4, div#series-episodes div.col-md-6",
        title = CssSelector(query = "h2, a", attr = "text"),
        url = CssSelector(query = "a[href*='/episode/']", attr = "abs:href"),
        episode = CssSelector(query = "h2, a", attr = "text", regex = "(\\d+)")
    )

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val elements = doc.select(episodeConfig.container)
        Log.d("AkwamParser", "parseEpisodes: found ${elements.size} episode elements")
        
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst("h2") ?: element.selectFirst("a")
            val urlElement = element.selectFirst("a[href*='/episode/']")
            
            if (titleElement != null && urlElement != null) {
                val title = titleElement.text()
                val url = urlElement.attr("abs:href")
                
                val epNum = Regex("(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val season = getSeasonNumber(title) ?: seasonNum ?: 1
                
                Log.d("AkwamParser", "Parsed episode: '$title', ep=$epNum, season=$season")
                
                ParserInterface.ParsedEpisode(
                    name = title,
                    url = url,
                    season = season,
                    episode = epNum
                )
            } else null
        }
    }

    private fun getSeasonNumber(text: String): Int? {
        val map = mapOf(
            "الاول" to 1, "الأول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
            "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
            "العاشر" to 10, "الحادي عشر" to 11, "الثاني عشر" to 12, "الثالث عشر" to 13,
            "الرابع عشر" to 14, "الخامس عشر" to 15, "السادس عشر" to 16, "السابع عشر" to 17,
            "الثامن عشر" to 18, "التاسع عشر" to 19, "العشرون" to 20
        )
        for ((key, value) in map) {
            if (text.contains(key)) return value
        }
        return Regex("الموسم (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("موسم (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        val result = isSeriesLogic(title, url, element)
        Log.d("AkwamParser", "isSeries: title='$title', url='$url', result=$result")
        return result
    }

    private fun isSeriesLogic(title: String, url: String, element: Element?): Boolean {
        if (title.contains("فيلم") || title.contains("film", true) || title.contains("movie", true)) return false
        if (element != null) {
            if (element is Document) {
                if (element.select(episodeConfig.container).isNotEmpty()) return true
                if (element.select("a.btn[href*='/series/']").isNotEmpty()) return true
            } else {
                // Check for season/episode labels in the catalog item
                val label = element.select("span.label").text()
                if (label.contains("مسلسل") || label.contains("حلقة") || label.contains("موسم")) return true
                
                // Poster badges
                val badge = element.select(".badge").text()
                if (badge.contains("مسلسل") || badge.contains("حلقة") || badge.contains("موسم")) return true
            }
        }
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        if (url.contains("series")) return true
        return false
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        val watchPathElement = doc.selectFirst("a.link-show")
        val pageIdElement = doc.selectFirst("input#page_id")
        
        if (watchPathElement == null || pageIdElement == null) {
            Log.d("AkwamParser", "watchPathElement or pageIdElement is null")
            return null
        }
        
        val watchPath = watchPathElement.attr("href").ifBlank { watchPathElement.attr("abs:href") }
        val pageId = pageIdElement.attr("value").ifBlank { pageIdElement.attr("data-value") }
        
        if (watchPath.isBlank() || pageId.isBlank()) {
            Log.d("AkwamParser", "watchPath or pageId is blank")
            return null
        }
        
        // Logic from decompiled code to construct watch URL
        val cleanPath = watchPath.substringAfter("/watch").trimEnd('/')
        val watchUrl = "/watch$cleanPath/$pageId"
        
        Log.d("AkwamParser", "getPlayerPageUrl: constructed watchUrl='$watchUrl'")
        return watchUrl
    }

    override fun extractWatchServersUrls(doc: Document): List<String> {
        val sources = doc.select("source[src]")
        Log.d("AkwamParser", "extractWatchServersUrls: found ${sources.size} sources")
        return sources.mapNotNull { src ->
            var videoUrl = src.attr("abs:src").ifBlank { src.attr("src") }
            if (videoUrl.isNotBlank()) {
                // Akwam uses direct links in the watch page
                // Fixing previous bug: strings are immutable, need to assign result
                videoUrl = videoUrl.replace("https://", "http://")
                videoUrl = videoUrl.replace(" ", "%20")
                Log.d("AkwamParser", "extracted videoUrl: $videoUrl")
                videoUrl
            } else {
                Log.d("AkwamParser", "source element has blank src")
                null
            }
        }.distinct()
    }
}
