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
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

            val keyMatcher = Pattern.compile("var\\s+_r\\s*=\\s*(\\d+)").matcher(rawHtml)
            if (!keyMatcher.find()) return doc
            val dynamicKey = keyMatcher.group(1).toLong()

            val dataMatcher = Pattern.compile("['\"]([A-Za-z0-9+/=~]{20,})['\"]").matcher(rawHtml)
            val extractedData = StringBuilder(100000)
            while (dataMatcher.find()) {
                extractedData.append(dataMatcher.group(1))
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
        Log.i("CimaNowLoadLinks", "================ [START LOADLINKS] ================")
        Log.d("CimaNowLoadLinks", "-> Data URL: $data")

        try {
            Log.i("CimaNowLoadLinks", "[1/6] Fetching initial movie page...")
            val moviePageDoc = app.get(data).document

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
                ?: run {
                    Log.e(TAG, "   CRITICAL: resolveFreex2line returned null.")
                    throw ErrorLoadingException("Failed to bypass shortlink.")
                }

            Log.i(TAG, "   Watch page URL obtained: $finalCimaNowUrl")
            Log.i(TAG, "[4/6] Fetching and decoding watch page...")

            val watchDoc = app.get(finalCimaNowUrl, referer = data).document
            val decodedDoc = decodeHtml(watchDoc)
            val serverElements = decodedDoc.select("ul#watch li[data-index]")

            if (serverElements.isEmpty()) {
                Log.w(TAG, "   No watch server elements found after decoding.")
            } else {
                Log.i(TAG, "   Found ${serverElements.size} watch server elements.")
            }

            Log.i(TAG, "[5/6] Processing WATCH server elements...")

            coroutineScope {
                serverElements.map { serverElement ->
                    async {
                        processServerElement(serverElement, finalCimaNowUrl, subtitleCallback, callback)
                    }
                }.awaitAll()
            }

            Log.i(TAG, "[6/6] Processing DOWNLOAD links...")
            val downloadLinks = decodedDoc.select("ul#download li a[href]")

            coroutineScope {
                downloadLinks.map { aTag ->
                    async {
                        processDownloadLink(aTag, finalCimaNowUrl, subtitleCallback, callback)
                    }
                }.awaitAll()
            }

            Log.i(TAG, "================ [END LOADLINKS] =================")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "FATAL ERROR in loadLinks: ${e.message}")
        }

        Log.i(TAG, "================ [END LOADLINKS] =================")
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
        Log.i("Freex2lineResolver", "======= [STARTING RESOLVER v3 - DYNAMIC KEY] =======")

        try {
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(context, "قد يستغرق 12 ثانية..", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}

        val sessionCookies = mutableMapOf<String, String>()

        try {
            Log.i("Freex2lineResolver", "[1/6] Initializing session...")
            val baseHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://rm.freex2line.online/"
            )

            val headResponse = app.get(url, headers = baseHeaders)
            sessionCookies.putAll(headResponse.cookies)

            Log.i("Freex2lineResolver", "[2/6] Fetching data page...")
            val pageUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
            val res = app.get(pageUrl, headers = baseHeaders, cookies = sessionCookies)
            val html = res.text
            sessionCookies.putAll(res.cookies)

            Log.i("Freex2lineResolver", "[3/6] Analyzing dynamic mapping (CFG)...")
            val cfgText = reMatch(html, "window\\._0x_cfg\\s*=\\s*\\{([^}]+)\\}")
                ?: throw Exception("CFG object not found")

            val cVarName = reMatch(cfgText, "c:\\s*'([^']+)'") ?: throw Exception("c mapping not found")
            val rVarName = reMatch(cfgText, "r:\\s*'([^']+)'") ?: throw Exception("r mapping not found")
            val kVarName = reMatch(cfgText, "k:\\s*'([^']+)'") ?: throw Exception("k (key) mapping not found")
            val sXorKey = reMatch(cfgText, "s:\\s*'([^']+)'") ?: throw Exception("s (XOR key) not found")

            Log.i("Freex2lineResolver", "[4/6] Extracting dynamic values...")
            val ch = reMatch(html, "window\\.$cVarName\\s*=\\s*'([^']+)'") ?: throw Exception("ch value not found")
            val requestId = reMatch(html, "window\\.$rVarName\\s*=\\s*'([^']+)'") ?: throw Exception("requestId value not found")
            val encryptedKeyB64 = reMatch(html, "window\\.$kVarName\\s*=\\s*'([^']+)'") ?: throw Exception("Encrypted key value not found")

            Log.i("Freex2lineResolver", "[5/6] Decrypting secret key...")
            val encryptedBytes = Base64.decode(encryptedKeyB64, 0)
            val decryptedChars = encryptedBytes.mapIndexed { index, byte ->
                (byte.toInt() xor sXorKey[index % sXorKey.length].code).toChar()
            }
            val secretKey = decryptedChars.joinToString("")
            Log.d(TAG, "   Dynamic Secret Key: $secretKey")

            Log.i("Freex2lineResolver", "[6/6] Generating HMAC signature...")
            val fpRaw = "Mozilla/5.10"
            val fpBase64 = Base64.encodeToString(fpRaw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val messageToSign = requestId + ch + fpBase64
            val hmacToken = calculateHmacSha256(messageToSign, secretKey)
            val hmacTokenEncoded = java.net.URLEncoder.encode(hmacToken, "UTF-8")

            delay(10000)

            Log.i(TAG, "Sending final API request...")
            val apiUrl = "https://rm.freex2line.online/2020/02/blog-post.html/get-link.php?request_id=$requestId&hmac_token=$hmacTokenEncoded&ch=$ch&fp=$fpBase64"

            val cookieHeader = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val finalHeaders = mapOf(
                "User-Agent" to baseHeaders["User-Agent"]!!,
                "Referer" to "https://rm.freex2line.online/",
                "Cookie" to cookieHeader
            )

            val finalRes = app.get(apiUrl, headers = finalHeaders)
            val finalResult = finalRes.text.trim()

            if (finalResult.startsWith("http")) {
                Log.i(TAG, "[SUCCESS] Watch page URL obtained: $finalResult")
                return finalResult
            }

            Log.e(TAG, "[FAILURE] Server did not return a valid URL. Response: $finalResult")
        } catch (e: Exception) {
            Log.e(TAG, "[FATAL ERROR] An exception occurred during resolution: ${e.message}")
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

    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    private fun calculateHmacSha256(message: String, secret: String): String {
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
