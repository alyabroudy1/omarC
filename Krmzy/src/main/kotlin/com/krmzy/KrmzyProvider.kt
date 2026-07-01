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
import org.json.JSONObject

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
        println("[Krmzy] resolveSayyarhUrl: checking URL $url")
        val urlParam = Regex("[?&]url=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        if (urlParam == null) {
            println("[Krmzy] resolveSayyarhUrl: no url param found")
            return url
        }
        println("[Krmzy] resolveSayyarhUrl: found base64 param: ${urlParam.take(50)}...")
        return try {
            val decoded = String(Base64.decode(urlParam, Base64.DEFAULT), Charsets.UTF_8).trim()
            println("[Krmzy] resolveSayyarhUrl: decoded to: $decoded")
            if (decoded.startsWith("http")) decoded else url
        } catch (e: Exception) {
            println("[Krmzy] resolveSayyarhUrl: base64 decode failed: ${e.message}")
            url
        }
    }

    private suspend fun extractLinkFromObfuscatedPage(
        url: String,
        referer: String,
        logCallback: (String) -> Unit
    ): String? {
        val pageText = try {
            logCallback("Custom Extractor: Fetching page $url with referer $referer")
            httpService.getText(url, headers = mapOf("Referer" to referer), rewriteDomain = false)
        } catch (e: Exception) {
            logCallback("Custom Extractor ERROR: Failed to fetch page $url. Exception: ${e.message}")
            return null
        }

        if (pageText == null) {
            logCallback("Custom Extractor ERROR: httpService.getText returned null for $url")
            return null
        }
        logCallback("Custom Extractor: Page fetched, length=${pageText.length}")

        val evalRegex = Regex("""eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""")
        val evalMatch = evalRegex.find(pageText)
        if (evalMatch == null) {
            logCallback("Custom Extractor ERROR: evalRegex did not find a match. Page snippet: ${pageText.take(500)}")
            return null
        }

        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null
        logCallback("Custom Extractor: Found eval params, length=${paramsString.length}")

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
        logCallback("Custom Extractor: base=$base, count=$count, keywords=${keywords.size}")

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

        logCallback("Custom Extractor: Deobfuscated JS start: ${deobfuscatedJs.take(200)}")

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

        logCallback("checkWorkingStreamReferer: Candidates: $candidates")

        for (ref in candidates) {
            try {
                val resp = httpService.getRaw(streamUrl, headers = mapOf("Referer" to ref))
                val code = resp.code
                if (code == 200) {
                    logCallback("checkWorkingStreamReferer: Referer works: $ref (code=$code)")
                    return ref
                } else {
                    logCallback("checkWorkingStreamReferer: Referer failed (code=$code): $ref")
                }
            } catch (e: Exception) {
                logCallback("checkWorkingStreamReferer: Error for $ref: ${e.message}")
            }
        }

        logCallback("checkWorkingStreamReferer: All checks failed. Defaulting to: $iframeHostReferer")
        return iframeHostReferer
    }

    private suspend fun extractLinkFromObfuscatedPage(
        url: String,
        referers: List<String>,
        logCallback: (String) -> Unit
    ): String? {
        var pageText: String? = null
        var usedReferer: String? = null

        for (ref in referers) {
            try {
                logCallback("Custom Extractor v2: Trying referer: $ref")
                val text = httpService.getText(url, headers = mapOf("Referer" to ref), rewriteDomain = false)

                if (text != null && text.contains("eval(function")) {
                    pageText = text
                    usedReferer = ref
                    logCallback("Custom Extractor v2: Success with referer: $ref (length=${text.length})")
                    break
                } else {
                    logCallback("Custom Extractor v2: No eval with referer: $ref (text=${text?.take(100) ?: "null"})")
                }
            } catch (e: Exception) {
                logCallback("Custom Extractor v2: Error with referer $ref: ${e.message}")
            }
        }

        if (pageText == null) {
            logCallback("Custom Extractor v2: Failed with ALL referers. Last attempted: $usedReferer")
            return null
        }

        logCallback("Custom Extractor v2: Page snippet: ${pageText.take(300)}")

        val evalRegex = Regex("""eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""")
        val evalMatch = evalRegex.find(pageText)
        if (evalMatch == null) {
            logCallback("Custom Extractor v2 ERROR: evalRegex did not find a match.")
            return null
        }

        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null
        logCallback("Custom Extractor v2: Found eval params, length=${paramsString.length}")

        val paramsRegex = Regex("""['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(['"]\|['"]\)""")
        val paramMatch = paramsRegex.find(paramsString)
        if (paramMatch == null) {
            logCallback("Custom Extractor v2 ERROR: paramsRegex failed on: '${paramsString.take(100)}...'")
            return null
        }

        val (packedCode, baseStr, countStr, dictionaryStr) = paramMatch.destructured
        val base = baseStr.toInt()
        val count = countStr.toInt()
        val keywords = dictionaryStr.split('|')
        logCallback("Custom Extractor v2: base=$base, count=$count, keywords=${keywords.size}")

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

        logCallback("Custom Extractor v2: Deobfuscated JS start: ${deobfuscatedJs.take(200)}")

        val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")
        val fileMatch = fileRegex.find(deobfuscatedJs)

        if (fileMatch == null) {
            logCallback("Custom Extractor v2 ERROR: fileRegex did not find a match.")
            return null
        }

        val finalUrl = fileMatch.groupValues[1]
        val cleanUrl = finalUrl.replace("\\/", "/")

        logCallback("Custom Extractor v2: Success! Found URL: $cleanUrl")
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

        log("========== Krmzy loadLinks START ==========")
        log("Input data: $data")

        val resolvedData = resolveSayyarhUrl(data)
        if (resolvedData != data) {
            log("resolveSayyarhUrl: modified URL to: $resolvedData")
        } else {
            log("resolveSayyarhUrl: URL not modified")
        }

        val mainPageHostReferer = try {
            val uri = java.net.URI(resolvedData)
            "${uri.scheme}://${uri.host}/"
        } catch (e: Exception) {
            log("URI parse error: ${e.message}, using raw")
            resolvedData
        }
        log("mainPageHostReferer = $mainPageHostReferer")

        log("STEP 1: Fetching episode page via httpService.getDocument...")
        val episodePage = try {
            httpService.getDocument(resolvedData, rewriteDomain = true)
        } catch (t: Throwable) {
            log("STEP 1 ERROR: httpService.getDocument threw: ${t.message}")
            return false
        }
        if (episodePage == null) {
            log("STEP 1 ERROR: httpService.getDocument returned null for $resolvedData")
            return false
        }
        log("STEP 1: Episode page fetched successfully. Title: '${episodePage.title()}'")
        log("STEP 1: Page URL (after redirects): TODO (httpService doesn't expose final URL)")

        log("STEP 2: Looking for a.fullscreen-clickable...")
        val extractorUrl = episodePage.selectFirst("a.fullscreen-clickable")?.attr("href")
        log("STEP 2: extractorUrl = ${extractorUrl ?: "NULL"}")
        if (extractorUrl.isNullOrBlank()) {
            log("STEP 2 ERROR: No a.fullscreen-clickable found. Page HTML snippet: ${episodePage.html().take(800)}")
            log("========== Krmzy loadLinks END (failure) ==========")
            return false
        }

        if (extractorUrl.endsWith(".m3u8", ignoreCase = true) || extractorUrl.endsWith(".mp4", ignoreCase = true)) {
            log("STEP 2: Extractor URL is direct media (.m3u8/.mp4), invoking callback")
            callback.invoke(
                newExtractorLink(source = this.name, name = this.name, url = extractorUrl) {
                    this.quality = Qualities.Unknown.value
                }
            )
            log("========== Krmzy loadLinks END (direct media success) ==========")
            return true
        }

        log("STEP 3: Resolving extractor URL chain...")

        data class ServerEntry(val name: String, val id: String)
        val serversFromJson = mutableListOf<ServerEntry>()
        var resolvedExtractorUrl = extractorUrl

        val urlParam = Regex("[?&]url=([^&]+)").find(resolvedExtractorUrl)?.groupValues?.getOrNull(1)
        if (urlParam != null) {
            try {
                var innerUrl = java.net.URLDecoder.decode(urlParam, "UTF-8")
                if (!innerUrl.startsWith("http")) {
                    innerUrl = String(Base64.decode(innerUrl, Base64.DEFAULT), Charsets.UTF_8).trim()
                }
                log("STEP 3: Resolved inner URL from url param: $innerUrl")
                resolvedExtractorUrl = innerUrl
            } catch (e: Exception) {
                log("STEP 3: Failed to decode url param: ${e.message}")
            }
        }

        val postMatch = Regex("[?&]post=([^&]+)").find(resolvedExtractorUrl)
        if (postMatch != null) {
            log("STEP 3: Found post param, decoding JSON...")
            try {
                var postEncoded = java.net.URLDecoder.decode(postMatch.groupValues[1], "UTF-8")
                val decodedBytes = Base64.decode(postEncoded, Base64.DEFAULT)
                val jsonString = String(decodedBytes, Charsets.UTF_8)
                log("STEP 3: Decoded post JSON: ${jsonString.take(300)}")

                val root = JSONObject(jsonString)
                val serversArr = root.optJSONArray("servers")
                if (serversArr != null && serversArr.length() > 0) {
                    log("STEP 3: Found ${serversArr.length()} servers in JSON")
                    for (i in 0 until serversArr.length()) {
                        val s = serversArr.getJSONObject(i)
                        val name = s.optString("name", "").trim()
                        val id = s.optString("id", "").trim()
                        if (name.isNotBlank() && id.isNotBlank()) {
                            serversFromJson.add(ServerEntry(name, id))
                            log("STEP 3: JSON server: name='$name' id='$id'")
                        }
                    }
                } else {
                    log("STEP 3: No servers array in JSON")
                }

                val backUrl = root.optString("backUrl", "")
                if (!backUrl.isNullOrBlank()) {
                    log("STEP 3: JSON backUrl: $backUrl")
                }
            } catch (e: Exception) {
                log("STEP 3: Failed to decode post JSON: ${e.message}")
            }
        }

        var serverItems = mutableListOf<org.jsoup.nodes.Element>()

        if (serversFromJson.isEmpty()) {
            log("STEP 4: No JSON servers, trying HTML fetch for ul.serversList li...")
            val extractorPage = try {
                httpService.getDocument(resolvedExtractorUrl, headers = mapOf("Referer" to mainPageHostReferer), rewriteDomain = false)
            } catch (t: Throwable) {
                log("STEP 4 ERROR: httpService.getDocument threw: ${t.message}")
                null
            }
            if (extractorPage != null) {
                log("STEP 4: Extractor page title: '${extractorPage.title()}'")
                val items = extractorPage.select("ul.serversList li")
                log("STEP 4: Found ${items.size} li items")
                serverItems.addAll(items)
            } else {
                log("STEP 4: Extractor page fetch returned null")
            }
        } else {
            log("STEP 4: Using ${serversFromJson.size} servers from JSON (skipping HTML fetch)")
        }

        if (serverItems.isEmpty() && serversFromJson.isEmpty()) {
            log("STEP 4 ERROR: No servers found from JSON or HTML")
            log("========== Krmzy loadLinks END (no servers) ==========")
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

        fun buildEmbedUrl(serverName: String, serverId: String): String? {
            val lower = serverName.lowercase().trim()
            log("buildEmbedUrl: name='$serverName' id='$serverId'")
            return when {
                lower.contains("youtube_in") || lower.contains("youtube-in") -> "https://www.youtube.com/embed/$serverId"
                lower.contains("youtube") -> "https://www.youtube.com/embed/$serverId"
                lower == "express" -> serverId
                lower.contains("dailymotion") -> "https://www.dailymotion.com/embed/video/$serverId"
                lower.contains("facebook") -> "https://app.videas.fr/embed/media/$serverId"
                lower.contains("estream") -> "https://arabveturk.com/embed-$serverId.html"
                lower.contains("arab hd") || lower.contains("arabhd") || lower.contains("arab-hd") -> "https://v.turkvearab.com/embed-$serverId.html"
                lower.contains("red hd") || lower.contains("redhd") || lower.contains("red-hd") -> null
                lower.contains("pro hd") || lower.contains("prohd") || lower.contains("pro-hd") -> "https://ebtv.upns.live/#$serverId"
                lower == "pro" -> "https://mdna.upns.online/#$serverId"
                lower.contains("ok") -> "https://ok.ru/videoembed/$serverId"
                lower.contains("box") -> "https://youdboox.com/embed-$serverId.html"
                lower.contains("now") -> "https://extreamnow.org/embed-$serverId.html"
                else -> null
            }
        }

        data class EmbedTarget(val name: String, val url: String)
        val targets = mutableListOf<EmbedTarget>()

        for (s in serversFromJson) {
            val embedUrl = buildEmbedUrl(s.name, s.id)
            if (!embedUrl.isNullOrBlank()) {
                targets.add(EmbedTarget(s.name, embedUrl))
                log("JSON server -> embed: name='${s.name}' url='$embedUrl'")
            } else {
                log("JSON server skipped: name='${s.name}' (no embed URL)")
            }
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
                    "red hd", "redhd", "red-hd" -> null
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
                    targets.add(EmbedTarget(serverTypeRaw, embedUrl))
                }
            } catch (_: Exception) {}
        }

        log("STEP 5: Total targets to process: ${targets.size}")
        if (targets.isEmpty()) {
            log("STEP 5: No embed targets to process")
            log("========== Krmzy loadLinks END (no targets) ==========")
            return false
        }

        var processedCount = 0
        var successCount = 0

        for (target in targets) {
            processedCount++
            val embedUrl = target.url
            val serverTypeRaw = target.name
            val serverType = serverTypeRaw.lowercase().trim()

            log("--- Server #$processedCount/${targets.size} ---")
            log("Server #$processedCount: name='$serverTypeRaw' embedUrl='$embedUrl'")

            try {
                when {
                    serverType.contains("youtube") -> {
                        log("Server #$processedCount: YouTube route")
                        callback.invoke(
                            newExtractorLink(source = this.name, name = "YouTube", url = embedUrl) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        successCount++
                    }
                    embedUrl.contains(".m3u8") || embedUrl.endsWith(".mp4") -> {
                        log("Server #$processedCount: direct media route")
                        callback.invoke(
                            newExtractorLink(source = this.name, name = serverTypeRaw, url = embedUrl) {
                                this.quality = Qualities.Unknown.value
                                this.referer = mainPageHostReferer
                            }
                        )
                        successCount++
                    }
                    serverType.contains("estream") || serverType.contains("arab hd") || serverType.contains("arabhd") || serverType.contains("arab-hd") -> {
                        log("Server #$processedCount: custom extraction route")
                        try {
                            val fetchReferers = listOf(mainPageHostReferer, "https://newaat.com/")
                            val extractedM3u8 = extractLinkFromObfuscatedPage(embedUrl, fetchReferers, ::log)

                            if (!extractedM3u8.isNullOrBlank()) {
                                log("Server #$processedCount: extracted M3U8 = $extractedM3u8")
                                val workingReferer = checkWorkingStreamReferer(extractedM3u8, embedUrl, ::log)
                                log("Server #$processedCount: working referer = $workingReferer")

                                val qualityLinks = M3u8Helper.generateM3u8(
                                    source = this.name,
                                    streamUrl = extractedM3u8,
                                    referer = workingReferer,
                                    headers = mapOf("Origin" to workingReferer.trimEnd('/'))
                                )
                                log("Server #$processedCount: generateM3u8 returned ${qualityLinks.size} links")

                                if (qualityLinks.isNotEmpty()) {
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
                            log("Server #$processedCount: EXCEPTION in custom extraction: ${t.message}")
                        }
                    }
                    else -> {
                        log("Server #$processedCount: delegating to loadExtractor: url=$embedUrl, referer=$mainPageHostReferer")
                        try {
                            val before = successCount
                            loadExtractor(embedUrl, mainPageHostReferer, subtitleCallback, callback)
                            val after = successCount
                            log("Server #$processedCount: loadExtractor done, new links = ${after - before}")
                        } catch (t: Throwable) {
                            log("Server #$processedCount: loadExtractor threw: ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                log("Server #$processedCount: EXCEPTION: ${t.message}")
            }
        }

        log("========== Krmzy loadLinks SUMMARY ==========")
        log("Servers processed: $processedCount")
        log("Links produced: $successCount")
        log("Returning: ${successCount > 0}")

        try {
            java.io.File("krmzy_debug_${System.currentTimeMillis()}.txt").writeText(logBuilder.toString())
            log("Wrote debug log to krmzy_debug_*.txt")
        } catch (e: Exception) {
            log("Failed to write debug log file: ${e.message}")
        }

        return successCount > 0
    }
}
