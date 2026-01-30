package com.arabseed

import com.arabseed.service.parsing.BaseParser
import com.arabseed.service.parsing.BaseParser.ParsedEpisode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * Arabseed-specific parser extending BaseParser.
 * Contains all site-specific selectors and parsing logic.
 */
class ArabseedParser : BaseParser() {
    
    override val providerName = "Arabseed"
    override val mainUrl = "https://arabseed.show"
    
    // ==================== MAIN PAGE SELECTORS ====================
    
    override val mainPageContainerSelectors = listOf(
        "div.item__contents",
        "ul.Blocks-UL > div",
        "div.Blocks-UL > div",
        "div.MovieBlock",
        "div.poster__single",
        "div.search__res__container", // Mobile container
        "div.live__search__res",      // Alternative mobile
        "div.Section",
        "div.Container",
        "div.Content",
        "main",
        "body" 
    )
    
    // Debug helper to find the real container
    override fun parseMainPage(doc: Document): List<ParsedSearchItem> {
        val url = doc.location()
        // Direct selection of items based on user report + fallbacks
        // Debug specific selectors
        Log.d(TAG, "DEBUG: div.series__box count: ${doc.select("div.series__box").size}")
        Log.d(TAG, "DEBUG: div.MovieBlock count: ${doc.select("div.MovieBlock").size}")
        Log.d(TAG, "DEBUG: ul.Blocks-UL > div count: ${doc.select("ul.Blocks-UL > div").size}")

        val items = doc.select("div.item__contents, div.MovieBlock, div.poster__single, ul.Blocks-UL > div, div.Blocks-UL > div, div.BlockItem, div.series__box").mapNotNull { element ->
            try {
                val title = extractTitle(element)
                val url = extractUrl(element)
                val poster = extractPoster(element)
                val isMovie = isMovie(element)
                
                if (url == null) {
                    Log.w(TAG, "Skipping item: URL null. Element: ${element.tagName()}.${element.className()}")
                    Log.w(TAG, "HTML of skipped item: ${element.outerHtml()}")
                    return@mapNotNull null
                }
                
                if (title.isBlank()) {
                    Log.w(TAG, "Skipping item: Title blank. URL: $url")
                    return@mapNotNull null
                }
                
                ParsedSearchItem(
                    title = title,
                    url = fixUrl(url),
                    posterUrl = fixUrl(poster),
                    isMovie = isMovie
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing item: ${e.message}")
                null
            }
        }
        
        if (items.isNotEmpty()) {
            Log.d(TAG, "[$providerName] Parsed ${items.size} items from $url using direct selection")
            return items
        } else {
            Log.w(TAG, "[$providerName] Found 0 items from $url using direct selection")
            
            // Debug: Inspect inner__contents specifically
            val innerContents = doc.selectFirst("div.inner__contents")
            if (innerContents != null) {
                val children = innerContents.children().take(10).map { "${it.tagName()}.${it.className()}" }
                Log.e(TAG, "DEBUG INNER_CONTENTS: $children")
            } else {
                Log.e(TAG, "DEBUG: div.inner__contents NOT FOUND")
            }
        }

        // Fallback to BaseParser logic if direct selection failed
        val baseItems = super.parseMainPage(doc)
        if (baseItems.isEmpty()) {
            // DEBUG: Log the structure to help find the new selector
            val divs = doc.select("div").take(20).map { "${it.tagName()}.${it.className()}" }
            Log.e(TAG, "DEBUG STRUCTURE: Found divs: $divs")
            
            // Log full HTML as requested
            Log.e(TAG, "FULL HTML DUMP: ${doc.html()}")
        }
        return baseItems
    }
    
    override fun extractTitle(element: Element): String {
        return element.select("a.movie__block").attr("title")
            .ifEmpty { element.select("h4").text() }
            .ifEmpty { element.select("h3").text() }
            .ifEmpty { element.select("div.title").text() }
            .ifEmpty { element.select("div.title___").text() }
            .ifEmpty { element.selectFirst("a")?.attr("title") ?: "" }
    }
    
    override fun extractPoster(element: Element): String {
        val img = element.selectFirst("div.post__image img")
            ?: element.selectFirst("img.imgOptimzer")
            ?: element.selectFirst("div.Poster img")
            ?: element.selectFirst("div.image__poster img")
            ?: element.selectFirst("img")
        
        return img?.attr("data-src")
            ?.ifBlank { img.attr("data-image") }
            ?.ifBlank { img.attr("src") }
            ?: ""
    }
    
    override fun extractUrl(element: Element): String? {
        val url = element.select("a.movie__block").attr("href")
            .ifBlank { element.select("a[href]").attr("href") }
            .ifBlank { element.attr("href") }
            
        return url.ifBlank { null }
    }
    
    override fun isMovie(element: Element): Boolean {
        // New logic: check category text
        val category = element.select("div.post__category").text()
        if (category.isNotBlank()) {
             return !category.contains("مسلسلات") && !category.contains("أنمي")
        }
        
        // Fallback
        val oldCategory = element.select("span.category").text()
        return !oldCategory.contains("مسلسلات")
    }
    
    // ==================== LOAD PAGE ====================
    
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
            Log.e(TAG, "HTML_DUMP_START_BODY")
            Log.e(TAG, doc.body().outerHtml().take(20000))
            Log.e(TAG, "HTML_DUMP_END_BODY")
            return null
        }
        
