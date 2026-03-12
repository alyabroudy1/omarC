package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.cloudstream.shared.logging.ProviderLogger
import org.mozilla.javascript.Context as RhinoContext

/**
 * FaselHD stream URL extractor.
 *
 * FaselHD obfuscates stream URLs using JavaScript _0x string-table obfuscation.
 * The player HTML contains a document['write']() call that injects quality
 * buttons with data-url attributes pointing to scdns.io CDN m3u8 streams.
 *
 * Uses a Strategy Pattern with two extraction approaches:
 * 1. LiteralConcatStrategy — regex-based, extracts string literals from the
 *    document.write() concatenation (works when URL parts are plain strings)
 * 2. RhinoJsStrategy — fallback, evaluates the minimal JS deobfuscation
 *    pipeline (string array + shuffler + lookup + wrappers) using Mozilla Rhino
 */
class FaselHDExtractor : ExtractorApi() {
    override val name = "FaselHD"
    override val mainUrl = "https://web3126x.faselhdx.xyz"
    override val requiresReferer = true


    // ── Strategy interface ──────────────────────────────────────────
    private interface ExtractionStrategy {
        val strategyName: String
        fun canHandle(html: String): Boolean
        fun extract(html: String): List<FaselStream>
    }

    data class FaselStream(val url: String, val quality: String)

    // ── Strategy 1: Extract string literals from document.write() ──
    private class LiteralConcatStrategy : ExtractionStrategy {
        override val strategyName = "LiteralConcat"

        override fun canHandle(html: String): Boolean {
            return html.contains("document['write']") || html.contains("document.write")
        }

        override fun extract(html: String): List<FaselStream> {
            val lines = html.split('\n')
            val targetLine = lines.find { line ->
                line.contains("_0x") &&
                (line.contains("document['write']") || line.contains("document.write"))
            } ?: return emptyList()

            // Extract all single-quoted string literals from the document.write() argument
            val writeCallIdx = targetLine.indexOf("document['write']")
                .takeIf { it >= 0 }
                ?: targetLine.indexOf("document.write")
                    .takeIf { it >= 0 }
                ?: return emptyList()

            val afterWrite = targetLine.substring(writeCallIdx)
            val literals = Regex("'([^']*)'").findAll(afterWrite)
                .map { it.groupValues[1] }
                .toList()

            if (literals.isEmpty()) return emptyList()

            // Join and unescape \x20, \x22 etc.
            val joined = literals.joinToString("").replace(Regex("\\\\x([0-9a-fA-F]{2})")) { matchResult ->
                val code = matchResult.groupValues[1].toInt(16)
                code.toChar().toString()
            }

            ProviderLogger.d(TAG, strategyName, "Joined literal length: ${joined.length}")
            return parseDataUrls(joined)
        }
    }

    // ── Strategy 2: Rhino JS evaluation of the deobfuscation pipeline ──
    private class RhinoJsStrategy : ExtractionStrategy {
        override val strategyName = "RhinoJs"

        override fun canHandle(html: String): Boolean {
            return html.contains("_0x") &&
                (html.contains("scdns") || html.contains("m3u8") || html.contains("hls"))
        }

