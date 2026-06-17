package com.cimaleek

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

    /**
     * Handles cswru/vid872 wrapper pages by fetching the raw HTML (bypassing CS3's
     * URL rewriting), extracting the iframe or AJAX redirect, then calling
     * loadExtractor / sniffer on the resolved URL.
     *
     * This mirrors the logic from [CswruExtractor] but is inlined here because
     * the runtime CS3 does not expose extractorApis for dynamic registration.
     *
     * Logs intermediate states for live debugging.
     */
    private suspend fun handleCswruWrapper(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val methodTag = "[CimaLeek] [CswruWrapper]"
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
                "Referer" to referer
            )

            Log.d(methodTag, "Fetching wrapper page: ${url.take(100)}")

            // Use app.get() to bypass CS3's rewriteUrlIfNeeded which would change
            // cswru.vid872.top -> m.cimaleek.pw and return wrong content
            val response = app.get(url, headers = headers)
            val html = response.text

            if (html.isBlank()) {
                Log.w(methodTag, "Empty response from wrapper page")
                return
            }

            val doc = Jsoup.parse(html, url)

            // 1) Try to find the iframe source (same selectors as CswruExtractor)
            val iframeSrc = doc.selectFirst("iframe#embedr")?.let {
                val src = it.attr("src")
                if (src.startsWith("http")) src else it.absUrl("src")
            } ?: doc.selectFirst("iframe")?.let {
                val src = it.attr("src")
                if (src.startsWith("http")) src else it.absUrl("src")
            }

            if (!iframeSrc.isNullOrBlank()) {
                Log.d(methodTag, "Found iframe: $iframeSrc")

                var innerLinksCount = 0
                val countingCallback: (ExtractorLink) -> Unit = { link ->
                    innerLinksCount++
                    callback(link)
                }

                // Let standard extractors handle the iframe URL (e.g. Mixdrop, Doodstream)
                loadExtractor(iframeSrc, url, subtitleCallback, countingCallback)

                if (innerLinksCount == 0) {
                    Log.d(methodTag, "loadExtractor on iframe produced 0 links, sniffer fallback: ${iframeSrc.take(80)}")
                    awaitSnifferResult(iframeSrc, url, subtitleCallback, callback, 15000L)
                }
                return
            }

            // 2) Check for AJAX redirect data block
            val linkRegex = Regex(""""link"\s*:\s*"([^"]+)"""")
            val redirectLink = linkRegex.find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (!redirectLink.isNullOrBlank()) {
                val absoluteRedirectUrl = if (redirectLink.startsWith("http")) {
                    redirectLink
                } else {
                    val uri = java.net.URI(url)
                    "${uri.scheme}://${uri.host}$redirectLink"
                }
                Log.d(methodTag, "Found AJAX redirect: $absoluteRedirectUrl")

                var innerLinksCount = 0
                val countingCallback: (ExtractorLink) -> Unit = { link ->
                    innerLinksCount++
                    callback(link)
                }

                loadExtractor(absoluteRedirectUrl, url, subtitleCallback, countingCallback)

                if (innerLinksCount == 0) {
                    Log.d(methodTag, "loadExtractor on redirect produced 0 links, sniffer fallback: ${absoluteRedirectUrl.take(80)}")
                    awaitSnifferResult(absoluteRedirectUrl, url, subtitleCallback, callback, 15000L)
                }
                return
            }

            Log.w(methodTag, "No iframe or redirect found in wrapper page (${html.length} chars)")
        } catch (e: Exception) {
            Log.w(methodTag, "Error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 4-phase loadLinks (mirrors Laroza / shared BaseProvider pattern):
    //
    //   Phase 1 – Resolve (decrypt) all server URLs in parallel
    //   Phase 2 – Try standard extractors on EVERY resolved URL in parallel
    //   Phase 3 – Deliver all found links from Phase 2
    //   Phase 4 – Sniffer fallback: try servers ONE-BY-ONE sequentially
    //
    // Logging is verbose so each phase can be traced in logcat.
    // ──────────────────────────────────────────────────────────────────────────────
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

        // ====================================================================
        // PHASE 0 – Fetch watch page and parse metadata (ver, postId, servers)
        // ====================================================================
        Log.i(methodTag, "PHASE 0: Fetching watch page...")
        val watchUrl = if (data.endsWith("/watch/")) data else {
            if (data.endsWith("/")) "${data}watch/" else "$data/watch/"
        }
        val html = httpService.getText(watchUrl, skipRewrite = true)
        if (html.isNullOrBlank()) {
            Log.e(methodTag, "PHASE 0: Empty watch page HTML")
            return false
        }

        val doc = Jsoup.parse(html, watchUrl)
        val ver = Regex(""""ver"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        val postId = Regex(""""post_id"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex(""""post_id"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        Log.d(methodTag, "PHASE 0: ver='$ver', postId='$postId'")

        val serverElements = doc.select(".lalaplay_player_option")
        Log.d(methodTag, "PHASE 0: Found ${serverElements.size} server options")
        if (serverElements.isEmpty()) return false

        val path = java.net.URL(watchUrl).path.trim('/')
        val trimmedPath = if (path.endsWith("watch")) path.substringBeforeLast("watch").trim('/') else path
        val pathLength = trimmedPath.length
        Log.d(methodTag, "PHASE 0: path='$trimmedPath' length=$pathLength")

        // ====================================================================
        // PHASE 1 – Call API + decrypt for every server (all in parallel)
        // ====================================================================
        Log.i(methodTag, "PHASE 1: Resolving ${serverElements.size} servers...")

        data class Resolved(val name: String, val url: String, val idx: Int)

        val resolved: List<Resolved> = coroutineScope {
            serverElements.mapIndexed { idx, server ->
                async {
                    try {
                        val dataType = server.attr("data-type")
                        val dataPost = server.attr("data-post").ifBlank { postId }
                        val dataNume = server.attr("data-nume")
                        val serverName = server.text().trim()
                        if (dataType.isBlank() || dataPost.isBlank() || dataNume.isBlank()) return@async null

                        val rand = generateRandomString(16)
                        val apiUrl = "$mainUrl/wp-json/lalaplayer/v2/?p=$dataPost&t=$dataType&n=$dataNume&ver=$ver&rand=$rand"
                        val headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to watchUrl,
                            "X-Requested-With" to "com.android.browser"
                        )

                        val json = httpService.getText(apiUrl, headers, skipRewrite = true)
                        if (json.isNullOrBlank()) {
                            Log.w(methodTag, "PHASE 1: [$idx] $serverName — empty API response")
                            return@async null
                        }

                        val node = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(json)
                        val a = node.get("a")?.asText() ?: ""
                        val bNode = node.get("b")
                        val c = node.get("c")?.asText() ?: ""
                        if (a.isBlank()) {
                            Log.w(methodTag, "PHASE 1: [$idx] $serverName — missing 'a'")
                            return@async null
                        }

                        val bList = mutableListOf<List<Int>>()
                        if (bNode != null && bNode.isArray) {
                            for (r in bNode) {
                                if (r.isArray && r.size() >= 2)
                                    bList.add(listOf(r.get(0).asInt(), r.get(1).asInt()))
                            }
                        }

                        val cleaned = mdTq(a, bList, pathLength)
                        val decrypted = decryptIOns(cleaned, c)
                        if (!decrypted.startsWith("http")) {
                            Log.w(methodTag, "PHASE 1: [$idx] $serverName — decrypt did not produce http URL: $decrypted")
                            return@async null
                        }

                        Log.d(methodTag, "PHASE 1: [$idx] $serverName -> ${decrypted.take(80)}")
                        Resolved(serverName, decrypted, idx)
                    } catch (e: Exception) {
                        Log.e(methodTag, "PHASE 1: [$idx] error: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        Log.i(methodTag, "PHASE 1 done: ${resolved.size}/${serverElements.size} resolved")
        if (resolved.isEmpty()) return false

        // ====================================================================
        // PHASE 2 – Try standard extractors on ALL resolved URLs IN PARALLEL
        // ====================================================================
        Log.i(methodTag, "PHASE 2: Processing ${resolved.size} URLs via extractors...")

        data class Phase2Result(val name: String, val idx: Int, val links: List<ExtractorLink>)

        val phase2: List<Phase2Result> = coroutineScope {
            resolved.map { (serverName, decryptedUrl, idx) ->
                async {
                    val collected = mutableListOf<ExtractorLink>()
                    val collectCb: (ExtractorLink) -> Unit = { collected.add(it) }

                    Log.d(methodTag, "PHASE 2: [$idx] $serverName — ${decryptedUrl.take(80)}")

                    if (decryptedUrl.contains("cswru") || decryptedUrl.contains("vid872")) {
                        // Inline CswruExtractor logic (bypasses URL rewriting)
                        handleCswruWrapper(decryptedUrl, watchUrl, subtitleCallback, collectCb)
                    } else {
                        loadExtractor(decryptedUrl, watchUrl, subtitleCallback, collectCb)
                    }

                    Phase2Result(serverName, idx, collected.toList())
                }
            }.awaitAll()
        }

        // ====================================================================
        // PHASE 3 – Deliver ALL links found in Phase 2
        // ====================================================================
        val allLinks = phase2.flatMap { it.links }
        val successServers = phase2.filter { it.links.isNotEmpty() }

        if (successServers.isNotEmpty()) {
            Log.i(methodTag, "PHASE 3: ${successServers.size} servers produced ${allLinks.size} link(s)")
            val unique = allLinks.distinctBy { it.url }
            unique.forEach { link ->
                Log.d(methodTag, "PHASE 3: → ${link.name} | ${link.url.take(80)}")
                callback(link)
            }
            Log.i(methodTag, "PHASE 3 done: delivered ${unique.size} unique link(s)")
            return true
        }

        Log.w(methodTag, "PHASE 2-3: no extractor matched any URL")

        // ====================================================================
        // PHASE 4 – Sniffer fallback: ONE server at a time (sequential)
        // ====================================================================
        Log.i(methodTag, "PHASE 4: Sniffer fallback — ${resolved.size} server(s) sequentially...")

        for ((serverName, decryptedUrl, idx) in resolved) {
            Log.d(methodTag, "PHASE 4: Sniffing [$idx] $serverName — ${decryptedUrl.take(80)}")
            val ok = awaitSnifferResult(decryptedUrl, watchUrl, subtitleCallback, callback, 15000L)
            if (ok) {
                Log.i(methodTag, "PHASE 4: SUCCESS [$idx] $serverName")
                return true
            }
            Log.w(methodTag, "PHASE 4: FAILED [$idx] $serverName")
        }

        Log.w(methodTag, "END — all ${resolved.size} URLs exhausted, no video found")
        return false
    }
}
