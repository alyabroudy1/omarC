package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.strategy.VideoSniffingStrategy
import com.lagradost.cloudstream3.AcraApplication
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
        
        // Check if this is a virtual URL or a direct URL
        if (url.contains(serverEndpoint)) {
            // Virtual URL path - parse parameters and fetch embed URL
            processVirtualUrl(url, referer, subtitleCallback, callback)
        } else {
            // Direct URL path - pass directly to extractors
            processDirectUrl(url, referer, subtitleCallback, callback)
        }
    }
    
    /**
     * Process virtual URLs (e.g., /get__watch__server/?post_id=...).
     * Makes POST request, parses JSON, decodes Base64 if needed.
     */
    protected open suspend fun processVirtualUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val postId = getQueryParam(url, "post_id") ?: return
        val quality = getQueryParam(url, "quality") ?: "720"
        val server = getQueryParam(url, "server") ?: "0"
        val csrfToken = getQueryParam(url, "csrf_token") ?: ""
        val rawReferer = getQueryParam(url, "referer")
        val pageReferer = rawReferer?.let { 
            try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
        } ?: referer ?: ""
        
        val baseUrl = url.substringBefore(serverEndpoint)
        
        ProviderLogger.d(TAG, "processVirtualUrl", "Params parsed",
            "postId" to postId, "quality" to quality, "server" to server)
        
        // Fetch embed URL via POST
        val embedUrl = fetchEmbedUrl(baseUrl, postId, quality, server, csrfToken, pageReferer)
        if (embedUrl.isBlank()) {
            ProviderLogger.e(TAG, "processVirtualUrl", "Failed to get embed URL")
            return
        }
        
        ProviderLogger.d(TAG, "processVirtualUrl", "Got embed URL", "url" to embedUrl.take(80))
        
        var foundVideo = false
        
        // ===== TRY OUR FIXED EXTRACTORS FIRST (with proper referer handling) =====
        // These fix 403 errors that CloudStream's built-in extractors cause
        when {
            embedUrl.contains("up4fun.top") || embedUrl.contains("up4stream.com") -> {
                ProviderLogger.d(TAG, "processVirtualUrl", "Using fixed Up4FunExtractor")
                Up4FunExtractor().getUrl(embedUrl, pageReferer, subtitleCallback) { link ->
                    ProviderLogger.d(TAG, "processVirtualUrl", "Up4FunExtractor returned link", "url" to link.url.take(60))
                    callback(link)
                    foundVideo = true
                }
            }
            embedUrl.contains("reviewrate.net") -> {
                ProviderLogger.d(TAG, "processVirtualUrl", "Using ReviewRateExtractor")
                ReviewRateExtractor().getUrl(embedUrl, pageReferer, subtitleCallback) { link ->
                    ProviderLogger.d(TAG, "processVirtualUrl", "ReviewRateExtractor returned link", "url" to link.url.take(60))
                    callback(link)
                    foundVideo = true
                }
            }
        }
        
        // ===== FALLBACK TO CLOUDSTREAM EXTRACTORS =====
        if (!foundVideo) {
            ProviderLogger.d(TAG, "processVirtualUrl", "Calling loadExtractor with 15s timeout", 
                "embedUrl" to embedUrl.take(80),
                "referer" to (pageReferer.take(60).ifBlank { "EMPTY" }))
            
            try {
                kotlinx.coroutines.withTimeoutOrNull(15_000) {
                    loadExtractor(embedUrl, pageReferer, subtitleCallback) { link ->
                        ProviderLogger.d(TAG, "processVirtualUrl", "loadExtractor returned link",
                            "source" to link.source,
                            "url" to link.url.take(60))
                        callback(link)
                        foundVideo = true
                    }
                } ?: ProviderLogger.w(TAG, "processVirtualUrl", "loadExtractor timed out after 15s")
            } catch (e: Exception) {
                ProviderLogger.e(TAG, "processVirtualUrl", "loadExtractor failed", e)
            }
        }
        
        // Manual extraction fallback
        if (!foundVideo) {
            ProviderLogger.d(TAG, "processVirtualUrl", "Trying manual extraction")
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
                        this.headers = mapOf(
                            "Referer" to embedUrl,
                            "Accept" to "*/*"
                        )
                    }
                )
                foundVideo = true
            }
        }
        
        // ===== VIDEO SNIFFER FALLBACK (WebView-based) =====
        // Last resort: use headless WebView to capture video URLs from the embed page
        if (!foundVideo) {
            ProviderLogger.d(TAG, "processVirtualUrl", "Trying VideoSniffer fallback")
            try {
                val context = AcraApplication.context
                if (context != null) {
                    val sniffer = VideoSniffingStrategy(context, timeout = 30_000)
                    val userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
                    val sources = sniffer.sniff(embedUrl, userAgent)
                    
                    if (sources.isNotEmpty()) {
                        ProviderLogger.d(TAG, "processVirtualUrl", "VideoSniffer found ${sources.size} sources")
                        sources.forEach { source ->
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ${source.quality}",
                                    url = source.url,
                                    type = source.type ?: ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = when {
                                        source.quality.contains("1080") -> Qualities.P1080.value
                                        source.quality.contains("720") -> Qualities.P720.value
                                        source.quality.contains("480") -> Qualities.P480.value
                                        source.quality.contains("360") -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }
                                    this.headers = source.headers + mapOf("Referer" to embedUrl)
                                }
                            )
                        }
                        foundVideo = true
                    }
                }
            } catch (e: Exception) {
                ProviderLogger.e(TAG, "processVirtualUrl", "VideoSniffer failed", e)
            }
        }
    }
    
    /**
     * Process direct URLs (e.g., https://reviewrate.net/...).
     * Handles Base64 decoding for play/?id=... format.
     * Passes to CloudStream extractors.
     */
    protected open suspend fun processDirectUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Decode Base64 if URL contains play/?id= or play.php?id=
        var finalUrl = url
        if (url.contains("id=")) {
            val param = url.substringAfter("id=").substringBefore("&")
            try {
                val decoded = String(android.util.Base64.decode(param, android.util.Base64.DEFAULT))
                if (decoded.startsWith("http")) {
                    finalUrl = decoded
                    ProviderLogger.d(TAG, "processDirectUrl", "Decoded Base64", "url" to finalUrl.take(80))
                }
            } catch (e: Exception) {
                ProviderLogger.d(TAG, "processDirectUrl", "Base64 decode failed, using original URL")
            }
        }
        
        ProviderLogger.d(TAG, "processDirectUrl", "Passing to extractors", "url" to finalUrl.take(80))
        
        // Try global extractors first
        var foundVideo = false
        loadExtractor(finalUrl, referer ?: "", subtitleCallback) { link ->
            callback(link)
            foundVideo = true
        }
        
        // Manual extraction fallback
        if (!foundVideo) {
            ProviderLogger.d(TAG, "processDirectUrl", "Trying manual extraction")
            val directUrl = extractDirectVideoUrl(finalUrl)
            if (directUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = directUrl,
                        type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = finalUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to finalUrl,
                            "Accept" to "*/*"
                        )
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
     * Handles both direct URLs and Base64-encoded URLs.
     */
    protected open fun parseEmbedUrlFromJson(json: String): String {
        try {
            // Check for embed_url first
            val embedMatch = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(json)
            if (embedMatch != null) return embedMatch.groupValues[1].replace("\\/", "/")
            
            // Then check for server field
            val serverMatch = Regex(""""server"\s*:\s*"([^"]+)"""").find(json)
            var serverUrl = serverMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
            
            // Handle Base64 encoded URLs (common in Arabseed responses)
            if (serverUrl.isNotBlank() && !serverUrl.startsWith("http")) {
                try {
                    val decoded = String(android.util.Base64.decode(serverUrl, android.util.Base64.DEFAULT))
                    if (decoded.startsWith("http")) {
                        serverUrl = decoded
                        ProviderLogger.d(TAG, "parseEmbedUrlFromJson", "Decoded Base64 URL", "url" to serverUrl.take(60))
                    }
                } catch (e: Exception) {
                    // Not Base64, use as-is
                }
            }
            
            return serverUrl
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
        return Regex("""[?&]$key=([^&]+)""").find(url)?.groupValues?.get(1)
    }
}
