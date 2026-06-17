package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Base64 // قد نحتاجها أحياناً، لكن الاعتماد الأساسي على Hex

class Tuniflix : MainAPI() {
    override var mainUrl = "https://tuniflix.site"
    override var name = "Tuni flix"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "أفلام",
        "$mainUrl/series/page/" to "مسلسلات",
        "$mainUrl/tg/tunisian-movies/page/" to "أفلام تونسية",
        "$mainUrl/tg/arabic-movies/page/" to "أفلام عربية",
        "$mainUrl/tg/turkish-series/page/" to "مسلسلات تركية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val home = document.select("article.TPost.B").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href")
        val title = this.selectFirst(".Title")?.text() ?: return null

        var posterUrl = this.selectFirst(".Image img")?.let { img ->
            img.attr("data-src") ?: img.attr("src")
        }
        if (posterUrl?.startsWith("//") == true) {
            posterUrl = "https:$posterUrl"
        }

        return if (href.contains("/serie/") || this.select(".TpTv").text()
                .contains("Serie", true)
        ) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.TPost.B").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.Title")?.text()?.trim() ?: "Unknown"
        val desc = document.selectFirst(".Description p")?.text()?.trim()

        var poster = document.selectFirst(".Image img.TPostBg")?.attr("src")
            ?: document.selectFirst(".Image img")?.attr("src")
        if (poster?.startsWith("//") == true) poster = "https:$poster"

        val year = document.selectFirst(".Date")?.text()?.toIntOrNull()
        val tags = document.select(".Tags a").map { it.text() }

        val isSeries = url.contains("/serie/") || document.select(".SeasonBx").isNotEmpty()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonLinks = document.select(".SeasonBx .Title a").map { it.attr("href") }

            seasonLinks.amap { seasonUrl ->
                try {
                    val seasonDoc = app.get(seasonUrl).document
                    val seasonTitle = seasonDoc.selectFirst("h1.Title")?.text() ?: ""
                    val seasonNum = Regex("Season\\s*(\\d+)").find(seasonTitle)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: 1

                    seasonDoc.select(".TPTblCn table tr").forEach { tr ->
                        val epLink = tr.selectFirst("a")?.attr("href") ?: return@forEach
                        val epName = tr.selectFirst(".MvTbTtl a")?.text() ?: "Episode"
                        val epNum = tr.selectFirst(".Num")?.text()?.toIntOrNull()

                        episodes.add(newEpisode(epLink) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        })
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document


        val playerFrames = document.select("iframe[src*='strp2p'], script[src*='strp2p']")
        playerFrames.forEach { frame ->
            val src = fixUrl(frame.attr("src"))
            Strp2p.extract(src, callback)
        }

        val embedFrames = document.select("iframe[src*='trembed'], iframe[src*='trid']")
        embedFrames.forEach { frame ->
            val src = fixUrl(frame.attr("src"))

            val embedDoc = app.get(src, referer = mainUrl).document

            val innerPlayer = embedDoc.selectFirst("iframe[src*='strp2p'], script[src*='strp2p']")
            if (innerPlayer != null) {
                val playerSrc = fixUrl(innerPlayer.attr("src"))
                Strp2p.extract(playerSrc, callback)
            }
        }

        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (!src.contains("trembed") && !src.contains("strp2p")) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return mainUrl + url
        return url
    }


    object Strp2p {
        private const val KEY_STRING = "kiemtienmua911ca"
        private const val API_BASE = "https://watch.strp2p.site"

        private fun getIv(D: Int, W: Int): ByteArray {
            try {

                val part1 = (1..9).map { (it + D).toChar() }.joinToString("")

                val part2Chars = intArrayOf(D, 111, W, 128, 132, 97, 95)
                val part2 = part2Chars.map { it.toChar() }.joinToString("")

                val fullString = part1 + part2

                return fullString.toByteArray(Charsets.UTF_8).copyOfRange(0, 16)
            } catch (e: Exception) {
                return ByteArray(16)
            }
        }

        private fun decrypt(encryptedHex: String, D: Int, W: Int): String? {
            return try {
                var cleanHex = encryptedHex.trim().replace("\"", "")

                if (cleanHex.length % 2 != 0) {
                    cleanHex = cleanHex.dropLast(1)
                }

                val encryptedBytes = cleanHex.chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()

                val skeySpec = SecretKeySpec(KEY_STRING.toByteArray(Charsets.UTF_8), "AES")
                val ivSpec = IvParameterSpec(getIv(D, W))

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec)

                val original = cipher.doFinal(encryptedBytes)
                String(original, Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }

        suspend fun extract(initialUrl: String, callback: (ExtractorLink) -> Unit) {
            val MAX_RETRIES = 5
            var lastError: Exception? = null

            for (attempt in 1..MAX_RETRIES) {
                try {
                    var videoId = ""

                    if (initialUrl.contains("strp2p.site")) {
                        videoId = if (initialUrl.contains("#")) {
                            initialUrl.substringAfter("#").substringBefore("&")
                        } else {
                            initialUrl.substringAfter("id=").substringBefore("&")
                        }
                    } else if (initialUrl.contains("tuniflix.site")) {
                        val doc = app.get(initialUrl).document
                        val playerIframe = doc.selectFirst("iframe[src*='strp2p']")?.attr("src")
                            ?: doc.selectFirst("iframe[src*='trembed']")?.attr("src")

                        if (playerIframe != null) {
                            val playerUrl = if (playerIframe.startsWith("//")) "https:$playerIframe" else playerIframe
                            if (playerUrl.contains("strp2p")) {
                                videoId = playerUrl.substringAfter("#").substringBefore("&")
                            } else {
                                extract(playerUrl, callback)
                                return
                            }
                        }
                    }

                    if (videoId.isEmpty()) return

                    val apiUrl = "$API_BASE/api/v1/video?id=$videoId"
                    val headers = mapOf(
                        "Referer" to "https://watch.strp2p.site/",
                        "Origin" to "https://watch.strp2p.site",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )

                    val encryptedResponse = app.get(apiUrl, headers = headers).text

                    val candidates = listOf(
                        Pair(48, 105), Pair(48, 0), Pair(48, 141), Pair(48, 189), Pair(48, 63), Pair(323, 0)
                    )

                    var foundLinks = false
                    for ((D, W) in candidates) {
                        val jsonResult = decrypt(encryptedResponse, D, W)

                        if (jsonResult != null && jsonResult.trim().startsWith("{")) {
                            try {
                                val data = AppUtils.parseJson<StrpResponse>(jsonResult)

                                val linksToAdd = mutableListOf<Pair<String, String>>()

                                data.source?.let { if (it.isNotEmpty() && "://" in it) linksToAdd.add(Pair("SRC", it)) }

                                if (linksToAdd.isNotEmpty()) {

                                    linksToAdd.apmap { (name, rawLink) ->

                                        val masterM3u8 = sanitizeUrl(rawLink)

                                        val finalM3u8 = getFinalM3u8(masterM3u8)

                                        if (finalM3u8 != null) {
                                            callback.invoke(
                                                newExtractorLink(
                                                    "Tuniflix",
                                                    "Tuniflix Server ($name)",
                                                    finalM3u8,
                                                ) {
                                                    referer = "https://watch.strp2p.site/"
                                                    Qualities.Unknown.value
                                                }
                                            )
                                            foundLinks = true
                                        }
                                    }

                                }

                                if (foundLinks) return // نجحنا، نخرج من الدالة

                            } catch (e: Exception) { /* JSON غير صالح، نكمل */ }
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                    e.printStackTrace()
                }
                if(attempt < MAX_RETRIES) kotlinx.coroutines.delay(500)
            }

            if (lastError != null) throw lastError
        }


        private suspend fun getFinalM3u8(masterUrl: String): String? {
            return try {
                val playlistContent = app.get(masterUrl, headers = mapOf("Referer" to "https://watch.strp2p.site/")).text

                val qualityLine = playlistContent.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }

                if (qualityLine != null) {
                    val basePath = masterUrl.substringBeforeLast("/")
                    "$basePath/$qualityLine"
                } else {
                    masterUrl // fallback to master if quality not found
                }
            } catch (e: Exception) {
                masterUrl // return master url on error
            }
        }


        private fun sanitizeUrl(rawLink: String): String {

            val urlRegex = Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,10}/.*)""")
            val match = urlRegex.find(rawLink)

            return if (match != null) {

                "https://${match.value}"
            } else {

                if (rawLink.contains("://")) "https://" + rawLink.substringAfter("://") else "https://$rawLink"
            }
        }

        data class StrpResponse(
            @JsonProperty("source") val source: String?,
            @JsonProperty("cf") val cf: String?
        )
    }
}