        override fun extract(html: String): List<FaselStream> {
            val lines = html.split('\n')
            val targetLine = lines.find { line ->
                line.contains("_0x") &&
                (line.contains("document['write']") || line.contains("document.write") || line.contains("hlsPlaylist"))
            } ?: return emptyList()

            var js = targetLine
                .replace(Regex("</?script[^>]*>", RegexOption.IGNORE_CASE), "")
                .trim()

            // Strip trailing HTML after the last ;<
            val lastSemiHtml = js.lastIndexOf(";<")
            if (lastSemiHtml > 0) js = js.substring(0, lastSemiHtml + 1)

            return try {
                val isDocumentWrite = js.contains("document['write']") || js.contains("document.write")
                val writeArg = if (isDocumentWrite) extractWriteArgument(js) else null

                val arrayFunc = extractArrayFunction(js) ?: run {
                    ProviderLogger.d(TAG, "RhinoJs", "Failed to extract array function")
                    return emptyList()
                }
                val shufflerCode = extractShuffler(js) ?: run {
                    ProviderLogger.d(TAG, "RhinoJs", "Failed to extract shuffler")
                    return emptyList()
                }
                val lookupFunc = extractLookupFunction(js, arrayFunc.name) ?: run {
                    ProviderLogger.d(TAG, "RhinoJs", "Failed to extract lookup function")
                    return emptyList()
                }
                val wrappers = extractWrappers(js, lookupFunc.name)

                val evalJs = buildString {
                    appendLine(arrayFunc.code)
                    appendLine(shufflerCode)
                    appendLine(lookupFunc.code)
                    wrappers.forEach { appendLine(it) }
                    
                    if (isDocumentWrite && writeArg != null) {
                        appendLine("var __result = $writeArg;")
                        appendLine("__result;")
                    } else {
                        // For the jwplayer variant, find the hlsPlaylist assignment and evaluate it
                        val hlsmatch = Regex("var hlsPlaylist=(_0x[a-f0-9]+)").find(js)
                        if (hlsmatch != null) {
                            val playlistVar = hlsmatch.groupValues[1]
                            // The playlist var is usually populated earlier in the script.
                            // To get it, we need to evaluate the block leading up to it.
                            // But a simpler approach for the Rhino strategy: we replace the player setup with JSON.stringify()
                            
                            // Get the part of the code up to hlsPlaylist assignment
                            val setupIdx = js.indexOf("mainPlayer[")
                            if (setupIdx > 0) {
                                val scriptBody = js.substring(0, setupIdx)
                                // We only need the code AFTER the shuffler to build the playlist object
                                val afterShuffler = scriptBody.substring(shufflerCode.length)
                                appendLine(afterShuffler)
                                appendLine("JSON.stringify(hlsPlaylist);")
                            } else {
                                return emptyList()
                            }
                        } else {
                            return emptyList()
                        }
                    }
                }

                val result = evalWithRhino(evalJs)
                if (result.isNullOrBlank()) return emptyList()

                ProviderLogger.d(TAG, "RhinoJs", "Eval result length: ${result.length}")
                
                if (isDocumentWrite) {
                    parseDataUrls(result)
                } else {
                    parseJwplayerPlaylist(result)
                }
            } catch (e: Exception) {
                ProviderLogger.e(TAG, "RhinoJs", "Extraction error", e)
                emptyList()
            }
        }

        // ── JS component extraction helpers ──

        private data class NamedCode(val name: String, val code: String)

        private fun extractWriteArgument(js: String): String? {
            val regex = Regex("""document\['write'\]\(([^;]+)\);$""")
            return regex.find(js)?.groupValues?.get(1)
        }

        private fun extractArrayFunction(js: String): NamedCode? {
            val nameMatch = Regex("""function (_0x[a-f0-9]+)\(\)\{var _0x[a-f0-9]+=\[""").find(js)
                ?: return null
            val funcName = nameMatch.groupValues[1]
            val startIdx = js.indexOf("function $funcName()")
            val endStr = "return ${funcName}();}"
            val endIdx = js.indexOf(endStr, startIdx)
            if (endIdx < 0) return null
            return NamedCode(funcName, js.substring(startIdx, endIdx + endStr.length))
        }

        private fun extractShuffler(js: String): String? {
            val endMark = js.indexOf("));var ")
            if (endMark < 0) return null
            return js.substring(0, endMark + 2) // include ));
        }

        private fun extractLookupFunction(js: String, arrayFuncName: String): NamedCode? {
            val regex = Regex("""function (_0x[a-f0-9]+)\(_0x[a-f0-9]+,_0x[a-f0-9]+\)\{var _0x[a-f0-9]+=$arrayFuncName""")
            val nameMatch = regex.find(js) ?: return null
            val funcName = nameMatch.groupValues[1]
            val startIdx = js.indexOf("function $funcName(")
            // Find the end of the function by seeking the next function or the end of a block
            var endIdx = js.indexOf(";$funcName=", startIdx + 30)
            if (endIdx < 0) {
                endIdx = js.indexOf(";}", startIdx + 30) + 2
            } else {
                endIdx = js.indexOf(";}", endIdx) + 2
            }
            if (endIdx < 2) return null
            return NamedCode(funcName, js.substring(startIdx, endIdx))
        }

        private fun extractWrappers(js: String, lookupFuncName: String): List<String> {
            val regex = Regex("function (_0x[a-f0-9]+)\\([^)]+\\)\\{return $lookupFuncName\\([^}]+}")
            return regex.findAll(js).map { it.value }.toList()
        }

