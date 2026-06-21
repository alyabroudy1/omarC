package com.cloudstream.shared.extractors

import java.net.URLDecoder

/**
 * Deobfuscates eval()-packed JavaScript (P.a.c.k.e.r style).
 * Extracted from duplicate implementations in Gesseh/3isk providers.
 */
object EvalDeobfuscator {
    /**
     * Extract a URL from an eval-packed page.
     * Returns the first "file:" URL found in the deobfuscated JS, or null.
     */
    fun extractVideoUrl(pageText: String): String? {
        val evalRegex = Regex("eval\\s*\\(\\s*function\\s*\\(.*?\\)\\s*\\{.*?\\}\\s*\\((.*)\\)\\s*\\)")
        val evalMatch = evalRegex.find(pageText) ?: return null

        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null

        val paramsRegex = Regex("['\"](.*?)['\"]\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*['\"](.*?)['\"]\\.split\\s*\\(['\"]\\|['\"]\\)")
        val paramMatch = paramsRegex.find(paramsString) ?: return null

        val (packedCode, baseStr, countStr, dictionaryStr) = paramMatch.destructured
        val base = baseStr.toInt()
        val count = countStr.toInt()
        val keywords = dictionaryStr.split('|')

        val deobfuscatedJs = deobfuscate(packedCode, base, count, keywords)

        val fileRegex = Regex("[\"']?file[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
        val fileMatch = fileRegex.find(deobfuscatedJs)
        val finalUrl = fileMatch?.groupValues?.get(1) ?: return null
        return finalUrl.replace("\\/", "/")
    }

    private fun toBase(num: Int, radix: Int): String {
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

    private fun deobfuscate(p: String, a: Int, c: Int, k: List<String>): String {
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

    /**
     * Resolve a URL that may contain ?url=... or ?post=... encoded parameters.
     */
    fun resolveRealUrl(url: String): String {
        var currentUrl = url
        val urlMatch = Regex("[?&]url=([^&]+)").find(currentUrl)
        if (urlMatch != null) {
            try {
                var extractedUrl = URLDecoder.decode(urlMatch.groupValues[1], "UTF-8")
                if (!extractedUrl.startsWith("http")) {
                    val decodedBytes = android.util.Base64.decode(extractedUrl, android.util.Base64.DEFAULT)
                    extractedUrl = String(decodedBytes, Charsets.UTF_8).trim()
                }
                if (extractedUrl.startsWith("http")) {
                    currentUrl = extractedUrl
                }
            } catch (_: Exception) {}
        }
        return currentUrl
    }

    /**
     * Extract iframe/video source from HTML code fragment.
     */
    fun extractUrlFromCodeHtml(codeHtml: String?, base: String? = null): String? {
        if (codeHtml.isNullOrBlank()) return null
        val doc = org.jsoup.Jsoup.parse(codeHtml, base ?: "")
        doc.selectFirst("iframe[src]")?.attr("abs:src")?.let { return normalizeUrl(it, base) }
        doc.selectFirst("source[src]")?.attr("abs:src")?.let { return normalizeUrl(it, base) }
        doc.selectFirst("a[href]")?.attr("abs:href")?.let { return normalizeUrl(it, base) }
        Regex("https?:\\/\\/[^\\s\"']+").find(codeHtml)?.value?.let {
            return normalizeUrl(it, base)
        }
        return null
    }

    fun normalizeUrl(u: String?, base: String? = null): String? {
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
}
