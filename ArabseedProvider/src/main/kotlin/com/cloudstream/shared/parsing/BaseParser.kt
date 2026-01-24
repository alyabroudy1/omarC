package com.cloudstream.shared.parsing

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Base parser with common extraction logic.
 * Each provider extends this and overrides specific methods.
 */
abstract class BaseParser {
    protected val TAG = "BaseParser"
    
    // ==================== CONFIGURATION ====================
    
    /** Provider name for logging */
    abstract val providerName: String
    
    /** Main URL for fixing relative URLs */
    abstract val mainUrl: String
    
    // ==================== MAIN PAGE ====================
    
    /** Override to define container selectors (tried in order) */
    abstract val mainPageContainerSelectors: List<String>
    
    /** Override for complex title extraction */
    open fun extractTitle(element: Element): String {
        return element.select("h4, h3, div.title").text()
    }
    
    /** Override for complex poster extraction */
    open fun extractPoster(element: Element): String {
        val img = element.selectFirst("img")
        return img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
    }
    
    /** Override for complex URL extraction */
    open fun extractUrl(element: Element): String? {
        return element.selectFirst("a")?.attr("href")
    }
    
    /** Override to detect if item is a movie (vs series) */
    open fun isMovie(element: Element): Boolean {
        return true // Default to movie
    }
    
    // ==================== MAIN PAGE ====================
    
    data class ParsedSearchItem(
        val title: String,
        val url: String,
        val posterUrl: String,
        val isMovie: Boolean
    )
    
    /** Final parsing method - uses overridable helpers */
    open fun parseMainPage(doc: Document): List<ParsedSearchItem> {
        val container = findContainer(doc, mainPageContainerSelectors)
        
        if (container == null) {
            Log.w(TAG, "[$providerName] No container found for main page. Dumping HTML:")
            Log.w(TAG, "HTML_DUMP_START")
            Log.w(TAG, doc.outerHtml())
            Log.w(TAG, "HTML_DUMP_END")
            return emptyList()
        }
        
        val elements = container.children()
        Log.d(TAG, "[$providerName] Found ${elements.size} elements in main page container")
        
        return elements.mapNotNull { element ->
            try {
                val title = extractTitle(element)
                val url = extractUrl(element) ?: return@mapNotNull null
                val poster = extractPoster(element)
                val isMovie = isMovie(element)
                
                if (title.isBlank()) return@mapNotNull null
                
                ParsedSearchItem(
                    title = title,
                    url = fixUrl(url),
                    posterUrl = fixUrl(poster),
                    isMovie = isMovie
                )
            } catch (e: Exception) {
                Log.w(TAG, "[$providerName] Failed to parse element: ${e.message}")
                null
            }
        }
    }
    
    // ==================== SEARCH ====================
    
    /** Override for search result container selectors */
    open val searchContainerSelectors: List<String>
        get() = mainPageContainerSelectors
    
    fun parseSearch(doc: Document): List<ParsedSearchItem> {
        val container = findContainer(doc, searchContainerSelectors)
        
        if (container == null) {
            Log.w(TAG, "[$providerName] No container found for search. Dumping HTML:")
            Log.w(TAG, "HTML_DUMP_START")
            Log.w(TAG, doc.outerHtml())
            Log.w(TAG, "HTML_DUMP_END")
            return emptyList()
        }
        
        return container.children().mapNotNull { element ->
            try {
                val title = extractTitle(element)
                val url = extractUrl(element) ?: return@mapNotNull null
                val poster = extractPoster(element)
                
                if (title.isBlank()) return@mapNotNull null
                
                ParsedSearchItem(
                    title = title,
                    url = fixUrl(url),
                    posterUrl = fixUrl(poster),
                    isMovie = true // Default to movie for search if type unknown? Or add detection logic
                )
            } catch (e: Exception) {
                null
            }
        }
    }
    
    // ==================== LOAD PAGE ====================
    
    /** Override to parse load page (movie or series detail) */
    abstract fun parseLoadPage(doc: Document, url: String): LoadResponse?
    
    // ==================== EPISODES ====================
    
    data class ParsedEpisode(
        val url: String,
        val name: String,
        val season: Int,
        val episode: Int
    )
    
    /** Override to parse episodes for a series */
    abstract fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode>
    
    // ==================== VIDEO EXTRACTION ====================
    
    /** Override to extract player URLs from load page */
    abstract fun extractPlayerUrls(doc: Document): List<String>
    
    // ==================== HELPERS ====================
    
    protected fun findContainer(doc: Document, selectors: List<String>): Element? {
        for (selector in selectors) {
            val found = doc.select(selector)
            if (found.isNotEmpty()) {
                Log.d(TAG, "[$providerName] Found container with selector: $selector")
                return found.first()
            }
        }
        return null
    }
    
    protected fun findElements(doc: Document, selectors: List<String>): List<Element> {
        for (selector in selectors) {
            val found = doc.select(selector)
            if (found.isNotEmpty()) {
                return found.toList()
            }
        }
        return emptyList()
    }
    
    protected fun findFirst(doc: Document, selectors: List<String>): Element? {
        for (selector in selectors) {
            val found = doc.selectFirst(selector)
            if (found != null) return found
        }
        return null
    }
    
    protected fun findText(doc: Document, selectors: List<String>): String {
        for (selector in selectors) {
            val text = doc.select(selector).text()
            if (text.isNotBlank()) return text
        }
        return ""
    }
    
    fun fixUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
