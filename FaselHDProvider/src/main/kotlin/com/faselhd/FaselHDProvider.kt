package com.faselhd

import android.util.Log
import android.net.Uri
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.network.VideoSniffer
import org.jsoup.nodes.Element

class FaselHDFix : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://faselhds.biz"
    override var name = "FaselHD Fix"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    companion object {
        private const val TAG = "FaselHD Fix"
        private const val VERSION = "6.2"
    }

    init {
        Log.e(TAG, "!!!!!!!! FaselHD Provider Initialized - Version: $VERSION !!!!!!!!")
    }

    val posterHeaders: Map<String, String>
        get() {
            Log.e(TAG, "posterHeaders: Fetching cookies for images (MainUrl=$mainUrl)")
            val manager = CookieManager.getInstance()
            val uri = Uri.parse(mainUrl)
            val host = uri.host ?: return emptyMap()
            
            val cookies = (manager.getCookie(mainUrl)
                ?: manager.getCookie("https://www.$host")
                ?: manager.getCookie("https://${host.removePrefix("www.")}")
                ?: "").ifEmpty {
                    manager.getCookie(host) ?: ""
                }
            
            val ua = WebViewResolver.webViewUserAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            
            Log.e(TAG, "posterHeaders: Found cookies length ${cookies.length}. UA: $ua")
            return mapOf(
                "Cookie" to cookies,
                "User-Agent" to ua,
                "Referer" to "$mainUrl/"
            )
        }
    
    private val cfInterceptor = CloudflareKiller()

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("div.postDiv a").attr("href").ifEmpty { return null }
        var posterUrl = select("div.postDiv a div img").attr("data-src")
        if (posterUrl.isEmpty()) posterUrl = select("div.postDiv a div img").attr("data-lazy-src")
        if (posterUrl.isEmpty()) posterUrl = select("div.postDiv a div img").attr("src")
        
        val title = select("div.postDiv a div img").attr("alt")
        val quality = select(".quality").first()?.text()?.replace("1080p |-".toRegex(), "")
        val type = if (title.contains("فيلم")) TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(
            title.replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي".toRegex(), "").trim(),
            url,
            type
        ) {
            this.posterUrl = fixUrl(posterUrl)
            this.posterHeaders = this@FaselHDFix.posterHeaders
            this.quality = getQualityFromString(quality)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/all-movies/page/" to "جميع الافلام",
        "$mainUrl/movies_top_views/page/" to "الافلام الاعلي مشاهدة",
        "$mainUrl/dubbed-movies/page/" to "الأفلام المدبلجة",
        "$mainUrl/movies_top_imdb/page/" to "الافلام الاعلي تقييما IMDB",
        "$mainUrl/series/page/" to "مسلسلات",
        "$mainUrl/recent_series/page/" to "المضاف حديثا",
        "$mainUrl/anime/page/" to "الأنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        Log.d(TAG, "getMainPage: Fetching $url")
        try {
            val response = app.get(url, interceptor = cfInterceptor, timeout = 120)
            val doc = response.document
            val list = doc.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
                .mapNotNull { it.toSearchResponse() }
            return newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: Error fetching $url", e)
            throw e
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        val url = "$mainUrl/?s=$q"
        Log.d(TAG, "search: Fetching $url")
        try {
            val response = app.get(url, interceptor = cfInterceptor, timeout = 120)
            val doc = response.document
            val results = doc.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
                .mapNotNull { it.toSearchResponse() }
            return results
        } catch (e: Exception) {
            Log.e(TAG, "search: Error fetching $url", e)
            throw e
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val doc = app.get(url, interceptor = cfInterceptor, timeout = 120).document
            val isMovie = doc.select("div.epAll").isEmpty()
            
            var posterUrl = doc.select("div.posterImg img").attr("data-src")
            if (posterUrl.isEmpty()) posterUrl = doc.select("div.posterImg img").attr("data-lazy-src")
            if (posterUrl.isEmpty()) posterUrl = doc.select("div.posterImg img").attr("src")
            
            if (posterUrl.isEmpty()) {
                 posterUrl = doc.select("div.seasonDiv.active img").attr("data-src")
            }

            val year = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]")
                .firstOrNull { it.text().contains("سنة|موعد".toRegex()) }
                ?.text()?.getIntFromText()

            val title = doc.select("title").text()
                .replace(" - فاصل إعلاني", "")
                .replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|$year".toRegex(), "")
                .trim()

            val duration = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]")
                .firstOrNull { it.text().contains("مدة|توقيت".toRegex()) }
                ?.text()?.getIntFromText()

            val tags = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]:contains(تصنيف الفيلم) a")
                .map { it.text() }
            val recommendations = doc.select("div#postList div.postDiv").mapNotNull { it.toSearchResponse() }
            val synopsis = doc.select("div.singleDesc p").text()

            return if (isMovie) {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = fixUrl(posterUrl)
                    this.posterHeaders = this@FaselHDFix.posterHeaders
                    this.year = year
                    this.plot = synopsis
                    this.duration = duration
                    this.tags = tags
                    this.recommendations = recommendations
                }
            } else {
                val episodes = ArrayList<Episode>()
                doc.select("div.epAll a").forEach {
                    episodes.add(
                        newEpisode(it.attr("href")) {
                            this.name = it.text()
                            this.season = doc.select("div.seasonDiv.active div.title").text().getIntFromText() ?: 1
                            this.episode = it.text().getIntFromText()
                        }
                    )
                }
                doc.select("div[id=\"seasonList\"] div[class=\"col-xl-2 col-lg-3 col-md-6\"] div.seasonDiv")
                    .not(".active").amap { seasonElement ->
                        val id = seasonElement.attr("onclick").replace(".*/?p=|'".toRegex(), "")
                        val seasonDoc = app.get("$mainUrl/?p=$id", interceptor = cfInterceptor, timeout = 120).document
                        seasonDoc.select("div.epAll a").forEach {
                            episodes.add(
                                newEpisode(it.attr("href")) {
                                    this.name = it.text()
                                    this.season = seasonDoc.select("div.seasonDiv.active div.title").text().getIntFromText()
                                    this.episode = it.text().getIntFromText()
                                }
                            )
                        }
                    }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { "${it.season}-${it.episode}" }.sortedBy { it.episode }) {
                    this.duration = duration
                    this.posterUrl = fixUrl(posterUrl)
                    this.posterHeaders = this@FaselHDFix.posterHeaders
                    this.year = year
                    this.plot = synopsis
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: Error fetching $url", e)
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "!!!!!!!! loadLinks Started (Version: $VERSION) - Data: $data !!!!!!!!")
        try {
            val doc = app.get(data, interceptor = cfInterceptor, timeout = 120).document

            // Try download source
            val downloadUrl = doc.select(".downloadLinks a").attr("href")
            if (downloadUrl.isNotEmpty()) {
                try {
                    val player = app.post(downloadUrl, referer = mainUrl, interceptor = cfInterceptor, timeout = 120).document
                    val directUrl = player.select("div.dl-link a").attr("href")
                    if (directUrl.isNotEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} Download",
                                url = directUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "loadLinks: Error getting download link", e)
                }
            }

            // Try iframe source
            val iframeSrc = doc.select("iframe[name=\"player_iframe\"]").attr("src")
            if (iframeSrc.isNotEmpty()) {
                Log.e(TAG, "!!!!!!!! Starting VideoSniffer for $iframeSrc !!!!!!!!")
                val result = VideoSniffer.sniff(iframeSrc)
                if (result != null) {
                    Log.e(TAG, "VideoSniffer SUCCESS: Found ${result.url}")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} Auto (Visible)",
                            url = result.url,
                            type = if (result.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = iframeSrc
                            this.headers = result.headers
                        }
                    )
                } else {
                    Log.e(TAG, "VideoSniffer failed, falling back to WebViewResolver...")
                    val webView = WebViewResolver(
                        interceptUrl = Regex("""(?i)(https?://.*\.m3u8.*)|(https?://.*\.mp4.*)""")
                    )
                    val (finalRequest, extraRequests) = webView.resolveUsingWebView(iframeSrc)
                    val extracted = (extraRequests + listOfNotNull(finalRequest)).map { it.url.toString() }.distinct()
                    
                    for (url in extracted) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} Auto",
                                url = url,
                                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = iframeSrc
                            }
                        )
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: Error", e)
            throw e
        }
    }
}
