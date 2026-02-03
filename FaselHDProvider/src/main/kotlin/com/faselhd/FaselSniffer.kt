package com.faselhd

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import okhttp3.Request

/**
 * FaselHD Video Extractor using WebViewResolver with JWPlayer source extraction.
 * Extracts all quality options (Auto, 1080p, 720p, etc.) from the JWPlayer sources array.
 */
open class FaselSniffer : ExtractorApi() {
    override val name = "FaselHD"
    override val mainUrl: String
        get() = FaselState.currentDomain
    override val requiresReferer = false

    companion object {
        private const val TAG = "FaselSniffer"
        private const val MIN_VIDEO_URL_LENGTH = 50
        
        // Regex to match video stream URLs (m3u8, mp4, etc.)
        // Note: Explicitly excludes .ts, .js, .css, .png etc. by only allowing specific video extensions.
        // This matches the efficiency of the reference SnifferActivity.java implementation.
        private val videoUrlRegex = Regex(
            """\.(m3u8|mp4|mkv|avi|webm|mov)(\?|${'$'})""",
            RegexOption.IGNORE_CASE
        )
        
        /**
         * JavaScript to:
         * 1. Auto-click play buttons and skip ads
         * 2. Wait for JWPlayer to load
         * 3. Extract sources array with quality labels
         * 4. Return sources via SnifferAndroid.onSourcesFound()
         */
        private val extractSourcesScript = """
            (function() {
                console.log('[FaselSniffer] Source extraction script initialized');
                var sourcesSent = false;
                
                // Ad-skip: speed up short videos, click skip buttons
                function skipAds() {
                    try {
                        // Speed up and skip short videos (ads)
                        document.querySelectorAll('video').forEach(function(v) {
                            if (v.duration > 0 && v.duration < 30) {
                                v.playbackRate = 16;
                                v.muted = true;
                                v.currentTime = v.duration - 0.5;
                            }
                        });
                        
                        // Click skip buttons
                        var skipSelectors = ['.jw-skip', '.skip-button', '.skip-ad', '[class*="skip"]', '[class*="close"]'];
                        skipSelectors.forEach(function(sel) {
                            var btn = document.querySelector(sel);
                            if (btn && btn.offsetParent) btn.click();
                        });
                        
                        // JWPlayer ad skip
                        if (typeof jwplayer !== 'undefined') {
                            var player = jwplayer();
                            if (player && player.getDuration && player.getDuration() > 0 && player.getDuration() < 30) {
                                player.seek(player.getDuration() - 0.5);
                                player.setMute(true);
                            }
                        }
                    } catch(e) {}
                }
                
                // Auto-click play buttons
                function autoPlay() {
                    try {
                        if (typeof jwplayer !== 'undefined') {
                            var player = jwplayer();
                            if (player) {
                                if (player.setMute) player.setMute(true);
                                if (player.play) player.play();
                            }
                        }
                        
                        // Click play buttons
                        var playSelectors = ['.jw-icon-playback', '.jw-display-icon-container', '.vjs-big-play-button', '.play-button'];
                        playSelectors.forEach(function(sel) {
                            var btn = document.querySelector(sel);
                            if (btn && btn.offsetParent) btn.click();
                        });
                        
                        document.querySelectorAll('video').forEach(function(v) {
                            if (v.paused) {
                                v.muted = true; // Key fix: Mute before playing
                                v.play();
                            }
                        });
                    } catch(e) {}
                }
                
                // Extract JWPlayer sources
                function extractSources() {
                    if (sourcesSent) return;
                    
                    try {
                        if (typeof jwplayer !== 'undefined') {
                            var player = jwplayer();
                            if (player && player.getPlaylistItem) {
                                var item = player.getPlaylistItem();
                                if (item && item.sources && item.sources.length > 0) {
                                    var sources = [];
                                    item.sources.forEach(function(src) {
                                        if (src.file && src.file.length > 40) {
                                            sources.push({
                                                url: src.file,
                                                label: src.label || 'Auto',
                                                type: src.type || 'unknown'
                                            });
                                        }
                                    });
                                    
                                    if (sources.length > 0) {
                                        sourcesSent = true;
                                        console.log('[FaselSniffer] Found ' + sources.length + ' sources');
                                        // Pass data in URL - use current domain dynamically
                                        var json = JSON.stringify(sources);
                                        window.location.href = window.location.origin + "/sniffer_done?sources=" + encodeURIComponent(json); 
                                        return json;
                                    }
                                }
                            }
                        }
                    } catch(e) {
                        console.log('[FaselSniffer] Extract error: ' + e);
                    }
                    return null;
                }
                
                // Run immediately and periodically
                autoPlay();
                skipAds();
                
                setInterval(function() {
                    autoPlay();
                    skipAds();
                    extractSources();
                }, 1000);
                
                // Return sources if available
                return extractSources();
            })();
        """.trimIndent()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i(TAG, "Starting JWPlayer source extraction for: $url")
        
        var sourcesJson: String? = null
        var capturedVideoUrls = mutableListOf<Pair<String, Map<String, String>>>()

        // 0. Inject saved cookies into WebView CookieManager
        try {
            FaselState.init()
            val savedHeaders = FaselState.headers
            Log.i(TAG, "[getUrl] FaselState.headers at start: $savedHeaders")
            val cookies = savedHeaders["cookie"] ?: savedHeaders["Cookie"]
            val userAgent = savedHeaders["User-Agent"] ?: savedHeaders["user-agent"]
            Log.i(TAG, "[getUrl] Extracted cookies: $cookies")
            Log.i(TAG, "[getUrl] Extracted userAgent: $userAgent")
            
            if (!cookies.isNullOrEmpty()) {
                Log.i(TAG, "Injecting saved cookies into WebView CookieManager")
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                val domain = FaselState.currentDomain
                
                // Cookies format: "name=value; name2=value2"
                cookies?.let { c ->
                    c.split(";").forEach { cookie ->
                        cookieManager.setCookie(domain, cookie.trim())
                    }
                }
                cookieManager.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject cookies: ${e.message}")
        }

        // Case-insensitive lookup for User-Agent
        val resolverUserAgent = FaselState.headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value 
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        Log.i(TAG, "[getUrl] Resolver userAgent: $resolverUserAgent")
        // Build dynamic regex patterns based on current domain
        val domainHost = FaselState.getDomainHost()
        val snifferDoneRegex = Regex("""https://(www\.)?${Regex.escape(domainHost.removePrefix("www."))}/sniffer_done""")
        val siteRegex = Regex("""https://(www\.)?${Regex.escape(domainHost.removePrefix("www."))}(s)?/.*""")
        
        val resolver = ConfigurableWebViewResolver(
            interceptUrl = videoUrlRegex,
            additionalUrls = listOf(videoUrlRegex, snifferDoneRegex, siteRegex),
            userAgent = resolverUserAgent,
            useOkhttp = false,
            script = extractSourcesScript,
            scriptCallback = { result ->
                if (result != "null" && result.startsWith("[")) {
                     // Backup: still capture callback result if available
                    sourcesJson = result
                }
            },
            timeout = 35_000L,
            blockNonHttp = true,
            allowThirdPartyCookies = true
        )

        try {
            var masterPlaylistUrl: String? = null
            var masterHeaders: Map<String, String> = emptyMap()
            
            val (request, additionalRequests) = resolver.resolveUsingWebView(
                url = url
            ) { capturedRequest: Request ->
                val reqUrl = capturedRequest.url.toString()
                val headers = capturedRequest.headers.toMap()

                // CAPTURE HEADERS from any request to the main site that has cookies
                if (reqUrl.contains("fasel", ignoreCase = true) && headers.keys.any { key -> key.equals("cookie", true) }) {
                   // Update Hybrid State
                   FaselState.updateHeaders(headers)
                   
                   // Also update masterHeaders if it's empty, just in case
                   if (masterHeaders.isEmpty()) {
                       masterHeaders = headers
                   }
                }

                // 1. Check for JS completion signal with data
                if (reqUrl.contains("/sniffer_done")) {
                    Log.i(TAG, "🚀 JS extraction signal received!")
                    android.net.Uri.parse(reqUrl).getQueryParameter("sources")?.let { sources ->
                        if (sources.isNotEmpty()) {
                            Log.i(TAG, "📦 Retrieved sources from URL")
                            sourcesJson = sources
                        }
                    }
                    return@resolveUsingWebView true // STOP immediately
                }
                
                // 2. Check for Master/Video URLs
                if (videoUrlRegex.containsMatchIn(reqUrl) && reqUrl.length >= MIN_VIDEO_URL_LENGTH) {
                    
                    // Check if this looks like a MASTER playlist (no quality indicators)
                    val qualityIndicators = listOf("hd1080", "hd720", "hd480", "1080p", "720p", "480p", "360p", "_hd", "_sd")
                    val isVariantPlaylist = qualityIndicators.any { reqUrl.lowercase().contains(it) }
                    
                    if (!isVariantPlaylist && reqUrl.contains(".m3u8")) {
                        Log.i(TAG, "✅ Master playlist detected: $reqUrl")
                        masterPlaylistUrl = reqUrl
                        masterHeaders = capturedRequest.headers.toMap() // Prefer these headers for the master playlist
                        FaselState.updateHeaders(masterHeaders) // Update hybrid state
                        return@resolveUsingWebView true // STOP immediately
                    } else {
                        capturedVideoUrls.add(reqUrl to capturedRequest.headers.toMap())
                        return@resolveUsingWebView false
                    }
                }
                false
            }

            // 1. Priority: Use extracted JWPlayer sources (contains explicit labels)
            if (!sourcesJson.isNullOrEmpty()) {
                Log.i(TAG, "Using extracted JWPlayer sources JSON")
                try {
                    // Raw JSON: [{"url":"...","label":"..."},{"url":"...","label":"..."}]
                    // Remove outer brackets and parse objects
                    val cleanJson = sourcesJson!!.trim()
                        .removePrefix("\"") // Remove potential wrapping quotes from evaluation result
                        .removeSuffix("\"")
                        .replace("\\\"", "\"") // Unescape quotes if stringified
                        .removePrefix("[")
                        .removeSuffix("]")
                    
                    val objects = cleanJson.split("},{").map { 
                        var obj = it
                        if (!obj.startsWith("{")) obj = "{$obj"
                        if (!obj.endsWith("}")) obj = "$obj}"
                        obj
                    }
                    
                    var sourcesFound = false
                    objects.forEach { obj ->
                        // Match "url":"..." and "label":"..."
                        val urlMatch = Regex(""""file|url":"([^"]+)"""").find(obj)
                        val labelMatch = Regex(""""label":"([^"]+)"""").find(obj)
                        
                        var videoUrl = urlMatch?.groupValues?.get(1)?.replace("\\/", "/")
                        val label = labelMatch?.groupValues?.get(1) ?: "Auto"
                        
                        if (videoUrl != null && videoUrl.length > MIN_VIDEO_URL_LENGTH) {
                            val quality = label.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value
                            Log.i(TAG, "Extracted source: $label -> $videoUrl")
                            sourcesFound = true
                            
                            callback(
                                newExtractorLink(
                                    name,
                                    "$name - $label",
                                    videoUrl,
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer ?: url
                                    this.quality = quality
                                    
                                    // Smart Header Selection
                                    var finalHeaders = emptyMap<String, String>()
                                    
                                    // 1. Try to find actual network headers captured for this URL
                                    val captured = capturedVideoUrls.firstOrNull { it.first == videoUrl }?.second
                                    if (captured != null) {
                                        finalHeaders = captured
                                    } else {

                                        // 2. Build from FaselState/Master headers/Default
                                        val stateHeaders = FaselState.headers
                                        val fallbackUserAgent = resolver.userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                                        
                                        var baseHeaders = stateHeaders.ifEmpty { 
                                            masterHeaders.ifEmpty { 
                                                mapOf("User-Agent" to fallbackUserAgent) 
                                            } 
                                        }

                                        // Ensure User-Agent is present
                                        if (!baseHeaders.keys.any { keys -> keys.equals("user-agent", true) }) {
                                            baseHeaders = baseHeaders + mapOf("User-Agent" to fallbackUserAgent)
                                        }
                                        
                                        // 3. Cookie Safety: Don't send Fasel cookies to external CDNs
                                        val isFaselDomain = videoUrl.contains(FaselState.getDomainHost().removePrefix("www."), ignoreCase = true)
                                        if (isFaselDomain) {
                                            finalHeaders = baseHeaders
                                        } else {
                                            // Filter out cookies for external domains
                                            finalHeaders = baseHeaders.filterKeys { key -> 
                                                !key.equals("Cookie", true) && !key.equals("cookie", true)
                                            }
                                        }
                                    }
                                    
                                    // 4. Explicitly ensure Referer is set
                                    this.headers = finalHeaders + mapOf("Referer" to (referer ?: url))
                                }
                            )
                        }
                    }
                    if (sourcesFound) return // Success via JS extraction
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing sources JSON: ${e.message}")
                }
            }

            // 2. Fallback: Use captured network URLs (Master Playlist / Variants)
            // Prefer master playlist if found
            val finalUrl = masterPlaylistUrl ?: capturedVideoUrls.firstOrNull()?.first
            val finalHeaders = if (masterPlaylistUrl != null) masterHeaders else capturedVideoUrls.firstOrNull()?.second ?: emptyMap()
            
            if (finalUrl != null) {
                Log.i(TAG, "Fallback: Delivering network-captured URL: $finalUrl")
                
                callback(
                    newExtractorLink(
                        name,
                        name, // Default name if we don't know quality
                        finalUrl,
                        type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: url
                        val safeHeaders = FaselState.headers.ifEmpty { finalHeaders }
                        this.headers = safeHeaders + mapOf("Referer" to (referer ?: url))
                    }
                )
            } else {
                Log.w(TAG, "No video URLs captured for: $url")
            }

        } catch (e: Exception) {
            Log.e(TAG, "WebViewResolver error: ${e.message}")
        }
    }
}
