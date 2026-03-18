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
import android.util.Base64

class SyriaLive : BaseProvider() {

    override val baseDomain get() = "www.syrlive.com"
    override val providerName get() = "SyriaLive"
    override var lang = "ar"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/syrialive.json"
    
    override val supportedTypes = setOf(TvType.Live, TvType.Movie)

    override fun getParser(): NewBaseParser {
        return SyriaLiveParser()
    }

    // Overriding getMainPage because .match-container requires complex string concatenation
    // that the basic CSS selectors in NewBaseParser cannot handle automatically.
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val app = com.lagradost.cloudstream3.app
        val service = com.cloudstream.shared.service.ProviderHttpServiceHolder.getInstance()
        val doc = service?.getDocument("https://d.syrlive.com/") ?: app.get("https://d.syrlive.com/").document

        val homePageList = mutableListOf<HomePageList>()

        // 1. Matches (complex structure)
        val matches = mutableListOf<SearchResponse>()
        doc.select(".match-container").forEach { element ->
            val rightTeam = element.selectFirst(".right-team .team-name")?.text()
            val leftTeam = element.selectFirst(".left-team .team-name")?.text()
            
            if (rightTeam != null && leftTeam != null) {
                val result = element.selectFirst(".result")?.text() ?: "VS"
                val matchTime = element.selectFirst(".match-time")?.text() ?: ""
                val title = "$rightTeam $result $leftTeam"
                
                val url = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) }
                
                val posterImg = element.selectFirst(".right-team img")
                val poster = posterImg?.attr("data-src")?.ifBlank { posterImg.attr("src") } ?: ""
                
                if (url != null) {
                    matches.add(newMovieSearchResponse(title, url, TvType.Live) {
                        this.posterUrl = fixUrl(poster)
                    })
                }
            }
        }
        
        if (matches.isNotEmpty()) {
            homePageList.add(HomePageList("مباريات اليوم", matches, isHorizontalImages = true))
        }

        // 2. News/Movies (standard structure)
        val news = mutableListOf<SearchResponse>()
        doc.select(".AY-PItem").forEach { element ->
            val titleNode = element.selectFirst(".AY-PostTitle a")
            val title = titleNode?.text() ?: return@forEach
            val url = titleNode.attr("href")?.let { fixUrl(it) } ?: return@forEach
            
            val imgNode = element.selectFirst("img")
            val poster = imgNode?.attr("data-src")?.ifBlank { imgNode.attr("src") } ?: ""
            
            news.add(newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            })
        }
        
        if (news.isNotEmpty()) {
            homePageList.add(HomePageList("آخر الأخبار", news, isHorizontalImages = false))
        }

        return newHomePageResponse(homePageList, false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val app = com.lagradost.cloudstream3.app
        val service = com.cloudstream.shared.service.ProviderHttpServiceHolder.getInstance()
        val doc = service?.getDocument(url) ?: app.get(url).document
        
        val title = doc.selectFirst(".EntryTitle")?.text() ?: "No Title"
        val posterElement = doc.selectFirst("meta[property='og:image']")
        val poster = posterElement?.attr("content") ?: doc.selectFirst(".teamlogo")?.attr("data-src") ?: ""
        
        // Extract plot either from match table or paragraph
        val descBuilder = java.lang.StringBuilder()
        val tableRows = doc.select(".AY-MatchInfo table tr")
        
        if (tableRows.isNotEmpty()) {
            tableRows.forEach { row ->
                val key = row.select("th").text()
                val value = row.select("td").text()
                if (key.isNotBlank() && value.isNotBlank()) {
                    descBuilder.append("$key: $value\n")
                }
            }
        } else {
            descBuilder.append(doc.select(".entry-content p").text())
        }
        
        val isLive = url.contains("/matches/") && tableRows.isNotEmpty()
        val type = if (isLive) TvType.Live else TvType.Movie
        
        val tags = tableRows.mapNotNull { it.select("td").text() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrl(poster)
            this.plot = descBuilder.toString()
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val app = com.lagradost.cloudstream3.app
        val service = com.cloudstream.shared.service.ProviderHttpServiceHolder.getInstance()
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        
        // 1. Fetch exact match/movie page
        val reqHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "https://www.google.com/"
        )
        val doc = service?.getDocument(data, reqHeaders) ?: app.get(data, headers = reqHeaders).document
        
        val iframeElement = doc.selectFirst(".entry-content iframe")
        val iframeSrc = iframeElement?.attr("src")
        
        // 2. Extractor servers list (.video-serv a)
        for (btn in doc.select(".video-serv a")) {
            val href = btn.attr("href")
            if (href.isNotBlank()) {
                val fixedUrl = fixUrl(href)
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
                foundLinks = true
            }
        }
        
        // 3. Main player iframe
        if (iframeSrc.isNullOrBlank()) {
            return foundLinks
        }
        
        val playerUrl = fixUrl(iframeSrc)
        val pHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to data
        )
        val playerResponse = service?.getText(playerUrl, pHeaders) ?: app.get(playerUrl, headers = pHeaders).text
        
        // Check for AlbaPlayerControl base64 packed stream
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
        else if (playerResponse.contains("Clappr.Player")) {
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
