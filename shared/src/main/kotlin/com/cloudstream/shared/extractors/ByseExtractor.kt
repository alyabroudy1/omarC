package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.cloudstream.shared.webview.VideoSnifferEngine
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.WebViewResult
import com.cloudstream.shared.webview.ExitCondition
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
        
        val service = ProviderHttpServiceHolder.getInstance()
        if (service == null) {
            android.util.Log.e(name, "ProviderHttpService is not initialized")
            return
        }
        
        android.util.Log.d(name, "Fetching internal Byse API: $apiUrl using ProviderHttpService")
        
        try {
            // 1. Bypass broken SPA wrapper by fetching real iframe URL securely via central HTTP Service
            // MUST use skipRewrite = true so the HTTP service doesn't rewrite bysezejataos.com to the provider's domain
            val response = service.getText(apiUrl, mapOf("Referer" to url), skipRewrite = true)
            if (response.isNullOrEmpty()) {
                android.util.Log.e(name, "Empty response from API")
                return
            }
            
            var iframeUrl = Regex("\"embed_frame_url\"\\s*:\\s*\"([^\"]+)\"").find(response)?.groupValues?.get(1)
            
            if (iframeUrl.isNullOrEmpty()) {
                iframeUrl = url
            } else {
                // Converts JSON serialized string "https:\/\/f75s.com\/..." to "https://f75s.com/..."
                iframeUrl = iframeUrl.replace("\\/", "/") 
            }
            
            android.util.Log.d(name, "Launching Service Headless Sniffer for raw iframe -> $iframeUrl")
            
            val preSniffJs = """
                (function() {
                    try {
                        var buttons = document.querySelectorAll('.jw-video, .plyr__control--overlaid, .play-btn, #vplayer, .video-js, button');
                        buttons.forEach(function(b) { b.click(); });
                        var centerEl = document.elementFromPoint(window.innerWidth/2, window.innerHeight/2);
                        if(centerEl) centerEl.click();
                        document.querySelectorAll('video').forEach(function(v) { v.muted = true; v.play(); });
                    } catch(e) {}
                })();
            """.trimIndent()
            
            // 2. Launch Sniffer securely relying on ProviderHttpService's engine infrastructure
            val sessionResult = service.snifferEngine.runSession(
                url = iframeUrl,
                mode = Mode.HEADLESS,
                userAgent = service.userAgent,
                exitCondition = ExitCondition.VideoFound(minCount = 1),
                timeout = 20000,
                delayMs = 1500,
                referer = url,
                preSniffJavaScript = preSniffJs
            )
            
            android.util.Log.d(name, "Sniffer completed with result type: ${sessionResult.javaClass.simpleName}")
            
            var hasFoundLinks = false
            
            // 3. Process Extractor Matches Gracefully
            if (sessionResult is WebViewResult.Success) {
                // Expose cookies from headless sniffer run to the HTTP service seamlessly
                service.storeCdnCookies(iframeUrl, sessionResult.cookies)
                
                sessionResult.foundLinks.forEach { source ->
                    if (source.url.contains(".m3u8", ignoreCase = true) || source.url.contains(".mp4", ignoreCase = true)) {
                        android.util.Log.d(name, "Extracted HEADLESS link -> ${source.url.take(100)}")
                        hasFoundLinks = true
                        
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
            }
            
            // 4. Fallback HTML Scraping (Crucial for Streamwish)
            if (!hasFoundLinks) {
                val html = when (sessionResult) {
                    is WebViewResult.Success -> sessionResult.html
                    is WebViewResult.Timeout -> sessionResult.partialHtml
                    else -> null
                }
                
                if (!html.isNullOrEmpty()) {
                    val fallbackUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                        ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                        
                    if (!fallbackUrl.isNullOrEmpty()) {
                        android.util.Log.d(name, "Extracted HTML Regex Fallback -> ${fallbackUrl.take(100)}")
                        val isM3u8 = fallbackUrl.contains(".m3u8", ignoreCase = true)
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name Auto",
                                url = fallbackUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = iframeUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else if (sessionResult is WebViewResult.Timeout || sessionResult is WebViewResult.Error) {
                        android.util.Log.w(name, "No video found via network or fallback for $iframeUrl")
                    }
                } else if (sessionResult is WebViewResult.Timeout || sessionResult is WebViewResult.Error) {
                    android.util.Log.w(name, "No video found or timed out scanning $iframeUrl - exiting gracefully.")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(name, "Graceful failure during HEADLESS extraction: ${e.message}")
        }
    }
}
