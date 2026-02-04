package com.cloudstream.shared.parsing

import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parsed item data - provider-agnostic result type.
 * Providers convert this to SearchResponse using their MainAPI context.
 */
data class ParsedItem(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val quality: String?,
    val type: TvType,
    val year: Int? = null
)

/**
 * Load page data - contains parsed detail page info.
 */
data class LoadPageData(
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val plot: String?,
    val tags: List<String>,
    val rating: Double?,
    val isMovie: Boolean,
    val episodes: List<EpisodeData>,
    val recommendations: List<ParsedItem>
)

/**
 * Episode data for series.
 */
data class EpisodeData(
    val name: String,
    val url: String,
    val season: Int?,
    val episode: Int?,
    val posterUrl: String?
)

/**
 * Generic parser that uses ParserSpec to extract data from HTML.
 * 
 * Returns generic ParsedItem types that providers convert to SearchResponse.
 */
class GenericParser(
    private val spec: ParserSpec,
    private val fixUrl: (String) -> String
) {
    private val TAG = "GenericParser"
    
    // ========== MAIN PAGE ==========
    
    /**
     * Parse main page items from document.
     */
    fun parseMainPage(doc: Document): List<ParsedItem> {
        val selectors = spec.mainPage
        
        val container = doc.select(selectors.container)
        if (container.isEmpty()) {
            ProviderLogger.w(TAG, "parseMainPage", "Container not found", "selector" to selectors.container)
            return emptyList()
        }
        
        return container.select(selectors.item).mapNotNull { element ->
            parseMainPageItem(element, selectors)
        }
    }
    
    private fun parseMainPageItem(element: Element, selectors: MainPageSelectors): ParsedItem? {
        // URL
        val url = element.select("a").attr(selectors.urlAttr).ifBlank {
            element.attr(selectors.urlAttr)
        }
        if (url.isBlank()) return null
        
        // Title
        val title = if (selectors.titleAttr == "text") {
            element.select(selectors.title).text()
        } else {
            element.select(selectors.title).attr(selectors.titleAttr)
        }
        if (title.isBlank()) return null
        
        // Poster
        val posterElement = element.select(selectors.poster).firstOrNull()
        val posterUrl = posterElement?.let { img ->
            selectors.posterAttr.firstNotNullOfOrNull { attr ->
                img.attr(attr).takeIf { it.isNotBlank() }
            }
        }?.let { fixUrl(it) }
        
        // Quality
        val quality = selectors.quality?.let { element.select(it).text() }
        
        // Type detection
        val type = detectType(url, title)
        
        return ParsedItem(
            title = cleanTitle(title),
            url = fixUrl(url),
            posterUrl = posterUrl,
            quality = quality,
            type = type
        )
    }
    
    // ========== LOAD PAGE ==========
    
    /**
     * Parse detail page for title, poster, plot, etc.
     */
    fun parseLoadPage(doc: Document, url: String): LoadPageData {
        val selectors = spec.loadPage
        
        // Title (try multiple selectors)
        val title = selectors.title.firstNotNullOfOrNull { sel ->
            doc.select(sel).text().takeIf { it.isNotBlank() }
        } ?: doc.title()
        
        // Poster (try multiple selectors)
        val posterUrl = selectors.poster.firstNotNullOfOrNull { sel ->
            val img = doc.selectFirst(sel)
            img?.attr("data-src")?.ifBlank { img.attr("src") }?.takeIf { text -> text.isNotBlank() }
        }?.let { fixUrl(it) }
        
        // Year
        val year = selectors.year?.let { doc.select(it).text().toIntOrNull() }
        
        // Plot
        val plot = selectors.plot?.let { doc.select(it).text().takeIf { text -> text.isNotBlank() } }
        
        // Tags
        val tags = selectors.tags?.let { doc.select(it).map { el -> el.text() } }.orEmpty()
        
        // Rating
        val rating = selectors.rating?.let { 
            doc.select(it).text().toDoubleOrNull() 
        }
        
        // Check if movie or series
        val isMovie = when {
            selectors.movieIndicator != null && doc.select(selectors.movieIndicator).isNotEmpty() -> true
            selectors.seriesIndicator != null && doc.select(selectors.seriesIndicator).isNotEmpty() -> false
            else -> detectType(url, title) == TvType.Movie
        }
        
        // Episodes (using spec.episodes if available)
        val episodes = spec.episodes?.let { epSelectors ->
            parseEpisodes(doc, epSelectors)
        } ?: emptyList()
        
        // Recommendations (re-use main page parsing)
        val recommendations = emptyList<ParsedItem>()
        
        return LoadPageData(
            title = cleanTitle(title),
            posterUrl = posterUrl,
            year = year,
            plot = plot,
            tags = tags,
            rating = rating,
            isMovie = isMovie,
            episodes = episodes,
            recommendations = recommendations
        )
    }
    
    private fun parseEpisodes(doc: Document, selectors: EpisodeSelectors): List<EpisodeData> {
        return doc.select(selectors.episodeList).mapNotNull { element ->
            val epUrl = element.attr(selectors.urlAttr)
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            
            val epName = selectors.title?.let { element.select(it).text() }
                .takeIf { !it.isNullOrBlank() } ?: "Episode"
            
            // Extract episode number from title or URL
            val episodeNum = Regex(selectors.episodePattern).find(epName)?.value?.toIntOrNull()
                ?: Regex(selectors.episodePattern).find(epUrl)?.value?.toIntOrNull()
            
            EpisodeData(
                name = epName,
                url = fixUrl(epUrl),
                season = null,
                episode = episodeNum,
                posterUrl = null
            )
        }
    }
    
    // ========== PLAYER PAGE ==========
    
    /**
     * Extract player URLs from episode/movie page.
     */
    fun parsePlayerUrls(doc: Document): List<String> {
        val selectors = spec.player
        val urls = mutableListOf<String>()
        
        // Extract iframe URLs
        selectors.iframe?.let { iframeSel ->
            doc.select(iframeSel).forEach { iframe: Element ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank()) {
                    urls.add(fixUrl(src))
                }
            }
        }
        
        // Extract from server tabs/buttons
        selectors.serverTabs?.let { tabSel ->
            doc.select(tabSel).forEach { tab: Element ->
                val onclick = tab.attr("onclick")
                Regex(selectors.serverUrlPattern).find(onclick)?.groupValues?.getOrNull(1)?.let { url ->
                    if (url.isNotBlank()) {
                        urls.add(fixUrl(url))
                    }
                }
            }
        }
        
        // Extract watch button
        selectors.watchButton?.let { btnSel ->
            doc.select(btnSel).forEach { btn: Element ->
                val href = btn.attr("href")
                if (href.isNotBlank()) {
                    urls.add(fixUrl(href))
                }
            }
        }
        
        return urls.distinct()
    }
    
    // ========== HELPERS ==========
    
    private fun detectType(url: String, title: String): TvType {
        val patterns = spec.typePatterns
        
        val lowerUrl = url.lowercase()
        val lowerTitle = title.lowercase()
        
        // Check movie patterns
        for (pattern in patterns.moviePatterns) {
            if (lowerUrl.contains(pattern)) return TvType.Movie
        }
        for (pattern in patterns.movieTitlePatterns) {
            if (lowerTitle.contains(pattern)) return TvType.Movie
        }
        
        // Check series patterns
        for (pattern in patterns.seriesPatterns) {
            if (lowerUrl.contains(pattern)) return TvType.TvSeries
        }
        for (pattern in patterns.seriesTitlePatterns) {
            if (lowerTitle.contains(pattern)) return TvType.TvSeries
        }
        
        return TvType.Movie // Default
    }
    
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\s+"), " ")
            .replace(Regex("مترجم|مدبلج|حصري|جديد", RegexOption.IGNORE_CASE), "")
            .trim()
    }
    
    companion object {
        fun getQualityFromString(quality: String): SearchQuality? {
            val q = quality.lowercase()
            return when {
                q.contains("4k") || q.contains("2160p") -> SearchQuality.UHD
                q.contains("1080") -> SearchQuality.HD
                q.contains("720") -> SearchQuality.HD
                q.contains("480") -> SearchQuality.SD
                q.contains("bluray") || q.contains("webdl") -> SearchQuality.HD
                q.contains("hdcam") || q.contains("cam") -> SearchQuality.Cam
                else -> null
            }
        }
    }
}
