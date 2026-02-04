package com.arabseed

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.provider.ProviderConfig
import com.arabseed.service.ProviderHttpService
import com.arabseed.service.parsing.ParserInterface.ParsedEpisode
import com.arabseed.utils.ActivityProvider
import com.arabseed.utils.PluginContext
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response

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
        
        // 1. Get the watch page document
        val doc = httpService.getDocument(data)
        if (doc == null) {
            Log.e(name, "[loadLinks] Failed to get document for data URL")
            // Fallback: try to get player URLs directly
            return loadLinksFromService(data, subtitleCallback, callback)
        }
        
        // 2. Check if we need to navigate to watch page
        var watchDoc = doc
        val isWatchPage = doc.select("ul > li[data-link], ul > h3").isNotEmpty() || 
                         doc.select("iframe[name=player_iframe]").isNotEmpty()
        
        if (!isWatchPage) {
            val watchUrl = doc.select("a.watch__btn").attr("href")
            if (watchUrl.isNotBlank()) {
                Log.d(name, "[loadLinks] Following watch button: ${watchUrl.take(60)}")
                watchDoc = httpService.getDocument(watchUrl) ?: doc
            } else {
                Log.w(name, "[loadLinks] No watch button, trying data URL directly")
            }
        }
        
        // 3. Extract direct embeds FIRST (priority)
        val directEmbeds = parser.extractDirectEmbeds(watchDoc)
        Log.d(name, "[loadLinks] Direct embeds found: ${directEmbeds.size}")
        
        var found = false
        
        directEmbeds.forEach { embedUrl ->
            Log.d(name, "[loadLinks] Processing embed: ${embedUrl.take(60)}")
            val isPrivate = embedUrl.contains("arabseed") || embedUrl.contains("asd") || embedUrl.contains("reviewrate")
            
            if (isPrivate) {
                // Pass directly to callback for lazy resolution via getVideoInterceptor
                // (Matches built-in Arabseed.kt:361-371)
                Log.d(name, "[loadLinks] Passing private embed directly for lazy resolution")
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
            } else {
                loadExtractor(embedUrl, subtitleCallback, callback)
                found = true
            }
        }
        
        // 4. Extract server links and emit as lazy sources
        // Following built-in Arabseed.kt:403-427 approach
        val serverLinks = watchDoc.select("ul > li[data-link]")
        Log.d(name, "[loadLinks] Server links found: ${serverLinks.size}")
        
        var serverIndex = 0
        serverLinks.forEach { element ->
            val rawUrl = element.attr("data-link").ifBlank { element.attr("data-url") }
            if (rawUrl.isNotBlank()) {
                serverIndex++
                val serverName = element.selectFirst("span")?.text() ?: "Server $serverIndex"
                Log.d(name, "[loadLinks] Server link[$serverIndex]: ${rawUrl.take(60)}")
                
                val resolvedUrl = parser.resolveServerLink(rawUrl) ?: rawUrl
                val fullUrl = if (resolvedUrl.startsWith("/")) {
                    "https://${java.net.URI(watchDoc.location()).host}$resolvedUrl"
                } else resolvedUrl
                
                // Emit as lazy source - will be resolved by getVideoInterceptor
                callback(
                    newExtractorLink(
                        name,
                        "$name - $serverName",
                        fullUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = watchDoc.location()
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        
        // 5. Fallback: use getPlayerUrls service
        if (!found) {
            Log.w(name, "[loadLinks] No links from page, trying service fallback")
            return loadLinksFromService(data, subtitleCallback, callback)
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
     * Matches built-in Arabseed.kt:436-490
     * Handles: reviewrate, play.php, asd.pics/play, and other video embeds
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val url = extractorLink.url
        
        // Check if this is a lazy URL that needs resolution
        val needsResolution = url.contains("reviewrate") ||
                             url.contains("/play.php") ||
                             url.contains("/play/?id=") ||
                             url.contains("stmix.io") ||
                             url.contains("vidmoly") ||
                             url.contains("up4fun") ||
                             url.contains("savefiles") ||
                             url.contains("bysezejataos")
        
        if (needsResolution) {
            return Interceptor { chain ->
                val request = chain.request()
                val urlString = request.url.toString()
                
                Log.d(name, "[getVideoInterceptor] Resolving: ${urlString.take(80)}")
                
                try {
                    val referer = request.header("Referer")
                    val headers = mutableMapOf<String, String>()
                    if (!referer.isNullOrBlank()) headers["Referer"] = referer
                    
                    // Add session headers
                    httpService.getImageHeaders().forEach { (k, v) -> headers[k] = v }
                    
                    // Fetch the embed page
                    val html = kotlinx.coroutines.runBlocking {
                        try {
                            app.get(urlString, headers = headers).text
                        } catch (e: Exception) {
                            Log.w(name, "[getVideoInterceptor] First attempt failed: ${e.message}")
                            try { app.get(urlString).text } catch (e2: Exception) { "" }
                        }
                    }
                    
                    // Extract video URL using multiple patterns
                    var videoUrl: String? = null
                    
                    // Pattern 1: file: "..."
                    if (videoUrl == null) {
                        videoUrl = Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                    }
                    // Pattern 2: <source src="...">
                    if (videoUrl == null) {
                        videoUrl = Regex("""<source[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                    }
                    // Pattern 3: sources: [{file: "..."}]
                    if (videoUrl == null) {
                        videoUrl = Regex("""sources:\s*\[\{[^}]*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                    }
                    // Pattern 4: Direct video URL in response (m3u8/mp4)
                    if (videoUrl == null) {
                        videoUrl = Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""").find(html)?.groupValues?.get(1)
                    }
                    // Pattern 5: JWPlayer setup
                    if (videoUrl == null) {
                        videoUrl = Regex("""jwplayer.*?file:\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
                    }
                    
                    Log.d(name, "[getVideoInterceptor] Resolved: ${videoUrl?.take(80)}")
                    
                    if (videoUrl != null && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                        val newRequest = request.newBuilder().url(videoUrl).build()
                        chain.proceed(newRequest)
                    } else {
                        Log.w(name, "[getVideoInterceptor] Could not extract video URL")
                        chain.proceed(request)
                    }
                } catch (e: Exception) {
                    Log.e(name, "[getVideoInterceptor] Error: ${e.message}")
                    chain.proceed(request)
                }
            }
        }
        return null
    }
}
