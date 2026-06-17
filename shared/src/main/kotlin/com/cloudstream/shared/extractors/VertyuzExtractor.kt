package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.cloudstream.shared.logging.ProviderLogger

class VertyuzExtractor : ExtractorApi() {
    override val name = "Vertyuz"
    override val mainUrl = "https://tv.vertyuz.xyz"
    override val requiresReferer = true

    companion object {
        private const val TAG = "VertyuzExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ProviderLogger.d(TAG, "getUrl", "Processing Vertyuz URL", "url" to url, "referer" to referer)
        
        try {
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            val headers = mutableMapOf(
                "User-Agent" to userAgent
            )
            if (!referer.isNullOrBlank()) {
                headers["Referer"] = referer
            }

            // Fetch the vertyuz channel page
            val response = app.get(url, headers = headers).text
            if (response.isBlank()) {
                ProviderLogger.e(TAG, "getUrl", "Empty response from Vertyuz URL")
                return
            }

            // Find playbackURL in response
            val playRegex = Regex("var playbackURL\\s*=\\s*[\"']([^\"']+)[\"']")
            val m3u8Url = playRegex.find(response)?.groupValues?.get(1)
            
            if (m3u8Url == null) {
                ProviderLogger.e(TAG, "getUrl", "Could not find playbackURL in Vertyuz page")
                return
            }

            ProviderLogger.d(TAG, "getUrl", "Extracted playbackURL: $m3u8Url")

            val streamHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to url,
                "Origin" to "https://tv.vertyuz.xyz"
            )

            // Try to extract qualities from the M3U8 content manually using custom fetcher
            val m3u8Links = extractM3u8Qualities(m3u8Url, url, streamHeaders)

            if (m3u8Links.isNotEmpty()) {
                for (link in m3u8Links) {
                    callback(link)
                }
            } else {
                // Fallback: emit single link if manual parsing did not yield anything
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Stream",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
            }

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting Vertyuz", e)
        }
    }

    private suspend fun extractM3u8Qualities(
        m3u8Url: String,
        pageUrl: String,
        headers: Map<String, String>
    ): List<ExtractorLink> {
        return try {
            val m3u8Content = app.get(m3u8Url, headers = headers).text
            if (m3u8Content.isBlank()) return emptyList()

            // Check if it's a master playlist
            if (!m3u8Content.contains("#EXT-X-STREAM-INF")) {
                return emptyList()
            }

            val links = mutableListOf<ExtractorLink>()
            val lines = m3u8Content.lines()
            val baseUrl = m3u8Url.substringBeforeLast("/")

            lines.forEachIndexed { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    // Extract resolution
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val height = resMatch?.groupValues?.get(2)?.toIntOrNull()

                    // Extract bandwidth as fallback
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()

                    // Get URL from next non-comment line
                    for (i in index + 1 until lines.size) {
                        val nextLine = lines[i].trim()
                        if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                            val streamUrl = if (nextLine.startsWith("http")) nextLine else "$baseUrl/$nextLine"
                            val qualityLabel = height?.let { "${it}p" } ?: bandwidth?.let { "${it / 1000}kbps" } ?: "Auto"
                            val qualityNum = height ?: Qualities.Unknown.value

                            links.add(
                                newExtractorLink(
                                    source = name,
                                    name = "$name $qualityLabel",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = pageUrl
                                    this.quality = qualityNum
                                    this.headers = headers
                                }
                            )
                            break
                        }
                    }
                }
            }

            links
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "extractM3u8Qualities", "Failed to parse M3U8 manually", e)
            emptyList()
        }
    }
}