        // Poster extraction
        val posterUrl = extractPosterFromLoadPage(doc)
        
        // Metadata
        // Metadata
        var year = doc.select("div.singleInfo span:contains(السنة) a").text().toIntOrNull()
        if (year == null) {
            // New info__area selector
            year = doc.select("div.info__area li:has(span:contains(سنة العرض)) ul.tags__list a").text().toIntOrNull()
        }
        if (year == null) {
            year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()
        }

        var plot = findText(doc, listOf(
            "div.singleInfo span:contains(القصة) p",
            "div.singleDesc p",
            "div.story p",
            "div.post__story p",
            "div.postContent p"
        ))
        if (plot.isBlank()) {
            plot = doc.select("meta[property='og:description']").attr("content")
        }
        if (plot.isBlank()) {
            plot = doc.select("meta[name='description']").attr("content")
        }

        var tags = doc.select("div.singleInfo span:contains(النوع) a").map { it.text() }
        if (tags.isEmpty()) {
            // New info__area selector
            tags = doc.select("div.info__area li:has(span:contains(نوع العرض)) ul.tags__list a").map { it.text() }
        }
        
        // Country
        val country = doc.select("div.info__area li:has(span:contains(بلد العرض)) ul.tags__list a").text()
        if (country.isNotBlank()) {
            // Append to tags if found
            tags = tags + country
        }

        val rating = doc.select("div.singleInfo span:contains(التقييم) p").text()
            .replace("IMDB ", "").replace("/10", "")
            .toDoubleOrNull()?.times(1000)?.toInt()
        
        // Type detection
        val hasEpisodes = doc.select("div.epAll, div.episodes-list, ul.episodes, div.seasonDiv, div.seasonEpsCont, div.seasons--list, a.episode__item, div.series-episodes, div#seasons__list, div.list__sub__cats, div.epi__num, ul.episodes__list").isNotEmpty()
        
        val seriesKeywords = listOf("انمي", "مسلسل", "موسم", "برنامج", "سلسلة")
        val isSeriesTitle = seriesKeywords.any { title.contains(it, ignoreCase = true) }
        
        val isSeriesUrl = url.contains("/seasons/") || 
                         url.contains("/series/") || 
                         url.contains("/selary") ||
                         url.contains("/anime/")
        
        val isMovie = !hasEpisodes && !isSeriesUrl && !isSeriesTitle
            
        Log.d(TAG, "[parseLoadPageData] Extracted: title='$title', year=$year, plotLength=${plot.length}, hasEpisodes=$hasEpisodes, isSeriesUrl=$isSeriesUrl, isSeriesTitle=$isSeriesTitle, isMovie=$isMovie")
        
