package com.syrialive

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import org.jsoup.nodes.Document
import org.jsoup.Jsoup
import android.util.Base64

class SyriaLive : BaseProvider() {

    override var name = "سيريا لايف (كورة)"
    override val baseDomain get() = "d.syrlive.com/"
    override val providerName get() = "SyriaLive"
    override var lang = "ar"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/syrialive.json"

    companion object {
        private fun decodePlayerPage(response: String): String {
            val dwRegex = Regex("""document\.write\s*\(\s*"([^"]+)"\s*\)""", RegexOption.DOT_MATCHES_ALL)
            val dwMatch = dwRegex.find(response) ?: return response
            val encoded = dwMatch.groupValues[1]
            val decoded = StringBuilder()
            var i = 0
            while (i < encoded.length) {
                when {
                    encoded[i] == '\\' && i + 1 < encoded.length && encoded[i + 1] == 'x' -> {
                        if (i + 4 < encoded.length) {
                            val hex = encoded.substring(i + 2, i + 4)
                            try {
                                decoded.append(hex.toInt(16).toChar())
                            } catch (e: NumberFormatException) {
                                decoded.append(encoded[i])
                            }
                            i += 4
                        } else {
                            decoded.append(encoded[i])
                            i++
                        }
                    }
                    encoded[i] == '\\' && i + 1 < encoded.length && encoded[i + 1] == 'u' -> {
                        if (i + 6 < encoded.length) {
                            val hex = encoded.substring(i + 2, i + 6)
                            try {
                                decoded.append(hex.toInt(16).toChar())
                            } catch (e: NumberFormatException) {
                                decoded.append(encoded[i])
                            }
                            i += 6
                        } else {
                            decoded.append(encoded[i])
                            i++
                        }
                    }
                    encoded[i] == '\\' && i + 1 < encoded.length -> {
                        decoded.append(encoded[i + 1])
                        i += 2
                    }
                    else -> {
                        decoded.append(encoded[i])
                        i++
                    }
                }
            }
            return decoded.toString()
        }
    }
    
    override val supportedTypes = setOf(TvType.Live, TvType.Movie)

    override fun getParser(): NewBaseParser {
        return SyriaLiveParser()
    }

    private fun parseMatchContainers(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val matches = mutableListOf<SearchResponse>()
        doc.select(".match-container").forEach { element ->
            val rightTeam = element.selectFirst(".right-team .team-name")?.text()
            val leftTeam = element.selectFirst(".left-team .team-name")?.text()

            if (rightTeam != null && leftTeam != null) {
                val result = element.selectFirst(".result")?.text() ?: "VS"
                val matchTime = element.selectFirst(".match-time")?.text() ?: ""
                val status = element.selectFirst(".date.end")?.text() ?: ""
                val title = "$rightTeam $result $leftTeam"
                val subtitle = when {
                    status.isNotBlank() && matchTime.isNotBlank() -> "$matchTime • $status"
                    matchTime.isNotBlank() -> matchTime
                    status.isNotBlank() -> status
                    else -> null
                }

                val url = element.selectFirst("a")?.attr("href")?.let { fixSyriaUrl(it) }

                val posterImg = element.selectFirst(".right-team img")
                val poster = posterImg?.attr("data-src")?.ifBlank { posterImg.attr("src") } ?: ""

                if (url != null) {
                    matches.add(newMovieSearchResponse(if (subtitle != null) "$title — $subtitle" else title, url, TvType.Live) {
                        this.posterUrl = fixSyriaUrl(poster)
                    })
                }
            }
        }
        return matches
    }

