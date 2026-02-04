package com.cloudstream.shared.parsing

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainPageRequest

/**
 * Declarative specification for provider parsing.
 * 
 * Define all site-specific selectors and patterns in one place.
 * GenericParser uses this to extract data from HTML.
 */
data class ParserSpec(
    /** Provider name */
    val name: String,
    
    /** Main page definitions */
    val mainPages: List<MainPageDef>,
    
    /** Selectors for main page items */
    val mainPage: MainPageSelectors,
    
    /** Selectors for load page (detail page) */
    val loadPage: LoadPageSelectors,
    
    /** Selectors for episode list */
    val episodes: EpisodeSelectors? = null,
    
    /** Selectors for player extraction */
    val player: PlayerSelectors,
    
    /** URL patterns for type detection */
    val typePatterns: TypePatterns = TypePatterns()
)

/**
 * Main page definition (path + title).
 */
data class MainPageDef(
    val path: String,
    val title: String
)

/**
 * Selectors for main page item parsing.
 */
data class MainPageSelectors(
    /** Container for all items */
    val container: String,
    
    /** Single item selector within container */
    val item: String,
    
    /** URL attribute (usually "href") */
    val urlAttr: String = "href",
    
    /** Title selector relative to item */
    val title: String,
    
    /** Title attribute (text content or specific attr) */
    val titleAttr: String = "text",
    
    /** Poster image selector */
    val poster: String,
    
    /** Poster URL attribute priority: data-src, src */
    val posterAttr: List<String> = listOf("data-src", "src"),
    
    /** Quality badge selector (optional) */
    val quality: String? = null,
    
    /** Year selector (optional) */
    val year: String? = null
)

/**
 * Selectors for detail/load page parsing.
 */
data class LoadPageSelectors(
    /** Title selectors (tried in order) */
    val title: List<String>,
    
    /** Poster selectors (tried in order) */
    val poster: List<String>,
    
    /** Year selector */
    val year: String? = null,
    
    /** Plot/description selector */
    val plot: String? = null,
    
    /** Tags/genres selector */
    val tags: String? = null,
    
    /** Rating selector */
    val rating: String? = null,
    
    /** Movie indicator: if this selector matches, it's a movie */
    val movieIndicator: String? = null,
    
    /** Series indicator: if this selector matches, it's a series */
    val seriesIndicator: String? = null
)

/**
 * Selectors for episode list parsing.
 */
data class EpisodeSelectors(
    /** Season container selector */
    val seasonContainer: String? = null,
    
    /** Season number extraction pattern */
    val seasonPattern: String = """\\d+""",
    
    /** Episode list selector */
    val episodeList: String,
    
    /** Episode URL attribute */
    val urlAttr: String = "href",
    
    /** Episode title selector */
    val title: String? = null,
    
    /** Episode number extraction pattern */
    val episodePattern: String = """\\d+"""
)

/**
 * Selectors for player/watch page parsing.
 */
data class PlayerSelectors(
    /** Watch button/iframe selector */
    val watchButton: String? = null,
    
    /** Player iframe selector */
    val iframe: String? = null,
    
    /** Server tabs selector */
    val serverTabs: String? = null,
    
    /** Server onclick URL pattern */
    val serverUrlPattern: String = """href\s*=\s*['"]([^'"]+)['"]""",
    
    /** Direct video patterns (tried in order) */
    val videoPatterns: List<String> = listOf(
        """file:\s*["']([^"']+)["']""",
        """sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""",
        """source:\s*["']([^"']+\.mp4)["']""",
        """<source[^>]+src=["']([^"']+\.mp4)["']"""
    )
)

/**
 * URL patterns for content type detection.
 */
data class TypePatterns(
    /** URL patterns that indicate a movie */
    val moviePatterns: List<String> = listOf("/movie/", "/film/"),
    
    /** URL patterns that indicate a series */
    val seriesPatterns: List<String> = listOf("/series/", "/show/", "/season/"),
    
    /** Title patterns that indicate a movie */
    val movieTitlePatterns: List<String> = listOf("فيلم", "movie"),
    
    /** Title patterns that indicate a series */
    val seriesTitlePatterns: List<String> = listOf("مسلسل", "series", "موسم")
)

/**
 * Extension to convert ParserSpec main pages to MainPageRequest format.
 */
fun ParserSpec.toMainPageOf(): List<Pair<String, String>> {
    return mainPages.map { it.path to it.title }
}
