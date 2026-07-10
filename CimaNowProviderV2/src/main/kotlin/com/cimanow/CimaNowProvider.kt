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
import com.cloudstream.shared.webview.NavigationStep
import com.cloudstream.shared.webview.Mode
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
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
        if (query.trim().equals("test", ignoreCase = true)) {
            return listOf(
                newMovieSearchResponse("Test WebView Fallback", "https://cimanow.cc/test-webview-fallback/", TvType.Movie) {
                    this.posterUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/imgs/logo.svg"
                }
            )
        }
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
        if (url == "https://cimanow.cc/test-webview-fallback/") {
            return newMovieLoadResponse("Test WebView Fallback", url, TvType.Movie, "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-agent-kim-reactivated-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/") {
                this.posterUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/imgs/logo.svg"
            }
        }
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

        if (data.contains("agent-kim-reactivated", ignoreCase = true)) {
            Log.i("CimaNowLoadLinks", "Triggering isolated WebView test flow for: $data")
            return runIsolatedWebViewTest(data, callback)
        }

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
        } catch (e: CancellationException) {
            throw e
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

            Log.i(TAG_DP, "Waiting 12 seconds for delay bypass...")
            delay(12000)

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
            var decrypted = decryptWatchHtml(watchHtml)
            if (decrypted == null) {
                Log.w(TAG_DP, "Phase 1 (strategies) failed, trying Phase 2 (JS Sandbox)...")
                decrypted = decryptViaSandbox(watchHtml)
            }
            if (decrypted == null) {
                Log.e(TAG_DP, "Phase 2 (sandbox) failed too, falling through to WebView phase 3")
                return null
            }
            return decrypted
        } catch (e: CancellationException) {
            throw e
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
                // All domains allowed for debugging — the redirect confirmation dialog gives manual control
                allowedDomains = listOf("cimanow.cc", "freex2line.online", "rm.freex2line.online", "href.li", "viiukuhe.com", "ayhal.com"),
                destinationLockPatterns = listOf("/watching/"),
                // Extended timeout to allow manual navigation through the redirect chain
                overallTimeoutMs = 600_000L
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

        } catch (e: CancellationException) {
            throw e
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
                Log.i(TAG_FE, "loadExtractor SUCCEEDED for '$serverName' -> ${link.url.take(120)}")
                callback(link)
            }
            try {
                Log.i(TAG_FE, "Calling loadExtractor for server='$serverName' iframeUrl=$iframeUrl referer=${referer.take(100)}")
                loadExtractor(iframeUrl, referer, {}, countingCallback)
            } catch (e: Exception) {
                Log.w(TAG_FE, "loadExtractor threw for '$serverName': ${e.message}")
            }
            if (extracted) {
                Log.i(TAG_FE, "loadExtractor produced links for '$serverName', skipping HTTP fallback")
                return
            }
            Log.w(TAG_FE, "loadExtractor returned nothing for '$serverName' — trying HTTP fallback")

            Log.i(TAG_FE, "HTTP fetching iframe URL for '$serverName': $iframeUrl")
            val html = httpService.getText(iframeUrl, headers = mapOf("Referer" to referer), rewriteDomain = false)
            if (html == null) {
                Log.w(TAG_FE, "HTTP fallback returned null for '$serverName'")
                return
            }
            Log.i(TAG_FE, "HTTP fallback got ${html.length} chars for '$serverName'")
            val doc = Jsoup.parse(html, iframeUrl)

            val urls = mutableListOf<String>()
            Regex("""file:\s*["']([^"']+)["']""").findAll(html).forEach { urls.add(it.groupValues[1]) }
            Regex("""src=["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""").findAll(html).forEach { urls.add(it.groupValues[1]) }
            doc.select("source[src]").forEach { urls.add(it.attr("src")) }
            doc.select("video[src]").forEach { urls.add(it.attr("src")) }

            val baseUrl = Regex("(https?://[^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: ""
            Log.i(TAG_FE, "HTTP fallback found ${urls.size} raw URLs for '$serverName'")
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
                    Log.i(TAG_FE, "HTTP fallback EMITTED link for '$serverName': ${finalUrl.take(100)}")
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
            // Phase 1a: AtobConfigStrategy - atob() encoded config format
            try {
                val atobCfgMatcher = Pattern.compile("atob\\s*\\(\\s*'([A-Za-z0-9+/=]+)'\\s*\\)").matcher(html)
                if (atobCfgMatcher.find()) {
                    val atobCfgEncoded = atobCfgMatcher.group(1)
                    val atobCfgDecoded = String(Base64.decode(atobCfgEncoded, Base64.DEFAULT))
                    if (atobCfgDecoded.matches(Regex("^\\d+,\\d+,\\d+,\\d+,[0-9a-f]+$"))) {
                        val cfg = atobCfgDecoded.split(",")
                        val aBase = cfg[0].toInt()
                        val aModulo = cfg[1].toInt()
                        val aSubtract = cfg.getOrNull(2)?.toIntOrNull() ?: 0
                        val aBaseN = cfg.getOrNull(3)?.toIntOrNull() ?: 10
                        val aHex = cfg.getOrNull(4) ?: ""
                        val aKey = aBase + (aHex.toInt(16) % aModulo)

                        val dm = Pattern.compile("split\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)").matcher(html)
                        val aDelim = if (dm.find()) dm.group(1) else "*"

                        var payloadStr: String? = null

                        val joinM = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\.join\\(\\s*['\"]*['\"]\\s*\\)").matcher(html)
                        if (joinM.find()) {
                            val varName = joinM.group(1)
                            val arrayM = Pattern.compile("var\\s+$varName\\s*=\\s*\\[(.*?)\\];", Pattern.DOTALL).matcher(html)
                            if (arrayM.find()) {
                                val sm = Pattern.compile("'([^']*)'").matcher(arrayM.group(1) ?: "")
                                val sb = StringBuilder()
                                while (sm.find()) sb.append(sm.group(1) ?: "")
                                if (sb.isNotEmpty()) payloadStr = sb.toString()
                            }
                        }

                        if (payloadStr == null || payloadStr.length < 100) {
                            val vm = Pattern.compile("var\\s+(_[a-zA-Z0-9_]{3,10})\\s*=\\s*(.*?);", Pattern.DOTALL).matcher(html)
                            while (vm.find()) {
                                val vc = vm.group(2) ?: continue
                                if (vc.length > 500 && vc.contains(aDelim)) {
                                    val sm = Pattern.compile("'([^']*)'").matcher(vc)
                                    val sb = StringBuilder()
                                    while (sm.find()) sb.append(sm.group(1))
                                    if (sb.isNotEmpty() && sb.length > 100) {
                                        payloadStr = sb.toString()
                                        break
                                    }
                                }
                            }
                        }

                        if (payloadStr != null && payloadStr.length > 50) {
                            val parts = payloadStr.split(Regex(Pattern.quote(aDelim)))
                            val decrypted = StringBuilder()
                            for (p in parts) {
                                if (p.isBlank()) continue
                                try {
                                    val decoded = Base64.decode(p, Base64.DEFAULT)
                                    val decStr = String(decoded, Charsets.ISO_8859_1)
                                    val pIn = decStr.split('-')
                                    if (pIn.size >= 2) {
                                        val num = pIn[1].toLong(aBaseN).toInt()
                                        val fC = (num - aSubtract) xor aKey
                                        decrypted.append(fC.toChar())
                                    }
                                } catch (_: Exception) {}
                            }
                            if (decrypted.isNotBlank()) {
                                Log.d(TAG, "AtobConfig decrypt success, len: ${decrypted.length}")
                                return decrypted.toString()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AtobConfigStrategy error: ${e.message}")
            }

            // Phase 1b: Try finding _pHsh and kV (new July 2026 format)
            val pHshMatcher = Pattern.compile("var\\s+_pHsh\\s*=\\s*['\"]([0-9a-fA-F]+)['\"]").matcher(html)
            val kvMatcher = Pattern.compile("kV\\s*=\\s*(\\d+)").matcher(html)
            if (pHshMatcher.find()) {
                val pHsh = pHshMatcher.group(1)
                
                // Parse _kV calculation dynamically to handle future value changes
                val formulaMatcher = Pattern.compile("_kV\\s*=\\s*(\\d+)\\s*\\+\\s*\\(parseInt\\(_pHsh\\.substring\\(0,\\s*6\\),\\s*16\\)\\s*%\\s*(\\d+)\\)").matcher(html)
                var computedKv = 50000
                var modulo = 100000
                if (formulaMatcher.find()) {
                    computedKv = formulaMatcher.group(1).toIntOrNull() ?: 50000
                    modulo = formulaMatcher.group(2).toIntOrNull() ?: 100000
                } else if (kvMatcher.find()) {
                    computedKv = kvMatcher.group(1).toIntOrNull() ?: 50000
                }
                
                val hexPart = try { pHsh.substring(0, 6) } catch (_: Exception) { "" }
                val parsedHex = try { hexPart.toInt(16) } catch (_: Exception) { 0 }
                val keyVal = computedKv + (parsedHex % modulo)
                
                val arrayName = try { "_" + pHsh.substring(2, 7) } catch (_: Exception) { "" }
                if (arrayName.isNotEmpty()) {
                    val startKeyword = "var $arrayName"
                    val startIdx = html.indexOf(startKeyword)
                    if (startIdx != -1) {
                        val subHtml = html.substring(startIdx)
                        var endIdx = subHtml.indexOf(";var")
                        if (endIdx == -1) {
                            endIdx = subHtml.indexOf(";\nvar")
                        }
                        if (endIdx == -1) {
                            endIdx = subHtml.indexOf(";var _aArr")
                        }
                        val varBlock = if (endIdx != -1) subHtml.substring(0, endIdx) else subHtml
                        
                        // Extract all string literals inside single quotes
                        val strMatcher = Pattern.compile("'([^']*)'").matcher(varBlock)
                        val sb = StringBuilder()
                        while (strMatcher.find()) {
                            sb.append(strMatcher.group(1))
                        }
                        val arrayStr = sb.toString()
                        val parts = arrayStr.split('|')
                        
                        val decryptedChars = StringBuilder()
                        for (p in parts) {
                            if (p.isBlank()) continue
                            try {
                                val decodedBytes = Base64.decode(p, Base64.DEFAULT)
                                val decStr = String(decodedBytes, Charsets.ISO_8859_1)
                                val pIn = decStr.split('-')
                                if (pIn.size >= 2) {
                                    val num = pIn[1].toLong(36).toInt()
                                    val fC = (num - 1337) xor keyVal
                                    decryptedChars.append(fC.toChar())
                                }
                            } catch (_: Exception) {}
                        }
                        val decrypted = decryptedChars.toString()
                        if (decrypted.isNotBlank()) {
                            Log.d(TAG, "Successfully decrypted watch page using July 2026 format, length: ${decrypted.length}")
                            return decrypted
                        }
                    }
                }
            }

            // --- Fallback to old formats ---
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
                Log.d(TAG, "Key Detection (Fallback): Found _x variables, sum key: $key")
            }

            if (key == null) {
                // 2. Try _oArr sum key
                val oArrMatcher = Pattern.compile("var\\s+_oArr\\s*=\\s*\\[([\\d,\\s]+)\\]").matcher(html)
                if (oArrMatcher.find()) {
                    key = oArrMatcher.group(1).split(",").map { it.trim().toIntOrNull() ?: 0 }.sum()
                    Log.d(TAG, "Key Detection (Fallback): Found _oArr, sum key: $key")
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
                    Log.d(TAG, "Key Detection (Fallback): Found _dk1 and _dk2, diff key: $key")
                }
            }

            if (key == null) {
                Log.e(TAG, "decryptWatchHtml (Fallback): Key not found")
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
                Log.e(TAG, "decryptWatchHtml (Fallback): Base64 variable not found")
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
                Log.e(TAG, "decryptWatchHtml (Fallback): Failed to get first digits for operator auto-detection")
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
            Log.d(TAG, "Operator Detection (Fallback): useXor=$useXor (XOR=$valXor, SUB=$valSub)")

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
                } catch (_: Exception) {}
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

    private suspend fun decryptViaSandbox(watchHtml: String): String? {
        val TAG_SB = "CimaNowSandbox"
        Log.i(TAG_SB, "Phase 2: JS Sandbox decryption starting...")
        try {
            val scriptPattern = Pattern.compile("<script[^>]*>(.*?)</script>", Pattern.DOTALL)
            val matcher = scriptPattern.matcher(watchHtml)
            val scripts = mutableListOf<String>()
            while (matcher.find()) {
                var content = matcher.group(1)?.trim() ?: continue
                if (content.isNotBlank()) {
                    if (content.startsWith("<!--")) content = content.removePrefix("<!--").trim()
                    if (content.endsWith("-->")) content = content.removeSuffix("-->").trim()
                    if (content.startsWith("//<![CDATA[")) content = content.removePrefix("//<![CDATA[").trim()
                    if (content.endsWith("//]]>")) content = content.removeSuffix("//]]>").trim()
                    scripts.add(content)
                }
            }
            if (scripts.isEmpty()) {
                Log.e(TAG_SB, "No scripts found in watch page")
                return null
            }
            Log.d(TAG_SB, "Extracted ${scripts.size} scripts, total size: ${scripts.sumOf { it.length }}")

            val combinedJs = buildString {
                append("window.__captured='';var _ow=document.write;document.write=function(h){window.__captured+=h;};document.writeln=function(h){window.__captured+=h+'\\n';};")
                for (s in scripts) { append("try{").append(s).append("}catch(e){}") }
                append("document.write=_ow;return window.__captured;")
            }

            val result = httpService.navigationEngine.executeJsSandbox(combinedJs)
            if (result != null && result.length > 50) {
                Log.i(TAG_SB, "Sandbox decrypt succeeded, length: ${result.length}")
                return result
            } else {
                Log.w(TAG_SB, "Sandbox returned empty/short: ${result?.length ?: 0}")
            }
        } catch (e: Exception) {
            Log.e(TAG_SB, "Sandbox error: ${e.message}")
        }
        return null
    }

    // ==================== Hybrid Approach: HTTP Nav → WebView Timer ====================

    /**
     * Navigates the freex redirect chain using httpService (okhttp) directly with spoofed headers.
     *
     * Flow: loadon → redirectingfree → blog-post.html → (follow 301) → blog-post.html/ 158KB timer HTML.
     * Cookies are extracted at each hop and set into CookieManager for the WebView.
     *
     * @param movieUrl The CimaNow movie/episode page URL
     * @return The blog-post.html/ timer HTML (158KB) or null on failure
     */
    private suspend fun navigateToTimerPageViaHttp(movieUrl: String): String? {
        val TAG_HT = "CimaNowHttpNav"
        try {
            Log.i(TAG_HT, "======== [START] HTTP redirect chain navigation ========")
            Log.i(TAG_HT, "movieUrl: $movieUrl")

            // ====================== Step 1: Get movie page ======================
            Log.i(TAG_HT, "Step 1/4: Fetching movie page to extract freex URL")
            val cacheBuster = "?_ts=${System.currentTimeMillis()}"
            val fetchUrl = if (movieUrl.contains("?")) "$movieUrl&$cacheBuster" else "$movieUrl$cacheBuster"
            val rawHeaders = mapOf(
                "User-Agent" to httpService.userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            Log.d(TAG_HT, "GET $fetchUrl")
            Log.d(TAG_HT, "Headers: UA=${httpService.userAgent.take(50)}...")

            val movieResponse = httpService.getRaw(fetchUrl, headers = rawHeaders)
            val movieStatus = movieResponse.code
            val movieHeaders = movieResponse.headers
            val movieHtml = movieResponse.body?.string() ?: ""
            movieResponse.close()
            Log.i(TAG_HT, "Movie page response: HTTP $movieStatus, ${movieHtml.length} bytes")
            Log.d(TAG_HT, "Response headers: ${movieHeaders.joinToString("; ") { "${it.first}=${it.second.take(60)}" }}")

            // Extract the first freex2line URL (loadon link)
            val freexMatcher = Pattern.compile("href=[\"'](https?://[^\"']*freex2line[^\"']*)[\"']").matcher(movieHtml)
            if (!freexMatcher.find()) {
                Log.e(TAG_HT, "FATAL: No freex URL found in movie page")
                return null
            }
            val freexUrl = freexMatcher.group(1)
            Log.i(TAG_HT, "Extracted freex URL: $freexUrl")
            Log.d(TAG_HT, "freexMatcher found match in movieHtml[${freexMatcher.start()}:${freexMatcher.end()}]")

            // ====================== Step 2: Fetch loadon ======================
            Log.i(TAG_HT, "Step 2/4: Fetching loadon → $freexUrl")
            val sessionHeaders = mutableMapOf<String, String>()
            sessionHeaders["User-Agent"] = httpService.userAgent
            sessionHeaders["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

            val loadonResponse = httpService.getRaw(freexUrl, headers = sessionHeaders)
            val loadonStatus = loadonResponse.code
            val loadonBody = loadonResponse.body?.string() ?: ""
            Log.i(TAG_HT, "loadon response: HTTP $loadonStatus, ${loadonBody.length} bytes")
            Log.d(TAG_HT, "loadon body preview (200 chars): ${loadonBody.take(200)}")

            // Extract Set-Cookie headers
            val cookies = mutableMapOf<String, String>()
            for (header in loadonResponse.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    val key = header.substring(0, eqIdx)
                    cookies[key] = value
                    Log.d(TAG_HT, "Cookie from loadon: $key=$value")
                }
            }
            for (header in loadonResponse.headers("set-cookie")) {
                if (header.startsWith("Cookie=")) continue  // skip if already captured above
            }
            loadonResponse.close()

            if (cookies.isNotEmpty()) {
                sessionHeaders["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                Log.i(TAG_HT, "Tracking ${cookies.size} cookies: ${cookies.keys.joinToString(", ")}")
            } else {
                Log.w(TAG_HT, "No cookies set by loadon")
            }

            // Try to extract JS redirect URL from loadon response body for debugging
            val jsRedirectMatch = Regex("""window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""").find(loadonBody)
            if (jsRedirectMatch != null) {
                Log.d(TAG_HT, "JS redirect target in loadon: ${jsRedirectMatch.groupValues[1]}")
            } else {
                Log.d(TAG_HT, "No JS redirect found in loadon body (expected if href.li is skipped)")
            }

            // ====================== Step 3: Fetch redirectingfree ======================
            Log.i(TAG_HT, "Step 3/4: Fetching redirectingfree")
            sessionHeaders["Referer"] = freexUrl
            Log.d(TAG_HT, "GET https://rm.freex2line.online/redirectingfree/")
            Log.d(TAG_HT, "Request headers: ${sessionHeaders.entries.joinToString(", ") { "${it.key}=${it.value.take(50)}" }}")

            val redirResponse = httpService.getRaw("https://rm.freex2line.online/redirectingfree/", headers = sessionHeaders)
            val redirStatus = redirResponse.code
            val redirBody = redirResponse.body?.string() ?: ""
            Log.i(TAG_HT, "redirectingfree response: HTTP $redirStatus, ${redirBody.length} bytes")
            Log.d(TAG_HT, "redirectingfree body preview (200 chars): ${redirBody.take(200)}")

            // Extract any cookies
            var redirCookies = 0
            for (header in redirResponse.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    val key = header.substring(0, eqIdx)
                    if (key !in cookies) {
                        cookies[key] = value
                        redirCookies++
                        Log.d(TAG_HT, "New cookie from redirectingfree: $key=$value")
                    }
                }
            }
            if (redirCookies > 0) {
                Log.i(TAG_HT, "Got $redirCookies new cookies from redirectingfree")
            }

            // Update cookie header
            if (cookies.isNotEmpty()) {
                sessionHeaders["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            redirResponse.close()

            // Extract JS redirect target from redirectingfree
            val redirJsMatch = Regex("""window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""").find(redirBody)
            if (redirJsMatch != null) {
                Log.d(TAG_HT, "JS redirect target in redirectingfree: ${redirJsMatch.groupValues[1]}")
            } else {
                Log.w(TAG_HT, "No JS redirect found in redirectingfree body")
                Log.d(TAG_HT, "redirectingfree full body: $redirBody")
            }

            // ====================== Step 4: Fetch blog-post.html ======================
            Log.i(TAG_HT, "Step 4/4: Fetching blog-post.html (with 301 redirect to blog-post.html/)")
            sessionHeaders["Referer"] = "https://rm.freex2line.online/redirectingfree/"
            sessionHeaders["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"

            Log.d(TAG_HT, "GET https://rm.freex2line.online/2020/02/blog-post.html")
            Log.d(TAG_HT, "Request headers: ${sessionHeaders.entries.joinToString(", ") { "${it.key}=${it.value.take(50)}" }}")

            val blogResponse = httpService.getRaw("https://rm.freex2line.online/2020/02/blog-post.html", headers = sessionHeaders)
            val blogStatus = blogResponse.code
            val blogFinalUrl = blogResponse.request.url.toString()
            val blogBody = blogResponse.body?.string() ?: ""
            Log.i(TAG_HT, "blog-post.html response: HTTP $blogStatus, ${blogBody.length} bytes, finalUrl=$blogFinalUrl")
            Log.d(TAG_HT, "blog-post.html body preview (200 chars): ${blogBody.take(200)}")

            // Check if we got the timer page (expecting ~158KB)
            when {
                blogBody.length > 100000 -> Log.i(TAG_HT, "✅ Timer page detected! Body is ${blogBody.length} bytes (expected ~158KB)")
                blogBody.length > 10000 -> Log.w(TAG_HT, "⚠️ Timer page seems smaller than expected: ${blogBody.length} bytes")
                blogBody.length < 1000 -> {
                    Log.e(TAG_HT, "❌ Timer page too small (${blogBody.length} bytes). Response may be blocked or empty.")
                    Log.d(TAG_HT, "Full body: $blogBody")
                }
            }

            // Extract any additional cookies
            for (header in blogResponse.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    val key = header.substring(0, eqIdx)
                    if (key !in cookies) {
                        cookies[key] = value
                        Log.d(TAG_HT, "New cookie from blog-post: $key=$value")
                    }
                }
            }
            blogResponse.close()

            // ====================== Set cookies in CookieManager ======================
            if (cookies.isNotEmpty()) {
                Log.i(TAG_HT, "Setting ${cookies.size} cookies in CookieManager for rm.freex2line.online")
                val cm = android.webkit.CookieManager.getInstance()
                var setCount = 0
                for ((key, value) in cookies) {
                    cm.setCookie("https://rm.freex2line.online", "$key=$value; domain=.rm.freex2line.online")
                    setCount++
                }
                cm.flush()
                Log.i(TAG_HT, "Flushed $setCount cookies to CookieManager")
            } else {
                Log.w(TAG_HT, "No cookies to set in CookieManager")
            }

            // ====================== Validate the HTML ======================
            // Check for key markers that indicate it's the real timer page
            val hasCountdown = blogBody.contains("countdown") || blogBody.contains("setInterval") || blogBody.contains("setTimeout")
            val hasGetLink = blogBody.contains("get-link.php")
            val hasDownloadBtn = blogBody.contains("downloadbtn") || blogBody.contains("download-btn") || blogBody.contains("download_btn")
            Log.i(TAG_HT, "Validation: hasCountdown=$hasCountdown, hasGetLink=$hasGetLink, hasDownloadBtn=$hasDownloadBtn")

            if (blogBody.length < 5000) {
                Log.e(TAG_HT, "❌ Timer page body too short (${blogBody.length}), likely blocked by CF")
                Log.d(TAG_HT, "Dumping blog-post body for analysis:\n$blogBody")
                return null
            }

            Log.i(TAG_HT, "======== [END] HTTP redirect chain SUCCESS ========")
            return blogBody
        } catch (e: Exception) {
            Log.e(TAG_HT, "EXCEPTION in HTTP navigation: ${e.message}")
            Log.e(TAG_HT, "Stack: ${e.stackTrace?.joinToString("\n") { "  at $it" }}")
        }
        Log.i(TAG_HT, "======== [END] HTTP redirect chain FAILED ========")
        return null
    }

    private val JS_COMBINED_DIAG_EXTRACT = """
(function(){
    try {
        // --- HELPERS (avoid querySelectorAll / getElementsByTagName -- page patches them) ---
        function byTag(el,tag){
            var r=[];if(!el)return r;
            var c=el.firstChild;
            while(c){if(c.nodeType===1&&c.tagName===tag)r.push(c);c=c.nextSibling;}
            return r;
        }
        function children(el){
            var r=[];if(!el)return r;
            var c=el.firstChild;
            while(c){if(c.nodeType===1)r.push(c);c=c.nextSibling;}
            return r;
        }
        function attr(el,name){try{return el.getAttribute(name)}catch(e){return''}}

        // --- EXTRACTION DATA ---
        var watch=document.querySelector('#watch');
        var items=children(watch);
        var servers=[];
        for(var i=0;i<items.length;i++){
            if(items[i].tagName!=='LI')continue;
            servers.push({index:attr(items[i],'data-index')||attr(items[i],'data-idx')||'',id:attr(items[i],'data-id')||'',name:(items[i].textContent||'').trim().slice(0,50)});
        }

        var allLinks=[],el=document.querySelector('body'),c=el.firstChild;
        function walk(n){if(!n)return;if(n.nodeType===1){if(n.tagName==='A')allLinks.push(n);var ch=n.firstChild;while(ch){walk(ch);ch=ch.nextSibling;}}}
        walk(el);
        var hosts=['jetload','forafile','vk.com/doc','frdl.my','bysetayico','href.li'];
        var downloads=[];
        for(var i=0;i<allLinks.length;i++){
            var a=allLinks[i],h=attr(a,'href')||'';if(!h||h==='#'||h.indexOf('http')!==0)continue;
            var p=a.parentElement;if(!p)continue;
            var pid=attr(p,'id')||'',pl=attr(p,'aria-label')||'';
            var isQ=(pl==='quality'||pl==='q_hidden'),isD=(pid==='download'||pid==='d_hidden'||pl==='download');
            if(!isQ&&!isD){var hl=h.toLowerCase(),hit=false;for(var k=0;k<hosts.length;k++){if(hl.indexOf(hosts[k])!==-1){hit=true;break;}}if(!hit)continue;}
            downloads.push({name:(a.textContent||'').trim().slice(0,50),url:h});
        }

        var iframes=[];
        function walkIframes(n){if(!n)return;if(n.nodeType===1){if(n.tagName==='IFRAME'){var s=attr(n,'data-src')||n.src||'';if(s&&s.indexOf('about:blank')===-1)iframes.push(s);}var ch=n.firstChild;while(ch){walkIframes(ch);ch=ch.nextSibling;}}}
        walkIframes(document.querySelector('body'));

        // --- DIAGNOSTIC ---
        var qualityCount=0;
        for(var i=0;i<allLinks.length;i++){
            var ap=allLinks[i].parentElement;if(ap){var apl=attr(ap,'aria-label')||'';if(apl==='quality'||apl==='q_hidden')qualityCount++;}
        }
        var diag={
            url:window.location.href,
            ua:navigator.userAgent,
            cookie:(document.cookie||'').slice(0,300),
            hasSwal:typeof window.Swal !== 'undefined',
            hasJQuery:typeof window.jQuery !== 'undefined',
            hasJQueryCookie:(typeof window.jQuery !== 'undefined' && typeof window.jQuery.cookie !== 'undefined'),
            watchItems:items.length+qualityCount,
            allSpans:items.length,
            watchHtml:watch?(watch.innerHTML.slice(0,1200)||'no_watch'):'no_watch',
            visibleDialogs:0,dialogButtons:[],swal2Confirm:'',swal2Title:'',swal2Html:'',ifrList:[]
        };
        if(watch){var modals=watch.querySelectorAll('.swal2-container,.swal2-modal,.swal2-popup,.modal,.popup');for(var m=0;m<modals.length;m++){var mr=modals[m].getBoundingClientRect();if(mr.width>0&&mr.height>0&&modals[m].offsetParent!==null){diag.visibleDialogs++;var conf=modals[m].querySelector('.swal2-confirm');if(conf)diag.swal2Confirm=(conf.innerText||conf.textContent||'').trim().slice(0,30);var ttl=modals[m].querySelector('.swal2-title');if(ttl)diag.swal2Title=(ttl.innerText||ttl.textContent||'').trim().slice(0,60);var hc=modals[m].querySelector('.swal2-html-container');if(hc)diag.swal2Html=(hc.innerText||hc.textContent||'').trim().slice(0,120);var btns=modals[m].querySelectorAll('button,a,[role="button"],.swal2-confirm');for(var b=0;b<btns.length;b++){var t=(btns[b].innerText||btns[b].textContent||'').trim();if(t.length>0&&t.length<40)diag.dialogButtons.push(t);}}}}
        for(var i=0;i<iframes.length&&i<8;i++){diag.ifrList.push(iframes[i].slice(0,120));}

        // --- SERIALIZE (use direct string concat to avoid JSON.stringify monkey-patching) ---
        function j(v){
            if(v===null||v===undefined)return'null';
            if(typeof v==='string')return'"'+v.replace(/\\/g,'\\\\').replace(/"/g,'\\"').replace(/\n/g,'\\n').replace(/\r/g,'\\r').replace(/\t/g,'\\t')+'"';
            if(typeof v==='number'||typeof v==='boolean')return''+v;
            if(Array.isArray(v)){var a=[];for(var i=0;i<v.length;i++)a.push(j(v[i]));return'['+a.join(',')+']';}
            if(typeof v==='object'){var k=Object.keys(v),p=[];for(var i=0;i<k.length;i++){p.push(j(k[i])+':'+j(v[k[i]]));}return'{'+p.join(',')+'}';}
            return'null';
        }
        var res={servers:servers,downloads:downloads,iframes:iframes,diag:diag};
        return 'DIAG_JSON:'+j(res);
    } catch(e) {
        return 'diag_error:'+e.message;
    }
})();
""".trimIndent()

    private suspend fun runIsolatedWebViewTest(
        movieUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG_TEST = "CimaNowIsolatedTest"
        Log.i(TAG_TEST, "========== [START] HYBRID WebView Test Flow ==========")
        Log.i(TAG_TEST, "Target URL: $movieUrl")

        try {
            val userAgent = httpService.userAgent

            // ======================== PHASE 1: HTTP navigation ========================
            Log.i(TAG_TEST, "PHASE 1: Navigating redirect chain via httpService (OkHttp)...")
            val timerHtml = navigateToTimerPageViaHttp(movieUrl)
            if (timerHtml == null) {
                Log.e(TAG_TEST, "❌ PHASE 1 FAILED: Could not fetch timer page HTML via HTTP")
                return false
            }
            Log.i(TAG_TEST, "✅ PHASE 1 SUCCESS: Got timer page HTML (${timerHtml.length} bytes)")
            Log.d(TAG_TEST, "Timer HTML first 300 chars: ${timerHtml.take(300)}")
            Log.d(TAG_TEST, "Timer HTML last 100 chars: ...${timerHtml.takeLast(100)}")

            // Log key markers in the HTML
            for (marker in listOf("countdown", "get-link.php", "downloadbtn", "request_id", "hmac", "_0x_cfg", "setInterval", "setTimeout")) {
                val idx = timerHtml.indexOf(marker)
                if (idx >= 0) {
                    Log.d(TAG_TEST, "Marker '$marker' found at position $idx, context: ...${timerHtml.substring(maxOf(0, idx - 30), minOf(timerHtml.length, idx + 80))}...")
                } else {
                    Log.w(TAG_TEST, "Marker '$marker' NOT found in timer HTML")
                }
            }

            // ======================== PHASE 2: WebView rendering ========================
            Log.i(TAG_TEST, "PHASE 2: Rendering timer HTML in WebView via loadDataWithBaseURL...")
            val baseUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
            val referer = "https://rm.freex2line.online/redirectingfree/"

            val steps = listOf(
                // Step 0: Load the timer HTML directly (no network request for the page itself,
                //          bypassing Cloudflare's sec-ch-ua: "Android WebView" check entirely)
                NavigationStep.LoadHtml(html = timerHtml, baseUrl = baseUrl, referer = referer),

                // Step 1: Navigate to the watching URL captured by the interceptor.
                //         This polls interceptedWatchingUrl every 500ms (up to 15s total),
                //         navigating immediately once the countdown timer finishes and
                //         get-link.php fires (typically ~11s). The main-frame navigation
                //         is intercepted by the request interceptor (protected domain +
                //         spoofed headers), so Cloudflare doesn't block it.
                NavigationStep.NavigateToWatchingUrl(abortOnFailure = true),

                // Step 2: Wait until the watching page has rendered server tabs with
                //         decrypted data-index attributes.
                NavigationStep.WaitForDomCondition(
                    jsCondition = "document.querySelector('#watch li[data-index]') !== null",
                    timeoutMs = 20000L,
                    pollIntervalMs = 500L,
                    abortOnFailure = true
                ),

                // Step 3: Extract servers/downloads/iframes/diag BEFORE reading outerHTML,
                //         because reading outerHTML triggers the anti-scraping script which
                //         asynchronously patches querySelectorAll/getAttribute.
                NavigationStep.ExecuteJs(javascript = JS_COMBINED_DIAG_EXTRACT, key = "combined"),

                // Step 4: Capture early outerHTML (still useful for debugging, even though
                //         anti-scraping may patch it after this point).
                NavigationStep.ExtractHtml(key = "html_watch"),

                // Step 5: Wait for SweetAlert2 to load, then dismiss any consent popup.
                NavigationStep.WaitForDomCondition(
                    jsCondition = "typeof window.Swal !== 'undefined'",
                    timeoutMs = 8000L,
                    pollIntervalMs = 300L,
                    abortOnFailure = false
                ),
                NavigationStep.ExecuteJs(javascript = WebViewFlowHelper.JS_DISMISS_CONSENT, key = "consent"),
            )

            val movieHost = try { java.net.URI(movieUrl).host } catch(_: Exception) { null }
            val allowedDomains = mutableSetOf(
                "cimanow.cc", "freex2line.online", "rm.freex2line.online",
                "href.li", "www.freex2line.online"
            )
            if (movieHost != null) {
                allowedDomains.add(movieHost)
            }
            val destinationLockRegexes = listOf(Regex("/(watch|watching)/"))

            Log.i(TAG_TEST, "Executing navigation engine in FULLSCREEN mode...")

            val navResult = httpService.navigationEngine.execute(
                steps = steps,
                userAgent = userAgent,
                mode = Mode.HEADLESS,
                overallTimeoutMs = 180000L,
                allowedDomains = allowedDomains,
                destinationLockPatterns = destinationLockRegexes
            )

            Log.i(TAG_TEST, "Navigation Result: success=${navResult.success}, error=${navResult.error}")
            Log.i(TAG_TEST, "Final URL: ${navResult.finalUrl}")
            Log.i(TAG_TEST, "HTML dumps: ${navResult.extractedHtml.keys.filter { it.startsWith("html") }.joinToString(", ")}")
            navResult.extractedHtml.filterKeys { it.startsWith("html") }.forEach { (key, html) ->
                Log.i(TAG_TEST, "  $key: ${html.length} chars")
            }

            if (!navResult.success) {
                Log.e(TAG_TEST, "Isolated flow failed: ${navResult.error}")
                return false
            }

            // ======================== EXTRACT FROM extracted_all ========================
            val watchUrl = navResult.finalUrl
            var found = false
            val foundLinks = mutableListOf<String>()
            val loggingCallback: (ExtractorLink) -> Unit = { link ->
                foundLinks.add(link.url)
                Log.i(TAG_TEST, ">>> LINK: source=${link.source} name=${link.name} quality=${link.quality} url=${link.url.take(150)} referer=${link.referer.take(80)}")
                callback(link)
            }

            // Parse combined result (diagnostic + extraction in one JSON object)
            val combinedRaw = navResult.extractedHtml["combined"] ?: ""
            var root: JSONObject? = null
            if (combinedRaw.startsWith("DIAG_JSON:")) {
                val jsonStr = combinedRaw.removePrefix("DIAG_JSON:")
                Log.w(TAG_TEST, "Combined JSON: " + jsonStr.take(2000))
                try {
                    root = JSONObject(jsonStr)
                    val diag = root.optJSONObject("diag")
                    if (diag != null) {
                        Log.d(TAG_TEST, "DIAG: watchItems=${diag.optInt("watchItems")}, hasSwal=${diag.optBoolean("hasSwal")}, allSpans=${diag.optInt("allSpans")}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG_TEST, "Error parsing combined JSON: ${e.message}")
                }
            } else if (combinedRaw.isNotBlank()) {
                Log.w(TAG_TEST, "Combined raw start: ${combinedRaw.take(200)}")
            }

            if (root != null) {

                    // 1. Process servers — call core.php for each to get iframe URLs
                    val servers = root.optJSONArray("servers")
                    if (servers != null && servers.length() > 0) {
                        Log.i(TAG_TEST, "Found ${servers.length()} servers from JS extraction")
                        val cookieString = navResult.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        val coreHeaders = mutableMapOf(
                            "Referer" to watchUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                        if (cookieString.isNotBlank()) {
                            coreHeaders["Cookie"] = cookieString
                        }
                        coroutineScope {
                            for (i in 0 until servers.length()) {
                                val sv = servers.getJSONObject(i)
                                val index = sv.optString("index", "").trim()
                                val id = sv.optString("id", "").trim()
                                val name = sv.optString("name", "").trim()
                                if (index.isBlank() || id.isBlank()) continue
                                launch {
                                    try {
                                        val ajaxUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$id"
                                        Log.d(TAG_TEST, "core.php GET for server '$name': index=$index id=$id")
                                        val coreText = httpService.getText(ajaxUrl, headers = coreHeaders) ?: ""
                                        val iframeMatch = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']").find(coreText)
                                        val iframeUrl = iframeMatch?.groupValues?.get(1)?.let {
                                            if (it.startsWith("//")) "https:$it" else it
                                        } ?: ""
                                        if (iframeUrl.isNotBlank() && iframeUrl != "123456789") {
                                            Log.i(TAG_TEST, "Server '$name' iframe: $iframeUrl")
                                            fallbackExtractIframe(iframeUrl, name, watchUrl, loggingCallback)
                                            found = true
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG_TEST, "core.php failed for server '$name': ${e.message}")
                                    }
                                }
                            }
                        }
                    }

                    // 2. Process download links
                    val downloads = root.optJSONArray("downloads")
                    if (downloads != null && downloads.length() > 0) {
                        Log.i(TAG_TEST, "Found ${downloads.length()} download links from JS extraction")
                        for (i in 0 until downloads.length()) {
                            val dl = downloads.getJSONObject(i)
                            val dlUrl = dl.optString("url", "")
                            val name = dl.optString("name", "").trim()
                            if (dlUrl.isBlank() || !dlUrl.startsWith("http")) continue
                            val quality = Regex("\\d+p").find(name)?.value?.let { getQualityFromName(it) }
                                ?: Qualities.Unknown.value
                            try {
                                when {
                                    dlUrl.contains("jetload.pp.ua", true) -> {
                                        handleJetload(dlUrl, quality, watchUrl, loggingCallback)
                                    }
                                    dlUrl.contains("forafile.com", true) -> {
                                        handleForafile(dlUrl, quality, watchUrl, loggingCallback)
                                    }
                                    else -> {
                                        val extractorLink = newExtractorLink("CimaNow", name.ifBlank { "CimaNow" }, dlUrl, type = getLinkType(dlUrl))
                                        extractorLink.referer = watchUrl
                                        extractorLink.quality = quality
                                        loggingCallback(extractorLink)
                                    }
                                }
                                found = true
                            } catch (e: Exception) {
                                Log.e(TAG_TEST, "Error processing download link: ${e.message}")
                            }
                        }
                    }

                    // 3. Process direct iframes (already in DOM, e.g. VK embed)
                    val iframes = root.optJSONArray("iframes")
                    if (iframes != null && iframes.length() > 0) {
                        val iframeUrls = mutableListOf<String>()
                        for (i in 0 until iframes.length()) {
                            val url = iframes.optString(i, "")
                            if (url.isNotBlank()) iframeUrls.add(url)
                        }
                        if (iframeUrls.isNotEmpty()) {
                            Log.i(TAG_TEST, "Found ${iframeUrls.size} direct iframes from JS extraction")
                            coroutineScope {
                                iframeUrls.map { url ->
                                    async {
                                        try { fallbackExtractIframe(url, "direct_embed", watchUrl, loggingCallback) }
                                        catch (e: Exception) { Log.e(TAG_TEST, "fallbackExtractIframe failed for $url: ${e.message}") }
                                    }
                                }.awaitAll()
                            }
                            found = true
                        }
                    }
            }
            if (found) {
                Log.i(TAG_TEST, "=== ALL WATCH LINKS (${foundLinks.size}) ===")
                foundLinks.forEachIndexed { i, url -> Log.i(TAG_TEST, "  [$i] $url") }
            } else {
                Log.w(TAG_TEST, "========== NOTHING FOUND ==========")
            }
            Log.i(TAG_TEST, "========== [END] Isolated WebView Test Flow, found=$found ==========")
            return found
        } catch (e: Exception) {
            Log.e(TAG_TEST, "Exception in isolated test flow: ${e.message}")
        }
        return false
    }

}

