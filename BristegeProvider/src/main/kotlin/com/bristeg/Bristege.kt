package com.bristeg

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Document

class Bristege : BaseProvider() {

    override val baseDomain get() = "amd.brstej.com"
    override val providerName get() = "Bristege"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/bristege.json"

    override val mainPage = mainPageOf(
        "/newvideos.php" to "مسلسلات برستيج",
        "/cat44.php?cat=movies2-2224" to "افلام",
        "/cat44.php?cat=ramdan2026" to "مسلسلات رمضان 2026",
    )

    override fun getParser(): NewBaseParser {
        return BristegeParser()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "[$providerName] [loadLinks]"
        Log.i(methodTag, "START data='$data'")

        try {
            httpService.ensureInitialized()

            // 1. Fetch detail page
            val detailDoc = httpService.getDocument(data) ?: return false

            // 2. Ask parser for player/watch page URL (a.xtgo redirect)
            val watchPageUrl = getParser().getPlayerPageUrl(detailDoc)
            
            val targetDoc = if (!watchPageUrl.isNullOrBlank()) {
                val absoluteWatchUrl = if (watchPageUrl.startsWith("http")) {
                    watchPageUrl
                } else {
                    "$mainUrl/$watchPageUrl".replace("//", "/").replace("https:/", "https://")
                }
                Log.d(methodTag, "Following redirect to player page: $absoluteWatchUrl")
                httpService.getDocument(absoluteWatchUrl, mapOf("Referer" to data)) ?: detailDoc
            } else {
                detailDoc
            }

            // 3. Extract embed URLs from the target page (buttons and iframe)
            val embedDataList = extractEmbedData(targetDoc)
            Log.d(methodTag, "Extracted ${embedDataList.size} embed/server targets")

            if (embedDataList.isEmpty()) {
                Log.w(methodTag, "No servers found")
                return false
            }

            // 4. Process each embed target according to Bristege priority
            coroutineScope {
                embedDataList.map { embedData ->
                    async {
                        try {
                            processEmbed(embedData.url, data, embedData.name, embedData.quality, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error processing embed ${embedData.url}: ${e.message}")
                        }
                    }
                }.awaitAll()
            }

            return true
        } catch (e: Exception) {
            Log.e(methodTag, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private fun extractEmbedData(doc: Document): List<EmbedData> {
        val items = mutableListOf<EmbedData>()
        
        // Buttons
        doc.select("div#WatchServers button.watchButton, div#WatchServers button.watchbutton").forEach { btn ->
            val url = btn.attr("data-embed-url").ifBlank { btn.attr("data-embed") }
            if (url.isNotBlank()) {
                val absoluteUrl = buildAbsoluteUrl(url)
                items.add(EmbedData(absoluteUrl, btn.text(), Qualities.Unknown.value))
            }
        }
        
        // Iframe
        doc.select("div#Playerholder iframe").forEach { iframe ->
            val url = iframe.attr("src")
            if (url.isNotBlank()) {
                val absoluteUrl = buildAbsoluteUrl(url)
                items.add(EmbedData(absoluteUrl, "Main Server", Qualities.Unknown.value))
            }
        }
        
        return items.distinctBy { it.url }
    }

    private suspend fun processEmbed(
        embedUrl: String,
        referer: String,
        serverName: String,
        baseQuality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val methodTag = "[$providerName] [processEmbed]"
        
        // Bristege Logic Step 1: Download embed page natively (bypass aggressive URL rewriting)
        val html = httpService.getText(embedUrl, mapOf("Referer" to referer), skipRewrite = true) ?: return
        
        // Bristege Logic Step 2: Direct video link in text (First priority in source)
        val directUrl = findVideoInText(html)
        if (directUrl != null) {
            Log.d(methodTag, "Found direct link: $directUrl")
            callback(
                newExtractorLink(
                    source = providerName,
                    name = "$serverName (direct)",
                    url = directUrl,
                    type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = embedUrl
                    this.quality = baseQuality
                }
            )
            return
        }
        
        // Bristege Logic Step 3: JWPlayer setup (Includes key as seen in source)
        val jwSetupRegex = Regex("jwplayer\\.setup\\s*\\(\\{([\\s\\S]*?)\\}\\);")
        jwSetupRegex.find(html)?.let { match ->
            val setupJs = jsUnescape(match.groupValues[1])
            val videoFromJw = findVideoInText(setupJs)
            if (videoFromJw != null) {
                Log.d(methodTag, "Found video in JWPlayer setup: $videoFromJw")
                callback(
                    newExtractorLink(
                        source = providerName,
                        name = "$serverName (JW Player)",
                        url = videoFromJw,
                        type = if (videoFromJw.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = baseQuality
                    }
                )
                return
            }
        }

        // Bristege Logic Step 4: Packed JS (Matches decompiled eval pattern)
        val packedRegex = Regex("eval\\((function\\s*\\(.*)\\)\\)", RegexOption.DOT_MATCHES_ALL)
        packedRegex.findAll(html).forEach { match ->
            try {
                val fullEval = "eval(${match.groupValues[1]})"
                // Try standard unpacker first
                var unpacked = JsUnpacker(fullEval).unpack()
                
                // Fallback to manual unpacker
                if (unpacked == null || !unpacked.contains("http")) {
                    unpacked = manualUnpack(fullEval)
                }
                
                if (unpacked != null) {
                    val videoFromPacked = findVideoInText(unpacked)
                    if (videoFromPacked != null) {
                        Log.d(methodTag, "Found video in Packed JS: $videoFromPacked")
                        callback(
                            newExtractorLink(
                                source = providerName,
                                name = "$serverName (packed)",
                                url = videoFromPacked,
                                type = if (videoFromPacked.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = baseQuality
                            }
                        )
                        return // Exit after first success in packed JS
                    }
                }
            } catch (e: Exception) { }
        }

        // Bristege Logic Step 5: Standard Extractors as fallback
        Log.d(methodTag, "Falling back to standard extractors for $embedUrl")
        loadExtractor(embedUrl, referer, subtitleCallback) { link ->
            callback(link)
        }
    }

    private fun findVideoInText(text: String?): String? {
        if (text == null) return null
        
        // Unescape the text again just in case, specially slashes
        val cleanText = text.replace("\\/", "/")
        
        val patterns = listOf(
            Regex("(?:file|src)\\s*:\\s*['\"](https?://[^'\"]+)['\"]"),
            Regex("['\"](https?://[^\\s'\"]+\\.(?:m3u8|mp4)[^\\s'\"]*)['\"]"),
            Regex("(https?://[^\\s'\"]+\\.(?:m3u8|mp4)[^\\s'\"]*)")
        )

        for (pattern in patterns) {
            pattern.find(cleanText)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun jsUnescape(s: String): String {
        var r = Regex("\\\\x([0-9a-fA-F]{2})").replace(s) { mr ->
            try {
                mr.groupValues[1].toInt(16).toChar().toString()
            } catch (e: Exception) {
                mr.value
            }
        }
        r = Regex("\\\\u([0-9a-fA-F]{4})").replace(r) { mr ->
            try {
                mr.groupValues[1].toInt(16).toChar().toString()
            } catch (e: Exception) {
                mr.value
            }
        }
        return r.replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\/", "/")
    }

    private fun manualUnpack(packedJs: String): String? {
        // Simple implementation of the manual unpacker found in decompiled code
        try {
            val regex = Regex("eval\\(function\\s*\\(p,a,c,k,e,d\\)\\s*\\{[\\s\\S]*?\\}\\s*\\(\\s*(['\"])(.*?)\\1\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(['\"])(.*?)\\5\\.split\\(['\"]\\|['\"]\\)\\s*\\)\\s*\\)", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(packedJs) ?: return null
            
            val payloadRaw = match.groupValues[2]
            val base = match.groupValues[3].toIntOrNull() ?: 10
            val count = match.groupValues[4].toIntOrNull() ?: 0
            val dictionary = match.groupValues[6].split("|")
            
            val payload = jsUnescape(payloadRaw)
            val lookup = mutableMapOf<String, String>()
            for (i in count - 1 downTo 0) {
                val key = i.toString(base)
                val value = dictionary.getOrNull(i).let { if (it.isNullOrBlank()) key else it }
                lookup[key] = value
            }
            
            return Regex("\\b\\w+\\b").replace(payload) { mr ->
                lookup[mr.value] ?: mr.value
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun buildAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }.replace("//", "/").replace("https:/", "https://")
    }

    private data class EmbedData(val url: String, val name: String, val quality: Int)
}
