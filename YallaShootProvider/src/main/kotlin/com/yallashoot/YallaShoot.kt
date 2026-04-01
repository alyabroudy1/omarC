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

    override val baseDomain get() = "shoot-one.com" 
    override var mainUrl = "https://www.shoot-one.com"
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
        val processedUrls = mutableSetOf<String>()
        doc.select("a[href*=/matches/], a[href*=.html], a[href*=/sport], a[href*=/https]").forEach { aTag ->
            val urlRaw = aTag.attr("href")
            val url = urlRaw.let { fixYallaUrl(it) }
            if (url.isBlank() || !processedUrls.add(url)) return@forEach
            
            // Heuristic fallback: Traverse upward from the anchor tag to find the match wrapper holding 2 team logos
            var container = aTag.parent()
            var depth = 0
            while (container != null && container.select("img").size < 2 && depth < 5) {
                container = container.parent()
                depth++
            }
            
            val teams = container?.select("img")
            if (teams != null && teams.size >= 2) {
                val rightTeam = teams[0].attr("alt").trim().ifBlank { "Team 1" }
                val leftTeam = teams[1].attr("alt").trim().ifBlank { "Team 2" }
                
                val containerText = container.text()
                
                // Pure heuristic pattern extraction preventing reliance on heavily obfuscated or rotating CSS class designations
                val statusRegex = Regex("(جارية الان|لم تبدأ|انتهت|شوط|استراحة|تأجلت|جارية الأن)")
                val timeRegex = Regex("(\\d{1,2}:\\d{2}\\s*(?:AM|PM|ص|م)?)", RegexOption.IGNORE_CASE)
                val resultRegex = Regex("(\\d+\\s*-\\s*\\d+)")
                
                val resultText = resultRegex.find(containerText)?.groupValues?.get(1) ?: "VS"
                val status = statusRegex.find(containerText)?.groupValues?.get(1) ?: ""
                val matchTime = timeRegex.find(containerText)?.groupValues?.get(1) ?: ""
                
                val title = buildString {
                    append("$rightTeam $resultText $leftTeam")
                    if (status.isNotBlank() || matchTime.isNotBlank()) {
                        append("\n")
                        if (matchTime.isNotBlank()) append("⏱ $matchTime")
                        if (status.isNotBlank()) {
                            if (matchTime.isNotBlank()) append(" | ")
                            append("📌 $status")
                        }
                    }
                }
                
                Log.d("YallaShoot", "Match explicitly identified using DOM heuristics:")
                Log.d("YallaShoot", "-> Title: $title")
                Log.d("YallaShoot", "-> URL: $url")
                
                val posterRaw = teams[0].attr("data-src").ifBlank { teams[0].attr("src") }
                val poster = fixYallaUrl(posterRaw)
                
                matches.add(newMovieSearchResponse(title, url, TvType.Live) {
                    this.posterUrl = poster
                })
            } else {
                Log.w("YallaShoot", "Failed to resolve 2 image logos resolving match container upwards from link: $urlRaw")
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
        val isMatch = url.contains("yallashoooty") || url.contains("/sport") || url.contains("/https") || url.contains(".html") || url.contains("/matches/")
        
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
        
        val iframeElement = doc.selectFirst(".entry-content iframe, .posts-body iframe, iframe.cf, iframe")
        val iframeSrc = iframeElement?.attr("src")?.trim()
        
        Log.d("YallaShoot", "Extracting from Data URL: $data")
        Log.d("YallaShoot", "Base iframe element HTML: ${iframeElement?.outerHtml()}")
        Log.d("YallaShoot", "Base iframe src: $iframeSrc")
        
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
        Log.d("YallaShoot", "Fetching actual player URL: $playerUrl")
        
        val pHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to data
        )
        val playerResponse = httpService.getText(playerUrl, pHeaders, skipRewrite = true) ?: return foundLinks
        Log.d("YallaShoot", "Fetched base player HTML snippet: ${playerResponse}")
        
        // 1. Process default page directly
        val (foundAlba, foundGeneric) = processMultiIframe(playerResponse, playerUrl, userAgent, subtitleCallback, callback)
        if (foundAlba || foundGeneric) foundLinks = true
        
        // 2. Fetch all other servers from the menu natively
        val playerDoc = org.jsoup.Jsoup.parse(playerResponse, playerUrl)
        val menuLinks = playerDoc.select(".aplr-menu a.aplr-link").mapNotNull { it.attr("href") }.filter { it.isNotBlank() && it != playerUrl }
        
        Log.d("YallaShoot", "Identified concurrent server menu links: $menuLinks")
        
        // Use apmap if possible, or sequential robust loop
        menuLinks.forEach { serverUrl ->
            val fixedServer = fixYallaUrl(serverUrl)
            val serverHtml = httpService.getText(fixedServer, pHeaders, skipRewrite = true)
            if (serverHtml != null) {
                Log.d("YallaShoot", "Fetched secondary server payload HTML from \$fixedServer: \${serverHtml}")
                val (fA, fG) = processMultiIframe(serverHtml, fixedServer, userAgent, subtitleCallback, callback)
                if (fA || fG) foundLinks = true
            }
        }
        
        return foundLinks
    }
    
    private suspend fun processMultiIframe(
        html: String,
        referer: String,
        userAgent: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ): Pair<Boolean, Boolean> {
        if (depth > 2) return Pair(false, false)
        var foundAlba = false
        var foundGeneric = false
        
        // 1. Alba/Clappr Script Check
        val albaRegex = Regex("AlbaPlayerControl\\('([^']+)'")
        val albaMatch = albaRegex.find(html)
        if (albaMatch != null) {
            val encodedString = albaMatch.groupValues[1]
            try {
                val decodedBytes = Base64.decode(encodedString, Base64.DEFAULT)
                val m3u8Url = String(decodedBytes, Charsets.UTF_8)
                val origin = try { "https://${java.net.URI(referer).host}" } catch(e: Exception) { referer }
                
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = m3u8Url,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer,
                        "Origin" to origin,
                        "Accept" to "*/*"
                    )
                )
                
                m3u8Links.forEach { link ->
                    Log.d("YallaShoot", "Extracted Alba M3u8 link: ${link.url}")
                    callback(link)
                    foundAlba = true
                }
            } catch (e: Exception) {
                Log.e("YallaShoot", "Failed to decode AlbaPlayerControl: ${e.message}")
            }
        } else if (html.contains("Clappr.Player") || html.contains(".m3u8")) {
            val clapprRegex = Regex("source\\s*:\\s*[\"']([^\"']+)[\"']")
            val srcVarRegex = Regex("src\\s*=\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']")
            val fallbackRegex = Regex("[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")
            
            val m3u8Url = clapprRegex.find(html)?.groupValues?.get(1) 
                ?: srcVarRegex.find(html)?.groupValues?.get(1)
                ?: fallbackRegex.find(html)?.groupValues?.get(1)
                
            if (m3u8Url != null) {
                val origin = try { "https://${java.net.URI(referer).host}" } catch(e: Exception) { referer }
                
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = m3u8Url,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer,
                        "Origin" to origin,
                        "Accept" to "*/*"
                    )
                )
                
                m3u8Links.forEach { link ->
                    Log.d("YallaShoot", "Extracted Clappr M3u8 link: ${link.url}")
                    callback(link)
                    foundAlba = true
                }
            }
        }
        
        // 2. Generic Iframes embedded inside the server payload (like popcdn.day)
        val innerDoc = org.jsoup.Jsoup.parse(html, referer)
        innerDoc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            // Prevent recursive loop if it extracts an iframe containing its own default embed or matches referer perfectly
            if (src.isNotBlank() && !src.contains("?serv=0") && src != referer) { 
                Log.d("YallaShoot", "Forwarding generic embedded iframe to loadExtractor: $src")
                loadExtractor(src, referer, subtitleCallback, callback)
                foundGeneric = true
                
                // Heavily obfuscated streams often nest player scripts inside multiple generic iframes (e.g., tv7.koora.com)
                // Cloudstream's loadExtractor will FAIL to parse them if there is no built-in Extractor plugin for that domain.
                // We recursively fetch them here to manually intercept AlbaPlayer/Clappr payloads natively!
                try {
                    val pHeaders = mapOf("Referer" to referer, "User-Agent" to userAgent)
                    val innerHtml = httpService.getText(src, pHeaders, skipRewrite = true)
                    if (innerHtml != null) {
                        Log.d("YallaShoot", "Nested iframe successfully fetched from $src, recursively attempting manual extraction...")
                        val (nestedAlba, nestedGeneric) = processMultiIframe(innerHtml, src, userAgent, subtitleCallback, callback, depth + 1)
                        if (nestedAlba || nestedGeneric) foundGeneric = true
                    }
                } catch (e: Exception) {
                    Log.w("YallaShoot", "Failed to fetch nested generic iframe payload: ${e.message}")
                }
            }
        }
        
        return Pair(foundAlba, foundGeneric)
    }
}
