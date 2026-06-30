package com.eseek

import android.util.Base64
import com.google.gson.Gson
import java.net.URLDecoder
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser

class GessehProvider : BaseProvider() {
    override val baseDomain get() = "qeseh.net"
    override val providerName get() = "عشق 2"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/eseek.json"

    data class GessehServer(
        val name: String? = null,
        val id: String? = null
    )

    data class GessehPayload(
        val codeDaily: String? = null,
        val servers: List<GessehServer>? = null,
        val backUrl: String? = null
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/all-series/page/" to "جميع المسلسلات",
        "$mainUrl/category/الأفلام-التركية/page/" to "افلام تركية",
        "$mainUrl/page/" to "آخر الحلقات",
    )

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true
    override val supportsLazySearch = true

    override fun getParser(): NewBaseParser = GessehParser()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        httpService.ensureInitialized()
        val url = request.data + page
        val doc = httpService.getDocument(url, headers = defaultHeaders, checkDomainChange = true, rewriteDomain = true) ?: return null
        val items = getParser().parseSearch(doc).map { item ->
            newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = item.posterUrl
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = getParser().getSearchUrl(mainUrl, encoded)
        val doc = httpService.getDocumentNoFallback(url, headers = defaultHeaders, checkDomainChange = true, rewriteDomain = true)
            ?: throw com.cloudstream.shared.service.CloudflareBlockedSearchException(name, baseDomain)
        return getParser().parseSearch(doc).map { item ->
            newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = item.posterUrl
            }
        }
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        return searchNormal(query)
    }

    private fun resolveRealUrl(url: String): String {
        var currentUrl = url
        val urlMatch = Regex("[?&]url=([^&]+)").find(currentUrl)
        if (urlMatch != null) {
            try {
                var extractedUrl = URLDecoder.decode(urlMatch.groupValues[1], "UTF-8")
                if (!extractedUrl.startsWith("http")) {
                    val decodedBytes = Base64.decode(extractedUrl, Base64.DEFAULT)
                    extractedUrl = String(decodedBytes, Charsets.UTF_8).trim()
                }
                if (extractedUrl.startsWith("http")) {
                    currentUrl = extractedUrl
                }
            } catch (_: Exception) {}
        }
        val postMatch = Regex("[?&]post=([^&]+)").find(currentUrl)
        if (postMatch != null) {
            try {
                val encodedPost = URLDecoder.decode(postMatch.groupValues[1], "UTF-8")
                val jsonStr = String(Base64.decode(encodedPost, Base64.DEFAULT), Charsets.UTF_8)
                val backUrlMatch = Regex("\"backUrl\"\\s*:\\s*\"([^\"]+)\"").find(jsonStr)
                if (backUrlMatch != null) {
                    val backUrl = backUrlMatch.groupValues[1].replace("\\/", "/")
                    if (backUrl.startsWith("http")) {
                        currentUrl = backUrl
                    }
                }
            } catch (_: Exception) {}
        }
        return currentUrl
    }

    override suspend fun load(url: String): LoadResponse? {
        httpService.ensureInitialized()
        val realUrl = resolveRealUrl(url)
        val document = httpService.getDocument(realUrl, headers = defaultHeaders) ?: return null

        val data = getParser().parseLoadPage(document, realUrl) ?: return null

        if (data.parentSeriesUrl != null) {
            return load(data.parentSeriesUrl)
        }

        if (data.isMovie) {
            return newMovieLoadResponse(data.title, realUrl, TvType.Movie, realUrl) {
                this.posterUrl = fixUrlNull(data.posterUrl)
                this.plot = data.plot
            }
        }

        val episodes = getParser().parseEpisodes(document, null).map { parsedEp ->
            newEpisode(parsedEp.url) {
                name = parsedEp.name
                episode = parsedEp.episode
                posterUrl = fixUrlNull(parsedEp.posterUrl)
            }
        }.reversed()

        return newTvSeriesLoadResponse(data.title, realUrl, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(data.posterUrl)
            this.plot = data.plot
        }
    }

    private suspend fun extractLinkFromObfuscatedPage(
        url: String,
        referer: String,
        logCallback: (String) -> Unit
    ): String? {
        val pageText = try {
            logCallback("Custom Extractor: Fetching page $url with referer $referer")
            app.get(url).text
        } catch (e: Exception) {
            logCallback("Custom Extractor ERROR: Failed to fetch page $url. Exception: ${e.message}")
            return null
        }

        val evalRegex = Regex("eval\\s*\\(\\s*function\\s*\\(.*?\\)\\s*\\{.*?\\}\\s*\\((.*)\\)\\s*\\)")
        val evalMatch = evalRegex.find(pageText) ?: return null
        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null

        val paramsRegex = Regex("['\"](.*?)['\"]\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*['\"](.*?)['\"]\\.split\\s*\\(['\"]\\|['\"]\\)")
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
            return Regex("\\b\\w+\\b").replace(p) { matchResult ->
                replaceMap[matchResult.value] ?: matchResult.value
            }
        }

        val deobfuscatedJs = deobfuscate(packedCode, base, count, keywords)
        logCallback("Custom Extractor: Deobfuscated JS start: ${deobfuscatedJs.take(120)}")

        val fileRegex = Regex("[\"']?file[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
        val fileMatch = fileRegex.find(deobfuscatedJs)
        val finalUrl = fileMatch?.groupValues?.get(1) ?: return null
        val cleanUrl = finalUrl.replace("\\/", "/")
        logCallback("Custom Extractor: Success! Found URL: $cleanUrl")
        return cleanUrl
    }

    private fun normalizeUrl(u: String?, base: String? = null): String? {
        val s = u?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return when {
            s.startsWith("//") -> "https:$s"
            s.startsWith("http://") || s.startsWith("https://") -> s
            base != null -> try {
                java.net.URL(java.net.URL(base), s).toString()
            } catch (_: Exception) { s }
            else -> s
        }
    }

    private fun extractUrlFromCodeHtml(codeHtml: String?, base: String? = null): String? {
        if (codeHtml.isNullOrBlank()) return null
        val doc = org.jsoup.Jsoup.parse(codeHtml, base ?: "")
        doc.selectFirst("iframe[src]")?.attr("abs:src")?.let { return normalizeUrl(it, base) }
        doc.selectFirst("source[src]")?.attr("abs:src")?.let { return normalizeUrl(it, base) }
        doc.selectFirst("a[href]")?.attr("abs:href")?.let { return normalizeUrl(it, base) }
        Regex("https?:\\/\\/[^\\s\"']+").find(codeHtml)?.value?.let { return normalizeUrl(it, base) }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        httpService.ensureInitialized()
        val pageUrl = resolveRealUrl(data)

        val mainPage = try {
            app.get(pageUrl, headers = defaultHeaders).document
        } catch (e: Exception) {
            println("[GessehProvider] failed to load page: $pageUrl -> ${e.message}")
            return false
        }

        val rawExtractorHref = mainPage.selectFirst("a.fullscreen-clickable")?.attr("href")?.trim()
        if (rawExtractorHref.isNullOrBlank()) {
            println("[GessehProvider] extractor link not found on page")
            return false
        }

        var targetUrl = rawExtractorHref
        var playerReferer = "https://fashny.net/"

        val urlMatch = Regex("[?&]url=([^&]+)").find(rawExtractorHref)
        if (urlMatch != null) {
            try {
                var extracted = java.net.URLDecoder.decode(urlMatch.groupValues[1], "UTF-8")
                if (!extracted.startsWith("http")) {
                    val decodedBytes = android.util.Base64.decode(extracted, android.util.Base64.DEFAULT)
                    extracted = String(decodedBytes, Charsets.UTF_8).trim()
                }
                if (extracted.startsWith("http")) {
                    targetUrl = extracted
                    val uri = java.net.URI(rawExtractorHref)
                    playerReferer = "${uri.scheme}://${uri.host}/"
                }
            } catch (_: Exception) {}
        } else {
            playerReferer = pageUrl
        }

        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36",
            "Referer" to playerReferer
        )

        data class ServerData(val name: String, val embedUrl: String)
        val serversToProcess = mutableSetOf<ServerData>()

        val postMatch = Regex("[?&]post=([^&]+)").find(targetUrl)
        if (postMatch != null) {
            try {
                val encodedJson = java.net.URLDecoder.decode(postMatch.groupValues[1], "UTF-8")
                val decodedBytes = android.util.Base64.decode(encodedJson, android.util.Base64.DEFAULT)
                val jsonString = String(decodedBytes, Charsets.UTF_8)
                val payload = parseJson<GessehPayload>(jsonString)
                payload.servers?.let { servers ->
                    for (server in servers) {
                        val sName = server.name ?: continue
                        val sId = server.id ?: continue
                        buildEmbedUrl(sName, sId)?.let { url ->
                            serversToProcess.add(ServerData(sName, url))
                        }
                    }
                }
            } catch (e: Exception) {
                println("[GessehProvider] Failed to parse JSON: ${e.message}")
            }
        }

        try {
            val htmlPage = app.get(targetUrl, headers = customHeaders).document
            htmlPage.select("ul.serversList li").forEach { li ->
                val serverName = li.attr("data-name").trim().takeIf { it.isNotEmpty() } ?: li.text().trim()
                val serverId = li.attr("data-server").trim()
                var embedUrl: String? = null

                if (serverId.isEmpty()) {
                    val codeHtml = li.selectFirst("code")?.html()
                    embedUrl = extractUrlFromCodeHtml(codeHtml, targetUrl)
                    if (embedUrl.isNullOrBlank()) {
                        val a = li.selectFirst("a")
                        embedUrl = a?.attr("abs:href")?.takeIf { it.isNotBlank() } ?: a?.attr("href")
                    }
                } else {
                    embedUrl = buildEmbedUrl(serverName, serverId)
                }

                embedUrl = normalizeUrl(embedUrl)?.trim()
                if (!embedUrl.isNullOrBlank()) {
                    serversToProcess.add(ServerData(serverName, embedUrl))
                }
            }
        } catch (e: Exception) {
            println("[GessehProvider] HTML fetching failed: ${e.message}")
        }

        coroutineScope {
            serversToProcess.map { server ->
                async {
                    processLink(
                        embedUrl = server.embedUrl,
                        serverName = server.name,
                        serverNameLower = server.name.lowercase(),
                        playerReferer = targetUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }.awaitAll()
        }

        return true
    }

    private fun buildEmbedUrl(serverName: String, serverId: String): String? {
        val lower = serverName.lowercase()
        return when {
            lower.contains("youtube_in") || lower.contains("youtube-in") -> "https://www.youtube.com/embed/$serverId"
            lower.contains("youtube") -> "https://www.youtube.com/embed/$serverId"
            lower == "express" -> serverId
            lower.contains("facebook") -> "https://app.videas.fr/embed/media/$serverId"
            lower.contains("estream") -> "https://arabveturk.com/embed-$serverId.html"
            lower.contains("arab hd") || lower.contains("arabhd") -> "https://v.turkvearab.com/embed-$serverId.html"
            lower.contains("red hd") || lower.contains("redhd") -> "https://iplayerhls.com/e/$serverId"
            lower.contains("pro hd") || lower.contains("prohd") -> "https://w.larhu.com/play.php?id=$serverId"
            lower == "pro" -> "https://mdna.upns.online/#$serverId"
            lower.contains("ok") -> "https://ok.ru/videoembed/$serverId"
            lower.contains("box") -> "https://youdboox.com/embed-$serverId.html"
            lower.contains("now") -> "https://extreamnow.org/embed-$serverId.html"
            else -> null
        }
    }

    private suspend fun processLink(
        embedUrl: String,
        serverName: String,
        serverNameLower: String,
        playerReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val low = embedUrl.lowercase()

        if (low.contains("dailymotion.com")) {
            try {
                QesehDailymotionExtractor().getUrl(embedUrl, null, subtitleCallback, callback)
            } catch (e: Exception) {
                println("[GessehProvider] Custom Dailymotion extractor failed: ${e.message}")
            }
            return
        }

        when {
            low.contains(".m3u8") || low.endsWith(".mp4") || low.contains(".mp4") -> {
                callback(newExtractorLink(this.name, serverName, embedUrl) {
                    this.referer = playerReferer
                    this.quality = getQualityFromName(embedUrl)
                    this.type = ExtractorLinkType.VIDEO
                })
            }
            embedUrl.contains("youtube.com") || embedUrl.contains("youtu.be") -> {
                callback(newExtractorLink(this.name, "YouTube", embedUrl) {
                    this.referer = playerReferer
                    this.quality = getQualityFromName(embedUrl)
                    this.type = ExtractorLinkType.VIDEO
                })
            }
            serverNameLower.contains("arab") || serverNameLower.contains("arabhd") || serverNameLower.contains("estream") || serverNameLower.contains("turk") || serverNameLower.contains("prohd") -> {
                try {
                    val headers = mapOf(
                        "Referer" to embedUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                    )
                    val extracted = extractLinkFromObfuscatedPage(embedUrl, playerReferer) { s -> println(s) }
                    if (!extracted.isNullOrBlank()) {
                        callback(newExtractorLink(source = this.name, name = serverName, url = extracted) {
                            this.headers = headers
                            this.quality = getQualityFromName(extracted)
                        })
                        return
                    }
                } catch (_: Exception) {}
                try { loadExtractor(embedUrl, playerReferer, subtitleCallback, callback) }
                catch (e: Exception) {
                    callback(newExtractorLink(this.name, serverName, embedUrl) {
                        this.referer = playerReferer
                        this.quality = getQualityFromName(embedUrl)
                    })
                }
            }
            else -> {
                try {
                    loadExtractor(embedUrl, playerReferer, subtitleCallback, callback)
                } catch (e: Exception) {
                    callback(newExtractorLink(this.name, serverName, embedUrl) {
                        this.referer = playerReferer
                        this.quality = getQualityFromName(embedUrl)
                    })
                }
            }
        }
    }

    open class QesehDailymotionExtractor : ExtractorApi() {
        override val mainUrl = "https://www.dailymotion.com"
        override val name = "Dailymotion (Qeseh)"
        override val requiresReferer = true
        private val baseUrl = "https://www.dailymotion.com"
        private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val embedUrl = getEmbedUrl(url) ?: return
            val id = getVideoId(embedUrl) ?: return
            val metaDataUrl = "$baseUrl/player/metadata/video/$id"
            val response = app.get(metaDataUrl, referer = "https://qeseh.net/").text
            val gson = Gson()
            val meta = gson.fromJson(response, MetaData::class.java)

            meta.qualities?.get("auto")?.forEach { quality ->
                val videoUrl = quality.url
                if (!videoUrl.isNullOrEmpty() && videoUrl.contains(".m3u8")) {
                    getStream(videoUrl, this.name, callback)
                }
            }

            meta.subtitles?.data?.forEach { (_, subData) ->
                subData.urls.forEach { subUrl ->
                    subtitleCallback(newSubtitleFile(subData.label, subUrl))
                }
            }
        }

        private fun getEmbedUrl(url: String): String? {
            if (url.contains("/embed/") || url.contains("/video/")) return url.substringBefore("?")
            if (url.contains("geo.dailymotion.com")) {
                val videoId = url.substringAfter("video=")
                return "$baseUrl/embed/video/$videoId"
            }
            return null
        }

        private fun getVideoId(url: String): String? {
            val path = java.net.URI(url).path
            val id = path.substringAfter("/video/")
            return if (id.matches(videoIdRegex)) id else null
        }

        private suspend fun getStream(
            streamLink: String,
            name: String,
            callback: (ExtractorLink) -> Unit
        ) {
            for (link in M3u8Helper.generateM3u8(name, streamLink, mainUrl)) {
                callback(link)
            }
        }

        data class MetaData(
            val qualities: Map<String, List<Quality>>?,
            val subtitles: SubtitlesWrapper?
        )
        data class Quality(val type: String?, val url: String?)
        data class SubtitlesWrapper(val enable: Boolean, val data: Map<String, SubtitleData>?)
        data class SubtitleData(val label: String, val urls: List<String>)
    }
}
