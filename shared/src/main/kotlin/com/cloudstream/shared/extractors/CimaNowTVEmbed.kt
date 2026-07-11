package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class CimaNowTVEmbed : ExtractorApi() {
    override val name = "CimaNowTV"
    override val mainUrl = "https://cimanowtv.com"
    override val requiresReferer = true

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

        val http = ProviderHttpServiceHolder.getInstance()
        val html = http?.getText(
            url,
            headers = mapOf("Referer" to (referer ?: "https://$host/")),
            rewriteDomain = false
        ) ?: run {
            ProviderLogger.e(TAG, "getUrl", "Failed to fetch embed page: $url")
            return
        }

        val baseUrl = "https://$host"

        val qualities = mapOf(
            "2160p" to Qualities.P2160.value,
            "1440p" to Qualities.P1440.value,
            "1080p" to Qualities.P1080.value,
            "720p" to Qualities.P720.value,
            "480p" to Qualities.P480.value,
            "360p" to Qualities.P360.value,
            "240p" to Qualities.P240.value
        )

        val entryRegex = Regex("\"\\[([^\\]]+)\\]\\s+([^\"]+)\"")
        val entries = entryRegex.findAll(html)
        var found = false

        for (entry in entries) {
            val label = entry.groupValues[1].trim()
            var path = entry.groupValues[2].trim().trimEnd(',')

            if (path.isBlank()) continue

            val fullUrl = when {
                path.startsWith("http") -> path
                path.startsWith("//") -> "https:$path"
                path.startsWith("/") -> "$baseUrl$path"
                else -> "$baseUrl/$path"
            }

            val quality = qualities.entries.firstOrNull { label.contains(it.key, ignoreCase = true) }?.value
                ?: Qualities.Unknown.value

            val link = newExtractorLink(
                source = name,
                name = "$name $label",
                url = fullUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = quality
            }
            callback(link)
            found = true
            ProviderLogger.i(TAG, "getUrl", "Emitted $label -> ${fullUrl.take(80)}")
        }

        if (!found) {
            ProviderLogger.e(TAG, "getUrl", "No Playerjs video entries found in embed page")
        }
    }
}
