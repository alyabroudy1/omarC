package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.api.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.faselhd.utils.DomainConfigManager

class FaselHD : MainAPI() {
    override var lang = "ar"
    
    // Domain manager for auto-detection
    private val domainManager = DomainConfigManager(
        providerName = "FaselHD",
        configFileName = "faselhd.json",
        fallbackDomain = "https://www.faselhds.biz"
    )
    
    // Dynamic mainUrl that uses the domain manager
    override var mainUrl: String
        get() = domainManager.currentDomain
        set(value) { domainManager.updateDomain(value) }
    
    override var name = "FaselHD"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    
    // Use the custom ConfigurableCloudflareKiller
    // NOTE: This class is defined in ConfigurableWebViewResolver.kt within the same package
    private val cfKiller = ConfigurableCloudflareKiller(
        blockNonHttp = true,         // STRICTLY BLOCK non-HTTP redirects
        allowThirdPartyCookies = true // Explicitly enable third-party cookies (standard behavior)
    )

    // Simple in-memory header storage
    companion object {
        // Headers are now managed by FaselState
    }

    private fun getHeaders(): Map<String, String> {
        val current = FaselState.headers
        return if (current.isNotEmpty()) {
            current
        } else {
             // Fallback to default
             mapOf("User-Agent" to USER_AGENT)
        }
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun getSafeImageHeaders(url: String): Map<String, String> {
        val headers = getHeaders().toMutableMap()
        
        if (headers.containsKey("user-agent")) {
             headers["User-Agent"] = headers.remove("user-agent")!!
        }
        if (headers.containsKey("cookie")) {
             headers["Cookie"] = headers.remove("cookie")!!
        }

        val finalHeaders = if (url.contains("faselhd", ignoreCase = true)) {
            headers + mapOf("Referer" to mainUrl)
        } else {
            headers.remove("Cookie")
            headers + mapOf("Referer" to mainUrl)
        }
        return finalHeaders
    }

    private fun fixUrl(url: String): String {
        // Dynamic domain fix - ensure www prefix
        try {
            val currentHost = java.net.URI(mainUrl).host ?: return url
            val hostWithoutWww = currentHost.removePrefix("www.")
            if (url.contains(hostWithoutWww) && !url.contains("www.")) {
                return url.replace("://$hostWithoutWww", "://www.$hostWithoutWww")
            }
        } catch (e: Exception) { }
        return url
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("div.postDiv a").attr("href") ?: return null
        val rawPosterUrl = select("div.postDiv a div img").attr("data-src")
            .ifEmpty { select("div.postDiv a div img").attr("src") }
        val posterUrl = fixUrl(rawPosterUrl)
        
        val title = select("div.postDiv a div img").attr("alt")
        val quality = select(".quality").first()?.text()?.replace("1080p |-".toRegex(), "")
        val type = if(title.contains("فيلم")) TvType.Movie else TvType.TvSeries
        
        return newMovieSearchResponse(
            title.replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي".toRegex(),""),
            url,
            type
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            this.posterHeaders = getSafeImageHeaders(posterUrl)
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

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        // Initialize domain on first request
        if (!domainManager.isInitialized) {
            domainManager.initialize()
            // Update FaselState with the resolved domain
            FaselState.updateDomain(domainManager.currentDomain)
        }
        
        // Use page directly (CloudStream uses 1-indexed, site expects 1-indexed)
        // DYNAMIC DOMAIN FIX: Main page items are initialized with the default domain.
        // We must strip the old domain from request.data and use the current resolved domain.
        val relativePath = request.data.replace(Regex("^https?://[^/]+"), "")
        val pageUrl = domainManager.currentDomain + relativePath + page
        
        Log.d("FaselHD", "[getMainPage] Fetching: $pageUrl")

        // CLOUDFLARE SYNC LOGIC:
        // 1. Check if we already have valid headers
        // 2. If not, ACQUIRE LOCK and try again (double-check locking)
        // 3. If still not, run request WITH cfKiller (WebView) to get headers
        // 4. Update FaselState
        // 5. Subsequent requests use the saved headers WITHOUT cfKiller (standard request)
        
        var headers = FaselState.headers
        val response = if (headers.isEmpty()) {
            FaselState.paramMutex.withLock {
                // Double-check inside lock
                headers = FaselState.headers
                if (headers.isEmpty()) {
                    Log.i("FaselHD", "[getMainPage] First request/No headers. triggering CF Bypass for $pageUrl")
                    // This request WILL trigger WebView if needed
                    val resp = app.get(pageUrl, headers = getHeaders(), interceptor = cfKiller)
                    
                    // Headers should be properly updated by the interceptor/webview resolver
                    // But we can also manually grab them here to be safe if cfKiller exposes them
                    val cookies = cfKiller.getCookieHeaders(mainUrl).toMap()
                    val ua = com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent ?: USER_AGENT
                    
                    if (cookies.isNotEmpty()) {
                        val newHeaders = cookies.toMutableMap()
                        if (!newHeaders.any { it.key.equals("user-agent", true) }) {
                            newHeaders["User-Agent"] = ua
                        }
                        FaselState.updateHeaders(newHeaders)
                    }
                    resp
                } else {
                     Log.i("FaselHD", "[getMainPage] Headers found (in lock). reusing session for $pageUrl")
                     app.get(pageUrl, headers = headers) // No cfKiller
                }
            }
        } else {
             Log.i("FaselHD", "[getMainPage] Headers found. reusing session for $pageUrl")
             val resp = app.get(pageUrl, headers = headers) // No cfKiller initially
             
             if (resp.code == 403 || resp.code == 503) {
                 Log.w("FaselHD", "[getMainPage] Headers expired (403/503). Retrying with CF Killer.")
                 // Clear bad headers
                 FaselState.headers = emptyMap()
                 // Retry with interceptor to refresh
                 val retryResp = app.get(pageUrl, headers = getHeaders(), interceptor = cfKiller)
                 
                 // Capture new headers from retry
                 val cookies = cfKiller.getCookieHeaders(mainUrl).toMap()
                 val ua = com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent ?: USER_AGENT
                 
                 if (cookies.isNotEmpty()) {
                     val newHeaders = cookies.toMutableMap()
                     if (!newHeaders.any { it.key.equals("user-agent", true) }) {
                         newHeaders["User-Agent"] = ua
                     }
                     FaselState.updateHeaders(newHeaders)
                 }
                 retryResp
             } else {
                 resp
             }
        }
        
        val doc = response.document
        
        // Check for domain redirect
        // Passing response.url to domain manager
        if(domainManager.checkForDomainChange(response.url)) {
            FaselState.updateDomain(domainManager.currentDomain)
        }
        
        // Update cache
        val currentCookies = cfKiller.getCookieHeaders(mainUrl).toMap().toMutableMap()
        val ua = com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent ?: USER_AGENT
        if (!currentCookies.keys.any { it.equals("user-agent", true) }) {
            currentCookies["User-Agent"] = ua
        }
        if (currentCookies.isNotEmpty()) FaselState.updateHeaders(currentCookies)

        val list = doc.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
            .mapNotNull { element ->
                element.toSearchResponse()
            }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ","+")
        var headers = getHeaders()
        val d = app.get("$mainUrl/?s=$q", headers = headers, interceptor = cfKiller).document

        // Update cache
        val currentCookies = cfKiller.getCookieHeaders(mainUrl).toMap().toMutableMap()
        val ua = com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent ?: USER_AGENT
        if (!currentCookies.keys.any { it.equals("user-agent", true) }) {
            currentCookies["User-Agent"] = ua
        }
        if (currentCookies.isNotEmpty()) FaselState.updateHeaders(currentCookies)

        return d.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
            .mapNotNull {
                it.toSearchResponse()
            }
    }


    override suspend fun load(url: String): LoadResponse {
        val headers = getHeaders()
        val doc = app.get(url, headers = headers, interceptor = cfKiller).document

        // Update cache
        val currentCookies = cfKiller.getCookieHeaders(mainUrl).toMap().toMutableMap()
        val ua = com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent ?: USER_AGENT
        if (!currentCookies.keys.any { it.equals("user-agent", true) }) {
            currentCookies["User-Agent"] = ua
        }
        if (currentCookies.isNotEmpty()) FaselState.updateHeaders(currentCookies)

        val isMovie = doc.select("div.epAll").isEmpty()
        
        val posterElement = doc.select("div.posterImg img")
        val rawPosterUrl = posterElement.attr("data-src")
             .ifEmpty { posterElement.attr("src") }
             .ifEmpty { doc.select("div.seasonDiv.active img").attr("data-src") }
             
        val posterUrl = fixUrl(rawPosterUrl)

        val year = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]").firstOrNull {
            it.text().contains("سنة|موعد".toRegex())
        }?.text()?.getIntFromText()

        val title =
            doc.select("title").text().replace(" - فاصل إعلاني", "")
                .replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|$year".toRegex(),"")
        val duration = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]").firstOrNull {
            it.text().contains("مدة|توقيت".toRegex())
        }?.text()?.getIntFromText()

        val tags = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]:contains(تصنيف الفيلم) a").map {
            it.text()
        }
        val recommendations = doc.select("div#postList div.postDiv").mapNotNull {
            it.toSearchResponse()
        }
        val synopsis = doc.select("div.singleDesc p").text()
        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = getSafeImageHeaders(posterUrl)
            }
        } else {
            val episodes = ArrayList<Episode>()
            doc.select("div.epAll a").map {
                episodes.add(
                    newEpisode(it.attr("href")) {
                       this.name = it.text()
                       this.season = doc.select("div.seasonDiv.active div.title").text().getIntFromText() ?: 1
                       this.episode = it.text().getIntFromText()
                    }
                )
            }
            doc.select("div[id=\"seasonList\"] div[class=\"col-xl-2 col-lg-3 col-md-6\"] div.seasonDiv")
                .not(".active").amap { it ->
                    val id = it.attr("onclick").replace(".*\\/\\?p=|'".toRegex(), "")
                    val s = app.get("$mainUrl/?p="+id, headers = headers, interceptor = cfKiller).document
                    s.select("div.epAll a").map {
                        episodes.add(
                            newEpisode(it.attr("href")) {
                                this.name = it.text()
                                this.season = s.select("div.seasonDiv.active div.title").text().getIntFromText()
                                this.episode = it.text().getIntFromText()
                            }
                        )
                    }
                }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.duration = duration
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = getSafeImageHeaders(posterUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = getHeaders()
        val doc = app.get(data, headers = headers, interceptor = cfKiller).document

        // Update cache
        val currentCookies = cfKiller.getCookieHeaders(mainUrl).toMap().toMutableMap()
        val ua = com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent ?: USER_AGENT
        Log.d("FaselHD", "[loadLinks] cfKiller cookies: $currentCookies")
        Log.d("FaselHD", "[loadLinks] WebViewResolver.webViewUserAgent: ${com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent}")
        if (!currentCookies.keys.any { it.equals("user-agent", true) }) {
            currentCookies["User-Agent"] = ua
        }
        if (currentCookies.isNotEmpty()) {
            Log.d("FaselHD", "[loadLinks] Updating FaselState with: $currentCookies")
            FaselState.updateHeaders(currentCookies)
        }
        Log.d("FaselHD", "[loadLinks] FaselState.headers AFTER update: ${FaselState.headers}")
        
        val urlRegex = "'.*?'".toRegex()
        val elements = doc.select(".signleWatch ul.tabs-ul li[onclick]")
        
        var foundLink = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
             foundLink = true
             callback(link)
        }

        Log.d("FaselHD", "Found ${elements.size} potential link elements")
        for (li in elements) {
            if (foundLink) break
            
            var url: String? = null
            val onclickAttr = li.attr("onclick")
            val match = urlRegex.find(onclickAttr)
            
            if (match != null) {
                url = match.value.replace("'", "")
                Log.d("FaselHD", "Found URL via regex: $url")
            } else {
                 // Fallback: Check for data-url or other attributes if onclick parsing fails
                 url = li.attr("data-url").ifEmpty { li.attr("data-link") }
                 Log.d("FaselHD", "Found URL via fallback: $url")
            }
            
            if (!url.isNullOrEmpty() && url.contains("faselhd")) {
                Log.d("FaselHD", "Loading extractor for URL: $url")
                FaselSniffer().getUrl(url, data, subtitleCallback, wrappedCallback)
            } else {
                Log.d("FaselHD", "Skipping URL: $url (isNullOrEmpty=${url.isNullOrEmpty()}, containsFasel=${url?.contains("faselhd")})")
            }
        }
        return true
    }
}

object FaselState {
    var headers: Map<String, String> = emptyMap()
    // Mutex to ensure only one CF challenge runs at a time
    val paramMutex = Mutex()
    
    var currentDomain: String = "https://www.faselhds.biz"
        private set

    fun init() {
        // Initialize if needed
    }
    
    fun updateDomain(domain: String) {
        currentDomain = domain
        Log.d("FaselState", "[updateDomain] Domain set to: $domain")
    }
    
    fun getDomainHost(): String {
        return try {
            java.net.URI(currentDomain).host ?: "faselhds.biz"
        } catch (e: Exception) {
            "faselhds.biz"
        }
    }

    fun updateHeaders(newHeaders: Map<String, String>) {
        Log.d("FaselState", "[updateHeaders] Incoming: $newHeaders")
        headers = newHeaders
        Log.d("FaselState", "[updateHeaders] Stored: $headers")
    }
}
