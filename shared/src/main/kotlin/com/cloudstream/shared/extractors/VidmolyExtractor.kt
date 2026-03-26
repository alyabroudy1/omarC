package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import android.util.Log

/**
 * A robust extractor for Vidmoly and its mirror domains.
 * Completely overrides the core parser to avoid Jackson JSON strictness 
 * and reliably extract JWPlayer M3U8 sources using regex.
 */
class VidmolyExtractor(val host: String, override val name: String = "Vidmoly") : ExtractorApi() {
    override val mainUrl: String get() = "https://$host"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val methodTag = "[$name] [getUrl]"
        Log.d(methodTag, "Processing $url")
        
        try {
            val res = app.get(url, referer = referer)
            val script = res.document.select("script").find { it.data().contains("sources:") }?.data()
            
            if (script == null) {
                Log.w(methodTag, "Failed to find sources script inside the page")
                return
            }
            
            // Robust regex that ignores JSON syntax errors (e.g. single quotes)
            val match = Regex("file\\s*:\\s*[\"'](http[^\"']+\\.m3u8[^\"']*)[\"']").find(script)
            val m3u8Url = match?.groupValues?.get(1)
            
            if (m3u8Url != null) {
                Log.d(methodTag, "Extracted robust M3U8: $m3u8Url")
                
                // Let the native M3U8 Helper parse the playlist and emit links for all available qualities
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = referer ?: "$mainUrl/",
                    name = name
                ).forEach { link ->
                    Log.d(methodTag, "Emitting parsed variant: ${link.url}")
                    callback(link)
                }
            } else {
                Log.w(methodTag, "Regex failed to match m3u8 link in script")
            }
        } catch (e: Exception) {
            Log.e(methodTag, "Error extracting Vidmoly: ${e.message}")
        }
    }
}
