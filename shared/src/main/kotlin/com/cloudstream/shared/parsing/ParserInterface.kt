package com.cloudstream.shared.parsing

import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Document

/**
 * Interface for all provider parsers.
 * Decouples parsing logic from network logic.
 */
interface ParserInterface {
    
    data class ParsedItem(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val isMovie: Boolean,
        val tags: List<String> = emptyList()
    )

    /**
     * Complete load page data.
     */
    data class ParsedLoadData(
        val title: String,
        val url: String,
        val posterUrl: String,
        val plot: String?,
        val year: Int?,
        val type: TvType,
        val tags: List<String> = emptyList(),
        /** Watch URL for movies (critical for loadLinks) */
        val watchUrl: String? = null,
        /** Pre-parsed episodes for series */
        val episodes: List<ParsedEpisode>? = null,
        /** CSRF token for AJAX requests */
        val csrfToken: String? = null,
        /** URL of the parent series if this is an episode page */
        val parentSeriesUrl: String? = null
    ) {
        val isMovie: Boolean get() = type == TvType.Movie
    }

    data class ParsedEpisode(
        val url: String,
        val name: String,
        val season: Int,
        val episode: Int
    )

    // Basic Parsing
    fun parseMainPage(doc: Document): List<ParsedItem>
    fun parseSearch(doc: Document): List<ParsedItem>
    fun getSearchUrl(domain: String, query: String): String = "$domain/?s=$query"
    
    /** @deprecated Use parseLoadPageData instead */
    fun parseLoadPage(doc: Document, url: String): ParsedLoadData?
    
    /** Full load page parsing with watchUrl and episodes */
    fun parseLoadPageData(doc: Document, url: String): ParsedLoadData? = parseLoadPage(doc, url)
    fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode>
    fun extractPlayerUrls(doc: Document): List<String>
    
    /**
     * Extract the player/watch page URL from the detail page.
     * Returns null if no player page link exists.
     */
    fun getPlayerPageUrl(doc: Document): String? = null
    
    /**
     * Build server selectors for clicking buttons before video sniffing.
     * This is used by providers that require clicking server buttons (like Laroza's WatchList)
     * before the video player loads.
     * 
     * @param doc The watch page document
     * @param urls The extracted server URLs to match against selectors
     * @return List of selectors corresponding to each URL, null if no selector needed for that URL
     */
    fun buildServerSelectors(doc: Document, urls: List<String>): List<com.cloudstream.shared.extractors.SnifferSelector?> {
        return urls.map { null } // Default: no selectors
    }
    
    // Helpers
    fun resolveServerLink(serverUrl: String): String? = null
}
