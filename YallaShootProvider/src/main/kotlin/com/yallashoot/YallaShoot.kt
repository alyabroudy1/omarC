package com.yallashoot

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
import android.util.Base64

class YallaShoot : BaseProvider() {

    override val baseDomain get() = "www.shoot-one.com" 
    override val providerName get() = "YallaShoot"
    override var lang = "ar"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/yallashoot.json"
    
    // We can rely on a fallback domain or provide a raw URL
    override val supportedTypes = setOf(TvType.Live, TvType.Movie)

    override fun getParser(): NewBaseParser {
        return YallaShootParser()
    }

    private fun fixYallaUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("data:") || url.startsWith("intent:")) return url
        
        return try {
            val uri = java.net.URI(url)
            // Preserve external domains like yallashoooty
            if (uri.isAbsolute && uri.host?.contains("shoot") == false) {
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        httpService.ensureInitialized()
        val doc = httpService.getDocument(mainUrl) ?: return null

        val homePageList = mutableListOf<HomePageList>()

        val matches = mutableListOf<SearchResponse>()
        doc.select(".AY_Match").forEach { element ->
            val teams = element.select(".TM_Logo img")
            if (teams.size >= 2) {
                val rightTeam = teams[0].attr("alt")
                val leftTeam = teams[1].attr("alt")
                
                val resultContainer = element.selectFirst(".MT_Result")
                val result = if (resultContainer != null && resultContainer.children().size >= 3) {
                    "${resultContainer.child(0).text()} - ${resultContainer.child(2).text()}"
                } else "VS"
                
                val title = "$rightTeam $result $leftTeam"
                
                val urlNode = element.selectFirst("a")
                val url = urlNode?.attr("href")?.let { fixYallaUrl(it) }
                
                val poster = teams[0].attr("data-src").ifBlank { teams[0].attr("src") }
                
                if (url != null) {
                    matches.add(newMovieSearchResponse(title, url, TvType.Live) {
                        this.posterUrl = fixYallaUrl(poster)
                    })
                }
            }
        }
        
        if (matches.isNotEmpty()) {
            homePageList.add(HomePageList("مباريات اليوم", matches, isHorizontalImages = true))
        }

        val news = mutableListOf<SearchResponse>()
        doc.select(".AY-PItem").forEach { element ->
            val titleNode = element.selectFirst(".AY-PostTitle a")
            val title = titleNode?.text() ?: return@forEach
            val url = titleNode.attr("href")?.let { fixYallaUrl(it) } ?: return@forEach
            
            val imgNode = element.selectFirst("img")
            val poster = imgNode?.attr("data-src")?.ifBlank { imgNode.attr("src") } ?: ""
            
            news.add(newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = fixYallaUrl(poster)
            })
        }
        
        if (news.isNotEmpty()) {
            homePageList.add(HomePageList("آخر الأخبار", news, isHorizontalImages = false))
        }

        return newHomePageResponse(homePageList, false)
    }

    /**
     * Replaces the native BaseProvider load() to prevent ProviderHttpService from forcefully
     * redirecting "yallashoooty.com" cross-domain pages into "shoot-one.com". 
     */
    override suspend fun load(url: String): LoadResponse? {
        val isMatch = url.contains("yallashoooty") || url.contains("/sport") || url.contains("/https") || url.contains(".html")
        
        if (isMatch) {
            val html = httpService.getText(url, skipRewrite = true) ?: return null
            val doc = org.jsoup.Jsoup.parse(html, url)
            
            val titleNode = doc.selectFirst(".EntryTitle")
            val title = titleNode?.text() ?: "YallaShoot Match"
            
            // Extract poster properly
            val posterNode = doc.selectFirst("meta[property='og:image']")
            val fallbackPoster = doc.selectFirst(".teamlogo")?.attr("data-src") ?: ""
            val poster = posterNode?.attr("content")?.ifBlank { fallbackPoster } ?: ""
            
            val plot = doc.selectFirst(".entry-content p")?.text()
            
            return newLiveStreamLoadResponse(title, url, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        
        return super.load(url)
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

        // Bypass BaseProvider rewrite strictly using getText
        val html = httpService.getText(data, skipRewrite = true) ?: return false
        val doc = org.jsoup.Jsoup.parse(html, data)
        
        val iframeElement = doc.selectFirst(".entry-content iframe")
        val iframeSrc = iframeElement?.attr("src")
        
        // Extract external players
        for (btn in doc.select(".video-serv a")) {
            val href = btn.attr("href")
            if (href.isNotBlank()) {
                val fixedUrl = fixYallaUrl(href)
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
                foundLinks = true
            }
        }
        
        if (iframeSrc.isNullOrBlank()) {
            return foundLinks
        }
        
        val playerUrl = fixYallaUrl(iframeSrc)
        val pHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to data
        )
        val playerResponse = httpService.getText(playerUrl, pHeaders, skipRewrite = true) ?: return foundLinks
        
        val albaRegex = Regex("AlbaPlayerControl\\('([^']+)'")
        val albaMatch = albaRegex.find(playerResponse)
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
                        "Origin" to "https://${java.net.URI(playerUrl).host}",
                        "Accept" to "*/*"
                    )
                )
                
                m3u8Links.forEach { link ->
                    callback(link)
                    foundLinks = true
                }
            } catch (e: Exception) {
                Log.e("YallaShoot", "Failed to decode AlbaPlayerControl: ${e.message}")
            }
        } else if (playerResponse.contains("Clappr.Player")) {
            val clapprRegex = Regex("source\\s*:\\s*\"([^\"]+)\"")
            val clapprMatch = clapprRegex.find(playerResponse)
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
        
        return foundLinks
    }
}
