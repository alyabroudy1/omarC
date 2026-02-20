package com.cloudstream.shared.parsing

import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Advanced Selector for generic parsing.
 * @param query CSS selector query.
 * @param attr Attribute to extract (e.g., "href", "src", "text"). Can be comma-separated for fallbacks (e.g., "data-src, src").
 * @param regex Optional Regex to extract specific content from the result.
 */
data class CssSelector(
    val query: String,
    val attr: String = "text",
    val regex: String? = null
)

// Configuration Data Classes
data class MainPageConfig(
    val container: String,
    val item: String? = null, // Optional if container selects items directly
    val title: CssSelector,
    val url: CssSelector,
    val poster: CssSelector,
    val isMovie: ((String, String, Element) -> Boolean)? = null // Optional override
)

data class LoadPageConfig(
    val title: CssSelector,
    val plot: CssSelector,
    val poster: CssSelector,
    val rating: CssSelector? = null,
    val tags: CssSelector? = null,
    val year: CssSelector? = null,
    val movieIndicator: CssSelector? = null,
    val seriesIndicator: CssSelector? = null,
    val parentSeriesUrl: CssSelector? = null
)

data class EpisodeConfig(
    val container: String,
    val title: CssSelector? = null,
    val url: CssSelector,
    val season: CssSelector? = null, // Logic often complex, but selector support helps
    val episode: CssSelector? = null
)

data class SeasonSelector(
    val container: String,
    val title: CssSelector? = null,
    val url: CssSelector,
)

data class WatchServerSelector(
    val url: CssSelector? = null,
    val id: CssSelector? = null,
    val title: CssSelector? = null,
    val iframe: CssSelector? = null,
    val script: CssSelector? = null // For regex extraction from scripts
)


/**
 * Shared parsing logic and helpers.
 * Implements common pattern matching and selector utilities using CssConfig.
 */
abstract class NewBaseParser : ParserInterface {
    
    protected val TAG = "NewBaseParser"

    // Abstract configs to be provided by implementing classes
    abstract val mainPageConfig: MainPageConfig
    open val searchConfig: MainPageConfig get() = mainPageConfig
    abstract val loadPageConfig: LoadPageConfig
    abstract val episodeConfig: EpisodeConfig
    abstract val watchServersSelectors: WatchServerSelector

    // ================== PARSING IMPLEMENTATION ==================

    override fun parseMainPage(doc: Document): List<ParserInterface.ParsedItem> {
        return parseItems(doc, mainPageConfig)
    }

    override fun parseSearch(doc: Document): List<ParserInterface.ParsedItem> {
        val items = parseItems(doc, searchConfig)
        if (items.isEmpty() && searchConfig === mainPageConfig) {
             return parseMainPage(doc)
        }
        return items
    }

    protected open fun parseItems(doc: Document, config: MainPageConfig): List<ParserInterface.ParsedItem> {
        val items = mutableListOf<ParserInterface.ParsedItem>()
        doc.select(config.container).forEach { element ->
             // If 'item' is specified, select it inside container, otherwise use container element itself
             val itemElement = if (config.item != null) element.selectFirst(config.item) else element
             if (itemElement != null) {
                 parseItem(itemElement, config)?.let { items.add(it) }
             }
        }
        return items
    }

    protected open fun parseItem(element: Element, config: MainPageConfig): ParserInterface.ParsedItem? {
        val title = element.extract(config.title)
        val url = element.extract(config.url)
        val posterUrl = element.extract(config.poster)

        if (!title.isNullOrBlank() && !url.isNullOrBlank()) {
            return ParserInterface.ParsedItem(
                title = title,
                url = url,
                posterUrl = posterUrl,
                isMovie = config.isMovie?.invoke(title, url, element) ?: isMovie(title, url, element)
            )
        }
        return null
    }

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val config = loadPageConfig


        val title = doc.extract(config.title) ?: doc.title()
        val plot = doc.extract(config.plot)
        val posterUrl = doc.extract(config.poster)
        val year = doc.extract(config.year)?.toIntOrNull()
        val parentSeriesUrl = doc.extract(config.parentSeriesUrl)
        
        // Type Detection logic
        val isMovie = isMovie(title, url, doc)
        
        val episodes = if (!isMovie) {
             parseEpisodes(doc, null)
        } else emptyList()

        return ParserInterface.ParsedLoadData(
            title = title,
            plot = plot,
            posterUrl = posterUrl ?: "",
            url = url,
            type = if (isMovie) TvType.Movie else TvType.TvSeries,
            year = year, 
            episodes = episodes,
            parentSeriesUrl = parentSeriesUrl
        )
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val config = episodeConfig
        
        return doc.select(config.container).mapNotNull { element ->
            val title = element.extract(config.title) ?: "Episode"
            val url = element.extract(config.url)
            
            if (!url.isNullOrBlank()) {
                val epNum = element.extract(config.episode)?.toIntOrNull()
                    ?: Regex("(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: Regex("(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: 0
                    
                ParserInterface.ParsedEpisode(
                    name = title,
                    url = url,
                    season = seasonNum ?: 1,
                    episode = epNum
                )
            } else null
        }
    }

    override fun extractWatchServersUrls(doc: Document): List<String> {
        val urls = mutableListOf<String>()
        val config = watchServersSelectors
        
        config.url?.let { selector ->
             doc.select(selector.query).forEach { element ->
                 element.extract(selector.copy(query = ""))?.let { if (it.isNotBlank()) urls.add(it) }
             }
        }
        
        config.iframe?.let { selector ->
             doc.select(selector.query).forEach { element ->
                 element.extract(selector.copy(query = ""))?.let { if (it.isNotBlank()) urls.add(it) }
             }
        }
        
        return urls.distinct()
    }

    // ================== HELPER FUNCTIONS ==================

    /**
     * Extracts data from an element based on the CssSelector.
     * Handles:
     * - Query selection
     * - Multiple attribute fallbacks (comma separated)
     * - "text" attribute special handling
     * - Regex extraction (optional)
     */
    protected fun Element.extract(selector: CssSelector?): String? {
        if (selector == null) return null
        
        // 1. Select Element
        // If query is blank/empty, use current element (checking itself)
        val target = if (selector.query.isNotBlank()) this.selectFirst(selector.query) else this
        if (target == null) return null

        // 2. Extract Attribute value
        val rawValue = selector.attr.split(",").firstNotNullOfOrNull { attrName ->
            val attr = attrName.trim()
            if (attr.equals("text", ignoreCase = true)) {
                target.text()?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                target.attr(attr).trim().takeIf { it.isNotEmpty() }
            }
        } ?: return null

        // 3. Apply Regex if present
        return if (selector.regex != null) {
            Regex(selector.regex).find(rawValue)?.groupValues?.getOrNull(1) ?: rawValue
        } else {
            rawValue
        }
    }


    open fun isMovie(title: String, url: String, element: Element?): Boolean {
        if (isEpisode(title, url, element)) return false
        if (isSeries(title, url, element)) return false
        return true
    }

    open fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        // Default generic check
        if (url.contains("series") || url.contains("season")) return true
        
        if (element is Document && loadPageConfig.seriesIndicator != null) {
            if (element.extract(loadPageConfig.seriesIndicator) != null) return true
        }
        
        return false
    }

    open fun isEpisode(title: String, url: String, element: Element?): Boolean {
        // Default generic check
        return title.contains("حلقة") || title.contains("episode", true)
    }
}
