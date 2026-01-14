package com.faselhd

import android.content.Context
import com.lagradost.cloudstream3.*
import com.faselhd.service.ProviderHttpService
import com.faselhd.service.strategy.VideoSource
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.api.Log

/**
 * FaselHD provider integrated with the new ProviderHttpService.
 * 
 * ## Features:
 * - Automatic Cloudflare challenge solving
 * - Cookie persistence across app restarts
 * - Video sniffing with JWPlayer extraction
 * - Comprehensive logging
 * 
 * ## Extension Usage:
 * This is the main provider for the FaselHD extension plugin.
 * The service handles all CF challenges automatically.
 */
class FaselHDv2 : MainAPI() {
    override var lang = "ar"
    override var name = "FaselHD v2"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    
    companion object {
        private const val TAG = "FaselHDv2"
        private const val FALLBACK_DOMAIN = "https://www.faselhds.biz"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        
        // Lazy-initialized service (needs context)
        private var _httpService: ProviderHttpService? = null
        
        fun getHttpService(context: Context): ProviderHttpService {
            return _httpService ?: ProviderHttpService.create(
                context = context,
                providerName = "FaselHD",
                userAgent = USER_AGENT,
                fallbackDomain = FALLBACK_DOMAIN
            ).also { _httpService = it }
        }
    }
    
    // Get service using plugin context
    private val httpService: ProviderHttpService
        get() {
            val ctx = FaselHDPlugin.activity
                ?: throw IllegalStateException("No activity context available from plugin")
            return getHttpService(ctx)
        }
    
    override var mainUrl: String
        get() = httpService.currentDomain
        set(value) { httpService.updateDomain(value) }
    
    // ========== MAIN PAGE ==========
    
    override val mainPage = mainPageOf(
        "/all-movies/page/" to "جميع الافلام",
        "/movies_top_views/page/" to "الافلام الاعلي مشاهدة",
        "/dubbed-movies/page/" to "الأفلام المدبلجة",
        "/movies_top_imdb/page/" to "الافلام الاعلي تقييما IMDB",
        "/series/page/" to "مسلسلات",
        "/recent_series/page/" to "المضاف حديثا",
        "/anime/page/" to "الأنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "[getMainPage] Fetching: ${request.data}$page")
        
        val pageUrl = "$mainUrl${request.data}$page"
        val doc = httpService.getDocument(pageUrl)
            ?: throw ErrorLoadingException("Failed to load main page")
        
        val list = doc.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
            .mapNotNull { element -> element.toSearchResponse() }
        
        return newHomePageResponse(request.name, list)
    }

    // ========== SEARCH ==========
    
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        val doc = httpService.getDocument("$mainUrl/?s=$q")
            ?: return emptyList()
        
        return doc.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
            .mapNotNull { it.toSearchResponse() }
    }

    // ========== LOAD ==========
    
    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "[load] Loading: $url")
        
        val doc = httpService.getDocument(url)
            ?: throw ErrorLoadingException("Failed to load page")
        
        val title = doc.select("div.title h1").text()
        val posterUrl = fixUrl(doc.select("div.posterDiv img").attr("src"))
        val year = doc.select("div.singleInfo span:contains(السنة) a").text().toIntOrNull()
        val plot = doc.select("div.singleInfo span:contains(القصة) p").text()
        val tags = doc.select("div.singleInfo span:contains(النوع) a").map { it.text() }
        val rating = doc.select("div.singleInfo span:contains(التقييم) p").text()
            .replace("IMDB ", "").replace("/10", "").toDoubleOrNull()?.times(1000)?.toInt()
        
        val isMovie = !doc.select("div.seasonEpsCont").isNullOrEmpty().not() && 
                      doc.select("div.seasonEpsCont").isEmpty()
        
        return if (isMovie) {
            val watchUrl = doc.select("a.watchBtnIn498").attr("href")
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.posterHeaders = httpService.getImageHeaders()
            }
        } else {
            val episodes = mutableListOf<Episode>()
            doc.select("div.seasonEpsCont").forEach { season ->
                val seasonNum = season.select("div.season-title").text()
                    .replace("الموسم", "").trim().toIntOrNull() ?: 1
                
                season.select("a.epAll").forEach { ep ->
                    val epUrl = ep.attr("href")
                    val epTitle = ep.text()
                    val epNum = epTitle.replace("الحلقة", "").trim().toIntOrNull() ?: 1
                    
                    episodes.add(newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.posterHeaders = httpService.getImageHeaders()
            }
        }
    }

    // ========== LOAD LINKS (VIDEO SNIFFING) ==========
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "[loadLinks] Sniffing video from: $data")
        
        val videos = httpService.sniffVideos(data)
        
        if (videos.isEmpty()) {
            Log.w(TAG, "[loadLinks] No videos found!")
            return false
        }
        
        Log.i(TAG, "[loadLinks] Found ${videos.size} video sources")
        
        videos.forEach { source ->
            val quality = parseQuality(source.label)
            
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - ${source.label}",
                    url = source.url,
                    type = if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = quality
                    this.headers = source.headers + httpService.getImageHeaders()
                }
            )
        }
        
        return true
    }

    // ========== HELPERS ==========
    
    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("div.postDiv a").attr("href").ifEmpty { return null }
        val rawPosterUrl = select("div.postDiv a div img").attr("data-src")
            .ifEmpty { select("div.postDiv a div img").attr("src") }
        val posterUrl = fixUrl(rawPosterUrl)
        
        val title = select("div.postDiv a div img").attr("alt")
        val quality = select(".quality").first()?.text()?.replace("1080p |-".toRegex(), "")
        val type = if (title.contains("فيلم")) TvType.Movie else TvType.TvSeries
        
        return newMovieSearchResponse(
            title.replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي".toRegex(), ""),
            url,
            type
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            this.posterHeaders = httpService.getImageHeaders()
        }
    }
    
    private fun fixUrl(url: String): String {
        try {
            val currentHost = java.net.URI(mainUrl).host ?: return url
            val hostWithoutWww = currentHost.removePrefix("www.")
            if (url.contains(hostWithoutWww) && !url.contains("www.")) {
                return url.replace("://$hostWithoutWww", "://www.$hostWithoutWww")
            }
        } catch (e: Exception) { }
        return url
    }
    
    private fun parseQuality(label: String): Int {
        return when {
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
