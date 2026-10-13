package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.delay

interface EarnVidsObfuscationStrategy {
    fun decode(response: String): String?
}

/**
 * True for absolute http(s) media URLs. Rejects thumbnail/storyboard tracks
 * (get_slides), images, subtitle tracks and site assets that share the same
 * file:/src: syntax inside jwplayer setups.
 */
private fun isPlayableStreamUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val u = url.trim()
    if (!u.startsWith("http://") && !u.startsWith("https://")) return false
    val lower = u.lowercase()
    if (lower.contains("get_slides")) return false
    if (Regex("""\.(jpg|jpeg|png|gif|webp|bmp|svg|vtt|srt|css|js)(\?|#|$)""").containsMatchIn(lower)) return false
    if (lower.contains("logo")) return false
    return true
}

class HexObfuscationStrategy : EarnVidsObfuscationStrategy {
    override fun decode(response: String): String? {
        val hexRegex = Regex("""const\s+[A-Za-z0-9_]+\s*=\s*["']([0-9a-fA-F]{40,})["']""")
        val hexMatch = hexRegex.find(response) ?: return null
        val hexStr = hexMatch.groupValues[1]
        var decodedUrl = ""
        for (i in 0 until hexStr.length step 2) {
            try {
                decodedUrl += hexStr.substring(i, i + 2).toInt(16).toChar()
            } catch (e: Exception) {
                break
            }
        }
        if (!decodedUrl.startsWith("http")) return null
        // A hex blob may decode to a thumbnail/slides URL rather than the
        // stream — reject those so the packer strategy still gets its turn.
        return if (isPlayableStreamUrl(decodedUrl)) decodedUrl else null
    }
}

class PackerObfuscationStrategy : EarnVidsObfuscationStrategy {
    override fun decode(response: String): String? {
        var currentPayload = response
        var unpackedPayload = ""
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[d|r]\)\{.*?\}\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*['"](.+?)['"]""")
        
        // Unpack recursively up to 4 times
        for (i in 1..4) {
            val match = packedRegex.find(currentPayload)
            if (match == null) {
                if (i == 1) Log.w("EarnVidsExtractor", "❌ No eval(function) found on pass $i, exiting unpacker.")
                break
            }
            
            val payloadRaw = match.groupValues[1]
            val radixStr = match.groupValues[2]
            val sympipe = match.groupValues[3]
            
            val radix = radixStr.toIntOrNull() ?: 36
            val symtab = sympipe.split("|")
            
            val payload = payloadRaw
                .replace("location.href", "''")
                .replace("location", "''")
                .replace("document.cookie", "''")
                .replace("window.location", "''")
                .replace("window", "this")
                
            val tokenRe = Regex("""\b[0-9a-zA-Z]+\b""")
            val unpacked = tokenRe.replace(payload) { mo ->
                val tok = mo.value
                try {
                    val idx = tok.toInt(radix)
                    if (idx in symtab.indices && symtab[idx].isNotEmpty()) symtab[idx] else tok
                } catch (e: Exception) {
                    tok
                }
            }
            unpackedPayload = unpacked
            currentPayload = unpacked
            Log.d("EarnVidsExtractor", "🔄 Unpack iteration $i successful (Length: ${unpacked.length})")
        }
        
        if (unpackedPayload.isEmpty()) return null
        
        // Target 1: sources: [{ file: "..." }]
        // NOTE: the first file: entry is often a thumbnails track
        // ("/dl?op=get_slides...") or a JS var reference
        // ("links.hls4||links.hls3"), NOT the stream. Accept only absolute
        // http(s) media URLs here; otherwise fall through to the
        // var-links targets below which resolve links.hlsN references.
        val sourceRegex = Regex("""(?:file|src):\s*['"](.*?)['"]""")
        for (sourceMatch in sourceRegex.findAll(unpackedPayload)) {
            val candidate = sourceMatch.groupValues[1].trim()
            if (isPlayableStreamUrl(candidate)) {
                Log.d("EarnVidsExtractor", "✅ Extracted via [sources] syntax: ${candidate.take(100)}")
                return candidate
            }
        }
        
