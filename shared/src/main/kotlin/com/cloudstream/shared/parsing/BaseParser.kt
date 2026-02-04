package com.cloudstream.shared.parsing

import com.lagradost.api.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Shared parsing logic and helpers.
 * Implements common pattern matching and selector utilities.
 */
abstract class BaseParser : ParserInterface {
    
    protected val TAG = "BaseParser"
    
    abstract val providerName: String
    abstract val mainUrl: String
    
    // Default selectors (can be overridden)
    protected open val mainPageContainerSelectors = listOf("div.MovieBlock", "div.block-item")
    protected open val itemTitleSelectors = listOf("div.title", "h3")
    protected open val itemUrlSelectors = listOf("a")
    protected open val itemPosterSelectors = listOf("img")
    
    protected open val isMovieSelector = "div.type:contains(Movie)"

    // ================= HELPER METHODS =================
    
    protected fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        
        val base = mainUrl.trimEnd('/')
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    protected fun findText(doc: Element, selectors: List<String>): String {
        for (selector in selectors) {
            val text = doc.select(selector).text()
            if (text.isNotBlank()) return text
        }
        return ""
    }

    protected fun findFirst(doc: Element, selectors: List<String>): Element? {
        for (selector in selectors) {
            val el = doc.selectFirst(selector)
            if (el != null) return el
        }
        return null
    }

    // ================= INTERFACE IMPLEMENTATION (DEFAULTS) =================
    
    override fun parseMainPage(doc: Document): List<ParserInterface.ParsedItem> {
        val containerSelector = mainPageContainerSelectors.joinToString(", ")
        return doc.select(containerSelector).mapNotNull { element ->
             parseItem(element)
        }
    }
    
    override fun parseSearch(doc: Document): List<ParserInterface.ParsedItem> {
        return parseMainPage(doc)
    }
    
    // Abstract so concrete implementation defines it
    protected open fun parseItem(element: Element): ParserInterface.ParsedItem? {
        try {
            val title = extractTitle(element)
            val url = extractUrl(element)
            val poster = extractPoster(element)
            
            if (url == null || title.isBlank()) return null
            
            return ParserInterface.ParsedItem(
                title = title,
                url = fixUrl(url),
                posterUrl = fixUrl(poster),
                isMovie = isMovie(element)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing item: ${e.message}")
            return null
        }
    }
    
    protected open fun extractTitle(element: Element): String {
        return findText(element, itemTitleSelectors)
    }
    
    protected open fun extractUrl(element: Element): String? {
        for (selector in itemUrlSelectors) {
             val url = element.select(selector).attr("href")
             if (url.isNotBlank()) return url
        }
        return element.attr("href").ifBlank { null }
    }
    
    protected open fun extractPoster(element: Element): String {
        for (selector in itemPosterSelectors) {
            val img = element.selectFirst(selector) ?: continue
            val url = img.attr("data-src").ifBlank { img.attr("src") }
            if (url.isNotBlank()) return url
        }
        return ""
    }
    
    protected open fun isMovie(element: Element): Boolean {
         return element.select(isMovieSelector).isNotEmpty()
    }
}
