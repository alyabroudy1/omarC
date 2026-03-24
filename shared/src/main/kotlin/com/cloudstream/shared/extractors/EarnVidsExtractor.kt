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
        return if (decodedUrl.startsWith("http")) decodedUrl else null
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
        val sourceRegex = Regex("""sources:\s*\[\s*\{.*?(?:file|src):\s*['"](.*?)['"].*?\}\s*\]""")
        val sourceMatch = sourceRegex.find(unpackedPayload)
        if (sourceMatch != null) {
            Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via [sources] syntax: ${sourceMatch.groupValues[1]}")
            return sourceMatch.groupValues[1]
        }
        
        // Target 2: var links = { "hls4": "...", "hls": "..." }
        val linksRegex = Regex("""var\s+links\s*=\s*(\{.*?})\s*;""", RegexOption.DOT_MATCHES_ALL)
        val linksMatch = linksRegex.find(unpackedPayload)
        
        if (linksMatch != null) {
            Log.d("EarnVidsExtractor", "🔎 Found [var links] JSON dictionary instead of sources.")
            val jsonStr = linksMatch.groupValues[1].replace("'", "\"")
            // Basic regex parsing to extract the keys gracefully without org.json
            val hls4Regex = Regex(""""hls4"\s*:\s*"([^"]+)"""")
            val hlsRegex = Regex(""""hls"\s*:\s*"([^"]+)"""")
            
            val hls4Val = hls4Regex.find(jsonStr)?.groupValues?.get(1)
            if (!hls4Val.isNullOrBlank()) {
                Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via [var links -> hls4]: $hls4Val")
                return hls4Val
            }
            
            val hlsVal = hlsRegex.find(jsonStr)?.groupValues?.get(1)
            if (!hlsVal.isNullOrBlank()) {
                Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via [var links -> hls]: $hlsVal")
                return hlsVal
            }
        }
        
        // Target 3: Quick fallback if JSON bracket wasn't matched but keys are there
        val fallbackHls4 = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(unpackedPayload)?.groupValues?.get(1)
        if (!fallbackHls4.isNullOrBlank()) {
            Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via Regex [\"hls4\":]: $fallbackHls4")
            return fallbackHls4
        }
        
        val fallbackHls = Regex(""""hls"\s*:\s*"([^"]+)"""").find(unpackedPayload)?.groupValues?.get(1)
        if (!fallbackHls.isNullOrBlank()) {
            Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via Regex [\"hls\":]: $fallbackHls")
            return fallbackHls
        }
        
        Log.w("EarnVidsExtractor", "❌ Finished all unpackings but no valid M3U8 links were intercepted!")
        return null
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
                Log.d("EarnVidsExtractor", "🌐 Using explicit embed Referer: $referer")
                referer
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
                .joinToString("; ")
            
            val customHeaders = mutableMapOf<String, String>()
            if (jsCookies.isNotEmpty()) {
                Log.d("EarnVidsExtractor", "🍪 Intercepted Proprietary JS Cookies! Attaching to headers: $jsCookies")
                customHeaders["Cookie"] = jsCookies
            } else {
                Log.w("EarnVidsExtractor", "⚠️ No $.cookie tags intercepted from HTML.")
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
                        
                        callback(
                            com.lagradost.cloudstream3.utils.newExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl,
                                type = if (videoUrl.contains(".m3u8", ignoreCase = true)) com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 else com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
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
        } catch (e: Exception) {
            Log.e("EarnVidsExtractor", "❌ Catastrophic Error running EarnVidsExtractor: ${e.message}", e)
        }
    }
}
