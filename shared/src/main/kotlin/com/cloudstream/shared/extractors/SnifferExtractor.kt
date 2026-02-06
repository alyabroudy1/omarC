package com.cloudstream.shared.extractors

import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.WebViewEngine
import com.cloudstream.shared.webview.WebViewResult
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Sniffer Extractor - registered as a CloudStream extractor to handle video sniffing.
 * 
 * This extractor catches URLs with a special prefix pattern:
 * `sniffer://[base64_encoded_embed_url]?referer=[base64_encoded_referer]`
 * 
 * When LazyExtractor fails to find a matching extractor, it prefixes the embed URL
 * with this pattern and calls loadExtractor again. This extractor then:
 * 1. Decodes the embed URL
 * 2. Runs WebViewEngine in FULLSCREEN mode to sniff video URLs
 * 3. Returns the found video URLs as ExtractorLinks
 * 
 * This approach integrates with CloudStream's extractor system properly,
 * ensuring the final ExtractorLink has the real video URL.
 */
class SnifferExtractor : ExtractorApi() {
    
    override val name = "VideoSniffer"
    override val mainUrl = "sniff://"  // Matches URLs starting with sniff://
    override val requiresReferer = false
    
    private val TAG = "SnifferExtractor"
    
    // WebViewEngine instance - will be set by the provider
    var webViewEngine: WebViewEngine? = null
    var userAgent: String? = null
    
    companion object {
        // Create a sniffer URL from embed URL and referer
        fun createSnifferUrl(embedUrl: String, referer: String = ""): String {
            val encodedUrl = URLEncoder.encode(embedUrl, "UTF-8")
            val encodedReferer = URLEncoder.encode(referer, "UTF-8")
            return "sniff://$encodedUrl?referer=$encodedReferer"
        }
        
        // Parse sniffer URL to get embed URL and referer
        fun parseSnifferUrl(snifferUrl: String): Pair<String, String>? {
            if (!snifferUrl.startsWith("sniff://")) return null
            
            try {
                val content = snifferUrl.removePrefix("sniff://")
                val parts = content.split("?referer=", limit = 2)
                val embedUrl = URLDecoder.decode(parts[0], "UTF-8")
                val referer = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                return Pair(embedUrl, referer)
            } catch (e: Exception) {
                return null
            }
        }
    }
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ProviderLogger.d(TAG, "getUrl", "Processing sniffer URL", "url" to url.take(80))
        
        val parsed = parseSnifferUrl(url)
        if (parsed == null) {
            ProviderLogger.e(TAG, "getUrl", "Failed to parse sniffer URL")
            return
        }
        
        val (embedUrl, embedReferer) = parsed
        ProviderLogger.d(TAG, "getUrl", "Parsed embed URL", 
            "embedUrl" to embedUrl.take(60),
            "embedReferer" to embedReferer.take(40))
        
        // Get or create WebViewEngine
        val engine = webViewEngine ?: run {
            val activity = ActivityProvider.currentActivity
            if (activity == null) {
                ProviderLogger.e(TAG, "getUrl", "No Activity available for WebViewEngine")
                return
            }
            WebViewEngine { ActivityProvider.currentActivity }
        }
        
        val snifferUserAgent = userAgent 
            ?: "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
        
        ProviderLogger.d(TAG, "getUrl", "Starting WebViewEngine sniffing (Visible)")
        
        val result = engine.runSession(
            url = embedUrl,
            mode = WebViewEngine.Mode.FULLSCREEN,
            userAgent = snifferUserAgent,
            exitCondition = ExitCondition.VideoFound(minCount = 1),
            timeout = 60_000L
        )
        
