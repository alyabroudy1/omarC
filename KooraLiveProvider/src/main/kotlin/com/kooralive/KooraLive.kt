package com.kooralive

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class KooraLive : BaseProvider() {

    override val baseDomain get() = "koora-livehd7.com"
    override val providerName get() = "KooraLive"
    override var lang = "ar"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/kooralive.json"
    
    override val supportedTypes = setOf(TvType.Live)

    override fun getParser(): NewBaseParser {
        return KooraLiveParser()
    }

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("data:") || url.startsWith("intent:")) return url
        
        return try {
            val uri = java.net.URI(url)
            val isTargetHost = uri.host?.let { host ->
                host.contains("koora-live") || host.contains("koora-livehd") || host == "koora-livehd7.com"
            } ?: false
            
            if (uri.isAbsolute && !isTargetHost) {
                return url
            }

            val pathWithQuery = StringBuilder()
            uri.path?.let { pathWithQuery.append(it) }
            uri.query?.let { pathWithQuery.append("?").append(it) }
            uri.fragment?.let { pathWithQuery.append("#").append(it) }
            
            val finalPath = pathWithQuery.toString()
            if (finalPath.isEmpty() || finalPath == "/") mainUrl
            else "$mainUrl/${finalPath.trimStart('/')}"
        } catch (e: Exception) {
            url
        }
    }

    private fun parseMatchesFromDocument(doc: Document): List<SearchResponse> {
        val matches = mutableListOf<SearchResponse>()
        val processedUrls = mutableSetOf<String>()
        
        for (container in doc.select(".AF_Match.AF_EvItem")) {
            val aTag = container.selectFirst("a.AF_EventMask") ?: continue
            val urlRaw = aTag.attr("href")
            
            val fTeam = container.selectFirst(".AF_FTeam")
            val rightTeam = fTeam?.selectFirst(".AF_TeamName")?.text()?.trim() ?: "Team A"
            val rightLogo = fTeam?.selectFirst("img")?.attr("data-src")?.ifBlank { fTeam.selectFirst("img")?.attr("src") } ?: ""
            
            val sTeam = container.selectFirst(".AF_STeam")
            val leftTeam = sTeam?.selectFirst(".AF_TeamName")?.text()?.trim() ?: "Team B"
            val leftLogo = sTeam?.selectFirst("img")?.attr("data-src")?.ifBlank { sTeam.selectFirst("img")?.attr("src") } ?: ""
            
            val results = container.select(".AF_EventResult .result").map { it.text().trim() }
            val scoreText = if (results.size >= 2) "${results[0]} - ${results[1]}" else "VS"
            
            val matchTime = container.selectFirst(".AF_EvTime")?.text()?.trim() ?: ""
            val status = container.selectFirst(".AF_StaText")?.text()?.trim() ?: ""
            
            val title = buildString {
                append("$rightTeam $scoreText $leftTeam")
                if (matchTime.isNotBlank() || status.isNotBlank()) {
                    append("\n")
                    if (matchTime.isNotBlank()) append("⏱ $matchTime")
                    if (status.isNotBlank()) {
                        if (matchTime.isNotBlank()) append(" | ")
                        append("📌 $status")
                    }
                }
            }
            
            val url = fixUrl(urlRaw)
            if (url.isBlank() || !processedUrls.add(url)) continue
            
            matches.add(newMovieSearchResponse(title, url, TvType.Live) {
                this.posterUrl = rightLogo.ifBlank { leftLogo }
            })
        }
        return matches
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        httpService.ensureInitialized()
        val doc = httpService.getDocument(mainUrl) ?: return null
        val homePageList = mutableListOf<HomePageList>()
        
        val todayMatches = parseMatchesFromDocument(doc)
        if (todayMatches.isNotEmpty()) {
            homePageList.add(HomePageList("مباريات اليوم", todayMatches, isHorizontalImages = true))
        }
        
        return newHomePageResponse(homePageList, false)
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val html = httpService.getText(url, skipRewrite = true) ?: return emptyList()
        val doc = org.jsoup.Jsoup.parse(html, url)
        
        val allMatches = parseMatchesFromDocument(doc)
        return allMatches.filter { match ->
            match.name.contains(query, ignoreCase = true)
        }
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        return searchNormal(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = httpService.getText(url, skipRewrite = true) ?: return null
        val doc = org.jsoup.Jsoup.parse(html, url)
        
        val titleNode = doc.selectFirst("meta[property='og:title']")
        val title = titleNode?.attr("content") ?: "Koora Live Match"
        
        val posterNode = doc.selectFirst("meta[property='og:image']")
        val poster = posterNode?.attr("content") ?: ""
        
        val plotNode = doc.selectFirst("meta[property='og:description']")
        val plot = plotNode?.attr("content") ?: ""
        
        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        httpService.ensureInitialized()
        val userAgent = httpService.userAgent

        val html = httpService.getText(data, skipRewrite = true) ?: return false
        val doc = org.jsoup.Jsoup.parse(html, data)
        
        val iframeElement = doc.selectFirst("iframe[src*='albaplayer'], .video-con iframe, iframe")
        val iframeSrc = iframeElement?.attr("src")?.trim()
        
        Log.d("KooraLive", "Match page iframe src: $iframeSrc")
        
        if (iframeSrc.isNullOrBlank()) {
            return false
        }
        
        val playerUrl = fixUrl(iframeSrc)
        Log.d("KooraLive", "Fetching player URL: $playerUrl")
        
        val pHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to data
        )
        val playerResponse = httpService.getText(playerUrl, pHeaders, skipRewrite = true)
        
        val menuLinks = mutableListOf<String>()
        if (playerResponse != null) {
            val playerDoc = org.jsoup.Jsoup.parse(playerResponse, playerUrl)
            
            // Extract alternate stream servers from the aplr-menu
            for (btn in playerDoc.select("a.aplr-link")) {
                val href = btn.attr("href")
                if (href.isNotBlank() && href != playerUrl && !href.contains("javascript:")) {
                    menuLinks.add(fixUrl(href))
                }
            }
            Log.d("KooraLive", "Found alternative servers in menu: $menuLinks")

            // Statically extract all players in parallel using coroutineScope and async
            val allUrls = (if (playerUrl.isNotBlank()) listOf(playerUrl) else emptyList()) + menuLinks
            val results = kotlinx.coroutines.coroutineScope {
                allUrls.map { targetPlayerUrl ->
                    async {
                        try {
                            val pResponse = if (targetPlayerUrl == playerUrl) {
                                playerResponse
                            } else {
                                httpService.getText(targetPlayerUrl, pHeaders, skipRewrite = true)
                            }
                            
                            if (pResponse != null) {
                                val pDoc = org.jsoup.Jsoup.parse(pResponse, targetPlayerUrl)
                                val innerIframe = pDoc.selectFirst("iframe[src*='vertyuz.xyz'], iframe")
                                val innerIframeSrc = innerIframe?.attr("src")
                                if (!innerIframeSrc.isNullOrBlank()) {
                                    val vertyuzUrl = fixUrl(innerIframeSrc)
                                    Log.d("KooraLive", "Loading VertyuzExtractor for: $vertyuzUrl")
                                    if (loadExtractor(vertyuzUrl, targetPlayerUrl, subtitleCallback, callback)) {
                                        return@async true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("KooraLive", "Error during static extraction for $targetPlayerUrl: ${e.message}")
                        }
                        false
                    }
                }.awaitAll()
            }
            
            foundLinks = results.any { it }
        }
        
        // Execute WebView Sniffer fallback if we couldn't statically extract any streams
        if (!foundLinks) {
            val sniffUrls = mutableListOf<String>()
            if (playerUrl.isNotBlank()) {
                sniffUrls.add(playerUrl)
            }
            sniffUrls.addAll(menuLinks)
            
            for (sniffUrl in sniffUrls) {
                Log.d("KooraLive", "Executing WebView Sniffer fallback on: $sniffUrl")
                if (awaitSnifferResult(sniffUrl, data, subtitleCallback, callback, 15000L)) {
                    foundLinks = true
                    break // Stop sniffing other servers once we find at least one working stream
                }
            }
        }
        
        return foundLinks
    }
}
