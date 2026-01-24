package com.arabseed

import com.cloudstream.shared.parsing.BaseParser
import com.cloudstream.shared.parsing.BaseParser.ParsedEpisode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Arabseed-specific parser extending BaseParser.
 * Contains all site-specific selectors and parsing logic.
 */
class ArabseedParser : BaseParser() {
    
    override val providerName = "Arabseed"
    override val mainUrl = "https://arabseed.show"
    
    // ==================== MAIN PAGE SELECTORS ====================
    
    override val mainPageContainerSelectors = listOf(
        "ul.Blocks-UL > div",
        "div.Blocks-UL > div",
        "div.MovieBlock",
        "div.poster__single"
    )
    
    override fun extractTitle(element: Element): String {
        return element.select("h4").text()
            .ifEmpty { element.select("h3").text() }
            .ifEmpty { element.select("div.title").text() }
            .ifEmpty { element.selectFirst("a")?.attr("title") ?: "" }
    }
    
    override fun extractPoster(element: Element): String {
        val img = element.selectFirst("img.imgOptimzer")
            ?: element.selectFirst("div.Poster img")
            ?: element.selectFirst("img")
        
        return img?.attr("data-image")
            ?.ifBlank { img.attr("data-src") }
            ?.ifBlank { img.attr("src") }
            ?: ""
    }
    
    override fun extractUrl(element: Element): String? {
        return element.selectFirst("a")?.attr("href")?.ifBlank { null }
    }
    
    override fun isMovie(element: Element): Boolean {
        val category = element.select("span.category").text()
        return !category.contains("مسلسلات")
    }
    
    // ==================== LOAD PAGE ====================
    
    /**
     * Parsed data from load page (movie or series).
     * The provider converts this to proper LoadResponse.
     */
    /**
     * Parsed data from load page (movie or series).
     * The provider converts this to proper LoadResponse.
     */
    data class ParsedLoadData(
        val title: String,
        val posterUrl: String,
        val year: Int?,
        val plot: String,
        val tags: List<String>,
        val rating: Int?,
        val isMovie: Boolean,
        val watchUrl: String?, // For movies
        val episodes: List<ParsedEpisode>? // For series
    )
    
    // Implemented to satisfy BaseParser, but unused by this provider implementation
    // because we need to use MainAPI helpers in the provider class to create LoadResponse
    override fun parseLoadPage(doc: Document, url: String): LoadResponse? {
        return null
    }
    
    fun parseLoadPageData(doc: Document, url: String): ParsedLoadData? {
        // Title extraction with fallbacks
        var title = findText(doc, listOf(
            "div.title h1",
            "h1.postTitle",
            "div.h1-title h1"
        ))
        
        if (title.isBlank()) {
            title = doc.title()
                .replace(" - عرب سيد", "")
                .replace("مترجم اون لاين", "")
                .trim()
        }
        
        if (title.isBlank()) {
            Log.e(TAG, "[$providerName] Failed to parse title for: $url")
            return null
        }
        
        // Poster extraction
        val posterUrl = extractPosterFromLoadPage(doc)
        
        // Metadata
        val year = doc.select("div.singleInfo span:contains(السنة) a").text().toIntOrNull()
        val plot = findText(doc, listOf(
            "div.singleInfo span:contains(القصة) p",
            "div.singleDesc p",
            "div.story p",
            "div.postContent p"
        ))
        val tags = doc.select("div.singleInfo span:contains(النوع) a").map { it.text() }
        val rating = doc.select("div.singleInfo span:contains(التقييم) p").text()
            .replace("IMDB ", "").replace("/10", "")
            .toDoubleOrNull()?.times(1000)?.toInt()
        
        // Type detection
        val isMovie = doc.select("div.seasonEpsCont").isEmpty() &&
            !url.contains("/seasons/") &&
            !url.contains("/series/") &&
            !url.contains("/category/") &&
            !title.contains("مسلسل")
        
        return if (isMovie) {
            val watchUrl = extractMovieWatchUrl(doc)
            ParsedLoadData(
                title = title,
                posterUrl = posterUrl,
                year = year,
                plot = plot,
                tags = tags,
                rating = rating,
                isMovie = true,
                watchUrl = watchUrl,
                episodes = null
            )
        } else {
            val episodes = parseEpisodes(doc, null)
            ParsedLoadData(
                title = title,
                posterUrl = posterUrl,
                year = year,
                plot = plot,
                tags = tags,
                rating = rating,
                isMovie = false,
                watchUrl = null,
                episodes = episodes
            )
        }
    }
    
