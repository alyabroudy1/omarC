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

    private suspend fun fetchEmbedWithHttpURLConnection(
        urlUrl: String,
        headers: Map<String, String>
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val connection = java.net.URI(urlUrl).toURL().openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            for ((key, value) in headers) {
                connection.setRequestProperty(key, value)
            }
            val responseCode = connection.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                val response = java.lang.StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line).append("\n")
                }
                reader.close()
                response.toString()
            } else {
                throw java.io.IOException("HTTP error code: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
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
            val embedHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to (referer ?: "https://larozza.casa/"),
                "Accept-Language" to "en-GB,en;q=0.7"
            )

            // 1. Fetch the embed page using HttpURLConnection (so the token binds to native Java TLS handshake signature)
            val documentHtml = fetchEmbedWithHttpURLConnection(url, embedHeaders)

            // 2. Find the packed P.A.C.K.E.R JS script
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
            val urlRegex = Regex("""file:\s*["'](https:\/\/[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            val urlMatch = urlRegex.find(unpacked)
            
            if (urlMatch == null) {
                ProviderLogger.e(TAG, "getUrl", "Could not find M3U8 URL in unpacked script")
                return
            }

            val m3u8Url = urlMatch.groupValues[1]
            ProviderLogger.d(TAG, "getUrl", "Successfully extracted M3U8", "m3u8Url" to m3u8Url.take(80))

            // 5. Build the ExtractorLink pointing directly to the master playlist
            // Referer is set to the base domain "https://vidoba.org/" (trailing slash) to prevent mismatches
            val baseReferer = "https://vidoba.org/"
            val requestHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to baseReferer,
                "Origin" to "https://vidoba.org",
                "Accept" to "*/*",
                "Accept-Language" to "en-GB,en;q=0.7",
                "sec-ch-ua" to WebConfig.buildSecChUa(userAgent),
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site"
            )

            android.util.Log.d(TAG, "[getUrl] Emitting master playlist directly: $m3u8Url")
            android.util.Log.d(TAG, "[getUrl] Requesting M3U8 headers: $requestHeaders")

            callback(
                newExtractorLink(
                    source = name,
                    name = "Vidoba Server",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = baseReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = requestHeaders
                }
            )

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting Vidoba", e)
        }
    }
}
