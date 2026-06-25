package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class LuluvidExtractor : ExtractorApi() {
    override val name = "Luluvid"
    override val mainUrl = "https://luluvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val actualReferer = referer ?: mainUrl
        val html = app.get(url, referer = actualReferer).document.outerHtml()

        val videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

        if (videoUrl != null) {
            callback(newExtractorLink(name, name, videoUrl, type = getLinkType(videoUrl)) {
                this.referer = actualReferer
                this.quality = Qualities.Unknown.value
            })
        }
    }

    private fun getLinkType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mp4") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
    }
}
