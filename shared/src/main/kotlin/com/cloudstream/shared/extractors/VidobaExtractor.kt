package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

import com.cloudstream.shared.util.WebConfig

class VidobaExtractor : ExtractorApi() {
    override val name = "Vidoba"
    override val mainUrl = "https://vidoba.org"
    override val requiresReferer = true

    companion object {
        private const val TAG = "VidobaExtractor"
    }

    private suspend fun fetchViaHttpUrlConnection(
        url: String,
        headers: Map<String, String>
    ): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val connection = try {
            java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "fetchViaHttpUrlConnection", "Failed to open connection", e)
            return@withContext null
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
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
                ProviderLogger.w(TAG, "fetchViaHttpUrlConnection", "HTTP $responseCode for $url")
                null
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "fetchViaHttpUrlConnection", "Error: ${e.message}")
            null
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
            val headerReferer = referer ?: "https://larozza.casa/"

            // ── Phase 1: Fetch embed page ──
            // Try httpService first (handles CF, cookies, redirects).
            // Fall back to raw HttpURLConnection if service is unavailable (preserves TLS fingerprint
            // consistency with ExoPlayer for CDNs that check JA3).
            val documentHtml = fetchViaHttpService(url, headerReferer, userAgent)
                ?: fetchViaHttpUrlConnection(url, mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to headerReferer,
                    "Accept-Language" to "en-GB,en;q=0.7"
                ))

            if (documentHtml.isNullOrBlank()) {
                ProviderLogger.e(TAG, "getUrl", "Failed to fetch embed page")
                return
            }

            // ── Phase 2: Extract M3U8 from packed JS ──
            val m3u8Url = extractM3u8FromPage(documentHtml)
            if (m3u8Url == null) {
                ProviderLogger.w(TAG, "getUrl", "No M3U8 found in page")
                return
            }

            ProviderLogger.d(TAG, "getUrl", "Extracted M3U8", "url" to m3u8Url.take(80))

            // ── Phase 3: Emit M3U8 links ──
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

            val m3u8Links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = baseReferer,
                headers = requestHeaders
            )

            if (m3u8Links.isEmpty()) {
                callback(
                    newExtractorLink(source = name, name = "Vidoba", url = m3u8Url, type = ExtractorLinkType.M3U8) {
                        this.referer = baseReferer
                        this.quality = Qualities.Unknown.value
                        this.headers = requestHeaders
                    }
                )
            } else {
                m3u8Links.forEach { link ->
                    callback(
                        newExtractorLink(source = link.source, name = link.name, url = link.url) {
                            this.referer = link.referer
                            this.quality = link.quality
                            this.headers = link.headers
                        }
                    )
                }
            }

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting Vidoba", e)
        }
    }

    private suspend fun fetchViaHttpService(url: String, referer: String, userAgent: String): String? {
        val service = ProviderHttpServiceHolder.getInstance() ?: return null
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to referer,
            "Accept-Language" to "en-GB,en;q=0.7"
        )
        return try {
            val doc = service.getDocument(url, headers)
            doc?.outerHtml()
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "fetchViaHttpService", "httpService failed: ${e.message}")
            null
        }
    }

    private fun extractM3u8FromPage(html: String): String? {
        // Try standard P.A.C.K.E.R: eval(function(p,a,c,k,e,d)...)
        var unpacked: String? = tryUnpackPacker(html)
        if (unpacked != null) return findM3u8InText(unpacked)

        // Try base64-encoded eval: eval(atob('...'))
        unpacked = tryUnpackBase64(html)
        if (unpacked != null) return findM3u8InText(unpacked)

        // Try inline sources pattern (no eval)
        return findM3u8InText(html)
    }

    private fun tryUnpackPacker(html: String): String? {
        val packedRegex = Regex(
            """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val start = packedRegex.find(html) ?: return null
        val fullMatch = html.substring(start.range.first).let { rest ->
            // Find the closing ) of the eval
            var depth = 0
            var inString = false
            var stringChar = ' '
            for (i in rest.indices) {
                val c = rest[i]
                if (inString) {
                    if (c == '\\') { // skip escaped char
                        // just skip
                    } else if (c == stringChar) {
                        inString = false
                    }
                } else {
                    when (c) {
                        '\'', '"' -> { inString = true; stringChar = c }
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) return@let rest.substring(0, i + 1) }
                    }
                }
            }
            rest
        }
        return JsUnpacker(fullMatch).unpack()
    }

    private fun tryUnpackBase64(html: String): String? {
        val base64Regex = Regex(
            """eval\s*\(\s*(?:atob|Base64\.decode)\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = base64Regex.find(html) ?: return null
        return try {
            val decoded = String(android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT))
            // The decoded JS might itself be packed — try unpacking
            tryUnpackPacker(decoded) ?: decoded
        } catch (e: Exception) {
            null
        }
    }

    private fun findM3u8InText(text: String): String? {
        val patterns = listOf(
            Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""file\s*:\s*["'](https?://[^"']+)["'].*\.m3u8""", RegexOption.IGNORE_CASE),
            Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.groupValues[1].replace("\\/", "/")
        }
        return null
    }
}
