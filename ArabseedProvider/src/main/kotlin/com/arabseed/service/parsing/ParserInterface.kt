package com.arabseed.service.parsing

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
        val isMovie: Boolean
    )

    data class ParsedLoadData(
        val title: String,
        val url: String,
        val posterUrl: String,
        val plot: String?,
        val year: Int?,
        val type: TvType,
        val tags: List<String> = emptyList()
    )

    data class ParsedEpisode(
        val url: String,
        val name: String,
        val season: Int,
        val episode: Int
    )

    // Basic Parsing
    fun parseMainPage(doc: Document): List<ParsedItem>
    fun parseSearch(doc: Document): List<ParsedItem>
    fun parseLoadPage(doc: Document, url: String): ParsedLoadData?
    
    // Videos
    fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode>
    fun extractPlayerUrls(doc: Document): List<String>
    
    // Helpers
    fun resolveServerLink(serverUrl: String): String? = null
}
