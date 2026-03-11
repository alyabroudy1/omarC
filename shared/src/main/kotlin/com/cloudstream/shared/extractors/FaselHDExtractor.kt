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
                ProviderLogger.e(TAG, "decodeFaselB64", "Failed to decode swapped='$data'", e)
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
            val arrayMatch = arrayRegex.find(response)
            if (arrayMatch == null) {
                ProviderLogger.e(TAG, "getUrl", "Failed to find encoded array with regex")
                return
            }
            
            val encodedStrings = Regex("""'(.*?)'""").findAll(arrayMatch.groupValues[1]).map { it.groupValues[1] }.toList()
            ProviderLogger.d(TAG, "getUrl", "Encoded strings found: ${encodedStrings.size}")
            
            val decodedStrings = encodedStrings.map { decodeFaselB64(it) }
            
            // 2. Join all strings to find the reconstructed URL
            // The obfuscator often splits the URL into multiple array elements.
            // Joining them all usually reveals the final URL as a substring.
            val giantString = decodedStrings.joinToString("")
            
            ProviderLogger.d(TAG, "getUrl", "Giant string length: ${giantString.length}")
            
            // Regex to find the master m3u8 link. It might be preceded by "url=" or "file="
            val urlRegex = Regex("""https?://[a-zA-Z0-9.\-]+\.scdns\.io/stream/v[12]/[a-zA-Z0-9_\-]+/\d+/normal/0/[0-9.]+/yes/[a-f0-9]+/([a-zA-Z0-9.\-]*)/master\.m3u8""")
            val match = urlRegex.find(giantString)
            
            val finalUrl = match?.value ?: run {
                // Fallback: search for any http...m3u8 if the specific scdns pattern doesn't match
                ProviderLogger.w(TAG, "getUrl", "Specific SC DNS pattern failed, trying generic M3U8 search")
                val genericRegex = Regex("""https?://[a-zA-Z0-9.\-/?&=_%]+master\.m3u8""")
                genericRegex.find(giantString)?.value
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
                ProviderLogger.e(TAG, "getUrl", "Could not find any M3U8 URL in the array strings")
                // Log the giant string in chunks if it failed to debug
                giantString.chunked(1000).forEach { chunk ->
                    ProviderLogger.d(TAG, "getUrl", "Array Dump: $chunk")
                }
            }

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error", e)
        }
    }
}
