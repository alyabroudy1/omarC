package com.arabseed

import android.content.Context
import com.lagradost.cloudstream3.*
import com.arabseed.service.ProviderHttpService
import com.arabseed.service.ProviderSessionManager
import com.arabseed.service.ProviderLogger
import com.arabseed.service.strategy.VideoSource
import com.arabseed.utils.ActivityProvider
import com.arabseed.utils.PluginContext
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Arabseed provider integrated with the new ProviderHttpService.
 * 
 * ## Features:
 * - Automatic Cloudflare challenge solving
 * - Cookie persistence across app restarts
 * - Video sniffing with JWPlayer extraction
 * - Comprehensive logging
 * 
 * ## Built-in Provider:
 * This is a built-in provider for CloudStream.
 * The service handles all CF challenges automatically.
 */
class Arabseed : MainAPI() {
    override var lang = "ar"
    override var name = "Arabseed"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    
    companion object {
        private const val TAG = "Arabseed"
        private const val FALLBACK_DOMAIN = "https://arabseed.show"
        
        // Lazy-initialized service (needs context)
        private var _httpService: ProviderHttpService? = null
        
        fun getHttpService(context: Context): ProviderHttpService {
            return _httpService ?: ProviderHttpService.create(
                context = context,
                providerName = "Arabseed",
                userAgent = ProviderSessionManager.UNIFIED_USER_AGENT,
                fallbackDomain = FALLBACK_DOMAIN
            ).also { _httpService = it }
        }
    }
    
    // Get service using PluginContext
    private val httpService: ProviderHttpService
        get() {
            val ctx = PluginContext.context
                ?: throw IllegalStateException("No application context available")
            
            // Initialize ActivityProvider for Dialog support if not yet initialized
            (ctx as? android.app.Application)?.let { app ->
                ActivityProvider.init(app)
            }
            
            return getHttpService(ctx)
        }
    
    override var mainUrl: String
        get() = httpService.currentDomain
        set(value) { httpService.updateDomain(value) }

    
    // ========== MAIN PAGE ==========
    
    override val mainPage = mainPageOf(
        "/movies/?offset=" to "Movies",
        "/series/?offset=" to "Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "[getMainPage] Fetching: ${request.data}$page")
        
        // Initialize on first call (loads from disk, fetches GitHub for latest domain)
        if (!httpService.isInitialized) {
            httpService.initialize()
        }
        
        val pageUrl = "$mainUrl${request.data}$page"
        val doc = httpService.getDocument(pageUrl)
            ?: throw ErrorLoadingException("Failed to load main page")
        
        // Try multiple selectors for the container
        var elements = doc.select("ul.Blocks-UL > div")
        if (elements.isEmpty()) elements = doc.select("div.Blocks-UL > div")
        if (elements.isEmpty()) elements = doc.select("div.MovieBlock")
        if (elements.isEmpty()) elements = doc.select("div.poster__single") // Found in CSS logs
        if (elements.isEmpty()) elements = doc.select("div:has(div.post__image)") // Generic BEM fallback
        
        Log.i(TAG, "[getMainPage] Found ${elements.size} elements using selectors.")
        if (elements.isEmpty()) {
            Log.e(TAG, ">>> [getMainPage] PARSING FAILED. HTML Structure Analysis:")
            val body = doc.body()
            
            // 1. Log direct children of body
            val directChildren = body.children().map { "${it.tagName()}.${it.className()}" }.joinToString(", ")
            Log.e(TAG, "Body Children: $directChildren")
            
            // 2. Search for potential containers
            val potentialContainers = doc.select("div[class*=Block], ul[class*=Block], div[class*=Movie], div[class*=Item], div[class*=Post]")
            Log.e(TAG, "Potential Containers found: ${potentialContainers.size}")
            potentialContainers.take(5).forEach { 
                Log.e(TAG, "Container: ${it.tagName()}.${it.className()} (Children: ${it.childrenSize()})")
            }
            
            // 3. Search for 'MovieBlock' string in raw HTML to see if it exists
            val rawHtml = doc.html()
            val index = rawHtml.indexOf("MovieBlock")
            if (index != -1) {
                Log.e(TAG, "'MovieBlock' found at index $index. Context: ${rawHtml.substring((index-50).coerceAtLeast(0), (index+100).coerceAtMost(rawHtml.length))}")
            } else {
                 Log.e(TAG, "'MovieBlock' NOT found in raw HTML")
            }
            // 4. Search for 'poster' string
            val posterIndex = rawHtml.indexOf("poster")
             if (posterIndex != -1) {
                Log.e(TAG, "'poster' found at index $posterIndex. Context: ${rawHtml.substring((posterIndex-50).coerceAtLeast(0), (posterIndex+100).coerceAtMost(rawHtml.length))}")
            }
            
            // Dump broader chunk to see structure (3000 chars)
            Log.e(TAG, "HTML Header Dump: ${doc.html().take(2000)}")
        }

        val list = elements.mapNotNull { element -> 
             element.toSearchResponse().also { 
                 if (it == null) Log.w(TAG, "[getMainPage] Failed to parse element: ${element.html().take(100)}...") 
             }
        }
        
        return newHomePageResponse(request.name, list)
    }