        when (result) {
            is WebViewResult.Success -> {
                ProviderLogger.d(TAG, "getUrl", "Found ${result.foundLinks.size} video sources")
                
                // BUGFIX: Flush cookies from WebView to ensure they're available
                try {
                    android.webkit.CookieManager.getInstance().flush()
                    ProviderLogger.d(TAG, "getUrl", "CookieManager flushed")
                } catch (e: Exception) {
                    ProviderLogger.w(TAG, "getUrl", "Failed to flush cookies", "error" to e.message)
                }
                
                // Small delay to ensure cookies are synced
                kotlinx.coroutines.delay(100)
                
                result.foundLinks.forEach { source ->
                    // URL VALIDATION: Detect and skip truncated URLs
                    val url = source.url
                    if (isUrlTruncated(url)) {
                        ProviderLogger.w(TAG, "getUrl", "URL appears truncated, skipping",
                            "url" to url,
                            "reason" to getTruncationReason(url))
                        return@forEach
                    }
                    
                    val linkType = when {
                        url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }
                    
                    // Determine quality value
                    val qualityValue = when {
                        source.qualityLabel.contains("1080") -> Qualities.P1080.value
                        source.qualityLabel.contains("720") -> Qualities.P720.value
                        source.qualityLabel.contains("480") -> Qualities.P480.value
                        source.qualityLabel.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    
                    // Filter out forbidden headers that can cause protocol errors (like Host mismatch)
                    val filteredHeaders = source.headers.filterKeys { key ->
                        !key.equals("Host", ignoreCase = true) &&
                        !key.equals("Connection", ignoreCase = true) &&
                        !key.equals("Content-Length", ignoreCase = true) &&
                        !key.equals("Content-Type", ignoreCase = true) &&
                        !key.equals("Upgrade", ignoreCase = true) &&
                        !key.equals("Transfer-Encoding", ignoreCase = true)
                    }.toMutableMap()

                    // CAPTURE COOKIES: Crucial for savefiles.com and similar protected servers
                    // Try multiple times to get cookies with delays
                    var cookieHeader: String? = null
                    for (i in 0..3) {
                        try {
                            cookieHeader = android.webkit.CookieManager.getInstance().getCookie(source.url)
                            if (!cookieHeader.isNullOrBlank()) break
                            kotlinx.coroutines.delay(50)
                        } catch (e: Exception) {
                            break
                        }
                    }
                    
                    // Build headers map - CRITICAL: Must include all necessary headers
                    val finalHeaders = mutableMapOf<String, String>()
                    
                    // Add captured headers from WebView (these are crucial!)
                    finalHeaders.putAll(filteredHeaders)
                    
                    // Always add Referer - CRITICAL for 403 prevention
                    finalHeaders["Referer"] = embedUrl
                    
                    // Always add User-Agent
                    finalHeaders["User-Agent"] = snifferUserAgent
                    
                    // Add cookies if available - CRITICAL for 403 prevention
                    if (!cookieHeader.isNullOrBlank()) {
                        finalHeaders["Cookie"] = cookieHeader
                        ProviderLogger.d(TAG, "getUrl", "Added cookies to headers", "cookiesLen" to cookieHeader.length)
                    }
                    
                    // Accept header for video
                    finalHeaders["Accept"] = "*/*"
                    
                    // 403 FIX: Add security headers that WebView sends
                    finalHeaders["Origin"] = extractOrigin(embedUrl)
                    finalHeaders["sec-ch-ua"] = """"Not(A:Brand";v="8", "Chromium";v="120", "Google Chrome";v="120""""
                    finalHeaders["sec-ch-ua-mobile"] = "?1"
                    finalHeaders["sec-ch-ua-platform"] = "Android"
                    finalHeaders["Sec-Fetch-Dest"] = "empty"
                    finalHeaders["Sec-Fetch-Mode"] = "cors"
                    finalHeaders["Sec-Fetch-Site"] = "cross-site"
                    
                    ProviderLogger.d(TAG, "getUrl", "Creating ExtractorLink",
                        "url" to source.url.take(60),
                        "quality" to source.qualityLabel,
                        "headers" to finalHeaders.keys.joinToString())
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name ${source.qualityLabel}",
                            url = source.url,
                            type = linkType
                        ) {
                            this.referer = embedUrl
                            this.quality = qualityValue
                            this.headers = finalHeaders
                        }
                    )
                    
                    ProviderLogger.d(TAG, "getUrl", "ExtractorLink callback invoked successfully")
                }
            }
            is WebViewResult.Timeout -> {
                ProviderLogger.w(TAG, "getUrl", "WebViewEngine timed out", "lastUrl" to result.lastUrl.take(60))
            }
            is WebViewResult.Error -> {
                ProviderLogger.e(TAG, "getUrl", "WebViewEngine error: ${result.reason}")
            }
        }
    }
    
    /**
     * Check if a URL appears to be truncated/incomplete.
     * This detects URLs that were cut off during processing.
     */
    private fun isUrlTruncated(url: String): Boolean {
        // Check for obvious truncation patterns
        return when {
            // URL ends with a dash (e.g., "index-" instead of "index-v1-a1.m3u8")
            url.endsWith("-") -> true
            // URL ends with ".urls" instead of ".urlset/master.m3u8"
            url.endsWith(".urls") -> true
            // URL ends with incomplete file extension
            url.endsWith(".") -> true
            // URL contains ".." (double dots, often a truncation artifact)
            url.contains("..") -> true
            // M3U8 URL without .m3u8 extension
            (url.contains("/index-") || url.contains("/master")) && !url.contains(".m3u8") -> true
            // URLset URL without proper suffix
            url.contains(".urlset") && !url.contains("/master.m3u8") && !url.contains("/index-") -> true
            // URL is suspiciously short for a video URL
            url.length < 60 -> true
            else -> false
        }
    }
    
    /**
     * Get a human-readable reason for why a URL was flagged as truncated.
     */
    private fun getTruncationReason(url: String): String {
        return when {
            url.endsWith("-") -> "URL ends with dash (filename truncated)"
            url.endsWith(".urls") -> "URL ends with .urls (should be .urlset)"
            url.endsWith(".") -> "URL ends with dot (incomplete extension)"
            url.contains("..") -> "URL contains double dots"
            (url.contains("/index-") || url.contains("/master")) && !url.contains(".m3u8") -> "M3U8 URL missing extension"
            url.contains(".urlset") && !url.contains("/master.m3u8") && !url.contains("/index-") -> "URLset missing path"
            url.length < 60 -> "URL too short (${url.length} chars)"
            else -> "Unknown truncation pattern"
        }
    }
    
    /**
     * Extract origin (scheme + host) from a URL.
     * Example: https://example.com/path -> https://example.com
     */
    private fun extractOrigin(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}${if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}"
        } catch (e: Exception) {
            // Fallback: simple string manipulation
            url.substringBefore("/", url)
                .let { if (it.contains("://")) it else "https://$it" }
        }
    }
}
