package com.faselHDV2

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.extractors.FaselHDExtractor
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
    override val preferIpv6 get() = false

    /**
     * Force IPv4 for everything this provider does.
     *
     * fasel-hd.cam is dual-stack, so on a dual-stack network (home WiFi) the request that mints the
     * stream token goes out over IPv6 by default, and the CDN bakes that IPv6 into the URL path:
     *   …/stream/v1/hls/<id>/<exp>/www.fasel-hd.cam/all/2001:16b8:…:7efa/yes/…
     * The media host (c.scdns.io) has NO AAAA record, so playback can only ever happen over IPv4 —
     * the source IP can never match the one in the token and the CDN answers 403. Measured
     * 2026-07-25: that exact URL, still 6h from expiry, returns 403 over IPv4 and is unresolvable
     * over IPv6. On LTE/VPN (IPv4-only path) the token is minted for the IPv4 the player uses,
     * which is why it "works on mobile data".
     *
     * NEVER set preferIpv6 here — it would force the broken case to happen every time.
     */
    override val preferIpv4 get() = true

    /**
     * Keep the IPv4 pin alive during playback, not just during extraction.
     *
     * [preferIpv4] only covers this provider's own requests. Without an interceptor here, core hands
     * the link to a bare `DefaultHttpDataSource.Factory()` (`CS3IPlayer.createOnlineSource`) — plain
     * `HttpURLConnection` with its own system DNS — so the token can be minted over IPv4 and then
     * played over IPv6, which scdns.io rejects with 403 even though the token itself is fine.
     * A non-null return switches core to `OkHttpDataSource`; see [Ipv4PinnedInterceptor].
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor? =
        if (extractorLink.url.contains("scdns.io", ignoreCase = true)) {
            com.cloudstream.shared.network.Ipv4PinnedInterceptor
        } else null

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
                        
                        val seasonDoc = httpService.getDocument(fullUrl, rewriteDomain = true) ?: return@async emptyList()
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
            
            var doc = httpService.getDocument(url, checkDomainChange = true, rewriteDomain = true)
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
                
                doc = httpService.post(ajaxUrl, data, referer = "$mainUrl/main", headers = headers, rewriteDomain = true)
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
            
            var doc = httpService.getDocumentNoFallback(url, checkDomainChange = true, rewriteDomain = true)
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
        val methodTag = "[FaselHDV2] [loadLinks]"
        Log.i(methodTag, "=== START loadLinks ===")
        Log.i(methodTag, "Target URL: $data")
        Log.i(methodTag, "Provider settings: preferIpv4=$preferIpv4, preferIpv6=$preferIpv6")

        try {
            httpService.ensureInitialized()

            // Step 1: Fetch detail/movie document
            val detailDoc = httpService.getDocument(data, rewriteDomain = true)
            if (detailDoc == null) {
                Log.e(methodTag, "Failed to fetch detail document from $data")
                return false
            }

            // Step 2: Check for player page (play.php?vid=...)
            val parser = getParser() as FaselHDV2Parser
            val watchPageUrl = parser.getPlayerPageUrl(detailDoc)
            val targetDoc = if (!watchPageUrl.isNullOrBlank()) {
                val fullWatchUrl = if (watchPageUrl.startsWith("http")) watchPageUrl else "$mainUrl/$watchPageUrl"
                Log.i(methodTag, "Following watch player page: $fullWatchUrl")
                httpService.getDocument(fullWatchUrl, rewriteDomain = true) ?: detailDoc
            } else {
                Log.d(methodTag, "No separate player page found, using detail document")
                detailDoc
            }

            // Step 3: Extract watch server URLs from document (.tabs-ul li[onclick], iframe[src], etc.)
            val watchUrls = parser.extractIframeSources(targetDoc)
            Log.i(methodTag, "Extracted ${watchUrls.size} watch server URLs:")
            watchUrls.forEachIndexed { idx, url ->
                Log.i(methodTag, "  Server #$idx: $url")
            }

            if (watchUrls.isEmpty()) {
                Log.w(methodTag, "No watch server URLs found on page")
                return false
            }

            val referer = "$mainUrl/"

            // Step 4: Try standard extractors first on all watch URLs
            Log.i(methodTag, "Trying standard extractors on ${watchUrls.size} watch URLs...")
            var standardExtractorFound = false
            for ((index, watchUrl) in watchUrls.withIndex()) {
                Log.d(methodTag, "Checking standard extractors for [$index]: $watchUrl")
                val links = collectExtractorLinks(watchUrl, referer, subtitleCallback, timeoutMs = 8000L)
                if (links.isNotEmpty()) {
                    Log.i(methodTag, "Standard extractor SUCCESS for [$index] (${links.size} links found): $watchUrl")
                    links.forEach { link ->
                        Log.i(methodTag, "Delivering link: ${link.name} | ${link.url.take(100)}")
                        callback(link)
                    }
                    standardExtractorFound = true
                }
            }

            if (standardExtractorFound) {
                Log.i(methodTag, "=== loadLinks SUCCESS via standard extractors ===")
                return true
            }

            Log.i(methodTag, "No standard extractors triggered. Passing ${watchUrls.size} watch URLs sequentially to VideoSnifferEngine (IPv4 enabled)...")

            // Step 5: Sequential fallback to VideoSnifferEngine for each watch URL
            val serverSelectors = parser.buildServerSelectors(targetDoc, watchUrls)

            for ((index, watchUrl) in watchUrls.withIndex()) {
                val selector = serverSelectors.getOrNull(index)
                Log.i(methodTag, "=== Sniffing Server [$index/${watchUrls.size}] ===")
                Log.i(methodTag, "URL: $watchUrl")
                Log.i(methodTag, "Referer: $referer")
                if (selector != null) {
                    Log.d(methodTag, "Selector: ${selector.query}")
                }

                val snifferResult = awaitSnifferResult(
                    targetUrl = watchUrl,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    timeoutMs = com.cloudstream.shared.webview.VideoSnifferEngine.SNIFFER_PLAYER_TIMEOUT_MS,
                    selector = selector
                )

                if (snifferResult) {
                    Log.i(methodTag, "=== SUCCESS via VideoSnifferEngine on Server [$index] ===")
                    return true
                }

                Log.w(methodTag, "VideoSnifferEngine failed for Server [$index]")
            }

            Log.w(methodTag, "=== loadLinks END: All ${watchUrls.size} servers failed ===")
            return false

        } catch (e: Exception) {
            Log.e(methodTag, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}