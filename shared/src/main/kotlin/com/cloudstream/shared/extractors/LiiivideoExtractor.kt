package com.cloudstream.shared.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class LiiivideoExtractor : ExtractorApi() {
    override val name = "Liiivideo"
    override val mainUrl = "https://vipserver.liiivideo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "[$name]"
        Log.d(tag, "Fetching $url")

        val html = app.get(url, referer = referer ?: mainUrl).text

        val m3u8Url = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            .find(html)?.groupValues?.get(1)

        if (m3u8Url == null) {
            Log.w(tag, "No M3U8 URL found in page")
            return
        }

        Log.d(tag, "Found M3U8: ${m3u8Url.take(80)}")

        for (link in M3u8Helper.generateM3u8(
            source = name,
            streamUrl = m3u8Url,
            referer = referer ?: "$mainUrl/",
            name = name
        )) {
            callback(link)
        }
    }
}