    private fun extractPosterFromLoadPage(doc: Document): String {
        val posterImg = findFirst(doc, listOf(
            "div.posterDiv img",
            "div.poster img",
            "img.poster",
            "div.single-poster img",
            "div.postDiv a div img",
            "div.postDiv img",
            ".moviePoster img"
        ))
        
        var posterUrl = posterImg?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: ""
        
        // Fallback: find image with wp-content URL
        if (posterUrl.isBlank()) {
            val contentImg = doc.select("img").firstOrNull { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                src.contains("/wp-content/uploads/") && src.length > 10
            }
            posterUrl = contentImg?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            } ?: ""
        }
        
        return fixUrl(posterUrl)
    }
    
    private fun extractMovieWatchUrl(doc: Document): String {
        // Try iframe
        var watchUrl = doc.select("iframe[name=player_iframe]").attr("src")
        
        // Fallback: onclick
        if (watchUrl.isBlank()) {
            val onClick = doc.select("ul.tabs-ul li.active").attr("onclick")
            watchUrl = Regex("""href\s*=\s*'([^']+)'""").find(onClick)?.groupValues?.get(1) ?: ""
        }
        
        // Fallback: any list item
        if (watchUrl.isBlank()) {
            doc.select("ul.tabs-ul li").forEach { li ->
                if (watchUrl.isBlank()) {
                    val onClick = li.attr("onclick")
                    watchUrl = Regex("""href\s*=\s*'([^']+)'""").find(onClick)?.groupValues?.get(1) ?: ""
                }
            }
        }
        
        return watchUrl
    }
    
    // ==================== EPISODES ====================
    
    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode> {
        val episodes = mutableListOf<ParsedEpisode>()
        
        // Detect active season
        val seasonTabs = doc.select("div.seasonDiv")
        var activeSeasonNum = 1
        
        if (seasonTabs.isNotEmpty()) {
            val activeTab = seasonTabs.find { it.hasClass("active") }
            if (activeTab != null) {
                val t = activeTab.select(".title").text()
                activeSeasonNum = Regex("""\d+""").find(t)?.value?.toIntOrNull() ?: 1
            }
        } else {
            val t = doc.select("div.seasonDiv.active .title").text()
            activeSeasonNum = Regex("""\d+""").find(t)?.value?.toIntOrNull() ?: 1
        }
        
        // Parse episodes from current page
        doc.select("div.epAll a").forEach { ep ->
            val epUrl = ep.attr("href")
            val epTitle = ep.text()
            val epNum = epTitle.replace("الحلقة", "").trim().toIntOrNull() ?: 1
            
            episodes.add(ParsedEpisode(
                url = epUrl,
                name = epTitle,
                season = seasonNum ?: activeSeasonNum,
                episode = epNum
            ))
        }
        
        return episodes.distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }
    
    /**
     * Extract season URLs for parallel fetching.
     */
    fun extractSeasonUrls(doc: Document): List<Pair<Int, String>> {
        val seasonTabs = doc.select("div.seasonDiv")
        
        return seasonTabs.filter { !it.hasClass("active") }.mapNotNull { tab ->
            val t = tab.select(".title").text()
            val sNum = Regex("""\d+""").find(t)?.value?.toIntOrNull() ?: return@mapNotNull null
            
            val pageUrl = Regex("""href\s*=\s*['"]([^'"]+)['"]""")
                .find(tab.attr("onclick"))?.groupValues?.get(1)
            
            if (pageUrl != null) {
                Pair(sNum, pageUrl)
            } else null
        }
    }
    
    // ==================== VIDEO EXTRACTION ====================
    
    override fun extractPlayerUrls(doc: Document): List<String> {
        val urls = mutableListOf<String>()
        val urlRegex = "'.*?'".toRegex()
        
        doc.select(".signleWatch ul.tabs-ul li[onclick]").forEach { li ->
            val onclick = li.attr("onclick")
            val match = urlRegex.find(onclick)
            
            if (match != null) {
                val url = match.value.replace("'", "")
                if (url.contains("arabseed")) {
                    urls.add(url)
                }
            } else {
                val dataUrl = li.attr("data-url").ifEmpty { li.attr("data-link") }
                if (dataUrl.isNotEmpty() && dataUrl.contains("arabseed")) {
                    urls.add(dataUrl)
                }
            }
        }
        
        return urls
    }
    
    // ==================== QUALITY PARSING ====================
    
    fun parseQuality(label: String): Int {
        return when {
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
