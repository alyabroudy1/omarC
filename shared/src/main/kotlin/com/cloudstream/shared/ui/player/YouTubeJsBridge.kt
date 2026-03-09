package com.cloudstream.shared.ui.player

import android.webkit.WebView
import android.util.Log

/**
 * Manages communication with the underlying HTML5/YouTube player.
 * Logic adopted from omerFlex5-ref for production reliability.
 */
class YouTubeJsBridge(private val webView: WebView) {

    private val TAG = "YouTubeJsBridge"

    /**
     * Injects the CSS and JS logic used by omerFlex5-ref.
     * Includes aggressive ad-skipping, Arabic caption forcing, and fullscreen CSS.
     */
    fun injectFullscreenCss(isMuted: Boolean = false) {
        val js = """
            (function() {
                try {
                    // Bypass GDPR consent screen if redirected
                    if (window.location.hostname.indexOf('consent.youtube.com') !== -1) {
                        console.log("On consent page, attempting to auto-accept...");
                        var forms = document.querySelectorAll('form');
                        if (forms.length > 0) {
                            var buttons = document.querySelectorAll('button');
                            if (buttons.length > 0) {
                                for (var i = 0; i < buttons.length; i++) {
                                    if (buttons[i].textContent.indexOf('Accept') !== -1 || 
                                        buttons[i].textContent.indexOf('قبول') !== -1 ||   
                                        buttons[i].textContent.indexOf('Agree') !== -1) {
                                        buttons[i].click();
                                        return;
                                    }
                                }
                                buttons[buttons.length - 1].click();
                            } else {
                                forms[0].submit();
                            }
                        }
                        return; // DO NOT apply fullscreen CSS to the consent page
                    }

                    var style = document.querySelector('#cs-youtube-style');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'cs-youtube-style';
                        document.head.appendChild(style);
                    }
                    
                    style.textContent = `
                        html, body { 
                            margin: 0 !important;
                            padding: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            overflow: hidden !important;
                            background: #000 !important;
                        }
                        
                        /* Hide ALL mobile YouTube UI elements */
                        .mobile-topbar-header, 
                        .player-controls-top, 
                        .watch-below-the-player, 
                        ytm-app-header-layout header, 
                        .ytm-autonav-bar, 
                        .related-chips-slot-wrapper, 
                        .slim-video-metadata-header, 
                        .slim-video-information-renderer, 
                        ytm-item-section-renderer, 
                        ytm-comments-entry-point-header-renderer, 
                        ytm-comments-simplebox-renderer, 
                        .ytp-chrome-top, 
                        .ytp-chrome-bottom,
                        .ytp-gradient-top,
                        .ytp-gradient-bottom,
                        #masthead-container,
                        #header { display: none !important; visibility: hidden !important; }
                        
                        ytm-watch, ytm-app { background: #000 !important; padding: 0 !important; margin: 0 !important; }

                        /* Force ALL containers to fill screen */
                        #player-container-id, 
                        .player-container, 
                        #player, 
                        ytm-player, 
                        #movie_player, 
                        .html5-video-container, 
                        .html5-video-player, 
                        .html5-main-video { 
                            position: fixed !important;
                            top: 0 !important;
                            left: 0 !important;
                            right: 0 !important;
                            bottom: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            max-width: 100vw !important;
                            max-height: 100vh !important;
                            margin: 0 !important;
                            padding: 0 !important;
                            z-index: 9999 !important;
                            background: #000 !important;
                        }

                        /* Force video element to fill entire screen */
                        video { 
                            position: fixed !important;
                            top: 0 !important;
                            left: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            object-fit: contain !important; /* contain is better for non-stretched video */
                            z-index: 9998 !important;
                            background: #000 !important;
                        }

                        /* ENSURE CAPTION CONTAINERS ARE VISIBLE */
                        .ytp-caption-window-container, 
                        .caption-window, 
                        .captions-text, 
                        .ytp-caption-segment { 
                            display: block !important;
                            visibility: visible !important;
                            opacity: 1 !important;
                            z-index: 99999 !important;
                        }
                    `;

                    // Auto-play video
                    var video = document.querySelector('video');
                    if (video) {
                        video.muted = $isMuted;
                        video.play().catch(function(e) { console.log('Autoplay blocked'); });
                    }

                    // AD SKIPPER & CAPTION MANAGER - Loop runs every 500ms
                    if (!window.csAdManagerRunning) {
                        window.csAdManagerRunning = true;
                        setInterval(function() {
                            try {
                                var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, [class*="skip"][class*="button"]');
                                if (skipBtn && skipBtn.style.display !== 'none') {
                                    skipBtn.click();
                                }
                                
                                var mPlayer = document.querySelector('#movie_player');
                                if (mPlayer) {
                                    // Fast-forward through unskippable ads
                                    if (mPlayer.classList.contains('ad-showing')) {
                                        var v = document.querySelector('video');
                                        if (v && v.duration && v.duration > 0 && v.duration < 120) {
                                            v.currentTime = v.duration;
                                        }
                                    }
                                    
                                    // Ensure captions are enabled in Arabic
                                    if (mPlayer.getOption) {
                                        var tracks = mPlayer.getOption('captions', 'tracklist');
                                        if (tracks && tracks.length > 0) {
                                            var arabicTrack = tracks.find(function(t) { return t.languageCode === 'ar'; });
                                            if (arabicTrack) {
                                                if (mPlayer.getOption('captions', 'track')?.languageCode !== 'ar') {
                                                    mPlayer.setOption('captions', 'track', arabicTrack);
                                                }
                                            } else {
                                                // Try auto-translate
                                                var autoTrack = tracks.find(function(t) { return t.kind === 'asr' || t.is_default; }) || tracks[0];
                                                if (autoTrack) {
                                                    mPlayer.setOption('captions', 'track', {languageCode: autoTrack.languageCode});
                                                    mPlayer.setOption('captions', 'translationLanguage', {languageCode: 'ar', languageName: 'Arabic'});
                                                }
                                            }
                                            
                                            // Force CC visibility if not on
                                            var ccBtn = document.querySelector('.ytp-subtitles-button');
                                            if (ccBtn && ccBtn.getAttribute('aria-pressed') !== 'true') {
                                                ccBtn.click();
                                            }
                                            
                                            // Additional check from omerFlex5-ref
                                            if (mPlayer.toggleSubtitles && (!mPlayer.isSubtitlesOn || !mPlayer.isSubtitlesOn())) {
                                                mPlayer.toggleSubtitles();
                                            }
                                        }
                                    }
                                }
                            } catch(e) {}
                        }, 500);
                    }
                } catch(e) {
                    console.error("Injection error: " + e.message);
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
        evaluateJs("""
            (function() { 
                var v = document.querySelector('video'); 
                if (v) { 
                  var wasPlaying = !v.paused; 
                  v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + $seconds)); 
                  if (wasPlaying) { v.pause(); setTimeout(function() { v.play(); }, 100); }
                } 
            })();
        """.trimIndent())
    }

    fun seekTo(timeSeconds: Double) {
        evaluateJs("""
            (function() { 
                var v = document.querySelector('video'); 
                if (v) { 
                  var wasPlaying = !v.paused; 
                  v.currentTime = $timeSeconds; 
                  if (wasPlaying) { v.pause(); setTimeout(function() { v.play(); }, 100); }
                } 
            })();
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

    fun setPlaybackSpeed(speed: Double) {
        evaluateJs("var v = document.querySelector('video'); if(v) { v.playbackRate = $speed; }")
    }

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
                    val unescaped = result.substring(1, result.length - 1).replace("\\\"", "\"")
                    val json = org.json.JSONObject(unescaped)
                    val t = json.optDouble("t", 0.0)
                    val d = json.optDouble("d", 0.0)
                    val p = json.optBoolean("p", true)
                    onResult(t, d, p)
                }
            } catch (e: Exception) {}
        }
    }

    private fun evaluateJs(js: String) {
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }
}
