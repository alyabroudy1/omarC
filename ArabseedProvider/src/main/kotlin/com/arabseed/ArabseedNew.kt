package com.arabseed

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.provider.ProviderConfig
import com.cloudstream.shared.service.ProviderHttpService
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.android.PluginContext
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.arabseed.extractors.ArabseedLazyExtractor
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * Arabseed V4 - Ported to Plugin Architecture.
 * 
 * Uses independent service stack for better isolation and maintainability.
 */
class ArabseedV2 : MainAPI() {
    
    override var mainUrl = "https://arabseed.show"
    override var name = "ArabseedV2"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    
    override val mainPage = mainPageOf(
        "/movies-1/" to "أفلام",
        "/series-1/" to "مسلسلات"
    )
    
    companion object {
        private const val GITHUB_CONFIG = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/arabseed.json"
    }

    private val parser = ArabseedParser()

    private val httpService by lazy {
        // Ensure context is available
        val context = PluginContext.context ?: (com.lagradost.cloudstream3.app as android.content.Context)
        
        ProviderHttpService.create(
            context = context,
            config = ProviderConfig(
                name = name,
                fallbackDomain = "arabseed.show",
                githubConfigUrl = GITHUB_CONFIG,
                syncWorkerUrl = "https://omarstreamcloud.alyabroudy1.workers.dev",
                skipHeadless = true,
                trustedDomains = listOf("arabseed", "asd"),
                validateWithContent = listOf("ArabSeed", "عرب سيد")
            ),
            parser = parser,
            activityProvider = { ActivityProvider.currentActivity }
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        httpService.ensureInitialized()
        
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val items = httpService.getMainPage(url)
        
        if (items.isEmpty()) return null
        
        // Get headers ONCE after CF is solved
        val imageHeaders = httpService.getImageHeaders()
        Log.d("ArabseedV2", "[getMainPage] Image headers for ${items.size} items: hasCookies=${imageHeaders.containsKey("Cookie")}, cookieLen=${imageHeaders["Cookie"]?.length ?: 0}")
        
        val responses = items.mapIndexed { idx, item ->
            // Log first 3 items for debugging
            if (idx < 3) {
                Log.d("ArabseedV2", "[getMainPage] Item[$idx]: poster=${item.posterUrl?.take(60) ?: "NULL"}...")
            }
            newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = imageHeaders
            }
        }
        return newHomePageResponse(request.name, responses)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        return httpService.search(query).map { item ->
             newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                 this.posterUrl = item.posterUrl
                 this.posterHeaders = httpService.getImageHeaders()
             }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        httpService.ensureInitialized()
        Log.d("ArabseedV2", "[load] Loading: ${url.take(60)}...")
        
        val doc = httpService.getDocument(url) ?: run {
            Log.e("ArabseedV2", "[load] Failed to get document")
            return null
        }
        
        val data = parser.parseLoadPageData(doc, url) ?: run {
            Log.e("ArabseedV2", "[load] Failed to parse load page data")
            return null
        }
        
        Log.d("ArabseedV2", "[load] Parsed: title='${data.title}', isMovie=${data.isMovie}, watchUrl=${data.watchUrl?.take(40)}, episodes=${data.episodes?.size ?: 0}")
        
        return if (data.isMovie) {
            // For movies: pass watchUrl (or url as fallback) for loadLinks
            val movieDataUrl = data.watchUrl ?: url
            newMovieLoadResponse(data.title, url, TvType.Movie, movieDataUrl) {
                this.posterUrl = data.posterUrl
                this.posterHeaders = httpService.getImageHeaders()  // CRITICAL: Image headers
                this.year = data.year
                this.plot = data.plot
                this.tags = data.tags
            }
        } else {
            // For series: use pre-parsed episodes from ParsedLoadData
            val episodes = data.episodes ?: parser.parseEpisodes(doc, null)
            Log.d("ArabseedV2", "[load] Series episodes: ${episodes.size}")
            
            newTvSeriesLoadResponse(data.title, url, TvType.TvSeries, episodes.map {
                newEpisode(it.url) {
                    this.name = it.name
                    this.season = it.season
                    this.episode = it.episode
                }
            }) {
                this.posterUrl = data.posterUrl
                this.posterHeaders = httpService.getImageHeaders()  // CRITICAL: Image headers
                this.year = data.year
                this.plot = data.plot
                this.tags = data.tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ArabseedV2", "[loadLinks] START data='${data.take(80)}...'")
        
        // 1. Get the document
        val doc = httpService.getDocument(data)
        if (doc == null) {
            Log.e("ArabseedV2", "[loadLinks] Failed to get document for data URL")
            return loadLinksFromService(data, subtitleCallback, callback)
        }
        
        // 2. Check if we need to navigate to watch page
        var isWatchPage = doc.select("ul > li[data-link], ul > h3").isNotEmpty() || 
                         doc.select("iframe[name=player_iframe]").isNotEmpty()
        
        var watchDoc = if (isWatchPage) doc else null
        
        if (watchDoc == null) {
            val watchUrl = doc.select("a.watch__btn").attr("href")
            if (watchUrl.isNotBlank()) {
                Log.d("ArabseedV2", "[loadLinks] Found watch button, following to: ${watchUrl.take(60)}")
                watchDoc = httpService.getDocument(watchUrl)
            }
        }
        
        if (watchDoc == null) {
            Log.e("ArabseedV2", "[loadLinks] Could not get watch page")
            return false
        }
        
        // 3. Extract available qualities and identify default
        val availableQualities = parser.extractQualities(watchDoc)
        val defaultQuality = watchDoc.selectFirst("ul.qualities__list li.active")
            ?.attr("data-quality")?.toIntOrNull() ?: availableQualities.lastOrNull()?.quality ?: 480
        
        val globalPostId = parser.extractPostId(watchDoc) ?: ""
        val csrfToken = parser.extractCsrfToken(doc) ?: ""
        
        Log.d("ArabseedV2", "[loadLinks] Qualities: ${availableQualities.map { it.quality }}, Default: $defaultQuality")
        Log.d("ArabseedV2", "[loadLinks] GlobalPostID: ${globalPostId.ifBlank { "N/A" }}, CSRF: ${if(csrfToken.isNotBlank()) "FOUND" else "MISSING"}")
        
        // 4. Extract visible servers (these are for the DEFAULT quality)
        val visibleServers = parser.extractVisibleServers(watchDoc)
        Log.d("ArabseedV2", "[loadLinks] Visible servers (${defaultQuality}p): ${visibleServers.size}")
        
        // Get any server's postId for generating other quality URLs
        val anyPostId = visibleServers.firstOrNull()?.postId?.ifBlank { globalPostId } ?: globalPostId
        
        val currentBaseUrl = try {
            val uri = java.net.URI(watchDoc.location())
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "https://${httpService.currentDomain}"
        }
        
        var found = false
        
        // ==================== QUALITY-BASED SOURCE GENERATION ====================
        // Sort qualities from highest to lowest
        val sortedQualities = availableQualities.sortedByDescending { it.quality }
        
        Log.i("ArabseedV2", "[loadLinks] Processing ${sortedQualities.size} qualities, ${visibleServers.size} visible servers")
        
        sortedQualities.forEach { qData ->
            val quality = qData.quality
            
            if (quality != defaultQuality) {
                // NON-DEFAULT QUALITY: Build virtual URLs and resolve via LazyExtractor with http.postText
                if (anyPostId.isNotBlank() && csrfToken.isNotBlank()) {
                    // Create LazyExtractor with fetcher that uses httpService.postDebug (has CF session!)
                    // CRITICAL: Pass RELATIVE PATH only (e.g., "/get__watch__server/"), NOT full URL!
                    // The ProviderHttpService.buildUrl() will construct the full URL with proper domain
                    val extractor = ArabseedLazyExtractor(
                        fetcher = { endpoint, data, referer ->
                            // endpoint is already the relative path "/get__watch__server/"
                            // DO NOT construct full URL - let ProviderHttpService.handle it!
                            Log.d("ArabseedV2", "[LazyExtractor.fetcher] POST to endpoint: $endpoint")
                            Log.d("ArabseedV2", "[LazyExtractor.fetcher] POST data: $data")
                            
                            kotlinx.coroutines.runBlocking {
                                try {
                                    // Use postDebug to get full response details
                                    // Pass endpoint directly (relative path), NOT full URL!
                                    val result = httpService.postDebug(
                                        url = endpoint,  // <-- RELATIVE PATH! "/get__watch__server/"
                                        data = data,
                                        referer = referer,
                                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                                    )
                                    
                                    Log.d("ArabseedV2", "[LazyExtractor.fetcher] Response code: ${result.responseCode}")
                                    Log.d("ArabseedV2", "[LazyExtractor.fetcher] Success: ${result.success}")
                                    Log.d("ArabseedV2", "[LazyExtractor.fetcher] isCloudflareBlocked: ${result.isCloudflareBlocked}")
                                    
                                    if (!result.success) {
                                        Log.e("ArabseedV2", "[LazyExtractor.fetcher] REQUEST FAILED!")
                                        Log.e("ArabseedV2", "[LazyExtractor.fetcher] Error: ${result.error?.message}")
                                    } else if (result.html.isNullOrBlank()) {
                                        Log.e("ArabseedV2", "[LazyExtractor.fetcher] SUCCESS BUT EMPTY RESPONSE")
                                    } else {
                                        Log.d("ArabseedV2", "[LazyExtractor.fetcher] SUCCESS: Response length=${result.html.length}")
                                        Log.d("ArabseedV2", "[LazyExtractor.fetcher] Response preview: ${result.html.take(200)}")
                                    }
                                    
                                    result.html
                                } catch (e: Exception) {
                                    Log.e("ArabseedV2", "[LazyExtractor.fetcher] POST EXCEPTION: ${e.message}")
                                    e.printStackTrace()
                                    null
                                }
                            }
                        }
                    )
                    
                    // Generate virtual URLs for server 1, 2, 3... (up to 5 servers typically)
                    // CRITICAL FIX: Use loadExtractor() to route through ArabseedVirtualExtractor
                    // This ensures URLs are resolved BEFORE reaching ExoPlayer
                    for (serverId in 1..5) {
                        val virtualUrl = "$currentBaseUrl/get__watch__server/?post_id=$anyPostId&quality=$quality&server=$serverId&csrf_token=$csrfToken"
                        
                        Log.d("ArabseedV2", "[loadLinks] Processing ${quality}p server $serverId via loadExtractor -> ArabseedVirtualExtractor")
                        
                        // Use loadExtractor to route virtual URL through our custom extractor
                        // ArabseedVirtualExtractor will resolve the URL and return proper ExtractorLink
                        loadExtractor(virtualUrl, "$currentBaseUrl/", subtitleCallback) { link ->
                            Log.d("ArabseedV2", "[loadLinks] Extractor resolved ${quality}p server $serverId: ${link.url.take(60)} (type=${link.type})")
                            callback(link)
                            found = true
                        }
                    }
                } else {
                    Log.w("ArabseedV2", "[loadLinks] Cannot generate ${quality}p sources - missing postId or csrf")
                }
            } else {
                // DEFAULT QUALITY: Use visible servers with data-link (already fetched)
                // OPTIMIZATION: Skip if we already found enough videos from other qualities
                if (found) {
                    Log.d("ArabseedV2", "[loadLinks] Skipping ${quality}p data-link servers - already found working links")
                    return@forEach
                }
                
                visibleServers.forEachIndexed { idx, server ->
                    if (found) {
                        Log.d("ArabseedV2", "[loadLinks] Skipping remaining ${quality}p servers - already found")
                        return@forEachIndexed
                    }
                    
                    if (server.dataLink.isNotBlank()) {
                        // Process data-link URL via loadExtractor (these are actual embed URLs)
                        Log.d("ArabseedV2", "[loadLinks] Processing ${quality}p server ${idx+1} (data-link) via loadExtractor: ${server.dataLink.take(50)}")
                        loadExtractor(server.dataLink, "$currentBaseUrl/", subtitleCallback) { link ->
                            Log.d("ArabseedV2", "[loadLinks] Extractor resolved data-link: ${link.url.take(60)} (type=${link.type})")
                            callback(link)
                            found = true
                        }
                    } else if (server.postId.isNotBlank() && csrfToken.isNotBlank()) {
                        // Fallback to virtual URL if no data-link - use loadExtractor
                        val virtualUrl = "$currentBaseUrl/get__watch__server/?post_id=${server.postId}&quality=$quality&server=${server.serverId}&csrf_token=$csrfToken"
                        
                        Log.d("ArabseedV2", "[loadLinks] Processing ${quality}p server ${server.serverId} (virtual) via loadExtractor")
                        loadExtractor(virtualUrl, "$currentBaseUrl/", subtitleCallback) { link ->
                            Log.d("ArabseedV2", "[loadLinks] Extractor resolved virtual: ${link.url.take(60)} (type=${link.type})")
                            callback(link)
                            found = true
                        }
                    }
                }
            }
        }
        
        // ==================== DIRECT EMBEDS (FALLBACK) ====================
        if (!found) {
            val directEmbeds = parser.extractDirectEmbeds(watchDoc)
            Log.i("ArabseedV2", "[loadLinks] No servers found, using ${directEmbeds.size} direct embeds as fallback")
            
            directEmbeds.forEachIndexed { i, embedUrl ->
                Log.i("ArabseedV2", "[loadLinks] Fallback Direct Embed #${i+1}: $embedUrl")
                // Use loadExtractor for direct embeds too - allows proper extraction
                loadExtractor(embedUrl, "$currentBaseUrl/", subtitleCallback) { link ->
                    Log.d("ArabseedV2", "[loadLinks] Direct embed resolved: ${link.url.take(60)} (type=${link.type})")
                    callback(link)
                    found = true
                }
            }
        }
        
        Log.d("ArabseedV2", "[loadLinks] END found=$found")
        return found
    }
    
    private suspend fun loadLinksFromService(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = httpService.getPlayerUrls(data)
        Log.d("ArabseedV2", "[loadLinksFromService] URLs: ${urls.size}")
        
        var found = false
        urls.forEach { url ->
            val isPrivateServer = url.contains("arabseed") || url.contains("asd")
            
            if (isPrivateServer) {
                // Use visible sniffer directly as per user request
                val sources = httpService.sniffVideosVisible(url)
                
                sources.forEach { source ->
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            source.url,
                            if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = getQualityFromName(source.quality)
                            this.headers = source.headers
                        }
                    )
                    found = true
                }
            } else {
                loadExtractor(url, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }

    private fun getQualityFromName(qualityName: String): Int {
        return when {
            qualityName.contains("360") -> Qualities.P360.value
            qualityName.contains("480") -> Qualities.P480.value
            qualityName.contains("720") -> Qualities.P720.value
            qualityName.contains("1080") -> Qualities.P1080.value
            qualityName.contains("4k") || qualityName.contains("2160") -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
    /**
     * REMOVED: getVideoInterceptor() - This approach doesn't work because:
     * 1. ExoPlayer uses MediaHTTPConnection (not OkHttp) for playback
     * 2. MediaHTTPConnection bypasses OkHttp interceptors entirely
     * 3. The interceptor runs but ExoPlayer ignores the resolved URL
     * 
     * SOLUTION: Use loadExtractor() in loadLinks() to route virtual URLs
     * through ArabseedVirtualExtractor. The extractor resolves URLs BEFORE
     * they reach ExoPlayer, which is the proper CloudStream pattern.
     */
}