        // Target 2: var links = { "hls4": "...", "hls3"/"hls2"/"hls": "..." }
        // Any hlsN key counts (observed hls3+hls2 without hls4); prefer the
        // highest-numbered variant, which is the best quality.
        // NOTE: no literal {}/braces in the pattern — Android's ICU regex
        // engine rejects the unescaped } (PatternSyntaxException), so the
        // object is captured as "everything up to the semicolon".
        val linksRegex = Regex("""var\s+links\s*=\s*([^;]+);""", RegexOption.DOT_MATCHES_ALL)
        val linksMatch = linksRegex.find(unpackedPayload)

        if (linksMatch != null) {
            Log.d("EarnVidsExtractor", "🔎 Found [var links] JSON dictionary instead of sources.")
            val jsonStr = linksMatch.groupValues[1].replace("'", "\"")
            val bestHls = pickBestHlsVariant(jsonStr)
            if (!bestHls.isNullOrBlank()) {
                Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via [var links]: ${bestHls.take(100)}")
                return bestHls
            }
        }

        // Target 3: Quick fallback if JSON bracket wasn't matched but keys are there
        val fallbackBest = pickBestHlsVariant(unpackedPayload)
        if (!fallbackBest.isNullOrBlank()) {
            Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via Regex [hlsN]: ${fallbackBest.take(100)}")
            return fallbackBest
        }

        Log.w("EarnVidsExtractor", "❌ Finished all unpackings but no valid M3U8 links were intercepted!")
        return null
    }

    /**
     * Picks the best "hlsN" stream URL from a JSON-ish blob: highest N wins
     * (hls4 > hls3 > hls2 > plain hls), skipping non-playable values.
     */
    private fun pickBestHlsVariant(jsonStr: String): String? {
        return Regex(""""(hls\d*)"\s*:\s*"([^"]+)"""").findAll(jsonStr)
            .mapNotNull { m ->
                val key = m.groupValues[1]
                val value = m.groupValues[2].trim()
                if (!isPlayableStreamUrl(value)) return@mapNotNull null
                val rank = if (key == "hls") 0 else key.removePrefix("hls").toIntOrNull() ?: -1
                rank to value
            }
            .maxByOrNull { it.first }
            ?.second
    }
}

