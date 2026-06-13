package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Custom extractor for Vidoba.org embeds.
 * 
 * Uses static JS extraction instead of the WebView VideoSniffer.
 * Why? The Vidoba CDN (cdnz.quest) checks the TLS JA3 Fingerprint. 
 * If WebView generates the token and ExoPlayer (OkHttp) plays it, the 
 * TLS mismatch triggers a 403 Forbidden. Static extraction forces OkHttp 
 * to generate the token, ensuring it matches ExoPlayer.
 */
class VidobaExtractor : ExtractorApi() {
    override val name = "Vidoba"
    override val mainUrl = "vidoba.org"
    override val requiresReferer = true

    companion object {
        private const val TAG = "VidobaExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ProviderLogger.d(TAG, "getUrl", "Processing Vidoba URL", "url" to url)

        try {
            // 1. Fetch the embed page using OkHttp (so the token binds to OkHttp JA3)
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to (referer ?: "https://larozza.casa/")
                )
            )

            // 2. Find the packed P.A.C.K.E.R JS script
            val documentHtml = response.text
            val packedRegex = Regex("""eval\((function\(p,a,c,k,e,d\).+?)\)""")
            val match = packedRegex.find(documentHtml)
            
            if (match == null) {
                ProviderLogger.e(TAG, "getUrl", "Could not find packed script in Vidoba embed")
                return
            }

            // 3. Unpack the script using CloudStream's built-in JsUnpacker
            val unpacked = JsUnpacker(match.value).unpack()
            if (unpacked == null) {
                ProviderLogger.e(TAG, "getUrl", "Failed to unpack Vidoba script")
                return
            }

            // 4. Extract the M3U8 URL from the unpacked script
            // The script typically contains: file:"https://vroba-store1...master.m3u8?t=..."
            val urlRegex = Regex("""file:\s*["'](https:\/\/[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            val urlMatch = urlRegex.find(unpacked)
            
            if (urlMatch == null) {
                ProviderLogger.e(TAG, "getUrl", "Could not find M3U8 URL in unpacked script")
                return
            }

            val m3u8Url = urlMatch.groupValues[1]
            ProviderLogger.d(TAG, "getUrl", "Successfully extracted M3U8", "m3u8Url" to m3u8Url.take(80))

            // 5. Build the ExtractorLink and pass to ExoPlayer
            callback(
                newExtractorLink(
                    source = name,
                    name = "Vidoba Server",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://vidoba.org/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to "https://vidoba.org/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                }
            )

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting Vidoba", e)
        }
    }
}
