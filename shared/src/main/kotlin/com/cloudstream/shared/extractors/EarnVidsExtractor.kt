package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[d|r]\)\{.*?\}\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*['"](.+?)['"]""")
        val match = packedRegex.find(response) ?: return null
        
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
        
        val sourceRegex = Regex("""sources:\s*\[\s*\{.*?(?:file|src):\s*['"](.*?)['"].*?\}\s*\]""")
        val sourceMatch = sourceRegex.find(unpacked) ?: return null
        return sourceMatch.groupValues[1]
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
        try {
            val response = app.get(finalUrl, referer = referer).text
            
            // Extract proprietary Javascript cookies (e.g. $.cookie('file_id', ...))
            val jsCookies = Regex("""\$\.cookie\(['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""")
                .findAll(response)
                .map { "${it.groupValues[1]}=${it.groupValues[2]}" }
                .joinToString("; ")
            
            val customHeaders = mutableMapOf<String, String>()
            if (jsCookies.isNotEmpty()) customHeaders["Cookie"] = jsCookies
            customHeaders["Accept"] = "*/*"
            
            for (strategy in strategies) {
                val videoUrl = strategy.decode(response)
                if (!videoUrl.isNullOrBlank() && videoUrl.startsWith("http")) {
                    callback(
                        com.lagradost.cloudstream3.utils.newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8", ignoreCase = true)) com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 else com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                        ) {
                            this.referer = finalUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = customHeaders
                        }
                    )
                    return // Stop after first successful decode
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
