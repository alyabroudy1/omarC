package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.ui.DrmPlayerDialog
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import kotlin.text.Regex
import kotlin.text.contains

class EgyDead : BaseProvider() {

    override val providerName get() = "EgyDead"
    override val baseDomain get() = "egydead.space"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/egydead.json"

    override val mainPage = mainPageOf(
        "/page/movies" to "افلام",
        "/serie" to "مسلسلات",
        "/type/comedy-1" to "كوميدي",
        "/series-category/tv-shows" to "برامج تلفزيونية"
    )

    override fun getParser(): NewBaseParser {
        return EgyDeadParser()
    }

    /**
     * Arabic season names matching reference pattern.
     */
    override fun getSeasonName(seasonNum: Int): String = "الموسم $seasonNum"

    /**
     * Fetch episodes from all seasons.
     * Reference (EgyDead.java load method):
     * 1. Get season list: div.seasons-list ul > li > a (reversed)
     * 2. For each season, fetch its page and parse div.EpsList > li > a
     * 3. Season number = index + 1
     * 4. If no season list, use div.EpsList > li > a from current page (season=0)
     */
    override suspend fun fetchExtraEpisodes(
        doc: org.jsoup.nodes.Document, url: String, data: com.cloudstream.shared.parsing.ParserInterface.ParsedLoadData
    ): List<com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode> {
        val allEpisodes = mutableListOf<com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode>()
        
        // Step 1: Get season links (reversed, matching reference)
        val seasonLinks = doc.select("div.seasons-list ul > li > a").reversed()
        Log.d("EgyDead", "fetchExtraEpisodes: found ${seasonLinks.size} season links")
        
        if (seasonLinks.isNotEmpty()) {
            // Multi-season: fetch each season page
            for ((index, seasonElement) in seasonLinks.withIndex()) {
                val seasonUrl = seasonElement.attr("href")
                if (seasonUrl.isBlank()) continue
                
                val seasonNum = index + 1
                Log.d("EgyDead", "Fetching season $seasonNum: $seasonUrl")
                
                try {
                    val seasonDoc = httpService.getDocument(seasonUrl)
                    if (seasonDoc != null) {
                        val episodeLinks = seasonDoc.select("div.EpsList > li > a")
                        Log.d("EgyDead", "Season $seasonNum: found ${episodeLinks.size} episodes")
                        
                        for (epElement in episodeLinks) {
                            val epUrl = epElement.attr("href").trim()
                            val epTitle = epElement.attr("title").trim().ifBlank { epElement.text().trim() }
                            if (epUrl.isBlank()) continue
                            
                            val epNum = Regex("""(\d+)""").find(epElement.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            
                            allEpisodes.add(
                                com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode(
                                    name = epTitle.ifBlank { "Episode $epNum" },
                                    url = epUrl,
                                    season = seasonNum,
                                    episode = epNum
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("EgyDead", "Error fetching season $seasonNum: ${e.message}")
                }
            }
        } else {
            // Single season / no seasons: use episodes from current page (season=1)
            val episodeLinks = doc.select("div.EpsList > li > a")
            Log.d("EgyDead", "fetchExtraEpisodes: no seasons, found ${episodeLinks.size} episodes on page")
            
            for (epElement in episodeLinks) {
                val epUrl = epElement.attr("href").trim()
                val epTitle = epElement.attr("title").trim().ifBlank { epElement.text().trim() }
                if (epUrl.isBlank()) continue
                
                val epNum = Regex("""(\d+)""").find(epElement.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                allEpisodes.add(
                    com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode(
                        name = epTitle.ifBlank { "Episode $epNum" },
                        url = epUrl,
                        season = 1,
                        episode = epNum
                    )
                )
            }
        }
        
        // If we found episodes from our custom parsing, return them
        // Otherwise fall back to the parser's default episodes
        if (allEpisodes.isNotEmpty()) {
            return allEpisodes
        }
        
        return data.episodes ?: emptyList()
    }

    private fun getLinkType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mp4") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
    }

    /**
     * Trgsfjll handler: fetches iframe URL, extracts M3U8 links from scripts using file:"(https://...)" regex
     * Reference: EgyDead.java Trgsfjll method
     */
    private suspend fun handleTrgsfjll(iframeUrl: String, serverName: String, callback: (ExtractorLink) -> Unit) {
        try {
            Log.d("EgyDead", "Trgsfjll: $iframeUrl")
            val doc = httpService.getDocument(iframeUrl) ?: return
            
            // Extract all script contents
            val scripts = doc.select("script[type='text/javascript']").map { it.html() }
            
            // Look for file:"(https://...)" pattern in scripts
            val filePattern = Regex("""file:"(https://[^"]*)""")
            for (script in scripts) {
                val matches = filePattern.findAll(script)
                for (match in matches) {
                    val videoUrl = match.groupValues[1]
                    Log.d("EgyDead", "Trgsfjll found: $videoUrl")
                    callback(newExtractorLink(serverName, serverName, videoUrl, type = getLinkType(videoUrl)) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("EgyDead", "handleTrgsfjll: ${e.message}")
        }
    }

    private suspend fun handleVidhide(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            Log.d("EgyDead", "handleVidhide: $url")
            val doc = httpService.getDocument(url) ?: return
            
            // Extract script contents
            val scripts = doc.select("script[type='text/javascript']").map { it.html() }
            
            // Find eval-packed script and unpack it
            val evalPattern = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)""")
            var videoScript: String? = null
            for (script in scripts) {
                if (evalPattern.containsMatchIn(script)) {
                    videoScript = script
                    break
                }
            }
            
            if (videoScript != null) {
                val unpacked = try { JsUnpacker(videoScript).unpack() } catch (_: Exception) { null }
                
                if (unpacked != null) {
                    Log.d("EgyDead", "Vidhide unpacked: ${unpacked.take(200)}")
                    val fileMatch = Regex("""file:"(https://[^"]*)"""").find(unpacked)
                    if (fileMatch != null) {
                        val videoUrl = fileMatch.groupValues[1]
                        Log.d("EgyDead", "Vidhide found: $videoUrl")
                        callback(newExtractorLink("Vidhide", "Vidhide", videoUrl, type = getLinkType(videoUrl)) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                        })
                    }
                }
            }
            
            // Also try direct extraction from HTML
            val html = doc.outerHtml()
            val packed = html.substringAfter("eval(function(p,a,c,k,e,d)", "").substringBefore("</script>")
            val directUnpacked = if (packed.isNotBlank()) {
                JsUnpacker("eval(function(p,a,c,k,e,d)$packed").unpack()
            } else null
            
            val videoUrl = directUnpacked?.let {
                Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)
            } ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Vidhide", "Vidhide", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("EgyDead", "handleVidhide: ${e.message}")
        }
    }

    private suspend fun handleDoodstream(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Doodstream", "Doodstream", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("EgyDead", "handleDoodstream: ${e.message}")
        }
    }

    private suspend fun handleStreamtape(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Streamtape", "Streamtape", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("EgyDead", "handleStreamtape: ${e.message}")
        }
    }

    private suspend fun handleVidguard(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val packed = html.substringAfter("eval(function(p,a,c,k,e,d)", "").substringBefore("</script>")
            val unpacked = if (packed.isNotBlank()) {
                JsUnpacker("eval(function(p,a,c,k,e,d)$packed").unpack()
            } else null

            val videoUrl = unpacked?.let {
                Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)
            } ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Vidguard", "Vidguard", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("EgyDead", "handleVidguard: ${e.message}")
        }
    }

    private suspend fun handleUptostream(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Uptostream", "Uptostream", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("EgyDead", "handleUptostream: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "EgyDead"
        try {
            httpService.ensureInitialized()
            
            // Step 1: POST to the data URL with View=1 (matching original reference)
            // The original: Requests.post(data, data = mapOf("View" to "1"))
            val doc = httpService.post(data, mapOf("View" to "1"))
            if (doc == null) {
                Log.e(methodTag, "Failed to POST for server list")
                return super.loadLinks(data, isCasting, subtitleCallback, callback)
            }
            
            Log.d(methodTag, "POST successful, parsing server elements")
            val referer = "https://${httpService.currentDomain}/"
            var foundLinks = false
            
            // Step 2: Process .donwload-servers-list > li (original: element.select("a").attr("href"))
            val downloadElements = doc.select(".donwload-servers-list > li")
            Log.d(methodTag, "Found ${downloadElements.size} download server elements")
            
            for (element in downloadElements) {
                try {
                    val url = element.select("a").attr("href")
                    if (url.isBlank()) continue
                    Log.d(methodTag, "Download server URL: $url")
                    
                    val urlLower = url.lowercase()
                    
                    // Dispatch based on URL content (matching original)
                    if (urlLower.contains("trgsfjll")) {
                        handleTrgsfjll(url, "Trgsfjll", callback)
                    }
                    if (urlLower.contains("vidhide")) {
                        handleVidhide(url, referer, callback)
                    }
                    
                    // Always try loadExtractor as fallback (matching original)
                    try {
                        loadExtractor(url, data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.d(methodTag, "loadExtractor failed for $url: ${e.message}")
                    }
                    foundLinks = true
                } catch (e: Exception) {
                    Log.e(methodTag, "Error processing download element: ${e.message}")
                }
            }
            
            // Step 3: Process ul.serversList > li (original: li.attr("data-link"))
            val serverElements = doc.select("ul.serversList > li")
            Log.d(methodTag, "Found ${serverElements.size} serversList elements")
            
            for (li in serverElements) {
                try {
                    val iframeUrl = li.attr("data-link")
                    if (iframeUrl.isBlank()) continue
                    Log.d(methodTag, "Server iframe URL: $iframeUrl")
                    
                    val urlLower = iframeUrl.lowercase()
                    
                    // Dispatch based on URL content (matching original)
                    if (urlLower.contains("trgsfjll")) {
                        handleTrgsfjll(iframeUrl, "Trgsfjll", callback)
                    }
                    if (urlLower.contains("vidhide")) {
                        handleVidhide(iframeUrl, referer, callback)
                    }
                    
                    // Always try loadExtractor as fallback
                    try {
                        loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.d(methodTag, "loadExtractor failed for $iframeUrl: ${e.message}")
                    }
                    foundLinks = true
                } catch (e: Exception) {
                    Log.e(methodTag, "Error processing server element: ${e.message}")
                }
            }
            
            if (foundLinks) return true
            
            // Debug: log what elements are on the page
            Log.d(methodTag, "DEBUG: No server elements found. UL elements: ${doc.select("ul").map { it.className() }}")
            Log.d(methodTag, "DEBUG: HTML snippet: ${doc.outerHtml().take(500)}")
            
        } catch (e: Exception) {
            Log.e(methodTag, "loadLinks error: ${e.message}")
        }

        // Fallback to base
        return super.loadLinks(data, isCasting, subtitleCallback, callback)
    }
}

