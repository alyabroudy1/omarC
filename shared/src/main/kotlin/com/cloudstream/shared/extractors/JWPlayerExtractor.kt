package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.strategy.VideoSource
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * JWPlayer source extraction utility.
 * 
 * Extracts video sources from HTML containing JWPlayer patterns.
 */
object JWPlayerExtractor {
    
    private val TAG = "JWPlayerExtractor"
    
    /** Patterns for video URL extraction */
    private val VIDEO_PATTERNS = listOf(
        // JWPlayer file property
        Regex("""file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        
        // JWPlayer sources array
        Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        
        // HTML5 source tag
        Regex("""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        
        // Generic source property
        Regex("""source:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        
        // URL in variable
        Regex("""(?:var|let|const)\s+(?:url|source|file|stream)\s*=\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
    )
    
    /** Patterns for quality extraction */
    private val QUALITY_PATTERNS = listOf(
        Regex("""(?:quality|label)["']?\s*[:=]\s*["']?(\d+)p?["']?""", RegexOption.IGNORE_CASE),
        Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
    )
    
    /**
     * Extract video sources from HTML.
     */
    fun extractFromHtml(html: String): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()
        
        VIDEO_PATTERNS.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val url = match.groupValues[1]
                    .replace("\\/", "/")
                    .trim()
                
                if (url.isNotBlank() && url.length > 30) {
                    val quality = detectQuality(url, html)
                    val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    
                    if (sources.none { it.url == url }) {
                        sources.add(VideoSource(url, quality, emptyMap(), type))
                        ProviderLogger.d(TAG, "extractFromHtml", "Found source",
                            "quality" to quality, "urlLength" to url.length)
                    }
                }
            }
        }
        
        return sources
    }
    
    /**
     * Extract sources from JWPlayer JSON sources array.
     */
    fun extractFromSourcesJson(json: String): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()
        
        try {
            // Parse [{file:"...", label:"..."}, ...]
            val sourcePattern = Regex("""\{\s*file:\s*["']([^"']+)["'][^}]*(?:label:\s*["']([^"']*)["'])?""")
            
            sourcePattern.findAll(json).forEach { match ->
                val url = match.groupValues[1].replace("\\/", "/")
                val label = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: detectQuality(url, "")
                
                if (url.isNotBlank() && url.length > 30) {
                    val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    sources.add(VideoSource(url, label, emptyMap(), type))
                }
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "extractFromSourcesJson", "Parse error", e)
        }
        
        return sources
    }
    
    /**
     * Detect quality from URL or surrounding context.
     */
    fun detectQuality(url: String, context: String): String {
        val lowerUrl = url.lowercase()
        
        return when {
            lowerUrl.contains("1080") -> "1080p"
            lowerUrl.contains("720") -> "720p"
            lowerUrl.contains("480") -> "480p"
            lowerUrl.contains("360") -> "360p"
            lowerUrl.contains("master") || lowerUrl.contains("auto") -> "Auto"
            else -> {
                // Try to find quality in context
                QUALITY_PATTERNS.firstNotNullOfOrNull { pattern ->
                    pattern.find(context)?.groupValues?.get(1)?.let { "${it}p" }
                } ?: "Unknown"
            }
        }
    }
    
    /**
     * Extract single best quality source from HTML.
     */
    fun extractBestSource(html: String): VideoSource? {
        val sources = extractFromHtml(html)
        
        // Priority order: 1080p > 720p > 480p > Auto > Others
        val priority = listOf("1080p", "720p", "480p", "Auto")
        
        return priority.firstNotNullOfOrNull { quality ->
            sources.find { it.label == quality }
        } ?: sources.firstOrNull()
    }
}
