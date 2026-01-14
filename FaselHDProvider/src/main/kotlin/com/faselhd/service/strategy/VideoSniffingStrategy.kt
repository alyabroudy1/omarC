package com.faselhd.service.strategy

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.faselhd.service.ProviderLogger
import com.faselhd.service.ProviderLogger.TAG_VIDEO_SNIFFER
import com.faselhd.service.cloudflare.CloudflareDetector
import com.faselhd.service.cookie.CookieLifecycleManager
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Video sniffing strategy extending WebViewStrategy.
 * 
 * ## Inheritance:
 * Inherits all cookie/session management from WebViewStrategy:
 * - Cookie extraction and storage
 * - Cloudflare challenge detection and solving
 * - WebView lifecycle management
 * 
 * ## Additional Features:
 * - Monitors network requests for video URLs
 * - JS injection for player interaction (auto-play, skip ads)
 * - JWPlayer source extraction
 * - Configurable URL patterns
 * 
 * ## JS Actions (Adopted from FaselSniffer.kt):
 * - skipAds(): Speed up short videos, click skip buttons
 * - autoPlay(): Mute and auto-play videos, click play buttons
 * - extractSources(): Extract JWPlayer sources via bridge
 * 
 * @param context Android context
 * @param cookieManager Cookie lifecycle manager
 * @param videoPatterns Regex patterns to match video URLs
 * @param jsActions Video JS actions configuration
 */
