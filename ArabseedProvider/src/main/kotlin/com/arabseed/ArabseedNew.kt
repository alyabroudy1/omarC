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
        
        var found = false
        
        // ==================== QUALITY-BASED SOURCE GENERATION ====================
        // Sort qualities from highest to lowest
        val sortedQualities = availableQualities.sortedByDescending { it.quality }
        
        Log.i(name, "[loadLinks] Processing ${sortedQualities.size} qualities, ${visibleServers.size} visible servers")
        
        sortedQualities.forEach { qData ->
            val quality = qData.quality
            
            if (quality != defaultQuality) {
                // NON-DEFAULT QUALITY: Build virtual URLs and resolve via LazyExtractor with http.postText
                if (anyPostId.isNotBlank() && csrfToken.isNotBlank()) {
                    // Create LazyExtractor with fetcher that uses httpService.postDebug (has CF session!)
                    val extractor = ArabseedLazyExtractor(
                        fetcher = { endpoint, data, referer ->
                            // Use full URL if endpoint is relative
                            val fullUrl = if (endpoint.startsWith("http")) endpoint else "$currentBaseUrl$endpoint"
                            Log.d(name, "[LazyExtractor.fetcher] POST to: ${fullUrl.take(60)}")
                            Log.d(name, "[LazyExtractor.fetcher] POST data: $data")
                            
                            kotlinx.coroutines.runBlocking {
                                try {
                                    // Use postDebug to get full response details
                                    val result = httpService.postDebug(
                                        url = fullUrl,
                                        data = data,
                                        referer = referer,
                                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                                    )
                                    
                                    Log.d(name, "[LazyExtractor.fetcher] Response code: ${result.responseCode}")
                                    Log.d(name, "[LazyExtractor.fetcher] Success: ${result.success}")
                                    Log.d(name, "[LazyExtractor.fetcher] isCloudflareBlocked: ${result.isCloudflareBlocked}")
                                    
                                    if (!result.success) {
                                        Log.e(name, "[LazyExtractor.fetcher] REQUEST FAILED!")
                                        Log.e(name, "[LazyExtractor.fetcher] Error: ${result.error?.message}")
                                    } else if (result.html.isNullOrBlank()) {
                                        Log.e(name, "[LazyExtractor.fetcher] SUCCESS BUT EMPTY RESPONSE")
                                    } else {
                                        Log.d(name, "[LazyExtractor.fetcher] SUCCESS: Response length=${result.html.length}")
                                        Log.d(name, "[LazyExtractor.fetcher] Response preview: ${result.html.take(200)}")
                                    }
                                    
                                    result.html
                                } catch (e: Exception) {
                                    Log.e(name, "[LazyExtractor.fetcher] POST EXCEPTION: ${e.message}")
                                    e.printStackTrace()
                                    null
                                }
                            }
                        }
                    )
                    
                    // Generate virtual URLs for server 1, 2, 3... (up to 5 servers typically)
                    for (serverId in 1..5) {
                        val virtualUrl = "$currentBaseUrl/get__watch__server/?post_id=$anyPostId&quality=$quality&server=$serverId&csrf_token=$csrfToken"
                        
                        Log.d(name, "[loadLinks] Processing ${quality}p server $serverId via LazyExtractor with http.postText")
                        
                        // Use LazyExtractor directly to resolve virtual URL
                        extractor.getUrl(virtualUrl, "$currentBaseUrl/", subtitleCallback) { link ->
                            Log.d(name, "[loadLinks] LazyExtractor resolved ${quality}p server $serverId: ${link.url.take(60)} (type=${link.type})")
                            callback(link)
                            found = true
                        }
                    }
                } else {
                    Log.w(name, "[loadLinks] Cannot generate ${quality}p sources - missing postId or csrf")
                }
            } else {
                // DEFAULT QUALITY: Use visible servers with data-link (already fetched)
                visibleServers.forEachIndexed { idx, server ->
                    if (server.dataLink.isNotBlank()) {
                        // Process data-link URL via loadExtractor (these are actual embed URLs)
                        Log.d(name, "[loadLinks] Processing ${quality}p server ${idx+1} (data-link) via loadExtractor: ${server.dataLink.take(50)}")
                        loadExtractor(server.dataLink, "$currentBaseUrl/", subtitleCallback) { link ->
                            Log.d(name, "[loadLinks] Extractor resolved data-link: ${link.url.take(60)} (type=${link.type})")
                            callback(link)
                            found = true
                        }
                    } else if (server.postId.isNotBlank() && csrfToken.isNotBlank()) {
                        // Fallback to virtual URL if no data-link - resolve via LazyExtractor
                        val virtualUrl = "$currentBaseUrl/get__watch__server/?post_id=${server.postId}&quality=$quality&server=${server.serverId}&csrf_token=$csrfToken"
                        
                        // Create LazyExtractor with fetcher for this server (DEBUG version)
                        val extractor = ArabseedLazyExtractor(
                            fetcher = { endpoint, data, referer ->
                                val fullUrl = if (endpoint.startsWith("http")) endpoint else "$currentBaseUrl$endpoint"
                                kotlinx.coroutines.runBlocking {
                                    val result = httpService.postDebug(
                                        url = fullUrl,
                                        data = data,
                                        referer = referer,
                                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                                    )
                                    Log.d(name, "[LazyExtractor.fetcher] Response code: ${result.responseCode}, Success: ${result.success}")
                                    result.html
                                }
                            }
                        )
                        
                        Log.d(name, "[loadLinks] Processing ${quality}p server ${server.serverId} (virtual) via LazyExtractor")
                        extractor.getUrl(virtualUrl, "$currentBaseUrl/", subtitleCallback) { link ->
                            Log.d(name, "[loadLinks] LazyExtractor resolved virtual: ${link.url.take(60)} (type=${link.type})")
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
            Log.i(name, "[loadLinks] No servers found, using ${directEmbeds.size} direct embeds as fallback")
            
            directEmbeds.forEachIndexed { i, embedUrl ->
                Log.i(name, "[loadLinks] Fallback Direct Embed #${i+1}: $embedUrl")
                // Use loadExtractor for direct embeds too - allows proper extraction
                loadExtractor(embedUrl, "$currentBaseUrl/", subtitleCallback) { link ->
                    Log.d(name, "[loadLinks] Direct embed resolved: ${link.url.take(60)} (type=${link.type})")
                    callback(link)
                    found = true
                }
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
     * CRITICAL: Interceptor to resolve virtual URLs via POST using ProviderHttpService.
     * 
     * This solves the 403 error by using httpService.postText() which includes:
     * - Cloudflare session cookies (cf_clearance)
     * - Proper User-Agent
     * - All necessary headers from the solved session
     * 
     * The virtual URLs (1080p, 720p) need this because they require POST to /get__watch__server/
     * which is protected by Cloudflare.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val url = extractorLink.url
        
        // Only intercept virtual URLs that need resolution
        if (url.contains("/get__watch__server/")) {
            return Interceptor { chain ->
                val request = chain.request()
                val urlString = request.url.toString()
                
                Log.d(name, "[getVideoInterceptor] Resolving virtual URL: ${urlString.take(80)}")
                
                var resolvedUrl: String? = null
                
                try {
                    kotlinx.coroutines.runBlocking {
                        // Parse parameters from virtual URL
                        val postId = getQueryParam(urlString, "post_id") ?: return@runBlocking
                        val quality = getQueryParam(urlString, "quality") ?: "720"
                        val server = getQueryParam(urlString, "server") ?: "0"
                        val csrfToken = getQueryParam(urlString, "csrf_token") ?: ""
                        val rawReferer = getQueryParam(urlString, "referer")
                        val referer = rawReferer?.let { 
                            java.net.URLDecoder.decode(it, "UTF-8") 
                        } ?: extractorLink.referer ?: ""
                        
                        // CRITICAL: Use httpService.postText() which has CF session cookies!
                        val data = mapOf(
                            "post_id" to postId,
                            "quality" to quality,
                            "server" to server,
                            "csrf_token" to csrfToken
                        )
                        
                        Log.d(name, "[getVideoInterceptor] POST to /get__watch__server/ with data: $data")
                        Log.d(name, "[getVideoInterceptor] Referer: ${referer.take(60)}")
                        
                        val jsonResponse = httpService.postText(
                            url = "/get__watch__server/",
                            data = data,
                            referer = referer,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        )
                        
                        if (!jsonResponse.isNullOrBlank()) {
                            // Parse JSON response to get embed URL
                            resolvedUrl = parseEmbedUrlFromJson(jsonResponse)
                            Log.i(name, "[getVideoInterceptor] Resolved to: ${resolvedUrl?.take(60)}")
                        } else {
                            Log.e(name, "[getVideoInterceptor] Empty response from POST")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "[getVideoInterceptor] Error: ${e.message}")
                    e.printStackTrace()
                }
                
                if (!resolvedUrl.isNullOrBlank()) {
                    // Return resolved URL to ExoPlayer
                    Log.i(name, "[getVideoInterceptor] Proceeding with resolved URL")
                    return@Interceptor chain.proceed(
                        request.newBuilder()
                            .url(resolvedUrl!!)
                            .header("Referer", urlString)
                            .build()
                    )
                }
                
                // If resolution failed, proceed with original request (will likely fail with 403)
                Log.w(name, "[getVideoInterceptor] Resolution failed, proceeding with original URL")
                chain.proceed(request)
            }
        }
        
        return null
    }
    
    private fun getQueryParam(url: String, key: String): String? {
        return Regex("[?&]$key=([^&]+)").find(url)?.groupValues?.get(1)
    }
    
    private fun parseEmbedUrlFromJson(json: String): String? {
        return try {
            when {
                json.contains("\"embed_url\"") -> {
                    Regex("\"embed_url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
                }
                json.contains("\"server\"") -> {
                    Regex("\"server\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(name, "[parseEmbedUrlFromJson] Error: ${e.message}")
            null
        }
    }
}
