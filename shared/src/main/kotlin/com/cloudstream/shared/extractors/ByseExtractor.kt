package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.cloudstream.shared.webview.VideoSnifferEngine
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.WebViewResult
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.android.ActivityProvider
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

/**
 * Extractor for Bysezejataos.com and its aliases (often used by Aniworld.to and others).
 * Uses a self-contained Headless WebView to extract video URLs dynamically, completely 
 * bypassing the UI and fullscreen fallbacks.
 */
class ByseExtractor(val host: String, override val name: String = "Byse") : ExtractorApi() {
    override val mainUrl: String get() = "https://$host"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedId = url.substringAfterLast("/")
        val apiUrl = "https://$host/api/videos/$embedId/embed/details"
        
        android.util.Log.d(name, "Fetching internal Byse API: $apiUrl")
        
        try {
            // 1. Bypass broken SPA wrapper by fetching real iframe URL dynamically via REST
            val response = app.get(apiUrl, referer = url).text
            var iframeUrl = Regex("\"embed_frame_url\"\\s*:\\s*\"([^\"]+)\"").find(response)?.groupValues?.get(1)
            
            if (iframeUrl.isNullOrEmpty()) {
                iframeUrl = url
            } else {
                // Converts JSON serialized string "https:\/\/f75s.com\/..." to "https://f75s.com/..."
                iframeUrl = iframeUrl.replace("\\/", "/") 
            }
            
            android.util.Log.d(name, "Launching Headless Sniffer for raw iframe -> $iframeUrl")
            
            // 2. Launch Sniffer in strict HEADLESS mode
            val engine = VideoSnifferEngine { ActivityProvider.currentActivity }
            val sessionResult = engine.runSession(
                url = iframeUrl,
                mode = Mode.HEADLESS,
                userAgent = com.cloudstream.shared.session.SessionProvider.getUserAgent(), // Use Cloudstream's shared user agent
                exitCondition = ExitCondition.VideoFound(minCount = 1),
                timeout = 15000,
                delayMs = 1500,
                referer = url
            )
            
            android.util.Log.d(name, "Sniffer completed with result type: ${sessionResult.javaClass.simpleName}")
            
            // 3. Process Extractor Matches
            if (sessionResult is WebViewResult.Success) {
                sessionResult.foundLinks.forEach { source ->
                    // Make sure we only grab valid media formats
                    if (source.url.contains(".m3u8", ignoreCase = true) || source.url.contains(".mp4", ignoreCase = true)) {
                        android.util.Log.d(name, "Extracted HEADLESS link -> ${source.url.take(100)}")
                        
                        val qualityValue = when {
                            source.qualityLabel.contains("1080") -> Qualities.P1080.value
                            source.qualityLabel.contains("720") -> Qualities.P720.value
                            source.qualityLabel.contains("480") -> Qualities.P480.value
                            source.qualityLabel.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }

                        val isM3u8 = source.url.contains(".m3u8", ignoreCase = true)
                        
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name ${source.qualityLabel}".trim(),
                                url = source.url,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = iframeUrl
                                this.headers = source.headers
                                this.quality = qualityValue
                            }
                        )
                    }
                }
            } else if (sessionResult is WebViewResult.Error) {
                android.util.Log.e(name, "Headless sniffer error: ${sessionResult.reason}")
            } else if (sessionResult is WebViewResult.Timeout) {
                android.util.Log.e(name, "Headless sniffer timed out while scanning $iframeUrl")
            }
        } catch (e: Exception) {
            android.util.Log.e(name, "Exception during Byse HEADLESS extraction: ${e.message}", e)
        }
    }
}
