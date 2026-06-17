package com.cimaleek

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class CimaLeek : BaseProvider() {

    override val providerName get() = "CimaLeek"
    override val baseDomain get() = "cimaleek.pw"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/cimaleek.json"

    override val mainPage = mainPageOf(
        "/b5/" to "الرئيسية",
        "/category/aflam-online-1/" to "أفلام أجنبية",
        "/category/netflix-movies-1/" to "أفلام نتفليكس",
        "/category/cartoon-movies/" to "أفلام كرتون",
        "/category/anime-movies/" to "أفلام أنمي",
        "/category/english-series-1/" to "مسلسلات أجنبية",
        "/category/netflix-series/" to "مسلسلات نتفليكس",
        "/category/anime-series/" to "مسلسلات أنمي"
    )

    override fun getParser(): NewBaseParser {
        return CimaLeekParser()
    }

    override fun getSeasonName(seasonNum: Int): String = "الموسم $seasonNum"

    override suspend fun fetchExtraEpisodes(
        doc: org.jsoup.nodes.Document,
        url: String,
        data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        val methodTag = "[CimaLeek] [fetchExtraEpisodes]"
        
        // 1. Check if there are other seasons links
        val seasonLinks = doc.select(".seasonse ul.seas-list li.sealist a")
        Log.d(methodTag, "Found ${seasonLinks.size} season links")
        
        if (seasonLinks.isNotEmpty()) {
            for (seasonEl in seasonLinks) {
                val seasonUrl = seasonEl.attr("href")
                if (seasonUrl.isBlank()) continue
                
                val seasonNum = Regex("""الموسم\s+(\d+)""").find(seasonEl.attr("title"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(seasonEl.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1
                    
                Log.d(methodTag, "Fetching season $seasonNum URL: $seasonUrl")
                try {
                    val seasonDoc = httpService.getDocument(seasonUrl)
                    if (seasonDoc != null) {
                        val epLinks = seasonDoc.select("ul.episodios li.episodesList a")
                        Log.d(methodTag, "Season $seasonNum: found ${epLinks.size} episodes")
                        for (epEl in epLinks) {
                            val epUrl = epEl.attr("href")
                            val epTitle = epEl.attr("title").ifBlank { epEl.text() }
                            if (epUrl.isBlank()) continue
                            
                            val epNum = Regex("""الحلقة\s*\((\d+)\)""").find(epEl.text())?.groupValues?.get(1)?.toIntOrNull()
                                ?: Regex("""(\d+)""").find(epEl.text())?.groupValues?.get(1)?.toIntOrNull()
                                ?: 0
                                
                            episodes.add(
                                ParserInterface.ParsedEpisode(
                                    name = epTitle,
                                    url = epUrl,
                                    season = seasonNum,
                                    episode = epNum
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(methodTag, "Error fetching season $seasonNum episodes: ${e.message}")
                }
            }
        } else {
            // Single season, parse episodes from current doc
            val epLinks = doc.select("ul.episodios li.episodesList a")
            Log.d(methodTag, "Single season: found ${epLinks.size} episodes")
            for (epEl in epLinks) {
                val epUrl = epEl.attr("href")
                val epTitle = epEl.attr("title").ifBlank { epEl.text() }
                if (epUrl.isBlank()) continue
                
                val epNum = Regex("""الحلقة\s*\((\d+)\)""").find(epEl.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(epEl.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
                    
                episodes.add(
                    ParserInterface.ParsedEpisode(
                        name = epTitle,
                        url = epUrl,
                        season = 1,
                        episode = epNum
                    )
                )
            }
        }
        
        return if (episodes.isNotEmpty()) episodes else data.episodes ?: emptyList()
    }

    private fun generateRandomString(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun jsSlice(str: String, start: Int, end: Int?): String {
        val len = str.length
        var actualStart = if (start < 0) len + start else start
        if (actualStart < 0) actualStart = 0
        if (actualStart > len) actualStart = len

        val actualEnd = if (end == null) {
            len
        } else {
            var e = if (end < 0) len + end else end
            if (e < 0) e = 0
            if (e > len) e = len
            e
        }

        if (actualStart >= actualEnd) return ""
        return str.substring(actualStart, actualEnd)
    }

    private fun mdTq(a: String, b: List<List<Int>>, pathLength: Int): String {
        var kwDr = a
        for (i in b.indices.reversed()) {
            val range = b[i]
            if (range.size < 2) continue
            val startVal = range[0]
            val cqrr = range[1]
            val start = startVal - pathLength
            
            val sliced1 = jsSlice(kwDr, 0, start)
            val sliced2 = jsSlice(kwDr, cqrr, null)
            kwDr = sliced1 + sliced2
        }
        return kwDr
    }

    private fun decryptIOns(quzs: String, kQqs: String): String {
        val decodedBytes = android.util.Base64.decode(quzs, android.util.Base64.DEFAULT)
        val kopp = String(decodedBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
        
        val gNks = "9b09102b216d23cbb6cf75b47c82961c"
        val result = java.lang.StringBuilder()
        for (i in 0 until kopp.length) {
            val gljp = kopp[i].code
            val immp = gNks[i % 32].code
            val cidp = kQqs[i % kQqs.length].code
            val decryptedChar = gljp xor immp xor cidp
            result.append(decryptedChar.toChar())
        }
        return result.toString()
    }

    /// Handles cswru/vid872 wrapper pages: fetches the page, extracts the iframe,
    /// resolves it, and emits the first link. Returns null if no link found.
    private suspend fun handleCswruWrapper(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): ExtractorLink? {
        val methodTag = "[CimaLeek] [CswruWrapper]"
        val collectedLinks = mutableListOf<ExtractorLink>()
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
                "Referer" to referer
            )
            val html = httpService.getText(url, headers) ?: return null
            val doc = Jsoup.parse(html, url)

            // Find iframe
            val iframeSrc = doc.selectFirst("iframe#embedr")?.attr("src")
                ?: doc.selectFirst("iframe")?.attr("src")
            if (iframeSrc.isNullOrBlank()) {
                Log.w(methodTag, "No iframe found in wrapper page")
                // Fallback: sniffer
                awaitSnifferResult(url, referer, subtitleCallback, callback, 15000L)
                return null
            }

            val absoluteIframeSrc = if (iframeSrc.startsWith("http")) iframeSrc else
                if (iframeSrc.startsWith("//")) "https:$iframeSrc" else
                    "${java.net.URI(url).scheme}://${java.net.URI(url).host}$iframeSrc"

            Log.d(methodTag, "Found iframe: $absoluteIframeSrc")

            // Try loadExtractor on the iframe URL
            loadExtractor(absoluteIframeSrc, url, subtitleCallback) { link ->
                collectedLinks.add(link)
                callback(link)
            }

            if (collectedLinks.isEmpty()) {
                // Sniffer fallback on iframe
                awaitSnifferResult(absoluteIframeSrc, url, subtitleCallback, callback, 15000L)
            }
        } catch (e: Exception) {
            Log.w(methodTag, "Error handling cswru wrapper: ${e.message}")
            // Fallback: try sniffer on the original URL
            awaitSnifferResult(url, referer, subtitleCallback, callback, 15000L)
        }
        return collectedLinks.firstOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "[CimaLeek] [loadLinks]"
        Log.i(methodTag, "START data='$data'")
        
        httpService.ensureInitialized()
        val userAgent = httpService.userAgent
        
        // Fetch the watch page directly by appending /watch/ if needed
        val watchUrl = if (data.endsWith("/watch/")) data else {
            if (data.endsWith("/")) "${data}watch/" else "$data/watch/"
        }
        
        Log.d(methodTag, "Fetching watch URL: $watchUrl")
        val html = httpService.getText(watchUrl, skipRewrite = true)
        if (html.isNullOrBlank()) {
            Log.e(methodTag, "Empty watch page HTML")
            return false
        }
        
        val doc = Jsoup.parse(html, watchUrl)
        
        // Parse ver and post_id from the html
        val ver = Regex(""""ver"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        val postId = Regex(""""post_id"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1) 
            ?: Regex(""""post_id"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
            
        Log.d(methodTag, "Parsed ver='$ver', postId='$postId'")
        
        val serverElements = doc.select(".lalaplay_player_option")
        Log.d(methodTag, "Found ${serverElements.size} server options")
        
        if (serverElements.isEmpty()) return false
        
        val path = java.net.URL(watchUrl).path.trim('/')
        val trimmedPath = if (path.endsWith("watch")) path.substringBeforeLast("watch").trim('/') else path
        val pathLength = trimmedPath.length
        Log.d(methodTag, "Trimmed path: $trimmedPath, pathLength: $pathLength")
        
        val globalLinksCount = java.util.concurrent.atomic.AtomicInteger(0)
        
        // Process servers in parallel
        coroutineScope {
            serverElements.map { server ->
                async {
                    try {
                        val dataType = server.attr("data-type")
                        val dataPost = server.attr("data-post").ifBlank { postId }
                        val dataNume = server.attr("data-nume")
                        val serverName = server.text().trim()
                        
                        if (dataType.isBlank() || dataPost.isBlank() || dataNume.isBlank()) return@async
                        
                        val rand = generateRandomString(16)
                        val apiUrl = "$mainUrl/wp-json/lalaplayer/v2/?p=$dataPost&t=$dataType&n=$dataNume&ver=$ver&rand=$rand"
                        
                        Log.d(methodTag, "Fetching API for $serverName: $apiUrl")
                        val headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to watchUrl,
                            "X-Requested-With" to "com.android.browser"
                        )
                        
                        val apiResponseStr = httpService.getText(apiUrl, headers, skipRewrite = true)
                        if (!apiResponseStr.isNullOrBlank()) {
                            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                            val node = mapper.readTree(apiResponseStr)
                            
                            val a = node.get("a")?.asText() ?: ""
                            val bNode = node.get("b")
                            val c = node.get("c")?.asText() ?: ""
                            
                            if (a.isNotBlank()) {
                                val bList = mutableListOf<List<Int>>()
                                if (bNode != null && bNode.isArray) {
                                    for (rangeNode in bNode) {
                                         if (rangeNode.isArray && rangeNode.size() >= 2) {
                                             bList.add(listOf(rangeNode.get(0).asInt(), rangeNode.get(1).asInt()))
                                         }
                                    }
                                }
                                
                                // Decrypt
                                val cleaned = mdTq(a, bList, pathLength)
                                val decryptedUrl = decryptIOns(cleaned, c)
                                
                                Log.d(methodTag, "Decrypted URL for $serverName: $decryptedUrl")
                                if (decryptedUrl.startsWith("http")) {
                                    var serverLinksCount = 0
                                    val countingCallback: (ExtractorLink) -> Unit = { link ->
                                        serverLinksCount++
                                        globalLinksCount.incrementAndGet()
                                        callback(link)
                                    }

                                    // Handle cswru/vid872 wrapper pages inline (no dynamic extractor registration needed)
                                    if (decryptedUrl.contains("cswru") || decryptedUrl.contains("vid872")) {
                                        handleCswruWrapper(decryptedUrl, watchUrl, subtitleCallback, countingCallback)
                                    } else {
                                        loadExtractor(decryptedUrl, watchUrl, subtitleCallback, countingCallback)
                                    }

                                    if (serverLinksCount == 0) {
                                         val isWebpage = decryptedUrl.contains(".html") ||
                                                         decryptedUrl.contains("/e/") ||
                                                         decryptedUrl.contains("/e2/") ||
                                                         decryptedUrl.contains("/e3/") ||
                                                         decryptedUrl.contains("/e4/")
                                         if (isWebpage) {
                                             Log.d(methodTag, "loadExtractor failed on webpage, running sniffer: $decryptedUrl")
                                             if (awaitSnifferResult(decryptedUrl, watchUrl, subtitleCallback, countingCallback, 15000L)) {
                                             }
                                         } else {
                                             val type = if (decryptedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                             countingCallback(
                                                 newExtractorLink(
                                                     source = serverName,
                                                     name = serverName,
                                                     url = decryptedUrl,
                                                     type = type
                                                 ) {
                                                     this.referer = watchUrl
                                                     this.quality = Qualities.Unknown.value
                                                     this.headers = mapOf("User-Agent" to userAgent, "Referer" to watchUrl)
                                                 }
                                             )
                                         }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(methodTag, "Error loading link for server: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        
        return globalLinksCount.get() > 0
    }
}
