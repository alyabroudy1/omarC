package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * Extractor for ReviewRate video hosting.
 * Handles URLs from reviewrate.net and m.reviewrate.net
 * 
 * Optimized to extract all video URLs and parse M3U8 playlists for multiple qualities.
 */
class ReviewRateExtractor : ExtractorApi() {
    override val name = "ReviewRate"
    override val mainUrl = "https://reviewrate.net"
    override val requiresReferer = true
    
    companion object {
        private const val TAG = "ReviewRateExtractor"
        
        /** Video URL extraction patterns - order matters, more specific first */
        private val VIDEO_PATTERNS = listOf(
            // JWPlayer sources array with quality labels
            Regex("""sources:\s*\[\s*\{[^}]*file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["'][^}]*(?:label:\s*["']([^"']*)["'])?[^}]*\}""", RegexOption.IGNORE_CASE),
            // JWPlayer file property
            Regex("""file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            // HTML5 video source with type
            Regex("""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["'][^>]*>""", RegexOption.IGNORE_CASE),
            // HTML5 video source simple
            Regex("""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            // Variable assignments
            Regex("""(?:var|let|const)\s+(?:url|source|file|stream|video_url)\s*=\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            // Direct source property
            Regex("""source:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
    }
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val actualReferer = referer ?: mainUrl
        ProviderLogger.d(TAG, "getUrl", "Processing ReviewRate URL", "url" to url.take(80))
        
        try {
            val html = app.get(
                url,
                headers = mapOf(
                    "Referer" to actualReferer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            ).text
            
            // Extract ALL video URLs found in the page
            val videoSources = extractAllVideoSources(html)
            
            if (videoSources.isEmpty()) {
                ProviderLogger.w(TAG, "getUrl", "No video URLs found in page")
                return
            }
            
            ProviderLogger.i(TAG, "getUrl", "Found ${videoSources.size} video source(s)")
            
            // Process each video source
            for (source in videoSources) {
                val videoUrl = source.url.replace("\\/", "/")
                val isM3U8 = videoUrl.contains(".m3u8", ignoreCase = true)
                
                if (isM3U8) {
                    // For M3U8, try to parse playlist for multiple qualities
                    val m3u8Qualities = extractM3u8Qualities(videoUrl, url)
                    
                    if (m3u8Qualities.isNotEmpty()) {
                        ProviderLogger.i(TAG, "getUrl", "Extracted ${m3u8Qualities.size} qualities from M3U8")
                        m3u8Qualities.forEach { qualityLink ->
                            callback(qualityLink)
                        }
                    } else {
                        // Fallback: emit single link if M3U8 parsing failed
                        emitSingleLink(callback, videoUrl, source.quality, url, actualReferer, isM3U8)
                    }
                } else {
                    // For MP4/Direct videos, emit single link
                    emitSingleLink(callback, videoUrl, source.quality, url, actualReferer, isM3U8)
                }
            }
            
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Failed to extract video URL", e)
        }
    }
    
    /**
     * Extract all video URLs from HTML using all patterns.
     * Returns list of pairs (url, quality_label)
     */
    private fun extractAllVideoSources(html: String): List<VideoSourceData> {
        val sources = mutableListOf<VideoSourceData>()
        
        for (pattern in VIDEO_PATTERNS) {
            pattern.findAll(html).forEach { match ->
                val url = match.groupValues.getOrNull(1)?.trim() ?: return@forEach
                
                if (url.isBlank() || url.length < 20) return@forEach
                
                // Try to extract quality from same match context
                val fullMatch = match.value
                val quality = extractQualityFromContext(fullMatch)
                
                // Avoid duplicates
                if (sources.none { it.url == url }) {
                    sources.add(VideoSourceData(url, quality))
                    ProviderLogger.d(TAG, "extractAllVideoSources", "Found URL", 
                        "pattern" to pattern.pattern.take(30), "url" to url.take(60), "quality" to quality)
                }
            }
        }
        
        return sources
    }
    
    /**
     * Extract quality label from the context around the match.
     * Only looks for quality in URL path, not random numbers in filename.
     */
    private fun extractQualityFromContext(context: String): String {
        // Only look for quality in URL path patterns (not filename random numbers)
        val qualityPathPatterns = listOf(
            Regex("""/(?:videos?|movies?|streams?|media)?/(\d{3,4}p?)/""", RegexOption.IGNORE_CASE),
            Regex("""[?&](?:quality|q)=(\d{3,4})""", RegexOption.IGNORE_CASE),
            Regex("""[-_](\d{3,4}p)[-_.]""", RegexOption.IGNORE_CASE),
            Regex("""\.(\d{3,4}p)\.(?:mp4|m3u8)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in qualityPathPatterns) {
            val match = pattern.find(context)
            if (match != null) {
                val value = match.groupValues.getOrNull(1) ?: continue
                if (value.isNotBlank() && value.length <= 5) {
                    return if (value.all { it.isDigit() }) "${value}p" else value
                }
            }
        }
        
        // Only check URL for quality if it looks like a quality indicator
        val urlQualityPatterns = listOf(
            Regex("""/(?:quality|q)=(\d{3,4})""", RegexOption.IGNORE_CASE),
            Regex("""/(\d{3,4}p)/""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in urlQualityPatterns) {
            val match = pattern.find(context)
            if (match != null) {
                val value = match.groupValues.getOrNull(1) ?: continue
                // Only accept if it looks like a real quality (not random filename numbers)
                if (value.replace("p", "").toIntOrNull() in listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)) {
                    return "${value.replace("p", "")}p"
                }
            }
        }
        
        return "Unknown"
    }
    
    /**
     * Parse M3U8 playlist to extract multiple quality options.
     */
    private suspend fun extractM3u8Qualities(m3u8Url: String, pageUrl: String): List<ExtractorLink> {
        return try {
            val m3u8Content = app.get(
                m3u8Url,
                headers = mapOf(
                    "Referer" to pageUrl,
                    "Accept" to "*/*"
                )
            ).text
            
            ProviderLogger.d(TAG, "extractM3u8Qualities", "M3U8 Content", 
                "url" to m3u8Url.take(60), "length" to m3u8Content.length)
            
            // Check if it's a master playlist
            if (!m3u8Content.contains("#EXT-X-STREAM-INF")) {
                ProviderLogger.d(TAG, "extractM3u8Qualities", "Single quality M3U8, parsing as media playlist")
                // Single quality - try to extract from BANDWIDTH or return as-is
                val bandwidth = Regex("""#EXT-X-BANDWIDTH:(\d+)""").find(m3u8Content)?.groupValues?.get(1)
                val quality = if (bandwidth != null) {
                    (bandwidth.toLong() / 1000).toInt().let { "${it}kbps" }
                } else {
                    "Unknown"
                }
                
                return listOf(createExtractorLink(m3u8Url, quality, pageUrl, pageUrl, ExtractorLinkType.M3U8))
            }
            
            // Master playlist - parse all qualities
            val links = mutableListOf<ExtractorLink>()
            val lines = m3u8Content.lines()
            val baseUrl = m3u8Url.substringBeforeLast("/")
            
            lines.forEachIndexed { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    // Extract resolution
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val height = resMatch?.groupValues?.get(2)?.toIntOrNull()
                    
                    // Extract bandwidth as fallback
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    
                    // Get URL from next non-comment line
                    for (i in index + 1 until lines.size) {
                        val nextLine = lines[i].trim()
                        if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                            val streamUrl = if (nextLine.startsWith("http")) nextLine else "$baseUrl/$nextLine"
                            
                            val qualityLabel = height?.let { "${it}p" } ?: bandwidth?.let { "${it / 1000}kbps" } ?: "Auto"
                            val qualityNum = height ?: (bandwidth?.div(1000)?.toInt()) ?: Qualities.Unknown.value
                            
                            links.add(createExtractorLink(streamUrl, qualityLabel, pageUrl, pageUrl, ExtractorLinkType.M3U8).apply {
                                this.quality = qualityNum
                            })
                            break
                        }
                    }
                }
            }
            
            if (links.isEmpty()) {
                ProviderLogger.w(TAG, "extractM3u8Qualities", "No qualities found, returning original URL")
                listOf(createExtractorLink(m3u8Url, "Unknown", pageUrl, pageUrl, ExtractorLinkType.M3U8))
            } else {
                // Sort by quality (highest first)
                links.sortedByDescending { it.quality }
            }
            
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "extractM3u8Qualities", "Failed to parse M3U8", e)
            emptyList()
        }
    }
    
