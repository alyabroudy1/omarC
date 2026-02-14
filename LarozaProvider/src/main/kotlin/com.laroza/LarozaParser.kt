package com.laroza

import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.cloudstream.shared.extractors.LinkResolvers
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.cloudstream.shared.parsing.BaseParser
import com.cloudstream.shared.parsing.ParserInterface.ParsedItem
import com.cloudstream.shared.parsing.ParserInterface.ParsedLoadData
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode

// --- Selector Data Structures ---
data class ConfigSelector(
    val query: String,
    val attr: String? = null,
    val regex: String? = null
)

data class ItemPageSelectors(
    val container: String,
    val title: ConfigSelector,
    val url: ConfigSelector,
    val poster: ConfigSelector,
    val tags: List<ConfigSelector> = emptyList()
)

class LarozaParser : NewBaseParser() {
    
    // removed PARSER_TAG to avoid shadowing

    // Selectors from analysis (Bootstrap grid + thumbnail)
    private val itemSelectors = listOf(
        "div.col-md-3 div.thumbnail",
        "div.col-sm-4 div.thumbnail",
        "div.thumbnail",
        "div.pm-li-category" // Legacy fallback
    )

    // --- Configuration ---
    private val mainPageConfig = ItemPageSelectors(
        container = "div.col-md-3 div.thumbnail, div.col-sm-4 div.thumbnail, div.thumbnail, ul.pm-ul-browse-videos li",
        title = ConfigSelector(query = ".caption h3 a, h3 a"),
        url = ConfigSelector(query = ".caption h3 a, h3 a", attr = "href"),
        poster = ConfigSelector(query = ".pm-video-thumb img, img", attr = "src"),
        tags = listOf(
            ConfigSelector(query = ".pm-label-duration") // Duration tag
        )
    )

    // --- Generic Parsing ---
    private fun extractValue(element: Element, selector: ConfigSelector): String? {
        val target = if (selector.query.isBlank()) element else (element.selectFirst(selector.query) ?: return null)
        
        var value = if (selector.attr != null) {
            target.attr(selector.attr).takeIf { it.isNotBlank() } ?: target.attr("data-echo") // Fallback for lazyload
        } else {
            target.text().trim()
        }
        
        // Apply regex if present
        if (!value.isNullOrBlank() && selector.regex != null) {
            // value = Regex(selector.regex).find(value)?.value // Simple implementation
        }
        
        return value
    }

    private fun parseGenericItems(doc: Document, config: ItemPageSelectors): List<ParsedItem> {
        Log.d("LarozaParser", "Parsing Generic Items with selector: ${config.container}")
        val elements = doc.select(config.container)
        Log.d("LarozaParser", "Found ${elements.size} items")
        
        return elements.mapNotNull { element ->
            val title = extractValue(element, config.title)
            val url = extractValue(element, config.url)
            val poster = extractValue(element, config.poster)
            
            if (title.isNullOrBlank() || url.isNullOrBlank()) return@mapNotNull null
            
            val extractedTags = config.tags.mapNotNull { extractValue(element, it) }

            ParsedItem(
                title = title,
                url = url,
                posterUrl = poster,
                isMovie = isMovie(title, url, element),
                tags = extractedTags
            )
        }
    }

    // --- Implementation ---

    // Main Page Selectors
    override fun parseMainPage(doc: Document): List<ParsedItem> {
        val title = doc.title()
        Log.d("LarozaParser", "parseMainPage START. Title: '$title'")
        
        val items = parseGenericItems(doc, mainPageConfig)
        if (items.isEmpty()) {
             Log.d("LarozaParser", "No items found in main page. Dumping 500 chars:")
             Log.d("LarozaParser", doc.html().take(500))
             
             // Try fallback to search parser logic if valid list found
             if (doc.select("ul.pm-ul-browse-videos li").isNotEmpty()) {
                 return parseSearch(doc)
             }
        }
        
        Log.d("LarozaParser", "parseMainPage END. Returning ${items.size} items")
        return items
    }
        


