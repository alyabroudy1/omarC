package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * Extractor for ReviewRate video hosting.
 * Handles URLs from reviewrate.net and m.reviewrate.net
 * 
 * Common patterns in embed pages:
 * - file: "https://...m3u8"
 * - sources: [{file:"..."}]
 * - <source src="...">
 */
class ReviewRateExtractor : ExtractorApi() {
    override val name = "ReviewRate"
    override val mainUrl = "https://reviewrate.net"
    override val requiresReferer = true
    
    companion object {
        private const val TAG = "ReviewRateExtractor"
        
        /** Video URL extraction patterns */
        private val VIDEO_PATTERNS = listOf(
            // JWPlayer patterns
            """file:\s*["']([^"']+\.m3u8[^"']*)["']""",
            """file:\s*["']([^"']+\.mp4[^"']*)["']""",
            """sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""",
            // HTML5 video patterns
            """<source[^>]+src=["']([^"']+\.m3u8[^"']*)["']""",
            """<source[^>]+src=["']([^"']+\.mp4[^"']*)["']""",
            // Variable patterns
            """var\s+url\s*=\s*["']([^"']+)["']""",
            """source:\s*["']([^"']+\.m3u8[^"']*)["']""",
            """source:\s*["']([^"']+\.mp4[^"']*)["']"""
        )
    }
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ProviderLogger.d(TAG, "getUrl", "Processing ReviewRate URL", "url" to url.take(80))
        
        try {
            val html = app.get(
                url,
                headers = mapOf(
                    "Referer" to (referer ?: mainUrl),
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            ).text
            
            // Try each pattern to find video URL
            var videoUrl: String? = null
            for (pattern in VIDEO_PATTERNS) {
                val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
                if (match != null) {
                    videoUrl = match.groupValues[1]
                    ProviderLogger.d(TAG, "getUrl", "Found video URL", "pattern" to pattern.take(30), "url" to videoUrl.take(60))
                    break
                }
            }
            
            if (videoUrl.isNullOrBlank()) {
                ProviderLogger.w(TAG, "getUrl", "No video URL found in page")
                return
            }
            
            // Clean up URL
            videoUrl = videoUrl.replace("\\/", "/")
            
            // Determine link type
            val isM3U8 = videoUrl.contains(".m3u8")
            val linkType = if (isM3U8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            
            // Extract quality from URL if available
            val qualityMatch = Regex("""(\d{3,4})p?""").find(videoUrl)
            val quality = qualityMatch?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
            
            callback(
                newExtractorLink(
                    source = name,
                    name = if (isM3U8) "$name HLS" else "$name MP4",
                    url = videoUrl,
                    type = linkType
                ) {
                    this.referer = url
                    this.quality = quality
                }
            )
            
            ProviderLogger.d(TAG, "getUrl", "Emitted link", "type" to linkType.name, "quality" to quality)
            
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Failed to extract video URL", e)
        }
    }
}
