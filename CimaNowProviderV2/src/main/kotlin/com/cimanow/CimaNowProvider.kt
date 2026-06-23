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
        val doc = httpService.getDocument("$mainUrl/?s=$encoded") ?: return emptyList()
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
        val doc = httpService.getDocument(url) ?: return null
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
        val doc = httpService.getDocument(url) ?: return null
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
                            httpService.getDocument(seasonElement.attr("href"))
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
        Log.i("CimaNowLoadLinks", "================ [START LOADLINKS v6] ================")
        Log.d("CimaNowLoadLinks", "-> Data URL: $data")

        try {
            Log.i("CimaNowLoadLinks", "[1/6] Fetching initial movie page via httpService.getRaw...")
            val cacheBuster = "?_ts=${System.currentTimeMillis()}"
            val fetchUrl = if (data.contains("?")) "$data&$cacheBuster" else "$data$cacheBuster"
            Log.d(TAG, "   Fetch URL (with cache buster): $fetchUrl")
            val rawHeaders = mapOf(
                "User-Agent" to httpService.userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val rawResp = httpService.getRaw(fetchUrl, headers = rawHeaders)
            val respCode = rawResp.code
            val respBody = rawResp.body?.string() ?: throw ErrorLoadingException("Empty response body")
            val moviePageDoc = Jsoup.parse(respBody, fetchUrl)
            val movieTitle = moviePageDoc.title()
            Log.d(TAG, "   Doc title: $movieTitle")
            Log.d(TAG, "   Doc HTML size: ${respBody.length} bytes")
            Log.d(TAG, "   Response status: $respCode")

            val cleanTitle = movieTitle.substringBefore("|").trim()
            Log.d(TAG, "   Cleaned title for search: $cleanTitle")

            Log.i("CimaNowLoadLinks", "[2/6] Extracting post ID from movie page HTML...")
            var postId = run {
                // Scan movie page HTML for WordPress post ID patterns (most reliable)
                val bodyClass = Regex("""postid[-\s]*(\d+)""", RegexOption.IGNORE_CASE).find(respBody)
                if (bodyClass != null) {
                    Log.i(TAG, "   Post ID found via body class: ${bodyClass.groupValues[1]}")
                    bodyClass.groupValues[1]
                } else {
                    val articleId = Regex("""<article[^>]*\sid\s*=\s*["']post[-\s]*(\d+)["']""", RegexOption.IGNORE_CASE).find(respBody)
                    if (articleId != null) {
                        Log.i(TAG, "   Post ID found via article id: ${articleId.groupValues[1]}")
                        articleId.groupValues[1]
                    } else {
                        val dataPostId = Regex("""data-post-id\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE).find(respBody)
                        if (dataPostId != null) {
                            Log.i(TAG, "   Post ID found via data-post-id: ${dataPostId.groupValues[1]}")
                            dataPostId.groupValues[1]
                        } else {
                            val jsPostId = Regex("""post_id\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(respBody)
                            if (jsPostId != null) {
                                Log.i(TAG, "   Post ID found via JS variable: ${jsPostId.groupValues[1]}")
                                jsPostId.groupValues[1]
                            } else {
                                val hiddenInput = Regex("""<input[^>]*name=["']post(?:_id)?["'][^>]*value=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(respBody)
                                if (hiddenInput != null) {
                                    Log.i(TAG, "   Post ID found via hidden input: ${hiddenInput.groupValues[1]}")
                                    hiddenInput.groupValues[1]
                                } else {
                                    // Fallback: search RSS feed for ?p= pattern
                                    Log.w(TAG, "   No post ID found in HTML — trying RSS feed fallback...")
                                    val searchTerm = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
                                    val feedUrl = "$mainUrl/feed/?s=$searchTerm"
                                    Log.d(TAG, "   Feed search URL: $feedUrl")
                                    val feedXml = httpService.getText(feedUrl)
                                    Log.d(TAG, "   Feed response size: ${feedXml?.length ?: 0} bytes")
                                    if (feedXml != null) Log.d(TAG, "   Feed content dump:\n${feedXml.take(1000)}")

                                    val fromRss = feedXml?.let { Regex("[?&]p=(\\d+)").find(it)?.groupValues?.get(1) }
                                    if (fromRss != null) {
                                        Log.i(TAG, "   Post ID found via RSS: $fromRss")
                                        fromRss
                                    } else {
                                        Log.w(TAG, "   RSS feed has no ?p= — trying REST API fallback...")
                                        val slug = data.trimEnd('/').substringAfterLast('/')
                                        Log.d(TAG, "   Extracted slug from URL: $slug")
                                        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?slug=$slug"
                                        Log.d(TAG, "   REST API URL: $apiUrl")
                                        val apiResult = httpService.getText(apiUrl, headers = mapOf("Accept" to "application/json"))
                                        Log.d(TAG, "   REST API response (first 1000): ${apiResult?.take(1000)}")
                                        val fromApi = apiResult?.let { json ->
                                            Regex(""""id"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)
                                        }
                                        if (fromApi != null) {
                                            Log.i(TAG, "   Post ID found via REST API: $fromApi")
                                            fromApi
                                        } else {
                                            Log.w(TAG, "   REST API failed — will try watching page fallback...")
                                            null
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "   Extracted post ID: $postId")

            if (postId == null) {
                Log.i("CimaNowLoadLinks", "[2b/6] Fallback: fetching watching page for post ID via data-id...")
                val fxLink = moviePageDoc.selectFirst("a[href*='freex2line']")?.attr("href")
                if (fxLink != null) {
                    val linkParam = fxLink.substringAfter("link=", "")
                    if (linkParam.isNotBlank()) {
                        val watchPageUrl = try {
                            String(Base64.decode(linkParam, Base64.DEFAULT), Charsets.UTF_8)
                        } catch (e: Exception) { null }
                        if (watchPageUrl != null && watchPageUrl.startsWith("http")) {
                            Log.d(TAG, "   Watching page URL: $watchPageUrl")
                            val cookieStr = httpService.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                            val watchHeaders = mapOf(
                                "User-Agent" to httpService.userAgent,
                                "Referer" to data,
                                "Cookie" to cookieStr
                            )
                            val watchResp = httpService.getRaw(watchPageUrl, headers = watchHeaders)
                            val watchHtml = watchResp.body?.string() ?: ""
                            Log.d(TAG, "   Watching page size: ${watchHtml.length} bytes")
                            val dataIdMatch = Regex("""data-id\s*=\s*["'](\d+)["']""").find(watchHtml)
                            if (dataIdMatch != null) {
                                postId = dataIdMatch.groupValues[1]
                                Log.i(TAG, "   Post ID found via watching page data-id: $postId")
                            } else {
                                Log.w(TAG, "   No data-id in watching page; snippet: ${watchHtml.take(300)}")
                            }
                        } else {
                            Log.w(TAG, "   Decoded watch page URL is invalid or null")
                        }
                    } else {
                        Log.w(TAG, "   No link param in freex2line URL")
                    }
                } else {
                    Log.w(TAG, "   No freex2line link for watching page fallback")
                }
            }

            if (postId == null) {
                Log.i("CimaNowLoadLinks", "[2c/6] Fallback: REST API via getRaw with Cloudflare cookies...")
                val slug = data.trimEnd('/').substringAfterLast('/')
                val apiUrl = "$mainUrl/wp-json/wp/v2/posts?slug=$slug"
                val cookieStr = httpService.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val apiResp = httpService.getRaw(apiUrl, headers = mapOf(
                    "User-Agent" to httpService.userAgent,
                    "Accept" to "application/json",
                    "Cookie" to cookieStr
                ))
                val apiBody = apiResp.body?.string()
                if (apiBody != null) {
                    Log.d(TAG, "   REST API body (first 500): ${apiBody.take(500)}")
                    val fromApi = Regex(""""id"\s*:\s*(\d+)""").find(apiBody)?.groupValues?.get(1)
                    if (fromApi != null) {
                        postId = fromApi
                        Log.i(TAG, "   Post ID found via REST API (getRaw): $postId")
                    } else {
                        Log.w(TAG, "   No id field in REST API response")
                    }
                } else {
                    Log.w(TAG, "   REST API response body is null")
                }
            }

            if (postId == null) {
                Log.w(TAG, "   All HTTP post-ID extraction methods exhausted — trying WebView navigation fallback...")
                return tryWebViewFallback(data, subtitleCallback, callback)
            }

            Log.i("CimaNowLoadLinks", "[3/6] Searching for freex2line intermediate link...")
            var intermediateLink: String? = null
            val preciseLink = moviePageDoc.selectFirst("ul.btns li a.shine[href*='freex2line']")
            if (preciseLink != null) {
                intermediateLink = preciseLink.attr("href")
                Log.d(TAG, "   Found via precise selector: $intermediateLink")
            }
            if (intermediateLink.isNullOrBlank()) {
                Log.w(TAG, "   - Precise selector failed, trying a general search...")
                val allFreex2 = moviePageDoc.select("a[href*='freex2line']")
                Log.d(TAG, "   Total freex2line links found: ${allFreex2.size}")
                intermediateLink = allFreex2.firstOrNull()?.attr("href")
            }
            if (intermediateLink.isNullOrBlank()) {
                Log.e(TAG, "   - CRITICAL: Could not find any freex2line link.")
                Log.e(TAG, "   - HTML snippet around watch area: ${moviePageDoc.select("ul.btns").text().take(200)}")
                throw ErrorLoadingException("Failed to find intermediate link.")
            }
            Log.d(TAG, "   Found intermediate link: $intermediateLink")

            Log.i(TAG, "[4/6] Resolving shortlink via resolveFreex2line...")
            val finalCimaNowUrl = resolveFreex2line(intermediateLink)
                ?: throw ErrorLoadingException("Failed to bypass shortlink.")
            Log.i(TAG, "   Watch page URL obtained: $finalCimaNowUrl")

            Log.i(TAG, "[5/6] Fetching video links via core.php (bypass watch page)...")
            val knownIndices = listOf("00", "66", "32", "7", "30", "12")
            Log.d(TAG, "   Known indices to try: $knownIndices")
            var successCount = 0
            var failCount = 0

            coroutineScope {
                knownIndices.map { index ->
                    async {
                        try {
                            val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$postId"
                            Log.d(TAG, "   core.php GET: $ajaxUrl")
                            val coreHeaders = mapOf("Referer" to finalCimaNowUrl, "X-Requested-With" to "XMLHttpRequest")
                            val coreText = httpService.getText(ajaxUrl, headers = coreHeaders) ?: ""
                            Log.d(TAG, "   core.php body start: ${coreText.take(300)}")
                            val playerDoc = Jsoup.parse(coreText, ajaxUrl)
                            val rawIframeUrl = playerDoc?.select("iframe")?.attr("src") ?: ""
                            val iframeUrl = if (rawIframeUrl.startsWith("//")) "https:$rawIframeUrl" else rawIframeUrl

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
                                Log.i(TAG, "   ✅ index=$index ($serverName): iframe=$iframeUrl")
                                successCount++

                                when {
                                    iframeUrl.contains("cimanowtv", true) -> {
                                        Log.d(TAG, "   -> routing to handlecima")
                                        handlecima(iframeUrl, serverName, callback)
                                    }
                                    iframeUrl.contains("forafile.com", true) -> {
                                        Log.d(TAG, "   -> routing to handleForafile")
                                        handleForafile(iframeUrl, 0, finalCimaNowUrl, callback)
                                    }
                                    else -> {
                                        Log.d(TAG, "   -> routing to fallbackExtractIframe")
                                        fallbackExtractIframe(iframeUrl, serverName, finalCimaNowUrl, callback)
                                    }
                                }
                            } else {
                                val reason = if (iframeUrl.isBlank()) "blank iframe" else "placeholder (123456789)"
                                Log.w(TAG, "   ❌ index=$index skipped ($reason)")
                                failCount++
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "   💥 Error processing server index=$index: ${e.message}")
                            failCount++
                        }
                    }
                }.awaitAll()
            }

            Log.i(TAG, "   core.php results: $successCount succeeded, $failCount failed out of ${knownIndices.size}")

            if (successCount > 0) {
                Log.i(TAG, "================ [END LOADLINKS v6] =================")
                return true
            }

            Log.w(TAG, "   No valid servers found via core.php! Trying WebView navigation fallback...")
            val webViewResult = tryWebViewFallback(data, subtitleCallback, callback)

            Log.i(TAG, "================ [END LOADLINKS v6] =================")
            return webViewResult

        } catch (e: Exception) {
            Log.e(TAG, "FATAL ERROR in loadLinks: ${e.message}")
            Log.e(TAG, "Stack: ${e.stackTrace?.joinToString("\n") { "  at $it" }}")
        }

        Log.i(TAG, "================ [END LOADLINKS v6] =================")
        return false
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
                httpService.getDocument(ajaxUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
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
            val iframeResponse = httpService.getText(finalUrl, headers = mapOf("Referer" to finalUrl), skipRewrite = true) ?: ""
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

            val html = httpService.getText(iframeUrl, headers = mapOf("Referer" to referer), skipRewrite = true) ?: return
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

}
