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
        fun createSnifferUrl(embedUrl: String, referer: String = "", selector: SnifferSelector? = null): String {
            val encodedUrl = URLEncoder.encode(embedUrl, "UTF-8")
            val encodedReferer = URLEncoder.encode(referer, "UTF-8")
            var url = "sniff://$encodedUrl?referer=$encodedReferer"
            
            // Add selector if provided
            if (selector != null) {
                val encodedSelector = URLEncoder.encode(selector.toJson(), "UTF-8")
                url += "&selector=$encodedSelector"
            }
            
            return url
        }
        
        // Parse sniffer URL to get embed URL, referer, and optional selector
        fun parseSnifferUrl(snifferUrl: String): Triple<String, String, SnifferSelector?>? {
            if (!snifferUrl.startsWith("sniff://")) return null
            
            try {
                val content = snifferUrl.removePrefix("sniff://")
                val parts = content.split("?", limit = 2)
                val embedUrl = URLDecoder.decode(parts[0], "UTF-8")
                
                var referer = ""
                var selector: SnifferSelector? = null
                
                if (parts.size > 1) {
                    val queryString = parts[1]
                    val queryParams = queryString.split("&").mapNotNull { param ->
                        val keyValue = param.split("=", limit = 2)
                        if (keyValue.size == 2) {
                            keyValue[0] to URLDecoder.decode(keyValue[1], "UTF-8")
                        } else null
                    }.toMap()
                    
                    referer = queryParams["referer"] ?: ""
                    
                    queryParams["selector"]?.let { selectorJson ->
                        selector = SnifferSelector.fromJson(selectorJson)
                    }
                }
                
                return Triple(embedUrl, referer, selector)
            } catch (e: Exception) {
                android.util.Log.e("SnifferExtractor", "Failed to parse sniffer URL: ${e.message}")
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
        
        val (embedUrl, embedReferer, selector) = parsed
        android.util.Log.d("SnifferExtractor", "Parsed embed URL: $embedUrl, referer: $embedReferer, hasSelector: ${selector != null}")
        ProviderLogger.d(TAG, "getUrl", "Parsed embed URL", 
            "embedUrl" to embedUrl.take(60),
            "embedReferer" to embedReferer.take(40),
            "hasSelector" to (selector != null))
        
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
        
        android.util.Log.i("SnifferExtractor", "[getUrl] === STARTING WEBVIEW SNIFF ===")
        android.util.Log.i("SnifferExtractor", "[getUrl] Target URL: ${embedUrl.take(80)}")
        android.util.Log.i("SnifferExtractor", "[getUrl] Mode: FULLSCREEN")
        android.util.Log.i("SnifferExtractor", "[getUrl] Timeout: 60s")
        ProviderLogger.d(TAG, "getUrl", "Starting WebViewEngine sniffing (Visible)")
        
        // Build pre-sniff JavaScript if selector is provided
        val preSniffJs = selector?.let { buildClickJavaScript(it) }
        
        if (selector != null) {
            android.util.Log.i("SnifferExtractor", "[getUrl] === SELECTOR MODE ===")
            android.util.Log.i("SnifferExtractor", "[getUrl] Selector query: ${selector.query}")
            ProviderLogger.i(TAG, "getUrl", "Selector mode enabled", 
                "query" to selector.query,
                "attr" to (selector.attr ?: "null"),
                "regex" to (selector.regex ?: "null"))
        }
        
        val startTime = System.currentTimeMillis()
        val result = engine.runSession(
            url = embedUrl,
            mode = WebViewEngine.Mode.FULLSCREEN,
            userAgent = snifferUserAgent,
            exitCondition = ExitCondition.VideoFound(minCount = 1),
            timeout = 60_000L,
            delayMs = 2000, // Wait 2s for page to fully load before starting detection
            preSniffJavaScript = preSniffJs,
            referer = embedReferer // Pass referer for embed servers (e.g., https://laroza.cfd/)
        )
        
        var callbackCount = 0
        var totalFound = 0
        
        // Fast exit flag to avoid race conditions when multiple links are found
        var isFinished = false

        val elapsed = System.currentTimeMillis() - startTime
        android.util.Log.i("SnifferExtractor", "[getUrl] === WEBVIEW COMPLETE === elapsed=${elapsed}ms")
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
                
                
                // === LINK PRIORITIZATION ===
                // 1. Filter out truncated/invalid links
                val validLinks = result.foundLinks.filter { !isUrlTruncated(it.url) }
                
                // 2. Sort links: Master M3U8 > M3U8 > MP4 > Others
                val sortedLinks = validLinks.sortedWith(compareByDescending { source ->
                    val u = source.url.lowercase()
                    when {
                        u.contains("master.m3u8") -> 100
                        u.contains(".m3u8") -> 50
                        u.contains(".mp4") -> 10
                        else -> 1
                    }
                })
                
                android.util.Log.i("SnifferExtractor", "[getUrl] Processing ${sortedLinks.size} valid links (sorted)")
                sortedLinks.forEach { android.util.Log.d("SnifferExtractor", " > Candidate: ${it.url}") }

                sortedLinks.firstOrNull()?.let { source ->
                    android.util.Log.i("SnifferExtractor", "[getUrl] Processing first sorted link: ${source.url.take(80)}")
                    
                    if (isFinished) {
                        android.util.Log.d("SnifferExtractor", "[getUrl] Link skipped (already finished)")
                        return@let
                    }
                    
                    val url = source.url
                    android.util.Log.i("SnifferExtractor", "[getUrl] Checking URL: ${url.take(100)}")
                    
                    if (isUrlTruncated(url)) {
                        android.util.Log.w("SnifferExtractor", "[getUrl] URL truncated: $url reason: ${getTruncationReason(url)}")
                        ProviderLogger.w(TAG, "getUrl", "URL appears truncated, skipping",
                            "url" to url,
                            "reason" to getTruncationReason(url))
                        return@let
                    }
                    android.util.Log.i("SnifferExtractor", "[getUrl] URL passed truncation check")

                    val linkType = when {
                        url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }
                    
                    var displaySourceName = name
                    if (url.contains("shahid.net", ignoreCase = true) && linkType == ExtractorLinkType.DASH) {
                        displaySourceName = "$name (DRM Protected)"
                    }
                    
                    android.util.Log.i("SnifferExtractor", "[getUrl] Link type: ${linkType.name}")
                    
                    // STOP after first valid link
                    isFinished = true
                    android.util.Log.i("SnifferExtractor", "[getUrl] Set isFinished=true") 
                    
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
                    
                    val mergedCookies = mutableMapOf<String, String>()
                    webViewCookies?.split(";")?.forEach { cookie ->
                        val trimmed = cookie.trim()
                        if (trimmed.isNotBlank()) {
                             mergedCookies[trimmed.substringBefore("=")] = trimmed.substringAfter("=", "")
                        }
                    }
                    for ((key, value) in SessionProvider.getCookies()) {
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
                        android.util.Log.i("SnifferExtractor", "[getUrl] Attempting M3U8 quality extraction")
                        ProviderLogger.i(TAG, "getUrl", "Attempting M3U8 quality extraction", "url" to source.url)
                        qualityLinks = extractM3u8Qualities(source.url, finalHeaders, embedUrl, name)
                    }
                    
                    if (!qualityLinks.isNullOrEmpty()) {
                         android.util.Log.i("SnifferExtractor", "[getUrl] Extracted ${qualityLinks.size} qualities from M3U8")
                         ProviderLogger.i(TAG, "getUrl", "Extracted ${qualityLinks.size} qualities from M3U8")
                          for (qLink in qualityLinks) {
                               android.util.Log.i("SnifferExtractor", "[getUrl] Invoking callback with quality link: ${qLink.url.take(80)}")
                               callback(qLink)
                               callbackCount++
                               android.util.Log.i("SnifferExtractor", "[getUrl] Quality callback invoked, count=$callbackCount")
                          }
                    } else {
                        // Fallback: Return original link
                        android.util.Log.i("SnifferExtractor", "[getUrl] PREPARING CALLBACK - URL: ${source.url.take(100)}")
                        android.util.Log.i("SnifferExtractor", "[getUrl] Headers: ${finalHeaders.keys.joinToString()}")
                         callback(
                            newExtractorLink(
                                source = displaySourceName,
                                name = "$displaySourceName ${source.qualityLabel}",
                                url = source.url,
                                type = linkType
                            ) {
                                this.referer = embedUrl
                                this.quality = qualityValue
                                this.headers = finalHeaders
                            }
                        )
                        
                        callbackCount++
                        android.util.Log.i("SnifferExtractor", "[getUrl] CALLBACK INVOKED - count=$callbackCount")
                    }
                    
                    android.util.Log.i("SnifferExtractor", "[getUrl] === LINK PROCESSING COMPLETE === callbacks=$callbackCount")
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

    /**
     * Build JavaScript code to find and click element based on SnifferSelector.
     * The script finds element by CSS selector, validates optional attribute/regex,
     * and simulates a click event.
     */
    private fun buildClickJavaScript(selector: SnifferSelector): String {
        val sb = StringBuilder()
        sb.append("(function() {")
        sb.append("  var element = document.querySelector('${escapeJsString(selector.query)}');")
        sb.append("  if (!element) {")
        sb.append("    return 'ERROR: Element not found for selector: ${escapeJsString(selector.query)}';")
        sb.append("  }")
        
        // Validate attribute if specified
        if (selector.attr != null) {
            sb.append("  var attrValue = element.getAttribute('${escapeJsString(selector.attr)}');")
            sb.append("  if (!attrValue) {")
            sb.append("    return 'ERROR: Attribute ${escapeJsString(selector.attr)} not found';")
            sb.append("  }")
            
            // Validate regex if specified
            if (selector.regex != null) {
                val escapedRegex = escapeJsString(selector.regex)
                sb.append("  var regex = new RegExp('$escapedRegex');")
                sb.append("  if (!regex.test(attrValue)) {")
                sb.append("    return 'ERROR: Attribute value does not match regex. Value: ' + attrValue;")
                sb.append("  }")
            }
            
            sb.append("  console.log('[SnifferSelector] Found element with ${selector.attr}=' + attrValue);")
        } else {
            sb.append("  console.log('[SnifferSelector] Found element');")
        }
        
        // Simulate click
        sb.append("  var clickEvent = new MouseEvent('click', {")
        sb.append("    bubbles: true,")
        sb.append("    cancelable: true,")
        sb.append("    view: window")
        sb.append("  });")
        sb.append("  element.dispatchEvent(clickEvent);")
        sb.append("  console.log('[SnifferSelector] Click event dispatched');")
        sb.append("  return 'SUCCESS: Clicked element';")
        sb.append("})();")
        
        return sb.toString()
    }
    
    /**
     * Escape string for use in JavaScript code.
     */
    private fun escapeJsString(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
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
            
            // LOGGING: Print first 500 chars of M3U8 to debug structure
            ProviderLogger.d(TAG, "extractM3u8Qualities", "M3U8 Content (First 500 chars)", 
                "content" to response.take(500))
            
            if (!response.contains("#EXTM3U")) {
                 ProviderLogger.e(TAG, "extractM3u8Qualities", "Invalid M3U8: Missing #EXTM3U header")
                 return null
            }

            val links = mutableListOf<ExtractorLink>()
            // Split by lines and process
            val lines = response.lines()
            
            lines.forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                // Check for stream info 
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    // Extract resolution
                    // RESOLUTION=1920x1080
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val width = resMatch?.groupValues?.get(1)?.toIntOrNull()
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
                            val qualityName = if (height != null) "${height}p" else if (bandwidth != null) "${bandwidth / 1000}kbps" else "Auto"
                            
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
            if (links.isEmpty()) {
                 ProviderLogger.w(TAG, "extractM3u8Qualities", "Parsed M3U8 but found 0 attributes", "lines" to lines.size)
                 null 
            } else {
                 links
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "extractM3u8Qualities", "Failed to extract M3U8", e)
            null
        }
    }
}
