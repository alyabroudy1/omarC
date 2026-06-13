package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
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

            // Generate M3U8 links with correct headers
            val m3u8Links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to url,
                    "Origin" to "https://tv.vertyuz.xyz"
                )
            )

            m3u8Links.forEach { link ->
                callback(link)
            }

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting Vertyuz", e)
        }
    }
}
