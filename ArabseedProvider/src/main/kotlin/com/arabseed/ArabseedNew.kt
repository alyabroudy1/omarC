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
        val sources = java.util.LinkedList<ArabseedSource>()
        
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
        
        Log.i("ArabseedV2", "[loadLinks] Collected ${sources.size} sources. Starting sequential processing...")
        
        // 2. ITERATE & RESOLVE
        var anyFound = false
        
        for (source in sources) {
            if (anyFound) break
            
            try {
                val sourceUrl = when (source) {
                    is ArabseedSource.LazySource -> {
                        // Resolve lazy source to get actual embed URL
                        val lazyUrl = "https://arabseed-lazy.com/?post_id=${source.postId}&quality=${source.quality}&server=${source.serverId}&csrf_token=${source.csrfToken}&base=${source.baseUrl}"
                        Log.i("ArabseedV2", "[loadLinks] Resolving Lazy URL: $lazyUrl")
                        resolveLazyUrl(lazyUrl) // Get the actual embed URL from AJAX
                    }
                    is ArabseedSource.VisibleSource -> source.url
                    is ArabseedSource.DirectEmbed -> source.url
                }
                
                if (sourceUrl.isNullOrBlank()) {
                    Log.w("ArabseedV2", "[loadLinks] Empty URL for source, skipping...")
                    continue
                }
                
                Log.i("ArabseedV2", "[loadLinks] Processing source: $sourceUrl")
                
                // STEP 1: Try loadExtractor (standard extractors)
                Log.d("ArabseedV2", "[loadLinks] STEP 1: Trying standard extractors...")
                val standardResult = awaitExtractorWithResult(sourceUrl, "$currentBaseUrl/", 8_000L)
                
                if (standardResult != null) {
                    // Video found via standard extractor - break immediately
                    Log.i("ArabseedV2", "[loadLinks] SUCCESS via standard extractor: ${standardResult.url.take(80)}")
                    cacheHeaders(standardResult)
                    callback(createExtractorLink(source, standardResult, currentBaseUrl))
                    anyFound = true
                    break
                }
                
                // STEP 2: Standard extractors failed, try sniffer
                Log.w("ArabseedV2", "[loadLinks] STEP 2: Standard extractors failed, trying sniffer...")
                
                // Wait for sniffer to complete - call directly, not through loadExtractor
                val snifferResult = awaitSnifferResult(sourceUrl, "$currentBaseUrl/", 60_000L)
                
                if (snifferResult != null) {
                    // Video found via sniffer - break immediately
                    Log.i("ArabseedV2", "[loadLinks] SUCCESS via sniffer: ${snifferResult.url.take(80)}")
                    cacheHeaders(snifferResult)
                    callback(createExtractorLink(source, snifferResult, currentBaseUrl))
                    anyFound = true
                    break
                }
                
                // Both failed - move to next source
                Log.w("ArabseedV2", "[loadLinks] Both standard extractors and sniffer failed for this source")
                
            } catch (e: Exception) {
                Log.e("ArabseedV2", "[loadLinks] Error processing source: ${e.message}")
            }
        }
        
        Log.d("ArabseedV2", "[loadLinks] END found=$anyFound")
        return anyFound
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
     * Runs video sniffer using VideoSniffingStrategy with auto-play, auto-mute, skip ads.
     * This runs HEADLESS (no UI) and automatically interacts with the player.
     */
    private suspend fun awaitSnifferResult(targetUrl: String, referer: String, timeoutMs: Long): ResolvedLink? {
        Log.d("ArabseedV2", "[awaitSnifferResult] Starting VideoSniffingStrategy for: ${targetUrl.take(80)}")
        
        return try {
            val activity = ActivityProvider.currentActivity
            if (activity == null) {
                Log.e("ArabseedV2", "[awaitSnifferResult] No activity available")
                return null
            }
            
            // Use VideoSniffingStrategy which has auto-play, auto-mute, skip ads built-in
            val sniffer = com.cloudstream.shared.strategy.VideoSniffingStrategy(
                context = activity,
                timeout = timeoutMs
            )
            
            // Get cookies from SessionProvider
            val cookies = com.cloudstream.shared.session.SessionProvider.getCookies()
            val userAgent = com.cloudstream.shared.session.SessionProvider.getUserAgent()
            
            Log.d("ArabseedV2", "[awaitSnifferResult] Starting sniff with cookies: ${cookies.size}")
            
            val sources = sniffer.sniff(
                url = targetUrl,
                userAgent = userAgent,
                cookies = cookies,
                extraHeaders = mapOf("Referer" to referer)
            )
            
            if (sources.isNotEmpty()) {
                val source = sources.first()
                Log.i("ArabseedV2", "[awaitSnifferResult] Video found: ${source.url.take(80)}")
                
                // Build headers
                val finalHeaders = mutableMapOf<String, String>()
                finalHeaders["Referer"] = targetUrl
                finalHeaders["User-Agent"] = userAgent
                finalHeaders.putAll(source.headers)
                finalHeaders["Accept"] = "*/*"
                
                // Add cookies
                val webViewCookies = try {
                    android.webkit.CookieManager.getInstance().getCookie(source.url)
                } catch (e: Exception) { null }
                
                if (!webViewCookies.isNullOrBlank()) {
                    finalHeaders["Cookie"] = webViewCookies
                }
                
                return ResolvedLink(source.url, finalHeaders)
            } else {
                Log.w("ArabseedV2", "[awaitSnifferResult] No video sources found")
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("ArabseedV2", "[awaitSnifferResult] Error: ${e.message}")
            null
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
