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
            console.log('[VideoSniffer] Script starting...');
            var sourcesSent = false;
            var clickCount = 0;
            var maxClicks = 50; // Increased limit
            
            // ===== UTILITY FUNCTIONS =====
            function log(msg) {
                console.log('[VideoSniffer] ' + msg);
                if (typeof SnifferBridge !== 'undefined') {
                    try { SnifferBridge.log(msg); } catch(e) {}
                }
            }
            
            function isVisible(el) {
                if (!el) return false;
                var rect = el.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0 && 
                       rect.top >= 0 && rect.top < window.innerHeight &&
                       rect.left >= 0 && rect.left < window.innerWidth;
            }
            
            // ===== COMPREHENSIVE CLICK SIMULATION =====
            function simulateFullClick(element, x, y) {
                if (!element) return false;
                
                if (clickCount >= maxClicks) {
                    log('Max clicks reached (' + maxClicks + '), ignoring click on ' + (element.tagName || 'unknown'));
                    return false;
                }
                clickCount++;
                
                try {
                    var rect = element.getBoundingClientRect();
                    // Calculate absolute coordinates considering scroll
                    var clickX = x || (rect.left + rect.width / 2);
                    var clickY = y || (rect.top + rect.height / 2);
                    
                    // Log element details for debugging
                    var elementInfo = element.tagName;
                    if (element.id) elementInfo += '#' + element.id;
                    if (element.className) elementInfo += '.' + element.className.split(' ')[0]; // Just first class
                    var html = element.outerHTML.substring(0, 100); // Capture first 100 chars
                    
                    log('Clicking element: ' + elementInfo + ' at ' + clickX + ',' + clickY + ' HTML: ' + html);
                    
                    // PRIMARY METHOD: Native Touch Injection via Bridge
                    if (typeof SnifferBridge !== 'undefined' && SnifferBridge.nativeClick) {
                         // Adjust for device pixel ratio if necessary, but usually WebView handles CSS pixels
                         // We pass CSS pixels, native side might need to convert if dispatchTouchEvent expects physical pixels
                         // But usually WebView.dispatchTouchEvent takes coordinates relative to the view in pixels.
                         // Standard WebView conversion:
                         var density = window.devicePixelRatio || 1; 
                         // Native dispatchTouchEvent usually expects physical pixels
                         SnifferBridge.nativeClick(clickX * density, clickY * density);
                         log('Requested native nativeClick at ' + (clickX*density) + ',' + (clickY*density));
                         return true;
                    }

                    // Method 1: Touch events (mobile) fallback
                    try {
                        var touch = new Touch({
                            identifier: Date.now(),
                            target: element,
                            clientX: clickX, clientY: clickY,
                            screenX: clickX, screenY: clickY,
                            pageX: clickX, pageY: clickY,
                            radiusX: 1, radiusY: 1, rotationAngle: 0, force: 1
                        });
                        element.dispatchEvent(new TouchEvent('touchstart', {bubbles: true, touches: [touch], targetTouches: [touch], changedTouches: [touch]}));
                        element.dispatchEvent(new TouchEvent('touchend', {bubbles: true, touches: [], targetTouches: [], changedTouches: [touch]}));
                    } catch(e) {}
                    
                    // Method 2: Mouse events
                    ['mousedown', 'mouseup', 'click'].forEach(function(eventType) {
                        element.dispatchEvent(new MouseEvent(eventType, {
                            bubbles: true, cancelable: true, view: window,
                            clientX: clickX, clientY: clickY,
                            screenX: clickX, screenY: clickY
                        }));
                    });
                     
                    // Method 4: Direct click
                    if (element.click) element.click();
                    
                    return true;
                } catch(e) {
                    log('Click error: ' + e.message);
                    return false;
                }
            }
            
            // ===== PLAYER DETECTION & CLICKING =====
            function findAndClickPlayButton() {
                // Priority selectors for different players
                var selectors = [
                    // Video.js
                    '.vjs-big-play-button', '.vjs-control-bar .vjs-play-control', '.video-js button[title="Play"]',
                    // JW Player
                    '.jw-icon-playback', '.jw-display-icon-container', '.jw-button-container .jw-icon-playback',
                    // Plyr
                    '.plyr__control--overlaid', '.plyr__controls button[data-plyr="play"]',
                    // HTML5 video
                    'video', 'video[controls]',
                    // Generic
                    '[class*="play-button"]', '[class*="playbutton"]', '#play-button', '.btn-play',
                    'button[aria-label*="play" i]', 'button[title*="play" i]', '[class*="big-play"]',
                    // Overlays
                    '.player-overlay', '.video-overlay', '.play-overlay', '[class*="overlay"]',
                    // Savefiles specific
                    '.start-button', '.load-player', '.watch-video', '[class*="watch"]',
                    // Generic clickable areas
                    '.click-to-play', '#click-to-play', '.video-wrapper', '.player-wrapper'
                ];
                
                for (var i = 0; i < selectors.length; i++) {
                    var elements = document.querySelectorAll(selectors[i]);
                    for (var j = 0; j < elements.length; j++) {
                        var el = elements[j];
                        if (isVisible(el)) {
                            log('Found play target: ' + selectors[i] + ' #' + j + ' (' + el.offsetWidth + 'x' + el.offsetHeight + ')');
                            if (simulateFullClick(el)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
            
            function clickCenterOfScreen() {
                var centerX = window.innerWidth / 2;
                var centerY = window.innerHeight / 2;
                var el = document.elementFromPoint(centerX, centerY);
                
                if (el) {
                    var elInfo = el.tagName;
                    if (el.id) elInfo += '#' + el.id;
                    if (el.className) elInfo += '.' + el.className.split(' ')[0];
                    log('Clicking center element: ' + elInfo);
                    simulateFullClick(el, centerX, centerY);
                    return true;
                } else {
                    log('No element at center. Forcing native click.');
                    if (typeof SnifferBridge !== 'undefined' && SnifferBridge.nativeClick) {
                         var density = window.devicePixelRatio || 1;
                         SnifferBridge.nativeClick(centerX * density, centerY * density);
                         return true;
                    }
                }
                return false;
            }
            
            var nuclearAttempts = 0;

            // ===== AUTO-PLAY LOGIC =====
            function attemptAutoPlay() {
                log('Attempting auto-play...');
                
                // Try play buttons first
                if (findAndClickPlayButton()) {
                    log('Play button clicked successfully');
                    nuclearAttempts = 0; // Reset
                    return true;
                }
                
                // Try direct video play
                var videos = document.querySelectorAll('video');
                var played = false;
                if (videos.length > 0) {
                    nuclearAttempts = 0; // Reset
                    videos.forEach(function(v) {
                        if (v.paused) {
                            v.muted = true;
                            v.volume = 0;
                            v.play().then(function() {
                                log('Video started playing directly');
                                played = true;
                            }).catch(function(e) {
                                log('Autoplay blocked: ' + e.message);
                                // Try clicking the video
                                simulateFullClick(v);
                            });
                        }
                    });
                } else {
                    // Fallback: If no videos and no play buttons, check for iframes
                    var iframes = document.querySelectorAll('iframe');
                    if (iframes.length > 0) {
                        log('No video/play-btn found, but ' + iframes.length + ' iframes exist. Trying center click fallback... (Attempt ' + clickCount + ')');
                        if (clickCount < 5) { // Only try a few times to avoid endless clicking
                             clickCenterOfScreen();
                        }
                    } else {
                         log('No videos, play buttons, or iframes found.');
                         nuclearAttempts++;
                         if (nuclearAttempts > 2 && clickCount < 5) {
                             log('NUCLEAR OPTION: Blindly clicking center (Attempt ' + nuclearAttempts + ')');
                             clickCenterOfScreen();
                         }
                    }
                }
                
                return played;
            }
            
            // ===== AD SKIP =====
            function skipAds() {
                try {
                    // Speed up short videos (ads)
                    document.querySelectorAll('video').forEach(function(v) {
                        if (v.duration > 0 && v.duration < 30) {
                            v.playbackRate = 16;
                            v.muted = true;
                            v.currentTime = v.duration - 0.5;
                        }
                    });
                    
                    // Click skip buttons
                    ['.skip-ad', '.skip-button', '.vast-skip', '.jw-skip', '[class*="skip"]', '[class*="close"]', '.ad-close'].forEach(function(sel) {
                        var btn = document.querySelector(sel);
                        if (btn && isVisible(btn)) {
                            simulateFullClick(btn);
                            log('Skipped ad: ' + sel);
                        }
                    });
                } catch(e) {}
            }
            
            // ===== VIDEO SOURCE EXTRACTION =====
            function extractSources() {
                if (sourcesSent) return;
                
                var sources = [];
                
                // Method 1: Network-intercepted sources (already captured by Kotlin)
                
                // Method 2: Video element sources
                document.querySelectorAll('video').forEach(function(v) {
                    if (v.src && v.src.length > 40) sources.push({url: v.src, label: 'Video'});
                    v.querySelectorAll('source').forEach(function(s) {
                        if (s.src && s.src.length > 40) sources.push({url: s.src, label: s.type || 'Source'});
                    });
                });
                
                // Method 3: JWPlayer
                try {
                    if (typeof jwplayer !== 'undefined') {
                        var player = jwplayer();
                        if (player && player.getPlaylistItem) {
                            var item = player.getPlaylistItem();
                            if (item && item.sources) {
                                item.sources.forEach(function(src) {
                                    if (src.file && src.file.length > 40) {
                                        sources.push({url: src.file, label: src.label || 'JW'});
                                    }
                                });
                            }
                        }
                    }
                } catch(e) {}
                
                // Method 4: Global player objects
                try {
                    if (window.player && window.player.src) sources.push({url: window.player.src, label: 'Global'});
                    if (window.videojs && window.videojs.players) {
                        Object.values(window.videojs.players).forEach(function(p) {
                            if (p.src && p.src()) sources.push({url: p.src(), label: 'VideoJS'});
                        });
                    }
                } catch(e) {}
                
                // Send sources if found
                if (sources.length > 0 && typeof SnifferBridge !== 'undefined') {
                    log('Found ' + sources.length + ' video sources');
                    sourcesSent = true;
                    SnifferBridge.onSourcesFound(JSON.stringify(sources));
                }
            }
            
            // ===== MUTATION OBSERVER - DETECT DYNAMIC PLAYER =====
            var observer = new MutationObserver(function(mutations) {
                findAndClickPlayButton();
                extractSources();
            });
            
            observer.observe(document.body, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['src', 'data-src']
            });
            
            // ===== EXECUTION =====
            log('Initializing auto-click system...');
            
            // Immediate attempt
            setTimeout(function() { attemptAutoPlay(); }, 500);
            
            // Retry with increasing delays
            [1000, 2000, 3000, 4000, 5000, 7000, 10000, 15000].forEach(function(delay) {
                setTimeout(function() {
                    if (!sourcesSent) {
                        log('Retry #' + (delay/1000) + 's');
                        attemptAutoPlay();
                        skipAds();
                        extractSources();
                    }
                }, delay);
            });
            
            // Continuous monitoring
            setInterval(function() {
                if (!sourcesSent) {
                    skipAds();
                    extractSources();
                    // Periodic click attempt every 3 seconds
                    if (clickCount < maxClicks) {
                        findAndClickPlayButton();
                    }
                }
            }, 3000);
            
            log('Auto-click system active - waiting for player...');
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
                for ((key, value) in cookies) {
                    cookieManager.setCookie(url, "$key=$value")
                }
                cookieManager.flush()
            }
            
            addJavascriptInterface(SnifferBridge(this), "SnifferBridge")
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
                ProviderLogger.d(TAG_VIDEO_SNIFFER, "onPageFinished", "Page loaded", "url" to (url?.take(80) ?: "unknown"))
                
                view?.let { webView ->
                    // First inject into main page
                    webView.evaluateJavascript(JS_SCRIPT) { }
                    
                    // Then check for iframes after a delay
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        injectIntoIframes(webView)
                    }, 1000)
                }
            }
            
            fun injectIntoIframes(webView: WebView) {
                try {
                    // Just log iframe info for now - cross-origin iframe injection is blocked by browser security
                    webView.evaluateJavascript("""
                        (function() {
                            var iframes = document.querySelectorAll('iframe');
                            var result = [];
                            for (var i = 0; i < iframes.length; i++) {
                                var iframe = iframes[i];
                                result.push({
                                    index: i, 
                                    src: iframe.src || 'no-src',
                                    hasContent: !!iframe.contentDocument
                                });
                            }
                            return JSON.stringify(result);
                        })()
                    """.trimIndent()) { result ->
                        ProviderLogger.d(TAG_VIDEO_SNIFFER, "iframe", "Found iframes", "data" to result)
                    }
                } catch (e: Exception) {
                    ProviderLogger.w(TAG_VIDEO_SNIFFER, "injectIntoIframes", "Error", "error" to e.message)
                }
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
    

    
    inner class SnifferBridge(private val webView: WebView) {
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

        @JavascriptInterface
        fun nativeClick(x: Float, y: Float) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val downTime = System.currentTimeMillis()
                    val eventTime = downTime + 100
                    val metaState = 0
                    
                    val motionEventDown = android.view.MotionEvent.obtain(
                        downTime,
                        eventTime,
                        android.view.MotionEvent.ACTION_DOWN,
                        x,
                        y,
                        metaState
                    )
                    
                    val motionEventUp = android.view.MotionEvent.obtain(
                        downTime + 100, // slightly later
                        eventTime + 200,
                        android.view.MotionEvent.ACTION_UP,
                        x,
                        y,
                        metaState
                    )
                    
                    webView.dispatchTouchEvent(motionEventDown)
                    webView.dispatchTouchEvent(motionEventUp)
                    
                    motionEventDown.recycle()
                    motionEventUp.recycle()
                    
                    ProviderLogger.d(TAG_VIDEO_SNIFFER, "nativeClick", "Dispatched native touch at $x,$y")
                } catch (e: Exception) {
                    ProviderLogger.e(TAG_VIDEO_SNIFFER, "nativeClick", "Failed", e)
                }
            }
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