class VideoSniffingStrategy(
    context: Context,
    cookieManager: CookieLifecycleManager,
    cfDetector: CloudflareDetector = CloudflareDetector(),
    private val videoPatterns: List<Regex> = DEFAULT_VIDEO_PATTERNS,
    private val jsActions: VideoJsActions = VideoJsActions.DEFAULT
) : WebViewStrategy(context, cookieManager, cfDetector) {
    
    override val name: String = "VideoSniffer"
    override val timeout: Long = 35_000
    
    /** Captured video sources */
    private val capturedSources = CopyOnWriteArrayList<VideoSource>()
    
    /** Deferred for JS bridge results */
    private var sourcesDeferred: CompletableDeferred<List<VideoSource>>? = null
    
    companion object {
        /** Default video URL patterns */
        val DEFAULT_VIDEO_PATTERNS = listOf(
            Regex("""\.m3u8(\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""\.mp4(\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""\.mkv(\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""\.webm(\?|$)""", RegexOption.IGNORE_CASE)
        )
        
        /** Minimum video URL length to filter noise */
        const val MIN_VIDEO_URL_LENGTH = 50
    }
    
    override val jsInjection: String
        get() = jsActions.buildScript()
    
    /**
     * Sniffs video URLs from a page.
     * 
     * @param url The page URL to sniff
     * @param userAgent User-Agent to use
     * @param cookies Existing cookies to inject
     * @return List of captured video sources
     */
    suspend fun sniff(
        url: String,
        userAgent: String,
        cookies: Map<String, String> = emptyMap()
    ): List<VideoSource> {
        capturedSources.clear()
        sourcesDeferred = CompletableDeferred()
        
        ProviderLogger.i(TAG_VIDEO_SNIFFER, "sniff", "Starting video sniff",
            "url" to url.take(80),
            "patternCount" to videoPatterns.size
        )
        
        val request = StrategyRequest(
            url = url,
            userAgent = userAgent,
            cookies = cookies
        )
        
        // Execute parent's WebView logic (handles CF, cookies, etc.)
        val response = execute(request)
        
        // Wait for JS bridge or use captured network sources
        val jsSources = withTimeoutOrNull(5_000) {
            sourcesDeferred?.await() ?: emptyList()
        } ?: emptyList()
        
        // Combine JS sources and network-captured sources
        val allSources = (jsSources + capturedSources).distinctBy { it.url }
        
        ProviderLogger.i(TAG_VIDEO_SNIFFER, "sniff", "Sniff completed",
            "jsSources" to jsSources.size,
            "networkSources" to capturedSources.size,
            "totalUnique" to allSources.size,
            "responseSuccess" to response.success
        )
        
        return allSources
    }
    
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun createWebView(request: StrategyRequest): WebView {
        return super.createWebView(request).apply {
            // Add JS bridge for source extraction
            addJavascriptInterface(SnifferBridge(), "SnifferBridge")
            
            ProviderLogger.d(TAG_VIDEO_SNIFFER, "createWebView", "JS bridge added")
            
            // Override to monitor network requests
            webViewClient = createSnifferWebViewClient(request)
        }
    }
    
    /**
     * Creates a WebViewClient that monitors network requests for video URLs.
     */
    private fun createSnifferWebViewClient(request: StrategyRequest): WebViewClient {
        return object : WebViewClient() {
            
            override fun shouldInterceptRequest(
                view: WebView?,
                webRequest: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = webRequest?.url?.toString() ?: return null
                
                // Check if URL matches video patterns
                if (isVideoUrl(url)) {
                    val headers = webRequest.requestHeaders ?: emptyMap()
                    captureVideoUrl(url, headers)
                }
                
                return null
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                ProviderLogger.d(TAG_VIDEO_SNIFFER, "onPageFinished", "Page loaded",
                    "url" to (url?.take(80) ?: "null"),
                    "capturedSoFar" to capturedSources.size
                )
                
                // Inject JS for player extraction
                jsInjection?.let { js ->
                    view?.evaluateJavascript(js) { result ->
                        ProviderLogger.d(TAG_VIDEO_SNIFFER, "jsInjection", "JS executed",
                            "resultLength" to (result?.length ?: 0)
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Checks if a URL matches video patterns.
     */
    private fun isVideoUrl(url: String): Boolean {
        if (url.length < MIN_VIDEO_URL_LENGTH) return false
        return videoPatterns.any { it.containsMatchIn(url) }
    }
    
    /**
     * Captures a video URL with its headers.
     */
    private fun captureVideoUrl(url: String, headers: Map<String, String>) {
        // Determine quality from URL
        val quality = detectQuality(url)
        val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        
        val source = VideoSource(
            url = url,
            label = quality,
            headers = headers,
            type = type
        )
        
        capturedSources.add(source)
        
        ProviderLogger.i(TAG_VIDEO_SNIFFER, "captureVideoUrl", "Video URL captured",
            "url" to url.take(80),
            "quality" to quality,
            "type" to type.name,
            "totalCaptured" to capturedSources.size
        )
    }
    
    /**
     * Detects quality label from URL.
     */
    private fun detectQuality(url: String): String {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("1080") -> "1080p"
            lowerUrl.contains("720") -> "720p"
            lowerUrl.contains("480") -> "480p"
            lowerUrl.contains("360") -> "360p"
            lowerUrl.contains("master") -> "Auto"
            else -> "Unknown"
        }
    }
    
    override fun onJsResult(result: String?) {
        // Handle sniffer_done signal if passed via URL
        if (result != null && result.contains("sniffer_done")) {
            ProviderLogger.d(TAG_VIDEO_SNIFFER, "onJsResult", "Sniffer done signal received")
        }
    }
    
    /**
     * JavaScript bridge for receiving extracted sources.
     */
    inner class SnifferBridge {
        
        @JavascriptInterface
        fun onSourcesFound(json: String) {
            ProviderLogger.i(TAG_VIDEO_SNIFFER, "SnifferBridge.onSourcesFound", "Sources received from JS",
                "jsonLength" to json.length
            )
            
            try {
                val sources = parseSourcesJson(json)
                if (sources.isNotEmpty()) {
                    sourcesDeferred?.complete(sources)
                }
            } catch (e: Exception) {
                ProviderLogger.e(TAG_VIDEO_SNIFFER, "SnifferBridge.onSourcesFound", "Failed to parse sources", e)
            }
        }
        
        @JavascriptInterface
        fun log(message: String) {
            ProviderLogger.d(TAG_VIDEO_SNIFFER, "JS", message)
        }
    }
    
    /**
     * Parses JWPlayer sources JSON.
     * Format: [{"url":"...","label":"...","type":"..."},...]
     */
    private fun parseSourcesJson(json: String): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()
        
        try {
            // Simple JSON parsing without dependencies
            val cleanJson = json.trim()
                .removePrefix("[")
                .removeSuffix("]")
            
            if (cleanJson.isBlank()) return sources
            
            val objects = cleanJson.split("},").map { 
                var obj = it.trim()
                if (!obj.startsWith("{")) obj = "{$obj"
                if (!obj.endsWith("}")) obj = "$obj}"
                obj
            }
            
            objects.forEach { obj ->
                val urlMatch = Regex(""""(?:url|file)"\s*:\s*"([^"]+)"""").find(obj)
                val labelMatch = Regex(""""label"\s*:\s*"([^"]+)"""").find(obj)
                
                val url = urlMatch?.groupValues?.get(1)?.replace("\\/", "/")
                val label = labelMatch?.groupValues?.get(1) ?: "Auto"
                
                if (url != null && url.length > MIN_VIDEO_URL_LENGTH) {
                    val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    sources.add(VideoSource(url, label, emptyMap(), type))
                    
                    ProviderLogger.d(TAG_VIDEO_SNIFFER, "parseSourcesJson", "Source parsed",
                        "label" to label,
                        "urlLength" to url.length
                    )
                }
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG_VIDEO_SNIFFER, "parseSourcesJson", "Parse error", e)
        }
        
        return sources
    }
}

/**
 * Video source captured during sniffing.
 */
data class VideoSource(
    val url: String,
    val label: String,
    val headers: Map<String, String>,
    val type: ExtractorLinkType
)

/**
 * Configuration for video JS actions.
 * 
 * Adopted from FaselSniffer.kt with configurable options.
 */
data class VideoJsActions(
    val autoPlay: Boolean = true,
    val skipAds: Boolean = true,
    val muteVideo: Boolean = true,
    val extractJwPlayer: Boolean = true,
    val customSelectors: List<String> = emptyList()
) {
    companion object {
        val DEFAULT = VideoJsActions()
    }
    
    /**
     * Builds the complete JS script for injection.
     */
    fun buildScript(): String = """
        (function() {
            console.log('[VideoSniffer] Script initialized');
            var sourcesSent = false;
            
            // ===== AD SKIP (from FaselSniffer) =====
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
                } catch(e) {
                    if (typeof SnifferBridge !== 'undefined') SnifferBridge.log('skipAds error: ' + e);
                }
            }
            
            // ===== AUTO PLAY (from FaselSniffer) =====
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
                            v.muted = true;
                            v.play();
                        }
                    });
                } catch(e) {
                    if (typeof SnifferBridge !== 'undefined') SnifferBridge.log('autoPlay error: ' + e);
                }
            }
            
            // ===== EXTRACT JWPLAYER SOURCES =====
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
                                
                                if (sources.length > 0 && typeof SnifferBridge !== 'undefined') {
                                    sourcesSent = true;
                                    SnifferBridge.onSourcesFound(JSON.stringify(sources));
                                    SnifferBridge.log('Found ' + sources.length + ' sources');
                                }
                            }
                        }
                    }
                } catch(e) {
                    if (typeof SnifferBridge !== 'undefined') SnifferBridge.log('extractSources error: ' + e);
                }
            }
            
            // Run immediately
            ${if (autoPlay) "autoPlay();" else ""}
            ${if (skipAds) "skipAds();" else ""}
            
            // Run periodically
            setInterval(function() {
                ${if (autoPlay) "autoPlay();" else ""}
                ${if (skipAds) "skipAds();" else ""}
                ${if (extractJwPlayer) "extractSources();" else ""}
            }, 1000);
            
            if (typeof SnifferBridge !== 'undefined') SnifferBridge.log('Script setup complete');
        })();
    """.trimIndent()
}
