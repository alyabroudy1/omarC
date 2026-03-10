package com.cloudstream.shared.provider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import org.jsoup.nodes.Document
import com.cloudstream.shared.service.CloudflareBlockedSearchException
import com.cloudstream.shared.service.ProviderHttpService
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.android.PluginContext
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

abstract class BaseProvider : MainAPI() {
    abstract val providerName: String
    abstract val baseDomain: String
    abstract val githubConfigUrl: String

    override var name: String = providerName
    override var mainUrl: String = "https://$baseDomain"
    override var lang: String = "ar"
    override val hasMainPage: Boolean = true
    override val hasDownloadSupport: Boolean = true
    override val supportedTypes: Set<TvType> = setOf(TvType.TvSeries, TvType.Movie)

    /**
     * Indicates this provider supports lazy search (skip CF WebView during global search).
     * When true, SearchViewModel will try [searchLazy] first and show a "tap to search"
     * placeholder if CF is detected, instead of opening a WebView immediately.
     */
    open val supportsLazySearch: Boolean = true

    protected abstract fun getParser(): NewBaseParser


    protected val httpService by lazy {
        val context = PluginContext.context ?: (com.lagradost.cloudstream3.app as android.content.Context)

        val service = ProviderHttpService.create(
            context = context,
            config = ProviderConfig(
                name = name,
                fallbackDomain = baseDomain,
                githubConfigUrl = githubConfigUrl,
                syncWorkerUrl = getSyncWorkerUrl(),
                skipHeadless = true,
            ),
            parser = getParser(),
            activityProvider = { ActivityProvider.currentActivity }
        )

        ProviderHttpServiceHolder.initialize(service)

        service
    }

    open fun getSyncWorkerUrl(): String = "https://omarstreamcloud.alyabroudy1.workers.dev"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val methodTag = "[$name] [getMainPage]"
        Log.i(methodTag, "START page=$page, data=${request.data}, name=${request.name}")

