package com.cloudstream.shared.extractors

import java.net.URLDecoder
import java.net.URLEncoder

data class SnifferUrlData(
    val embedUrl: String,
    val referer: String,
    val selector: SnifferSelector?,
    /** The MainAPI provider that requested the sniff, so playback hooks resolve correctly. */
    val source: String
)

/**
 * Pure URL codec for the `sniff://` pseudo-scheme.
 *
 * Kept separate from [SnifferExtractor] so it can be unit-tested on the JVM without pulling in
 * CloudStream's Android-only [com.lagradost.cloudstream3.utils.ExtractorApi].
 */
object SnifferUrlCodec {

    const val DEFAULT_SOURCE = "VideoSniffer"

    fun create(
        embedUrl: String,
        referer: String = "",
        selector: SnifferSelector? = null,
        source: String = DEFAULT_SOURCE
    ): String {
        val encodedUrl = URLEncoder.encode(embedUrl, "UTF-8")
        val encodedReferer = URLEncoder.encode(referer, "UTF-8")
        var url = "sniff://$encodedUrl?referer=$encodedReferer"

        // Preserve the caller's identity. Core uses ExtractorLink.source to look up the MainAPI
        // provider again, and that lookup is how provider-specific playback hooks (notably
        // FaselHDV2.getVideoInterceptor's IPv4 pin) are applied. "VideoSniffer" matches no MainAPI,
        // so playback silently lost those hooks.
        val encodedSource = URLEncoder.encode(source.ifBlank { DEFAULT_SOURCE }, "UTF-8")
        url += "&source=$encodedSource"

        if (selector != null) {
            val encodedSelector = URLEncoder.encode(selector.toJson(), "UTF-8")
            url += "&selector=$encodedSelector"
        }

        return url
    }

    fun parse(snifferUrl: String): SnifferUrlData? {
        if (!snifferUrl.startsWith("sniff://")) return null

        try {
            val content = snifferUrl.removePrefix("sniff://")
            val parts = content.split("?", limit = 2)
            val embedUrl = URLDecoder.decode(parts[0], "UTF-8")

            var referer = ""
            var selector: SnifferSelector? = null
            var source = DEFAULT_SOURCE

            if (parts.size > 1) {
                val queryString = parts[1]
                val queryParams = queryString.split("&").mapNotNull { param ->
                    val keyValue = param.split("=", limit = 2)
                    if (keyValue.size == 2) {
                        keyValue[0] to URLDecoder.decode(keyValue[1], "UTF-8")
                    } else null
                }.toMap()

                referer = queryParams["referer"] ?: ""
                source = queryParams["source"]?.takeIf { it.isNotBlank() } ?: DEFAULT_SOURCE

                queryParams["selector"]?.let { selectorJson ->
                    selector = SnifferSelector.fromJson(selectorJson)
                }
            }

            return SnifferUrlData(embedUrl, referer, selector, source)
        } catch (e: Exception) {
            return null
        }
    }
}
