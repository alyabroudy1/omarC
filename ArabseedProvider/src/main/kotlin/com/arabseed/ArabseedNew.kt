package com.arabseed

import android.content.Context
import com.arabseed.utils.ActivityProvider
import com.arabseed.utils.PluginContext
import com.arabseed.service.ProviderConfig
import com.arabseed.service.ProviderHttpService
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Arabseed provider V2 - Ported Extension
 */
class ArabseedV2 : MainAPI() {
    
    override var mainUrl = "https://arabseed.show"
    override var name = "ArabseedV2"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.AsianDrama)
    
    override val mainPage = mainPageOf(
        "/movies-1/" to "أفلام",
        "/series-1/" to "مسلسلات",
        "/anime-1/" to "أنمي",
        "/asian-drama/" to "دراما آسيوية"
    )
    
    companion object {
        private const val TAG = "ArabseedV2"
        private const val GITHUB_CONFIG = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/arabseed.json"
        // EXACT UA from FaselHD
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
    }
    
    private val parser = ArabseedParser()
    
    private val http by lazy {
        ProviderHttpService.create(
            context = PluginContext.context!!,
            config = ProviderConfig(
                name = name,
                fallbackDomain = "arabseed.show",
                githubConfigUrl = GITHUB_CONFIG,
                userAgent = USER_AGENT,
                syncWorkerUrl = "https://omarstreamcloud.alyabroudy1.workers.dev",
                skipHeadless = true
            ),
            parser = parser,
            activityProvider = { ActivityProvider.currentActivity }
        )
    }
    
    // ==================== MAIN PAGE ====================
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d(TAG, "[getMainPage] ${request.name} page=$page")
        
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }
        
        val items = http.getMainPage(url)
        
        if (items.isEmpty()) return null
        
        val searchResponses = items.map { item ->
            if (item.isMovie) {
                newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = http.getImageHeaders()
                }
            } else {
                newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = http.getImageHeaders()
                }
            }
        }
        
        return newHomePageResponse(request.name, searchResponses)
    }
    
    // ==================== SEARCH ====================
    
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "[search] query=$query")
        
        // Simulating reference: POST to /SearchingTwo.php for both "movies" and "series"
        val types = listOf("movies", "series")
        
        return coroutineScope {
            types.map { type ->
                async {
                    val doc = http.post(
                        url = "$mainUrl/wp-content/themes/Elshaikh2021/Ajaxat/SearchingTwo.php",
                        data = mapOf("search" to query, "type" to type),
                        referer = mainUrl
                    )
                    
                    // Use parser to extract items from the returned HTML document
                    doc?.let { parser.parseSearch(it) } ?: emptyList()
                }
            }.awaitAll().flatten().map { item -> // item is ParsedSearchItem
                if (item.isMovie) {
                    newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                        this.posterUrl = item.posterUrl
                        this.posterHeaders = http.getImageHeaders()
                    }
                } else {
                    newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                        this.posterUrl = item.posterUrl
                        this.posterHeaders = http.getImageHeaders()
                    }
                }
            }
        }
    }
    
    // ==================== LOAD ====================
    
    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "[load] $url")
        
        val doc = http.getDocument(url, checkDomainChange = true) ?: return null
        Log.d(TAG, "[load] Document size: ${doc.html().length}")
        
        val data = parser.parseLoadPageData(doc, url)
        
        if (data == null) {
             Log.e(TAG, "[load] Parsed data is NULL")
             return null
        }
        
        Log.d(TAG, "[load] Parsed data: title='${data.title}', isMovie=${data.isMovie}")
        
        return if (data.isMovie) {
            newMovieLoadResponse(data.title, url, TvType.Movie, data.watchUrl ?: url) {
                this.posterUrl = data.posterUrl
                this.year = data.year
                this.plot = data.plot
                this.tags = data.tags
                this.posterHeaders = http.getImageHeaders()
            }
        } else {
            // Reference logic: Check season list, if exists, fetch episodes via AJAX
            val seasonData = parser.parseSeasonsWithPostId(doc)
            val episodes = mutableListOf<ParsedEpisode>()
            
            if (seasonData.isNotEmpty()) {
                // Fetch episodes for each season via AJAX
                coroutineScope {
                    val ajaxEpisodes = seasonData.map { s ->
                        async {
                            val epDoc = http.post(
                                url = "$mainUrl/wp-content/themes/Elshaikh2021/Ajaxat/Single/Episodes.php",
                                data = mapOf("season" to s.season.toString(), "post_id" to s.postId),
                                referer = url
                            )
                            epDoc?.let { parser.parseEpisodesFromAjax(it, s.season) } ?: emptyList()
                        }
                    }.awaitAll().flatten()
                    episodes.addAll(ajaxEpisodes)
                }
            }
            
            // ALWAYS parse from DOM as well to ensure we check the current page (fallback & redundancy)
            val domEpisodes = parser.parseEpisodes(doc, null)
            if (domEpisodes.isNotEmpty()) {
                 episodes.addAll(domEpisodes)
            }

            val convertedEpisodes = episodes.distinctBy { "${it.season}:${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))
                .map { ep ->
                    newEpisode(ep.url) {
                        this.name = ep.name
                        this.season = ep.season
                        this.episode = ep.episode
                    }
                }
                
            val seasonNames = convertedEpisodes.mapNotNull { it.season }.distinct().sorted()
                .map { SeasonData(it, "الموسم $it") }

            newTvSeriesLoadResponse(data.title, url, TvType.TvSeries, convertedEpisodes) {
                this.posterUrl = data.posterUrl
                this.year = data.year
                this.plot = data.plot
                this.tags = data.tags
                this.posterHeaders = http.getImageHeaders()
                if (seasonNames.isNotEmpty()) {
                    this.seasonNames = seasonNames
                }
            }
        }
    }
    
    // ==================== LOAD LINKS ====================
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "[loadLinks] $data")
        
        // 1. Get Main Doc or Watch Doc
        var doc = http.getDocument(data) ?: return false
        
        // If we are already on the watch page (because load passed the watch URL), we might not find watchBTn
        // Check if we have server list or iframe
        var isWatchPage = doc.select("ul > li[data-link], ul > h3").isNotEmpty() || 
                         doc.select("iframe[name=player_iframe]").isNotEmpty()
        
        var watchDoc = if (isWatchPage) doc else null
        
        if (watchDoc == null) {
            val watchUrl = doc.select("a.watch__btn").attr("href")
            if (watchUrl.isNotBlank()) {
                Log.d(TAG, "Found watch button on main page, following to: $watchUrl")
                watchDoc = http.getDocument(watchUrl)
            } else {
                 Log.w(TAG, "No watch button found and not a watch page")
            }
        }
        
        if (watchDoc == null) return false
        
        // 3. Parse Links simulating reference logic (group by H3 headers/Qualities)
        val indexOperators = mutableListOf<Int>()
        val elements = watchDoc.select("ul > li[data-link], ul > h3")
        
        elements.forEachIndexed { index, element ->
            if (element.`is`("h3")) {
                indexOperators.add(index)
            }
        }
        
        val watchLinks = if (indexOperators.isNotEmpty()) {
            indexOperators.mapIndexed { i, index ->
                val endIndex = if (i != indexOperators.size - 1) indexOperators[i + 1] else elements.size
                val qualityText = elements[index].text()
                val quality = Regex("""\d+""").find(qualityText)?.value?.toIntOrNull() ?: 0
                val links = elements.subList(index + 1, endIndex).filter { !it.`is`("h3") }
                quality to links
            }
        } else {
             listOf(0 to elements.filter { !it.`is`("h3") })
        }
        
        var found = false
        
        watchLinks.forEach { (quality, links) ->
            links.forEach { linkElement ->
                val rawUrl = linkElement.attr("data-link").ifBlank { linkElement.attr("data-url") }
                val linkUrl = fixUrl(rawUrl)
                val linkName = linkElement.text()
                
                if (linkUrl.isNotBlank()) {
                     if (linkName.contains("سيد")) {
                         // Special handling for ArabSeed files
                         // Reference fetches iframe and selects "source"
                         val srcDoc = http.getDocument(linkUrl, headers = mapOf("Referer" to watchDoc.location()))
                         val src = srcDoc?.select("source")?.attr("src")
                         
                         if (!src.isNullOrBlank()) {
                             callback(
                                 newExtractorLink(
                                     source = name,
                                     name = "Arab Seed",
                                     url = src,
                                     type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                 ) {
                                     this.referer = ""
                                     this.quality = quality
                                 }
                             )
                             found = true
                         }
                         // Also loadExtractor as backup/alternative? Reference does BOTH.
                         loadExtractor(linkUrl, data, subtitleCallback) { link ->
                             callback(link)
                             found = true
                         }
                     } else {
                         loadExtractor(linkUrl, data, subtitleCallback) { link ->
                             // Inject quality if missing
                             // We can't easily modify ExtractorLink, but we pass it through
                             callback(link)
                             found = true
                         }
                     }
                }
            }
        }
        
        return found
    }
}
