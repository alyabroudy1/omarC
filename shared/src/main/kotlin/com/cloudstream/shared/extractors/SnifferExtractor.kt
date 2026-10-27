package com.cloudstream.shared.extractors

import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.network.IpPinnedUrl
import com.cloudstream.shared.session.SessionProvider
import com.cloudstream.shared.webview.CapturedLinkData
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.VideoSnifferEngine
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
 * 2. Runs VideoSnifferEngine in FULLSCREEN mode to sniff video URLs
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
    
    // VideoSnifferEngine instance - will be set by the provider
    var videoSnifferEngine: VideoSnifferEngine? = null
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
            
        if (embedUrl.isBlank() || embedUrl == "https://" || embedUrl == "http://") {
            android.util.Log.e("SnifferExtractor", "Invalid or blank embed URL detected: '$embedUrl'. Aborting.")
            ProviderLogger.e(TAG, "getUrl", "Invalid embed URL: $embedUrl")
            return
        }
        
        // Get or create VideoSnifferEngine
        val engine = videoSnifferEngine ?: run {
            val activity = ActivityProvider.currentActivity
            if (activity == null) {
                android.util.Log.e("SnifferExtractor", "No Activity available for VideoSnifferEngine")
                ProviderLogger.e(TAG, "getUrl", "No Activity available for VideoSnifferEngine")
                return
            }
            VideoSnifferEngine { ActivityProvider.currentActivity }
        }
        
        // ── UA Resolution ──
        // Use Android's real WebView UA directly. Both the CF solve WebView and this
        // sniffer WebView are Android WebViews on the same device — same default UA.
        // This avoids any class-loader isolation issues with WebConfig/SessionProvider.
        // Cookies are already in the system CookieManager (injected by ProviderHttpService.updateCookies).
        val snifferUserAgent = try {
            val ctx = ActivityProvider.currentActivity
            if (ctx != null) {
                android.webkit.WebSettings.getDefaultUserAgent(ctx)
                    .replace("; wv)", ")")  // Strip WebView marker, same as WebConfig
            } else {
                SessionProvider.getUserAgent()
            }
        } catch (e: Exception) {
            SessionProvider.getUserAgent()
        }
        
        ProviderLogger.d(TAG, "getUrl", "Sniffer UA resolved from WebSettings",
            "uaHash" to snifferUserAgent.hashCode())
        
        android.util.Log.i("SnifferExtractor", "[getUrl] === STARTING WEBVIEW SNIFF ===")
        android.util.Log.i("SnifferExtractor", "[getUrl] Target URL: ${embedUrl.take(80)}")
        android.util.Log.i("SnifferExtractor", "[getUrl] UA hash: ${snifferUserAgent.hashCode()}")
        ProviderLogger.d(TAG, "getUrl", "Starting VideoSnifferEngine sniffing (Visible)")
        
        // Build pre-sniff JavaScript if selector is provided
        val preSniffJs = selector?.let { buildClickJavaScript(it) }
        
        if (selector != null) {
            ProviderLogger.i(TAG, "getUrl", "Selector mode enabled", 
                "query" to selector.query,
                "attr" to (selector.attr ?: "null"),
                "regex" to (selector.regex ?: "null"))
        }
        
        val startTime = System.currentTimeMillis()
        val result = engine.runSession(
            url = embedUrl,
            mode = Mode.FULLSCREEN,
            userAgent = snifferUserAgent,
            exitCondition = ExitCondition.VideoFound(minCount = 1),
            timeout = VideoSnifferEngine.SNIFFER_PLAYER_TIMEOUT_MS,
            delayMs = 2000, // Wait 2s for page to fully load before starting detection
            preSniffJavaScript = preSniffJs,
            referer = embedReferer // Pass referer for embed servers (e.g., https://laroza.cfd/)
        )
        
        var callbackCount = 0
        var totalFound = 0

        val elapsed = System.currentTimeMillis() - startTime
        android.util.Log.i("SnifferExtractor", "[getUrl] === WEBVIEW COMPLETE === elapsed=${elapsed}ms")
        android.util.Log.i("SnifferExtractor", "[getUrl] VideoSnifferEngine returned: ${result.javaClass.simpleName}")
        
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
                
                
                // The WebView already holds the authorized URLs and the request context that
                // produced them. Planning is an offline decision now: no manifest re-fetch, and no
                // discarding a valid capture because an optional quality probe failed.
                val validLinks = result.foundLinks.filter { !isUrlTruncated(it.url) }

                // An IP-pinned token carrying an IPv6 literal is dead on arrival: the media edge is
                // IPv4-only, so no client can ever present the address the token was minted for and
                // every request 403s. Say so instead of handing the player links that cannot work —
                // the symptom is otherwise indistinguishable from a broken extractor.
                if (validLinks.any { IpPinnedUrl.containsIpv6Literal(it.url) }) {
                    ProviderLogger.e(TAG, "getUrl",
                        "Captured links are IP-pinned to an IPv6 address and cannot be played. " +
                            "The token-minting request escaped the IPv4 pin — see pinToIpv4.")
                }

                val plan = SnifferCapturePlanner.plan(validLinks)
                plan.rejected.forEach { rejected ->
                    android.util.Log.d("SnifferExtractor",
                        "[getUrl] Rejected capture (${rejected.reason}): ${rejected.url.take(100)}")
                    ProviderLogger.d(TAG, "getUrl", "Rejected capture",
                        "url" to rejected.url.take(100), "reason" to rejected.reason)
                }

                android.util.Log.i("SnifferExtractor",
                    "[getUrl] Processing ${plan.links.size} playable captures (${plan.rejected.size} rejected)")
                plan.links.forEach { planned ->
                    android.util.Log.d("SnifferExtractor",
                        " > Candidate: ${planned.capture.url} [${planned.kind}, ${planned.qualityLabel}]")
                }

                for (planned in plan.links) {
                    val source = planned.capture
                    val url = source.url
                    val linkType = planned.kind.toExtractorLinkType()
                    val finalHeaders = buildRequestHeaders(source, embedUrl, snifferUserAgent)
                    val referer = finalHeaders.entries
                        .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                        ?.value
                        ?: (extractOrigin(embedUrl) + "/")

                    android.util.Log.i("SnifferExtractor",
                        "[getUrl] Delivering captured ${planned.kind}: ${url.take(100)}")
                    android.util.Log.i("SnifferExtractor",
                        "[getUrl] Headers: ${finalHeaders.keys.joinToString()}")

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name ${planned.qualityLabel}",
                            url = url,
                            type = linkType
                        ) {
                            this.referer = referer
                            this.quality = planned.quality ?: Qualities.Unknown.value
                            this.headers = finalHeaders
                        }
                    )

                    callbackCount++
                    android.util.Log.i("SnifferExtractor",
                        "[getUrl] CALLBACK INVOKED - count=$callbackCount")
                }
            }
            is WebViewResult.Timeout -> {
                android.util.Log.w("SnifferExtractor", "[getUrl] TIMEOUT! Last URL: ${result.lastUrl.take(60)}")
                ProviderLogger.w(TAG, "getUrl", "VideoSnifferEngine timed out", "lastUrl" to result.lastUrl.take(60))
            }
            is WebViewResult.PlayingInWebView -> {
                android.util.Log.i("SnifferExtractor", "[getUrl] Video playing in WebView (DRM or unsniffable). Dialog remains open as player.")
                ProviderLogger.i(TAG, "getUrl", "Playing in WebView (dialog stays open as player)")
                
                // Wait indefinitely while the user watches the video in the WebView dialog
                while (result.dialog.isShowing) {
                    kotlinx.coroutines.delay(500)
                }
                
                // If the user dismisses the dialog (Back press), we should cancel the entire loop. 
                // They are trying to exit the player, not move to the next server.
                throw kotlinx.coroutines.CancellationException("User exited WebView player")
            }
            is WebViewResult.Error -> {
                android.util.Log.e("SnifferExtractor", "[getUrl] ERROR: ${result.reason}")
                ProviderLogger.e(TAG, "getUrl", "VideoSnifferEngine error: ${result.reason}")
                if (result.reason.contains("cancel", ignoreCase = true)) {
                    throw kotlinx.coroutines.CancellationException("User cancelled sniffing")
                }
            }
            is WebViewResult.Cancelled -> {
                android.util.Log.i("SnifferExtractor", "[getUrl] CANCELLED: ${result.reason}")
                ProviderLogger.i(TAG, "getUrl", "VideoSnifferEngine cancelled: ${result.reason}")
                throw kotlinx.coroutines.CancellationException("User cancelled sniffing")
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
        return lowerUrl.startsWith("data:") ||  // base64 blob placeholders (e.g. savefiles preload)
               lowerUrl.contains("/ping.gif") || 
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

    private fun SnifferStreamKind.toExtractorLinkType(): ExtractorLinkType = when (this) {
        SnifferStreamKind.HLS_MASTER,
        SnifferStreamKind.HLS_VARIANT,
        SnifferStreamKind.HLS_MANIFEST -> ExtractorLinkType.M3U8
        SnifferStreamKind.PROGRESSIVE,
        SnifferStreamKind.VIDEO -> ExtractorLinkType.VIDEO
    }

    /**
     * Builds the playback request from the capture's own headers, preferring what the page sent over
     * synthetic defaults. The WebView's client hints are part of the token-minting context and must
     * not be replaced with a Chrome string after the fact.
     */
    private fun buildRequestHeaders(
        source: CapturedLinkData,
        embedUrl: String,
        snifferUserAgent: String
    ): Map<String, String> {
        val headers = SnifferRequestHeaders.sanitize(source.headers).toMutableMap()

        fun putIfAbsent(name: String, value: String) {
            if (headers.keys.none { it.equals(name, ignoreCase = true) }) {
                headers[name] = value
            }
        }

        // CDN servers validate the base-domain Referer, not the full video_player URL.
        val origin = extractOrigin(embedUrl)
        putIfAbsent("Origin", origin)
        putIfAbsent("Referer", "$origin/")
        putIfAbsent("User-Agent", snifferUserAgent)
        cookieHeaderFor(source.url)?.let { putIfAbsent("Cookie", it) }
        putIfAbsent("Accept", "*/*")
        putIfAbsent("sec-ch-ua", com.cloudstream.shared.util.WebConfig.buildSecChUa(snifferUserAgent))
        putIfAbsent("sec-ch-ua-mobile", "?1")
        putIfAbsent("sec-ch-ua-platform", "\"Android\"")
        putIfAbsent("Sec-Fetch-Dest", "empty")
        putIfAbsent("Sec-Fetch-Mode", "cors")
        putIfAbsent("Sec-Fetch-Site", "cross-site")

        return headers
    }

    private fun cookieHeaderFor(url: String): String? {
        val merged = linkedMapOf<String, String>()

        try {
            android.webkit.CookieManager.getInstance().getCookie(url)
                ?.split(";")
                ?.forEach { cookie ->
                    val trimmed = cookie.trim()
                    if (trimmed.isNotBlank()) {
                        merged[trimmed.substringBefore("=")] = trimmed.substringAfter("=", "")
                    }
                }
        } catch (_: Exception) {
            // CookieManager is unavailable in some test/JVM contexts; SessionProvider may still help.
        }

        for ((key, value) in SessionProvider.getCookies()) {
            merged[key] = value
        }

        return if (merged.isEmpty()) null
        else merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
