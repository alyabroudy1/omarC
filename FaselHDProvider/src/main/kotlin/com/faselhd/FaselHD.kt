package com.faselhd

import android.util.Log
import android.net.Uri
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.Coroutines
import org.jsoup.nodes.Element

class FaselHD : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://faselhds.biz"
    override var name = "FaselHD (REFACTORED)"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    companion object {
        private const val TAG = "FaselHD"
        private const val VERSION = "6.1"
    }

    init {
        Log.e(TAG, "!!!!!!!! FaselHD Fixed Provider Initialized - Version: $VERSION !!!!!!!!")
    }

    val posterHeaders: Map<String, String>
        get() {
            val manager = CookieManager.getInstance()
            val uri = Uri.parse(mainUrl)
            val host = uri.host ?: return emptyMap()
            
            val cookies = (manager.getCookie(mainUrl)
                ?: manager.getCookie("https://www.$host")
                ?: manager.getCookie("https://${host.removePrefix("www.")}")
                ?: "").ifEmpty {
                    manager.getCookie(host) ?: ""
                }
            
            val ua = WebViewResolver.webViewUserAgent 
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            return mapOf(
                "Cookie" to cookies,
                "User-Agent" to ua,
                "Referer" to "$mainUrl/"
            )
        }
    
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
            this.posterHeaders = this@FaselHD.posterHeaders
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
            val response = app.get(url, timeout = 120)
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
            val response = app.get(url, timeout = 120)
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
        Log.d(TAG, "load: Fetching $url")
        try {
            val doc = app.get(url, timeout = 120).document
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

            Log.d(TAG, "load: Title=$title, isMovie=$isMovie")
            Log.d(TAG, "load: PosterUrl=$posterUrl")

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
                    this.posterHeaders = this@FaselHD.posterHeaders
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
                        val seasonDoc = app.get("$mainUrl/?p=$id", timeout = 120).document
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
                Log.d(TAG, "load: Found ${episodes.size} episodes")
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { "${it.season}-${it.episode}" }.sortedBy { it.episode }) {
                    this.duration = duration
                    this.posterUrl = fixUrl(posterUrl)
                    this.posterHeaders = this@FaselHD.posterHeaders
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
            val doc = app.get(data, timeout = 120).document

            // 1. Extract Watch Servers (Tabs)
            doc.select(".signleWatch li").forEach { tab ->
                val serverName = tab.text()
                val onclick = tab.attr("onclick")
                if (onclick.contains("player_iframe")) {
                    val serverUrl = onclick.replace("player_iframe.location.href =", "")
                        .replace("'", "")
                        .replace(";", "")
                        .trim()
                    
                    val fixedServerUrl = fixUrl(serverUrl)
                    if (fixedServerUrl.startsWith("http")) {
                        Log.e(TAG, "Found Watch Server: $serverName -> $fixedServerUrl")
                        // Use WebViewResolver to sniff the link if it's something complex, or yield likely direct links
                        // Since we removed VideoSniffer, we rely on standard sniffers or direct URLs.
                        // Standard practice: yield the link, and if it's an embed, the app's generic sniffers might pick it up.
                        // BUT, FaselHD often needs active sniffing.
                        
                        // We will check if it's a known direct link, otherwise try generic webview resolve
                        
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = serverName,
                                url = fixedServerUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                            }
                        )
                    }
                }
            }

            // 2. Extract Quality Buttons
            doc.select("div.quality_change button").forEach { btn ->
                val qualityName = btn.text()
                var directUrl = btn.attr("data-url").ifEmpty { btn.attr("data-href") }
                
                if (directUrl.isNotEmpty()) {
                    val fixedDirectUrl = fixUrl(directUrl)
                    if (fixedDirectUrl.startsWith("http")) {
                        Log.e(TAG, "Found Quality Button: $qualityName -> $fixedDirectUrl")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = qualityName, 
                                url = fixedDirectUrl,
                                type = if (fixedDirectUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = when(getQualityFromString(qualityName)) {
                                    SearchQuality.HD -> Qualities.P720.value
                                    SearchQuality.FourK -> Qualities.P2160.value
                                    SearchQuality.SD -> Qualities.P480.value
                                    else -> Qualities.Unknown.value
                                }
                            }
                        )
                    }
                }
            }

            // 3. Fallback to main player_iframe / Generic Resolver
            if (doc.select(".signleWatch li").isEmpty() && doc.select("div.quality_change button").isEmpty()) {
                val iframeSrc = doc.select("iframe[name=\"player_iframe\"]").attr("src")
                if (iframeSrc.isNotEmpty()) {
                    val fixedIframeSrc = fixUrl(iframeSrc)
                    Log.e(TAG, "Falling back to main iframe: $fixedIframeSrc")

                    // Attempt resolution using standard WebViewResolver
                    val webView = WebViewResolver(
                         interceptUrl = Regex("""(?i)(https?://.*\.m3u8.*)|(https?://.*\.mp4.*)""")
                    )
                    val (finalRequest, extraRequests) = webView.resolveUsingWebView(fixedIframeSrc)
                    
                    (extraRequests + listOfNotNull(finalRequest)).map { it.url.toString() }.distinct().forEach { url ->
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} Auto",
                                url = url,
                                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                            }
                        )
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: Error fetching $data", e)
            return false
        }
    }
}
