package com.krmzy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import java.net.URI

class KrmzyProvider : BaseProvider() {
    override val baseDomain get() = "krmzi.org"
    override val providerName get() = "قرمزي"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/krmzy.json"

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller

    override val mainPage = mainPageOf(
        "$mainUrl/series-list/page/" to "جميع المسلسلات",
    )

    override val supportedTypes = setOf(TvType.TvSeries)

    override fun getParser(): NewBaseParser {
        return KrmzyParser()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        httpService.ensureInitialized()
        val document = app.get(request.data + page, interceptor = cfInterceptor).document
        val home = document.select("article.postEp").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = link.selectFirst("div.title")?.text()?.trim() ?: link.attr("title")
            val posterUrl = link.selectFirst("div.imgSer, div.imgBg")?.attr("style")?.let {
                Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
            }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, interceptor = cfInterceptor).document
        return document.select("div.block-post").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = link.selectFirst("div.title")?.text()?.trim() ?: link.attr("title")
            val posterUrl = link.selectFirst("div.imgSer, div.imgBg")?.attr("style")?.let {
                Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
            }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        return searchNormal(query)
    }

    // Two-param paginated search preserved for app pagination use
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        httpService.ensureInitialized()
        val url = if (page > 1) {
            "$mainUrl/search/$query/page/$page/"
        } else {
            "$mainUrl/?s=$query"
        }
        val document = app.get(url, interceptor = cfInterceptor).document
        val items = document.select("div.block-post").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = link.selectFirst("div.title")?.text()?.trim() ?: link.attr("title")
            val posterUrl = link.selectFirst("div.imgSer, div.imgBg")?.attr("style")?.let {
                Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
            }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        return newSearchResponseList(items, items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        httpService.ensureInitialized()
        val document = app.get(url, interceptor = cfInterceptor).document

        val seriesUrl = document.selectFirst("div.singleSeries div.info h1 a")?.attr("href")
        if (seriesUrl != null) {
            return load(seriesUrl)
        }

        val title = document.selectFirst("div.info h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.cover div.img")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")
        val description = document.selectFirst("div.story")?.text()?.trim()

        if (url.contains("/movies/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodes = document.select("article.postEp").mapNotNull {
            val epUrl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epTitle = it.selectFirst("div.title")?.text()?.trim()
            val epNum = it.selectFirst("div.episodeNum span:last-child")?.text()?.toIntOrNull()
            val epPoster = it.selectFirst("div.imgSer")
                ?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")
            newEpisode(epUrl) {
                name = epTitle
                episode = epNum
                posterUrl = epPoster
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun extractLinkFromObfuscatedPage(
        url: String,
        referer: String,
        logCallback: (String) -> Unit
    ): String? {
        val pageText = try {
            logCallback("Custom Extractor: Fetching page $url with referer $referer")
            app.get(url, referer = referer, interceptor = cfInterceptor).text
        } catch (e: Exception) {
            logCallback("Custom Extractor ERROR: Failed to fetch page $url. Exception: ${e.message}")
            return null
        }

        val evalRegex = Regex("""eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""")
        val evalMatch = evalRegex.find(pageText) ?: return null
        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null

        val paramsRegex = Regex("""['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(['"]\|['"]\)""")
        val paramMatch = paramsRegex.find(paramsString) ?: return null

        val (packedCode, baseStr, countStr, dictionaryStr) = paramMatch.destructured
        val base = baseStr.toInt()
        val count = countStr.toInt()
        val keywords = dictionaryStr.split('|')

        fun toBase(num: Int, radix: Int): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            if (num == 0) return "0"
            var n = num
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(chars[n % radix])
                n /= radix
            }
            return sb.reverse().toString()
        }

        fun deobfuscate(p: String, a: Int, c: Int, k: List<String>): String {
            val replaceMap = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val keyword = k.getOrNull(i)
                if (!keyword.isNullOrEmpty()) {
                    replaceMap[toBase(i, a)] = keyword
                }
            }
            return Regex("""\b\w+\b""").replace(p) { matchResult ->
                replaceMap[matchResult.value] ?: matchResult.value
            }
        }

        val deobfuscatedJs = deobfuscate(packedCode, base, count, keywords)
        logCallback("Custom Extractor: Deobfuscated JS start: ${deobfuscatedJs.take(100)}")

        val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")
        val fileMatch = fileRegex.find(deobfuscatedJs)
        val finalUrl = fileMatch?.groupValues?.get(1) ?: return null
        val cleanUrl = finalUrl.replace("\\/", "/")
        logCallback("Custom Extractor: Success! Found URL: $cleanUrl")
        return cleanUrl
    }

    private suspend fun checkWorkingStreamReferer(
        streamUrl: String,
        originEmbedUrl: String,
        logCallback: (String) -> Unit
    ): String {
        val iframeHostReferer = try {
            val uri = URI(originEmbedUrl)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) {
            "https://qesen.net/"
        }

        val candidates = listOf(
            iframeHostReferer,
            "https://qesen.net/",
            "https://newaat.com/"
        )

        logCallback("Checking working referer for stream. Candidates: $candidates")

        for (ref in candidates) {
            try {
                val code = app.get(streamUrl, referer = ref, interceptor = cfInterceptor).code
                if (code == 200) {
                    logCallback("Referer works: $ref")
                    return ref
                } else {
                    logCallback("Referer failed ($code): $ref")
                }
            } catch (e: Exception) {
                logCallback("Referer check error for $ref: ${e.message}")
            }
        }

        logCallback("All checks failed. Defaulting to: $iframeHostReferer")
        return iframeHostReferer
    }

    private suspend fun extractLinkFromObfuscatedPage(
        url: String,
        referers: List<String>,
        logCallback: (String) -> Unit
    ): String? {
        var pageText: String? = null

        for (ref in referers) {
            try {
                logCallback("Custom Extractor: Trying to fetch page with referer: $ref")
                val text = app.get(url, referer = ref, interceptor = cfInterceptor).text
                if (text.contains("eval(function")) {
                    pageText = text
                    logCallback("Success fetching with referer: $ref")
                    break
                } else {
                    logCallback("Fetched page but no 'eval' found with referer: $ref")
                }
            } catch (e: Exception) {
                logCallback("Failed to fetch with referer $ref: ${e.message}")
            }
        }

        if (pageText == null) {
            logCallback("Custom Extractor ERROR: Failed to fetch valid page with all provided referers.")
            return null
        }

        val evalRegex = Regex("""eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""")
        val evalMatch = evalRegex.find(pageText) ?: return null
        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null

        val paramsRegex = Regex("""['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(['"]\|['"]\)""")
        val paramMatch = paramsRegex.find(paramsString) ?: return null

        val (packedCode, baseStr, countStr, dictionaryStr) = paramMatch.destructured
        val base = baseStr.toInt()
        val count = countStr.toInt()
        val keywords = dictionaryStr.split('|')

        fun deobfuscate(p: String, a: Int, c: Int, k: List<String>): String {
            fun toBase(num: Int, radix: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                if (num == 0) return "0"
                var n = num
                val sb = StringBuilder()
                while (n > 0) {
                    sb.append(chars[n % radix])
                    n /= radix
                }
                return sb.reverse().toString()
            }
            val replaceMap = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val keyword = k.getOrNull(i)
                if (!keyword.isNullOrEmpty()) {
                    replaceMap[toBase(i, a)] = keyword
                }
            }
            return Regex("""\b\w+\b""").replace(p) { matchResult ->
                replaceMap[matchResult.value] ?: matchResult.value
            }
        }

        val deobfuscatedJs = deobfuscate(packedCode, base, count, keywords)
        val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")
        val fileMatch = fileRegex.find(deobfuscatedJs)
        val finalUrl = fileMatch?.groupValues?.get(1) ?: return null
        val cleanUrl = finalUrl.replace("\\/", "/")
        logCallback("Custom Extractor: Success! Found URL: $cleanUrl")
        return cleanUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        httpService.ensureInitialized()
        val logBuilder = StringBuilder()
        fun log(line: String) {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getDefault() }
                .format(java.util.Date())
            val l = "[$ts] $line"
            println(l)
            logBuilder.append(l).append("\n")
        }

        log("START loadLinks for page: $data")

        val mainPageHostReferer = try {
            val uri = java.net.URI(data)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) { data }

        val episodePage = try {
            app.get(data, interceptor = cfInterceptor).document
        } catch (t: Throwable) {
            log("ERROR: failed to fetch episode page: ${t.message}")
            return false
        }

        val extractorUrl = episodePage.selectFirst("a.fullscreen-clickable")?.attr("href")
        if (extractorUrl.isNullOrBlank()) {
            log("No <a.fullscreen-clickable> found.")
            return false
        }

        if (extractorUrl.endsWith(".m3u8", ignoreCase = true) || extractorUrl.endsWith(".mp4", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(source = this.name, name = this.name, url = extractorUrl) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val extractorPage = try {
            app.get(extractorUrl, referer = data, interceptor = cfInterceptor).document
        } catch (t: Throwable) {
            log("ERROR: failed to fetch extractor page: ${t.message}")
            return false
        }

        val serverItems = extractorPage.select("ul.serversList li")
        if (serverItems.isEmpty()) return false

        fun ensureHttp(u: String): String = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http") -> u
            else -> "https://$u"
        }

        fun dailymotionFromLi(li: org.jsoup.nodes.Element): String? {
            val a = li.selectFirst("code a")
            if (a != null) return a.attr("href")
            val code = li.selectFirst("code")?.text()
            return code?.takeIf { it.isNotBlank() }
        }

        for (li in serverItems) {
            val serverIdRaw = li.attr("data-server").ifBlank { li.attr("data-server-id") }
            val serverTypeRaw = li.attr("data-name").ifBlank { li.attr("data-type") }.trim()
            val serverType = serverTypeRaw.lowercase().trim()

            var embedUrl: String? = null
            try {
                embedUrl = when (serverType) {
                    "youtube" -> "https://www.youtube.com/watch?v=$serverIdRaw"
                    "youtube_in" -> "https://www.youtube.com/embed/$serverIdRaw"
                    "express" -> serverIdRaw.ifBlank { null }
                    "dailymotion" -> dailymotionFromLi(li)
                    "facebook" -> "https://app.videas.fr/embed/media/$serverIdRaw"
                    "estream" -> "https://arabveturk.com/embed-$serverIdRaw.html"
                    "arab hd", "arabhd", "arab-hd" -> "https://v.turkvearab.com/embed-$serverIdRaw.html"
                    "box" -> "https://youdboox.com/embed-$serverIdRaw.html"
                    "now" -> "https://extreamnow.org/embed-$serverIdRaw.html"
                    "ok" -> ensureHttp("//ok.ru/videoembed/$serverIdRaw")
                    "red hd", "redhd", "red-hd" -> "https://iplayerhls.com/e/$serverIdRaw"
                    "pro hd", "prohd", "pro-hd" -> "https://ebtv.upns.live/#$serverIdRaw"
                    "pro" -> "https://mdna.upns.online/#$serverIdRaw"
                    else -> {
                        val fallbackHref = li.selectFirst("a")?.attr("href")
                        val fallbackData = li.attr("data-src")
                        when {
                            !fallbackHref.isNullOrBlank() -> fallbackHref
                            !fallbackData.isNullOrBlank() -> fallbackData
                            else -> null
                        }
                    }
                }

                if (!embedUrl.isNullOrBlank()) {
                    when (serverType) {
                        "arab hd", "arabhd", "arab-hd", "estream" -> {
                            log("Processing custom server: $serverType ($embedUrl)")
                            try {
                                val fetchReferers = listOf(mainPageHostReferer, "https://newaat.com/")
                                val extractedM3u8 = extractLinkFromObfuscatedPage(embedUrl, fetchReferers, ::log)

                                if (!extractedM3u8.isNullOrBlank()) {
                                    val workingReferer = checkWorkingStreamReferer(extractedM3u8, embedUrl, ::log)
                                    val qualityLinks = M3u8Helper.generateM3u8(
                                        source = this.name,
                                        streamUrl = extractedM3u8,
                                        referer = workingReferer,
                                        headers = mapOf("Origin" to workingReferer.trimEnd('/'))
                                    )

                                    if (qualityLinks.isNotEmpty()) {
                                        for (link in qualityLinks) {
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = link.source,
                                                    name = "$serverTypeRaw - ${link.name}",
                                                    url = link.url
                                                ) {
                                                    this.referer = link.referer
                                                    this.quality = link.quality
                                                    this.headers = link.headers
                                                }
                                            )
                                        }
                                    } else {
                                        callback.invoke(
                                            newExtractorLink(source = this.name, name = serverTypeRaw, url = extractedM3u8) {
                                                this.quality = Qualities.Unknown.value
                                                this.referer = workingReferer
                                            }
                                        )
                                    }
                                }
                            } catch (t: Throwable) {
                                log("Error in custom extraction: ${t.message}")
                            }
                        }
                        "youtube" -> {
                            callback.invoke(
                                newExtractorLink(source = this.name, name = "YouTube", url = embedUrl) {
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                        else -> {
                            try {
                                loadExtractor(embedUrl, mainPageHostReferer, subtitleCallback, callback)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (t: Throwable) {
                log("Exception processing server: ${t.message}")
            }
        }

        return true
    }
}
