package com.faselhd.service.strategy

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.AcraApplication
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
 * Video sniffing strategy using WebView.
 * 
 * ## Features:
 * - Monitors network requests for video URLs
 * - JS injection for player interaction (auto-play, skip ads)
 * - JWPlayer source extraction via JS bridge
 * - Configurable URL patterns
 * 
 * ## JS Actions (Adopted from FaselSniffer.kt):
 * - skipAds(): Speed up short videos, click skip buttons
 * - autoPlay(): Mute and auto-play videos, click play buttons
 * - extractSources(): Extract JWPlayer sources via bridge
 * 
 * @param context Android context
 * @param cookieManager Cookie lifecycle manager for storing captured cookies
 * @param cfDetector Cloudflare detector
 */
class VideoSniffingStrategy(
    private val context: Context,
    private val cookieManager: CookieLifecycleManager,
    private val cfDetector: CloudflareDetector = CloudflareDetector()
) {
    
    val name: String = "VideoSniffer"
    private val timeout: Long = 35_000
    
    /** Video URL patterns to capture */
    private val videoPatterns = listOf(
        Regex("""\.m3u8(\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""\.mp4(\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""\.mkv(\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""\.webm(\?|$)""", RegexOption.IGNORE_CASE)
    )
    
    /** Captured video sources */
    private val capturedSources = CopyOnWriteArrayList<VideoSource>()
    
    /** Deferred for JS bridge results */
    private var sourcesDeferred: CompletableDeferred<List<VideoSource>>? = null
    
    companion object {
        /** Minimum video URL length to filter noise */
        const val MIN_VIDEO_URL_LENGTH = 50
    }
    
    /**
     * Sniffs video URLs from a page.
     * 
     * @param url The page URL to sniff
     * @param userAgent User-Agent to use
     * @param cookies Existing cookies to inject
     * @return List of captured video sources
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(
        url: String,
        userAgent: String,
        cookies: Map<String, String> = emptyMap()
    ): List<VideoSource> = withContext(Dispatchers.Main) {
        capturedSources.clear()
        sourcesDeferred = CompletableDeferred()
        
        ProviderLogger.i(TAG_VIDEO_SNIFFER, "sniff", "Starting video sniff",
            "url" to url.take(80),
            "patternCount" to videoPatterns.size
        )
        
        var webView: WebView? = null
        val startTime = System.currentTimeMillis()
        
        try {
            webView = createWebView(url, userAgent, cookies)
            
            // Load the page
            webView.loadUrl(url)
            
            // Wait for JS bridge results with timeout
            val jsSources = withTimeoutOrNull(timeout) {
                // Poll for sources while waiting
                var attempts = 0
                while (capturedSources.isEmpty() && sourcesDeferred?.isCompleted != true && attempts < 70) {
                    kotlinx.coroutines.delay(500)
                    attempts++
                }
                
                // Return JS sources if available, otherwise empty
                if (sourcesDeferred?.isCompleted == true) {
                    sourcesDeferred?.await() ?: emptyList()
                } else {
                    emptyList()
                }
            } ?: emptyList()
            
            // Combine JS sources and network-captured sources
            val allSources = (jsSources + capturedSources).distinctBy { it.url }
            
            val durationMs = System.currentTimeMillis() - startTime
            
            ProviderLogger.i(TAG_VIDEO_SNIFFER, "sniff", "Sniff completed",
                "jsSources" to jsSources.size,
                "networkSources" to capturedSources.size,
                "totalUnique" to allSources.size,
                "durationMs" to durationMs
            )
            
            allSources
            
        } catch (e: Exception) {
            ProviderLogger.e(TAG_VIDEO_SNIFFER, "sniff", "Sniff failed", e)
            capturedSources.toList()
        } finally {
            // Cleanup WebView on main thread
            webView?.let { wv ->
                Handler(Looper.getMainLooper()).post {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
        }
    }
    
    /**
     * Creates and configures the WebView for video sniffing.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String, userAgent: String, cookies: Map<String, String>): WebView {
        // Try to get AcraApplication context if available, otherwise use provided context
        val ctx = try { AcraApplication.context ?: context } catch(e: Exception) { context }
        
        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = userAgent
            settings.mediaPlaybackRequiresUserGesture = false
            
            // Enable third-party cookies
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            
            // Inject cookies
            if (cookies.isNotEmpty()) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookies.forEach { (key, value) ->
                    cookieManager.setCookie(url, "$key=$value")
                }
                cookieManager.flush()
                ProviderLogger.d(TAG_VIDEO_SNIFFER, "createWebView", "Cookies injected", "count" to cookies.size)
            }
            
            // Add JS bridge for source extraction
            addJavascriptInterface(SnifferBridge(), "SnifferBridge")
            
            // Set WebViewClient to monitor network requests
            webViewClient = createSnifferWebViewClient()
            
            ProviderLogger.d(TAG_VIDEO_SNIFFER, "createWebView", "WebView created",
                "userAgent" to userAgent.take(50)
            )
        }
    }
    
    /**
     * Creates a WebViewClient that monitors network requests for video URLs.
     */
    private fun createSnifferWebViewClient(): WebViewClient {
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
                view?.evaluateJavascript(buildJsScript()) { result ->
                    ProviderLogger.d(TAG_VIDEO_SNIFFER, "jsInjection", "JS executed",
                        "resultLength" to (result?.length ?: 0)
                    )
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
    
    /**
     * Builds the JS script for video extraction.
     */
    private fun buildJsScript(): String = """
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
            autoPlay();
            skipAds();
            
            // Run periodically
            setInterval(function() {
                autoPlay();
                skipAds();
                extractSources();
            }, 1000);
            
            if (typeof SnifferBridge !== 'undefined') SnifferBridge.log('Script setup complete');
        })();
    """.trimIndent()
    
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