    // ========== SEARCH ==========
    
    override suspend fun search(query: String): List<SearchResponse> {
        val list = arrayListOf<SearchResponse>()
        
        // Initialize service if needed
        if (!httpService.isInitialized) {
            httpService.initialize()
        }
        
        // Search both series and movies via POST
        listOf("series", "movies").forEach { type ->
            try {
                val doc = httpService.postDocument(
                    "$mainUrl/wp-content/themes/Elshaikh2021/Ajaxat/SearchingTwo.php",
                    data = mapOf("search" to query, "type" to type),
                    referer = mainUrl
                )
                doc?.select("ul.Blocks-UL > div")?.mapNotNull { element ->
                    element.toSearchResponse()?.let { list.add(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search error for type=$type: ${e.message}")
            }
        }
        return list
    }

    // ========== LOAD ==========
    
    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "[load] Loading: $url")
        
        val doc = httpService.getDocument(url)
            ?: throw ErrorLoadingException("Failed to load page")
        
        // Improved title selector with fallback
        var title = doc.select("div.title h1").text()
        if (title.isBlank()) {
            title = doc.select("h1.postTitle").text()
        }
        if (title.isBlank()) {
            title = doc.select("div.h1-title h1").text()
        }
        // Fallback to page title (metadata) if structured selection fails
        if (title.isBlank()) {
            title = doc.title().replace(" - عرب سيد", "")
                .replace("مترجم اون لاين", "")
                .trim()
        }
        
        if (title.isBlank()) {
            ProviderLogger.e(TAG, "load", "Failed to parse title", null,
                "url" to url,
                "htmlStart" to doc.html().take(2000)
            )
            throw ErrorLoadingException("Failed to parse page content")
        }

        // Fix: Check data-src first (lazy loading), then src
        // Include toSearchResponse selector as fallback
        var posterImg = doc.selectFirst("div.posterDiv img") 
            ?: doc.selectFirst("div.poster img")
            ?: doc.selectFirst("img.poster")
            ?: doc.selectFirst("div.single-poster img")
            ?: doc.selectFirst("div.postDiv a div img")  // toSearchResponse selector
            ?: doc.selectFirst("div.postDiv img")
            ?: doc.selectFirst(".moviePoster img")
        
        // Extract URL from poster img
        var posterUrl = if (posterImg != null) {
            fixUrl(posterImg.attr("data-src").ifBlank { posterImg.attr("src") })
        } else ""
        
        // Fallback: If posterUrl is still empty, find first image with valid content URL
        if (posterUrl.isBlank()) {
            val contentImg = doc.select("img").firstOrNull { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                src.contains("/wp-content/uploads/") && src.length > 10
            }
            if (contentImg != null) {
                posterUrl = fixUrl(contentImg.attr("data-src").ifBlank { contentImg.attr("src") })
                Log.d(TAG, "[load] Poster found via content URL fallback: ${posterUrl.take(80)}...")
            }
        }
        
        // Debug: Log when poster is still empty after all attempts
        if (posterUrl.isBlank()) {
            val allImgs = doc.select("img").take(5).map { img ->
                "src=${img.attr("src").take(50)}, data-src=${img.attr("data-src").take(50)}, class=${img.attr("class")}"
            }
            Log.w(TAG, "[load] Poster URL is empty! First 5 images found: $allImgs")
            Log.w(TAG, "[load] HTML_DUMP (poster area): ${doc.select("div[class*=poster], div[class*=Poster]").html().take(1000)}")
        } else {
            Log.d(TAG, "[load] Poster URL found: ${posterUrl.take(80)}...")
        }

        val year = doc.select("div.singleInfo span:contains(السنة) a").text().toIntOrNull()
        val plot = doc.select("div.singleInfo span:contains(القصة) p").text()
            .ifBlank { doc.select("div.singleDesc p").text() }
            .ifBlank { doc.select("div.story p").text() }
            .ifBlank { doc.select("div.postContent p").text() }
        val tags = doc.select("div.singleInfo span:contains(النوع) a").map { it.text() }
        val rating = doc.select("div.singleInfo span:contains(التقييم) p").text()
            .replace("IMDB ", "").replace("/10", "").toDoubleOrNull()?.times(1000)?.toInt()
        
        val isMovie = doc.select("div.seasonEpsCont").isEmpty() && 
            !url.contains("/seasons/") && 
            !url.contains("/series/") &&
            !url.contains("/category/") && // CRITICAL FIX: Don't treat category pages as movies
            !title.contains("مسلسل")
        
        ProviderLogger.d(TAG, "load", "Page type detection",
            "isMovie" to isMovie,
            "url" to url,
            "hasSeasonEpsCont" to !doc.select("div.seasonEpsCont").isEmpty(),
            "isCategory" to url.contains("/category/")
        )
        
        return if (isMovie) {
            // New structure: generic iframe or tabs with onclick
            var watchUrl = doc.select("iframe[name=player_iframe]").attr("src")
            
            // Fallback: Extract from onclick="player_iframe.location.href = '...'"
            if (watchUrl.isBlank()) {
                 val onClick = doc.select("ul.tabs-ul li.active").attr("onclick")
                 watchUrl = Regex("""href\s*=\s*'([^']+)'""").find(onClick)?.groupValues?.get(1) ?: ""
            }
             
            // Fallback 2: Any list item
            if (watchUrl.isBlank()) {
                 doc.select("ul.tabs-ul li").forEach { li ->
                     if (watchUrl.isBlank()) {
                         val onClick = li.attr("onclick")
                         watchUrl = Regex("""href\s*=\s*'([^']+)'""").find(onClick)?.groupValues?.get(1) ?: ""
                     }
                 }
            }

            if (watchUrl.isBlank() || plot.isBlank()) {
                 ProviderLogger.w(TAG, "load", "Missing movie data",
                     "title" to title,
                     "watchUrlEmpty" to watchUrl.isBlank(),
                     "plotFound" to !plot.isBlank()
                 )
            }
            
            ProviderLogger.d(TAG, "load", "Movie parsed",
                "title" to title,
                "watchUrl" to watchUrl
            )
            
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.posterHeaders = httpService.getImageHeaders()
            }
        } else {
            val episodes = ArrayList<Episode>()
            
            // 1. Parse episodes from the current page (Active Season)
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

            // Current page episodes
            doc.select("div.epAll a").forEach { ep ->
                val epUrl = ep.attr("href")
                val epTitle = ep.text()
                val epNum = epTitle.replace("الحلقة", "").trim().toIntOrNull() ?: 1
                
                episodes.add(newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = activeSeasonNum
                    this.episode = epNum
                })
            }

            // 2. Fetch other seasons in parallel (Like V1)
            if (seasonTabs.isNotEmpty()) {
                 coroutineScope {
                     seasonTabs.filter { !it.hasClass("active") }.map { tab ->
                         async {
                             val t = tab.select(".title").text()
                             val sNum = Regex("""\d+""").find(t)?.value?.toIntOrNull() ?: return@async null
                             
                             // Extract URL
                             var pageUrl = Regex("""href\s*=\s*['"]([^'"]+)['"]""").find(tab.attr("onclick"))?.groupValues?.get(1)
                             if (pageUrl != null) {
                                 if (pageUrl.startsWith("/")) pageUrl = "$mainUrl$pageUrl"
                                 
                                 // Fetch page
                                 try {
                                     val sDoc = httpService.getDocument(pageUrl) ?: return@async null
                                     val sEpisodes = sDoc.select("div.epAll a").map { ep ->
                                         val epUrl = ep.attr("href")
                                         val epTitle = ep.text()
                                         val epNum = epTitle.replace("الحلقة", "").trim().toIntOrNull() ?: 1
                                         
                                         newEpisode(epUrl) {
                                             this.name = epTitle
                                             this.season = sNum
                                             this.episode = epNum
                                         }
                                     }
                                     return@async sEpisodes
                                 } catch (e: Exception) {
                                     return@async null
                                 }
                             }
                             return@async null
                         }
                     }.awaitAll().filterNotNull().flatten().forEach { episodes.add(it) }
                 }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { "${it.season}:${it.episode}" }.sortedWith(compareBy({ it.season }, { it.episode }))) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.posterHeaders = httpService.getImageHeaders()
                
                // Populate seasonNames from the fetched episodes
                val sNames = episodes.mapNotNull { it.season }.distinct().sorted()
                    .map { SeasonData(it, "الموسم $it") }
                if (sNames.isNotEmpty()) {
                    this.seasonNames = sNames
                }
            }
        }
    }

    // ========== LOAD LINKS (V1 Flow with VideoSniffingStrategy) ==========
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "[loadLinks] Starting V1-style extraction for: $data")
        
        // 1. Fetch the episode/movie page
        val doc = httpService.getDocument(data)
        if (doc == null) {
            Log.w(TAG, "[loadLinks] Failed to fetch page: $data")
            return false
        }
        
        // 2. Extract player URLs from onclick (V1 logic)
        val urlRegex = "'.*?'".toRegex()
        val elements = doc.select(".signleWatch ul.tabs-ul li[onclick]")
        
        Log.i(TAG, "[loadLinks] Found ${elements.size} watch servers")
        
        // Log all available servers first
        elements.forEachIndexed { index, li ->
            val serverTitle = li.select("a").text().ifBlank { "Server ${index + 1}" }
            val onclick = li.attr("onclick")
            val urlMatch = urlRegex.find(onclick)?.value?.replace("'", "") ?: "unknown"
            Log.i(TAG, "[loadLinks] Server #${index + 1}: '$serverTitle' -> ${urlMatch.take(80)}...")
        }
        
        var foundVideos = false
        var serverIndex = 0
        
        for (li in elements) {
            serverIndex++
            val serverTitle = li.select("a").text().ifBlank { "Server $serverIndex" }
            var playerUrl: String? = null
            val onclickAttr = li.attr("onclick")
            val match = urlRegex.find(onclickAttr)
            
            if (match != null) {
                playerUrl = match.value.replace("'", "")
            } else {
                playerUrl = li.attr("data-url").ifEmpty { li.attr("data-link") }
            }
            
            if (!playerUrl.isNullOrEmpty() && playerUrl.contains("arabseed")) {
                Log.i(TAG, "[loadLinks] Trying Server #$serverIndex '$serverTitle'...")
                
                // 3. Sniff the player URL (NOT the episode page)
                val videos = httpService.sniffVideos(playerUrl)
                
                if (videos.isNotEmpty()) {
                    Log.i(TAG, "[loadLinks] Server #$serverIndex '$serverTitle' -> Found ${videos.size} videos!")
                    foundVideos = true
                    
                    videos.forEach { source ->
                        val quality = parseQuality(source.label)
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - ${source.label}",
                                url = source.url,
                                type = if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = quality
                                this.headers = source.headers + httpService.getImageHeaders()
                            }
                        )
                    }
                    break // Found videos, stop trying other servers
                } else {
                    Log.w(TAG, "[loadLinks] Server #$serverIndex '$serverTitle' -> No videos found")
                }
            } else {
                Log.w(TAG, "[loadLinks] Server #$serverIndex '$serverTitle' -> Invalid URL: $playerUrl")
            }
        }
        
        // 4. Fallback: Direct sniff if no onclick elements found
        if (!foundVideos && elements.isEmpty()) {
            Log.w(TAG, "[loadLinks] No onclick elements, trying direct sniff on page")
            val videos = httpService.sniffVideos(data)
            videos.forEach { source ->
                val quality = parseQuality(source.label)
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - ${source.label}",
                        url = source.url,
                        type = if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = quality
                        this.headers = source.headers + httpService.getImageHeaders()
                    }
                )
            }
            foundVideos = videos.isNotEmpty()
        }
        
        return foundVideos
    }

    // ========== HELPERS ==========
    
    private fun Element.toSearchResponse(): SearchResponse? {
        val title = select("h4").text()
            .ifEmpty { select("h3").text() }
            .ifEmpty { select("div.title").text() }
            .ifEmpty { select("a").attr("title") } // Fallback to anchor title
            .ifEmpty { return null }
        val url = select("a").attr("href").ifEmpty { return null }
        val posterUrl = select("img.imgOptimzer").attr("data-image")
            .ifEmpty { select("div.Poster img").attr("data-src") }
        val tvType = if (select("span.category").text().contains("مسلسلات")) TvType.TvSeries else TvType.Movie
        
        return newMovieSearchResponse(
            title,
            url,
            tvType
        ) {
            this.posterUrl = posterUrl
            this.posterHeaders = httpService.getImageHeaders()
        }
    }
    
    private fun fixUrl(url: String): String {
        // Just return as is - don't force www. which breaks recent domains
        return url
    }
    
    private fun parseQuality(label: String): Int {
        return when {
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
