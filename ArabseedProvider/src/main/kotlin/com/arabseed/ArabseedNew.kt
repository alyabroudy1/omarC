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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import org.jsoup.nodes.Document

/**
 * Arabseed V4 - Ported to Plugin Architecture.
 * 
 * Uses independent service stack for better isolation and maintainability.
 */
data class ResolvedLink(val url: String, val headers: Map<String, String>)

sealed class ArabseedSource {
    data class VisibleSource(val url: String, val name: String) : ArabseedSource()
    data class LazySource(
        val postId: String, 
        val quality: String, 
        val serverId: String, 
        val csrfToken: String, 
        val baseUrl: String
    ) : ArabseedSource()
    data class DirectEmbed(val url: String) : ArabseedSource()
    data class VideoResult(
        val url: String, 
        val name: String, 
        val headers: Map<String, String> = emptyMap()
    ) : ArabseedSource()
}

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

    // Cache to propagate headers (UA, Referer) to HLS segments from different domains
    private val domainHeaderCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

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
        Log.d(name, "[search] Query: '$query'")

        // Use current domain from service
        val activeUrl = "https://${httpService.currentDomain}"
        val types = listOf("movies", "series")
        
        return coroutineScope {
            types.map { type ->
                async {
                    val searchUrl = "$activeUrl/find/?word=$query&type=$type"
                    Log.d(name, "[search] GET $searchUrl")
                    
                    val doc = httpService.getDocument(searchUrl)
                    
                    if (doc != null) {
                        val items = parser.parseSearch(doc)
                        Log.d(name, "[search] Parsed ${items.size} items for type '$type'")
                        items
                    } else {
                        Log.e(name, "[search] Failed to get document for type '$type'")
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.url }.map { item ->
                newMovieSearchResponse(item.title, item.url, if(item.isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                }
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
            // 1. Always parse episodes from the current DOM (Active Season)
            val domEpisodes = data.episodes ?: parser.parseEpisodes(doc, null)
            Log.d("ArabseedV2", "[load] DOM episodes found: ${domEpisodes.size}")
            val episodes = mutableListOf<ParsedEpisode>()
            episodes.addAll(domEpisodes)

            // 2. Fetch other seasons (AJAX Style)
            val seasonDataList = parser.parseSeasonsWithPostId(doc)
            Log.d("ArabseedV2", "[load] Season Data found: ${seasonDataList.size} -> $seasonDataList")
            
            if (seasonDataList.isNotEmpty()) {
                coroutineScope {
                    val otherEpisodes = seasonDataList.map { (seasonNum, postId) ->
                        async {
                            try {
                                if (seasonNum == domEpisodes.firstOrNull()?.season) return@async emptyList()

                                val payload = mapOf(
                                    "season_id" to postId,
                                    "csrf_token" to (data.csrfToken ?: "")
                                )
                                Log.d("ArabseedV2", "[load] Fetching season $seasonNum (ID: $postId) via AJAX. Payload: $payload")
                                
                                val result = httpService.postDebug(
                                    url = "/season__episodes/",
                                    data = payload,
                                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                    referer = url
                                )
                                
                                if (result.success && result.html != null) {
                                    Log.d("ArabseedV2", "[load] AJAX JSON (S$seasonNum): ${result.html.take(1000)}")
                                    val sEps = parser.parseEpisodesFromAjax(result.html, seasonNum)
                                    Log.d("ArabseedV2", "[load] Season $seasonNum fetched: ${sEps.size} episodes. First 3: ${sEps.take(3)}")
                                    sEps
                                } else {
                                    Log.w("ArabseedV2", "[load] Failed to fetch season $seasonNum (null response)")
                                    emptyList()
                                }
                            } catch (e: Exception) {
                                Log.e("ArabseedV2", "Failed to fetch season $seasonNum: ${e.message}")
                                emptyList<ParsedEpisode>()
                            }
                        }
                    }.awaitAll().flatten()
                    episodes.addAll(otherEpisodes)
                }
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

            Log.d("ArabseedV2", "[load] Final episodes count: ${convertedEpisodes.size}")
            Log.d("ArabseedV2", "[load] Final seasons: ${seasonNames.map { it.name }}")

            newTvSeriesLoadResponse(data.title, url, TvType.TvSeries, convertedEpisodes) {
                this.posterUrl = data.posterUrl
                this.posterHeaders = httpService.getImageHeaders()  // CRITICAL: Image headers
                this.year = data.year
                this.plot = data.plot
                this.tags = data.tags
                if (seasonNames.isNotEmpty()) {
                    this.seasonNames = seasonNames
                }
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
        
        Log.d("ArabseedV2", "[loadLinks] Watch page resolved. Parsing sources...")
        val availableQualities = parser.extractQualities(watchDoc)
        val defaultQuality = parser.extractDefaultQuality(watchDoc, availableQualities)
        val globalPostId = parser.extractPostId(watchDoc) ?: ""
        val csrfToken = parser.extractCsrfToken(doc) ?: ""
        val visibleServers = parser.extractVisibleServers(watchDoc)
        val directEmbeds = parser.extractDirectEmbeds(watchDoc)
        
        Log.d("ArabseedV2", "[loadLinks] Qualities: ${availableQualities.map { it.quality }}, Default: $defaultQuality")
        Log.d("ArabseedV2", "[loadLinks] GlobalPostID: ${globalPostId.ifBlank { "N/A" }}, CSRF: ${if(csrfToken.isNotBlank()) "FOUND" else "MISSING"}")

        val currentBaseUrl = try {
            val uri = java.net.URI(watchDoc.location())
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "https://${httpService.currentDomain}"
        }

        // 1. COLLECT SOURCES
        val sources = collectSources(
            availableQualities = availableQualities,
            defaultQuality = defaultQuality,
            visibleServers = visibleServers,
            directEmbeds = directEmbeds,
            globalPostId = globalPostId,
            csrfToken = csrfToken,
            currentBaseUrl = currentBaseUrl
        )
        
        if (sources.isEmpty()) {
            Log.w("ArabseedV2", "[loadLinks] No sources collected, returning false")
            return false
        }
        
        Log.i("ArabseedV2", "[loadLinks] Collected ${sources.size} sources. Starting sequential processing...")
        
        // 2. ITERATE & RESOLVE WITH DYNAMIC LIST
        // - Failed sources are removed from list
        // - Successful source is replaced with found video at top of list
        val workingSources = sources.toMutableList()
        var currentIndex = 0
        var successCount = 0
        
        while (currentIndex < workingSources.size) {
            val source = workingSources[currentIndex]
            
            // Skip already-extracted videos - they're already at the top
            if (source is ArabseedSource.VideoResult) {
                Log.d("ArabseedV2", "[loadLinks] Skipping already-extracted video at index $currentIndex")
                currentIndex++
                continue
            }
            
            try {
                val sourceUrl = resolveSourceUrl(source)
                
                if (sourceUrl.isNullOrBlank()) {
                    Log.w("ArabseedV2", "[loadLinks] Empty URL for source at index $currentIndex, removing...")
                    workingSources.removeAt(currentIndex)
                    continue
                }
                
                Log.i("ArabseedV2", "[loadLinks] Processing source $currentIndex/${workingSources.size}: $sourceUrl")
                
                // Try to extract video (standard extractors -> sniffer)
                val videoResult = extractVideo(sourceUrl, currentBaseUrl)
                
                if (videoResult != null) {
                    Log.i("ArabseedV2", "[loadLinks] SUCCESS for source $currentIndex: ${videoResult.url.take(80)}")
                    cacheHeaders(videoResult)
                    
                    // Create video link from result
                    val videoLink = createExtractorLink(source, videoResult, currentBaseUrl)
                    
                    // Replace current source with video at the TOP (index 0)
                    // Remove current source and insert video at beginning
                    workingSources.removeAt(currentIndex)
                    workingSources.add(0, ArabseedSource.VideoResult(videoLink.url, videoLink.name, videoResult.headers))
                    successCount++
                    
                    Log.i("ArabseedV2", "[loadLinks] Video moved to top. List now: ${workingSources.size} items")
                    
                    // Move to next source (don't reprocess the video we just added at index 0)
                    // Since we added at 0 and removed from currentIndex, we just continue with same index
                    // which now points to the next item (everything shifted left after removal)
                    continue
                } else {
                    // Both methods failed - remove this source from list
                    Log.w("ArabseedV2", "[loadLinks] Source $currentIndex failed both methods, removing from list")
                    workingSources.removeAt(currentIndex)
                    // Don't increment index since we removed current item
                    continue
                }
                
            } catch (e: Exception) {
                Log.e("ArabseedV2", "[loadLinks] Error processing source $currentIndex: ${e.message}")
                // On error, remove the source
                workingSources.removeAt(currentIndex)
                continue
            }
        }
        
        Log.i("ArabseedV2", "[loadLinks] Processing complete. ${workingSources.size} sources remain, $successCount successful")
        
        // Return all remaining sources as ExtractorLinks
        // First item(s) are videos, rest are original sources
        var anyFound = false
        workingSources.forEach { source ->
            when (source) {
                is ArabseedSource.VideoResult -> {
                    Log.i("ArabseedV2", "[loadLinks] Returning video: ${source.url.take(80)}")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = source.name,
                            url = source.url,
                            type = if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$currentBaseUrl/"
                            this.quality = Qualities.Unknown.value
                            this.headers = source.headers
                        }
                    )
                    anyFound = true
                }
                is ArabseedSource.VisibleSource -> {
                    Log.d("ArabseedV2", "[loadLinks] Returning visible source: ${source.url.take(80)}")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = source.name,
                            url = source.url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$currentBaseUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                is ArabseedSource.DirectEmbed -> {
                    Log.d("ArabseedV2", "[loadLinks] Returning direct embed: ${source.url.take(80)}")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Direct Source",
                            url = source.url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$currentBaseUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                is ArabseedSource.LazySource -> {
                    // For unprocessed lazy sources, return them as-is for retry
                    Log.d("ArabseedV2", "[loadLinks] Returning lazy source: Server ${source.serverId} (${source.quality}p)")
                    val lazyUrl = "https://arabseed-lazy.com/?post_id=${source.postId}&quality=${source.quality}&server=${source.serverId}&csrf_token=${source.csrfToken}&base=${source.baseUrl}"
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Server ${source.serverId} (${source.quality}p)",
                            url = lazyUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$currentBaseUrl/"
                            this.quality = source.quality.toIntOrNull() ?: Qualities.Unknown.value
                        }
                    )
                }
                else -> {
                    Log.w("ArabseedV2", "[loadLinks] Unknown source type: ${source.javaClass.simpleName}")
                }
            }
        }
        
        Log.d("ArabseedV2", "[loadLinks] END - Returned ${workingSources.size} sources, found=$anyFound")
        return anyFound || workingSources.isNotEmpty()
    }
    
    /**
     * Collects all video sources from the watch page.
     * Creates LazySource, VisibleSource, or DirectEmbed objects based on what's available.
     */
    private fun collectSources(
        availableQualities: List<ArabseedParser.QualityData>,
        defaultQuality: Int,
        visibleServers: List<ArabseedParser.ServerData>,
        directEmbeds: List<String>,
        globalPostId: String,
        csrfToken: String,
        currentBaseUrl: String
    ): List<ArabseedSource> {
        Log.d("ArabseedV2", "[collectSources] Starting with ${availableQualities.size} qualities, ${visibleServers.size} visible servers")
        
        val sources = mutableListOf<ArabseedSource>()
        
        // A. Qualities (Lazy)
        val sortedQualities = availableQualities.sortedByDescending { it.quality }
        sortedQualities.forEach { qData ->
            if (qData.quality != defaultQuality) {
                // Non-default qualities use server 1-5
                val anyPostId = visibleServers.firstOrNull()?.postId?.ifBlank { globalPostId } ?: globalPostId
                if (anyPostId.isNotBlank() && csrfToken.isNotBlank()) {
                    for (serverId in 1..5) {
                        sources.add(ArabseedSource.LazySource(
                            postId = anyPostId,
                            quality = qData.quality.toString(),
                            serverId = serverId.toString(),
                            csrfToken = csrfToken,
                            baseUrl = currentBaseUrl
                        ))
                    }
                }
            } else {
                 // Default quality: Process visible servers
                 visibleServers.forEach { server ->
                     if (server.dataLink.isNotBlank()) {
                         sources.add(ArabseedSource.VisibleSource(server.dataLink, "Server ${server.serverId} ($defaultQuality)"))
                     } else if (server.postId.isNotBlank() && csrfToken.isNotBlank()) {
                         sources.add(ArabseedSource.LazySource(
                             postId = server.postId,
                             quality = defaultQuality.toString(),
                             serverId = server.serverId,
                             csrfToken = csrfToken,
                             baseUrl = currentBaseUrl
                         ))
                     }
                 }
            }
        }
        
        // B. Direct Embeds (Fallback)
        if (sources.isEmpty() && directEmbeds.isNotEmpty()) {
            directEmbeds.forEach { url ->
                sources.add(ArabseedSource.DirectEmbed(url))
            }
        }
        
        Log.i("ArabseedV2", "[collectSources] Collected ${sources.size} sources")
        return sources
    }
    
    /**
     * Resolves a source to its actual URL.
     * For LazySource: Makes AJAX call to get embed URL
     * For others: Returns the URL directly
     */
    private suspend fun resolveSourceUrl(source: ArabseedSource): String? {
        return when (source) {
            is ArabseedSource.LazySource -> {
                val lazyUrl = "https://arabseed-lazy.com/?post_id=${source.postId}&quality=${source.quality}&server=${source.serverId}&csrf_token=${source.csrfToken}&base=${source.baseUrl}"
                Log.i("ArabseedV2", "[resolveSourceUrl] Resolving Lazy URL: $lazyUrl")
                resolveLazyUrl(lazyUrl)
            }
            is ArabseedSource.VisibleSource -> {
                Log.d("ArabseedV2", "[resolveSourceUrl] Using visible source: ${source.url.take(80)}")
                source.url
            }
            is ArabseedSource.DirectEmbed -> {
                Log.d("ArabseedV2", "[resolveSourceUrl] Using direct embed: ${source.url.take(80)}")
                source.url
            }
            is ArabseedSource.VideoResult -> {
                Log.d("ArabseedV2", "[resolveSourceUrl] Video already resolved: ${source.url.take(80)}")
                source.url
            }
        }
    }
    
    /**
     * Attempts to extract video from a URL.
     * First tries standard extractors (8s timeout), then falls back to sniffer (60s timeout).
     * Returns ResolvedLink on success, null on failure.
     */
    private suspend fun extractVideo(sourceUrl: String, referer: String): ResolvedLink? {
        // STEP 1: Try standard extractors
        Log.d("ArabseedV2", "[extractVideo] STEP 1: Trying standard extractors...")
        val standardResult = awaitExtractorWithResult(sourceUrl, "$referer/", 8_000L)
        
        if (standardResult != null) {
            Log.i("ArabseedV2", "[extractVideo] SUCCESS via standard extractor: ${standardResult.url.take(80)}")
            return standardResult
        }
        
        // STEP 2: Standard extractors failed, try sniffer
        Log.w("ArabseedV2", "[extractVideo] STEP 2: Standard extractors failed, trying sniffer...")
        val snifferResult = awaitSnifferResult(sourceUrl, "$referer/", 60_000L)
        
        if (snifferResult != null) {
            Log.i("ArabseedV2", "[extractVideo] SUCCESS via sniffer: ${snifferResult.url.take(80)}")
            return snifferResult
        }
        
        Log.w("ArabseedV2", "[extractVideo] Both methods failed")
        return null
    }
    
    /**
     * Resolves a lazy URL to the actual embed URL via AJAX call.
     */
    private suspend fun resolveLazyUrl(url: String): String? {
        val uri = try { java.net.URI(url) } catch (e: Exception) { 
            Log.e("ArabseedV2", "[resolveLazyUrl] Invalid URI: $url")
            return null 
        }
        
        val query = uri.query ?: ""
        val queryParams = query.split("&").associate { 
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else it to ""
        }
        
        val postId = queryParams["post_id"] ?: return null
        val quality = queryParams["quality"] ?: return null
        val server = queryParams["server"] ?: return null
        val csrfToken = queryParams["csrf_token"] ?: return null
        val baseUrlForAjax = queryParams["base"] ?: "https://asd.pics"
        
        val result = httpService.postDebug(
            "$baseUrlForAjax/get__watch__server/",
            data = mapOf(
                "post_id" to postId,
                "quality" to quality,
                "server" to server,
                "csrf_token" to csrfToken
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to baseUrlForAjax,
                "Referer" to "$baseUrlForAjax/"
            )
        )
        
        if (!result.success || result.html == null) {
            Log.e("ArabseedV2", "[resolveLazyUrl] AJAX failed: ${result.responseCode}")
            return null
        }
        
        val serverResponse = result.html
        var embedUrl = ""
        
        // Parse JSON/HTML response
        if (serverResponse.trim().startsWith("{")) {
            val serverMatch = Regex("\"server\"\\s*:\\s*\"([^\"]+)\"").find(serverResponse)
            if (serverMatch != null) {
                embedUrl = serverMatch.groupValues[1].replace("\\/", "/")
            } else if (serverResponse.contains("\"html\"")) {
                val htmlMatch = Regex("\"html\"\\s*:\\s*\"([^\"]+)\"").find(serverResponse)
                val escapedHtml = htmlMatch?.groupValues?.get(1)
                if (escapedHtml != null) {
                    val unescaped = escapedHtml.replace("\\/", "/").replace("\\\"", "\"")
                    embedUrl = org.jsoup.Jsoup.parse(unescaped).select("iframe").attr("src")
                }
            }
        } else {
            val serverDoc = org.jsoup.Jsoup.parse(serverResponse, "$baseUrlForAjax/")
            embedUrl = serverDoc.select("iframe").attr("src")
        }
        
        return if (embedUrl.isNotBlank()) embedUrl else null
    }
    
    /**
     * Awaits extractor result and returns the ResolvedLink or null.
     */
    private suspend fun awaitExtractorWithResult(targetUrl: String, referer: String, timeoutMs: Long): ResolvedLink? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                val deferred = CompletableDeferred<ResolvedLink?>()
                var found = false
                loadExtractor(targetUrl, referer, {}, { link ->
                    if (!found) {
                        found = true
                        deferred.complete(ResolvedLink(link.url, link.headers))
                    }
                })
                deferred.await()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
    
    /**
     * Uses visible SnifferExtractor (FULLSCREEN mode) to sniff video.
     * This shows the WebView UI and properly renders the player.
     * Returns immediately when first video is found.
     */
    private suspend fun awaitSnifferResult(targetUrl: String, referer: String, timeoutMs: Long): ResolvedLink? {
        Log.i("ArabseedV2", "[awaitSnifferResult] === START === target=${targetUrl.take(80)}")
        
        return try {
            withTimeoutOrNull(timeoutMs) {
                Log.d("ArabseedV2", "[awaitSnifferResult] Created deferred, timeout=$timeoutMs")
                val deferred = CompletableDeferred<ResolvedLink?>()
                var found = false
                var callbackReceived = false
                
                // Create sniffer URL
                val sniffUrl = com.cloudstream.shared.extractors.SnifferExtractor.createSnifferUrl(targetUrl, referer)
                Log.i("ArabseedV2", "[awaitSnifferResult] Sniffer URL: $sniffUrl")
                
                // Use loadExtractor which will trigger SnifferExtractor with FULLSCREEN mode
                Log.d("ArabseedV2", "[awaitSnifferResult] Calling loadExtractor...")
                loadExtractor(sniffUrl, referer, {}, { link ->
                    Log.i("ArabseedV2", "[awaitSnifferResult] CALLBACK FIRED! URL=${link.url.take(80)}")
                    callbackReceived = true
                    if (!found) {
                        found = true
                        Log.i("ArabseedV2", "[awaitSnifferResult] Completing deferred with link")
                        deferred.complete(ResolvedLink(link.url, link.headers))
                        Log.i("ArabseedV2", "[awaitSnifferResult] Deferred completed successfully")
                    } else {
                        Log.w("ArabseedV2", "[awaitSnifferResult] Already found, ignoring duplicate")
                    }
                })
                
                Log.d("ArabseedV2", "[awaitSnifferResult] loadExtractor returned, awaiting deferred...")
                val result = deferred.await()
                Log.i("ArabseedV2", "[awaitSnifferResult] Deferred awaited, result=${result != null}, callbackReceived=$callbackReceived")
                result
            }
        } catch (e: TimeoutCancellationException) {
            Log.e("ArabseedV2", "[awaitSnifferResult] TIMEOUT! No video found within ${timeoutMs}ms")
            null
        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.w("ArabseedV2", "[awaitSnifferResult] Cancelled")
                throw e
            }
            Log.e("ArabseedV2", "[awaitSnifferResult] EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            Log.i("ArabseedV2", "[awaitSnifferResult] === END ===")
        }
    }
    
    /**
     * Caches headers for domain.
     */
    private fun cacheHeaders(resolved: ResolvedLink) {
        try {
            val resolvedHost = java.net.URI(resolved.url).host
            if (resolved.headers.isNotEmpty()) {
                domainHeaderCache[resolvedHost] = resolved.headers
            }
            // Also store CDN cookies
            val rawCookie = resolved.headers.entries.find { it.key.equals("Cookie", ignoreCase = true) }?.value
            if (!rawCookie.isNullOrBlank()) {
                val cookieMap = rawCookie.split(";").associate {
                    val parts = it.split("=", limit = 2)
                    parts[0].trim() to (if (parts.size == 2) parts[1].trim() else "")
                }
                httpService.storeCdnCookies(resolved.url, cookieMap)
            }
        } catch (e: Exception) {}
    }
    
    /**
     * Creates an ExtractorLink from source and resolved link.
     */
    private suspend fun createExtractorLink(source: ArabseedSource, resolved: ResolvedLink, currentBaseUrl: String): ExtractorLink {
        val (linkName, quality) = when (source) {
            is ArabseedSource.LazySource -> "Server ${source.serverId} (${source.quality}p)" to (source.quality.toIntOrNull() ?: Qualities.Unknown.value)
            is ArabseedSource.VisibleSource -> source.name to Qualities.Unknown.value
            is ArabseedSource.DirectEmbed -> "Direct Source" to Qualities.Unknown.value
            is ArabseedSource.VideoResult -> source.name to Qualities.Unknown.value
        }
        
        return newExtractorLink(
            source = name,
            name = linkName,
            url = resolved.url,
            type = if (resolved.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = "$currentBaseUrl/"
            this.quality = quality
            this.headers = resolved.headers
        }
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
            val host = request.url.host
            
            // Automated Header Propagation (Fix for Segment 403s / Source Error)
            val cachedHeaders = domainHeaderCache[host]
            if (cachedHeaders != null) {
                // Only apply if not already present to avoid duplication
                val builder = request.newBuilder()
                val currentHeaders = request.headers
                
                cachedHeaders.forEach { (key, value) ->
                    if (currentHeaders[key] == null) {
                        builder.header(key, value)
                    }
                }
                return@Interceptor chain.proceed(builder.build())
            }

            return@Interceptor chain.proceed(request)
        }
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
