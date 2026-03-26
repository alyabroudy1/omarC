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
        // Build the sniffer protocol URL
        val snifferUrl = SnifferExtractor.createSnifferUrl(url, referer ?: "")
        
        // Delegate to the shared SnifferExtractor which launches a headless-like Android Webview, 
        // fully bypassing Cloudflare and extracting the raw m3u8/mp4 smoothly.
        val sniffer = SnifferExtractor()
        sniffer.videoSnifferEngine = com.cloudstream.shared.webview.VideoSnifferEngine { 
            com.cloudstream.shared.android.ActivityProvider.currentActivity 
        }
        
        sniffer.getUrl(snifferUrl, referer, subtitleCallback, callback)
    }
}
