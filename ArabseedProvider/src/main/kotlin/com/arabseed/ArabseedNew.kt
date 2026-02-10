package com.arabseed

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.provider.ProviderConfig
import com.cloudstream.shared.service.ProviderHttpService
import com.cloudstream.shared.service.ProviderHttpServiceHolder
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
import org.jsoup.nodes.Document

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
        
        val service = ProviderHttpService.create(
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
        
        // Initialize singleton so extractors can access it
        ProviderHttpServiceHolder.initialize(service)
        
        service
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
        
        val doc = httpService.getDocument(data) ?: run {
            Log.e("ArabseedV2", "[loadLinks] Failed to get document for data URL")
            return loadLinksFromService(data, subtitleCallback, callback)
        }
        
        val watchDoc = resolveWatchDocument(doc) ?: run {
            Log.e("ArabseedV2", "[loadLinks] Could not get watch page")
            return false
        }
        
        Log.d("ArabseedV2", "[loadLinks] Watch page resolved. Parsing qualities...")
        val availableQualities = parser.extractQualities(watchDoc)
        val defaultQuality = parser.extractDefaultQuality(watchDoc, availableQualities)
        val globalPostId = parser.extractPostId(watchDoc) ?: ""
        val csrfToken = parser.extractCsrfToken(doc) ?: ""
        
        Log.d("ArabseedV2", "[loadLinks] Qualities: ${availableQualities.map { it.quality }}, Default: $defaultQuality")
        Log.d("ArabseedV2", "[loadLinks] GlobalPostID: ${globalPostId.ifBlank { "N/A" }}, CSRF: ${if(csrfToken.isNotBlank()) "FOUND" else "MISSING"}")

        val currentBaseUrl = try {
            val uri = java.net.URI(watchDoc.location())
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "https://${httpService.currentDomain}"
        }

        var found = processQualities(
            watchDoc, 
            availableQualities, 
            defaultQuality, 
            globalPostId, 
            csrfToken, 
            currentBaseUrl, 
            subtitleCallback, 
            callback
        )
        
        if (!found) {
             found = processDirectEmbeds(watchDoc, currentBaseUrl, subtitleCallback, callback)
        }
        
        Log.d("ArabseedV2", "[loadLinks] END found=$found")
        return found
    }

    /**
     * Resolves the watch page document. 
     * If the current document is already a watch page, returns it.
     * Otherwise, finds the watch button and loads that page.
     */
    private suspend fun resolveWatchDocument(doc: Document): Document? {
        if (parser.isWatchPage(doc)) return doc
        
        val watchUrl = parser.getWatchUrl(doc)
        if (watchUrl.isNotBlank()) {
            Log.d("ArabseedV2", "[resolveWatchDocument] Found watch button, following to: ${watchUrl.take(60)}")
            return httpService.getDocument(watchUrl)
        }
        return null
    }

    /**
     * Iterates through available qualities and generates sources.
     * - Non-default qualities: Generates virtual URLs for on-demand resolution.
     * - Default quality: Processes visible servers directly.
     */
    override fun getVideoInterceptor(linker: ExtractorLink): okhttp3.Interceptor {
        return okhttp3.Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            if (url.contains("arabseed-lazy.com")) {
                val realUrl = runBlocking {
                     resolveLazyLink(url)
                } ?: throw java.io.IOException("Failed to resolve lazy link")
                
                // Redirect to the real URL
                val newRequest = request.newBuilder()
                    .url(realUrl)
                    .build()
                    
                return@Interceptor chain.proceed(newRequest)
            }
            return@Interceptor chain.proceed(request)
        }
    }

    private suspend fun resolveLazyLink(url: String): String? {
        Log.d("ArabseedV2", "[resolveLazyLink] Processing URL: $url")
        val uri = java.net.URI(url)
        val query = uri.query ?: ""
        val queryParams = query.split("&").associate { 
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else it to ""
        }
        
        Log.d("ArabseedV2", "[resolveLazyLink] Params: $queryParams")
        
        val postId = queryParams["post_id"] ?: return null
        val quality = queryParams["quality"] ?: return null
        val server = queryParams["server"] ?: return null
        val csrfToken = queryParams["csrf_token"] ?: return null
        val baseUrl = queryParams["base"] ?: "https://arabseed.show" // Extract base URL from param

        val serverResponse = httpService.postText(
            "$baseUrl/get__watch__server/",
            data = mapOf(
                "post_id" to postId,
                "quality" to quality,
                "server" to server,
                "csrf_token" to csrfToken
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to baseUrl,
                "Referer" to "$baseUrl/"
            )
        )
        
        Log.d("ArabseedV2", "[resolveLazyLink] Raw Response: ${serverResponse?.take(200)}")
        if (serverResponse == null) return null
        
        val serverDoc = org.jsoup.Jsoup.parse(serverResponse, "$baseUrl/")
        val iframeSrc = serverDoc.select("iframe").attr("src")
        
        // Fallback for JSON response
        val finalUrl = if (iframeSrc.isBlank() && serverResponse.contains("\"html\"")) {
             Log.d("ArabseedV2", "[resolveLazyLink] Attempting JSON/HTML extraction")
             val htmlMatch = Regex("\"html\"\\s*:\\s*\"([^\"]+)\"").find(serverResponse)
             val escapedHtml = htmlMatch?.groupValues?.get(1)
             if (escapedHtml != null) {
                 val unescaped = escapedHtml.replace("\\/", "/").replace("\\\"", "\"")
                 org.jsoup.Jsoup.parse(unescaped).select("iframe").attr("src")
             } else null
        } else iframeSrc

        Log.d("ArabseedV2", "[resolveLazyLink] Final URL: $finalUrl")
        return if (!finalUrl.isNullOrBlank()) finalUrl else null
    }

    private suspend fun processQualities(
        watchDoc: Document,
        availableQualities: List<com.arabseed.ArabseedParser.QualityData>,
        defaultQuality: Int,
        globalPostId: String,
        csrfToken: String,
        currentBaseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val visibleServers = parser.extractVisibleServers(watchDoc)
        val anyPostId = visibleServers.firstOrNull()?.postId?.ifBlank { globalPostId } ?: globalPostId
        
        Log.d("ArabseedV2", "[loadLinks] Visible servers (${defaultQuality}p): ${visibleServers.size}")

        // Sort qualities from highest to lowest
        val sortedQualities = availableQualities.sortedByDescending { it.quality }
        Log.i("ArabseedV2", "[loadLinks] Processing ${sortedQualities.size} qualities")

        sortedQualities.forEach { qData ->
            val quality = qData.quality
            
            if (quality != defaultQuality) {
                // NON-DEFAULT QUALITY: Emit lazy URLs
                if (anyPostId.isNotBlank() && csrfToken.isNotBlank()) {
                    for (serverId in 1..5) {
                        val lazyUrl = "https://arabseed-lazy.com/?post_id=$anyPostId&quality=$quality&server=$serverId&csrf_token=$csrfToken&base=$currentBaseUrl"
                        Log.d("ArabseedV2", "[loadLinks] Emitting ${quality}p server $serverId (lazy URL)")
                        callback(
                            newExtractorLink(
                                source = name, // Must match this.name
                                name = "Server $serverId (${quality}p)",
                                url = lazyUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$currentBaseUrl/"
                                this.quality = quality
                            }
                        )
                        found = true
                    }
                }
            } else {
                // DEFAULT QUALITY: Use visible servers
                 if (processVisibleServers(visibleServers, quality, csrfToken, currentBaseUrl, subtitleCallback, callback)) {
                     found = true
                }
            }
        }
        return found
    }

    private suspend fun processVisibleServers(
        visibleServers: List<com.arabseed.ArabseedParser.ServerData>,
        quality: Int,
        csrfToken: String,
        currentBaseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        visibleServers.forEachIndexed { idx, server ->
             if (found && idx > 0) {
                 // Optimization
             }

             if (server.dataLink.isNotBlank()) {
                 loadExtractor(server.dataLink, "$currentBaseUrl/", subtitleCallback, callback)
                 found = true
            } else if (server.postId.isNotBlank() && csrfToken.isNotBlank()) {
                val lazyUrl = "https://arabseed-lazy.com/?post_id=${server.postId}&quality=$quality&server=${server.serverId}&csrf_token=$csrfToken&base=$currentBaseUrl"
                Log.d("ArabseedV2", "[loadLinks] Processing ${quality}p server ${server.serverId} (lazy) via Interceptor")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Server ${server.serverId} (${quality}p)",
                        url = lazyUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$currentBaseUrl/"
                        this.quality = quality
                    }
                )
                found = true
            }
        }
        return found
    }

    private suspend fun processDirectEmbeds(
        watchDoc: Document,
        currentBaseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val directEmbeds = parser.extractDirectEmbeds(watchDoc)
        Log.i("ArabseedV2", "[loadLinks] No servers found, using ${directEmbeds.size} direct embeds as fallback")

        directEmbeds.forEachIndexed { i, embedUrl ->
            Log.i("ArabseedV2", "[loadLinks] Fallback Direct Embed #${i+1}: $embedUrl")
            loadExtractor(embedUrl, "$currentBaseUrl/", subtitleCallback) { link ->
                Log.d("ArabseedV2", "[loadLinks] Direct embed resolved: ${link.url.take(60)} (type=${link.type})")
                callback(link)
                found = true
            }
        }
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
}

