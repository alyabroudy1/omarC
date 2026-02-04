package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder

typealias JsonFetcher = suspend (url: String, data: Map<String, String>, referer: String) -> String?

/**
 * Abstract base class for lazy URL extraction.
 * 
 * Lazy extractors handle virtual URLs that need to be resolved on-demand.
 * Format: .../get__watch__server/?post_id=...&quality=...&server=...
 */
abstract class LazyExtractor : ExtractorApi() {
    
    abstract override val name: String
    abstract override val mainUrl: String
    override val requiresReferer = true
    
    /** Optional JSON fetcher for delegated requests */
    open val jsonFetcher: JsonFetcher? = null
    
    /** Endpoint path for server requests */
    open val serverEndpoint: String = "/get__watch__server/"
    
    /** Video extraction patterns */
    open val videoPatterns: List<String> = listOf(
        """file:\s*["']([^"']+)["']""",
        """<source[^>]+src=["']([^"']+\.mp4)["']""",
        """sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""",
        """source:\s*["']([^"']+\.mp4)["']""",
        """var\s+url\s*=\s*["']([^"']+)["']"""
    )
    
    private val TAG = "LazyExtractor"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ProviderLogger.d(TAG, "getUrl", "Processing", "url" to url.take(80))
        
        // Parse virtual URL parameters
        val postId = getQueryParam(url, "post_id") ?: return
        val quality = getQueryParam(url, "quality") ?: "720"
        val server = getQueryParam(url, "server") ?: "0"
        val csrfToken = getQueryParam(url, "csrf_token") ?: ""
        val rawReferer = getQueryParam(url, "referer")
        val pageReferer = rawReferer?.let { 
            try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
        } ?: referer ?: ""
        
        val baseUrl = url.substringBefore(serverEndpoint)
        
        ProviderLogger.d(TAG, "getUrl", "Params parsed",
            "postId" to postId, "quality" to quality, "server" to server)
        
        // Fetch embed URL
        val embedUrl = fetchEmbedUrl(baseUrl, postId, quality, server, csrfToken, pageReferer)
        if (embedUrl.isBlank()) {
            ProviderLogger.e(TAG, "getUrl", "Failed to get embed URL")
            return
        }
        
        ProviderLogger.d(TAG, "getUrl", "Got embed URL", "url" to embedUrl.take(80))
        
        // Try global extractors
        var foundVideo = false
        loadExtractor(embedUrl, pageReferer, subtitleCallback) { link ->
            callback(link)
            foundVideo = true
        }
        
        // Manual extraction fallback
        if (!foundVideo) {
            ProviderLogger.d(TAG, "getUrl", "Trying manual extraction")
            val directUrl = extractDirectVideoUrl(embedUrl)
            if (directUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name ${quality}p",
                        url = directUrl,
                        type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = quality.toIntOrNull() ?: 0
                    }
                )
            }
        }
    }
    
    /**
     * Fetch embed URL from server endpoint.
     */
    protected open suspend fun fetchEmbedUrl(
        baseUrl: String,
        postId: String,
        quality: String,
        server: String,
        csrfToken: String,
        referer: String
    ): String {
        try {
            val data = mapOf(
                "post_id" to postId,
                "quality" to quality,
                "server" to server,
                "csrf_token" to csrfToken
            )
            
            // Use delegated fetcher if available
            jsonFetcher?.let { fetcher ->
                ProviderLogger.d(TAG, "fetchEmbedUrl", "Using delegated fetcher")
                val json = fetcher.invoke(serverEndpoint, data, referer)
                if (!json.isNullOrBlank()) {
                    return parseEmbedUrlFromJson(json)
                }
                return ""
            }
            
            // Fallback to app.post
            val endpoint = "$baseUrl$serverEndpoint"
            val response = app.post(
                endpoint,
                headers = mapOf(
                    "Referer" to referer,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = data
            )
            return parseEmbedUrlFromJson(response.text)
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "fetchEmbedUrl", "Error", e)
            return ""
        }
    }
    
    /**
     * Parse embed URL from JSON response.
     */
    protected open fun parseEmbedUrlFromJson(json: String): String {
        try {
            // Simple JSON parsing without dependencies
            val embedMatch = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(json)
            if (embedMatch != null) return embedMatch.groupValues[1].replace("\\/", "/")
            
            val serverMatch = Regex(""""server"\s*:\s*"([^"]+)"""").find(json)
            if (serverMatch != null) return serverMatch.groupValues[1].replace("\\/", "/")
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "parseEmbedUrlFromJson", "Parse error", e)
        }
        return ""
    }
    
    /**
     * Extract direct video URL from embed page HTML.
     */
    protected open suspend fun extractDirectVideoUrl(embedUrl: String): String {
        try {
            val html = app.get(embedUrl).text
            return videoPatterns.firstNotNullOfOrNull { pattern ->
                Regex(pattern, RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            } ?: ""
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "extractDirectVideoUrl", "Error", e)
            return ""
        }
    }
    
    protected fun getQueryParam(url: String, key: String): String? {
        return Regex("[?&]$key=([^&]+)").find(url)?.groupValues?.get(1)
    }
}
