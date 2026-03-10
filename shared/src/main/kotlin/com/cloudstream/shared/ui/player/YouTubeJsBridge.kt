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
                        
                        // 1. Try known YouTube consent button classes first
                        var acceptBtn = document.querySelector('.eom-accept, .VfPpkd-LgbsSe, button[aria-label="Accept all"], button[aria-label="Agree"]');
                        if (acceptBtn) {
                            acceptBtn.click();
                            return;
                        }

                        // 2. Try generic form submit fallback
                        var forms = document.querySelectorAll('form');
                        if (forms.length > 0) {
                            var buttons = document.querySelectorAll('button');
                            if (buttons.length > 0) {
                                for (var i = 0; i < buttons.length; i++) {
                                    var text = buttons[i].textContent.toLowerCase();
                                    if (text.indexOf('accept all') !== -1 || text.indexOf('accept') !== -1 || 
                                        text.indexOf('قبول الكل') !== -1 || text.indexOf('موافق') !== -1 ||   
                                        text.indexOf('agree') !== -1 || text.indexOf('ich stimme zu') !== -1 ||
                                        text.indexOf('accepter') !== -1 || text.indexOf('aceptar') !== -1) {
                                        buttons[i].click();
                                        return;
                                    }
                                }
                                forms[0].submit();
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

    fun setPlaybackQuality(quality: String) {
        evaluateJs("""
            (function() {
                var player = document.querySelector('#movie_player');
                if (player && player.setPlaybackQualityRange) {
                    player.setPlaybackQualityRange('$quality');
                    player.setPlaybackQuality('$quality');
                }
            })();
        """.trimIndent())
    }

    fun setCaptions(enabled: Boolean, languageCode: String? = null, translateTo: String? = null) {
        val translateToJs = if (translateTo != null) "'$translateTo'" else "null"
        val languageCodeJs = if (languageCode != null) "'$languageCode'" else "null"
        evaluateJs("""
            (function() {
                try {
                    var player = document.querySelector('#movie_player');
                    if (player && player.setOption) {
                        var tracks = player.getOption('captions', 'tracklist') || [];
                        if ($enabled) {
                            if ($translateToJs !== null) {
                                var track = tracks.find(function(t) { return t.kind === 'asr' || t.is_default; }) || tracks[0];
                                if (track) {
                                    player.setOption('captions', 'track', {languageCode: track.languageCode});
                                    player.setOption('captions', 'translationLanguage', {languageCode: $translateToJs});
                                }
                            } else if ($languageCodeJs !== null) {
                                var track = tracks.find(function(t) { return t.languageCode === $languageCodeJs; }) || tracks[0];
                                if (track) player.setOption('captions', 'track', track);
                            } else {
                                if (tracks.length > 0) player.setOption('captions', 'track', tracks[0]);
                            }
                            var ccBtn = document.querySelector('.ytp-subtitles-button');
                            if (ccBtn && ccBtn.getAttribute('aria-pressed') !== 'true') ccBtn.click();
                        } else {
                            player.setOption('captions', 'track', {});
                            var ccBtn = document.querySelector('.ytp-subtitles-button');
                            if (ccBtn && ccBtn.getAttribute('aria-pressed') === 'true') ccBtn.click();
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent())
    }

    fun clickDescription() {
        evaluateJs("""
            (function() {
                var descBtn = document.querySelector('.slim-video-metadata-header, .slim-video-information-renderer, ytm-slim-video-information-renderer');
                if (descBtn) descBtn.click();
            })();
        """.trimIndent())
    }

    fun fetchMetadata(onResult: (title: String, channel: String, avatarUrl: String) -> Unit) {
        val js = """
            (function() {
                try {
                    var title = document.title || '';
                    var titleNode = document.querySelector('.slim-video-metadata-title, .title, .video-title');
                    if (titleNode && titleNode.textContent) title = titleNode.textContent.trim();
                    if (title.endsWith(' - YouTube')) title = title.replace(' - YouTube', '');

                    var channel = '';
                    var channelNode = document.querySelector('.slim-owner-channel-name, .channel-name');
                    if (channelNode && channelNode.textContent) channel = channelNode.textContent.trim();

                    var avatar = '';
                    var avatarNode = document.querySelector('.slim-owner-icon img, .channel-avatar img');
                    if (avatarNode && avatarNode.src) avatar = avatarNode.src;

                    return JSON.stringify({
                        'title': title,
                        'channel': channel,
                        'avatar': avatar
                    });
                } catch(e) { return null; }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { result ->
            try {
                if (result != null && result != "null" && result.startsWith("\"")) {
                    val unescaped = result.substring(1, result.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    val json = org.json.JSONObject(unescaped)
                    val t = json.optString("title", "")
                    val c = json.optString("channel", "")
                    val a = json.optString("avatar", "")
                    if (t.isNotEmpty()) {
                        onResult(t, c, a)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse metadata", e)
            }
        }
    }
    fun pollPlaybackState(onResult: (currentTime: Double, bufferedTime: Double, duration: Double, isPaused: Boolean) -> Unit) {
        val js = """
            (function() {
                var v = document.querySelector('video');
                if (v) {
                    var b = 0;
                    if (v.buffered && v.buffered.length > 0) {
                        b = v.buffered.end(v.buffered.length - 1);
                    }
                    return JSON.stringify({
                        't': v.currentTime || 0,
                        'b': b,
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
                    val b = json.optDouble("b", 0.0)
                    val d = json.optDouble("d", 0.0)
                    val p = json.optBoolean("p", true)
                    onResult(t, b, d, p)
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
