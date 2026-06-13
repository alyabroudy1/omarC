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
import com.lagradost.cloudstream3.utils.M3u8Helper

import com.cloudstream.shared.util.WebConfig

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
    override val mainUrl = "https://vidoba.org"
    override val requiresReferer = true

    init {
        android.util.Log.d("VidobaExtractor", "VidobaExtractor successfully initialized")
    }

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
            val userAgent = WebConfig.getCachedUserAgent()
            // 1. Fetch the embed page using OkHttp (so the token binds to OkHttp JA3)
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to (referer ?: "https://larozza.casa/")
                )
            )

            // 2. Find the packed P.A.C.K.E.R JS script
            val documentHtml = response.text
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).+?split\(\s*['"]\|['"]\s*\)\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
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

            // 5. Build the ExtractorLink and pass to ExoPlayer using M3u8Helper
            val requestHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to url,
                "Origin" to "https://vidoba.org",
                "Accept" to "*/*",
                "sec-ch-ua" to WebConfig.buildSecChUa(userAgent),
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site"
            )

            android.util.Log.d(TAG, "[getUrl] Requesting M3U8 with User-Agent: $userAgent")
            android.util.Log.d(TAG, "[getUrl] Requesting M3U8 headers: $requestHeaders")

            val m3u8Links = try {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = url,
                    headers = requestHeaders
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "[getUrl] M3u8Helper parsing failed", e)
                ProviderLogger.e(TAG, "getUrl", "M3u8Helper parsing failed", e)
                emptyList()
            }

            android.util.Log.d(TAG, "[getUrl] M3u8Helper returned ${m3u8Links.size} links")

            if (m3u8Links.isNotEmpty()) {
                m3u8Links.forEach { link ->
                    android.util.Log.d(TAG, "[getUrl] Generated Link Quality: ${link.quality}, Name: ${link.name}")
                    android.util.Log.d(TAG, "[getUrl] Generated Link URL: ${link.url}")
                    android.util.Log.d(TAG, "[getUrl] Generated Link Headers: ${link.headers}")
                    callback(link)
                }
            } else {
                android.util.Log.w(TAG, "[getUrl] Falling back to master playlist link directly")
                val fallbackLink = newExtractorLink(
                    source = name,
                    name = "Vidoba Server",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = requestHeaders
                }
                android.util.Log.d(TAG, "[getUrl] Fallback Link Headers: ${fallbackLink.headers}")
                callback(fallbackLink)
            }

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting Vidoba", e)
        }
    }
}
