package com.arabseed

import android.content.Context
import com.arabseed.utils.ActivityProvider
import com.arabseed.utils.PluginContext
import com.cloudstream.shared.ProviderConfig
import com.cloudstream.shared.ProviderHttpService
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Arabseed provider V2 - Now a thin layer using ProviderHttpService.
 * All HTTP, cookie, CF, and parsing logic is handled by the shared infrastructure.
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
        // EXACT UA from FaselHD's ProviderSessionManager.kt (Line 75)
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
        
        val items = http.search(query, "/find/$query/")
        
        return items.map { item ->
            if (item.isMovie) {
                newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = http.getImageHeaders()
                }
            } else {
                // Search usually returns mixed results, if we can't be sure, default to movie or try to infer
                // BaseParser now defaults isMovie=true for search if unclear, or logic needs to be robust
                // For now assuming the parser logic sets isMovie correctly
                newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = http.getImageHeaders()
                }
            }
        }
    }
    
    // ==================== LOAD ====================
    
    // ==================== LOAD ====================
    
    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "[load] $url")
        
        // 1. Fetch document directly (since http.load defaults to parser.parseLoadPage which returns null)
        val doc = http.getDocument(url, checkDomainChange = true) ?: return null
        
        // 2. Parse into intermediate data
        val data = parser.parseLoadPageData(doc, url) ?: return null
        
        // 3. Construct LoadResponse using MainAPI helpers
        return if (data.isMovie) {
            newMovieLoadResponse(data.title, url, TvType.Movie, data.watchUrl ?: "") {
                this.posterUrl = data.posterUrl
                this.year = data.year
                this.plot = data.plot
                this.tags = data.tags
                // this.rating = data.rating // Deprecated
                this.posterHeaders = http.getImageHeaders()
            }
        } else {
            val episodes = data.episodes?.toMutableList() ?: mutableListOf()
            
            // For series, fetch other seasons in parallel if needed
            // (We extract this logic here or keep it if parser has helper)
            val seasonUrls = parser.extractSeasonUrls(doc)
            if (seasonUrls.isNotEmpty()) {
                coroutineScope {
                    seasonUrls.map { seasonPair ->
                        async {
                            val seasonNum = seasonPair.first
                            val seasonUrl = seasonPair.second
                            try {
                                val episodeDoc = http.getDocument(
                                    parser.fixUrl(seasonUrl), 
                                    checkDomainChange = false
                                )
                                episodeDoc?.let { parser.parseEpisodes(it, seasonNum) }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to fetch season $seasonNum: ${e.message}")
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().forEach { epList ->
                        episodes.addAll(epList)
                    }
                }
            }
            
            // Convert ParsedEpisode to Episode using MainAPI.newEpisode
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
                this.tags = data.tags
                // Usage of 'rating' is deprecated. 
                // data.rating?.let { addRating(it) } // If addRating exists, or just omit.
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
        
        // Get player URLs from the page
        val playerUrls = http.getPlayerUrls(data)
        Log.i(TAG, "[loadLinks] Found ${playerUrls.size} player URLs")
        
        if (playerUrls.isEmpty()) {
            Log.w(TAG, "[loadLinks] No player URLs found, trying direct sniff")
            return sniffAndCallback(data, data, callback)
        }
        
        // Try each player URL
        for ((index, playerUrl) in playerUrls.withIndex()) {
            Log.i(TAG, "[loadLinks] Trying player #${index + 1}: ${playerUrl.take(80)}...")
            
            if (sniffAndCallback(playerUrl, data, callback)) {
                return true
            }
        }
        
        return false
    }
    
    private suspend fun sniffAndCallback(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videos = http.sniffVideos(url)
        
        if (videos.isEmpty()) {
            Log.w(TAG, "[sniffAndCallback] No videos found from: ${url.take(80)}")
            return false
        }
        
        Log.i(TAG, "[sniffAndCallback] Found ${videos.size} videos")
        
        videos.forEach { source ->
            val quality = parser.parseQuality(source.label)
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - ${source.label}",
                    url = source.url,
                    type = if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = source.headers + http.getImageHeaders()
                }
            )
        }
        
        return true
    }
}
