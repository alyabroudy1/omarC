package com.cimanow

import android.content.Context
import android.util.Base64
import android.widget.Toast
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

class CimaNowProvider : BaseProvider() {
    lateinit var context: Context

    override val providerName get() = "Cimanow"
    override val baseDomain get() = "cimanow.cc"
    override val githubConfigUrl get() = ""

    override fun getParser(): NewBaseParser {
        return CimaNowParser()
    }

    override var lang = "ar"

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val TAG = "CimaNowDebug"

    override val mainPage = mainPageOf(
        mainUrl + "/الاحدث/" to "الاحدث",
        mainUrl + "/category/افلام-اجنبية/page/" to "افلام اجنبية",
        mainUrl + "/category/مسلسلات-اجنبية/page/" to "مسلسلات اجنبية",
        mainUrl + "/category/افلام-نتفليكس/page/" to "افلام نتفليكس",
        mainUrl + "/category/مسلسلات-نتفليكس/page/" to "مسلسلات نتفليكس",
        mainUrl + "/category/افلام-مارفل/page/" to "افلام مارفل",
        mainUrl + "/category/مسلسلات-عربية/page/" to "مسلسلات عربية",
        mainUrl + "/category/افلام-عربية/page/" to "افلام عربية",
        mainUrl + "/category/مسلسلات-عربية/page/" to "مسلسلات عربية",
        mainUrl + "/category/افلام-هندية/page/" to "أفلام هندية",
        mainUrl + "/category/افلام-تركية/page/" to "أفلام تركية",
        mainUrl + "/category/مسلسلات-تركية/page/" to "مسلسلات تركية"
    )

    private data class SvgObject(val stream: String, val hash: String)

    private fun getIntFromText(text: String): Int? {
        return Regex("\\d+").find(text)?.value?.toIntOrNull()
    }

    // ==================== decodeHtml ====================

    private fun decodeAndWriteFast(chunk: StringBuilder, key: Long, out: ByteArrayOutputStream): Int {
        val r = chunk.length % 4
        if (r > 0) {
            when (r) {
                2 -> chunk.append("==")
                3 -> chunk.append("=")
            }
        }
        return try {
            val bytes = Base64.decode(chunk.toString(), Base64.DEFAULT)
            var num = 0L
            for (b in bytes) {
                val bInt = b.toInt()
                if (bInt in 48..57) {
                    num = num * 10 + (bInt - 48)
                }
            }
            if (num > 0) {
                out.write((num - key).toInt())
                1
            } else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun decodeHtml(doc: Document): Document {
        try {
            val rawHtml = doc.outerHtml()

            val keyMatcher = Pattern.compile("var\\s+_r\\s*=\\s*(\\d+(?:\\s*\\+\\s*\\d+)*)\\s*;").matcher(rawHtml)
            if (!keyMatcher.find()) return doc
            val dynamicKey = keyMatcher.group(1).split("+").sumOf { it.trim().toLong() }

            val dataMatcher = Pattern.compile("['\"]([A-Za-z0-9+/=~]{20,})['\"]").matcher(rawHtml)
            val extractedData = StringBuilder(100000)
            while (dataMatcher.find()) {
                val chunk = dataMatcher.group(1)
                if (chunk.indexOf('~') >= 0) extractedData.append(chunk)
            }
            if (extractedData.isEmpty()) return doc

            val out = ByteArrayOutputStream(extractedData.length / 4)
            val chunk = StringBuilder(64)
            val len = extractedData.length

            for (i in 0 until len) {
                val c = extractedData[i]
                when {
                    c == '~' -> {
                        if (chunk.isNotEmpty()) {
                            decodeAndWriteFast(chunk, dynamicKey, out)
                            chunk.setLength(0)
                        }
                    }
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' -> {
                        chunk.append(c)
                    }
                }
            }
            if (chunk.isNotEmpty()) {
                decodeAndWriteFast(chunk, dynamicKey, out)
            }

            val decoded = out.toString("UTF-8")
            if (decoded.isBlank()) return doc
            return Jsoup.parse(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "decodeHtml error: ${e.message}")
        }
        return doc
    }

    // ==================== search ====================

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        val q = query
        val doc = app.get("$mainUrl/?s=$q", referer = mainUrl).document
        val decodedDoc = decodeHtml(doc)
        return decodedDoc.select("article").mapNotNull { toSearchResponse(it) }
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        return searchNormal(query)
    }

