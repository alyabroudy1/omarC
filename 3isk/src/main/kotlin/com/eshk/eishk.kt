package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document

class eishk : BaseProvider() {
    override val providerName get() = "قصة عشق"
    override val baseDomain get() = "3esk.onl"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/eishk.json"

    override fun getParser(): NewBaseParser = EshkParser()

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true
    override val supportsLazySearch = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        httpService.ensureInitialized()
        val doc = httpService.getDocument(mainUrl, checkDomainChange = true, rewriteDomain = true) ?: return null
        val all = ArrayList<HomePageList>()

        doc.select("section.home-items-sec").forEach { section ->
            val sectionTitle = section.selectFirst(".sec-title")?.text() ?: return@forEach
            val items = getParser().parseSection(section).mapNotNull { item ->
                newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                }
            }
            if (items.isNotEmpty()) all.add(HomePageList(sectionTitle, items))
        }
        return newHomePageResponse(all)
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = getParser().getSearchUrl(mainUrl, encoded)
        val doc = httpService.getDocumentNoFallback(url, checkDomainChange = true, rewriteDomain = true)
            ?: throw com.cloudstream.shared.service.CloudflareBlockedSearchException(name, baseDomain)
        val items = getParser().parseSearch(doc)
        return items.map { item ->
            newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = item.posterUrl
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG = "Qesat3eshqProvider"
        val MEDIA_REGEX = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)

        fun jsStringUnescape(s: String): String {
            val regex = Regex("""\\u[0-9a-fA-F]{4}|\\x[0-9a-fA-F]{2}|\\.|\\n|\\r|\\t""")
            return regex.replace(s) { m ->
                val esc = m.value
                try {
                    when {
                        esc.startsWith("\\x") -> esc.substring(2).toInt(16).toChar().toString()
                        esc.startsWith("\\u") -> esc.substring(2).toInt(16).toChar().toString()
                        esc == "\\n" -> "\n"
                        esc == "\\r" -> "\r"
                        esc == "\\t" -> "\t"
                        esc == "\\'" -> "'"
                        esc == "\\\"" -> "\""
                        esc == "\\\\" -> "\\"
                        else -> if (esc.length >= 2 && esc[0] == '\\') esc.substring(1) else esc
                    }
                } catch (_: Exception) {
                    esc
                }
            }
        }

        fun intToBase36(n0: Int): String {
            if (n0 == 0) return "0"
            var n = n0
            val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(chars[n % 36])
                n /= 36
            }
            return sb.reverse().toString()
        }

        fun parseJsStringAt(text: String, idxInit: Int): Pair<String?, Int> {
            var idx = idxInit
            if (idx >= text.length) return Pair(null, idx)
            val quote = text[idx]
            if (quote != '"' && quote != '\'') return Pair(null, idx)
            idx += 1
            val out = StringBuilder()
            while (idx < text.length) {
                val ch = text[idx]
                if (ch == '\\') {
                    if (idx + 1 < text.length) {
                        out.append(text.substring(idx, idx + 2))
                        idx += 2
                    } else {
                        idx++
                    }
                } else if (ch == quote) {
                    val valStr = out.toString()
                    return Pair(jsStringUnescape(valStr), idx + 1)
                } else {
                    out.append(ch)
                    idx++
                }
            }
            return Pair(null, idx)
        }

        fun findMatchingBrace(text: String, startIdx: Int): Int {
            if (startIdx < 0 || startIdx >= text.length || text[startIdx] != '{') return -1
            var depth = 0
            var i = startIdx
            while (i < text.length) {
                val ch = text[i]
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return -1
        }

        fun unpackPackerFromEval(evalText: String): Pair<String?, String?> {
            try {
                val startFn = evalText.indexOf("function(p,a,c,k,e,d)")
                if (startFn == -1) return Pair(null, "no function signature")
                val braceOpen = evalText.indexOf('{', startFn)
                if (braceOpen == -1) return Pair(null, "no opening brace")
                val braceClose = findMatchingBrace(evalText, braceOpen)
                if (braceClose == -1) return Pair(null, "no matching brace found for function body")
                val argsStart = evalText.indexOf('(', braceClose)
                if (argsStart == -1) return Pair(null, "no args start found")
                var i = argsStart + 1
                while (i < evalText.length && evalText[i].isWhitespace()) i++
                val (pVal, newI) = parseJsStringAt(evalText, i); i = newI
                if (pVal == null) return Pair(null, "cannot parse p string")
                while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
                val aMatch = Regex("""\d+""").find(evalText.substring(i))
                if (aMatch == null) return Pair(null, "cannot parse a")
                i += aMatch.range.last + 1
                while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
                val cMatch = Regex("""\d+""").find(evalText.substring(i))
                if (cMatch == null) return Pair(null, "cannot parse c")
                val cVal = cMatch.value.toInt()
                i += cMatch.range.last + 1
                while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
                val kList = mutableListOf<String>()
                if (i < evalText.length && (evalText[i] == '"' || evalText[i] == '\'')) {
                    val (kStr, i2) = parseJsStringAt(evalText, i)
                    i = i2
                    if (kStr != null) {
                        kList.addAll(kStr.split("|"))
                    }
                } else {
                    val m2 = Regex("""(['"])(.*?)\1\s*\.split\s*\(\s*['"]\|['"]\s*\)""", RegexOption.DOT_MATCHES_ALL).find(evalText)
                    if (m2 != null) {
                        kList.addAll(m2.groupValues[2].split("|"))
                    }
                }

                var p = pVal
                for (idx in cVal - 1 downTo 0) {
                    val key = intToBase36(idx)
                    if (idx < kList.size && kList[idx].isNotEmpty()) {
                        p = Regex("\\b" + Regex.escape(key) + "\\b").replace(p ?: "") { kList[idx] }
                    }
                }
                return Pair(p, null)
            } catch (e: Exception) {
                return Pair(null, "exception:${e.message}")
            }
        }

        fun analyzeAndSaveEvalScripts(htmlText: String): List<String> {
            try {
                val doc = org.jsoup.Jsoup.parse(htmlText)
                val scripts = doc.select("script")
                val found = mutableListOf<String>()
                for (s in scripts) {
                    val content = s.data().ifBlank { s.html() }
                    if (content.contains("eval(")) {
                        val m = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{""").find(content)
                        if (m != null) {
                            val sample = content.substring(m.range.first)
                            val (unpacked, err) = unpackPackerFromEval(sample)
                            if (unpacked != null) {
                                MEDIA_REGEX.findAll(unpacked).forEach { found.add(it.groupValues[1]) }
                            }
                        }
                    }
                }
                return found
            } catch (e: Exception) {
                Log.w(TAG, "analyzeAndSaveEvalScripts failed: ${e.message}")
                return emptyList()
            }
        }

        fun getAllIframeSrcs(doc: org.jsoup.nodes.Document): List<String> {
            return doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
        }

        fun expandEmbedVariants(iframeUrl: String, labelPrefix: String): List<Pair<String, String>> {
            val handlers = listOf("embed", "player", "watch", "video", "episode")
            for (handler in handlers) {
                val m = Regex("""(https?://[^/]+)/$handler/(\d+)(/.*|$)""").find(iframeUrl)
                if (m != null) {
                    val base = m.groupValues[1]
                    val pathSuffix = m.groupValues[3]
                    return (1..5).map { n -> "$base/$handler/$n$pathSuffix" to "$labelPrefix-s$n" }
                }
            }
            val genericM = Regex("""(https?://[^/]+(?:/[^/]+)*/)(\d)(/.*|$)""").find(iframeUrl)
            if (genericM != null) {
                val prefix = genericM.groupValues[1]
                val origNum = genericM.groupValues[2]
                val suffix = genericM.groupValues[3]
                val list = mutableListOf(iframeUrl to "$labelPrefix-orig")
                for (n in 1..5) {
                    if (n.toString() != origNum) list.add("$prefix$n$suffix" to "$labelPrefix-s$n")
                }
                return list
            }
            return listOf(iframeUrl to "$labelPrefix-other")
        }

        suspend fun processSingleEmbedServer(
            embedUrl: String,
            refererFromPrevPage: String,
            headersBase: Map<String, String>,
            serverLabel: String = "unknown",
            maxDepth: Int = 2
        ): Set<String> {
            val result = mutableSetOf<String>()

            suspend fun extractFromPage(
                pageUrl: String,
                referer: String,
                depth: Int
            ) {
                if (depth > maxDepth) return
                try {
                    val hdrs = headersBase.toMutableMap()
                    hdrs["Referer"] = referer
                    val doc = httpService.getDocument(pageUrl, headers = hdrs) ?: return
                    val html = doc.html()

                    MEDIA_REGEX.findAll(html).forEach { result.add(it.groupValues[1]) }
                    analyzeAndSaveEvalScripts(html).forEach { result.add(it) }

                    // Try aa.3isk.icu or similar POST flow on this page
                    try {
                        val mMyurl = Regex("""var\s+myUrl\s*=\s*["']([^"']+)["']""").find(html)
                        val mInputVal = Regex("""myInput\.value\s*=\s*["']([^"']+)["']""").find(html)
                        if (mMyurl != null && mInputVal != null) {
                            val postUrl = mMyurl.groupValues[1]
                            val inputVal = mInputVal.groupValues[1]
                            val mMydUrl = Regex("""var\s+mydUrl\s*=\s*["']([^"']*)["']""").find(html)
                            val uVal = mMydUrl?.groupValues?.get(1) ?: ""
                            val postData = mapOf("news" to inputVal, "u" to uVal, "submit" to "submit")
                            val postHeaders = headersBase.toMutableMap()
                            postHeaders["Referer"] = pageUrl
                            val postResp = httpService.postText(postUrl, postData, referer = pageUrl, headers = postHeaders)
                            if (postResp != null) {
                                val postDoc = org.jsoup.Jsoup.parse(postResp)
                                val postHtml = postDoc.html()
                                MEDIA_REGEX.findAll(postHtml).forEach { result.add(it.groupValues[1]) }
                                analyzeAndSaveEvalScripts(postHtml).forEach { result.add(it) }
                                // Follow iframes from POST response
                                getAllIframeSrcs(postDoc).forEach { iframe ->
                                    extractFromPage(iframe, postUrl, depth + 1)
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    // Follow ALL iframes on the page (not just the first one)
                    getAllIframeSrcs(doc).forEach { iframe ->
                        extractFromPage(iframe, pageUrl, depth + 1)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extractFromPage($serverLabel, depth=$depth) failed: ${e.message}")
                }
            }

            try {
                extractFromPage(embedUrl, refererFromPrevPage, 0)
            } catch (e: Exception) {
                Log.w(TAG, "processSingleEmbedServer($serverLabel) failed: ${e.message}")
            }
            return result
        }

        try {
            val r0 = try {
                httpService.getDocument(data, checkDomainChange = true, rewriteDomain = true)
            } catch (e: Exception) {
                Log.e(TAG, "Initial GET failed: ${e.message}"); return false
            }
            if (r0 == null) {
                Log.e(TAG, "Initial GET returned null")
                return false
            }

            val soup0 = r0
            val headers = mapOf<String, String>()
            val foundAllMediaLinks = mutableMapOf<String, MutableSet<String>>()

            // ── Strategy 0 (direct): construct embed URLs from page data, bypass proxy ──
            try {
                val pageHtml = soup0.html()
                val postId = soup0.selectFirst("input#comment_post_ID")?.attr("value")?.toIntOrNull()
                val seMatch = Regex("""cl_se_eps\s*=\s*\{[^}]*"se"\s*:\s*"(\d+)"[^}]*\}""").find(pageHtml)
                val embedBase = Regex(""""embed"\s*:\s*"([^"]+)""").find(pageHtml)?.groupValues?.get(1)?.trimEnd('/')
                    ?: soup0.baseUri()?.let { Regex("""https?://[^/]+""").find(it)?.value }
                    ?: mainUrl
                if (postId != null && seMatch != null) {
                    val season = seMatch.groupValues[1]
                    Log.d(TAG, "Strategy 0: direct embed (post=$postId, season=$season, base=$embedBase)")
                    coroutineScope {
                        val deferreds = (1..5).map { serverNum ->
                            async {
                                val url = "$embedBase/embed/$serverNum/$postId/$season/"
                                processSingleEmbedServer(url, data, headers, "s0-s$serverNum")
                            }
                        }
                        for (deferred in deferreds) {
                            deferred.await().forEach { foundAllMediaLinks.getOrPut(it) { mutableSetOf() }.add("s0") }
                        }
                    }
                } else {
                    Log.d(TAG, "Strategy 0 skipped: postId=$postId, seMatch=${seMatch != null}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Strategy 0 failed: ${e.message}")
            }

            // ── Strategy 1 (proxy): a.single-watch-btn → aa.3isk.icu → JS vars → POST → iframe ──
            val watchAnchor = soup0.selectFirst("a.single-watch-btn")
            if (watchAnchor != null) {
                val redirectUrl = watchAnchor.attr("href").ifBlank { watchAnchor.attr("abs:href") }
                if (redirectUrl.isNotBlank()) {
                    Log.d(TAG, "Strategy 1: following a.single-watch-btn → $redirectUrl")
                    val aaDoc = try {
                        httpService.getDocument(redirectUrl, headers = headers)
                    } catch (e: Exception) {
                        Log.e(TAG, "Strategy 1: GET failed: ${e.message}"); null
                    }
                    if (aaDoc != null) {
                        val aaHtml = aaDoc.html()
                        val mMyurl = Regex("""var\s+myUrl\s*=\s*["']([^"']+)["']""").find(aaHtml)
                        val mInputVal = Regex("""myInput\.value\s*=\s*["']([^"']+)["']""").find(aaHtml)
                        val mMydUrl = Regex("""var\s+mydUrl\s*=\s*["']([^"']*)["']""").find(aaHtml)
                        if (mMyurl != null && mInputVal != null) {
                            val postUrl = mMyurl.groupValues[1]
                            val inputVal = mInputVal.groupValues[1]
                            val uVal = mMydUrl?.groupValues?.get(1) ?: ""
                            val postData = mapOf("news" to inputVal, "u" to uVal, "submit" to "submit")
                            Log.d(TAG, "Strategy 1: POST to $postUrl")
                            val r2Text = try {
                                httpService.postText(postUrl, postData, referer = redirectUrl, headers = headers)
                            } catch (e: Exception) {
                                Log.e(TAG, "Strategy 1: POST failed: ${e.message}"); null
                            }
                            if (r2Text != null) {
                                val soup2 = org.jsoup.Jsoup.parse(r2Text)
                                val iframeUrls = getAllIframeSrcs(soup2)
                                Log.d(TAG, "Strategy 1: found ${iframeUrls.size} iframes in POST response")
                                coroutineScope {
                                    val deferreds = iframeUrls.flatMap { ifr ->
                                        expandEmbedVariants(ifr, "s1").map { (url, label) ->
                                            async { processSingleEmbedServer(url, redirectUrl, headers, label) } to label
                                        }
                                    }
                                    for ((deferred, label) in deferreds) {
                                        deferred.await().forEach { url ->
                                            foundAllMediaLinks.getOrPut(url) { mutableSetOf() }.add(label)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Strategy 2 (fallback): form-based POST flow (legacy) ──
            if (foundAllMediaLinks.isEmpty()) {
                Log.d(TAG, "Strategy 2: legacy form-based POST flow")
                var watchForm = soup0.selectFirst("button.single-watch-btn")?.let { it.parent() }
                if (watchForm == null) {
                    watchForm = soup0.select("form").firstOrNull { f ->
                        f.attr("action").isNotBlank() && !f.select("input[type=hidden]").isEmpty()
                    }
                }
                if (watchForm != null) {
                    val firstPostUrl = watchForm.attr("action")
                    val firstFormData = watchForm.select("input[type=hidden]")
                        .associate { it.attr("name") to it.attr("value") }.toMutableMap()
                    val watchBtn = soup0.selectFirst("button.single-watch-btn")
                    if (watchBtn != null) {
                        val btnName = watchBtn.attr("name")
                        if (btnName.isNotBlank()) firstFormData[btnName] = watchBtn.attr("value")
                    }
                    val r1Text = try {
                        httpService.postText(firstPostUrl, firstFormData, referer = data, headers = headers)
                    } catch (e: Exception) {
                        Log.e(TAG, "Strategy 2: POST failed: ${e.message}"); null
                    }
                    if (r1Text != null) {
                        val mMyurl = Regex("""var\s+myUrl\s*=\s*["']([^"']+)["']""").find(r1Text)
                        val mNews = Regex("""myInput\.value\s*=\s*["']([^"']+)["']""").find(r1Text)
                        if (mMyurl != null && mNews != null) {
                            val nextPost = mMyurl.groupValues[1]
                            val newsVal = mNews.groupValues[1]
                            val post2Data = mapOf("news" to newsVal, "u" to "", "submit" to "submit")
                            val r2Text = try {
                                httpService.postText(nextPost, post2Data, referer = data, headers = headers)
                            } catch (e: Exception) {
                                Log.e(TAG, "Strategy 2: POST2 failed: ${e.message}"); null
                            }
                            if (r2Text != null) {
                                val soup2 = org.jsoup.Jsoup.parse(r2Text)
                                val iframeSrcs = getAllIframeSrcs(soup2)
                                Log.d(TAG, "Strategy 2: found ${iframeSrcs.size} iframes in POST2 response")
                                coroutineScope {
                                    val deferreds = iframeSrcs.flatMap { ifr ->
                                        expandEmbedVariants(ifr, "s2").map { (url, label) ->
                                            async { processSingleEmbedServer(url, data, headers, label) } to label
                                        }
                                    }
                                    for ((deferred, label) in deferreds) {
                                        deferred.await().forEach { url ->
                                            foundAllMediaLinks.getOrPut(url) { mutableSetOf() }.add(label)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (foundAllMediaLinks.isEmpty()) {
                return false
            }

            for ((link, servers) in foundAllMediaLinks) {
                try {
                    val serverLabel = servers.firstOrNull()
                    val labelNum = serverLabel?.let { Regex("""(\d+)""").findAll(it).lastOrNull()?.groupValues?.get(1) }
                    val displayName = if (labelNum != null) "سيرفر $labelNum" else this.name
                    callback.invoke(
                        newExtractorLink(
                            source = displayName,
                            name = displayName,
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to invoke callback for a link: ${e.message}")
                }
            }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "loadLinks top-level error: ${e.message}")
            return false
        }
    }
}
