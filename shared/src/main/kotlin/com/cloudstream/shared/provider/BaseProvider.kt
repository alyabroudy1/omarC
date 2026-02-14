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

    open fun getSyncWorkerUrl(): String = "https://omarstreamcloud.alyabroudy1.workers.dev"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val methodTag = "[$name] [getMainPage]"
        Log.i(methodTag, "START page=$page, data=${request.data}, name=${request.name}")

        try {
            httpService.ensureInitialized()
            
            val items = mutableListOf<HomePageList>()
            
            // If request.data holds a specific category URL (from LoadMore or specific section), use it.
            // But typical CloudStream structure iterates mainPage entries for the first load (page <= 1).
            // Here we mimic standard logic: if page 1, load all defined categories.
            
            if (page <= 1) {
                Log.d(methodTag, "Loading all categories from mainPage definition (${mainPage.size} entries)")
                
                mainPage.forEachIndexed { index, (urlPath, sectionName) ->
                    Log.d(methodTag, "Processing category $index: '$sectionName' -> '$urlPath'")
                    
                    val fullUrl = if (urlPath.startsWith("http")) urlPath else "$mainUrl$urlPath"
                    Log.d(methodTag, "Fetching URL: $fullUrl")
                    
                    val doc = httpService.getDocument(fullUrl)
                    
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
                // Handle pagination if needed, or specific category loading if request.data is used
                // For now, focusing on the main landing page implementation
                Log.d(methodTag, "Page > 1 not fully implemented in BaseProvider generic logic yet.")
            }

            Log.i(methodTag, "END. Returning ${items.size} sections.")
            return HomePageResponse(items)

        } catch (e: Exception) {
            Log.e(methodTag, "Error in getMainPage: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
}