package com.cloudstream.shared.extractors

import com.lagradost.api.Log

/**
 * Shared JS Packer (eval(function(p,a,c,k,e,d){...})) deobfuscation utility.
 * Extracted from duplicate implementations in eseen (eseek/) and Krmzy providers.
 * 
 * The P.a.c.k.e.r format: eval(function(p,a,c,k,e,d){...})('packed',62,4,'keyword1|keyword2|...'.split('|'))
 * - p: packed/encoded string with base-N placeholders
 * - a: radix (usually 62)
 * - c: count of keywords
 * - k: dictionary of keywords split by '|'
 */
object PackerUnpacker {
    private const val TAG = "PackerUnpacker"

    /**
     * Deobfuscate a JS Packer encoded string.
     * Replaces base-N tokens in the packed code with their dictionary values.
     *
     * @param p The packed/encoded code string
     * @param a The radix (base) used for encoding (e.g., 62)
     * @param c The number of keywords in the dictionary
     * @param k The dictionary of keywords (split by '|' from the original)
     * @return Deobfuscated JavaScript code
     */
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

    /**
     * Extract and deobfuscate a JS Packer encoded video URL from HTML.
     *
     * @param html The HTML page text
     * @return The extracted video URL, or null if not found
     */
    fun extractFromHtml(html: String): String? {
        val evalRegex = Regex("""eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""")
        val evalMatch = evalRegex.find(html) ?: return null

        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null

        val paramsRegex = Regex("""['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(['"]\|['"]\)""")
        val paramMatch = paramsRegex.find(paramsString) ?: return null

        val (packedCode, baseStr, countStr, dictionaryStr) = paramMatch.destructured
        val base = baseStr.toInt()
        val count = countStr.toInt()
        val keywords = dictionaryStr.split('|')

        val deobfuscatedJs = deobfuscate(packedCode, base, count, keywords)

        val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")
        val fileMatch = fileRegex.find(deobfuscatedJs)
        val finalUrl = fileMatch?.groupValues?.get(1) ?: return null

        return finalUrl.replace("\\/", "/")
    }

    /**
     * Extract and deobfuscate from a full page fetch + regex.
     * Convenience wrapper for providers that fetch the page themselves.
     */
    fun extractFromText(pageText: String, logCallback: ((String) -> Unit)? = null): String? {
        logCallback?.invoke("PackerUnpacker: Searching for eval(function... pattern")
        return extractFromHtml(pageText)?.also {
            logCallback?.invoke("PackerUnpacker: Found URL: $it")
        }
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

    fun findVideoUrl(html: String): String? {
        return extractFromHtml(html)
    }
}
