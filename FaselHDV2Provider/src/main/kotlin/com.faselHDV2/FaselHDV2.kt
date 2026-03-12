package com.faselHDV2

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class FaselHDV2 : BaseProvider() {

    override val baseDomain get() = "faselhds.biz"
    override val providerName get() = "FaselHDV2"
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
            mainUrl = "https://${httpService.currentDomain}"
            
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
            mainUrl = "https://${httpService.currentDomain}"
            
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
        Log.i(methodTag, "START data='$data'")

        // Try to extract FaselHD player first BEFORE base logic
        var extractorFoundStreams = false
        try {
            val doc = httpService.getDocument(data)
            if (doc != null) {
                val playerUrl = getParser().getPlayerPageUrl(doc)
                if (playerUrl != null) {
                    val fullUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl/$playerUrl".replace("//", "/").replace("https:/", "https://")
                    Log.i(methodTag, "Got player URL: $fullUrl")
                    
                    val playerDoc = httpService.getDocument(fullUrl)
                    if (playerDoc != null) {
                        val urls = getParser().extractWatchServersUrls(playerDoc)
                        Log.i(methodTag, "Player has ${urls.size} URLs")
                        
                        for (url in urls) {
                            Log.i(methodTag, "Checking URL: $url")
                            if (url.contains("video_player") || url.contains("player_token")) {
                                Log.i(methodTag, ">>> Calling FaselHDExtractor for: $url")
                                com.cloudstream.shared.extractors.FaselHDExtractor().getUrl(url, fullUrl, subtitleCallback, callback)
                                extractorFoundStreams = true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(methodTag, "FaselHD extractor error: ${e.message}")
        }

        // Call base logic
        val baseResult = super.loadLinks(data, isCasting, subtitleCallback, callback)

        return extractorFoundStreams || baseResult
    }
}