    override fun parseSearch(doc: Document): List<ParsedItem> {
        Log.d("LarozaParser", "parseSearch START")
        // Use same config for search as structure appears identical or fallback is built-in
        val items = parseGenericItems(doc, mainPageConfig)
        
        if (items.isEmpty()) {
            Log.d("LarozaParser", "Search empty. Trying main page parser fallback.")
            return parseMainPage(doc) // Fallback to main page parser if structure matches
        }
        
        Log.d("LarozaParser", "parseSearch END. Returning ${items.size} items")
        return items
    }

    override fun parseLoadPage(doc: Document, url: String): ParsedLoadData? {
        Log.d("LarozaParser", "parseLoadPage START. URL: $url")
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
        
        // Plot
        val plot = doc.selectFirst("div#video-description")?.text()?.trim()
            ?: doc.select("meta[name='description']").attr("content")
            ?: doc.select("meta[property='og:description']").attr("content")
        
        // Poster
        val posterUrl = doc.select("meta[property='og:image']").attr("content")
        
        // Type Detection
        // 1. Check for Episodes list (Generic selector for div or ul)
        val episodeElements = doc.select(".pm-ul-browse-videos a")
        val hasEpisodes = episodeElements.isNotEmpty()
        
        Log.d("LarozaParser", "Type detection: hasEpisodes=$hasEpisodes")

        // 2. Keyword check
        val isSeriesKeyword = title.contains("مسلسل") || title.contains("حلقة") || url.contains("series")
        
        // 3. Category/Breadcrumb check
        val categories = doc.select(".breadcrumb li").text()
        val isSeriesCategory = categories.contains("مسلسلات")
        
        val isMovie = !hasEpisodes && !isSeriesKeyword && !isSeriesCategory
        
        val episodes = if (!isMovie) {
             episodeElements.mapNotNull { element ->
                val epTitle = element.attr("title").trim()
                val epUrl = element.attr("href")
                
                if (epUrl.isNotBlank()) {
                    // Extract number from title or inner text (e.g. <em>12</em>)
                    val emText = element.select("em").text().trim()
                    val epNum = emText.toIntOrNull() 
                        ?: Regex("الحلقة\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                        ?: Regex("(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                        ?: 0
                        
                    ParsedEpisode(
                        name = epTitle,
                        url = epUrl,
                        season = 1, // Default to season 1 for now
                        episode = epNum
                    )
                } else null
            }
        } else emptyList()

        Log.d("LarozaParser", "parseLoadPage END. isMovie=$isMovie, episodes=${episodes.size}")

        return ParsedLoadData(
            title = title,
            plot = plot,
            posterUrl = posterUrl,
            url = url,
            type = if (isMovie) TvType.Movie else TvType.TvSeries,
            year = null, // Could extract from title or meta
            episodes = episodes
        )
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode> {
        // Handled in parseLoadPage for this structure, or can be called separately
        return emptyList()
    }

    override fun extractPlayerUrls(doc: Document): List<String> {
        Log.d("LarozaParser", "extractPlayerUrls START")
        val urls = mutableListOf<String>()
        
        // 1. WatchList (Most reliable for multiple Servers)
        val listItems = doc.select("ul.WatchList li[data-embed-url]")
        Log.d("LarozaParser", "Found ${listItems.size} WatchList items")
        for (li in listItems) {
            val embedUrl = li.attr("data-embed-url")
            if (embedUrl.isNotBlank()) {
                urls.add(embedUrl)
            }
        }
        
        // 2. Direct Iframe (Fallback)
        if (urls.isEmpty()) {
            val iframeSrc = doc.select(".brooks_player iframe").attr("src")
            Log.d("LarozaParser", "Fallback iframe src: '$iframeSrc'")
            if (iframeSrc.isNotBlank()) {
                urls.add(iframeSrc)
            }
        }
        
        Log.d("LarozaParser", "extractPlayerUrls END. Found ${urls.size} urls")
        return urls.distinct()
    }
    
    // Helper for robust detection
    private fun isMovie(title: String, url: String, element: Element?): Boolean {
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return false
        if (url.contains("series") || url.contains("ramadan")) return false
        
        // Check category tag if available
        val categoryText = element?.select("span.label")?.text() ?: ""
        if (categoryText.contains("مسلسلات")) return false
        
        return true
    }
}