        try {
            httpService.ensureInitialized()
            // Sync mainUrl after domain may have changed from GitHub config
            mainUrl = "https://${httpService.currentDomain}"
            
            val items = mutableListOf<HomePageList>()
            
            if (page <= 1) {
                val urlPath = request.data
                val sectionName = request.name
                Log.d(methodTag, "Fetching category: '$sectionName' -> '$urlPath'")
                
                val fullUrl = if (urlPath.startsWith("http")) urlPath else "$mainUrl$urlPath"
                Log.d(methodTag, "Fetching URL: $fullUrl")
                
                val doc = httpService.getDocument(fullUrl, checkDomainChange = true)
                
                if (doc != null) {
                    Log.d(methodTag, "Document fetched successfully. Parsing...")
                    val parsedItems = getParser().parseMainPage(doc)
                    Log.d(methodTag, "Parsed ${parsedItems.size} items for '$sectionName'")
                    
                    if (parsedItems.isNotEmpty()) {
                         val searchResponses = parsedItems.map { item ->
                            val type = if (item.isMovie) TvType.Movie else TvType.TvSeries
                            newMovieSearchResponse(item.title, item.url, type) {
                                this.posterUrl = item.posterUrl
                                this.posterHeaders = httpService.getImageHeaders()
                                
                            }
                        }
                        Log.d(methodTag, "Mapped ${searchResponses.size} items to SearchResponse")
                        items.add(HomePageList(sectionName, searchResponses))
                    } else {
                        Log.w(methodTag, "No items found for category '$sectionName'")
                    }
                } else {
                    Log.e(methodTag, "Failed to fetch document for '$fullUrl'")
                }
            } else {
                Log.d(methodTag, "Page > 1 not fully implemented in BaseProvider generic logic yet.")
            }

            Log.i(methodTag, "END. Returning ${items.size} sections.")
            return newHomePageResponse(items)

        } catch (e: Exception) {
            Log.e(methodTag, "Error in getMainPage: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val methodTag = "[$name] [search]"
        Log.i(methodTag, "START query='$query'")
        
        try {
            httpService.ensureInitialized()
            mainUrl = "https://${httpService.currentDomain}"
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = getParser().getSearchUrl(mainUrl, encoded)
            Log.d(methodTag, "Fetching search URL: $url")
            
            val doc = httpService.getDocument(url, checkDomainChange = true)
            if (doc == null) {
                Log.e(methodTag, "Failed to fetch search document")
                return emptyList()
            }
            
            val items = getParser().parseSearch(doc)
            Log.d(methodTag, "Parsed ${items.size} search items")
            
            return items.map { item ->
                newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                }
            }
        } catch (e: Exception) {
            Log.e(methodTag, "Error in search: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * Lazy search: attempts HTTP search WITHOUT Cloudflare WebView fallback.
     * If CF blocks the request, throws [CloudflareBlockedSearchException]
     * so the caller (SearchViewModel) can show a placeholder instead.
     * 
     * The full [search] method (with CF solve) is called later if the user taps the placeholder.
     */
    open suspend fun searchLazy(query: String): List<SearchResponse> {
        val methodTag = "[$name] [searchLazy]"
        Log.i(methodTag, "START query='$query'")

        httpService.ensureInitialized()
        mainUrl = "https://${httpService.currentDomain}"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = getParser().getSearchUrl(mainUrl, encoded)
        Log.d(methodTag, "Fetching search URL (no CF fallback): $url")

        // This throws CloudflareBlockedSearchException if CF is detected
        val doc = httpService.getDocumentNoFallback(url, checkDomainChange = true)
        if (doc == null) {
            Log.e(methodTag, "Failed to fetch search document (no CF)")
            return emptyList()
        }

        val items = getParser().parseSearch(doc)
        Log.d(methodTag, "Parsed ${items.size} search items")

        return items.map { item ->
            newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeaders()
            }
        }
    }

    /**
     * Hook for providers that need to fetch extra episodes (e.g., AJAX season loading).
     * Called during load() for series. Override to add episodes from other seasons.
     */
    open suspend fun fetchExtraEpisodes(
        doc: Document, url: String, data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> = data.episodes ?: emptyList()

    /**
     * Hook for providers to customize season display names.
     * Default: "Season N". Override for localized names.
     */
    open fun getSeasonName(seasonNum: Int): String = "Season $seasonNum"

    /**
     * Hook for providers that need to resolve server URLs before extraction.
     * Called for each URL in the loadLinks loop. Override to handle virtual URLs
     * (e.g., lazy AJAX resolution). Return null to skip the URL.
     */
    open suspend fun resolveServerUrl(url: String, referer: String): String? = url

    override suspend fun load(url: String): LoadResponse? {
        val methodTag = "[$name] [load]"
        Log.i(methodTag, "START url='$url'")
        
        try {
            httpService.ensureInitialized()
            
            // Check if URL domain differs from main domain (subdomain case)
            // e.g., mainUrl=https://laroza.cfd/ but url=https://qq.laroza.cfd/vid-...
            
            val doc = httpService.getDocument(url)
            if (doc == null) {
                Log.e(methodTag, "Failed to fetch load document")
                return null
            }

            var actualDoc = doc
            var actualUrl = url
            
            // Check for Meta-Refresh Redirect (Client-side redirect)
            val metaResolved = resolveMetaRefresh(actualDoc, actualUrl, methodTag)
            actualDoc = metaResolved.first
            actualUrl = metaResolved.second
            
            var data = getParser().parseLoadPageData(actualDoc, actualUrl)
            
            if (data == null) {
                Log.e(methodTag, "Failed to parse load data")
                return null
            }
            
            // Refactored Fallback: Swap entire 'data' context if this is an episode pointing to a parent series
            data = resolveParentSeries(data, methodTag)
            
            // Fetch extra episodes via provider hook (e.g., AJAX seasons)
            val allEpisodes = fetchExtraEpisodes(actualDoc, actualUrl, data)
                .distinctBy { "${it.season}:${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            
            // Ultimate Fallback: If it's a series but we have 0 episodes, treat as a Movie to show the Play button
            val finalType = if (data.type == TvType.TvSeries && allEpisodes.isEmpty()) {
                Log.d(methodTag, "Fallback: Series has 0 episodes, treating as Movie to ensure playability.")
                TvType.Movie
            } else {
                data.type
            }

            Log.d(methodTag, "Parsed load data: title='${data.title}', type=$finalType, episodes=${allEpisodes.size}")
            
            return if (finalType == TvType.Movie) {
                val movieDataUrl = data.watchUrl ?: data.url
                newMovieLoadResponse(data.title, data.url, TvType.Movie, movieDataUrl) {
                    this.posterUrl = data.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                    this.plot = data.plot
                    this.tags = data.tags
                    this.year = data.year
                }
            } else {
                val episodeList = allEpisodes.map { ep ->
                    newEpisode(ep.url) {
                        this.name = ep.name
                        this.season = ep.season
                        this.episode = ep.episode
                    }
                }
                
                val seasonNames = episodeList.mapNotNull { it.season }.distinct().sorted()
                    .map { SeasonData(it, getSeasonName(it)) }
                
                newTvSeriesLoadResponse(data.title, data.url, TvType.TvSeries, episodeList) {
                    this.posterUrl = data.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                    this.plot = data.plot
                    this.tags = data.tags
                    this.year = data.year
                    if (seasonNames.isNotEmpty()) this.seasonNames = seasonNames
                }
            }
        } catch (e: Exception) {
            Log.e(methodTag, "Error in load: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun handleDomainDifference(url: String, methodTag: String) {
        try {
            val urlDomain = java.net.URL(url).host
            val mainDomain = java.net.URL(mainUrl).host

            if (urlDomain != mainDomain) {
                Log.d(
                    methodTag,
                    "Detected different domain in load URL: urlDomain=$urlDomain, mainDomain=$mainDomain"
                )

                // Check if related and add as alias
                if (com.cloudstream.shared.session.SessionProvider.areDomainsRelated(
                        urlDomain,
                        mainDomain
                    )
                ) {
                    com.cloudstream.shared.session.SessionProvider.addDomainAlias(urlDomain)
                    val baseDomain =
                        com.cloudstream.shared.session.SessionProvider.extractBaseDomain(urlDomain)
                    Log.i(
                        methodTag,
                        "Added domain alias for load URL domain: alias=$urlDomain, baseDomain=$baseDomain"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(methodTag, "Failed to parse URL domains for alias check: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "[$name] [loadLinks]"
        Log.i(methodTag, "START data='$data'")
        
        try {
            httpService.ensureInitialized()
            
            // Step 1: Fetch detail page
            val detailDoc = httpService.getDocument(data)
            if (detailDoc == null) {
                Log.e(methodTag, "Failed to fetch loadLinks document")
                return false
            }
            
            // Step 2: Ask parser for watch page URL
            val watchPageUrl = getParser().getPlayerPageUrl(detailDoc)
            
            // Step 3: If watch page found, fetch it; else use detail page
            var actualWatchUrl: String? = null
            val targetDoc = if (!watchPageUrl.isNullOrBlank()) {
                actualWatchUrl = if (watchPageUrl.startsWith("http")) {
                    watchPageUrl
                } else {
                    "$mainUrl/$watchPageUrl".replace("//", "/").replace("https:/", "https://")
                }
                Log.d(methodTag, "Following watch page: $actualWatchUrl")
                httpService.getDocument(actualWatchUrl) ?: detailDoc
            } else {
                Log.d(methodTag, "No watch page found, using detail page")
                detailDoc
            }
            
            // Step 4: Extract player URLs and server selectors from the target page
            val urls = getParser().extractWatchServersUrls(targetDoc)
            Log.d(methodTag, "Extracted ${urls.size} player URLs")
            
            if (urls.isEmpty()) {
                Log.w(methodTag, "No player URLs found")
                return false
            }
            
            // Step 5: Extract server selectors from parser (provider-specific)
            val serverSelectors = getParser().buildServerSelectors(targetDoc, urls)
            val selectorCount = serverSelectors.count { it != null }
            Log.d(methodTag, "Extracted $selectorCount/${serverSelectors.size} server selectors from parser")
            
            // Build referer from watch page domain
            val watchUrlForReferer = actualWatchUrl ?: data
            val referer = try {
                val uri = java.net.URI(watchUrlForReferer)
                "${uri.scheme}://${uri.host}/"
            } catch (e: Exception) {
                "https://$baseDomain/"
            }
            
            Log.d(methodTag, "Using referer: $referer")
            
            // ========================================
            // PHASE 1: Resolve ALL lazy URLs in parallel (AJAX, fast)
            // ========================================
            Log.i(methodTag, "PHASE 1: Resolving ${urls.size} URLs in parallel...")
            
            // Triple: (originalUrl, resolvedUrl, index)
            val resolvedServers: List<Triple<String, String, Int>> = coroutineScope {
                urls.mapIndexed { index, url ->
                    async {
                        try {
                            val resolved = resolveServerUrl(url, referer)
                            if (!resolved.isNullOrBlank()) {
                                if (resolved != url) {
                                    Log.d(methodTag, "PHASE 1: Resolved [$index]: $url -> ${resolved.take(80)}")
                                }
                                Triple(url, resolved, index)
                            } else {
                                Log.d(methodTag, "PHASE 1: resolveServerUrl returned null [$index], skipping: $url")
                                null
                            }
                        } catch (e: Exception) {
                            Log.w(methodTag, "PHASE 1: Exception resolving [$index]: ${e.message}")
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            
            Log.i(methodTag, "PHASE 1 complete: ${resolvedServers.size}/${urls.size} URLs resolved")
            
            if (resolvedServers.isEmpty()) {
                Log.w(methodTag, "PHASE 1: No URLs could be resolved")
                return false
            }
            
            // ========================================
            // PHASE 2: Try standard extractors on ALL resolved URLs in parallel
            // ========================================
            Log.i(methodTag, "PHASE 2: Trying standard extractors on ${resolvedServers.size} URLs in parallel...")
            
            val allExtractorLinks: List<ExtractorLink> = coroutineScope {
                resolvedServers.map { (_, resolvedUrl, serverIndex) ->
                    async {
                        try {
                            val links = collectExtractorLinks(resolvedUrl, referer, subtitleCallback, timeoutMs = 8000L)
                            if (links.isNotEmpty()) {
                                Log.i(methodTag, "PHASE 2: Extractor found ${links.size} links for [$serverIndex]: ${resolvedUrl.take(60)}")
                            }
                            links
                        } catch (e: Exception) {
                            Log.w(methodTag, "PHASE 2: Exception for [$serverIndex]: ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            
            // ========================================
            // PHASE 3: Deliver all found links to callback (quality picker)
            // ========================================
            if (allExtractorLinks.isNotEmpty()) {
                Log.i(methodTag, "PHASE 3: Delivering ${allExtractorLinks.size} links to callback (quality picker)")
                
                // Deduplicate by URL
                val uniqueLinks = allExtractorLinks.distinctBy { it.url }
                uniqueLinks.forEach { link ->
                    Log.d(methodTag, "PHASE 3: Delivering link: ${link.name} | ${link.url.take(80)}")
                    callback(link)
                }
                
                Log.i(methodTag, "PHASE 3 complete: Delivered ${uniqueLinks.size} unique links")
                return true
            }
            
            Log.w(methodTag, "PHASE 2-3: No standard extractors succeeded for any URL")
            
            // ========================================
            // PHASE 4: Sniffer fallback — try servers one-by-one until one works
            // ========================================
            Log.i(methodTag, "PHASE 4: Sniffer fallback — trying ${resolvedServers.size} servers sequentially...")
            
            for ((_, resolvedUrl, serverIndex) in resolvedServers) {
                val selector = serverSelectors.getOrNull(serverIndex)
                if (selector != null) {
                    Log.d(methodTag, "PHASE 4: Using selector for sniffer: ${selector.query}")
                }
                
                Log.d(methodTag, "PHASE 4: Sniffing [$serverIndex]: ${resolvedUrl.take(80)}")
                
                val snifferResult = awaitSnifferResult(
                    resolvedUrl, 
                    referer, 
                    subtitleCallback, 
                    callback, 
                    timeoutMs = com.cloudstream.shared.webview.VideoSnifferEngine.SNIFFER_PLAYER_TIMEOUT_MS,
                    selector = selector
                )
                
                if (snifferResult) {
                    Log.i(methodTag, "PHASE 4: SUCCESS via sniffer [$serverIndex]: ${resolvedUrl.take(80)}")
                    return true
                }
                
                Log.w(methodTag, "PHASE 4: Sniffer failed for [$serverIndex]")
            }
            
            Log.w(methodTag, "END - No videos found from ${urls.size} URLs (all 4 phases exhausted)")
            return false
        } catch (e: Exception) {
            Log.e(methodTag, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Collects ALL extractor links for a URL without calling the final callback.
     * Used in Phase 2 to gather links from all servers before delivering them.
     * Returns all found links (may be empty if no extractor matches).
     */
    private suspend fun collectExtractorLinks(
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        timeoutMs: Long
    ): List<ExtractorLink> {
        val collected = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
        
        try {
            withTimeoutOrNull(timeoutMs) {
                loadExtractor(targetUrl, referer, subtitleCallback) { link ->
                    collected.add(link)
                }
            }
        } catch (e: Exception) {
            Log.w("[$name] [collectExtractorLinks]", "Exception: ${e.message}")
        }
        
        return collected.toList()
    }
    
    /**
     * Uses SnifferExtractor (WebView) to sniff video when standard extractors fail.
     * Returns true if a video was found.
     * 
     * @param selector Optional selector to click a server button before sniffing
     */
    private suspend fun awaitSnifferResult(
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        timeoutMs: Long,
        selector: com.cloudstream.shared.extractors.SnifferSelector? = null
    ): Boolean {
        Log.d("[$name] [awaitSnifferResult]", "=== START === target=${targetUrl.take(80)}, hasSelector=${selector != null}")
        
        return try {
            withTimeoutOrNull(timeoutMs) {
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                var found = false
                
                // Create sniffer URL with optional selector
                val sniffUrl = com.cloudstream.shared.extractors.SnifferExtractor.createSnifferUrl(
                    targetUrl, 
                    referer,
                    selector
                )
                Log.d("[$name] [awaitSnifferResult]", "Sniffer URL: $sniffUrl")
                
                // Use loadExtractor which will trigger SnifferExtractor
                loadExtractor(sniffUrl, referer, subtitleCallback) { link ->
                    Log.i("[$name] [awaitSnifferResult]", "CALLBACK FIRED! URL=${link.url.take(80)}")
                    val isAudioChunk = link.url.contains("audio", ignoreCase = true) && link.url.contains(".mp4", ignoreCase = true)
                    if (!found && !isAudioChunk) {
                        found = true
                        callback(link)
                        deferred.complete(true)
                    } else if (isAudioChunk) {
                        Log.w("[$name] [awaitSnifferResult]", "Rejected AUDIO fragment from sniffer: ${link.url.take(80)}")
                    }
                }
                
                // loadExtractor returned — if callback never fired (user cancelled, error, no video),
                // complete deferred so we don't hang for 3 hours
                if (!found) {
                    Log.w("[$name] [awaitSnifferResult]", "loadExtractor returned without callback — completing as cancelled")
                    deferred.complete(false)
                }
                val result = deferred.await()
                Log.d("[$name] [awaitSnifferResult]", "=== END === result=$result")
                result
            } ?: false
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("[$name] [awaitSnifferResult]", "TIMEOUT! No video found within ${timeoutMs}ms")
            false
        } catch (e: Exception) {
            Log.e("[$name] [awaitSnifferResult]", "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Resolves meta-refresh redirects during load(), e.g., <meta http-equiv="Refresh" content="0;URL=...">
     * Returns the pair of (Document, Url). If no redirect, returns the original pair.
     */
    protected open suspend fun resolveMetaRefresh(doc: Document, url: String, methodTag: String): Pair<Document, String> {
        val refreshMeta = doc.selectFirst("meta[http-equiv=Refresh]")
        if (refreshMeta != null) {
            val content = refreshMeta.attr("content")
            val newUrlMatch = Regex("URL=(.+)", RegexOption.IGNORE_CASE).find(content)
            val newUrl = newUrlMatch?.groupValues?.get(1)

            if (!newUrl.isNullOrBlank()) {
                Log.d(methodTag, "Found Meta-Refresh to: $newUrl")
                val newDoc = httpService.getDocument(newUrl)
                if (newDoc != null) {
                    Log.d(methodTag, "Swapped to Meta-Refresh document.")
                    return Pair(newDoc, newUrl)
                }
            }
        }
        return Pair(doc, url)
    }

    /**
     * Resolves parent series context during load() if the current document is just an episode
     * but links to a parent URL for the full series graph.
     */
    protected open suspend fun resolveParentSeries(data: ParserInterface.ParsedLoadData, methodTag: String): ParserInterface.ParsedLoadData {
        if (!data.isMovie && data.episodes.isNullOrEmpty() && !data.parentSeriesUrl.isNullOrBlank()) {
            val parentUrl = data.parentSeriesUrl!!
            try {
                val parentDoc = httpService.getDocument(parentUrl)
                if (parentDoc != null) {
                    val parentData = getParser().parseLoadPageData(parentDoc, parentUrl)
                    if (parentData != null) {
                        Log.d(methodTag, "Swapped entire load context to parent series: $parentUrl")
                        return parentData
                    }
                } else {
                    Log.e(methodTag, "Failed to fetch parent series document: $parentUrl")
                }
            } catch (e: Exception) {
                Log.e(methodTag, "Failed to fetch parent series: $parentUrl")
                e.printStackTrace()
            }
        }
        return data
    }
}