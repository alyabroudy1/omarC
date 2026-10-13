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
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.text.Regex
import kotlin.text.contains

class EgyDead : BaseProvider() {

    override val providerName get() = "EgyDead"
    override val baseDomain get() = "tv9.egydead.live"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/egydead.json"

    override val mainPage = mainPageOf(
        "/page/movies/" to "افلام اجنبي",
        "/category/افلام-عربي/" to "افلام عربي",
        "/type/comedy/" to "كوميديا",
        "/serie/" to "مسلسلات",
        "/category/افلام-كرتون/افلام-كرتون-ديزني-باللهجة-المصرية/" to "افلام كرتون",
        "/series-category/tv-shows/" to "برامج تلفزيونية"
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
                    val seasonDoc = httpService.getDocument(seasonUrl, rewriteDomain = true)
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
            val doc = httpService.getDocument(iframeUrl, rewriteDomain = true) ?: return
            
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
            val doc = httpService.getDocument(url, rewriteDomain = true) ?: return
            
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
            val doc = httpService.getDocument(url, rewriteDomain = true) ?: return
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
            val doc = httpService.getDocument(url, rewriteDomain = true) ?: return
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
            val doc = httpService.getDocument(url, rewriteDomain = true) ?: return
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
            val doc = httpService.getDocument(url, rewriteDomain = true) ?: return
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
        val delivered = AtomicBoolean(false)
        fun countingCallback(link: ExtractorLink) {
            delivered.set(true)
            callback(link)
        }

        try {
            httpService.ensureInitialized()

            // Step 1: POST to the data URL with View=1 to reveal the server list.
            // GET only shows a "watch & download" button + the youtube trailer.
            val doc = httpService.post(data, mapOf("View" to "1"), rewriteDomain = true)
            if (doc == null) {
                Log.e(methodTag, "Failed to POST for server list")
                return super.loadLinks(data, isCasting, subtitleCallback, callback)
            }

            Log.d(methodTag, "POST successful, parsing server elements")
            val referer = "https://${httpService.currentDomain}/"

            // Collect watch + download server targets
            val targets = mutableListOf<String>()
            for (li in doc.select("ul.serversList > li")) {
                li.attr("data-link").trim().takeIf { it.isNotBlank() }?.let { targets.add(it) }
            }
            for (a in doc.select(".donwload-servers-list > li a[href]")) {
                val href = a.attr("href").trim()
                if (href.startsWith("http") || href.startsWith("//")) targets.add(href)
            }
            Log.d(methodTag, "Found ${targets.size} server targets")

            for (rawUrl in targets.distinct()) {
                val url = normalizeUrl(rawUrl)
                if (url.isBlank()) continue
                if (isTrailerOrJunk(url)) {
                    Log.d(methodTag, "Skipping trailer/junk URL: ${url.take(80)}")
                    continue
                }
                try {
                    if (isMegamaxIframe(url)) {
                        // New backend: Inertia-driven mirror player -> resolve real embeds
                        val mirrors = resolveMegamaxMirrors(url, data)
                        Log.d(methodTag, "MegaMax ${url.take(60)} -> ${mirrors.size} mirrors")
                        for ((mirrorUrl, label) in mirrors) {
                            if (isTrailerOrJunk(mirrorUrl)) continue
                            try {
                                Log.d(methodTag, "Trying mirror [$label]: ${mirrorUrl.take(80)}")
                                loadExtractor(mirrorUrl, url, subtitleCallback, ::countingCallback)
                            } catch (e: Exception) {
                                Log.d(methodTag, "loadExtractor failed for $mirrorUrl: ${e.message}")
                            }
                        }
                    } else {
                        // Legacy dispatch based on URL content
                        val urlLower = url.lowercase()
                        if (urlLower.contains("trgsfjll")) {
                            handleTrgsfjll(url, "Trgsfjll", ::countingCallback)
                        }
                        if (urlLower.contains("vidhide")) {
                            handleVidhide(url, referer, ::countingCallback)
                        }
                        if (urlLower.contains("dood")) {
                            handleDoodstream(url, referer, ::countingCallback)
                        }
                        if (urlLower.contains("streamtape")) {
                            handleStreamtape(url, referer, ::countingCallback)
                        }
                        if (urlLower.contains("vidguard")) {
                            handleVidguard(url, referer, ::countingCallback)
                        }
                        if (urlLower.contains("uptostream")) {
                            handleUptostream(url, referer, ::countingCallback)
                        }
                        // Always try loadExtractor as fallback
                        try {
                            loadExtractor(url, data, subtitleCallback, ::countingCallback)
                        } catch (e: Exception) {
                            Log.d(methodTag, "loadExtractor failed for $url: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(methodTag, "Error processing server element: ${e.message}")
                }
            }

            if (delivered.get()) return true
            Log.w(methodTag, "No links delivered from ${targets.size} targets, trying base fallback")

        } catch (e: Exception) {
            Log.e(methodTag, "loadLinks error: ${e.message}")
        }

        // Fallback to base (parser filters trailer/junk iframes)
        return super.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    private fun normalizeUrl(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("//") -> "https:$u"
            else -> u
        }
    }

    private fun isTrailerOrJunk(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    /**
     * MegaMax-style mirror player: https://<mirror-host>/iframe/<hash>
     * (legacy file hosts like vidhide/doodstream go through the old handlers).
     */
    private fun isMegamaxIframe(url: String): Boolean {
        if (!url.contains("/iframe/")) return false
        val lower = url.lowercase()
        // Known legacy embed hosts are handled by their own code paths
        val legacy = listOf("vidhide", "dood", "streamtape", "vidguard", "uptostream", "trgsfjll", "ok.ru", "vk.com", "mail.ru", "mp4upload", "filemoon", "voe", "mixdrop", "streamwish", "uqload", "doodstream")
        if (legacy.any { lower.contains(it) }) return false
        return true
    }

    /**
     * Resolves a MegaMax mirror iframe to per-quality embed mirror links.
     *
     * The player is an Inertia.js app: the iframe HTML only carries a version +
     * video hash, the actual mirrors come from a partial-reload GET
     * (props.streams.data[].mirrors[].link). Same backend as TukTukcima/Animerco.
     *
     * @return list of (embedUrl, qualityLabel)
     */
    private suspend fun resolveMegamaxMirrors(iframeUrl: String, referer: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val reqHeaders = mapOf(
                "User-Agent" to httpService.userAgent,
                "Referer" to referer
            )

            // 1. Initial page: Inertia version + mirror cookies (XSRF)
            val initialRes = httpService.getRaw(iframeUrl, headers = reqHeaders)
            val setCookies = initialRes.headers("Set-Cookie")
            val xsrf = setCookies.find { it.startsWith("XSRF-TOKEN=") }
                ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
                ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null } }
            val cookieHeader = setCookies.joinToString("; ") { it.substringBefore(";") }
            val initialText = try { initialRes.body?.string() ?: "" } catch (_: Exception) { "" } finally {
                try { initialRes.close() } catch (_: Exception) {}
            }

            val version = Regex("\"version\":\"([^\"]+)\"").find(initialText)?.groupValues?.get(1)
            if (version.isNullOrBlank()) {
                Log.w("EgyDead", "MegaMax: no Inertia version in $iframeUrl")
                return emptyList()
            }

            // 2. Partial reload asking only for the streams prop
            val inertiaHeaders = mutableMapOf(
                "X-Inertia" to "true",
                "X-Inertia-Version" to version,
                "X-Inertia-Partial-Component" to "files/mirror/video",
                "X-Inertia-Partial-Data" to "streams",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to iframeUrl,
                "Accept" to "application/json"
            )
            if (!xsrf.isNullOrBlank()) inertiaHeaders["X-XSRF-TOKEN"] = xsrf
            if (cookieHeader.isNotBlank()) inertiaHeaders["Cookie"] = cookieHeader

            val jsonRes = httpService.getRaw(iframeUrl, headers = inertiaHeaders)
            val jsonText = try { jsonRes.body?.string() ?: "" } catch (_: Exception) { "" } finally {
                try { jsonRes.close() } catch (_: Exception) {}
            }
            if (jsonText.isBlank() || !jsonText.trimStart().startsWith("{")) {
                Log.w("EgyDead", "MegaMax: unexpected streams response (${jsonText.take(80)})")
                return emptyList()
            }

            val data = JSONObject(jsonText).optJSONObject("props")
                ?.optJSONObject("streams")?.optJSONArray("data")
            if (data == null) {
                Log.w("EgyDead", "MegaMax: no streams.data in response")
                return emptyList()
            }

            for (i in 0 until data.length()) {
                val quality = data.optJSONObject(i) ?: continue
                val label = quality.optString("label", "")
                val mirrors = quality.optJSONArray("mirrors") ?: continue
                for (j in 0 until mirrors.length()) {
                    val link = mirrors.optJSONObject(j)?.optString("link")?.trim().orEmpty()
                    if (link.isBlank()) continue
                    val finalLink = if (link.startsWith("//")) "https:$link" else link
                    if (!finalLink.startsWith("http")) continue
                    out.add(finalLink to label)
                }
            }
        } catch (e: Exception) {
            Log.e("EgyDead", "resolveMegamaxMirrors failed: ${e.message}")
        }
        return out.distinctBy { it.first }
    }
}

