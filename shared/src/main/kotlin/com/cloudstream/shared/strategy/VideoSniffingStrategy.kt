package com.cloudstream.shared.strategy

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_VIDEO_SNIFFER
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Video sniffing strategy using WebView.
 * 
 * Features:
 * - Monitors network requests for video URLs (.m3u8, .mp4, etc.)
 * - JS injection for player interaction (auto-play, skip ads)
 * - JWPlayer source extraction via JS bridge
 */
class VideoSniffingStrategy(
    private val context: Context,
    private val timeout: Long = 35_000,
    private val dumpHtmlOnTimeout: Boolean = false
) {
    val name: String = "VideoSniffer"
    
    private val videoPatterns = listOf(
        Regex("""\\.m3u8(\\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""\\.mp4(\\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""\\.mkv(\\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""\\.webm(\\?|$)""", RegexOption.IGNORE_CASE)
    )
    
    private val capturedSources = CopyOnWriteArrayList<VideoSource>()
    private var sourcesDeferred: CompletableDeferred<List<VideoSource>>? = null
    
    companion object {
        const val MIN_VIDEO_URL_LENGTH = 50
        
        val JS_SCRIPT = """
        (function() {
            var sourcesSent = false;
            var clickAttempted = false;
            
            function skipAds() {
                try {
                    document.querySelectorAll('video').forEach(function(v) {
                        if (v.duration > 0 && v.duration < 30) {
                            v.playbackRate = 16;
                            v.muted = true;
                            v.currentTime = v.duration - 0.5;
                        }
                    });
                    
                    ['.jw-skip', '.skip-button', '.skip-ad', '[class*="skip"]', '[class*="SkipAd"]', '#skip-ad', '.vast-skip', '.ytp-ad-skip-button'].forEach(function(sel) {
                        var btn = document.querySelector(sel);
                        if (btn && btn.offsetParent) {
                            btn.click();
                            console.log('Skip ad clicked: ' + sel);
                        }
                    });
                } catch(e) {}
            }
            
            function clickPlayButton() {
                if (clickAttempted) return;
                try {
                    // Aggressive play button selectors
                    var selectors = [
                        'button[aria-label*="play" i]',
                        'button[title*="play" i]',
                        '.play-button',
                        '.vjs-big-play-button',
                        '.vjs-play-control',
                        '.jw-icon-playback',
                        '.plyr__control--overlaid',
                        '[class*="play" i]',
                        '#play',
                        '.play',
                        'video',
                        'button svg[class*="play"]',
                        'button:has(svg)',
                        '.player-overlay',
                        '.video-overlay',
                        '.start-button',
                        '.load-player',
                        '.btn-play'
                    ];
                    
                    for (var i = 0; i < selectors.length; i++) {
                        var elements = document.querySelectorAll(selectors[i]);
                        for (var j = 0; j < elements.length; j++) {
                            var el = elements[j];
                            if (el.offsetParent !== null && el.offsetWidth > 10 && el.offsetHeight > 10) {
                                // Simulate click
                                var clickEvent = new MouseEvent('click', {
                                    bubbles: true,
                                    cancelable: true,
                                    view: window
                                });
                                el.dispatchEvent(clickEvent);
                                
                                // Also try direct click
                                if (el.click) el.click();
                                
                                console.log('Clicked: ' + selectors[i] + ' #' + j);
                                clickAttempted = true;
                                return true;
                            }
                        }
                    }
                } catch(e) {}
                return false;
            }
            
            function autoPlay() {
                try {
                    // Try clicking play button first
                    clickPlayButton();
                    
                    if (typeof jwplayer !== 'undefined') {
                        var player = jwplayer();
                        if (player) {
                            if (player.setMute) player.setMute(true);
                            if (player.play) player.play();
                        }
                    }
                    
                    document.querySelectorAll('video').forEach(function(v) {
                        v.muted = true;
                        if (v.paused) { 
                            v.play().catch(function(e) {
                                // If autoplay blocked, try clicking on video element
                                v.click();
                            }); 
                        }
                    });
                } catch(e) {}
            }
            
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
                                        sources.push({url: src.file, label: src.label || 'Auto'});
                                    }
                                });
                                
                                if (sources.length > 0 && typeof SnifferBridge !== 'undefined') {
                                    sourcesSent = true;
                                    SnifferBridge.onSourcesFound(JSON.stringify(sources));
                                }
                            }
                        }
                    }
                } catch(e) {}
            }
            
            // Initial attempts
            setTimeout(function() { clickPlayButton(); }, 500);
            setTimeout(function() { clickPlayButton(); }, 1000);
            setTimeout(function() { clickPlayButton(); }, 2000);
            
            // Continuous monitoring
            autoPlay();
            skipAds();
            setInterval(function() { autoPlay(); skipAds(); extractSources(); }, 1000);
        })();
    """.trimIndent()
    }
    
    /**
     * Sniff video URLs from a player page.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(
        url: String,
        userAgent: String,
        cookies: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap()
    ): List<VideoSource> = withContext(Dispatchers.Main) {
        capturedSources.clear()
        sourcesDeferred = CompletableDeferred()
        
        ProviderLogger.i(TAG_VIDEO_SNIFFER, "sniff", "Starting", "url" to url.take(80))
        ProviderLogger.d(TAG_VIDEO_SNIFFER, "sniff", "UserAgent", "ua" to userAgent)
        ProviderLogger.d(TAG_VIDEO_SNIFFER, "sniff", "Cookies provided", "count" to cookies.size)
        ProviderLogger.d(TAG_VIDEO_SNIFFER, "sniff", "Headers provided", "keys" to extraHeaders.keys.joinToString())
        
        var webView: WebView? = null
        val startTime = System.currentTimeMillis()
        
        try {
            webView = createWebView(url, userAgent, cookies)
            if (extraHeaders.isNotEmpty()) {
                webView.loadUrl(url, extraHeaders)
            } else {
                webView.loadUrl(url)
            }
            
            val jsSources = withTimeoutOrNull(timeout) {
                var attempts = 0
                while (capturedSources.isEmpty() && sourcesDeferred?.isCompleted != true && attempts < 70) {
                    kotlinx.coroutines.delay(500)
                    attempts++
                }
                
                if (sourcesDeferred?.isCompleted == true) {
                    sourcesDeferred?.await() ?: emptyList()
                } else {
                    emptyList()
                }
            } ?: emptyList()
            
            val allSources = (jsSources + capturedSources).distinctBy { it.url }
            val durationMs = System.currentTimeMillis() - startTime
            
            ProviderLogger.i(TAG_VIDEO_SNIFFER, "sniff", "Complete",
                "jsSources" to jsSources.size, "networkSources" to capturedSources.size,
                "total" to allSources.size, "durationMs" to durationMs)
            
            if (allSources.isNotEmpty()) {
                allSources.forEachIndexed { index, source ->
                    ProviderLogger.d(TAG_VIDEO_SNIFFER, "sniff", "Found source #$index", "url" to source.url.take(60), "quality" to source.quality)
                }
            } else {
                ProviderLogger.w(TAG_VIDEO_SNIFFER, "sniff", "No sources found", "durationMs" to durationMs)
                
                // DUMP HTML for debugging why auto-click didn't work
                if (dumpHtmlOnTimeout) {
                    try {
                        val htmlDump = getHtmlFromWebView(webView!!)
                        ProviderLogger.w(TAG_VIDEO_SNIFFER, "sniff", "=== HTML DUMP START ===", "size" to htmlDump.length)
                        // Log HTML in chunks to avoid truncation
                        htmlDump.chunked(3000).forEachIndexed { idx, chunk ->
                            ProviderLogger.w(TAG_VIDEO_SNIFFER, "sniff", "HTML chunk", "index" to idx, "content" to chunk.take(500))
                        }
                        ProviderLogger.w(TAG_VIDEO_SNIFFER, "sniff", "=== HTML DUMP END ===")
                        
                        // Also try to get info about video elements
                        webView.evaluateJavascript("""
                            (function() {
                                var videos = document.querySelectorAll('video');
                                var iframes = document.querySelectorAll('iframe');
                                var result = {
                                    videoCount: videos.length,
                                    iframeCount: iframes.length,
                                    videoSources: [],
                                    iframeSrcs: []
                                };
                                videos.forEach(function(v, i) {
                                    result.videoSources.push({
                                        index: i,
                                        src: v.src || v.currentSrc || 'no-src',
                                        paused: v.paused,
                                        muted: v.muted,
                                        duration: v.duration,
                                        readyState: v.readyState
                                    });
                                });
                                iframes.forEach(function(f, i) {
                                    result.iframeSrcs.push(f.src || 'no-src');
                                });
                                return JSON.stringify(result);
                            })()
                        """) { jsResult ->
                            ProviderLogger.w(TAG_VIDEO_SNIFFER, "sniff", "Video/iframe info", "data" to jsResult)
                        }
                    } catch (e: Exception) {
                        ProviderLogger.e(TAG_VIDEO_SNIFFER, "sniff", "Failed to dump HTML", e)
                    }
                }
            }
            
            allSources
            
        } catch (e: Exception) {
            ProviderLogger.e(TAG_VIDEO_SNIFFER, "sniff", "Failed", e)
            capturedSources.toList()
        } finally {
            webView?.let { wv ->
                Handler(Looper.getMainLooper()).post {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
        }
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String, userAgent: String, cookies: Map<String, String>): WebView {
        val ctx = try { AcraApplication.context ?: context } catch (e: Exception) { context }
        
        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = userAgent
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            
            if (cookies.isNotEmpty()) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookies.forEach { (key, value) -> cookieManager.setCookie(url, "$key=$value") }
                cookieManager.flush()
            }
            
            addJavascriptInterface(SnifferBridge(), "SnifferBridge")
            webViewClient = createSnifferWebViewClient()
            
            // Add WebChromeClient to capture console logs from JavaScript
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        val msg = "${it.message()} [${it.sourceId()}:${it.lineNumber()}]"
                        when (it.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR -> 
                                android.util.Log.e("VideoSnifferJS", msg)
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> 
                                android.util.Log.w("VideoSnifferJS", msg)
                            else -> 
                                android.util.Log.d("VideoSnifferJS", msg)
                        }
                    }
                    return true
                }
                
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress % 20 == 0) {
                        ProviderLogger.d(TAG_VIDEO_SNIFFER, "WebView", "Loading progress", "percent" to newProgress)
                    }
                }
            }
        }
    }
    
    private fun createSnifferWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isVideoUrl(url)) {
                    ProviderLogger.d(TAG_VIDEO_SNIFFER, "intercept", "Video URL detected", "url" to url.take(80))
                    captureVideoUrl(url, request.requestHeaders ?: emptyMap())
                }
                return null
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(JS_SCRIPT) { }
            }
        }
    }
    
    private fun isVideoUrl(url: String): Boolean {
        if (url.length < MIN_VIDEO_URL_LENGTH) return false
        return videoPatterns.any { it.containsMatchIn(url) }
    }
    
    private fun captureVideoUrl(url: String, headers: Map<String, String>) {
        val quality = detectQuality(url)
        // STRICT M3U8 detection: check extension and query params
        val isM3u8 = url.contains(".m3u8", ignoreCase = true)
        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        
        // Capture cookies specifically for this video URL
        val videoCookies = try {
            val cookieManager = CookieManager.getInstance()
            val cookiesRaw = cookieManager.getCookie(url)
            if (!cookiesRaw.isNullOrBlank()) {
                 mapOf("Cookie" to cookiesRaw)
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
        
        // Merge headers: Original request headers + Video specific cookies
        val finalHeaders = headers + videoCookies
        
        capturedSources.add(VideoSource(url, quality, finalHeaders, type))
        
        ProviderLogger.i(TAG_VIDEO_SNIFFER, "captureVideoUrl", "Captured",
            "quality" to quality, "type" to type.name, "cookies" to (if (videoCookies.isNotEmpty()) "YES" else "NO"))
    }
    
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
    

    
    inner class SnifferBridge {
        @JavascriptInterface
        fun onSourcesFound(json: String) {
            ProviderLogger.i(TAG_VIDEO_SNIFFER, "SnifferBridge", "Sources received", "length" to json.length)
            try {
                val sources = parseSourcesJson(json)
                if (sources.isNotEmpty()) {
                    ProviderLogger.d(TAG_VIDEO_SNIFFER, "SnifferBridge", "Parsed ${sources.size} sources from JS")
                    sourcesDeferred?.complete(sources)
                } else {
                    ProviderLogger.w(TAG_VIDEO_SNIFFER, "SnifferBridge", "No sources parsed from JS JSON")
                }
            } catch (e: Exception) {
                ProviderLogger.e(TAG_VIDEO_SNIFFER, "SnifferBridge", "Parse failed", e)
            }
        }
        
        @JavascriptInterface
        fun log(message: String) {
            ProviderLogger.d(TAG_VIDEO_SNIFFER, "JS", message)
        }
    }
    
    private fun getCookieHeaders(url: String): Map<String, String> {
        return try {
            val cookieManager = CookieManager.getInstance()
            val cookiesRaw = cookieManager.getCookie(url)
            if (!cookiesRaw.isNullOrBlank()) {
                 mapOf("Cookie" to cookiesRaw)
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun parseSourcesJson(json: String): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()
        try {
            val pattern = Regex(""""url"\s*:\s*"([^"]+)"""")
            val labelPattern = Regex(""""label"\s*:\s*"([^"]+)"""")
            
            pattern.findAll(json).forEach { match ->
                val url = match.groupValues[1].replace("\\/", "/")
                val label = labelPattern.find(json)?.groupValues?.get(1) ?: "Auto"
                
                if (url.length > MIN_VIDEO_URL_LENGTH) {
                    val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    // FIX: Capture cookies for JS sources too
                    val headers = getCookieHeaders(url)
                    sources.add(VideoSource(url, label, headers, type))
                }
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG_VIDEO_SNIFFER, "parseSourcesJson", "Error", e)
        }
        return sources
    }
    
    /**
     * Get HTML content from WebView for debugging.
     */
    private fun getHtmlFromWebView(webView: WebView): String {
        return try {
            val htmlDeferred = CompletableDeferred<String>()
            webView.evaluateJavascript("""
                (function() {
                    return document.documentElement.outerHTML;
                })()
            """) { result ->
                htmlDeferred.complete(result?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "")
            }
            kotlinx.coroutines.runBlocking { 
                kotlinx.coroutines.withTimeout(3000) { htmlDeferred.await() } 
            }
        } catch (e: Exception) {
            "Error getting HTML: ${e.message}"
        }
    }
}
