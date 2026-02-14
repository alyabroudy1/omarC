package com.cloudstream.shared.provider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.provider.ProviderConfig
import com.cloudstream.shared.parsing.NewBaseParser
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import org.jsoup.nodes.Document

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

    protected abstract fun getParser(): NewBaseParser


    private val httpService by lazy {
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

    abstract val searchPath: String

    open fun getSyncWorkerUrl(): String = "https://omarstreamcloud.alyabroudy1.workers.dev"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val methodTag = "[$name] [getMainPage]"
        Log.i(methodTag, "START page=$page, data=${request.data}, name=${request.name}")

        try {
            httpService.ensureInitialized()
            
            val items = mutableListOf<HomePageList>()
            
            if (page <= 1) {
                Log.d(methodTag, "Loading all categories from mainPage definition (${mainPage.size} entries)")
                
                mainPage.forEachIndexed { index, (sectionName, urlPath) ->
                    Log.d(methodTag, "Processing category $index: '$sectionName' -> '$urlPath'")
                    
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
            val url = "$mainUrl$searchPath$query"
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

    override suspend fun load(url: String): LoadResponse? {
        val methodTag = "[$name] [load]"
        Log.i(methodTag, "START url='$url'")
        
        try {
            httpService.ensureInitialized()
            val doc = httpService.getDocument(url)
            if (doc == null) {
                Log.e(methodTag, "Failed to fetch load document")
                return null
            }
            
            var data = getParser().parseLoadPageData(doc, url)

            // Check for Meta-Refresh Redirect (Client-side redirect)
            // Example: <meta http-equiv="Refresh" content="0;URL=https://laroza.cfd/video.php?vid=YJBBtHyVB">
            if (data?.episodes?.isEmpty() == true && data.isMovie) {
                 val refreshMeta = doc.selectFirst("meta[http-equiv=Refresh]")
                 if (refreshMeta != null) {
                     val content = refreshMeta.attr("content")
                     val newUrlMatch = Regex("URL=(.+)", RegexOption.IGNORE_CASE).find(content)
                     val newUrl = newUrlMatch?.groupValues?.get(1)
                     
                     if (!newUrl.isNullOrBlank()) {
                         Log.d(methodTag, "Found Meta-Refresh to: $newUrl")
                         val newDoc = httpService.getDocument(newUrl)
                         if (newDoc != null) {
                             data = getParser().parseLoadPageData(newDoc, newUrl)
                         }
                     }
                 }
            }
            
            if (data == null) {
                Log.e(methodTag, "Failed to parse load data")
                return null
            }
            
            var episodes = data.episodes ?: emptyList()
            
            // Fallback: If no episodes found but parent series link exists, fetch series page
            if (episodes.isEmpty() && !data.isMovie && !data.parentSeriesUrl.isNullOrBlank()) {
                try {
                    val seriesUrl = data.parentSeriesUrl!! // Safe due to isNullOrBlank check
                    val seriesDoc = httpService.getDocument(seriesUrl)
                    // We can use parseLoadPageData again on the series page 
                    if (seriesDoc != null) {
                        val seriesData = getParser().parseLoadPageData(seriesDoc, seriesUrl)
                         if (seriesData?.episodes?.isNotEmpty() == true) {
                             episodes = seriesData.episodes!!
                             Log.d(methodTag, "Fetched ${episodes.size} episodes from parent series: $seriesUrl")
                         }
                    } else {
                         Log.e(methodTag, "Failed to fetch parent series document: $seriesUrl")
                    }
                } catch (e: Exception) {
                    Log.e(methodTag, "Failed to fetch parent series: ${data.parentSeriesUrl}")
                    e.printStackTrace()
                }
            }

            Log.d(methodTag, "Parsed load data: title='${data.title}', type=${data.type}")
            
            return if (data.type == TvType.Movie) {
                newMovieLoadResponse(data.title, url, TvType.Movie, data.url) {
                    this.posterUrl = data.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                    this.plot = data.plot
                    this.tags = data.tags
                    this.year = data.year
                }
            } else {
                val episodeList = episodes.map { ep ->
                    newEpisode(ep.url) {
                        this.name = ep.name
                        this.season = ep.season
                        this.episode = ep.episode
                    }
                }
                
                newTvSeriesLoadResponse(data.title, url, TvType.TvSeries, episodeList) {
                    this.posterUrl = data.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                    this.plot = data.plot
                    this.tags = data.tags
                    this.year = data.year
                }
            }
        } catch (e: Exception) {
            Log.e(methodTag, "Error in load: ${e.message}")
            e.printStackTrace()
            return null
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
            val targetDoc = if (!watchPageUrl.isNullOrBlank()) {
                val fullWatchUrl = if (watchPageUrl.startsWith("http")) {
                    watchPageUrl
                } else {
                    "$mainUrl/$watchPageUrl".replace("//", "/").replace("https:/", "https://")
                }
                Log.d(methodTag, "Following watch page: $fullWatchUrl")
                httpService.getDocument(fullWatchUrl) ?: detailDoc
            } else {
                Log.d(methodTag, "No watch page found, using detail page")
                detailDoc
            }
            
            // Step 4: Extract player URLs from the target page
            val urls = getParser().extractPlayerUrls(targetDoc)
            Log.d(methodTag, "Extracted ${urls.size} player URLs")
            
            if (urls.isEmpty()) {
                Log.w(methodTag, "No player URLs found")
                return false
            }
            
            // Step 5: Process URLs with standard extractors + sniffer fallback (Arabseed pattern)
            val referer = "$mainUrl/"
            
            for (url in urls) {
                Log.d(methodTag, "Processing player URL: $url")
                
                // STEP 1: Try standard extractors (8s timeout)
                Log.d(methodTag, "STEP 1: Trying standard extractors...")
                val standardResult = awaitExtractorWithResult(url, referer, subtitleCallback, callback, timeoutMs = 8000L)
                
                if (standardResult) {
                    Log.i(methodTag, "SUCCESS via standard extractor: $url")
                    return true
                }
                
                // STEP 2: Standard failed, try sniffer (60s timeout)
                Log.w(methodTag, "STEP 2: Standard extractors failed, trying sniffer...")
                val snifferResult = awaitSnifferResult(url, referer, subtitleCallback, callback, timeoutMs = 60000L)
                
                if (snifferResult) {
                    Log.i(methodTag, "SUCCESS via sniffer: $url")
                    return true
                }
                
                Log.w(methodTag, "Both methods failed for: $url")
            }
            
            Log.w(methodTag, "END - No videos found from ${urls.size} URLs")
            return false
        } catch (e: Exception) {
            Log.e(methodTag, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Awaits standard extractor result with timeout.
     * Returns true if a link was found and callback invoked.
     */
    private suspend fun awaitExtractorWithResult(
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        timeoutMs: Long
    ): Boolean {
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                var found = false
                
                loadExtractor(targetUrl, referer, subtitleCallback) { link ->
                    if (!found) {
                        found = true
                        callback(link)
                        deferred.complete(true)
                    }
                }
                
                deferred.await()
            } ?: false
        } catch (e: Exception) {
            Log.w("[$name] [awaitExtractorWithResult]", "Exception: ${e.message}")
            false
        }
    }
    
    /**
     * Uses SnifferExtractor (WebView) to sniff video when standard extractors fail.
     * Returns true if a video was found.
     */
    private suspend fun awaitSnifferResult(
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        timeoutMs: Long
    ): Boolean {
        Log.d("[$name] [awaitSnifferResult]", "=== START === target=${targetUrl.take(80)}")
        
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                var found = false
                
                // Create sniffer URL
                val sniffUrl = com.cloudstream.shared.extractors.SnifferExtractor.createSnifferUrl(targetUrl, referer)
                Log.d("[$name] [awaitSnifferResult]", "Sniffer URL: $sniffUrl")
                
                // Use loadExtractor which will trigger SnifferExtractor
                loadExtractor(sniffUrl, referer, subtitleCallback) { link ->
                    Log.i("[$name] [awaitSnifferResult]", "CALLBACK FIRED! URL=${link.url.take(80)}")
                    if (!found) {
                        found = true
                        callback(link)
                        deferred.complete(true)
                    }
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
}