    private suspend fun createExtractorLink(
        url: String,
        qualityLabel: String,
        pageUrl: String,
        externalReferer: String,
        type: ExtractorLinkType
    ): ExtractorLink {
        return newExtractorLink(
            source = name,
            name = "$name $qualityLabel",
            url = url,
            type = type
        ) {
            this.referer = externalReferer
            this.headers = mapOf(
                "Origin" to "https://reviewrate.net",
                "Referer" to externalReferer,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9"
            )
        }
    }
    
    private suspend fun emitSingleLink(
        callback: (ExtractorLink) -> Unit,
        videoUrl: String,
        qualityLabel: String,
        pageUrl: String,
        referer: String,
        isM3U8: Boolean
    ) {
        val type = if (isM3U8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        
        // Parse quality number from label
        val qualityNum = try {
            qualityLabel.replace("p", "").replace("kbps", "").toIntOrNull() ?: Qualities.Unknown.value
        } catch (e: Exception) {
            Qualities.Unknown.value
        }
        
        callback(createExtractorLink(videoUrl, qualityLabel, pageUrl, referer, type).apply {
            this.quality = qualityNum
        })
        
        ProviderLogger.d(TAG, "getUrl", "Emitted link", 
            "type" to type.name, "quality" to qualityLabel, "url" to videoUrl.take(60))
    }
    
    /**
     * Data class to hold extracted video source info.
     */
    private data class VideoSourceData(
        val url: String,
        val quality: String
    )
}
