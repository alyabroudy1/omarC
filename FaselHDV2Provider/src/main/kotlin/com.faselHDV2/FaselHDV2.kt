package com.faselHDV2

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import org.jsoup.nodes.Document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URL

class FaselHDV2 : BaseProvider() {

    override val baseDomain get() = "faselhds.biz"
    override val providerName get() = "FaselHD"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/faselhd.json"

    override val mainPage = mainPageOf(
        "/all-movies" to "جميع الافلام",
        "/movies_top_views" to "الافلام الاعلي مشاهدة",
        "/dubbed-movies" to "الأفلام المدبلجة",
        "/movies_top_imdb" to "الافلام الاعلي تقييما IMDB",
        "/series" to "مسلسلات",
        "/recent_series" to "المضاف حديثا",
        "/anime" to "الأنمي",
    )

    override fun getParser(): NewBaseParser {
        return FaselHDV2Parser()
    }

    /**
     * Fetch extra episodes from inactive season tabs via AJAX.
     * FaselHD shows only the active season's episodes on the detail page.
     * Other seasons require fetching `$mainUrl/?p=ID` for each season tab.
     */
    override suspend fun fetchExtraEpisodes(
        doc: Document, url: String, data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        val parser = getParser() as FaselHDV2Parser
        val currentEpisodes = data.episodes?.toMutableList() ?: mutableListOf()
        
        // Parse inactive season tabs
        val seasonTabs = parser.parseSeasonTabs(doc)
        
        if (seasonTabs.isEmpty()) {
            Log.d("[FaselHDV2]", "fetchExtraEpisodes: no extra season tabs found")
            return currentEpisodes
        }
        
        Log.i("[FaselHDV2]", "fetchExtraEpisodes: fetching ${seasonTabs.size} extra seasons in parallel")
        
        // Fetch all season pages in parallel
        val extraEpisodes = coroutineScope {
            seasonTabs.map { (seasonNum, pageUrl) ->
                async {
                    try {
                        val fullUrl = if (pageUrl.startsWith("http")) pageUrl else "$mainUrl$pageUrl"
                        Log.d("[FaselHDV2]", "fetchExtraEpisodes: fetching season $seasonNum from $fullUrl")
                        
                        val seasonDoc = httpService.getDocument(fullUrl) ?: return@async emptyList()
                        val episodes = parser.parseEpisodes(seasonDoc, seasonNum)
                        
                        Log.d("[FaselHDV2]", "fetchExtraEpisodes: season $seasonNum -> ${episodes.size} episodes")
                        episodes
                    } catch (e: Exception) {
                        Log.w("[FaselHDV2]", "fetchExtraEpisodes: failed for season $seasonNum: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        
        currentEpisodes.addAll(extraEpisodes)
        return currentEpisodes
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        val methodTag = "[$name] [searchNormal override]"
        try {
            httpService.ensureInitialized()
            
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = getParser().getSearchUrl(mainUrl, encoded)
            Log.d(methodTag, "Fetching normal search URL: $url")
            
            var doc = httpService.getDocument(url, checkDomainChange = true)
            var items = doc?.let { getParser().parseSearch(it) } ?: emptyList()
            
            if (items.isEmpty()) {
                Log.w(methodTag, "Normal search failed or found 0 items. Trying AJAX fallback...")
                
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*",
                    "Origin" to mainUrl
                )
                val data = mapOf(
                    "action" to "dtc_live",
                    "trsearch" to query
                )
                
                doc = httpService.post(ajaxUrl, data, referer = "$mainUrl/main", headers = headers)
                if (doc != null) {
                    val rawHtml = doc.html()
                    Log.d(methodTag, "AJAX response HTML (length: ${rawHtml.length}):\n${rawHtml.take(2000)}")
                    items = getParser().parseSearch(doc)
                    Log.d(methodTag, "AJAX search returned ${items.size} items")
                } else {
                    Log.e(methodTag, "AJAX search also failed")
                }
            } else {
                Log.d(methodTag, "Normal search returned ${items.size} items")
            }

            return items.map { item ->
                newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                }
            }
        } catch (e: Exception) {
            Log.e(methodTag, "Error in searchNormal: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        val methodTag = "[$name] [searchLazy override]"
        try {
            httpService.ensureInitialized()
            
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = getParser().getSearchUrl(mainUrl, encoded)
            Log.d(methodTag, "Fetching lazy search URL: $url")
            
            var doc = httpService.getDocumentNoFallback(url, checkDomainChange = true)
            var items = doc?.let { getParser().parseSearch(it) } ?: emptyList()
            
            if (items.isEmpty()) {
                Log.w(methodTag, "Lazy search failed or found 0 items. Trying AJAX fallback...")
                
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*",
                    "Origin" to mainUrl
                )
                val data = mapOf(
                    "action" to "dtc_live",
                    "trsearch" to query
                )
                
                // For post requests without fallback, we use executeDirectRequest/executePostRequest directly
                val result = httpService.postText(ajaxUrl, data, referer = "$mainUrl/main", headers = headers)
                if (result != null) {
                    Log.d(methodTag, "AJAX Lazy response (length: ${result.length}):\n${result.take(2000)}")
                    doc = org.jsoup.Jsoup.parse(result, ajaxUrl)
                    items = getParser().parseSearch(doc)
                    Log.d(methodTag, "AJAX lazy search returned ${items.size} items")
                } else {
                    Log.e(methodTag, "AJAX search also failed")
                }
            } else {
                Log.d(methodTag, "Lazy search returned ${items.size} items")
            }

            return items.map { item ->
                newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                }
            }
        } catch (e: com.cloudstream.shared.service.CloudflareBlockedSearchException) {
            throw e
        } catch (e: Exception) {
            Log.e(methodTag, "Error in searchLazy: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "[$name] [loadLinks]"
        Log.i(methodTag, "START data='$data' - Using EXCLUSIVE VideoSniffer session natively!")

        try {
            httpService.ensureInitialized()
            
            // 1. Fetch detail page
            val detailDoc = httpService.getDocument(data)
            if (detailDoc == null) {
                Log.e(methodTag, "Failed to fetch detail document")
                return false
            }
            
            // 2. Extract watch URLs (servers)
            val watchUrls = getParser().extractWatchServersUrls(detailDoc)
            Log.d(methodTag, "Found ${watchUrls.size} watch URLs: $watchUrls")
            
            if (watchUrls.isEmpty()) {
                Log.w(methodTag, "No watch URLs found")
                return false
            }

            // 3. Build server selectors for the sniffer (handles server clicks)
            val serverSelectors = getParser().buildServerSelectors(detailDoc, watchUrls)
            
            val referer = try {
                val uri = java.net.URI(data)
                "${uri.scheme}://${uri.host}/"
            } catch (e: Exception) {
                mainUrl
            }

            var anyFound = false

            // 4. Sniff EACH URL one by one (Exclusive VideoSniffer mode)
            for (index in watchUrls.indices) {
                val watchUrl = watchUrls[index]
                val selector = serverSelectors.getOrNull(index)
                
                Log.d(methodTag, "Sniffing server [$index]: $watchUrl (hasSelector=${selector != null})")
                
                val result = httpService.snifferEngine.runSession(
                    url = watchUrl,
                    mode = com.cloudstream.shared.webview.Mode.FULLSCREEN,
                    userAgent = httpService.userAgent,
                    exitCondition = com.cloudstream.shared.webview.ExitCondition.VideoFound(minCount = 1),
                    timeout = 45_000L,
                    referer = referer,
                    selector = selector
                )

                if (result is com.cloudstream.shared.webview.WebViewResult.Success) {
                    val capturedLinks = result.foundLinks.map { 
                        newExtractorLink(
                            source = name,
                            name = "$name ${it.qualityLabel}",
                            url = it.url,
                            type = if (it.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.headers = it.headers
                            this.referer = referer
                        }
                    }

                    if (capturedLinks.isNotEmpty()) {
                        capturedLinks.forEach { callback(it) }
                        anyFound = true
                        Log.i(methodTag, "Captured ${capturedLinks.size} links from server [$index]")
                        // Continue to other servers? Or return true? 
                        // Typically, if one server works, we might still want others for more qualities.
                    }
                } else if (result is com.cloudstream.shared.webview.WebViewResult.Timeout) {
                    Log.w(methodTag, "Timeout sniffing server [$index]")
                }
            }

            return anyFound
        } catch (e: Exception) {
            Log.e(methodTag, "Error in loadLinks (exclusive sniffer): ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}