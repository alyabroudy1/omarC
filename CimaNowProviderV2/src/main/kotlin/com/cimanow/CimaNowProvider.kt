package com.cimanow

import android.content.Context
import android.util.Base64
import android.widget.Toast
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

/// Main provider for CimaNow.cc — a multi-server movie/series streaming site.
///
/// Flow:
///   search/getMainPage/load → decodeHtml (unobfuscate page) → parse results
///   loadLinks → resolveFreex2line (JS challenge bypass) → decode watch page → extract servers
///
/// Server types handled: CimaNow native, VidPro, Govid, Vidlook, Streamwish,
/// Streamfile/Luluvid, Vadbam/Viidshare, Jetload, Forafile
class CimaNowProvider(private val context: Context) : MainAPI() {

    override var name = "Cimanow"
    override var mainUrl = "https://cimanow.cc"
    override var lang = "ar"
    override val hasMainPage = true
    override val usesWebView = false

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val TAG = "CimaNowDebug"
    private val TAG_LOAD = "CimaNowLoadLinks"

    override val mainPage = mainPageOf(
        mainUrl + "/الاحدث/" to "الاحدث",
        mainUrl + "/category/افلام-اجنبية/page/" to "افلام اجنبية",
        mainUrl + "/category/مسلسلات-اجنبية/page/" to "مسلسلات اجنبية",
        mainUrl + "/category/افلام-نتفليكس/page/" to "افلام نتفليكس",
        mainUrl + "/category/مسلسلات-نتفليكس/page/" to "مسلسلات نتفليكس",
        mainUrl + "/category/افلام-مارفل/page/" to "افلام مارفل",
        mainUrl + "/category/مسلسلات-عربية/page/" to "مسلسلات عربية",
        mainUrl + "/category/افلام-عربية/page/" to "افلام عربية",
        mainUrl + "/category/افلام-هندية/page/" to "أفلام هندية",
        mainUrl + "/category/افلام-تركية/page/" to "أفلام تركية",
        mainUrl + "/category/مسلسلات-تركية/page/" to "مسلسلات تركية"
    )

    private data class SvgObject(val stream: String, val hash: String)

    private fun getIntFromText(text: String): Int? {
        return Regex("\\d+").find(text)?.value?.toIntOrNull()
    }

    // ==================== decodeHtml (Anti-bot deobfuscation) ====================
    //
    // CimaNow obfuscates HTML by embedding base64-encoded payloads in the page,
    // separated by '~'. A dynamic key `_r` is used to offset each decoded number.
    // This method reverses that: extracts _r, collects base64 chunks, decodes
    // each into numbers, subtracts _r, writes the resulting byte, then re-parses
    // the output as HTML.

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

            // Extract the dynamic numeric key from `var _r = 36938+36939+36938` (can be sum of multiple numbers)
            val keyMatcher = Pattern.compile("var\\s+_r\\s*=\\s*(\\d+(?:\\+\\d+)*)").matcher(rawHtml)
            if (!keyMatcher.find()) return doc
            val dynamicKey = keyMatcher.group(1).split("+").sumOf { it.toLong() }

            // Collect all base64-like strings (20+ chars inside quotes)
            val dataMatcher = Pattern.compile("['\"]([A-Za-z0-9+/=~]{20,})['\"]").matcher(rawHtml)
            val extractedData = StringBuilder(100000)
            while (dataMatcher.find()) {
                extractedData.append(dataMatcher.group(1))
            }
            if (extractedData.isEmpty()) return doc

