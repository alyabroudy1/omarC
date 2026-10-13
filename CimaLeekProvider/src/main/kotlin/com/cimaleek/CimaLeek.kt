package com.cimaleek

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
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
                    val seasonDoc = httpService.getDocument(seasonUrl, rewriteDomain = true)
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

    // ====================================================================
    // DECRYPTION STRATEGIES ARCHITECTURE
    // ====================================================================

    /**
     * Contextual metadata extracted from the watch page needed for server decryption.
     */
    data class DecryptionMetadata(
        val watchUrl: String,
        val postLink: String,
        val postId: String,
        val ver: String
    ) {
        val postLinkPathLength: Int by lazy {
            val link = postLink.ifBlank { watchUrl }
            val path = try { java.net.URI(link).path?.trim('/') ?: "" } catch (_: Exception) { "" }
            val trimmed = if (path.endsWith("watch")) path.substringBeforeLast("watch").trim('/') else path
            trimmed.length
        }

        val watchPathLength: Int by lazy {
            val path = try { java.net.URI(watchUrl).path?.trim('/') ?: "" } catch (_: Exception) { "" }
            val trimmed = if (path.endsWith("watch")) path.substringBeforeLast("watch").trim('/') else path
            trimmed.length
        }
    }

    /**
     * Interface defining a server payload decryption strategy.
     * Encapsulates key derivation, XOR sequence, and slicing offset logic.
     * Allows clean fallback chains and future scalability when the site rotates keys/algorithms.
     */
    interface DecryptionStrategy {
        val name: String
        fun decrypt(
            payloadA: String,
            slicesB: List<List<Int>>,
            keyC: String,
            metadata: DecryptionMetadata
        ): String?
    }

    /**
     * Primary Strategy (V2 - 2026 update):
     * Uses the updated XOR key derived from ajax-5.js and post_link pathLength slicing.
     */
    private class V2DecryptionStrategy : DecryptionStrategy {
        override val name = "V2_CURRENT"
        private val xorKey = "121af524a017cb96675243aa34cde44e"

        override fun decrypt(
            payloadA: String,
            slicesB: List<List<Int>>,
            keyC: String,
            metadata: DecryptionMetadata
        ): String? {
            val candidateLengths = listOf(metadata.postLinkPathLength, metadata.watchPathLength).distinct()
            for (len in candidateLengths) {
                try {
                    val cleaned = mdTq(payloadA, slicesB, len)
                    val result = xorDecrypt(cleaned, keyC, xorKey)
                    if (result.startsWith("http")) return result
                } catch (_: Exception) {}
            }
            return null
        }
    }

    /**
     * Fallback Strategy (V1 - Legacy):
     * Retains the original XOR key for movies/posts cached under the previous site bundle.
     */
    private class V1LegacyDecryptionStrategy : DecryptionStrategy {
        override val name = "V1_LEGACY"
        private val xorKey = "9b09102b216d23cbb6cf75b47c82961c"

        override fun decrypt(
            payloadA: String,
            slicesB: List<List<Int>>,
            keyC: String,
            metadata: DecryptionMetadata
        ): String? {
            val candidateLengths = listOf(metadata.watchPathLength, metadata.postLinkPathLength, 0).distinct()
            for (len in candidateLengths) {
                try {
                    val cleaned = mdTq(payloadA, slicesB, len)
                    val result = xorDecrypt(cleaned, keyC, xorKey)
                    if (result.startsWith("http")) return result
                } catch (_: Exception) {}
            }
            return null
        }
    }

    companion object {
        private val decryptionStrategies: List<DecryptionStrategy> = listOf(
            V2DecryptionStrategy(),
            V1LegacyDecryptionStrategy()
        )

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

        private fun xorDecrypt(quzs: String, kQqs: String, key: String): String {
            val decodedBytes = android.util.Base64.decode(quzs, android.util.Base64.DEFAULT)
            val kopp = String(decodedBytes, java.nio.charset.StandardCharsets.ISO_8859_1)

            val result = java.lang.StringBuilder()
            for (i in 0 until kopp.length) {
                val gljp = kopp[i].code
                val immp = key[i % key.length].code
                val cidp = kQqs[i % kQqs.length].code
                val decryptedChar = gljp xor immp xor cidp
                result.append(decryptedChar.toChar())
            }
            return result.toString()
        }

        /**
         * Iterates through available decryption strategies in order (primary -> fallback).
         */
        fun decryptServerPayload(
            payloadA: String,
            slicesB: List<List<Int>>,
            keyC: String,
            metadata: DecryptionMetadata,
            serverTag: String
        ): String? {
            for (strategy in decryptionStrategies) {
                val decrypted = strategy.decrypt(payloadA, slicesB, keyC, metadata)
                if (!decrypted.isNullOrBlank() && decrypted.startsWith("http")) {
                    Log.d("[CimaLeek] [loadLinks]", "$serverTag resolved using strategy '${strategy.name}'")
                    return decrypted
                }
            }
            return null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // loadLinks: extract watch-server EMBED URLs, hand each to the standard
    // CloudStream extractor system (loadExtractor). No custom video sniffing
    // here — unsupported hosts fall through to the shared sniffer fallback
    // inside the extraction system.
    //
    //   Watch page -> .lalaplay_player_option servers -> lalaplayer API
    //   -> V1/V2 decrypt strategies -> embed URL -> loadExtractor
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

        // PHASE 0 – Fetch watch page (getDocument owns the CF solve + cookies
        // the lalaplayer API calls need) and parse metadata + servers.
        Log.i(methodTag, "PHASE 0: Fetching watch page...")
        val watchUrl = if (data.endsWith("/watch/")) data else {
            if (data.endsWith("/")) "${data}watch/" else "$data/watch/"
        }
        val doc = httpService.getDocument(watchUrl, rewriteDomain = true)
        if (doc == null || doc.body().text().isBlank()) {
            Log.e(methodTag, "PHASE 0: Empty watch page document")
            return false
        }
        val html = doc.html()

        val ver = Regex(""""ver"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        val postId = Regex(""""post_id"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex(""""post_id"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        val postLink = Regex(""""post_link"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
        Log.d(methodTag, "PHASE 0: ver='$ver', postId='$postId', postLink='$postLink'")

        val serverElements = doc.select(".lalaplay_player_option")
        Log.d(methodTag, "PHASE 0: Found ${serverElements.size} server options")
        if (serverElements.isEmpty()) {
            Log.w(methodTag, "PHASE 0: no servers (title='${doc.title().take(80)}', html=${html.length} chars)")
            return false
        }

        val metadata = DecryptionMetadata(
            watchUrl = watchUrl,
            postLink = postLink,
            postId = postId,
            ver = ver
        )

        // PHASE 1 – Resolve (decrypt) all server URLs in parallel.
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

                        val json = httpService.getText(apiUrl, headers, rewriteDomain = true)
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

                        val decrypted = decryptServerPayload(
                            payloadA = a,
                            slicesB = bList,
                            keyC = c,
                            metadata = metadata,
                            serverTag = "[$idx] $serverName"
                        )

                        if (decrypted == null || !decrypted.startsWith("http")) {
                            Log.w(methodTag, "PHASE 1: [$idx] $serverName — all decryption strategies failed")
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

        // PHASE 2 – Hand EVERY embed URL to the standard extractor system.
        Log.i(methodTag, "PHASE 2: Piping ${resolved.size} embed URLs through loadExtractor...")
        val delivered = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            resolved.map { (serverName, decryptedUrl, idx) ->
                async {
                    try {
                        Log.d(methodTag, "PHASE 2: [$idx] $serverName — ${decryptedUrl.take(80)}")
                        loadExtractor(decryptedUrl, watchUrl, subtitleCallback) { link ->
                            delivered.incrementAndGet()
                            callback(link)
                        }
                    } catch (e: Exception) {
                        Log.w(methodTag, "PHASE 2: [$idx] $serverName extractor error: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        Log.i(methodTag, "END delivered=${delivered.get()} from ${resolved.size} servers")
        return delivered.get() > 0
    }
}
