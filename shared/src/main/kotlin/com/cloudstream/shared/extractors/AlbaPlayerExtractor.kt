package com.cloudstream.shared.extractors

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.cloudstream.shared.logging.ProviderLogger
import org.jsoup.Jsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class AlbaPlayerExtractor : ExtractorApi() {
    override val name = "AlbaPlayer"
    override val mainUrl = "https://player.syria-player.live"
    override val requiresReferer = true

    companion object {
        private const val TAG = "AlbaPlayerExtractor"
    }

    private fun decodeDocumentWrite(response: String): String {
        val dwRegex = Regex("""document\.write\s*\(\s*"([^"]+)"\s*\)""", RegexOption.DOT_MATCHES_ALL)
        val dwMatch = dwRegex.find(response) ?: return response
        val encoded = dwMatch.groupValues[1]
        val decoded = StringBuilder()
        var i = 0
        while (i < encoded.length) {
            when {
                encoded[i] == '\\' && i + 1 < encoded.length && encoded[i + 1] == 'x' -> {
                    if (i + 4 < encoded.length) {
                        val hex = encoded.substring(i + 2, i + 4)
                        try {
                            decoded.append(hex.toInt(16).toChar())
                        } catch (e: NumberFormatException) {
                            decoded.append(encoded[i])
                        }
                        i += 4
                    } else {
                        decoded.append(encoded[i])
                        i++
                    }
                }
                encoded[i] == '\\' && i + 1 < encoded.length && encoded[i + 1] == 'u' -> {
                    if (i + 6 < encoded.length) {
                        val hex = encoded.substring(i + 2, i + 6)
                        try {
                            decoded.append(hex.toInt(16).toChar())
                        } catch (e: NumberFormatException) {
                            decoded.append(encoded[i])
                        }
                        i += 6
                    } else {
                        decoded.append(encoded[i])
                        i++
                    }
                }
                encoded[i] == '\\' && i + 1 < encoded.length -> {
                    decoded.append(encoded[i + 1])
                    i += 2
                }
                else -> {
                    decoded.append(encoded[i])
                    i++
                }
            }
        }
        return decoded.toString()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ProviderLogger.d(TAG, "getUrl", "Processing AlbaPlayer URL", "url" to url, "referer" to referer)

        try {
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            val headers = mutableMapOf(
                "User-Agent" to userAgent
            )
            if (!referer.isNullOrBlank()) {
                headers["Referer"] = referer
            }

            val rawResponse = app.get(url, headers = headers).text
            if (rawResponse.isBlank()) {
                ProviderLogger.e(TAG, "getUrl", "Empty response from AlbaPlayer URL")
                return
            }
            ProviderLogger.d(TAG, "getUrl", "Raw response length: ${rawResponse.length}, starts with: ${rawResponse.take(100)}")

            // Decode document.write hex obfuscation if present
            val response = decodeDocumentWrite(rawResponse)
            if (response != rawResponse) {
                ProviderLogger.d(TAG, "getUrl", "document.write obfuscation decoded: ${rawResponse.length} -> ${response.length} chars")
            } else {
                ProviderLogger.d(TAG, "getUrl", "No document.write obfuscation found, using raw response as-is")
            }

            // Extract all server URLs from the menu, including the current one
            val doc = Jsoup.parse(response, url)
            val allUrls = mutableSetOf<String>()
            allUrls.add(url)

            for (btn in doc.select("ul.aplr-menu a.aplr-link")) {
                val href = btn.attr("href")
                if (href.isNotBlank() && !href.contains("javascript:")) {
                    allUrls.add(href)
                }
            }

            ProviderLogger.d(TAG, "getUrl", "Found ${allUrls.size} server URL(s) in menu: $allUrls")

            val origin = try {
                val uri = java.net.URI(if (referer.isNullOrBlank()) url else referer)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                "https://player.syria-player.live"
            }

            val results = kotlinx.coroutines.coroutineScope {
                allUrls.map { serverUrl ->
                    async {
                        ProviderLogger.d(TAG, "getUrl", "Starting extraction for server: $serverUrl")
                        try {
                            val serverRaw = if (serverUrl == url) {
                                ProviderLogger.d(TAG, "getUrl", "Reusing cached response for current URL: $serverUrl")
                                rawResponse
                            } else {
                                ProviderLogger.d(TAG, "getUrl", "Fetching additional server URL: $serverUrl")
                                app.get(serverUrl, headers = headers).text
                            }

                            if (serverRaw.isNullOrBlank()) {
                                ProviderLogger.e(TAG, "getUrl", "Empty response for server: $serverUrl")
                                return@async false
                            }

                            val serverHtml = if (serverRaw.contains("document.write")) {
                                val decoded = decodeDocumentWrite(serverRaw)
                                ProviderLogger.d(TAG, "getUrl", "Decoded document.write for $serverUrl: ${serverRaw.length} -> ${decoded.length} chars")
                                decoded
                            } else {
                                serverRaw
                            }

                            extractFromPage(serverHtml, serverUrl, origin, userAgent, callback)
                        } catch (e: Exception) {
                            ProviderLogger.e(TAG, "getUrl", "Error processing server $serverUrl", e)
                            false
                        }
                    }
                }.awaitAll()
            }

            val anyFound = results.any { it }
            ProviderLogger.d(TAG, "getUrl", "All servers processed, any stream found: $anyFound (results: $results)")
            if (!anyFound) {
                ProviderLogger.e(TAG, "getUrl", "No playable stream found in any AlbaPlayer server")
            }

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting AlbaPlayer", e)
        }
    }

    private suspend fun extractFromPage(
        html: String,
        pageUrl: String,
        origin: String,
        userAgent: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to pageUrl,
            "Origin" to origin,
            "Accept" to "*/*"
        )

        ProviderLogger.d(TAG, "extractFromPage", "Extracting from page: $pageUrl, HTML length: ${html.length}")

        // Check what patterns exist in the HTML
        val hasClappr = html.contains("Clappr.Player")
        val hasAlbaCtrl = html.contains("AlbaPlayerControl")
        val hasM3u8 = html.contains(".m3u8")
        ProviderLogger.d(TAG, "extractFromPage", "Pattern check - Clappr: $hasClappr, AlbaPlayerControl: $hasAlbaCtrl, .m3u8: $hasM3u8")

        // Try AlbaPlayerControl base64 encoded stream
        if (hasAlbaCtrl) {
            val albaRegex = Regex("AlbaPlayerControl\\('([^']+)'")
            val albaMatch = albaRegex.find(html)
            if (albaMatch != null) {
                val encodedString = albaMatch.groupValues[1]
                try {
                    val decodedBytes = Base64.decode(encodedString, Base64.DEFAULT)
                    val m3u8Url = String(decodedBytes, Charsets.UTF_8)
                    ProviderLogger.d(TAG, "extractFromPage", "AlbaPlayerControl decoded M3U8: $m3u8Url")

                    val m3u8Links = M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = m3u8Url,
                        referer = pageUrl,
                        headers = streamHeaders
                    )

                    if (m3u8Links.isNotEmpty()) {
                        ProviderLogger.d(TAG, "extractFromPage", "M3u8Helper generated ${m3u8Links.size} quality variants")
                        m3u8Links.forEach { callback(it) }
                        return true
                    }

                    ProviderLogger.d(TAG, "extractFromPage", "M3u8Helper returned 0 links, emitting raw M3U8 as fallback")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Stream",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = pageUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = streamHeaders
                        }
                    )
                    return true
                } catch (e: Exception) {
                    ProviderLogger.e(TAG, "extractFromPage", "Failed to decode AlbaPlayerControl", e)
                }
            }
        }

        // Try Clappr.Player source extraction
        if (hasClappr || hasM3u8) {
            val clapprRegex = Regex("source\\s*:\\s*[\"']([^\"']+)[\"']")
            val srcVarRegex = Regex("src\\s*=\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']")
            val fallbackRegex = Regex("[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")

            val clapprMatch = clapprRegex.find(html)
            val srcVarMatch = if (clapprMatch == null) srcVarRegex.find(html) else null

            ProviderLogger.d(TAG, "extractFromPage",
                "Regex results - clappr: ${clapprMatch?.groupValues?.get(1) ?: "no match"}, " +
                "srcVar: ${srcVarMatch?.groupValues?.get(1) ?: "no match"}"
            )

            val m3u8Url = clapprMatch?.groupValues?.get(1)
                ?: srcVarMatch?.groupValues?.get(1)
                ?: fallbackRegex.find(html)?.groupValues?.get(1)

            if (m3u8Url != null) {
                ProviderLogger.d(TAG, "extractFromPage", "Extracted M3U8 URL: $m3u8Url")

                val m3u8Links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = pageUrl,
                    headers = streamHeaders
                )

                if (m3u8Links.isNotEmpty()) {
                    ProviderLogger.d(TAG, "extractFromPage", "M3u8Helper generated ${m3u8Links.size} quality variants")
                    m3u8Links.forEach { callback(it) }
                    return true
                }

                ProviderLogger.d(TAG, "extractFromPage", "M3u8Helper returned 0 links, emitting raw M3U8 as fallback")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Stream",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
                return true
            }

            ProviderLogger.e(TAG, "extractFromPage", "Found Clappr/m3u8 markers but no regex matched! Checking HTML snippet around 'source':")
            val sourceIdx = html.indexOf("source")
            if (sourceIdx >= 0) {
                ProviderLogger.e(TAG, "extractFromPage", "Context: ${html.substring(maxOf(0, sourceIdx - 20), minOf(html.length, sourceIdx + 80))}")
            }
        }

        ProviderLogger.e(TAG, "extractFromPage", "No playable stream found in page: $pageUrl")
        return false
    }
}
