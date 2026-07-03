package com.cimanow

import android.content.Context
import android.util.Base64
import android.widget.Toast
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.webview.WebViewFlowHelper
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
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

    private val webViewFlowHelper by lazy {
        WebViewFlowHelper(httpService.navigationEngine)
    }

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
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = httpService.getDocument("$mainUrl/?s=$encoded", rewriteDomain = true) ?: return emptyList()
        val items = getParser().parseSearch(doc)
        return items.map { item ->
            newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeaders()
            }
        }
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        return searchNormal(query)
    }

    // ==================== getMainPage ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = request.data + page
        val doc = httpService.getDocument(url, rewriteDomain = true) ?: return null
        val items = getParser().parseMainPage(doc)
        return newHomePageResponse(request.name, items.map { item ->
            val type = if (item.isMovie) TvType.Movie else TvType.TvSeries
            newMovieSearchResponse(item.title, item.url, type) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeaders()
            }
        })
    }

    // ==================== toSearchResponse ====================

    private fun selectPosterImg(element: Element): Element? {
        val img = element.selectFirst("img[data-src]")
            ?: element.selectFirst("img.lazy")
            ?: element.selectFirst("img[alt!=logo]")
            ?: element.selectFirst("img")
        return img
    }

    private fun getPosterUrl(img: Element): String {
        return img.attr("data-src").ifBlank { img.attr("src") }
    }

    private fun getTitle(element: Element, img: Element?): String? {
        val fromLi = element.selectFirst("li[aria-label='title']")?.text()
        if (!fromLi.isNullOrBlank()) return fromLi
        val fromH3 = element.selectFirst("h3 a")?.text()
        if (!fromH3.isNullOrBlank()) return fromH3
        val fromATitle = element.selectFirst("a[title]")?.attr("title")
        if (!fromATitle.isNullOrBlank()) return fromATitle
        val fromImgAlt = img?.attr("alt")
        if (!fromImgAlt.isNullOrBlank() && fromImgAlt != "logo") return fromImgAlt
        return null
    }

    private fun getHref(element: Element): String? {
        val link = element.selectFirst("a[href]")
            ?: element.parent()?.selectFirst("a[href]") ?: return null
        return link.attr("href").takeUnless { it.isBlank() || it.startsWith("#") || it.startsWith("javascript:") }
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val img = selectPosterImg(element) ?: return null
        val href = getHref(element) ?: return null
        val title = getTitle(element, img) ?: return null

        val posterUrl = getPosterUrl(img)

        val category = element.select("a[href*='/category/']").text()
        val year = element.select("a[href*='/release-year/']").text().toIntOrNull()

        val quality = null

        val type = if (category.contains("مسلسلات", true) || category.contains("موسم", true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    // ==================== load ====================

    override suspend fun load(url: String): LoadResponse? {
        val doc = httpService.getDocument(url, rewriteDomain = true) ?: return null
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
                            httpService.getDocument(seasonElement.attr("href"), rewriteDomain = true)
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
        Log.i("CimaNowLoadLinks", "================ [START LOADLINKS v7] ================")
        Log.d("CimaNowLoadLinks", "-> Data URL: $data")

        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            val decryptedHtml = fetchDecryptedWatchPage(data)
            if (!decryptedHtml.isNullOrBlank()) {
                val doc = Jsoup.parse(decryptedHtml, data)

                // 1. Extract watch servers
                val servers = doc.select("li[data-index]")
                Log.i("CimaNowLoadLinks", "Found ${servers.size} watch servers in decrypted HTML")

                val deferredList = mutableListOf<Deferred<Unit>>()
                coroutineScope {
                    servers.forEach { server ->
                        val index = server.attr("data-index")
                        val id = server.attr("data-id")
                        val serverName = server.text().trim()

                        if (index.isNotBlank() && id.isNotBlank()) {
                            val deferred = async {
                                try {
                                    val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$id"
                                    Log.d(TAG, "core.php GET: $ajaxUrl")
                                    val coreHeaders = mapOf("Referer" to data, "X-Requested-With" to "XMLHttpRequest")
                                    val coreText = httpService.getText(ajaxUrl, headers = coreHeaders) ?: ""
                                    val playerDoc = Jsoup.parse(coreText, ajaxUrl)
                                    val rawIframeUrl = playerDoc.select("iframe").attr("src") ?: ""
                                    val iframeUrl = if (rawIframeUrl.startsWith("//")) "https:$rawIframeUrl" else rawIframeUrl

                                    if (iframeUrl.isNotBlank() && iframeUrl != "123456789") {
                                        successCount.incrementAndGet()
                                        when {
                                            iframeUrl.contains("cimanowtv", true) -> {
                                                handlecima(iframeUrl, serverName, callback)
                                            }
                                            iframeUrl.contains("forafile.com", true) -> {
                                                handleForafile(iframeUrl, 0, data, callback)
                                            }
                                            else -> {
                                                fallbackExtractIframe(iframeUrl, serverName, data, callback)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error switching server index=$index: ${e.message}")
                                }
                            }
                            deferredList.add(deferred)
                        }
                    }
                    deferredList.awaitAll()
                }

                /*
                // 2. Extract download links (Commented out to prevent video player launch delays. Uncomment to re-enable)
                val downloadLinks = doc.select("#download li a[href], a[href*=download], a[href*=dl], .download-links a[href]")
                Log.i("CimaNowLoadLinks", "Found ${downloadLinks.size} download links in decrypted HTML")
                for (link in downloadLinks) {
                    try {
                        val dlink = link.attr("href")
                        val linkText = link.text().trim()
                        val quality = Regex("\\d+p").find(linkText)?.value?.let { getQualityFromName(it) }
                            ?: Qualities.Unknown.value

                        if (dlink.isNotBlank() && dlink.startsWith("http")) {
                            Log.d(TAG, "Download link found: quality=$quality url=$dlink")
                            when {
                                dlink.contains("forafile.com", true) -> {
                                    handleForafile(dlink, quality, data, callback)
                                }
                                dlink.contains("jetload.pp.ua", true) -> {
                                    handleJetload(dlink, quality, data, callback)
                                }
                                else -> {
                                    callback(newExtractorLink("CimaNow", "CimaNow", dlink, type = getLinkType(dlink)) {
                                        this.referer = data
                                        this.quality = quality
                                    })
                                }
                            }
                            successCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing download link: ${e.message}")
                    }
                }
                */
            } else {
                Log.w("CimaNowLoadLinks", "Programmatic watch HTML decryption returned empty result")
            }
        } catch (e: Exception) {
            Log.e("CimaNowLoadLinks", "Programmatic flow error: ${e.message}")
        }

        if (successCount.get() > 0) {
            Log.i("CimaNowLoadLinks", "================ [END PROGRAMMATIC SUCCESS] ================")
            return true
        }

        Log.w("CimaNowLoadLinks", "Programmatic flow failed to find links. Falling back to WebView...")
        val webViewResult = tryWebViewFallback(data, subtitleCallback, callback)
        Log.i("CimaNowLoadLinks", "================ [END WebView FALLBACK Result: $webViewResult] ================")
        return webViewResult
    }

    private suspend fun fetchDecryptedWatchPage(movieUrl: String): String? {
        val TAG_DP = "CimaNowDecryptedPage"
        try {
            Log.i(TAG_DP, "1. Fetching movie page: $movieUrl")
            val cacheBuster = "?_ts=${System.currentTimeMillis()}"
            val fetchUrl = if (movieUrl.contains("?")) "$movieUrl&$cacheBuster" else "$movieUrl$cacheBuster"
            val rawHeaders = mapOf(
                "User-Agent" to httpService.userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val movieHtml = httpService.getRaw(fetchUrl, headers = rawHeaders).body?.string() ?: return null

            val freexMatcher = Pattern.compile("href=[\"'](https?://[^\"']*freex2line[^\"']*)[\"']").matcher(movieHtml)
            if (!freexMatcher.find()) {
                Log.e(TAG_DP, "Freex URL not found in movie page")
                return null
            }
            val freexUrl = freexMatcher.group(1)
            Log.d(TAG_DP, "Found Freex URL: $freexUrl")

            val sessionHeaders = mutableMapOf<String, String>()
            sessionHeaders["User-Agent"] = httpService.userAgent

            Log.i(TAG_DP, "2. Requesting loadon: $freexUrl")
            val loadonResp = httpService.getRaw(freexUrl, headers = sessionHeaders)
            val loadonBody = loadonResp.body?.string() ?: ""

            // Extract any cookies set by loadon
            val cookies = mutableMapOf<String, String>()
            for (header in loadonResp.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    cookies[header.substring(0, eqIdx)] = value
                }
            }
            loadonResp.close()

            if (cookies.isNotEmpty()) {
                sessionHeaders["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }

            Log.i(TAG_DP, "3. Requesting redirectingfree")
            sessionHeaders["Referer"] = freexUrl
            val redirResp = httpService.getRaw("https://rm.freex2line.online/redirectingfree/", headers = sessionHeaders)
            redirResp.close()

            Log.i(TAG_DP, "4. Requesting blog-post.html")
            sessionHeaders["Referer"] = "https://rm.freex2line.online/redirectingfree/"
            val blogResp = httpService.getRaw("https://rm.freex2line.online/2020/02/blog-post.html", headers = sessionHeaders)
            val blogHtml = blogResp.body?.string() ?: ""
            blogResp.close()

            // Extract ch, ri, ke, se variables
            val chVar = reMatch(blogHtml, "ch:\\s*['\"]([^'\"]+)['\"]") ?: return null
            val riVar = reMatch(blogHtml, "ri:\\s*['\"]([^'\"]+)['\"]") ?: return null
            val keVar = reMatch(blogHtml, "ke:\\s*['\"]([^'\"]+)['\"]") ?: return null
            val seVar = reMatch(blogHtml, "se:\\s*['\"]([^'\"]+)['\"]") ?: return null

            val ctxMatcher = Pattern.compile("window\\['ctx_[^']+'\\]\\s*=\\s*\\{([^}]+)\\}").matcher(blogHtml)
            if (!ctxMatcher.find()) {
                Log.e(TAG_DP, "ctx not found in blog-post.html")
                return null
            }
            val ctxContent = ctxMatcher.group(1)
            val ch = reMatch(ctxContent, "'$chVar'\\s*:\\s*'([^']+)'") ?: return null
            val ri = reMatch(ctxContent, "'$riVar'\\s*:\\s*'([^']+)'") ?: return null
            val ke = reMatch(ctxContent, "'$keVar'\\s*:\\s*'([^']+)'") ?: return null
            val se = reMatch(ctxContent, "'$seVar'\\s*:\\s*'([^']+)'") ?: return null

            // Decrypt key
            val keDecoded = Base64.decode(ke, Base64.DEFAULT)
            val keyBytes = ByteArray(keDecoded.size)
            for (i in keDecoded.indices) {
                keyBytes[i] = (keDecoded[i].toInt() xor se[i % se.length].code).toByte()
            }
            val keyHex = String(keyBytes, Charsets.UTF_8)

            val fp = "TW96aWxsYS81Ll9f"
            val msg = ri + ch + fp
            val hmacBytes = hmacSha256(keyHex.toByteArray(Charsets.UTF_8), msg.toByteArray(Charsets.UTF_8))
            val hmacToken = Base64.encodeToString(hmacBytes, Base64.NO_WRAP)

            Log.i(TAG_DP, "Waiting 11 seconds for delay bypass...")
            delay(11000)

            Log.i(TAG_DP, "5. Requesting get-link.php")
            val ajaxUrl = "https://rm.freex2line.online/2020/02/blog-post.html/get-link.php?request_id=$ri&hmac_token=${java.net.URLEncoder.encode(hmacToken, "UTF-8")}&ch=$ch&fp=$fp"
            sessionHeaders["X-Requested-With"] = "XMLHttpRequest"
            sessionHeaders["Referer"] = "https://rm.freex2line.online/2020/02/blog-post.html/"

            val ajaxResp = httpService.getRaw(ajaxUrl, headers = sessionHeaders)
            val ajaxText = ajaxResp.body?.string() ?: ""
            ajaxResp.close()

            val watchUrl = ajaxText.trim().replace("\ufeff", "")
            Log.i(TAG_DP, "Resolved Watch URL: $watchUrl")

            Log.i(TAG_DP, "6. Fetching watch page: $watchUrl")
            sessionHeaders.remove("X-Requested-With")
            sessionHeaders["Referer"] = "https://rm.freex2line.online/2020/02/blog-post.html/"
            val watchResp = httpService.getRaw(watchUrl, headers = sessionHeaders)
            val watchHtml = watchResp.body?.string() ?: ""
            watchResp.close()

            Log.i(TAG_DP, "7. Decrypting watch page HTML")
            val decrypted = decryptWatchHtml(watchHtml)
            if (decrypted == null) {
                Log.e(TAG_DP, "Failed to decrypt watch page HTML")
                return null
            }
            return decrypted
        } catch (e: Exception) {
            Log.e(TAG_DP, "fetchDecryptedWatchPage error: ${e.message}")
        }
        return null
    }

    // ==================== WebView Fallback ====================

    private suspend fun tryWebViewFallback(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG_WV = "CimaNowWebViewFallback"
        Log.i(TAG_WV, "========== [START] WebView navigation fallback ==========")
        Log.i(TAG_WV, "URL: $data")

        try {
            val config = WebViewFlowHelper.Config(
                allowedDomains = listOf("cimanow.cc", "freex2line.online", "rm.freex2line.online", "href.li"),
                destinationLockPatterns = listOf("/watching/"),
                overallTimeoutMs = 120_000L
            )
            Log.d(TAG_WV, "Config: allowedDomains=${config.allowedDomains}, destPatterns=${config.destinationLockPatterns}, timeoutMs=${config.overallTimeoutMs}")

            Log.i(TAG_WV, "Invoking WebView navigation (this may take up to ${config.overallTimeoutMs / 1000}s)...")
            val flowResult = webViewFlowHelper.navigateMovieToWatchPage(data, config)

            if (!flowResult.success) {
                Log.w(TAG_WV, "WebView flow FAILED: ${flowResult.error}")
                Log.d(TAG_WV, "  finalUrl: ${flowResult.finalUrl.take(100)}")
                Log.d(TAG_WV, "  servers extracted: ${flowResult.servers.size}")
                return false
            }

            Log.i(TAG_WV, "WebView flow SUCCEEDED")
            Log.d(TAG_WV, "  finalUrl: ${flowResult.finalUrl.take(100)}")
            Log.d(TAG_WV, "  servers count: ${flowResult.servers.size}")
            Log.d(TAG_WV, "  downloads count: ${flowResult.downloads.size}")

            flowResult.servers.forEachIndexed { i, s ->
                Log.d(TAG_WV, "  server[$i]: name='${s.name}' index=${s.index} id=${s.id} hasIframe=${s.iframeUrl.isNotBlank()}")
                if (s.iframeUrl.isNotBlank()) {
                    Log.d(TAG_WV, "    iframeUrl: ${s.iframeUrl.take(100)}")
                }
            }
            flowResult.downloads.forEachIndexed { i, d ->
                Log.d(TAG_WV, "  download[$i]: name='${d.name}' url=${d.url.take(100)}")
            }

            var found = false
            val referer = flowResult.finalUrl.ifBlank { data }

            for (server in flowResult.servers) {
                if (server.iframeUrl.isNotBlank()) {
                    Log.i(TAG_WV, "Processing server '${server.name}' ...")
                    try {
                        val iframeUrl = server.iframeUrl
                        when {
                            iframeUrl.contains("cimanowtv", true) -> {
                                Log.d(TAG_WV, "  -> routing to handlecima")
                                handlecima(iframeUrl, server.name, callback)
                            }
                            iframeUrl.contains("forafile.com", true) -> {
                                Log.d(TAG_WV, "  -> routing to handleForafile")
                                handleForafile(iframeUrl, 0, referer, callback)
                            }
                            else -> {
                                Log.d(TAG_WV, "  -> routing to fallbackExtractIframe (referer=$referer)")
                                fallbackExtractIframe(iframeUrl, server.name, referer, callback)
                            }
                        }
                        found = true
                    } catch (e: Exception) {
                        Log.e(TAG_WV, "  ERROR processing server '${server.name}': ${e.message}")
                    }
                } else {
                    Log.d(TAG_WV, "Server '${server.name}' has empty iframeUrl, skipping")
                }
            }

            for (download in flowResult.downloads) {
                if (download.url.isNotBlank()) {
                    Log.i(TAG_WV, "Processing download '${download.name}' -> ${download.url.take(100)}")
                    callback(
                        newExtractorLink(download.name, download.name, download.url, type = getLinkType(download.url))
                    )
                    found = true
                }
            }

            Log.i(TAG_WV, "========== [END] WebView fallback, found=$found ==========")
            return found

        } catch (e: Exception) {
            Log.e(TAG_WV, "WebView fallback EXCEPTION: ${e.message}")
            Log.e(TAG_WV, "Stack: ${e.stackTrace?.joinToString("\n") { "  at $it" }}")
            return false
        }
    }

    // ==================== processServerElement ====================

    private suspend fun processServerElement(
        serverElement: Element,
        finalCimaNowUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_PS = "ProcessSrvElement"
        Log.w(TAG_PS, "Called but should be dead code (v5 bypasses watch page)")
        try {
            val dataIndex = serverElement.attr("data-index")
            val dataId = serverElement.attr("data-id")
            val serverName = serverElement.text().trim()

            Log.i(TAG_PS, "Server: name='$serverName', index=$dataIndex, id=$dataId")

            val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$dataIndex&id=$dataId"
            val playerDoc = try {
                httpService.getDocument(ajaxUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"), rewriteDomain = true)
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
        val TAG_CI = "CimaNowExtractor"
        Log.i(TAG_CI, "handlecima: iframe=$iframeUrl")
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            Log.d(TAG_CI, "Fetching cimanow iframe page: $finalUrl")
            val iframeResponse = httpService.getText(finalUrl, headers = mapOf("Referer" to finalUrl), rewriteDomain = false) ?: ""
            Log.d(TAG_CI, "Iframe response size: ${iframeResponse.length} bytes")

            val regex = Regex("\\[(\\d+p)]\\s+(/uploads/[^\"]+\\.mp4)")
            val baseUrlMatch = Regex("(https?://[^/]+)").find(finalUrl)
            val baseUrl = baseUrlMatch?.groupValues?.get(1) ?: ""
            Log.d(TAG_CI, "Base URL for mp4: $baseUrl")

            val links = mutableListOf<ExtractorLink>()

            for (match in regex.findAll(iframeResponse)) {
                val qualityStr = match.groupValues[1]
                val filePath = match.groupValues[2]
                val videoUrl = baseUrl + filePath
                Log.d(TAG_CI, "Found quality link: $qualityStr -> $videoUrl")

                val link = newExtractorLink("CimaNow", "CimaNow", videoUrl, type = getLinkType(videoUrl))
                link.quality = getQualityFromName(qualityStr)
                link.referer = finalUrl
                links.add(link)
            }

            if (links.size > 1) {
                links.sortByDescending { it.quality }
            }

            if (links.isEmpty()) {
                Log.w(TAG_CI, "No quality links found in response. Preview: ${iframeResponse.take(200)}")
            } else {
                for (link in links) {
                    Log.i(TAG_CI, "Reporting quality link: ${link.quality}p -> ${link.url}")
                    callback(link)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_CI, "handlecima error: ${e.message}")
            Log.e(TAG_CI, "Stack: ${e.stackTrace?.joinToString("\n") { "  at $it" }}")
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
            "User-Agent" to httpService.userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar-EG,ar;q=0.9"
        )

        try {
            val res1 = httpService.getRaw(url, headers = headers)
            res1.close()
            val sessionCookies = mutableMapOf<String, String>()
            for (header in res1.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    sessionCookies[header.substring(0, eqIdx)] = value
                }
            }

            val headers2 = headers + ("Referer" to url)
            val targetUrl = "https://jetload.pp.ua/Jetload4/"
            val cookieHeader = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val res2 = httpService.getRaw(targetUrl, headers = headers2 + ("Cookie" to cookieHeader))
            val html = res2.body?.string() ?: return
            res2.close()
            for (header in res2.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    sessionCookies[header.substring(0, eqIdx)] = value
                }
            }

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
            val cookieHdr = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val finalResp = httpService.getRaw(ajaxUrl, headers = ajaxHeaders + ("Cookie" to cookieHdr))
            val rawLink = finalResp.body?.string()?.trim() ?: return
            finalResp.close()

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
                "User-Agent" to httpService.userAgent,
                "Referer" to url
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

            val formBody = okhttp3.FormBody.Builder().apply {
                for ((k, v) in data) { add(k, v) }
            }.build()
            val headerBuilder = okhttp3.Headers.Builder()
            for ((k, v) in headers) { headerBuilder.add(k, v) }
            val okRequest = okhttp3.Request.Builder()
                .url(baseUrl)
                .headers(headerBuilder.build())
                .post(formBody)
                .build()
            val client = app.baseClient.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val response = client.newCall(okRequest).execute()
            val location = response.header("location") ?: response.header("Location")
            response.close()

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

    // ==================== fallbackExtractIframe ====================

    private suspend fun fallbackExtractIframe(
        iframeUrl: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_FE = "FallbackExtract"
        try {
            var extracted = false
            val countingCallback: (ExtractorLink) -> Unit = { link ->
                extracted = true
                callback(link)
            }
            try {
                loadExtractor(iframeUrl, referer, {}, countingCallback)
            } catch (_: Exception) {}
            if (extracted) return

            val html = httpService.getText(iframeUrl, headers = mapOf("Referer" to referer), rewriteDomain = false) ?: return
            val doc = Jsoup.parse(html, iframeUrl)

            val urls = mutableListOf<String>()
            Regex("""file:\s*["']([^"']+)["']""").findAll(html).forEach { urls.add(it.groupValues[1]) }
            Regex("""src=["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""").findAll(html).forEach { urls.add(it.groupValues[1]) }
            doc.select("source[src]").forEach { urls.add(it.attr("src")) }
            doc.select("video[src]").forEach { urls.add(it.attr("src")) }

            val baseUrl = Regex("(https?://[^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: ""
            for (url in urls.distinct()) {
                val finalUrl = when {
                    url.startsWith("http") -> url
                    url.startsWith("//") -> "https:$url"
                    url.startsWith("/") -> "$baseUrl$url"
                    else -> "$baseUrl/$url"
                }
                if (finalUrl.contains(".mp4") || finalUrl.contains(".m3u8")) {
                    val link = newExtractorLink(serverName, serverName, finalUrl, type = getLinkType(finalUrl))
                    link.referer = iframeUrl
                    callback(link)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_FE, "Error extracting from $serverName: ${e.message}")
        }
    }

    // ==================== resolveFreex2line ====================

    private suspend fun resolveFreex2line(url: String): String? {
        val TAG_FX = "Freex2lineResolver"
        Log.i(TAG_FX, "======= [START] =======")
        Log.d(TAG_FX, "Input URL: $url")

        try {
            val linkParam = url.substringAfter("link=", "")
            Log.d(TAG_FX, "Extracted link param (base64): ${linkParam.take(50)}...")

            if (linkParam.isBlank()) {
                Log.e(TAG_FX, "No 'link' parameter found in URL: $url")
                return null
            }

            val decodedBytes = Base64.decode(linkParam, Base64.DEFAULT)
            val decodedUrl = String(decodedBytes, Charsets.UTF_8)
            Log.d(TAG_FX, "Decoded raw: $decodedUrl")

            if (decodedUrl.startsWith("http")) {
                Log.i(TAG_FX, "[SUCCESS] Resolved URL: $decodedUrl")
                return decodedUrl
            }

            Log.e(TAG_FX, "Decoded value is not a valid URL: $decodedUrl")
        } catch (e: Exception) {
            Log.e(TAG_FX, "Exception: ${e.message}")
            Log.e(TAG_FX, "Stack: ${e.stackTrace?.joinToString("\n") { "  at $it" }}")
        }

        Log.i(TAG_FX, "======= [FAILED] =======")
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

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data)
    }

    /**
     * Decrypts the watch page HTML (from /watching/?token=...) containing the server lists and iframes.
     * The watch HTML contains an encrypted string consisting of base64 blocks split by '@'.
     *
     * How to debug/fix when the decryption method changes:
     * 1. Check the dynamic key computation:
     *    - Currently, the key is the sum of integer elements inside `var _oArr = [...]` (e.g. 39597 + 39598 + 39597 = 118792).
     *    - Fallback: Key is calculated as `_dk1 - _dk2` if they are present.
     *    - If they change the variable name `_oArr`, update the regex matching the key array.
     * 2. Check the encrypted data format:
     *    - Currently, it extracts the variable containing the string with '@' separators (e.g., var _b5178 = 'base64@base64@...').
     *    - It splits by '@', base64 decodes each block, extracts all digit characters, parses them as an integer, XORs it with the key, and converts it to a character.
     *    - If they change the separator '@' or base64 layout, adjust the splitting and parsing loop accordingly.
     * 3. Look at the Android Logcat under the tags:
     *    - "CimaNowDecryptedPage": For redirection, HMAC token generation, and decryption status logs.
     *    - "CimaNowLoadLinks": For parsing success counts and failover WebView triggers.
     */
    private fun decryptWatchHtml(html: String): String? {
        try {
            var key: Int? = null

            // 1. Try _x\d+ dynamic sum key first (latest logic)
            val xVarMatcher = Pattern.compile("var\\s+(_x\\d+)\\s*=\\s*(\\d+)").matcher(html)
            var xSum = 0
            var hasXVars = false
            while (xVarMatcher.find()) {
                xSum += xVarMatcher.group(2).toIntOrNull() ?: 0
                hasXVars = true
            }
            if (hasXVars) {
                key = xSum
                Log.d(TAG, "Key Detection: Found _x variables, sum key: $key")
            }

            if (key == null) {
                // 2. Try _oArr sum key
                val oArrMatcher = Pattern.compile("var\\s+_oArr\\s*=\\s*\\[([\\d,\\s]+)\\]").matcher(html)
                if (oArrMatcher.find()) {
                    key = oArrMatcher.group(1).split(",").map { it.trim().toIntOrNull() ?: 0 }.sum()
                    Log.d(TAG, "Key Detection: Found _oArr, sum key: $key")
                }
            }

            if (key == null) {
                // 3. Try _dk1 and _dk2
                val dk1Matcher = Pattern.compile("var\\s+_dk1\\s*=\\s*(\\d+);").matcher(html)
                val dk2Matcher = Pattern.compile("var\\s+_dk2\\s*=\\s*(\\d+);").matcher(html)
                if (dk1Matcher.find() && dk2Matcher.find()) {
                    val dk1 = dk1Matcher.group(1).toIntOrNull() ?: 0
                    val dk2 = dk2Matcher.group(1).toIntOrNull() ?: 0
                    key = dk1 - dk2
                    Log.d(TAG, "Key Detection: Found _dk1 and _dk2, diff key: $key")
                }
            }

            if (key == null) {
                Log.e(TAG, "decryptWatchHtml: Key not found")
                return null
            }

            // Find base64 variable containing '*' or '@'
            val varMatcher = Pattern.compile("var\\s+(_[a-zA-Z0-9_]{3,10})\\s*=\\s*(.*?);", Pattern.DOTALL).matcher(html)
            var rawVal = ""
            var delimiter = "@"
            while (varMatcher.find()) {
                val valContent = varMatcher.group(2)
                if (valContent.length > 1000) {
                    if (valContent.contains("*")) {
                        rawVal = valContent
                        delimiter = "*"
                        break
                    } else if (valContent.contains("@")) {
                        rawVal = valContent
                        delimiter = "@"
                        break
                    }
                }
            }

            if (rawVal.isBlank()) {
                Log.e(TAG, "decryptWatchHtml: Base64 variable not found")
                return null
            }

            // Extract string literals
            val strMatcher = Pattern.compile("'([^']*)'").matcher(rawVal)
            val sb = StringBuilder()
            while (strMatcher.find()) {
                sb.append(strMatcher.group(1))
            }

            val parts = sb.toString().split(delimiter)
            
            // 4. Operator Auto-detection (XOR vs Subtraction)
            var firstDigits: Int? = null
            for (p in parts) {
                if (p.isNotBlank()) {
                    try {
                        val decodedBytes = Base64.decode(p, Base64.DEFAULT)
                        val decStr = String(decodedBytes, Charsets.ISO_8859_1)
                        val digits = decStr.filter { it.isDigit() }
                        if (digits.isNotEmpty()) {
                            firstDigits = digits.toInt()
                            break
                        }
                    } catch (_: Exception) {}
                }
            }

            if (firstDigits == null) {
                Log.e(TAG, "decryptWatchHtml: Failed to get first digits for operator auto-detection")
                return null
            }

            val valXor = firstDigits xor key
            val valSub = firstDigits - key
            val useXor = if (valXor in 10..127) {
                true
            } else if (valSub in 10..127) {
                false
            } else {
                true // Fallback to XOR
            }
            Log.d(TAG, "Operator Detection: useXor=$useXor (XOR=$valXor, SUB=$valSub)")

            val decryptedChars = StringBuilder()

            for (p in parts) {
                if (p.isBlank()) continue
                try {
                    val decodedBytes = Base64.decode(p, Base64.DEFAULT)
                    val decStr = String(decodedBytes, Charsets.ISO_8859_1)
                    val digits = decStr.filter { it.isDigit() }
                    if (digits.isNotEmpty()) {
                        val num = digits.toInt()
                        val code = if (useXor) (num xor key) else (num - key)
                        decryptedChars.append(code.toChar())
                    }
                } catch (_: Exception) {
                }
            }

            val decrypted = decryptedChars.toString()
            if (decrypted.isBlank()) return null

            val bytes = ByteArray(decrypted.length)
            for (i in 0 until decrypted.length) {
                bytes[i] = decrypted[i].code.toByte()
            }
            return String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decryptWatchHtml error: ${e.message}")
        }
        return null
    }

}
