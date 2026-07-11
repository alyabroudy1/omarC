package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

class CimaNowTVEmbed : ExtractorApi() {
    override val name = "CimaNowTV"
    override val mainUrl = "https://cimanowtv.com"
    override val requiresReferer = false

    private val TAG = "CimaNowTVEmbed"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = Regex("https?://([^/]+)").find(url)?.groupValues?.get(1) ?: run {
            ProviderLogger.e(TAG, "getUrl", "Failed to extract host from URL: $url")
            return
        }
        if (!host.endsWith(".cimanowtv.com") && host != "cimanowtv.com") {
            ProviderLogger.e(TAG, "getUrl", "Unexpected host: $host (expected *.cimanowtv.com)")
            return
        }

        val videoId = url.substringAfter("/e/").substringBefore("?").substringBefore("#")
        if (videoId.isBlank() || videoId.length <= 5) {
            ProviderLogger.e(TAG, "getUrl", "Invalid video ID from URL: $url")
            return
        }

        ProviderLogger.i(TAG, "getUrl", "Delegating to ByseExtractor with host=$host videoId=$videoId")
        ByseExtractor(host, "CimaNowTV").getUrl(url, referer, subtitleCallback, callback)
    }
}
