package com.cloudstream.shared.extractors

import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.session.SessionProvider
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.WebViewEngine
import com.cloudstream.shared.webview.WebViewResult
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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
        android.util.Log.d("SnifferExtractor", "getUrl called with url: $url")
        ProviderLogger.d(TAG, "getUrl", "Processing sniffer URL", "url" to url.take(80))
        
        val parsed = parseSnifferUrl(url)
        if (parsed == null) {
            android.util.Log.e("SnifferExtractor", "Failed to parse sniffer URL: $url")
            ProviderLogger.e(TAG, "getUrl", "Failed to parse sniffer URL")
            return
        }
        
        val (embedUrl, embedReferer) = parsed
        android.util.Log.d("SnifferExtractor", "Parsed embed URL: $embedUrl, referer: $embedReferer")
        ProviderLogger.d(TAG, "getUrl", "Parsed embed URL", 
            "embedUrl" to embedUrl.take(60),
            "embedReferer" to embedReferer.take(40))
        
        // Get or create WebViewEngine
        val engine = webViewEngine ?: run {
            val activity = ActivityProvider.currentActivity
            if (activity == null) {
                android.util.Log.e("SnifferExtractor", "No Activity available for WebViewEngine")
                ProviderLogger.e(TAG, "getUrl", "No Activity available for WebViewEngine")
                return
            }
            WebViewEngine { ActivityProvider.currentActivity }
        }
        
        // CRITICAL: Use SessionProvider to get the SAME UA used for CF challenge
        // This ensures cookies are valid for this UA
        val snifferUserAgent = SessionProvider.getUserAgent()
        android.util.Log.d("SnifferExtractor", "Using SessionProvider UA: ${snifferUserAgent.take(50)}...")
        ProviderLogger.d(TAG, "getUrl", "Using SessionProvider UA",
            "uaHash" to snifferUserAgent.hashCode(),
            "hasSession" to SessionProvider.hasValidSession())
        
        android.util.Log.d("SnifferExtractor", "Starting WebViewEngine sniffing (Visible)")
        ProviderLogger.d(TAG, "getUrl", "Starting WebViewEngine sniffing (Visible)")
        
        val result = engine.runSession(
            url = embedUrl,
            mode = WebViewEngine.Mode.FULLSCREEN,
            userAgent = snifferUserAgent,
            exitCondition = ExitCondition.VideoFound(minCount = 1),
            timeout = 60_000L,
            delayMs = 2000 // Wait 2s for page to fully load before starting detection
        )
        
        var callbackCount = 0
        var totalFound = 0
        
        // Fast exit flag to avoid race conditions when multiple links are found
        var isFinished = false

        android.util.Log.i("SnifferExtractor", "[getUrl] WebViewEngine returned: ${result.javaClass.simpleName}")
        
        when (result) {
            is WebViewResult.Success -> {
                totalFound = result.foundLinks.size
                android.util.Log.i("SnifferExtractor", "[getUrl] SUCCESS! Found $totalFound video sources")
                result.foundLinks.forEachIndexed { index, link ->
                    android.util.Log.i("SnifferExtractor", "[getUrl] Link #$index: ${link.url.take(100)}")
                }
                ProviderLogger.d(TAG, "getUrl", "Found $totalFound video sources")
                
                // BUGFIX: Flush cookies from WebView to ensure they're available
                try {
                    android.webkit.CookieManager.getInstance().flush()
                    android.util.Log.d("SnifferExtractor", "CookieManager flushed")
                    ProviderLogger.d(TAG, "getUrl", "CookieManager flushed")
                } catch (e: Exception) {
                    android.util.Log.w("SnifferExtractor", "Failed to flush cookies: ${e.message}")
                    ProviderLogger.w(TAG, "getUrl", "Failed to flush cookies", "error" to e.message)
                }
                
                // Small delay to ensure cookies are synced
                kotlinx.coroutines.delay(100)
                
                result.foundLinks.forEach { source ->
                    if (isFinished) {
                        android.util.Log.d("SnifferExtractor", "Link skipped (already finished)")
                        return@forEach
                    }
                    
                    val url = source.url
                    if (isUrlTruncated(url)) {
                        android.util.Log.w("SnifferExtractor", "URL truncated: $url reason: ${getTruncationReason(url)}")
                        ProviderLogger.w(TAG, "getUrl", "URL appears truncated, skipping",
                            "url" to url,
                            "reason" to getTruncationReason(url))
                        return@forEach
                    }

                    val linkType = when {
                        url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }
                    
                    // STOP after first valid link
                    isFinished = true 
                    
                    // Determine quality value
                    val qualityValue = when {
                        source.qualityLabel.contains("1080") -> Qualities.P1080.value
                        source.qualityLabel.contains("720") -> Qualities.P720.value
                        source.qualityLabel.contains("480") -> Qualities.P480.value
                        source.qualityLabel.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    
                    // Filter out forbidden headers
                    val filteredHeaders = source.headers.filterKeys { key ->
                        !key.equals("Host", ignoreCase = true) &&
                        !key.equals("Connection", ignoreCase = true) &&
                        !key.equals("Content-Length", ignoreCase = true) &&
                        !key.equals("Content-Type", ignoreCase = true) &&
                        !key.equals("Upgrade", ignoreCase = true) &&
                        !key.equals("Transfer-Encoding", ignoreCase = true)
                    }.toMutableMap()

                    // PREPARE HEADERS (Common logic)
                    val webViewCookies = try {
                        android.webkit.CookieManager.getInstance().getCookie(source.url)
                    } catch (e: Exception) { null }
                    
                    val sessionCookies = SessionProvider.buildCookieHeader()
                    val mergedCookies = mutableMapOf<String, String>()
                    webViewCookies?.split(";")?.forEach { cookie ->
                        val trimmed = cookie.trim()
                        if (trimmed.isNotBlank()) {
                             mergedCookies[trimmed.substringBefore("=")] = trimmed.substringAfter("=", "")
                        }
                    }
                    SessionProvider.getCookies().forEach { (key, value) ->
                        mergedCookies[key] = value
                    }
                    
                    val cookieHeader = if (mergedCookies.isNotEmpty()) {
                        mergedCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    } else null
                    
                    val finalHeaders = mutableMapOf<String, String>()
                    finalHeaders.putAll(filteredHeaders)
                    finalHeaders["Referer"] = embedUrl
                    finalHeaders["User-Agent"] = snifferUserAgent
                    if (!cookieHeader.isNullOrBlank()) {
                        finalHeaders["Cookie"] = cookieHeader
                    }
                    finalHeaders["Accept"] = "*/*"
                    finalHeaders["Origin"] = extractOrigin(embedUrl)
                    finalHeaders["sec-ch-ua"] = """"Not(A:Brand";v="8", "Chromium";v="120", "Google Chrome";v="120""""
                    finalHeaders["sec-ch-ua-mobile"] = "?1"
                    finalHeaders["sec-ch-ua-platform"] = "Android"
                    finalHeaders["Sec-Fetch-Dest"] = "empty"
                    finalHeaders["Sec-Fetch-Mode"] = "cors"
                    finalHeaders["Sec-Fetch-Site"] = "cross-site"

                    // === ERROR HANDLING / M3U8 EXTRACTION ===
                    // If it's an M3U8, try to extract qualities
                    var qualityLinks: List<ExtractorLink>? = null
                    if (linkType == ExtractorLinkType.M3U8) {
                        ProviderLogger.i(TAG, "getUrl", "Attempting M3U8 quality extraction", "url" to source.url)
                        qualityLinks = extractM3u8Qualities(source.url, finalHeaders, embedUrl, name)
                    }
                    
                    if (!qualityLinks.isNullOrEmpty()) {
                         ProviderLogger.i(TAG, "getUrl", "Extracted ${qualityLinks.size} qualities from M3U8")
                         qualityLinks.forEach { qLink ->
                             callback(qLink)
                             callbackCount++
                         }
                    } else {
                        // Fallback: Return original link
                        android.util.Log.i("SnifferExtractor", "[getUrl] INVOKING CALLBACK with URL: ${source.url.take(100)}")
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
                        callbackCount++
                    }
                    
                    android.util.Log.i("SnifferExtractor", "[getUrl] CALLBACK invoked")
                    ProviderLogger.d(TAG, "getUrl", "ExtractorLink callback invoked",
                        "url" to source.url.take(60),
                        "type" to linkType.name)
                }
            }
            is WebViewResult.Timeout -> {
                android.util.Log.w("SnifferExtractor", "[getUrl] TIMEOUT! Last URL: ${result.lastUrl.take(60)}")
                ProviderLogger.w(TAG, "getUrl", "WebViewEngine timed out", "lastUrl" to result.lastUrl.take(60))
            }
            is WebViewResult.Error -> {
                android.util.Log.e("SnifferExtractor", "[getUrl] ERROR: ${result.reason}")
                ProviderLogger.e(TAG, "getUrl", "WebViewEngine error: ${result.reason}")
            }
        }
        
        android.util.Log.i("SnifferExtractor", "[getUrl] === END === totalFound=$totalFound callbacks=$callbackCount")
        ProviderLogger.i(TAG, "getUrl", "=== END ===", 
            "totalFound" to totalFound,
            "successfulCallbacks" to callbackCount)
    }
    
    /**
     * Check if a URL appears to be truncated/incomplete.
     * This detects URLs that were cut off during processing.
     * 
     * FIX: Relaxed validation to allow valid HLS URLs that have unconventional naming
     */
    /**
     * Check if a URL is blacklisted (trackers, analytics, etc.)
     */
    private fun isBlacklisted(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("/ping.gif") || 
               lowerUrl.contains("/analytics") || 
               lowerUrl.contains("/google-analytics") || 
               lowerUrl.contains("doubleclick.net") ||
               lowerUrl.contains("facebook.net") ||
               lowerUrl.contains("adnxs.com") ||
               lowerUrl.contains("criteo.com") ||
               lowerUrl.contains("/ads/") ||
               lowerUrl.contains("favicon.ico")
    }

    private fun isUrlTruncated(url: String): Boolean {
        if (isBlacklisted(url)) return true // Treat blacklisted as "truncated" for skipping
        // Check for obvious truncation patterns
        return when {
            // URL ends with incomplete file extension (just a dot)
            url.endsWith(".") -> true
            // URL contains ".." (double dots, often a truncation artifact) - but not "..."
            url.contains("..") && !url.contains("...") -> true
            // URL is suspiciously short for a video URL (< 40 chars is definitely too short)
            url.length < 40 -> true
            // URL ends with just a dash AND doesn't contain hls or urlset patterns
            url.endsWith("-") && !url.contains("hls") && !url.contains("urlset") -> true
            // NEW: HLS/manifest URLs must have an extension or manifest pattern
            (url.contains("/hls/") || url.contains("/urlset/")) && 
            !url.contains(".m3u8") && 
            !url.contains("/master") && 
            !url.contains("/index-") &&
            !url.contains("token=") -> true
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

    private suspend fun extractM3u8Qualities(
        url: String, 
        headers: Map<String, String>, 
        referer: String,
        sourceName: String
    ): List<ExtractorLink>? {
        return try {
            // Remove referer param as it's already in headers
            val response = app.get(url, headers = headers).text
            if (!response.contains("#EXTM3U")) return null // Not a valid M3U8

            val links = mutableListOf<ExtractorLink>()
            // Split by lines and process
            val lines = response.lines()
            
            lines.forEachIndexed { index, line ->
                // Check for stream info 
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    // Extract resolution
                    // RESOLUTION=1920x1080
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val height = resMatch?.groupValues?.get(2)?.toIntOrNull()
                    
                    // Extract Bandwidth (as fallback for quality)
                    // BANDWIDTH=2000000
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    
                    // The URL is usually the next non-empty, non-comment line
                    for (i in index + 1 until lines.size) {
                        val next = lines[i].trim()
                        if (next.isNotEmpty() && !next.startsWith("#")) {
                            // Found the URL
                            val resolvedUrl = try {
                                if (next.startsWith("http")) next 
                                else java.net.URI(url).resolve(next).toString()
                            } catch (e: Exception) {
                                // Fallback resolution logic
                                if (next.startsWith("http")) next 
                                else "${url.substringBeforeLast("/")}/$next"
                            }
                            
                            val qualityNum = height ?: if (bandwidth != null) (bandwidth / 1000).toInt() else Qualities.Unknown.value
                            val qualityName = if (height != null) "${height}p" else "Auto"
                            
                            links.add(
                                newExtractorLink(
                                    source = sourceName,
                                    name = "$sourceName $qualityName",
                                    url = resolvedUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = referer
                                    this.quality = qualityNum
                                    this.headers = headers
                                }
                            )
                            break // Stop looking for URL for this stream-inf
                        }
                    }
                }
            }
            if (links.isEmpty()) null else links
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "extractM3u8Qualities", "Failed to extract M3U8", e)
            null
        }
    }
}
