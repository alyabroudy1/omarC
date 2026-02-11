package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class SavefilesExtractor : ExtractorApi() {
    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "SavefilesExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val safeUrl = url.replace("http://", "https://")
        ProviderLogger.d(TAG, "getUrl", "Processing", "url" to safeUrl.take(60), "referer" to (referer ?: "null"))

        try {
            val response = app.get(
                safeUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "https://savefiles.com/"
                )
            ).text

            // Look for sources: [{file:"..."}]
            val sourcesRegex = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""")
            val match = sourcesRegex.find(response)
            val videoUrl = match?.groupValues?.get(1)

            if (videoUrl != null) {
                ProviderLogger.i(TAG, "getUrl", "Found video URL", "url" to videoUrl.take(80))
                
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = safeUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                ProviderLogger.w(TAG, "getUrl", "No video URL found in sources block")
                // Check if it requires a click/interaction or is hidden
                // Note: Browser execution showed it IS in the source, but maybe obfuscated?
                // The browser subagent saw: sources: [{file:"https://s3.savefiles.com/hls2/..."}]
                // So the regex should work.
            }

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting video", e)
        }
    }
}
