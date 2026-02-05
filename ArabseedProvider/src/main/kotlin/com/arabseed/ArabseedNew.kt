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
import okhttp3.Interceptor
import okhttp3.Response
import com.arabseed.extractors.ArabseedLazyExtractor

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
        Log.d(name, "[getMainPage] Image headers for ${items.size} items: hasCookies=${imageHeaders.containsKey("Cookie")}, cookieLen=${imageHeaders["Cookie"]?.length ?: 0}")
        
        val responses = items.mapIndexed { idx, item ->
            // Log first 3 items for debugging
            if (idx < 3) {
                Log.d(name, "[getMainPage] Item[$idx]: poster=${item.posterUrl?.take(60) ?: "NULL"}...")
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
        Log.d(name, "[load] Loading: ${url.take(60)}...")
        
        val doc = httpService.getDocument(url) ?: run {
            Log.e(name, "[load] Failed to get document")
            return null
        }
        
        val data = parser.parseLoadPageData(doc, url) ?: run {
            Log.e(name, "[load] Failed to parse load page data")
            return null
        }
        
        Log.d(name, "[load] Parsed: title='${data.title}', isMovie=${data.isMovie}, watchUrl=${data.watchUrl?.take(40)}, episodes=${data.episodes?.size ?: 0}")
        
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
            Log.d(name, "[load] Series episodes: ${episodes.size}")
            
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
        Log.d(name, "[loadLinks] START data='${data.take(80)}...'")
        
        // 1. Get the document
        val doc = httpService.getDocument(data)
        if (doc == null) {
            Log.e(name, "[loadLinks] Failed to get document for data URL")
            return loadLinksFromService(data, subtitleCallback, callback)
        }
        
        // 2. Check if we need to navigate to watch page
        var isWatchPage = doc.select("ul > li[data-link], ul > h3").isNotEmpty() || 
                         doc.select("iframe[name=player_iframe]").isNotEmpty()
        
        var watchDoc = if (isWatchPage) doc else null
        
        if (watchDoc == null) {
            val watchUrl = doc.select("a.watch__btn").attr("href")
            if (watchUrl.isNotBlank()) {
                Log.d(name, "[loadLinks] Found watch button, following to: ${watchUrl.take(60)}")
                watchDoc = httpService.getDocument(watchUrl)
            }
        }
        
        if (watchDoc == null) {
            Log.e(name, "[loadLinks] Could not get watch page")
            return false
        }
        
        // 3. Extract available qualities and identify default
        val availableQualities = parser.extractQualities(watchDoc)
        val defaultQuality = watchDoc.selectFirst("ul.qualities__list li.active")
            ?.attr("data-quality")?.toIntOrNull() ?: availableQualities.lastOrNull()?.quality ?: 480
        
        val globalPostId = parser.extractPostId(watchDoc) ?: ""
        val csrfToken = parser.extractCsrfToken(doc) ?: ""
        
        Log.d(name, "[loadLinks] Qualities: ${availableQualities.map { it.quality }}, Default: $defaultQuality")
        Log.d(name, "[loadLinks] GlobalPostID: ${globalPostId.ifBlank { "N/A" }}, CSRF: ${if(csrfToken.isNotBlank()) "FOUND" else "MISSING"}")
        
        // 4. Extract visible servers (these are for the DEFAULT quality)
        val visibleServers = parser.extractVisibleServers(watchDoc)
        Log.d(name, "[loadLinks] Visible servers (${defaultQuality}p): ${visibleServers.size}")
        
        // Get any server's postId for generating other quality URLs
        val anyPostId = visibleServers.firstOrNull()?.postId?.ifBlank { globalPostId } ?: globalPostId
        
        val currentBaseUrl = try {
            val uri = java.net.URI(watchDoc.location())
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "https://${httpService.currentDomain}"
        }
        val encodedReferer = java.net.URLEncoder.encode(watchDoc.location(), "UTF-8")
        
        var found = false
        
        // ==================== QUALITY-BASED SOURCE GENERATION ====================
        // Sort qualities from highest to lowest
        val sortedQualities = availableQualities.sortedByDescending { it.quality }
        
        sortedQualities.forEach { qData ->
            val quality = qData.quality
            
            if (quality != defaultQuality) {
                // NON-DEFAULT QUALITY: Build virtual URLs (need AJAX to fetch servers)
                if (anyPostId.isNotBlank() && csrfToken.isNotBlank()) {
                    // Generate virtual URLs for server 1, 2, 3... (up to 5 servers typically)
                    for (serverId in 1..5) {
                        val virtualUrl = "$currentBaseUrl/get__watch__server/?post_id=$anyPostId&quality=$quality&server=$serverId&csrf_token=$csrfToken&referer=$encodedReferer"
                        
                        Log.d(name, "[loadLinks] Emitting ${quality}p server $serverId (virtual)")
                        callback(
                            newExtractorLink(
                                name,
                                "سيرفر $serverId (${quality}p)",
                                virtualUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                            }
                        )
                        found = true
                    }
                } else {
                    Log.w(name, "[loadLinks] Cannot generate ${quality}p sources - missing postId or csrf")
                }
            } else {
                // DEFAULT QUALITY: Use visible servers with data-link (already fetched)
                visibleServers.forEachIndexed { idx, server ->
                    if (server.dataLink.isNotBlank()) {
                        // Emit the data-link URL - LazyExtractor will decode Base64 if needed
                        Log.d(name, "[loadLinks] Emitting ${quality}p server ${idx+1} (data-link): ${server.dataLink.take(50)}")
                        callback(
                            newExtractorLink(
                                name,
                                "${server.title.ifBlank { "سيرفر ${idx+1}" }} (${quality}p)",
                                server.dataLink,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = watchDoc.location()
                                this.quality = quality
                            }
                        )
                        found = true
                    } else if (server.postId.isNotBlank() && csrfToken.isNotBlank()) {
                        // Fallback to virtual URL if no data-link
                        val virtualUrl = "$currentBaseUrl/get__watch__server/?post_id=${server.postId}&quality=$quality&server=${server.serverId}&csrf_token=$csrfToken&referer=$encodedReferer"
                        
                        Log.d(name, "[loadLinks] Emitting ${quality}p server ${server.serverId} (virtual fallback)")
                        callback(
                            newExtractorLink(
                                name,
                                "${server.title.ifBlank { "سيرفر ${server.serverId}" }} (${quality}p)",
                                virtualUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                            }
                        )
                        found = true
                    }
                }
            }
        }
        
        // ==================== DIRECT EMBEDS (FALLBACK) ====================
        if (!found) {
            val directEmbeds = parser.extractDirectEmbeds(watchDoc)
            Log.i(name, "[loadLinks] No servers found, using ${directEmbeds.size} direct embeds as fallback")
            
            directEmbeds.forEachIndexed { i, embedUrl ->
                Log.i(name, "[loadLinks] Fallback Direct Embed #${i+1}: $embedUrl")
                callback(
                    newExtractorLink(
                        name,
                        "$name Direct",
                        embedUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = watchDoc.location()
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        
        Log.d(name, "[loadLinks] END found=$found")
        return found
    }
    
    private suspend fun loadLinksFromService(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = httpService.getPlayerUrls(data)
        Log.d(name, "[loadLinksFromService] URLs: ${urls.size}")
        
        var found = false
        urls.forEach { url ->
            val isPrivateServer = url.contains("arabseed") || url.contains("asd")
            
            if (isPrivateServer) {
                val sources = httpService.sniffVideos(url)
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
     * Lazy URL resolution for embed URLs.
     * Uses LazyExtractor to handle both virtual URLs and direct embeds.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val url = extractorLink.url
        
        // Check if resolution is needed
        val isVirtual = url.contains("/get__watch__server/")
        val needsResolution = isVirtual || 
                             url.contains("reviewrate") ||
                             url.contains("/play.php") ||
                             url.contains("/play/?id=") ||
                             url.contains("stmix.io") ||
                             url.contains("vidmoly") ||
                             url.contains("up4fun") ||
                             url.contains("savefiles") ||
                             url.contains("bysezejataos")
        
        val isArabseedDomain = url.contains("asd.") || url.contains("arabseed")
        
        if (needsResolution || isArabseedDomain) {
            return Interceptor { chain ->
                val request = chain.request()
                val urlString = request.url.toString()
                
                Log.d(name, "[getVideoInterceptor] Resolving: ${urlString.take(80)}")
                
                var resolvedLink: ExtractorLink? = null
                
                try {
                    // Use LazyExtractor for EVERYTHING - it now handles decoding, extraction, and VideoSniffer fallback
                    val extractor = ArabseedLazyExtractor { targetUrl, data, referer ->
                         // Bridge to HttpService post - using runBlocking safely here since we are in interceptor thread
                         try {
                              val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                              httpService.post(targetUrl, data, referer = referer, headers = headers)?.outerHtml()
                         } catch (e: Exception) {
                              Log.e(name, "[ArabseedLazyExtractor] Fetch failed: ${e.message}")
                              null
                         }
                    }
                    
                    kotlinx.coroutines.runBlocking {
                        // Pass URL to extractor (LazyExtractor handles routing to Virtual vs Direct)
                        // CRITICAL: Pass referer from the extractor link so LazyExtractor has the correct one
                        extractor.getUrl(urlString, extractorLink.referer, {}) { link ->
                            resolvedLink = link
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "[getVideoInterceptor] Resolution error: ${e.message}")
                }
                
                if (resolvedLink != null) {
                    Log.d(name, "[getVideoInterceptor] Resolved to: ${resolvedLink!!.url}")
                    val builder = request.newBuilder().url(resolvedLink!!.url)
                    if (resolvedLink!!.referer.isNotBlank()) {
                         builder.header("Referer", resolvedLink!!.referer)
                    }
                    return@Interceptor chain.proceed(builder.build())
                }
                
                Log.w(name, "[getVideoInterceptor] No resolution found, proceeding with original")
                return@Interceptor chain.proceed(request)
            }
        }
        return null
    }
}
