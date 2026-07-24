package com.cimanow

import android.content.Context
import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.webview.NavigationStep
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.VideoUrlClassifier
import com.cloudstream.shared.extractors.CimaNowTVEmbed
import com.cloudstream.shared.extractors.UpnshareEmbed
import com.cloudstream.shared.extractors.VKVideoEmbed
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
        Log.i("CimaNowLoadLinks", "================ [START LOADLINKS] ================")
        Log.d("CimaNowLoadLinks", "-> Data URL: $data")
        // The WebView does all decryption; see resolveViaWebViewSandbox.
        val found = resolveViaWebViewSandbox(data, subtitleCallback, callback)
        Log.i("CimaNowLoadLinks", "================ [END LOADLINKS found=$found] ================")
        return found
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
            // Try dedicated extractors first (before loadExtractor)
            val host = Regex("https?://([^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: ""
            var extracted = false

            val countingCb: (ExtractorLink) -> Unit = { link ->
                extracted = true
                Log.i(TAG_FE, "Dedicated extractor SUCCEEDED for '$serverName' -> ${link.url.take(120)}")
                callback(link)
            }

            // ── CimaNowTV embed (random subdomain *.cimanowtv.com/e/{id}) ──
            if (!extracted && (host.endsWith(".cimanowtv.com") || host == "cimanowtv.com")) {
                Log.i(TAG_FE, "Trying CimaNowTVEmbed for '$serverName'")
                try {
                    CimaNowTVEmbed().getUrl(iframeUrl, referer, {}, countingCb)
                } catch (e: Exception) {
                    Log.w(TAG_FE, "CimaNowTVEmbed threw for '$serverName': ${e.message}")
                }
                if (extracted) {
                    Log.i(TAG_FE, "CimaNowTVEmbed succeeded for '$serverName'")
                    return
                }
            }

            // ── Upnshare embed (cimanow.upns.online/#hash) ──
            if (!extracted && (host.contains("upns.online") || host.contains("upns."))) {
                Log.i(TAG_FE, "Trying UpnshareEmbed for '$serverName'")
                try {
                    UpnshareEmbed().getUrl(iframeUrl, referer, {}, countingCb)
                } catch (e: Exception) {
                    Log.w(TAG_FE, "UpnshareEmbed threw for '$serverName': ${e.message}")
                }
                if (extracted) {
                    Log.i(TAG_FE, "UpnshareEmbed succeeded for '$serverName'")
                    return
                }
            }

            // ── VK Video embed (vkvideo.ru or vk.com) ──
            if (!extracted && (host.contains("vkvideo") || host.contains("vk.com"))) {
                Log.i(TAG_FE, "Trying VKVideoEmbed for '$serverName'")
                try {
                    VKVideoEmbed().getUrl(iframeUrl, referer, {}, countingCb)
                } catch (e: Exception) {
                    Log.w(TAG_FE, "VKVideoEmbed threw for '$serverName': ${e.message}")
                }
                if (extracted) {
                    Log.i(TAG_FE, "VKVideoEmbed succeeded for '$serverName'")
                    return
                }
            }

            // ── Standard registered extractor ──
            if (!extracted) {
                val countingCallback: (ExtractorLink) -> Unit = { link ->
                    extracted = true
                    Log.i(TAG_FE, "loadExtractor SUCCEEDED for '$serverName' -> ${link.url.take(120)}")
                    callback(link)
                }
                try {
                    Log.i(TAG_FE, "Calling loadExtractor for server='$serverName' iframeUrl=$iframeUrl")
                    loadExtractor(iframeUrl, referer, {}, countingCallback)
                } catch (e: Exception) {
                    Log.w(TAG_FE, "loadExtractor threw for '$serverName': ${e.message}")
                }
                if (extracted) {
                    Log.i(TAG_FE, "loadExtractor produced links for '$serverName', skipping HTTP fallback")
                    return
                }
            }

            // ── HTTP fallback: fetch page and scrape video URLs ──
            Log.w(TAG_FE, "No extractor matched for '$serverName' — trying HTTP fallback for $iframeUrl")
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

    /**
     * True only when the HTML contains a real server-list element (<li ... data-index=...>),
     * NOT merely the substring "data-index". The bare substring also appears in injected
     * hook/script text, so a substring check yields a false positive.
     */
    private fun hasServerEntries(html: String): Boolean =
        Regex("<li\\b[^>]*\\bdata-index\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html)

    /**
     * Decrypt the captured (still-encrypted) watch-page HTML by letting the page's OWN decryptor
     * run in a WebView — the WebView does all the work; nothing is decrypted in Kotlin.
     *
     * Delegates to NavigationEngine.renderHtmlInSandbox, which (per the decoded anti-bot): serves
     * the HTML as a real navigation to [pageUrl] (so document.write/open commit and location.host
     * is set), sets document.referrer via [referrer] (to pass the /home redirect gate), and reads
     * the decrypted server list back through an in-page reader over a JavascriptInterface (never
     * via evaluateJavascript, which the page's isBot() stack-check would sabotage).
     *
     * @param watchHtml captured watch-page HTML (still encrypted)
     * @param pageUrl   the real /watching/?token=… URL — served as the document's URL/origin
     * @param referrer  the Referer the watch page was actually reached with (the freex blog-post
     *                  page). The decrypted page redirects to /home unless document.referrer
     *                  matches that host, which aborts the parse — so this must mirror the real
     *                  navigation, NOT be hardcoded. See the HAR: watching → Referer blog-post.html/.
     */
    private suspend fun decryptViaSandbox(watchHtml: String, pageUrl: String, referrer: String): String? {
        val TAG_SB = "CimaNowSandbox"
        Log.i(TAG_SB, "Phase 2: WebView stealth render starting — input ${watchHtml.length} chars, base=$pageUrl, referrer=$referrer")
        if (watchHtml.isBlank()) {
            Log.w(TAG_SB, "Empty watchHtml — nothing to decrypt")
            return null
        }
        return try {
            val result = httpService.navigationEngine.renderHtmlInSandbox(
                html = watchHtml,
                baseUrl = pageUrl.ifBlank { "https://cimanow.cc/" },
                userAgent = httpService.userAgent,
                referrer = referrer,
                timeoutMs = 25_000L
            )

            when {
                result.isNullOrBlank() -> {
                    Log.w(TAG_SB, "Sandbox returned no HTML")
                    null
                }
                hasServerEntries(result) -> {
                    Log.i(TAG_SB, "✅ Sandbox decrypt succeeded — ${result.length} chars, real <li data-index> present")
                    result
                }
                else -> {
                    // No real server elements. The page's inline decryptor did not emit the
                    // server list (see the render heartbeat: li=0/dh=0 for the whole window).
                    // Returning this HTML would only trip a substring false-positive downstream,
                    // so report failure honestly and let higher-level fallbacks decide.
                    Log.w(TAG_SB, "❌ Sandbox produced no <li data-index> elements (${result.length} chars) — decryptor did not run/emit")
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG_SB, "Sandbox error: ${e.message}")
            null
        }
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


    /**
     * Official link resolver. The WebView does ALL decryption:
     *   1. HTTP-navigate the freex redirect chain to the timer (blog-post) page.
     *   2. Render it in the NavigationEngine, which follows the countdown → get-link.php → the
     *      cimanow /watching/ URL and captures its raw (still-encrypted) HTTP response.
     *   3. Decrypt that HTML in an isolated WebView via decryptViaSandbox (page's own JS runs,
     *      stealth in-page reader returns the server list — see NavigationEngine.renderHtmlInSandbox).
     *   4. Parse servers/downloads/iframes with Jsoup and resolve each (core.php → extractors).
     */
    private suspend fun resolveViaWebViewSandbox(
        movieUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG_TEST = "CimaNowResolve"
        Log.i(TAG_TEST, "========== [START] WebView sandbox resolve ==========")
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

                // Step 1: Navigate to the watching URL once the countdown timer fires get-link.php.
                //         The request interceptor stashes the raw HTTP response (capturedMainFrameHtml)
                //         — that encrypted HTML is ALL we need; decryption happens later in the sandbox.
                NavigationStep.NavigateToWatchingUrl(abortOnFailure = true),

                // Step 2: Brief settle so the interceptor finishes capturing the watching-page
                //         response, then we're done with the nav. We deliberately do NOT poll for
                //         in-nav decryption or SweetAlert here: THIS WebView carries the
                //         anti-anti-bot document.write hook, which trips the decryptor's
                //         "[native code]" self-check so it never decrypts — the previous 20s DOM
                //         snapshot + 8s Swal waits always timed out (~28s wasted). decryptViaSandbox
                //         does the real decryption in <1s.
                NavigationStep.WaitForDomCondition(
                    jsCondition = "document.readyState === 'complete'",
                    timeoutMs = 6000L,
                    pollIntervalMs = 250L,
                    abortOnFailure = false
                ),
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
            Log.i(TAG_TEST, "mainFrameHtml available: ${!navResult.mainFrameHtml.isNullOrBlank()}, length: ${navResult.mainFrameHtml?.length ?: 0}")
            Log.i(TAG_TEST, "capturedVideoUrls count: ${navResult.capturedVideoUrls.size}")

            // Don't return early on failure — the interceptor may have captured
            // mainFrameHtml (server-rendered DOM pre anti-bot) and/or video URLs
            // (e.g. VK CDN) even when DOM polling times out.
            if (!navResult.success) {
                Log.w(TAG_TEST, "Isolated flow had step failures: ${navResult.error}")
                Log.w(TAG_TEST, "Continuing anyway to parse mainFrameHtml and capturedVideoUrls...")
            }

            // ======================== EXTRACT FROM CAPTURED DATA ========================
            val watchUrl = navResult.finalUrl
            var found = false
            val foundLinks = mutableListOf<String>()
            val loggingCallback: (ExtractorLink) -> Unit = { link ->
                foundLinks.add(link.url)
                Log.i(TAG_TEST, ">>> LINK: source=${link.source} name=${link.name} quality=${link.quality} url=${link.url.take(150)} referer=${link.referer.take(80)}")
                callback(link)
            }

            // ======================== 1. CAPTURED VIDEO URLS (from interceptor) ========================
            // The request interceptor captures video stream URLs (e.g. VK CDN, ok.ru)
            // directly from network requests. These are available even if the DOM
            // was cleared by anti-bot JS.
            // We filter using VideoUrlClassifier (same logic as the SnifferExtractor)
            // to skip segments (.ts, .m4s, .key), images, ads, DRM, and other non-video assets.
            val capturedVideoUrls = navResult.capturedVideoUrls
            val playableUrls = capturedVideoUrls.distinct().filter { url ->
                url.isNotBlank() &&
                !VideoUrlClassifier.isSegmentOrAsset(url) &&
                !VideoUrlClassifier.isBlacklisted(url) &&
                VideoUrlClassifier.isVideoUrl(url)
            }
            if (playableUrls.isNotEmpty()) {
                Log.i(TAG_TEST, "Processing ${playableUrls.size} playable video URLs from interceptor (filtered from ${capturedVideoUrls.size} total)")
                for (videoUrl in playableUrls) {
                    Log.i(TAG_TEST, ">>> CAPTURED VIDEO: ${videoUrl.take(150)}")
                    if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                        M3u8Helper.generateM3u8(
                            source = "CimaNow",
                            streamUrl = videoUrl,
                            referer = watchUrl,
                            headers = mapOf("User-Agent" to httpService.userAgent)
                        ).forEach { link ->
                            Log.i(TAG_TEST, ">>> M3U8 quality: ${link.quality}p -> ${link.url.take(100)}")
                            callback(link)
                            found = true
                        }
                    } else {
                        val link = newExtractorLink("CimaNow", "CimaNow", videoUrl, type = getLinkType(videoUrl))
                        link.referer = watchUrl
                        callback(link)
                        found = true
                    }
                }
            } else {
                Log.d(TAG_TEST, "No playable video URLs from interceptor (${capturedVideoUrls.size} total captured)")
            }

            // ======================== 2. OFFLINE PARSING OF captured HTML ========================
            // Primary source: decrypted HTML captured by the document.write interceptor
            // (injected via NavigationEngine.ANTI_ANTI_BOT_JS) which stores the unmodified
            // page content in window.__decryptedHtml BEFORE the anti-bot runs.
            // Fallback source: mainFrameHtml (encrypted server HTML from HTTP interceptor).

            val mainFrameHtml = navResult.mainFrameHtml
            val rawHtmlData = navResult.extractedHtml["raw_html"] ?: ""

            // Prefer the JS snapshot if available — the document.write interceptor
            // (NavigationEngine.ANTI_ANTI_BOT_JS) captures the decrypted page HTML into
            // window.__decryptedHtml before the anti-bot can strip <li> entries.
            // Fall back to mainFrameHtml (encrypted server HTML) only if the snapshot is empty.
            val watchHtml: String = if (rawHtmlData.startsWith("RAW_HTML:")) {
                val html = rawHtmlData.removePrefix("RAW_HTML:")
                val source = if (html.contains("data-index")) "decrypted" else "DOM"
                Log.i(TAG_TEST, "Using JS snapshot: ${html.length} chars ($source, anti-bot bypassed)")
                html
            } else if (!mainFrameHtml.isNullOrBlank()) {
                Log.i(TAG_TEST, "Using captured mainFrameHtml: ${mainFrameHtml.length} chars (fallback, pre anti-bot)")
                mainFrameHtml
            } else {
                Log.w(TAG_TEST, "No HTML captured. mainFrameHtml=${mainFrameHtml != null}, rawHtmlData=${rawHtmlData.take(100)}")
                ""
            }

            data class ServerInfo(val index: String, val id: String, val name: String)

            if (watchHtml.isNotBlank()) {
                // Real server elements already present? (substring "data-index" is NOT enough —
                // see hasServerEntries.) If not, the HTML is still encrypted → try the sandbox.
                val htmlForParsing = if (!hasServerEntries(watchHtml)) {
                    Log.i(TAG_TEST, "⚙️ SANDBOX FALLBACK: watchHtml (${watchHtml.length} chars) has no <li data-index> — running through decryptViaSandbox...")
                    // baseUrl (the freex blog-post page) is exactly the Referer the watch page was
                    // navigated with — pass it so document.referrer passes the /home redirect gate.
                    val sandboxResult = decryptViaSandbox(watchHtml, watchUrl.ifBlank { movieUrl }, baseUrl)
                    if (sandboxResult != null && hasServerEntries(sandboxResult)) {
                        Log.i(TAG_TEST, "✅ SANDBOX SUCCESS: decrypted HTML has real <li data-index> (${sandboxResult.length} chars)")
                        sandboxResult
                    } else {
                        // Decryption did not yield server elements — parsing the raw/rendered HTML
                        // will find 0 servers. Keep watchHtml so downstream logging is consistent,
                        // but expect NOTHING FOUND unless another source (video URLs) populated links.
                        Log.w(TAG_TEST, "⚠️ SANDBOX did not produce server elements — decryptor did not run/emit; parsing will likely find nothing")
                        watchHtml
                    }
                } else {
                    Log.i(TAG_TEST, "✅ watchHtml already contains real <li data-index> — skipping sandbox")
                    watchHtml
                }

                // Parse with Jsoup — robust across multi-line/attribute-order variation (the old
                // regex parser missed the multi-line <a>…</a> download anchors → 0 downloads).
                val doc = Jsoup.parse(htmlForParsing, watchUrl.ifBlank { movieUrl })

                // Servers: <li data-index="XX" data-id="YY">Name</li>
                val servers = doc.select("li[data-index]").mapNotNull { el ->
                    val idx = el.attr("data-index").trim()
                    val id = el.attr("data-id").trim()
                    if (idx.isBlank() && id.isBlank()) null else ServerInfo(idx, id, el.text().trim().take(50))
                }

                // Direct iframes (e.g. VK embed rendered straight into the DOM)
                val iframeUrls = doc.select("iframe[src]").map { it.attr("src") }
                    .filter { it.isNotBlank() && !it.contains("about:blank") }

                // Download links: every anchor inside #download, plus known file hosts anywhere.
                val downloads = doc.select(
                    "#download a[href], a[href*='jetload'], a[href*='forafile'], a[href*='vk.com/doc'], a[href*='frdl.my'], a[href*='bysetayico']"
                ).mapNotNull { a ->
                    val href = a.attr("href")
                    if (href.isBlank() || !href.startsWith("http")) return@mapNotNull null
                    Pair(href, a.text().trim().take(50))
                }.distinctBy { it.first }

                Log.i(TAG_TEST, "Parsed ${servers.size} servers, ${iframeUrls.size} iframes, ${downloads.size} downloads from ${if (htmlForParsing !== watchHtml) "SANDBOX-DECRYPTED" else "raw"} HTML")

                // 1. Process servers — call core.php for each to get iframe URLs
                if (servers.isNotEmpty()) {
                    Log.i(TAG_TEST, "Found ${servers.size} servers from raw HTML")
                    val cookieString = navResult.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    val coreHeaders = mutableMapOf(
                        "Referer" to watchUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                    if (cookieString.isNotBlank()) {
                        coreHeaders["Cookie"] = cookieString
                    }
                    coroutineScope {
                        for (sv in servers) {
                            if (sv.index.isBlank() || sv.id.isBlank()) continue
                            launch {
                                try {
                                    val ajaxUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=${sv.index}&id=${sv.id}"
                                    Log.d(TAG_TEST, "core.php GET for server '${sv.name}': index=${sv.index} id=${sv.id}")
                                    val coreText = httpService.getText(ajaxUrl, headers = coreHeaders) ?: ""
                                    val iframeMatch = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']").find(coreText)
                                    val iframeUrl = iframeMatch?.groupValues?.get(1)?.let {
                                        if (it.startsWith("//")) "https:$it" else it
                                    } ?: ""
                                    if (iframeUrl.isNotBlank() && iframeUrl != "123456789") {
                                        Log.i(TAG_TEST, "Server '${sv.name}' iframe: $iframeUrl")
                                        fallbackExtractIframe(iframeUrl, sv.name, watchUrl, loggingCallback)
                                        found = true
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG_TEST, "core.php failed for server '${sv.name}': ${e.message}")
                                }
                            }
                        }
                    }
                }

                // 2. Process download links — ONLY as a fallback when there are no watch links
                //    (no servers and no direct embed iframes). Watch/stream links are preferred;
                //    the download list is passed to the player only when nothing else is available.
                val haveWatchLinks = servers.isNotEmpty() || iframeUrls.isNotEmpty()
                if (!haveWatchLinks && downloads.isNotEmpty()) {
                    Log.i(TAG_TEST, "No watch links — falling back to ${downloads.size} download links")
                    for ((dlUrl, name) in downloads) {
                        val quality = Regex("""\d+p""").find(name)?.value?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
                        try {
                            when {
                                dlUrl.contains("jetload.pp.ua", true) -> handleJetload(dlUrl, quality, watchUrl, loggingCallback)
                                dlUrl.contains("forafile.com", true) -> handleForafile(dlUrl, quality, watchUrl, loggingCallback)
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

                // 3. Process direct iframes (e.g. VK embed)
                if (iframeUrls.isNotEmpty()) {
                    Log.i(TAG_TEST, "Found ${iframeUrls.size} direct iframes from raw HTML")
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

