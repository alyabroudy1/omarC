package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class EshkEmbedExtractor(
    override val mainUrl: String = "https://3esk.onl",
    override val name: String = "EshkEmbed"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedMatch = Regex("""(https://3esk\.onl/embed/)(\d+)/(.*)""").find(url)
        val foundUrls = mutableSetOf<String>()

        if (embedMatch != null) {
            val baseUrlPrefix = embedMatch.groupValues[1]
            val trailingPart = embedMatch.groupValues[3]
            for (serverNum in 1..5) {
                val currentEmbedUrl = "$baseUrlPrefix$serverNum/$trailingPart"
                foundUrls.addAll(processEmbedPage(currentEmbedUrl, url))
            }
        } else {
            foundUrls.addAll(processEmbedPage(url, referer ?: url))
        }

        for (link in foundUrls) {
            callback(
                newExtractorLink(name, name, link) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.type = inferType(link)
                }
            )
        }
    }

    private fun inferType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mp4") -> ExtractorLinkType.VIDEO
            url.contains(".webm") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
    }

    private suspend fun processEmbedPage(
        embedUrl: String,
        refererFromPrevPage: String
    ): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val r1 = app.get(embedUrl, referer = refererFromPrevPage)
            val text1 = r1.text

            Regex(
                """(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""",
                RegexOption.IGNORE_CASE
            ).findAll(text1).forEach { result.add(it.groupValues[1]) }

            result.addAll(extractFromEvalScripts(text1))

            val docIf1 = r1.document
            val iframeSrcs = docIf1.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
            if (iframeSrcs.isNotEmpty()) {
                val rFinal = app.get(iframeSrcs[0], referer = embedUrl)
                val t = rFinal.text
                Regex(
                    """(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""",
                    RegexOption.IGNORE_CASE
                ).findAll(t).forEach { result.add(it.groupValues[1]) }
                result.addAll(extractFromEvalScripts(t))
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun extractFromEvalScripts(htmlText: String): Set<String> {
        val found = mutableSetOf<String>()
        try {
            val doc = Jsoup.parse(htmlText)
            for (s in doc.select("script")) {
                val content = s.data().ifBlank { s.html() }
                if (content.contains("eval(")) {
                    val urls = unpackPackerAndFindMedia(content)
                    found.addAll(urls)
                }
            }
        } catch (_: Exception) {
        }
        return found
    }

    private fun unpackPackerAndFindMedia(evalText: String): List<String> {
        try {
            val startFn = evalText.indexOf("function(p,a,c,k,e,d)")
            if (startFn == -1) return emptyList()
            val braceOpen = evalText.indexOf('{', startFn)
            if (braceOpen == -1) return emptyList()
            val braceClose = findMatchingBrace(evalText, braceOpen)
            if (braceClose == -1) return emptyList()

            val argsStart = evalText.indexOf('(', braceClose)
            if (argsStart == -1) return emptyList()
            var i = argsStart + 1
            while (i < evalText.length && evalText[i].isWhitespace()) i++

            val (pVal, i2) = parseJsStringAt(evalText, i)
            if (pVal == null) return emptyList()
            i = i2
            while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++

            val aMatch = Regex("""\d+""").find(evalText.substring(i))
            if (aMatch == null) return emptyList()
            val aVal = aMatch.value.toInt()
            i += aMatch.range.last + 1
            while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++

            val cMatch = Regex("""\d+""").find(evalText.substring(i))
            if (cMatch == null) return emptyList()
            val cVal = cMatch.value.toInt()
            i += cMatch.range.last + 1
            while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++

            val kList = mutableListOf<String>()
            if (i < evalText.length && (evalText[i] == '"' || evalText[i] == '\'')) {
                val (kStr, _) = parseJsStringAt(evalText, i)
                if (kStr != null) kList.addAll(kStr.split("|"))
            } else {
                val m2 = Regex(
                    """(['"])(.*?)\1\s*\.split\s*\(\s*['"]\|['"]\s*\)""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(evalText)
                if (m2 != null) kList.addAll(m2.groupValues[2].split("|"))
            }

            var p = pVal
            for (idx in cVal - 1 downTo 0) {
                val key = intToBase36(idx)
                if (idx < kList.size && kList[idx].isNotEmpty()) {
                    p = Regex("\\b" + Regex.escape(key) + "\\b").replace(p ?: "") { kList[idx] }
                }
            }

            if (p != null) {
                return Regex(
                    """(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""",
                    RegexOption.IGNORE_CASE
                ).findAll(p).map { it.groupValues[1] }.toList()
            }
        } catch (_: Exception) {
        }
        return emptyList()
    }

    private fun findMatchingBrace(text: String, startIdx: Int): Int {
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

    private fun parseJsStringAt(text: String, idxInit: Int): Pair<String?, Int> {
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

    private fun jsStringUnescape(s: String): String {
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

    private fun intToBase36(n0: Int): String {
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
}
