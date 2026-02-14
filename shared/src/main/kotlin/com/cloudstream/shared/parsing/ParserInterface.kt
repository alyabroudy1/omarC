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
        val csrfToken: String? = null
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
    
    /** @deprecated Use parseLoadPageData instead */
    fun parseLoadPage(doc: Document, url: String): ParsedLoadData?
    
    /** Full load page parsing with watchUrl and episodes */
    fun parseLoadPageData(doc: Document, url: String): ParsedLoadData? = parseLoadPage(doc, url)
    
    // Videos
    fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode>
    fun extractPlayerUrls(doc: Document): List<String>
    
    // Helpers
    fun resolveServerLink(serverUrl: String): String? = null
}