    private fun fixSyriaUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("data:") || url.startsWith("intent:")) return url

        return try {
            val uri = java.net.URI(url)
            // Preserve external domains (like player.syria-player.live)
            if (uri.isAbsolute && uri.host?.contains("syrlive") == false) {
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

    /**
     * Overriding getMainPage because the root URL `https://d.syrlive.com/` returns two 
     * entirely different structural lists ("مباريات اليوم" matches and "آخر الأخبار" news).
     * If we relied on `mainPageOf`, `BaseProvider` would iterate and fetch the heavy 
     * Cloudflare-protected page multiple times and NewBaseParser configs only support 
     * one list per container configuration.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // Ensure HTTP service is ready
        httpService.ensureInitialized()
        val doc = httpService.getDocument(mainUrl, rewriteDomain = true) ?: return null

        val homePageList = mutableListOf<HomePageList>()

        // 1. Matches (complex structure)
        val todayMatches = parseMatchContainers(doc)
        if (todayMatches.isNotEmpty()) {
            homePageList.add(HomePageList("مباريات اليوم", todayMatches, isHorizontalImages = true))
        }

        // 2. Tomorrow's matches
        try {
            val tomorrowUrl = mainUrl.trimEnd('/') + "/matches-tomorrow/"
            val tomorrowDoc = httpService.getDocument(tomorrowUrl, rewriteDomain = true)
            if (tomorrowDoc != null) {
                val tomorrowMatches = parseMatchContainers(tomorrowDoc)
                if (tomorrowMatches.isNotEmpty()) {
                    homePageList.add(HomePageList("مباريات الغد", tomorrowMatches, isHorizontalImages = true))
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to fetch tomorrow matches: ${e.message}")
        }

        // 3. News/Movies (standard structure)
        val news = mutableListOf<SearchResponse>()
        doc.select(".AY-PItem").forEach { element ->
            val titleNode = element.selectFirst(".AY-PostTitle a")
            val title = titleNode?.text() ?: return@forEach
            val url = titleNode.attr("href")?.let { fixSyriaUrl(it) } ?: return@forEach
            
            val imgNode = element.selectFirst("img")
            val poster = imgNode?.attr("data-src")?.ifBlank { imgNode.attr("src") } ?: ""
            
            news.add(newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = fixSyriaUrl(poster)
            })
        }
        
        if (news.isNotEmpty()) {
            homePageList.add(HomePageList("آخر الأخبار", news, isHorizontalImages = false))
        }

        return newHomePageResponse(homePageList, false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = super.load(url)
        
        // BaseProvider incorrectly maps TvType.Live to an empty TvSeriesLoadResponse
        // which triggers the "Coming soon" UI without a play button in Cloudstream.
        // We intercept this specific to SyriaLive and convert it back to a LiveStreamLoadResponse.
        if (data is TvSeriesLoadResponse && url.contains("/matches/")) {
            return newLiveStreamLoadResponse(data.name, data.url, data.url) {
                this.posterUrl = data.posterUrl
                this.plot = data.plot
                this.tags = data.tags
                this.year = data.year
            }
        }
        
        return data
    }

    /**
     * Overridden to handle custom internal players (AlbaPlayerControl and Clappr) which
     * cannot rely on generic `BaseProvider.loadLinks()` extractor delegations.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        httpService.ensureInitialized()
        val userAgent = httpService.userAgent

        // 1. Fetch exact match/movie page via getText (bypasses RequestQueue
        //    follower rewrite entirely, keeping the original domain)
        val reqHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "https://www.google.com/"
        )
        val html = httpService.getText(data, reqHeaders, rewriteDomain = false) ?: return false
        val doc = org.jsoup.Jsoup.parse(html, data)
        
        val iframeElement = doc.selectFirst(".entry-content iframe")
        val iframeSrc = iframeElement?.attr("src")
        
        // 2. Extractor servers list (.video-serv a)
        for (btn in doc.select(".video-serv a")) {
            val href = btn.attr("href")
            if (href.isNotBlank()) {
                val fixedUrl = fixSyriaUrl(href)
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
                foundLinks = true
            }
        }
        
        // 3. Main player iframe
        if (iframeSrc.isNullOrBlank()) {
            return foundLinks
        }
        
        val playerUrl = fixSyriaUrl(iframeSrc)
        val pHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to data
        )
        
        // First: delegate to shared extractors (AlbaPlayerExtractor handles document.write deobfuscation)
        if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
            foundLinks = true
            return foundLinks
        }
        
        // Fallback: fetch page and try inline extraction with deobfuscation
        val playerResponse = httpService.getText(playerUrl, pHeaders, rewriteDomain = false) ?: return foundLinks
        
        // Decode document.write hex obfuscation if present
        val decodedHtml = decodePlayerPage(playerResponse)
        
        // Check for AlbaPlayerControl base64 packed stream
        val albaRegex = Regex("AlbaPlayerControl\\('([^']+)'")
        val albaMatch = albaRegex.find(decodedHtml)
        if (albaMatch != null) {
            val encodedString = albaMatch.groupValues[1]
            try {
                val decodedBytes = Base64.decode(encodedString, Base64.DEFAULT)
                val m3u8Url = String(decodedBytes, Charsets.UTF_8)
                
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = m3u8Url,
                    referer = playerUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to playerUrl,
                        "Origin" to "https://player.syria-player.live",
                        "Accept" to "*/*"
                    )
                )
                
                m3u8Links.forEach { link ->
                    callback(link)
                    foundLinks = true
                }
            } catch (e: Exception) {
                Log.e("SyriaLive", "Failed to decode AlbaPlayerControl: ${e.message}")
            }
        } 
        // Check for Clappr source
        else if (decodedHtml.contains("Clappr.Player")) {
            val clapprRegex = Regex("source\\s*:\\s*\"([^\"]+)\"")
            val clapprMatch = clapprRegex.find(decodedHtml)
            if (clapprMatch != null) {
                val m3u8Url = clapprMatch.groupValues[1]
                
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = m3u8Url,
                    referer = playerUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent
                    )
                )
                
                m3u8Links.forEach { link ->
                    callback(link)
                    foundLinks = true
                }
            }
        }
        
        if (!foundLinks) {
            val sniffUrls = mutableListOf<String>()
            if (playerUrl.isNotBlank()) sniffUrls.add(playerUrl)
            
            for (sniffUrl in sniffUrls) {
                Log.d("SyriaLive", "Executing Sniffer fallback on: \$sniffUrl")
                if (awaitSnifferResult(sniffUrl, data, subtitleCallback, callback, 15000L)) {
                    foundLinks = true
                    break
                }
            }
        }
        
        return foundLinks
    }
}
