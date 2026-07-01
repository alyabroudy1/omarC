package com.krmzy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import java.net.URI
import android.util.Base64

class KrmzyProvider : BaseProvider() {
    override val baseDomain get() = "krmzi.org"
    override val providerName get() = "قرمزي"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/krmzy.json"
    override val paginationFormat get() = "/page/%d/"

    override val mainPage = mainPageOf(
        "$mainUrl" to "آخر الحلقات",
        "$mainUrl/series-list/" to "جميع المسلسلات",
    )

    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "ar"
    override val hasMainPage = true
    override val supportsLazySearch = true

    override fun getParser(): NewBaseParser = KrmzyParser()

    private fun resolveSayyarhUrl(url: String): String {
        if (!url.contains("sayyarh.com") && !url.contains("latest1501")) return url
        val urlParam = Regex("[?&]url=([^&]+)").find(url)?.groupValues?.getOrNull(1) ?: return url
        return try {
            val decoded = String(Base64.decode(urlParam, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decoded.startsWith("http")) decoded else url
        } catch (_: Exception) { url }
    }

    private suspend fun extractLinkFromObfuscatedPage(
        url: String,
        referer: String,
        logCallback: (String) -> Unit
    ): String? {
        val pageText = try {
            logCallback("Custom Extractor: Fetching page $url with referer $referer")
            app.get(url, referer = referer).text
        } catch (e: Exception) {
            logCallback("Custom Extractor ERROR: Failed to fetch page $url. Exception: ${e.message}")
            return null
        }

        val evalRegex = Regex("""eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""")
        val evalMatch = evalRegex.find(pageText)
        if (evalMatch == null) {
            logCallback("Custom Extractor ERROR: evalRegex did not find a match.")
            return null
        }

        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null

        val paramsRegex = Regex("""['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(['"]\|['"]\)""")
        val paramMatch = paramsRegex.find(paramsString)
        if (paramMatch == null) {
            logCallback("Custom Extractor ERROR: paramsRegex failed on: '${paramsString.take(100)}...'")
            return null
        }

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

        logCallback("Custom Extractor: Deobfuscated JS start: ${deobfuscatedJs.take(100)}")

        val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")
        val fileMatch = fileRegex.find(deobfuscatedJs)

        if (fileMatch == null) {
            logCallback("Custom Extractor ERROR: fileRegex did not find a match.")
            return null
        }

        val finalUrl = fileMatch.groupValues[1]
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
        } catch (e: Exception) {
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
                val code = app.get(
                    streamUrl,
                    referer = ref
                ).code

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
                val text = app.get(url, referer = ref).text

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
        val evalMatch = evalRegex.find(pageText)
        if (evalMatch == null) {
            logCallback("Custom Extractor ERROR: evalRegex did not find a match.")
            return null
        }

        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null

        val paramsRegex = Regex("""['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(['"]\|['"]\)""")
        val paramMatch = paramsRegex.find(paramsString)
        if (paramMatch == null) {
            logCallback("Custom Extractor ERROR: paramsRegex failed.")
            return null
        }

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

        if (fileMatch == null) {
            logCallback("Custom Extractor ERROR: fileRegex did not find a match.")
            return null
        }

        val finalUrl = fileMatch.groupValues[1]
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

        val resolvedData = resolveSayyarhUrl(data)
        if (resolvedData != data) {
            log("Resolved sayyarh URL to: $resolvedData")
        } else {
            log("URL not modified by resolveSayyarhUrl, using original: $resolvedData")
        }

        log("Parsed mainPageHostReferer from resolvedData: $resolvedData")
        val mainPageHostReferer = try {
            val uri = java.net.URI(resolvedData)
            "${uri.scheme}://${uri.host}/"
        } catch (e: Exception) {
            log("Failed to parse URI from resolvedData, falling back to resolvedData itself: ${e.message}")
            resolvedData
        }
        log("mainPageHostReferer = $mainPageHostReferer")

        val episodeResponse = try {
            app.get(resolvedData)
        } catch (t: Throwable) {
            log("ERROR: failed to fetch episode page: ${t.message}")
            return false
        }
        log("Episode page fetched, code=${episodeResponse.code}, url=${episodeResponse.url}")
        val episodePage = episodeResponse.document
        log("Episode page title: ${episodePage.title()}")

        val extractorUrl = episodePage.selectFirst("a.fullscreen-clickable")?.attr("href")
        log("Extractor URL from a.fullscreen-clickable: ${extractorUrl ?: "NULL"}")
        if (extractorUrl.isNullOrBlank()) {
            log("No <a.fullscreen-clickable> found.")
            return false
        }

        if (extractorUrl.endsWith(".m3u8", ignoreCase = true) || extractorUrl.endsWith(".mp4", ignoreCase = true)) {
            log("Extractor URL is direct media, invoking callback: $extractorUrl")
            callback.invoke(
                newExtractorLink(source = this.name, name = this.name, url = extractorUrl) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val extractorResponse = try {
            app.get(extractorUrl, referer = data)
        } catch (t: Throwable) {
            log("ERROR: failed to fetch extractor page: ${t.message}")
            return false
        }
        log("Extractor page fetched, code=${extractorResponse.code}, url=${extractorResponse.url}")
        val extractorPage = extractorResponse.document
        log("Extractor page title: ${extractorPage.title()}")

        val serverItems = extractorPage.select("ul.serversList li")
        log("Server items found (ul.serversList li): ${serverItems.size}")
        if (serverItems.isEmpty()) {
            log("No server items found in extractor page. Dumping HTML snippet: ${extractorPage.html().take(500)}")
            return false
        }

        fun ensureHttp(u: String): String =
            when {
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

        var processedCount = 0
        var successCount = 0

        for (li in serverItems) {
            val serverIdRaw = li.attr("data-server").ifBlank { li.attr("data-server-id") }
            val serverTypeRaw = li.attr("data-name").ifBlank { li.attr("data-type") }.trim()
            val serverType = serverTypeRaw.lowercase().trim()
            processedCount++

            log("Server #$processedCount: typeRaw='$serverTypeRaw' type='$serverType' id='$serverIdRaw'")

            var embedUrl: String? = null
            try {
                embedUrl = when (serverType) {
                    "youtube" -> "https://www.youtube.com/watch?v=$serverIdRaw"
                    "youtube_in" -> "https://www.youtube.com/embed/$serverIdRaw"
                    "express" -> {
                        log("Server #$processedCount: express type, using serverIdRaw directly")
                        serverIdRaw.ifBlank { null }
                    }
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
                        log("Server #$processedCount: unknown type '$serverType', fallbackHref='$fallbackHref', fallbackData='$fallbackData'")
                        when {
                            !fallbackHref.isNullOrBlank() -> fallbackHref
                            !fallbackData.isNullOrBlank() -> fallbackData
                            else -> null
                        }
                    }
                }

                log("Server #$processedCount: embedUrl = ${embedUrl ?: "NULL (skipping)"}")
                if (!embedUrl.isNullOrBlank()) {
                    when (serverType) {
                        "arab hd", "arabhd", "arab-hd", "estream" -> {
                            log("Server #$processedCount: custom extraction for $serverType")
                            try {
                                val fetchReferers = listOf(mainPageHostReferer, "https://newaat.com/")
                                val extractedM3u8 = extractLinkFromObfuscatedPage(embedUrl, fetchReferers, ::log)

                                if (!extractedM3u8.isNullOrBlank()) {
                                    log("Server #$processedCount: extracted M3U8 URL = $extractedM3u8")
                                    val workingReferer = checkWorkingStreamReferer(extractedM3u8, embedUrl, ::log)
                                    log("Server #$processedCount: working referer = $workingReferer")

                                    val qualityLinks = M3u8Helper.generateM3u8(
                                        source = this.name,
                                        streamUrl = extractedM3u8,
                                        referer = workingReferer,
                                        headers = mapOf("Origin" to workingReferer.trimEnd('/'))
                                    )

                                    if (qualityLinks.isNotEmpty()) {
                                        log("Server #$processedCount: generated ${qualityLinks.size} quality links")
                                        qualityLinks.forEach { link ->
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
                                            successCount++
                                        }
                                    } else {
                                        log("Server #$processedCount: no quality links, using raw M3U8 URL")
                                        callback.invoke(
                                            newExtractorLink(source = this.name, name = serverTypeRaw, url = extractedM3u8) {
                                                this.quality = Qualities.Unknown.value
                                                this.referer = workingReferer
                                            }
                                        )
                                        successCount++
                                    }
                                } else {
                                    log("Server #$processedCount: custom extraction returned null")
                                }
                            } catch (t: Throwable) {
                                log("Server #$processedCount: Error in custom extraction: ${t.message}")
                            }
                        }

                        "youtube" -> {
                            log("Server #$processedCount: invoking YouTube callback")
                            callback.invoke(
                                newExtractorLink(source = this.name, name = "YouTube", url = embedUrl) {
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            successCount++
                        }

                        else -> {
                            log("Server #$processedCount: delegating to loadExtractor: url=$embedUrl, referer=$mainPageHostReferer")
                            try {
                                val before = successCount
                                loadExtractor(embedUrl, mainPageHostReferer, subtitleCallback, callback)
                                val after = successCount
                                log("Server #$processedCount: loadExtractor done, links produced = ${after - before}")
                            } catch (t: Throwable) {
                                log("Server #$processedCount: loadExtractor threw: ${t.message}")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                log("Server #$processedCount: Exception processing server: ${t.message}")
            }
        }

        log("loadLinks completed. Processed $processedCount servers, total links produced: $successCount")

        try {
            java.io.File("server_log_${System.currentTimeMillis()}.txt").writeText(logBuilder.toString())
            log("Wrote debug log to file")
        } catch (_: Exception) {
            log("Failed to write debug log file")
        }

        return successCount > 0
    }
}
