package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Extractor for Bysezejataos.com and its aliases (often used by Aniworld.to and others).
 * Since the site is a heavily obfuscated Single Page Application (SPA) protected by Cloudflare,
 * the most robust method is to use the native Webview Sniffer to capture the video source 
 * dynamically rather than brittle static JSON/Regex parsing that breaks on updates.
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
        android.util.Log.d(name, "getUrl: Starting extraction for Bysezejataos (SPA) -> $url")
        android.util.Log.d(name, "getUrl: Incoming referer -> $referer")

        // Build the sniffer protocol URL
        val snifferUrl = SnifferExtractor.createSnifferUrl(url, referer ?: "")
        android.util.Log.d(name, "getUrl: Generated Sniffer URL -> $snifferUrl")
        
        // Delegate to the shared SnifferExtractor which launches a headless-like Android Webview, 
        // fully bypassing Cloudflare and extracting the raw m3u8/mp4 smoothly.
        val sniffer = SnifferExtractor()
        sniffer.videoSnifferEngine = com.cloudstream.shared.webview.VideoSnifferEngine { 
            com.cloudstream.shared.android.ActivityProvider.currentActivity 
        }
        
        android.util.Log.d(name, "getUrl: Delegating to SnifferExtractor...")
        
        // Wrap the callback to log the exact extracted targets
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            android.util.Log.d(name, "getUrl: [SUCCESS] Sniffer returned link -> name=${link.name}, quality=${link.quality}, url=${link.url.take(100)}")
            callback(link)
        }

        try {
            sniffer.getUrl(snifferUrl, referer, subtitleCallback, wrappedCallback)
            android.util.Log.d(name, "getUrl: Sniffer extractor delegation completed.")
        } catch (e: Exception) {
            android.util.Log.e(name, "getUrl: Sniffer extractor failed with exception: ${e.message}", e)
        }
    }
}
