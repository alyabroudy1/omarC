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
        
        val responses = items.map { item ->
            newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeaders()
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
        
        val doc = httpService.getDocument(url) ?: return null
        val data = parser.parseLoadPage(doc, url) ?: return null
        
        return if (data.type == TvType.Movie) {
             newMovieLoadResponse(data.title, url, TvType.Movie, data.url) {
                 this.posterUrl = data.posterUrl
                 this.year = data.year
                 this.plot = data.plot
                 this.tags = data.tags
             }
        } else {
             val episodes = parser.parseEpisodes(doc, null)
             newTvSeriesLoadResponse(data.title, url, TvType.TvSeries, episodes.map {
                 newEpisode(it.url) {
                     this.name = it.name
                     this.season = it.season
                     this.episode = it.episode
                 }
             }) {
                 this.posterUrl = data.posterUrl
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
        // Delegate to service to find links
        val urls = httpService.getPlayerUrls(data)
        
        urls.forEach { url ->
            val isPrivateServer = url.contains("arabseed") || url.contains("asd") // TODO: refined check
            
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
                 }
            } else {
                 loadExtractor(url, subtitleCallback, callback)
            }
        }
        return true
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
