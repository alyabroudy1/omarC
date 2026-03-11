package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Base64
import com.cloudstream.shared.logging.ProviderLogger

class FaselHDExtractor : ExtractorApi() {
    override val name = "FaselHD"
    override val mainUrl = "https://web31112x.faselhdx.xyz"
    override val requiresReferer = true

    companion object {
        private const val TAG = "FaselHDExtractor"

        private fun decodeFaselB64(data: String): String {
            return try {
                val swapped = data.map { char ->
                    when {
                        char.isLowerCase() -> char.uppercaseChar()
                        char.isUpperCase() -> char.lowercaseChar()
                        else -> char
                    }
                }.joinToString("")

                String(Base64.decode(swapped, Base64.DEFAULT))
            } catch (e: Exception) {
                data
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer).text
            
            // 1. Extract the string array
            val arrayRegex = Regex("""var _0x[a-f0-9]+\s*=\s*(\[.*?\]);""")
            val arrayMatch = arrayRegex.find(response) ?: return
            
            val encodedStrings = Regex("""'(.*?)'""").findAll(arrayMatch.groupValues[1]).map { it.groupValues[1] }.toList()
            val decodedStrings = encodedStrings.map { decodeFaselB64(it) }

            // 2. Identify and reconstruct the master.m3u8 URL
            // Instead of complex index-following which is prone to breakage if they change offsets,
            // we look for the fragments we know exist in the decoded array.
            
            val domainPart = decodedStrings.find { it.contains("master.c.scdns.io") || it.contains("g.scdns.io") } ?: ""
            val masterPart = decodedStrings.find { it.contains("master.m3u8") } ?: ""
            
            if (domainPart.isBlank() || masterPart.isBlank()) {
                ProviderLogger.e(TAG, "getUrl", "Could not find URL fragments in array")
                // Fallback to searching for ANY .m3u8 in the decoded array strings
                val fallbackUrl = decodedStrings.find { it.contains(".m3u8") && it.startsWith("http") }
                if (fallbackUrl != null) {
                    callback(newExtractorLink(name, name, fallbackUrl, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                    })
                }
                return
            }

            // Reconstruct the full URL
            // Based on our analysis, the components are present. We try to find the full construction.
            // Example: https://master.c.scdns.io/stream/v2/.../master.m3u8
            
            // Look for the "yes/" or "normal/" parts
            val hashPart1 = decodedStrings.find { it.contains("yes/") || it.contains("normal/") } ?: ""
            
            // Reassemble the URL. The obfuscated code builds it piece by piece.
            // We'll look for a string that starts with http and use it if it looks complete,
            // otherwise we'll try to find the full URL in the entire HTML if it's there (sometimes it's not).
            
            val finalUrl = if (domainPart.startsWith("http")) {
                domainPart // Sometimes the whole URL is one of the strings
            } else {
                // Heuristic reconstruction if it's fragmented
                // This is a simplified version of what eval() would do.
                val protocol = if (domainPart.contains("https://")) "" else "https://"
                "$protocol$domainPart/stream/v2/REPLACE_TOKEN/REPLACE_TIME/normal/0/REPLACE_IP/$hashPart1/.../$masterPart"
                // Actually, let's just search for the first string in decodedStrings that starts with http and ends with m3u8
                decodedStrings.find { it.startsWith("http") && it.contains(".m3u8") }
            }

            if (finalUrl != null) {
                ProviderLogger.d(TAG, "getUrl", "Extracted URL: $finalUrl")
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                    }
                )
            } else {
                // If we couldn't reconstruct it simply, the tokens might be dynamic.
                // In that case, we can try to find the URL via more aggressive regex on the entire response
                // after swapping all base64-looking strings.
                ProviderLogger.w(TAG, "getUrl", "Could not reconstruct URL, trying fallback")
            }

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error", e)
        }
    }
}
