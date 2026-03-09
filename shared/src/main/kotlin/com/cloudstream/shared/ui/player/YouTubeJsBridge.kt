package com.cloudstream.shared.ui.player

import android.webkit.WebView
import android.util.Log

/**
 * Manages communication with the underlying HTML5/YouTube player.
 */
class YouTubeJsBridge(private val webView: WebView) {

    private val TAG = "YouTubeJsBridge"

    fun injectFullscreenCss() {
        Log.d(TAG, "injectFullscreenCss: Injecting CSS with viewport fix")
        val js = """
            (function() {
                try {
                    var viewport = document.querySelector('meta[name="viewport"]');
                    if (!viewport) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        document.head.appendChild(viewport);
                    }
                    viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                    
                    var css = `
                      * { box-sizing: border-box !important; }
                      html, body {
                        background: #000 !important; 
                        width: 100% !important; height: 100% !important;
                        min-height: 100% !important;
                        margin: 0 !important; padding: 0 !important; 
                        overflow: hidden !important;
                        position: fixed !important;
                        top: 0 !important; left: 0 !important;
                      }
                      #page, #content, ytd-app, ytd-watch-flexy, #player-theater-container,
                      #player, .player-container, #movie_player, .html5-video-player,
                      .html5-video-container {
                        position: fixed !important; 
                        top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important;
                        width: 100% !important; height: 100% !important;
                        min-height: 100% !important;
                        margin: 0 !important; padding: 0 !important;
                        background: #000 !important;
                        z-index: 9999 !important;
                        transform: none !important;
                      }
                      video, .video-stream, .html5-main-video, iframe {
                        position: absolute !important;
                        top: 0 !important; left: 0 !important;
                        width: 100% !important; height: 100% !important;
                        min-height: 100% !important;
                        object-fit: contain !important;
                        object-position: center center !important;
                        background: #000 !important;
                        z-index: 1 !important;
                      }
                      .mobile-topbar-header, .player-controls-top, .watch-below-the-player, 
                      .ytp-chrome-top, .ytp-chrome-bottom, .ytp-gradient-top, .ytp-gradient-bottom,
                      .ad-showing, .video-ads, .ytp-ad-overlay-container, .ytp-ad-module,
                      .ytp-upnext, .ytp-suggestion-set, .ytp-share-panel, .ytp-watermark,
                      .ytp-title, .ytp-title-link, .ytp-show-cards-title,
                      #secondary, #related, #comments, #masthead, #guide,
                      ytd-watch-next-secondary-results-renderer, ytd-compact-video-renderer { 
                          display: none !important; 
                          opacity: 0 !important; 
                          visibility: hidden !important;
                          pointer-events: none !important;
                          height: 0 !important;
                          width: 0 !important;
                      }
                      video { opacity: 1 !important; visibility: visible !important; }
                      .caption-window, .ytp-caption-window-container, .ytp-caption-segment {
                          display: block !important;
                          opacity: 1 !important;
                          visibility: visible !important;
                          z-index: 20000 !important;
                      }
                    `;
                    var style = document.createElement('style');
                    style.appendChild(document.createTextNode(css));
                    document.head.appendChild(style);
                    
                    var vid = document.querySelector('video');
                    if(vid) { vid.muted = false; vid.play(); }
                } catch(e) {
                    console.error("CSS Injection Failed: " + e.message);
                }
            })();
        """.trimIndent()
        evaluateJs(js)
    }

    fun play() {
        evaluateJs("var v = document.querySelector('video'); if (v) { v.play(); }")
    }

    fun pause() {
        evaluateJs("var v = document.querySelector('video'); if (v) { v.pause(); }")
    }

    fun seekRelative(seconds: Int) {
        evaluateJs("var v = document.querySelector('video'); if(v) { v.currentTime += $seconds; }")
    }

    fun seekTo(timeSeconds: Double) {
        evaluateJs("var v = document.querySelector('video'); if(v) { v.currentTime = $timeSeconds; }")
    }

    fun setPlaybackSpeed(speed: Double) {
        evaluateJs("var v = document.querySelector('video'); if(v) { v.playbackRate = $speed; }")
    }

    fun setQuality(qualityStr: String) {
        evaluateJs("""
            try {
                var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                if (player && typeof player.setPlaybackQualityRange === 'function') {
                    player.setPlaybackQualityRange('$qualityStr');
                }
            } catch(e) {}
        """.trimIndent())
    }

    fun setCaptionLanguage(langCode: String) {
        evaluateJs("""
            try {
                var player = document.getElementById('movie_player');
                if (player && player.setOption) {
                    player.setOption('captions', 'track', {'languageCode': '$langCode'});
                }
            } catch(e) {}
        """.trimIndent())
    }

    fun setScaleMode(isCover: Boolean) {
        val fitType = if (isCover) "cover" else "contain"
        evaluateJs("""
            var v = document.querySelector('video');
            if (v) {
                v.style.setProperty('object-fit', '$fitType', 'important');
            }
        """.trimIndent())
    }

    /**
     * Polls the video state. In a full implementation, this could use a JavascriptInterface,
     * but polling via evaluateJavascript is simpler to inject securely dynamically.
     */
    fun pollPlaybackState(onResult: (currentTime: Double, duration: Double, isPaused: Boolean) -> Unit) {
        val js = """
            (function() {
                var v = document.querySelector('video');
                if (v) {
                    return JSON.stringify({
                        't': v.currentTime || 0,
                        'd': v.duration || 0,
                        'p': v.paused
                    });
                }
                return null;
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { result ->
            try {
                if (result != null && result != "null" && result.startsWith("\"")) {
                    // Result comes back wrapped in quotes and escaped.
                    val unescaped = result.substring(1, result.length - 1).replace("\\\"", "\"")
                    val json = org.json.JSONObject(unescaped)
                    val t = json.optDouble("t", 0.0)
                    val d = json.optDouble("d", 0.0)
                    val p = json.optBoolean("p", true)
                    onResult(t, d, p)
                }
            } catch (e: Exception) {
                // Ignore parse errors or null states during navigation
            }
        }
    }

    private fun evaluateJs(js: String) {
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }
}