class EarnVidsExtractor(
    override val mainUrl: String = "earnvids.com",
    private val displayName: String = "EarnVids"
) : ExtractorApi() {
    override val name = displayName
    override val requiresReferer = true
    
    private val strategies = listOf(
        HexObfuscationStrategy(),
        PackerObfuscationStrategy()
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalUrl = if (url.startsWith("http")) url else "https:$url"
        Log.d("EarnVidsExtractor", "▶️ Initializing Phase 2 Extractor | Domain: $name | URL: $finalUrl")
        try {
            // 1. Spoof Desktop UA headers & standard Accept headers
            // 2. Intercept fdewsdc.sbs and forcefully enforce referer validation
            val activeReferer = if (finalUrl.contains("fdewsdc.sbs", ignoreCase = true)) {
                Log.d("EarnVidsExtractor", "🌐 [fdewsdc.sbs Match] Hijacking Referer to https://shhahid4u.cam")
                "https://shhahid4u.cam"
            } else {
                Log.d("EarnVidsExtractor", "🌐 Using explicit embed finalUrl as Referer: $finalUrl")
                finalUrl
            }
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive"
            )
            
            Log.d("EarnVidsExtractor", "📥 Requesting payload with spoofed Windows headers...")
            val response = app.get(finalUrl, referer = activeReferer, headers = headers).text
            Log.d("EarnVidsExtractor", "✅ Response acquired. Length: ${response.length}")
            
            // Extract proprietary Javascript cookies (e.g. $.cookie('file_id', ...))
            val jsCookies = Regex("""\$\.cookie\(['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""")
                .findAll(response)
                .map { "${it.groupValues[1]}=${it.groupValues[2]}" }
                .distinct()
                .toList()
                
            // Natively extract Cloudflare clearance & session cookies from Android WebView
            val webViewCookieString = android.webkit.CookieManager.getInstance().getCookie(finalUrl) ?: ""
            val webViewCookies = webViewCookieString.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            
            // Merge JS Cookies and WebView Cookies securely without duplicating keys!
            val mergedCookieMap = mutableMapOf<String, String>()
            for (cookie in jsCookies + webViewCookies) {
                if (cookie.contains("=")) {
                    val split = cookie.split("=", limit = 2)
                    mergedCookieMap[split[0]] = split[1]
                }
            }
            
            val finalCookieHeader = mergedCookieMap.map { "${it.key}=${it.value}" }.joinToString("; ")

            
            val customHeaders = mutableMapOf<String, String>()
            if (finalCookieHeader.isNotEmpty()) {
                Log.d("EarnVidsExtractor", "🍪 Assembled Master Cookies! Attaching to headers: $finalCookieHeader")
                customHeaders["Cookie"] = finalCookieHeader
            } else {
                Log.w("EarnVidsExtractor", "⚠️ No cookies intercepted from WebView or HTML.")
            }
            
            customHeaders["Accept"] = "*/*"
            customHeaders["User-Agent"] = headers["User-Agent"]!!
            
            for (strategy in strategies) {
                var videoUrl = strategy.decode(response)
                if (!videoUrl.isNullOrBlank()) {
                    // Normalize relative urls extracted from the payload
                    if (videoUrl.startsWith("//")) {
                        videoUrl = "https:$videoUrl"
                    } else if (videoUrl.startsWith("/")) {
                        val uri = java.net.URI(finalUrl)
                        videoUrl = "${uri.scheme}://${uri.host}$videoUrl"
                    }
                    
                    if (videoUrl.startsWith("http")) {
                        Log.d("EarnVidsExtractor", "🥇 Extraction Success via Strategy: ${strategy::class.java.simpleName}")
                        Log.d("EarnVidsExtractor", "🔗 Handing off Link to ExoPlayer: $videoUrl")

                        // master.txt playlists carry HLS content under a .txt
                        // extension (verified: #EXTM3U master with variants).
                        // They must go out as M3U8, or ExoPlayer probes them
                        // as progressive video and fails.
                        val isHls = videoUrl.contains(".m3u8", ignoreCase = true) ||
                                videoUrl.contains("master.txt", ignoreCase = true)
                        callback(
                            com.lagradost.cloudstream3.utils.newExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl,
                                type = if (isHls) com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 else com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                            ) {
                                this.referer = activeReferer ?: finalUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = customHeaders
                            }
                        )
                        return // Stop after first successful decode
                    }
                }
            }
            Log.e("EarnVidsExtractor", "🛑 All extraction strategies failed heavily against $finalUrl")

            // Fallback: download-landing URLs (/d/<id>) carry no player, only
            // a download button. Retry once against the embed variant
            // (/v/<id>.html) which holds the packed jwplayer. Single hop:
            // embed URLs never produce a further variant, so no loop risk.
            toEmbedVariant(finalUrl)?.let { embedUrl ->
                Log.d("EarnVidsExtractor", "🔁 Retrying as embed variant: $embedUrl")
                getUrl(embedUrl, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("EarnVidsExtractor", "❌ Catastrophic Error running EarnVidsExtractor: ${e.message}", e)
        }
    }

    private fun toEmbedVariant(url: String): String? {
        val id = Regex("""/d/([A-Za-z0-9]+)/?$""").find(url)?.groupValues?.get(1)
            ?: return null
        val base = url.substringBefore("/d/")
        if (base.isBlank() || base == url) return null
        return "$base/v/$id.html"
    }
}
