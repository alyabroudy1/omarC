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
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class YallaShoot : BaseProvider() {

    override val baseDomain get() = "yalla-team.com" 
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
            if (uri.isAbsolute && uri.host?.contains("yalla") == false && uri.host?.contains("shoot") == false) {
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
        
        // Find all elements containing exactly 2 image tags in their subtree, and containing at least 1 a tag
        val allElements = doc.getAllElements()
        val candidates = allElements.filter { element ->
            val imgs = element.select("img")
            if (imgs.size != 2) return@filter false
            
            // Check if it has an anchor tag
            val hasLink = element.select("a").isNotEmpty()
            if (!hasLink) return@filter false
            
            // Heuristic check: does the text contain score, time, or match-related keywords?
            val text = element.text()
            val hasScore = text.contains("-") || text.contains("VS") || text.contains("vs")
            val hasTime = text.contains(":")
            val hasStatus = text.contains("تبدأ") || text.contains("جارية") || text.contains("انتهت") || text.contains("بث") || text.contains("مباشر") || text.contains("شوط") || text.contains("استراحة")
            
            hasScore || hasTime || hasStatus
        }
        
        // Filter out candidates that are parents of other candidates
        val matchContainers = candidates.filter { parent ->
            candidates.none { child ->
                child != parent && parent.getAllElements().contains(child)
            }
        }
        
        matchContainers.forEach { container ->
            val aTag = container.selectFirst("a") ?: return@forEach
            val urlRaw = aTag.attr("href")
            
            val teams = container.select("img")
            if (teams.size >= 2) {
                val rightTeam = teams[0].attr("alt").trim().ifBlank { 
                    teams[0].attr("title").trim().ifBlank { "Team 1" } 
                }
                val leftTeam = teams[1].attr("alt").trim().ifBlank { 
                    teams[1].attr("title").trim().ifBlank { "Team 2" } 
                }
                
                val containerText = container.text()
                
                val statusRegex = Regex("(جارية الان|لم تبدأ|انتهت|شوط|استراحة|تأجلت|مباشر|جارية الأن|بث مباشر)")
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
                
                val posterRaw = teams[0].attr("data-src").ifBlank { teams[0].attr("src") }
                val poster = fixYallaUrl(posterRaw)
                
                val url = if (urlRaw.isBlank() || urlRaw == "/") {
                    val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
                    val encodedPoster = java.net.URLEncoder.encode(poster, "UTF-8")
                    "$mainUrl/matches/dummy?title=$encodedTitle&poster=$encodedPoster"
                } else {
                    fixYallaUrl(urlRaw)
                }
                
                if (url.isBlank() || !processedUrls.add(url)) return@forEach
                
                Log.d("YallaShoot", "Match identified dynamically:")
                Log.d("YallaShoot", "-> Title: $title")
                Log.d("YallaShoot", "-> URL: $url")
                
                matches.add(newMovieSearchResponse(title, url, TvType.Live) {
                    this.posterUrl = poster
                })
            }
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

    override suspend fun search(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val html = httpService.getText(url, skipRewrite = true) ?: return emptyList()
        val doc = org.jsoup.Jsoup.parse(html, url)
        
        val allMatches = parseMatchesFromDocument(doc)
        return allMatches.filter { match ->
            match.name.contains(query, ignoreCase = true)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/matches/dummy")) {
            val title = try {
                url.substringAfter("title=").substringBefore("&").let { java.net.URLDecoder.decode(it, "UTF-8") }
            } catch (e: Exception) {
                "YallaShoot Match"
            }
            val poster = try {
                url.substringAfter("poster=").substringBefore("&").let { java.net.URLDecoder.decode(it, "UTF-8") }
            } catch (e: Exception) {
                ""
            }
            
            return newLiveStreamLoadResponse(title, url, url) {
                this.posterUrl = poster
                this.plot = "لم تبدأ المباراة بعد. يرجى العودة لاحقاً عند بدء البث المباشر."
            }
        }
        
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
        if (data.contains("/matches/dummy")) {
            Log.d("YallaShoot", "Matches dummy link clicked, stream is not active yet.")
            return false
        }
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
        
        if (!foundLinks) {
            val sniffUrls = mutableListOf<String>()
            if (playerUrl.isNotBlank()) sniffUrls.add(playerUrl)
            sniffUrls.addAll(menuLinks.map { fixYallaUrl(it) })
            
            for (sniffUrl in sniffUrls) {
                Log.d("YallaShoot", "Executing Sniffer fallback on: \$sniffUrl")
                if (awaitSnifferResult(sniffUrl, data, subtitleCallback, callback, 15000L)) {
                    foundLinks = true
                    break
                }
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
            Log.d("YallaShoot", "Clappr or .m3u8 text identified in payload! Extracting...")
            val clapprRegex = Regex("source\\s*:\\s*[\"']([^\"']+)[\"']")
            val srcVarRegex = Regex("src\\s*=\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']")
            val fallbackRegex = Regex("[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")
            
            val m3u8Url = clapprRegex.find(html)?.groupValues?.get(1) 
                ?: srcVarRegex.find(html)?.groupValues?.get(1)
                ?: fallbackRegex.find(html)?.groupValues?.get(1)
            
            Log.d("YallaShoot", "Regex matched Master M3U8 URL: \$m3u8Url")
                
            if (m3u8Url != null) {
                val origin = try { "https://\${java.net.URI(referer).host}" } catch(e: Exception) { referer }
                val headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to referer,
                    "Origin" to origin,
                    "Accept" to "*/*"
                )
                
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = m3u8Url,
                    referer = referer,
                    headers = headers
                )
                
                Log.d("YallaShoot", "M3u8Helper returned \${m3u8Links.size} links.")
                
                if (m3u8Links.isEmpty()) {
                    Log.w("YallaShoot", "M3u8Helper failed to parse playlist (possible chunklist)! Emitting raw fallback ExtractorLink.")
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.headers = headers
                            this.quality = 1
                        }
                    )
                    foundAlba = true
                } else {
                    m3u8Links.forEach { link ->
                        Log.d("YallaShoot", "Extracted Clappr M3u8 link: \${link.url}")
                        callback(link)
                        foundAlba = true
                    }
                }
            } else {
                Log.e("YallaShoot", "Failed to extract M3u8 string from regexes!")
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
