package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
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
            val sectionDoc = org.jsoup.Jsoup.parse(section.html())
            val items = getParser().parseMainPage(sectionDoc).mapNotNull { item ->
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
                            val start = m.range.first
                            val sample = if (content.length > start + 10000) content.substring(start, start + 10000) else content.substring(start)
                            val (unpacked, err) = unpackPackerFromEval(sample)
                            if (unpacked != null) {
                                val mediaRegex = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                                mediaRegex.findAll(unpacked).forEach { found.add(it.groupValues[1]) }
                            }
                        }
                    }
                }
                return found
            } catch (e: Exception) {
                return emptyList()
            }
        }

        fun getAllIframeSrcs(doc: org.jsoup.nodes.Document): List<String> {
            return doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
        }

        suspend fun processSingleEmbedServer(
            embedUrl: String,
            refererFromPrevPage: String,
            headersBase: Map<String, String>,
            serverLabel: String = "unknown"
        ): Set<String> {
            val result = mutableSetOf<String>()
            try {
                val hdrs = headersBase.toMutableMap()
                hdrs["Referer"] = refererFromPrevPage
                val docIf1 = httpService.getDocument(embedUrl, headers = hdrs) ?: return result
                val text1 = docIf1.html()

                Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                    .findAll(text1).forEach { result.add(it.groupValues[1]) }

                analyzeAndSaveEvalScripts(text1).forEach { result.add(it) }

                val iframe1Srcs = getAllIframeSrcs(docIf1)
                if (iframe1Srcs.isNotEmpty()) {
                    val iframe2Src = iframe1Srcs[0]
                    val hdrs2 = hdrs.toMutableMap()
                    hdrs2["Referer"] = embedUrl
                    val docFinal = httpService.getDocument(iframe2Src, headers = hdrs2)
                    if (docFinal != null) {
                        val t = docFinal.html()
                        Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                            .findAll(t).forEach { result.add(it.groupValues[1]) }
                        analyzeAndSaveEvalScripts(t).forEach { result.add(it) }
                    }
                }
            } catch (e: Exception) {
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

            // ── Strategy 1 (preferred): a.single-watch-btn → aa.3isk.icu → JS vars → POST → iframe ──
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
                                for (ifr in iframeUrls) {
                                    val links = processSingleEmbedServer(ifr, redirectUrl, headers, "aa")
                                    links.forEach { foundAllMediaLinks.getOrPut(it) { mutableSetOf() }.add("aa") }
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
                    for (f in soup0.select("form")) {
                        val act = f.attr("action")
                        if (act.contains("3isk") || act.contains("aa.3isk") || act.contains("watch")) {
                            watchForm = f; break
                        }
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
                                for (baseIframeSrc in iframeSrcs) {
                                    val embedMatch = Regex("""(https://3esk\.onl/embed/)(\d+)/(.*)""").find(baseIframeSrc)
                                    if (embedMatch != null) {
                                        val baseUrlPrefix = embedMatch.groupValues[1]
                                        val trailingPart = embedMatch.groupValues[3]
                                        for (serverNum in 1..5) {
                                            val currentEmbedUrl = "$baseUrlPrefix$serverNum/$trailingPart"
                                            val links = processSingleEmbedServer(currentEmbedUrl, data, headers, serverNum.toString())
                                            links.forEach { foundAllMediaLinks.getOrPut(it) { mutableSetOf() }.add(serverNum.toString()) }
                                        }
                                    } else {
                                        val links = processSingleEmbedServer(baseIframeSrc, data, headers, "base")
                                        links.forEach { foundAllMediaLinks.getOrPut(it) { mutableSetOf() }.add("base") }
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
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } catch (e: Exception) {
                }
            }

            return true

        } catch (e: Exception) {
            return false
        }
    }
}
