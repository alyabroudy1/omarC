package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.delay

class EarnVidsExtractor : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl = "earnvids.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalUrl = if (url.startsWith("http")) url else "https:$url"
        try {
            val response = app.get(finalUrl, referer = referer).text
            
            // Extract the packed javascript
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[d|r]\)\{.*?\}\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*['"](.+?)['"]""")
            val match = packedRegex.find(response) ?: return
            
            val payloadRaw = match.groupValues[1]
            val radixStr = match.groupValues[2]
            val sympipe = match.groupValues[3]
            
            val radix = radixStr.toIntOrNull() ?: 36
            val symtab = sympipe.split("|")
            
            val payload = payloadRaw
                .replace("location.href", "'$finalUrl'")
                .replace("location", "'$finalUrl'")
                .replace("document.cookie", "''")
                .replace("window.location", "'$finalUrl'")
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
            
            // Now find the sources in the unpacked JS
            val sourceRegex = Regex("""sources:\s*\[\s*\{.*?(?:file|src):\s*['"](.*?)['"].*?\}\s*\]""")
            val sourceMatch = sourceRegex.find(unpacked) ?: return
            
            val videoUrl = sourceMatch.groupValues[1]
            
            callback(
                com.lagradost.cloudstream3.utils.newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = if (videoUrl.contains(".m3u8")) com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 else com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                ) {
                    this.referer = finalUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
