package com.arabseed

import android.content.Context
import com.arabseed.utils.ActivityProvider
import com.arabseed.utils.PluginContext
import com.arabseed.service.ProviderConfig
import com.arabseed.service.ProviderHttpService
import com.arabseed.service.parsing.BaseParser.ParsedEpisode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import com.arabseed.extractors.ArabseedLazyExtractor

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
        private const val GITHUB_CONFIG = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/arabseed.json"
        // EXACT UA from FaselHD
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
    }
    
    private val parser = ArabseedParser()
    
    private val http by lazy {
        ProviderHttpService.create(
            context = PluginContext.context!!,
            config = ProviderConfig(
                name = this.name,
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
        // Initialize session (loads cookies from disk) - CRITICAL for image loading
        http.ensureInitialized()
        
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
        Log.d(TAG, "[search] Query: '$query'")
        http.ensureInitialized()
        
        // Sync mainUrl with the active session domain (from headers)
        http.getImageHeaders()["Referer"]?.let { referer ->
            this.mainUrl = referer.trimEnd('/')
        }
        val activeUrl = this.mainUrl
        Log.d(TAG, "[search] Active Domain: $activeUrl")
        
        // Simulating reference: GET to /find/?word=...&type=... for both "movies" and "series"
        val types = listOf("movies", "series")
        
        return coroutineScope {
            types.map { type ->
                async {
                    val searchUrl = "$activeUrl/find/?word=$query&type=$type"
                    Log.d(TAG, "[search] GET $searchUrl")
                    
                    val doc = http.getDocument(
                        url = searchUrl,
                        headers = mapOf("Referer" to mainUrl)
                    )
                    
                    if (doc != null) {
                        val html = doc.html()
                        Log.d(TAG, "[search] Response HTML for $type (Size: ${html.length}):\n${html.take(2000)}...") // Log first 2000 chars
                        
                        val items = parser.parseSearch(doc)
                        Log.d(TAG, "[search] Parsed ${items.size} items for type '$type'")
                        items
                    } else {
                        Log.e(TAG, "[search] Failed to get document for type '$type'")
                        emptyList()
                    }
                }
            }.awaitAll().flatten().map { item ->
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
    


    // ==================== INTERCEPTOR ====================

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        // Handle both lazy servers and reviewrate direct embeds
        if (extractorLink.url.contains("/get__watch__server/") || extractorLink.url.contains("reviewrate")) {
            return Interceptor { chain ->
                val request = chain.request()
                val url = request.url
                val urlString = url.toString()
                
                // Handle JIT Direct Embeds (ReviewRate)
                if (urlString.contains("reviewrate")) {
                    Log.d(TAG, "[getVideoInterceptor] Resolving direct embed: $urlString")
                    try {
                        val referer = request.header("Referer")
                        
                        val directUrl = runBlocking {
                            // Robust Manual Extraction
                            // Try with provided Referer first
                            var response = try {
                                val headers = if (!referer.isNullOrBlank()) mapOf("Referer" to referer) else emptyMap()
                                app.get(urlString, headers = headers).text
                            } catch (e: Exception) {
                                null
                            }
                            
                            // Fallback: Try without Referer
                            if (response == null) {
                                 Log.w(TAG, "[getVideoInterceptor] First attempt failed, retrying without specific referer...")
                                 response = try { app.get(urlString).text } catch(e: Exception) { null }
                            }
                            
                            val html = response ?: ""
                            // Debug: Log the HTML to see what we are getting
                            if (html.isNotBlank()) Log.d(TAG, "[getVideoInterceptor] ReviewRate HTML len=${html.length}")
                            
                            var foundUrl: String? = null
                            
                            // Pattern 1: file: "..."
                            if (foundUrl == null) foundUrl = Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                            // Pattern 2: <source src="...">
                            if (foundUrl == null) foundUrl = Regex("""<source[^>]+src=["']([^"']+\.mp4)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                            // Pattern 3: sources: [{file:"..."}]
                            if (foundUrl == null) foundUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                            // Pattern 4: source: "..."
                            if (foundUrl == null) foundUrl = Regex("""source:\s*["']([^"']+\.mp4)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                            // Pattern 5: URL inside script variable
                            if (foundUrl == null) foundUrl = Regex("""var\s+url\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                            
                            if (foundUrl != null) Log.d(TAG, "[getVideoInterceptor] Found match: $foundUrl")
                            else Log.w(TAG, "[getVideoInterceptor] No regex match found in HTML")
                            
                            return@runBlocking foundUrl
                        }
                        
                        if (!directUrl.isNullOrBlank()) {
                            Log.i(TAG, "[getVideoInterceptor] Resolved direct embed to: $directUrl")
                            return@Interceptor chain.proceed(
                                request.newBuilder()
                                    .url(directUrl)
                                    .header("Referer", urlString) // Use embed as referer for video
                                    .build()
                            )
                        } else {
                            Log.w(TAG, "[getVideoInterceptor] Failed to resolve direct link, but proceeding with original URL")
                            return@Interceptor chain.proceed(request)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[getVideoInterceptor] JIT resolution failed: ${e.message}")
                        return@Interceptor chain.proceed(request)
                    }
                }

                // Handle Lazy Servers
                if (urlString.contains("/get__watch__server/")) {
                    var resolvedLink: ExtractorLink? = null
                    
                    try {
                        runBlocking {
                             // Use the Extension's Lazy Extractor (com.arabseed.extractors)
                             val extractor = ArabseedLazyExtractor { endpoint, data, referer ->
                                 // Ensure we pass headers explicitly, especially X-Requested-With
                                 http.postText(
                                     endpoint, // should be relative path
                                     data,
                                     referer = referer,
                                     headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                                 )
                             }
                             extractor.getUrl(urlString, null, {}) { link ->
                                 resolvedLink = link
                             }
                        }
                    } catch(e: Exception) {
                        Log.e(TAG, "[getVideoInterceptor] Lazy resolution failed: ${e.message}")
                    }
                    
                    resolvedLink?.let { link ->
                         Log.d(TAG, "[getVideoInterceptor] Resolved to: ${link.url}")
                         val builder = request.newBuilder().url(link.url)
                         if (link.referer.isNotBlank()) {
                             builder.header("Referer", link.referer)
                         }
                         return@Interceptor chain.proceed(builder.build())
                    }
                }
                
                chain.proceed(request)
            }
        }
        return null
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
        
        // 3. Dynamic Quality Extraction
        // Extract available qualities from tabs
        val availableQualities = parser.extractQualities(watchDoc)
        val postId = parser.extractPostId(watchDoc)
        val csrfToken = parser.parseCsrfToken(doc) ?: "" // Need token for AJAX
        
        Log.d(TAG, "[loadLinks] Found qualities: ${availableQualities.map { it.quality }}")
        Log.d(TAG, "[loadLinks] PostID: $postId, CSRF: ${if(csrfToken.isNotBlank()) "FOUND" else "MISSING"}")
        

        
        var found = false
        
        // ==================== LEGACY LINKS (PRIORITY) ====================
        // Logic from Reference: Parse data-link elements grouped by H3 headers
        // This handles cases where servers are listed directly as links
        val elements = watchDoc.select("ul > li[data-link], ul > h3")
        val indexOperators = mutableListOf<Int>()
        elements.forEachIndexed { index, element ->
            if (element.`is`("h3")) {
                indexOperators.add(index)
            }
        }
        
        val watchLinks = if (indexOperators.isNotEmpty()) {
            indexOperators.map { index ->
                val endIndex = elements.drop(index + 1).indexOfFirst { it.`is`("h3") }.let { if (it == -1) elements.size else it + index + 1 }
                val qualityText = elements[index].text()
                val quality = Regex("""\d+""").find(qualityText)?.value?.toIntOrNull() ?: 0
                val links = elements.subList(index + 1, endIndex).filter { !it.`is`("h3") }
                quality to links
            }
        } else {
             // Fallback: use active quality from tabs if available
             val activeQuality = availableQualities.find { watchDoc.select("ul.qualities__list li[data-quality='${it.quality}']").hasClass("active") }?.quality ?: 0
             listOf(activeQuality to elements.filter { !it.`is`("h3") })
        }
        
        watchLinks.forEach { (quality, linkElements) ->
            linkElements.forEach { element ->
                val rawUrl = element.attr("data-link").ifBlank { element.attr("data-url") }
                val linkName = element.text()
                if (rawUrl.isNotBlank()) {
                    Log.d(TAG, "[loadLinks] Found legacy link: $linkName ($rawUrl)")
                    loadExtractor(rawUrl, watchDoc.location(), subtitleCallback) { link ->
                        // Enrich with quality if missing
                        if (link.quality == Qualities.Unknown.value && quality > 0) {
                             link.quality = quality
                        }
                        callback(link)
                        found = true
                    }
                }
            }
        }

        // ==================== DIRECT EMBEDS (PRIORITY) ====================
        val directEmbeds = parser.extractDirectEmbeds(watchDoc)
        Log.d(TAG, "[loadLinks] Found direct embeds: $directEmbeds")
        directEmbeds.forEach { embedUrl ->
            // Manual handling for ReviewRate
            if (embedUrl.contains("reviewrate")) {
                loadExtractor(embedUrl, watchDoc.location(), subtitleCallback) { link ->
                     // No op - standard loadExtractor handles it
                }
                // OR better: Emit directly as we do in built-in
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = embedUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = watchDoc.location()
                    }
                )
            } else {
                 loadExtractor(embedUrl, watchDoc.location(), subtitleCallback, callback)
            }
        }
        
        // ==================== VISIBLE SERVERS (LAZY) ====================
        var servers = parser.extractVisibleServers(watchDoc).toMutableList()
        Log.d(TAG, "[loadLinks] Visible servers: ${servers.size}")
        
        // Cross-product: Use all available qualities for ALL unique servers found
        // If no servers found, assume Server 0 (Auto) exists
        val uniqueServers = servers.distinctBy { it.serverId }.toMutableList()
        if (uniqueServers.isEmpty()) {
             // Fallback to Server 0 (Auto) if no explicit servers are listed
             uniqueServers.add(com.arabseed.ArabseedParser.ServerData(postId, 0, "0", "Server Auto"))
        }

        // Determine qualities to process (Prioritize extracted qualities, fallback to defaults)
        val qualitiesToProcess = if (availableQualities.isNotEmpty()) {
             availableQualities.map { it.quality }.distinct().sortedDescending()
        } else {
             // Fallback to qualities seen in server list, or default 720p
             val sQualities = servers.map { it.quality }.filter { it > 0 }.distinct().sortedDescending()
             if (sQualities.isNotEmpty()) sQualities else listOf(720) 
        }
        
        val pageReferer = watchDoc.location()
        val encodedReferer = java.net.URLEncoder.encode(pageReferer, "UTF-8")
        
        if (postId.isNotBlank() && csrfToken.isNotBlank()) {
            qualitiesToProcess.forEach { quality ->
                uniqueServers.forEach { server ->
                    val linkName = server.title.ifBlank { "Server ${server.serverId}" }
                    
                    // Construct virtual URL overriding the quality parameter
                    val currentBaseUrl = try {
                        val uri = java.net.URI(pageReferer)
                        "${uri.scheme}://${uri.host}"
                    } catch (e: Exception) { "https://arabseed.show" }
                    
                    val virtualUrl = "$currentBaseUrl/get__watch__server/?post_id=$postId&quality=$quality&server=${server.serverId}&csrf_token=$csrfToken&referer=$encodedReferer"
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$linkName (${quality}p)",
                            url = virtualUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                        }
                    )
                    found = true
                }
            }
        }
        
        return found || directEmbeds.isNotEmpty()
    }
}

