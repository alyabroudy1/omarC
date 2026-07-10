package com.yallashoot

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import org.jsoup.nodes.Document
import com.cloudstream.shared.extractors.AlbaPlayerExtractor

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
        val doc = httpService.getDocument(mainUrl, rewriteDomain = true) ?: return null
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
        val html = httpService.getText(url, rewriteDomain = false) ?: return emptyList()
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
            val html = httpService.getText(url, rewriteDomain = false) ?: return null
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
        val html = httpService.getText(data, rewriteDomain = false) ?: return false
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
        Log.d("YallaShoot", "Delegating to AlbaPlayerExtractor: $playerUrl")

        // Delegate to AlbaPlayerExtractor (handles hex deobfuscation, 
        // multi-server menu, Clappr/AlbaPlayerControl extraction, quality sorting)
        // Works regardless of domain because we use the extractor directly
        try {
            AlbaPlayerExtractor().getUrl(playerUrl, data, subtitleCallback, callback)
            foundLinks = true
        } catch (e: Exception) {
            Log.e("YallaShoot", "AlbaPlayerExtractor failed: ${e.message}")
            // Fallback to sniffer
            if (awaitSnifferResult(playerUrl, data, subtitleCallback, callback, 15000L)) {
                foundLinks = true
            }
        }

        return foundLinks
    }
}