    // ==================== getMainPage ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = request.data + page
        val doc = app.get(url, referer = mainUrl).document
        val decodedDoc = decodeHtml(doc)
        val elements = decodedDoc.select("article").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, elements)
    }

    // ==================== toSearchResponse ====================

    private fun toSearchResponse(element: Element): SearchResponse? {
        if (element.select("a").text().contains("الكل")) return null

        val urlElement = element.selectFirst("a") ?: return null
        val href = urlElement.attr("href")

        var posterUrl = element.select("img.lazy").attr("data-src")
        if (posterUrl.isBlank()) {
            posterUrl = element.select("img.lazy").attr("src")
        }

        val category = element.select("ul.info li[aria-label=tab]").text()
        val titleElement = element.selectFirst("li[aria-label=title]")
        var title = ""
        if (titleElement != null) {
            titleElement.select("em").remove()
            title = titleElement.text().ifBlank { "" }
        }

        val year = element.select("li[aria-label=year]").text().toIntOrNull()

        val qualities = element.select("li[aria-label=ribbon]")
            .map { it.text() }
            .filter { Regex("\\d+").containsMatchIn(it) }
        val quality = getQualityFromString(qualities.joinToString(" "))

        val type = if (category.contains("مسلسلات", true) || category.contains("موسم", true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        val cleanTitle = Regex("$category|موسم 1|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\\||")
            .replace(title, "")

        return newMovieSearchResponse(cleanTitle, href, type) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    // ==================== load ====================

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document
        val decodedDoc = decodeHtml(doc)

        val isMovie = decodedDoc.title().contains("فيلم")

        val posterUrl = decodedDoc.select("figure img").attr("src")
        val year = decodedDoc.select("ul li a[href^='https://cimanow.cc/release-year/']").text().toIntOrNull()

        val titleRegex = Regex("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\\|${year}|Cima Now|-|سيما ناو|ج[0-9]|\\|")
        val title = titleRegex.replace(decodedDoc.title(), "")

        val tags = decodedDoc.select("article ul li")
            .filter { it.attr("aria-label") != "story" }
            .flatMap { it.text().split("،") }
            .map { it.trim() }

        val recommendations = decodedDoc.select("ul.related li").mapNotNull { toSearchResponse(it) }

        val synopsis = decodedDoc.select("li[aria-label=story] p").text()

        val actors = decodedDoc.select("ul li a[href^='https://cimanow.cc/actor/']")
            .map { it.text() }
            .filter { !it.isNullOrBlank() }
            .map { ActorData(Actor(it)) }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasonElements = decodedDoc.select("section[aria-label=seasons] ul li a")

        if (seasonElements.isNotEmpty()) {
            coroutineScope {
                val deferredEpisodes = seasonElements.map { seasonElement ->
                    async {
                        val seasonDoc = try {
                            app.get(seasonElement.attr("href"), referer = url).document
                        } catch (_: Exception) { null }
                        if (seasonDoc != null) {
                            val decodedSeason = decodeHtml(seasonDoc)
                            val seasonTitle = decodedSeason.selectFirst("span[aria-label=season-title]")
                            val seasonNum = getIntFromText(seasonTitle?.text() ?: "") ?: 1
                            decodedSeason.select("ul#eps li a").mapNotNull { epElement ->
                                newEpisode(epElement.attr("href")) {
                                    this.name = epElement.selectFirst("img")?.attr("alt")
                                    this.season = seasonNum
                                    this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                                    this.posterUrl = posterUrl
                                }
                            }
                        } else emptyList()
                    }
                }
                episodes.addAll(deferredEpisodes.awaitAll().flatten())
            }
        } else {
            val seasonTitle = decodedDoc.selectFirst("span[aria-label=season-title]")
            val seasonNum = getIntFromText(seasonTitle?.text() ?: "") ?: 1

            decodedDoc.select("ul#eps li a").mapNotNull { epElement ->
                newEpisode(epElement.attr("href")) {
                    this.name = epElement.selectFirst("img")?.attr("alt")
                    this.season = seasonNum
                    this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                    this.posterUrl = posterUrl
                }
            }.let { episodes.addAll(it) }
        }

        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = synopsis
            this.tags = tags
            this.recommendations = recommendations
            this.actors = actors
        }
    }

    // ==================== loadLinks ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i("CimaNowLoadLinks", "================ [START LOADLINKS v5] ================")
        Log.d("CimaNowLoadLinks", "-> Data URL: $data")

        try {
            Log.i("CimaNowLoadLinks", "[1/6] Fetching initial movie page...")
            val cacheBuster = "?_ts=${System.currentTimeMillis()}"
            val fetchUrl = if (data.contains("?")) "$data&$cacheBuster" else "$data$cacheBuster"
            val moviePageResp = app.get(fetchUrl)
            val moviePageDoc = moviePageResp.document
            val litespeedTag = moviePageResp.headers?.get("x-litespeed-tag")
                ?: moviePageResp.headers?.get("X-Litespeed-Tag")
            val postId = litespeedTag?.let {
                Regex("904_Po\\.(\\d+)").find(it)?.groupValues?.get(1)
            } ?: throw ErrorLoadingException("Failed to extract post ID from LiteSpeed header")
            Log.i(TAG, "Extracted post ID: $postId")

            Log.i("CimaNowLoadLinks", "[2/6] Searching for freex2line intermediate link...")
            var intermediateLink: String? = null
            val preciseLink = moviePageDoc.selectFirst("ul.btns li a.shine[href*='freex2line']")
            if (preciseLink != null) {
                intermediateLink = preciseLink.attr("href")
            }
            if (intermediateLink.isNullOrBlank()) {
                Log.w(TAG, "   - Precise selector failed, trying a general search...")
                intermediateLink = moviePageDoc.select("a[href*='freex2line']").firstOrNull()?.attr("href")
            }
            if (intermediateLink.isNullOrBlank()) {
                Log.e(TAG, "   - CRITICAL: Could not find any freex2line link.")
                throw ErrorLoadingException("Failed to find intermediate link.")
            }
            Log.d(TAG, "   Found intermediate link: $intermediateLink")

            Log.i(TAG, "[3/6] Resolving shortlink via resolveFreex2line...")
            val finalCimaNowUrl = resolveFreex2line(intermediateLink)
                ?: throw ErrorLoadingException("Failed to bypass shortlink.")
            Log.i(TAG, "   Watch page URL obtained: $finalCimaNowUrl")

            Log.i(TAG, "[4/6] Fetching video links via core.php (bypass watch page)...")
            val knownIndices = listOf("00", "66", "32", "7", "30", "12")

            coroutineScope {
                knownIndices.map { index ->
                    async {
                        try {
                            val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$postId"
                            val playerDoc = app.get(ajaxUrl, referer = finalCimaNowUrl).document
                            val iframeUrl = playerDoc?.select("iframe")?.attr("src") ?: ""

                            if (iframeUrl.isNotBlank() && iframeUrl != "123456789") {
                                val serverName = when (index) {
                                    "00" -> "CimaNow"
                                    "66" -> "Upnshare"
                                    "32" -> "Filemoon"
                                    "7" -> "OK.ru"
                                    "30" -> "VK Video"
                                    "12" -> "Uqload"
                                    else -> "Server $index"
                                }
                                Log.i(TAG, "Valid iframe for index=$index ($serverName): $iframeUrl")

                                if (iframeUrl.contains("cimanowtv", true)) {
                                    handlecima(iframeUrl, serverName, callback)
                                } else if (iframeUrl.contains("forafile.com", true)) {
                                    handleForafile(iframeUrl, 0, finalCimaNowUrl, callback)
                                } else {
                                    loadExtractor(iframeUrl, finalCimaNowUrl, subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing server index=$index: ${e.message}")
                        }
                    }
                }.awaitAll()
            }

            Log.i(TAG, "================ [END LOADLINKS v5] =================")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "FATAL ERROR in loadLinks: ${e.message}")
        }

        Log.i(TAG, "================ [END LOADLINKS v5] =================")
        return false
    }

    // ==================== processServerElement ====================

    private suspend fun processServerElement(
        serverElement: Element,
        finalCimaNowUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val dataIndex = serverElement.attr("data-index")
            val dataId = serverElement.attr("data-id")
            val serverName = serverElement.text().trim()

            Log.d(TAG, "Server: name='$serverName', index=$dataIndex, id=$dataId")

            val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$dataIndex&id=$dataId"
            val playerDoc = try {
                app.get(ajaxUrl, referer = finalCimaNowUrl).document
            } catch (_: Exception) { null }

            val iframeUrl = playerDoc?.select("iframe")?.attr("src") ?: ""

            if (serverName.contains("Cima Now", true) || serverName.contains("cima", true)) {
                handlecima(iframeUrl, serverName, callback)
            } else if (serverName.contains("VidPro", true)) {
                handleVidPro(iframeUrl, serverName, callback)
            } else if (serverName.contains("Govid", true) || serverName.contains("Goovid", true)) {
                handleGovid(iframeUrl, serverName, callback)
            } else if (serverName.contains("Vidlook", true)) {
                handleVidlook(iframeUrl, serverName, callback)
            } else if (serverName.contains("Streamwish", true)) {
                handleStreamwish(iframeUrl, serverName, callback)
            } else if (serverName.contains("Streamfile", true) || serverName.contains("Luluvid", true)) {
                handleStreamfileAndLuluvid(iframeUrl, serverName, callback)
            } else if (serverName.contains("Vadbam", true) || serverName.contains("Viidshare", true)) {
                handleVadbamAndViidshare(iframeUrl, serverName, callback)
            } else if (serverName.contains("Jetload", true)) {
                handleJetload(iframeUrl, 0, finalCimaNowUrl, callback)
            } else if (serverName.contains("Forafile", true) || iframeUrl.contains("forafile.com")) {
                handleForafile(iframeUrl, 0, finalCimaNowUrl, callback)
            } else {
                for (link in serverElement.select("a")) {
                    val dlink = link.attr("href")
                    if (dlink.isNotBlank() && dlink.startsWith("http")) {
                        val quality = Regex("\\d+p").find(link.text())?.value?.let { getQualityFromName(it) }
                            ?: Qualities.Unknown.value
                        callback(newExtractorLink(serverName, serverName, dlink, type = getLinkType(dlink)) {
                            this.referer = finalCimaNowUrl
                            this.quality = quality
                        })
                    }
                }
                if (iframeUrl.isNotBlank()) {
                    loadExtractor(iframeUrl, finalCimaNowUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing server: ${e.message}")
        }
    }

    // ==================== processDownloadLink ====================

    private suspend fun processDownloadLink(
        aTag: Element,
        finalCimaNowUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val dlink = aTag.attr("href")
            val linkText = aTag.text()
            val quality = Regex("\\d+p").find(linkText)?.value?.let { getQualityFromName(it) }
                ?: Qualities.Unknown.value

            if (dlink.isNotBlank() && dlink.startsWith("http")) {
                Log.d(TAG, "Download link: quality=$quality url=$dlink")
                callback(newExtractorLink("CimaNow", "CimaNow", dlink, type = getLinkType(dlink)) {
                    this.referer = finalCimaNowUrl
                    this.quality = quality
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing download link: ${e.message}")
        }
    }

    // ==================== handlecima ====================

    private suspend fun handlecima(
        iframeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            val iframeResponse = app.get(finalUrl, referer = finalUrl).text

            val regex = Regex("\\[(\\d+p)]\\s+(/uploads/[^\"]+\\.mp4)")
            val baseUrlMatch = Regex("(https?://[^/]+)").find(finalUrl)
            val baseUrl = baseUrlMatch?.groupValues?.get(1) ?: ""

            val links = mutableListOf<ExtractorLink>()

            for (match in regex.findAll(iframeResponse)) {
                val qualityStr = match.groupValues[1]
                val filePath = match.groupValues[2]
                val videoUrl = baseUrl + filePath

                val link = newExtractorLink("CimaNow", "CimaNow", videoUrl, type = getLinkType(videoUrl))
                link.quality = getQualityFromName(qualityStr)
                link.referer = finalUrl
                links.add(link)
            }

            if (links.size > 1) {
                links.sortByDescending { it.quality }
            }

            links.firstOrNull()?.let { callback(it) }
        } catch (e: Exception) {
            Log.e(TAG, "handlecima error: ${e.message}")
        }
    }

    // ==================== Simple handle methods (loadExtractor based) ====================

    private suspend fun handleVidPro(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleGovid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleVidlook(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleStreamwish(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleStreamfileAndLuluvid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleVadbamAndViidshare(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    // ==================== handleJetload ====================

    private suspend fun handleJetload(
        url: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_JL = "JetloadExtractor"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar-EG,ar;q=0.9"
        )

        try {
            val res1 = app.get(url, headers = headers)
            val sessionCookies = res1.cookies.toMutableMap()

            val headers2 = headers + ("Referer" to url)
            val targetUrl = "https://jetload.pp.ua/Jetload4/"
            val res2 = app.get(targetUrl, headers = headers2, cookies = sessionCookies)
            val html = res2.text
            sessionCookies.putAll(res2.cookies)

            val extraToken = Regex("window\\.extraToken\\s*=\\s*'([^']+)'").find(html)?.groupValues?.get(1)
            val dataToken = Regex("data-token=\"([^\"]+)\"").find(html)?.groupValues?.get(1)

            if (extraToken == null || dataToken == null) {
                Log.e(TAG_JL, "[-] Failed to extract tokens.")
                return
            }

            delay(10000)

            val ajaxUrl = "https://jetload.pp.ua/Jetload4/get-link.php?token=$dataToken"
            val ajaxHeaders = headers2 + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to targetUrl
            )
            val finalResp = app.get(ajaxUrl, headers = ajaxHeaders, cookies = sessionCookies)
            val rawLink = finalResp.text.trim()

            if (!rawLink.startsWith("http")) {
                Log.e(TAG_JL, "[-] Invalid server response: $rawLink")
                return
            }

            val intermediateLink = "$rawLink?t=$extraToken"
            Log.d(TAG_JL, "[+] Final Media Link: $intermediateLink")

            val link = newExtractorLink("Jetload", "Jetload", intermediateLink)
            link.referer = targetUrl
            link.quality = quality
            callback(link)

        } catch (e: Exception) {
            Log.e(TAG_JL, "[-] Error in Jetload: ${e.message}")
        }
    }

    // ==================== handleForafile ====================

    private suspend fun handleForafile(
        url: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_FF = "ForafileExtractor"
        try {
            val match = Regex("(https://forafile\\.com/([^/]+)/)").find(url) ?: return
            val baseUrl = match.groupValues[1]
            val fileId = match.groupValues[2]

            val headers = mapOf(
                "user-agent" to "Mozilla/5.0 (Linux; Android 13)",
                "referer" to url
            )
            val data = mapOf(
                "op" to "download2",
                "id" to fileId,
                "rand" to "",
                "referer" to "",
                "method_free" to "",
                "method_premium" to "",
                "adblock_detected" to "0"
            )

            val response = app.post(baseUrl, headers = headers, data = data)
            val location = response.headers["location"] ?: response.headers["Location"]

            if (location.isNullOrBlank()) {
                Log.e(TAG_FF, "[-] No redirect location found.")
                return
            }

            val link = newExtractorLink("Forafile", "Forafile", location)
            link.referer = baseUrl
            link.quality = quality
            callback(link)

        } catch (e: Exception) {
            Log.e(TAG_FF, "[-] Error in Forafile: ${e.message}")
        }
    }

    // ==================== resolveFreex2line ====================

    private suspend fun resolveFreex2line(url: String): String? {
        Log.i("Freex2lineResolver", "======= [STARTING RESOLVER v4 - SIMPLE DECODE] =======")

        try {
            val linkParam = url.substringAfter("link=", "")
            if (linkParam.isBlank()) {
                Log.e(TAG, "[FATAL ERROR] No 'link' parameter found in URL: $url")
                return null
            }

            val decodedBytes = Base64.decode(linkParam, Base64.DEFAULT)
            val decodedUrl = String(decodedBytes, Charsets.UTF_8)

            if (decodedUrl.startsWith("http")) {
                Log.i(TAG, "[SUCCESS] Resolved URL: $decodedUrl")
                return decodedUrl
            }

            Log.e(TAG, "[FAILURE] Decoded value is not a valid URL: $decodedUrl")
        } catch (e: Exception) {
            Log.e(TAG, "[FATAL ERROR] Exception during resolution: ${e.message}")
            e.printStackTrace()
        }

        Log.i(TAG, "======= [RESOLVER FINISHED - FAILED] =======")
        return null
    }

    private fun getLinkType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mp4") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
    }

    // ==================== Utilities ====================

    private fun reMatch(html: String, regex: String): String? {
        return try {
            val matcher = Pattern.compile(regex).matcher(html)
            if (matcher.find()) matcher.group(1) else null
        } catch (_: Exception) {
            null
        }
    }

}