        private fun evalWithRhino(script: String): String? {
            val rhino = RhinoContext.enter()
            return try {
                rhino.optimizationLevel = -1
                val scope = rhino.initSafeStandardObjects()
                val result = rhino.evaluateString(scope, script, "FaselHD", 1, null)
                RhinoContext.toString(result)
            } catch (e: Exception) {
                ProviderLogger.e(TAG, "evalWithRhino", "JS evaluation failed", e)
                null
            } finally {
                RhinoContext.exit()
            }
        }
    }

    // ── Shared parsing utilities ────────────────────────────────────

    companion object {
        private const val TAG = "FaselHDExtractor"

        /** Parse data-url attributes from the decoded HTML fragment. */
        fun parseDataUrls(html: String): List<FaselStream> {
            val streams = mutableListOf<FaselStream>()
            val buttonRegex = Regex("""data-url="([^"]+\.m3u8)">([^<]+)""")
            for (match in buttonRegex.findAll(html)) {
                val url = match.groupValues[1]
                val label = match.groupValues[2].trim()
                streams.add(FaselStream(url, label))
            }
            // Fallback: try to find any m3u8 URL if data-url parsing didn't work
            if (streams.isEmpty()) {
                val urlRegex = Regex("""https?://[^\s"'<>]+\.m3u8""")
                for (match in urlRegex.findAll(html)) {
                    streams.add(FaselStream(match.value, "auto"))
                }
            }
            return streams
        }

        /** Parse JSON playlist object from jwplayer configuration. */
        fun parseJwplayerPlaylist(jsonStr: String): List<FaselStream> {
            val streams = mutableListOf<FaselStream>()
            try {
                // The JSON looks like: [{"file":"https://...","label":"1080p","type":"mp4"},...]
                // A simple regex approach to extract file and label properties from JSON objects:
                val objRegex = Regex("""\{[^{}]*\}""")
                for (match in objRegex.findAll(jsonStr)) {
                    val objStr = match.value
                    val fileMatch = Regex(""""file"\s*:\s*"([^"]+)"""").find(objStr)
                    val labelMatch = Regex(""""label"\s*:\s*"([^"]+)"""").find(objStr)
                    
                    if (fileMatch != null) {
                        val fileUrl = fileMatch.groupValues[1]
                        val label = labelMatch?.groupValues?.get(1) ?: "auto"
                        // Only add m3u8 or video formats
                        if (fileUrl.contains(".m3u8") || fileUrl.contains(".mp4")) {
                            streams.add(FaselStream(fileUrl, label))
                        }
                    }
                }
            } catch (e: Exception) {
                ProviderLogger.e(TAG, "parseJwplayerPlaylist", "Error parsing playlist JSON: $jsonStr", e)
            }
            return streams
        }

        /** Map quality label (e.g., "1080p", "720p", "auto") to Qualities value. */
        fun qualityFromLabel(label: String): Int {
            return when {
                label.contains("1080") -> Qualities.P1080.value
                label.contains("720") -> Qualities.P720.value
                label.contains("480") -> Qualities.P480.value
                label.contains("360") -> Qualities.P360.value
                label.contains("auto") -> Qualities.Unknown.value
                else -> Qualities.Unknown.value
            }
        }
    }

    // ── Orchestrator ────────────────────────────────────────────────

    private val strategies: List<ExtractionStrategy> = listOf(
        LiteralConcatStrategy(),
        RhinoJsStrategy()
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer).text
            ProviderLogger.d(TAG, "getUrl", "Fetched ${response.length} bytes from $url")

            for (strategy in strategies) {
                if (!strategy.canHandle(response)) continue

                ProviderLogger.d(TAG, "getUrl", "Trying strategy: ${strategy.strategyName}")
                val streams = strategy.extract(response)

                if (streams.isNotEmpty()) {
                    ProviderLogger.d(TAG, "getUrl", "${strategy.strategyName} found ${streams.size} streams")
                    for (stream in streams) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name ${stream.quality}",
                                url = stream.url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = qualityFromLabel(stream.quality)
                            }
                        )
                    }
                    return // First successful strategy wins
                }
                ProviderLogger.w(TAG, "getUrl", "${strategy.strategyName} found no streams, trying next")
            }

            ProviderLogger.e(TAG, "getUrl", "All strategies failed for $url")
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error", e)
        }
    }
}