            // Process each ~-separated chunk
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
            Log.d(TAG, "decodeHtml: decoded ${decoded.length} chars")
            return Jsoup.parse(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "decodeHtml error: ${e.message}")
        }
        return doc
    }

    // ==================== search ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query
        Log.d(TAG, "search: query=$q")
        val doc = app.get("$mainUrl/?s=$q", referer = mainUrl).document
        val decodedDoc = decodeHtml(doc)
        val results = decodedDoc.select("article").mapNotNull { toSearchResponse(it) }
        Log.d(TAG, "search: found ${results.size} results")
        return results
    }

    // ==================== getMainPage ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = request.data + page
        Log.d(TAG, "getMainPage: url=$url")
        val doc = app.get(url, referer = mainUrl).document
        val decodedDoc = decodeHtml(doc)
        val elements = decodedDoc.select("article").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, elements)
    }

    // ==================== toSearchResponse ====================

    /// Parses an <article> element from the listing page into a SearchResponse.
    /// Extracts: title, poster, year, quality, type (Movie/TvSeries).
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

    // ==================== load (detail page) ====================

    /// Loads full metadata for a movie or series.
    /// For series: fetches each season's episode list concurrently.
    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: url=$url")
        val doc = app.get(url, referer = mainUrl).document
        val decodedDoc = decodeHtml(doc)

        val isMovie = decodedDoc.title().contains("فيلم")
        Log.d(TAG, "load: isMovie=$isMovie")

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
            Log.d(TAG, "load: fetching ${seasonElements.size} seasons concurrently")
            coroutineScope {
                val deferredEpisodes = seasonElements.map { seasonElement ->
                    async {
                        val seasonDoc = try {
                            app.get(seasonElement.attr("href"), referer = url).document
                        } catch (e: Exception) {
                            Log.w(TAG, "load: failed fetching season: ${e.message}")
                            null
                        }
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
        Log.d(TAG, "load: ${episodes.size} total episodes across ${seasonElements.size} seasons")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = synopsis
            this.tags = tags
            this.recommendations = recommendations
            this.actors = actors
        }
    }

    // ==================== loadLinks (stream URL resolution) ====================
    //
    // Entry point for resolving playable links.
    //   1. Fetch movie page → find freex2line intermediate link
    //   2. resolveFreex2line → bypass JS challenge → get watch page URL
    //   3. Decode watch page → extract server elements (data-index/data-id)
    //   4. Route each server to its handler via AJAX iframe resolution
    //   5. Also extract direct download links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG_LOAD, "================ [START LOADLINKS] ================")
        Log.d(TAG_LOAD, "-> Data URL: $data")

        try {
            Log.i(TAG_LOAD, "[1/6] Fetching initial movie page...")
            val moviePageDoc = app.get(data).document

            Log.i(TAG_LOAD, "[2/6] Searching for freex2line intermediate link...")
            var intermediateLink: String? = null

            // Try precise selector first: <a class="shine" href*="freex2line"> inside ul.btns
            val preciseLink = moviePageDoc.selectFirst("ul.btns li a.shine[href*='freex2line']")
            if (preciseLink != null) {
                intermediateLink = preciseLink.attr("href")
                Log.d(TAG_LOAD, "   Found via precise selector: $intermediateLink")
            }

            // Fallback: any <a href*="freex2line"> on the page
            if (intermediateLink.isNullOrBlank()) {
                Log.w(TAG, "   - Precise selector failed, trying general search...")
                intermediateLink = moviePageDoc.select("a[href*='freex2line']").firstOrNull()?.attr("href")
            }

            if (intermediateLink.isNullOrBlank()) {
                Log.e(TAG, "   - CRITICAL: Could not find any freex2line link.")
                throw ErrorLoadingException("Failed to find intermediate link.")
            }

            Log.d(TAG, "   Found intermediate link: $intermediateLink")
            Log.i(TAG, "[3/6] Resolving shortlink via resolveFreex2line...")

            // Bypass the freex2line JS challenge to get the real watch page URL
            val finalCimaNowUrl = resolveFreex2line(intermediateLink, context)
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
                serverElements.forEach { el ->
                    Log.d(TAG, "     Server: '${el.text()}' index=${el.attr("data-index")} id=${el.attr("data-id")}")
                }
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
            Log.d(TAG, "   Found ${downloadLinks.size} download links")

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
    //
    // Routes a watch server to its dedicated handler based on server name.
    // Each server type has its own iframe/AJAX resolution logic.

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

            // AJAX call to get the iframe URL for this server
            val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$dataIndex&id=$dataId"
            val playerDoc = try {
                app.get(ajaxUrl, referer = finalCimaNowUrl).document
            } catch (e: Exception) {
                Log.w(TAG, "   AJAX failed for server '$serverName': ${e.message}")
                null
            }

            val iframeUrl = (playerDoc?.select("iframe")?.attr("src") ?: "").let { normalizeUrl(it, mainUrl) }

            when {
                serverName.contains("Cima Now", true) || serverName.contains("cima", true) -> {
                    Log.d(TAG, "   -> Routing to handlecima")
                    handlecima(iframeUrl, serverName, callback)
                }
                serverName.contains("VidPro", true) -> {
                    Log.d(TAG, "   -> Routing to handleVidPro: $iframeUrl")
                    handleVidPro(iframeUrl, serverName, callback)
                }
                serverName.contains("Govid", true) || serverName.contains("Goovid", true) -> {
                    Log.d(TAG, "   -> Routing to handleGovid: $iframeUrl")
                    handleGovid(iframeUrl, serverName, callback)
                }
                serverName.contains("Vidlook", true) -> {
                    Log.d(TAG, "   -> Routing to handleVidlook: $iframeUrl")
                    handleVidlook(iframeUrl, serverName, callback)
                }
                serverName.contains("Streamwish", true) -> {
                    Log.d(TAG, "   -> Routing to handleStreamwish: $iframeUrl")
                    handleStreamwish(iframeUrl, serverName, callback)
                }
                serverName.contains("Streamfile", true) || serverName.contains("Luluvid", true) -> {
                    Log.d(TAG, "   -> Routing to handleStreamfileAndLuluvid: $iframeUrl")
                    handleStreamfileAndLuluvid(iframeUrl, serverName, callback)
                }
                serverName.contains("Vadbam", true) || serverName.contains("Viidshare", true) -> {
                    Log.d(TAG, "   -> Routing to handleVadbamAndViidshare: $iframeUrl")
                    handleVadbamAndViidshare(iframeUrl, serverName, callback)
                }
                serverName.contains("Jetload", true) -> {
                    Log.d(TAG, "   -> Routing to handleJetload: $iframeUrl")
                    handleJetload(iframeUrl, 0, finalCimaNowUrl, callback)
                }
                serverName.contains("Forafile", true) || iframeUrl.contains("forafile.com") -> {
                    Log.d(TAG, "   -> Routing to handleForafile: $iframeUrl")
                    handleForafile(iframeUrl, 0, finalCimaNowUrl, callback)
                }
                else -> {
                    Log.d(TAG, "   -> Unknown server type, trying direct links + loadExtractor")
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
                Log.d(TAG, "Download link: quality=$quality url=${dlink.take(80)}")
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
    //
    // Native CimaNow player: the iframe response contains inline
    // links like `[1080p] /uploads/path/to/video.mp4`. Extracts the
    // highest quality by parsing that format.

    private suspend fun handlecima(
        iframeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            val iframeResponse = app.get(finalUrl, referer = finalUrl).text

            // Pattern: [QUALITY] /uploads/...mp4
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

            Log.d(TAG, "handlecima: found ${links.size} quality levels")

            // Return the highest quality
            if (links.size > 1) {
                links.sortByDescending { it.quality }
            }

            links.firstOrNull()?.let {
                Log.d(TAG, "handlecima: selected quality ${it.quality} -> ${it.url.take(80)}")
                callback(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handlecima error: ${e.message}")
        }
    }

    // ==================== Simple handle methods (loadExtractor based) ====================
    //
    // These servers just need their iframe URL passed to CS3's built-in
    // loadExtractor, which knows how to handle common extractors.

    private suspend fun handleVidPro(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleVidPro failed: ${e.message}")
        }
    }

    private suspend fun handleGovid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleGovid failed: ${e.message}")
        }
    }

    private suspend fun handleVidlook(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleVidlook failed: ${e.message}")
        }
    }

    private suspend fun handleStreamwish(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleStreamwish failed: ${e.message}")
        }
    }

    private suspend fun handleStreamfileAndLuluvid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleStreamfileAndLuluvid failed: ${e.message}")
        }
    }

    private suspend fun handleVadbamAndViidshare(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleVadbamAndViidshare failed: ${e.message}")
        }
    }

    // ==================== handleJetload ====================
    //
    // Jetload uses a 3-step process:
    //   1. Load page → get session cookies
    //   2. Fetch Jetload4/ → extract extraToken and data-token
    //   3. Wait 10s → call get-link.php with tokens → get stream URL

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
            Log.d(TAG_JL, "[1/3] Initial page loaded, cookies=${res1.cookies.size}")
            val sessionCookies = res1.cookies.toMutableMap()

            val headers2 = headers + ("Referer" to url)
            val targetUrl = "https://jetload.pp.ua/Jetload4/"
            val res2 = app.get(targetUrl, headers = headers2, cookies = sessionCookies)
            val html = res2.text
            sessionCookies.putAll(res2.cookies)

            val extraToken = Regex("window\\.extraToken\\s*=\\s*'([^']+)'").find(html)?.groupValues?.get(1)
            val dataToken = Regex("data-token=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            Log.d(TAG_JL, "[2/3] extraToken=${extraToken?.take(20)}, dataToken=${dataToken?.take(20)}")

            if (extraToken == null || dataToken == null) {
                Log.e(TAG_JL, "[-] Failed to extract tokens.")
                return
            }

            Log.d(TAG_JL, "[3/3] Waiting 10s then fetching get-link.php...")
            delay(10000)

            val ajaxUrl = "https://jetload.pp.ua/Jetload4/get-link.php?token=$dataToken"
            val ajaxHeaders = headers2 + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to targetUrl
            )
            val finalResp = app.get(ajaxUrl, headers = ajaxHeaders, cookies = sessionCookies)
            val rawLink = finalResp.text.trim()
            Log.d(TAG_JL, "   Server response: ${rawLink.take(80)}")

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
    //
    // Forafile uses a POST form-based flow:
    //   1. POST to base URL with form data (op=download2, id=fileId)
    //   2. Follow redirect location header → get stream URL

    private suspend fun handleForafile(
        url: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_FF = "ForafileExtractor"
        try {
            val match = Regex("(https://forafile\\.com/([^/]+)/)").find(url) ?: run {
                Log.w(TAG_FF, "[-] Could not parse forafile URL: $url")
                return
            }
            val baseUrl = match.groupValues[1]
            val fileId = match.groupValues[2]
            Log.d(TAG_FF, "baseUrl=$baseUrl fileId=$fileId")

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
            Log.d(TAG_FF, "POST response location=$location")

            if (location.isNullOrBlank()) {
                Log.e(TAG_FF, "[-] No redirect location found.")
                return
            }

            val link = newExtractorLink("Forafile", "Forafile", location)
            link.referer = baseUrl
            link.quality = quality
            callback(link)
            Log.d(TAG_FF, "[+] Link emitted: ${location.take(80)}")

        } catch (e: Exception) {
            Log.e(TAG_FF, "[-] Error in Forafile: ${e.message}")
        }
    }

    // ==================== resolveFreex2line (JS challenge bypass) ====================
    //
    // Bypasses the freex2line.online JS challenge to resolve a short link
    // into the actual watch page URL.
    //
    // The challenge has two possible formats:
    //
    //   NEW format (preferred):
    //     window.ptr_XXXX = 'ctx_YYYY'                    → pointer to context object
    //     window['ctx_YYYY'] = { v_65b1: 'ch', ... }      → actual values
    //     window.map_XXXX = { ch: 'v_65b1', ri: '...' }   → key mapping
    //     Values are then: context[map['ch']], context[map['ri']], etc.
    //
    //   OLD format (fallback):
    //     window._0x_cfg = { c: 'chValue', r: 'reqId', k: 'encKeyB64', s: 'xorKey' }
    //
    // Steps:
    //   1. Load intermediate link → get session cookies
    //   2. Fetch challenge page (blog-post.html/)
    //   3. Parse challenge data (new format, fallback to old)
    //   4. XOR-decrypt the base64-encoded key using sXorKey
    //   5. HMAC-SHA256(requestId + ch + fp, secretKey)
    //   6. Wait 10s then POST to get-link.php → receive watch page URL

    private suspend fun resolveFreex2line(url: String, context: Context): String? {
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

            // Step 1: Load the intermediate (loadon/?link=...) URL to establish session
            val headResponse = app.get(url, headers = baseHeaders)
            sessionCookies.putAll(headResponse.cookies)
            val headHtml = headResponse.text
            Log.d(TAG, "   Head response length: ${headHtml.length}, URL: ${headResponse.url}")

            // Step 2: Fetch the challenge page that contains the JS variables
            Log.i("Freex2lineResolver", "[2/6] Fetching data page...")
            val pageUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
            val res = app.get(pageUrl, headers = baseHeaders, cookies = sessionCookies)
            var html = res.text
            sessionCookies.putAll(res.cookies)
            Log.d(TAG, "   Blog page length: ${html.length}")

            // If the blog page doesn't have challenge data, try the head response instead
            if (!html.contains("_0x_cfg")) {
                Log.d(TAG, "   Blog page has no _0x_cfg, using head response page")
                html = headHtml
            }

            Log.i("Freex2lineResolver", "[3/6] Parsing challenge data...")

            // Try new format: window['ctx_XXXXX'] with ptr + map indirection
            val ptrMatch = reMatch(html, """window\.ptr_\w+\s*=\s*'([^']+)'""")
            val ctxName = ptrMatch
            var ch: String? = null
            var requestId: String? = null
            var encryptedKeyB64: String? = null
            var sXorKey: String? = null

            if (ctxName != null) {
                Log.d(TAG, "   Found ptr context name: $ctxName")
                // Extract the context object: window['ctx_XXXXX'] = { ... }
                val ctxJson = reMatch(html, """(?:window\[)?['\"]$ctxName['\"](?:\])?\s*=\s*\{([^}]+)\}""")
                val mapMatch = reMatch(html, """window\.map_\w+\s*=\s*\{([^}]+)\}""")

                if (ctxJson != null && mapMatch != null) {
                    val chKey = reMatch(mapMatch, """ch:\s*'([^']+)'""")
                    val riKey = reMatch(mapMatch, """ri:\s*'([^']+)'""")
                    val keKey = reMatch(mapMatch, """ke:\s*'([^']+)'""")
                    val seKey = reMatch(mapMatch, """se:\s*'([^']+)'""")

                    if (chKey != null) ch = reMatch(ctxJson, """'?$chKey'?\s*:\s*'([^']+)'""")
                    if (riKey != null) requestId = reMatch(ctxJson, """'?$riKey'?\s*:\s*'([^']+)'""")
                    if (keKey != null) encryptedKeyB64 = reMatch(ctxJson, """'?$keKey'?\s*:\s*'([^']+)'""")
                    if (seKey != null) sXorKey = reMatch(ctxJson, """'?$seKey'?\s*:\s*'([^']+)'""")

                    Log.d(TAG, "   New format: ch=$ch, ri=$requestId, ke=${encryptedKeyB64?.take(20)}..., se=$sXorKey")
                } else {
                    Log.w(TAG, "   New format: ctxJson=${ctxJson != null}, mapMatch=${mapMatch != null}")
                }
            }

            // Fallback: old _0x_cfg format (direct key-value pairs)
            if (ch == null || requestId == null || encryptedKeyB64 == null || sXorKey == null) {
                Log.d(TAG, "   New format incomplete, trying _0x_cfg fallback...")
                val cfgText = reMatch(html, "(?:var|let|const|window\\.)?\\s*_0x_cfg\\s*=\\s*\\{([^}]+)\\}")
                    ?: throw Exception("CFG object not found")
                Log.d(TAG, "   _0x_cfg content: $cfgText")

                if (ch == null) ch = reMatch(cfgText, "'?c'?:\\s*'([^']+)'")
                if (requestId == null) requestId = reMatch(cfgText, "'?r'?:\\s*'([^']+)'")
                if (encryptedKeyB64 == null) encryptedKeyB64 = reMatch(cfgText, "'?k'?:\\s*'([^']+)'")
                if (sXorKey == null) sXorKey = reMatch(cfgText, "'?s'?:\\s*'([^']+)'")

                ch = ch ?: throw Exception("ch value not found")
                requestId = requestId ?: throw Exception("requestId value not found")
                encryptedKeyB64 = encryptedKeyB64 ?: throw Exception("Encrypted key value not found")
                sXorKey = sXorKey ?: throw Exception("s (XOR key) not found")
                Log.d(TAG, "   _0x_cfg values: ch=$ch, ri=$requestId, se=$sXorKey")
            }

            Log.i("Freex2lineResolver", "[5/6] Decrypting secret key...")
            val encryptedBytes = Base64.decode(encryptedKeyB64, 0)
            val decryptedChars = encryptedBytes.mapIndexed { index, byte ->
                // XOR each byte with the corresponding sXorKey character
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
            Log.d(TAG, "   messageToSign length=${messageToSign.length}, hmac=$hmacToken")

            // Mandatory delay — the server enforces timing
            delay(10000)

            Log.i(TAG, "Sending final API request...")
            val apiUrl = "https://rm.freex2line.online/2020/02/blog-post.html/get-link.php?request_id=$requestId&hmac_token=$hmacTokenEncoded&ch=$ch&fp=$fpBase64"
            Log.d(TAG, "   API URL (sensitive values masked): request_id=${requestId.take(16)}..., ch=${ch.take(8)}...")

            // Build cookie header string from session cookies
            val cookieHeader = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val finalHeaders = mapOf(
                "User-Agent" to baseHeaders["User-Agent"]!!,
                "Referer" to "https://rm.freex2line.online/",
                "Cookie" to cookieHeader
            )

            val finalRes = app.get(apiUrl, headers = finalHeaders)
            // Strip UTF-8 BOM (\uFEFF) that some responses include before the URL
            val finalResult = finalRes.text.trim().trim('\uFEFF')
            Log.d(TAG, "   Raw response (trimmed): ${finalResult.take(80)}")

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

    // ==================== getLinkType ====================

    private fun getLinkType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mp4") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
    }

    // ==================== Utilities ====================

    /// Normalizes a URL: protocol-relative (//example.com) → https://example.com,
    /// relative paths (/path) → https://mainUrl/path.
    private fun normalizeUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val base = baseUrl.trimEnd('/')
                "$base$url"
            }
            else -> url
        }
    }

    /// Matches a regex pattern against html and returns the first capture group.
    /// Returns null if no match or if the pattern throws.
    private fun reMatch(html: String, regex: String): String? {
        return try {
            val matcher = Pattern.compile(regex).matcher(html)
            if (matcher.find()) matcher.group(1) else null
        } catch (e: Exception) {
            Log.w(TAG, "reMatch: invalid regex '$regex': ${e.message}")
            null
        }
    }

    /// Computes HMAC-SHA256 of `message` using `secret` as the key,
    /// returns the result as a Base64-encoded string (no wrapping).
    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    private fun calculateHmacSha256(message: String, secret: String): String {
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