        // Dump HTML for debugging as requested
        Log.e(TAG, "HTML_DUMP_START")
        Log.e(TAG, doc.outerHtml())
        Log.e(TAG, "HTML_DUMP_END")
        
        return if (isMovie) {
            val watchUrl = extractMovieWatchUrl(doc)
            Log.d(TAG, "[parseLoadPageData] Movie detected. WatchUrl='$watchUrl'")
            
            if (watchUrl.isBlank()) {
                Log.w(TAG, "[parseLoadPageData] Watch URL is empty! Dumping BODY (first 200k chars)...")
                Log.w(TAG, doc.body().outerHtml().take(200000))
            }
            
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
            Log.d(TAG, "[parseLoadPageData] Series detected. Episodes count=${episodes.size}")
            
            if (episodes.isEmpty()) {
                 Log.w(TAG, "[parseLoadPageData] No episodes found! Dumping HTML (first 20k chars)...")
                 Log.w(TAG, doc.outerHtml().take(20000))
            }
            
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
        // Try iframe with specific name
        var watchUrl = doc.select("iframe[name=player_iframe]").attr("src")
        
        // Try any iframe that might be the player
        if (watchUrl.isBlank()) {
             watchUrl = doc.select("div.Container iframe, div.Content iframe, div.embed-player iframe")
                 .attr("src")
        }

        // Fallback: Watch Button (critical for loadLinks)
        if (watchUrl.isBlank()) {
            watchUrl = doc.select("a.watch__btn").attr("href")
            if (watchUrl.isNotBlank()) Log.d(TAG, "Found watch button URL: $watchUrl")
        }

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
    
    // ==================== EPISODES ====================
    
    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode> {
        val episodes = mutableListOf<ParsedEpisode>()
        
        // Detect active season
        var activeSeasonNum = 1
        // Check for the specific ID provided by user or class fallback
        val seasonList = doc.selectFirst("div#seasons__list, div.list__sub__cats")
        
        if (seasonList != null) {
            val selected = seasonList.selectFirst("li.selected")
            if (selected != null) {
                // Extract number from "الموسم الثالث" etc
                val t = selected.text()
                activeSeasonNum = parseSeasonNumber(t)
            }
        } else {
            // Fallback to old selectors
            val seasonTabs = doc.select("div.seasonDiv, div.seasons--list")
            if (seasonTabs.isNotEmpty()) {
                val activeTab = seasonTabs.find { it.hasClass("active") }
                if (activeTab != null) {
                    activeSeasonNum = Regex("""\d+""").find(activeTab.text())?.value?.toIntOrNull() ?: 1
                }
            }
        }
        
        // Parse episodes with new selector
        // User provided: <ul class="episodes__list boxs__wrapper ..."> <li> <a> ...
        // Also try finding by inner content "div.epi__num" which seems unique to episodes
        // using :has() is cleaner than detecting parents
        var epElements = doc.select("ul.episodes__list li a, a:has(div.epi__num)")
        
        if (epElements.isEmpty()) {
            // retain old selectors as fallback
             epElements = doc.select("div.epAll a, div.episodes-list a, ul.episodes li a, a.episode__item, div.series-episodes a")
        }
        
        epElements.forEach { ep ->
            val epUrl = ep.attr("href")
            val epName = ep.text()
            
            // User snippet shows: <div class="epi__num"> الحلقة <b>12</b> </div>
            val numText = ep.select("div.epi__num b").text()
            val epNum = if (numText.isNotEmpty()) {
                numText.trim().toIntOrNull() ?: 0
            } else {
                epName.replace("الحلقة", "").trim().toIntOrNull() ?: 0
            }
            
            episodes.add(ParsedEpisode(
                url = epUrl,
                name = epName,
                season = seasonNum ?: activeSeasonNum,
                episode = epNum
            ))
        }
        
        return episodes.distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }
    
    private fun parseSeasonNumber(text: String): Int {
        if (text.contains("الأول")) return 1
        if (text.contains("الثاني")) return 2
        if (text.contains("الثالث")) return 3
        if (text.contains("الرابع")) return 4
        if (text.contains("الخامس")) return 5
        if (text.contains("السادس")) return 6
        if (text.contains("السابع")) return 7
        if (text.contains("الثامن")) return 8
        if (text.contains("التاسع")) return 9
        if (text.contains("العاشر")) return 10
        return Regex("""\d+""").find(text)?.value?.toIntOrNull() ?: 1
    }

    /**
     * Extract season URLs for parallel fetching.
     */
    fun extractSeasonUrls(doc: Document): List<Pair<Int, String>> {
        val urls = mutableListOf<Pair<Int, String>>()
        
        // New selector from user
        doc.select("div#seasons__list ul li, div.list__sub__cats ul li").forEach { li ->
            if (li.hasClass("selected")) return@forEach
            
            // Try to find a link inside
            var pageUrl = li.select("a").attr("href")
            if (pageUrl.isBlank()) {
                // Check onclick
                pageUrl = Regex("""href\s*=\s*['"]([^'"]+)['"]""").find(li.attr("onclick"))?.groupValues?.get(1) ?: ""
            }
            
            if (pageUrl.isNotBlank()) {
                 val t = li.text()
                 val sNum = parseSeasonNumber(t)
                 urls.add(Pair(sNum, pageUrl))
            }
        }
        
        if (urls.isNotEmpty()) return urls
        
        // Fallback to old logic
        val seasonTabs = doc.select("div.seasonDiv, div.seasons--list")
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
    
    fun parseEpisodesFromAjax(doc: Document, seasonNum: Int): List<ParsedEpisode> {
        return doc.select("a").mapNotNull { ep ->
            val epUrl = ep.attr("href")
            val epTitle = ep.text()
            if (epUrl.isBlank()) return@mapNotNull null
            
            val epNum = epTitle.replace("الحلقة", "").trim().toIntOrNull() ?: 0
            
            ParsedEpisode(
                url = epUrl,
                name = epTitle,
                season = seasonNum,
                episode = epNum
            )
        }
    }
    
    // ==================== SEASONS (AJAX SUPPORT) ====================
    
    data class SeasonData(val season: Int, val postId: String)
    
    fun parseSeasonsWithPostId(doc: Document): List<SeasonData> {
        val list = mutableListOf<SeasonData>()
        
        // Old selector
        doc.select("div.SeasonsListHolder ul > li").forEach { li ->
            val season = li.attr("data-season").toIntOrNull()
            val postId = li.attr("data-id")
            if (season != null && postId.isNotBlank()) {
                list.add(SeasonData(season, postId))
            }
        }
        
        // New selector
        if (list.isEmpty()) {
            doc.select("div#seasons__list ul li, div.list__sub__cats ul li").forEach { li ->
                val termId = li.attr("data-term")
                if (termId.isNotBlank()) {
                    val sNum = parseSeasonNumber(li.text())
                    list.add(SeasonData(sNum, termId))
                }
            }
        }
        
        return list.distinctBy { it.season }
    }
    
    // ==================== SERVER LIST PARSING ====================
    
    data class ServerItem(val name: String, val url: String)
    
    fun parseServerList(doc: Document): List<ServerItem> {
        val servers = mutableListOf<ServerItem>()
        // Logic from reference: select "ul > li[data-link], ul > h3"
        // But we need to handle the grouping or just extract valid links
        // The reference groups them by quality (H3 text).
        // For simplicity and robustness, we can just extract all "li[data-link]"
        // and append the quality from the preceding H3 if we want, OR just extract all links.
        // The reference filters for "سيد" (Seed) in the name for special handling.
        
        doc.select("ul > li[data-link]").forEach { li ->
             val url = li.attr("data-link").ifBlank { li.attr("data-url") }
             val name = li.text().trim()
             if (url.isNotBlank()) {
                 servers.add(ServerItem(name, url))
             }
        }
        
        // Also check .watchBTn if not found in list (though reference does this separately)
        return servers
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
