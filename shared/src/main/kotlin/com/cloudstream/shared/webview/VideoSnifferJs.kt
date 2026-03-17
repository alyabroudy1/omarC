package com.cloudstream.shared.webview

/**
 * JavaScript snippets for video sniffing.
 *
 * Contains the auto-play, ad-skip, player detection, and source extraction
 * JavaScript that is injected into web pages during video sniffing sessions.
 *
 * Click strategy: JS finds the target element coordinates, then calls
 * `SnifferBridge.requestNativeClick(x, y)` for a trusted OS-level touch event.
 * Falls back to JS-only click simulation if bridge is unavailable.
 */
object VideoSnifferJs {

    const val MIN_VIDEO_URL_LENGTH = 50

    val JS_SCRIPT = """
        (function() {
            try {
            console.log('[VideoSniffer] Script starting...');
            var sourcesSent = false;
            var playStarted = false;
            var clickCount = 0;
            var maxClicks = 30;
            
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
            
            // ===== CLICK SIMULATION =====
            // Primary: Native click via bridge (isTrusted = true)
            // Fallback: JS touch + mouse events (isTrusted = false)
            function simulateClick(element, x, y) {
                if (!element) return false;
                
                if (clickCount >= maxClicks) {
                    log('Max clicks reached, ignoring');
                    return false;
                }
                clickCount++;
                
                try {
                    var rect = element.getBoundingClientRect();
                    var clickX = x || (rect.left + rect.width / 2);
                    var clickY = y || (rect.top + rect.height / 2);
                    var density = window.devicePixelRatio || 1;
                    
                    var tag = element.tagName;
                    if (element.id) tag += '#' + element.id;
                    if (element.className) tag += '.' + String(element.className).split(' ')[0];
                    log('Click target: ' + tag + ' at ' + Math.round(clickX) + ',' + Math.round(clickY));
                    
                    // PRIMARY: Native touch via bridge (produces isTrusted=true events)
                    if (typeof SnifferBridge !== 'undefined' && SnifferBridge.requestNativeClick) {
                        SnifferBridge.requestNativeClick(clickX * density, clickY * density);
                        log('Native click dispatched');
                        return true;
                    }
                    
                    // FALLBACK: JS events (isTrusted=false, may be blocked by strict sites)
                    try {
                        var touch = new Touch({
                            identifier: Date.now(), target: element,
                            clientX: clickX, clientY: clickY,
                            screenX: clickX, screenY: clickY,
                            pageX: clickX, pageY: clickY,
                            radiusX: 1, radiusY: 1, rotationAngle: 0, force: 1
                        });
                        element.dispatchEvent(new TouchEvent('touchstart', {bubbles: true, touches: [touch], targetTouches: [touch], changedTouches: [touch]}));
                        element.dispatchEvent(new TouchEvent('touchend', {bubbles: true, touches: [], targetTouches: [], changedTouches: [touch]}));
                    } catch(e) {}
                    
                    ['mousedown', 'mouseup', 'click'].forEach(function(type) {
                        element.dispatchEvent(new MouseEvent(type, {
                            bubbles: true, cancelable: true, view: window,
                            clientX: clickX, clientY: clickY
                        }));
                    });
                    
                    if (element.click) element.click();
                    return true;
                } catch(e) {
                    log('Click error: ' + e.message);
                    return false;
                }
            }
            
            // ===== PLAY BUTTON DETECTION =====
            function findAndClickPlayButton() {
                var selectors = [
                    // Video.js
                    '.vjs-big-play-button', '.video-js button[title="Play"]',
                    // JW Player
                    '.jw-icon-playback', '.jw-display-icon-container',
                    // Plyr
                    '.plyr__control--overlaid', '.plyr__controls button[data-plyr="play"]',
                    // HTML5 video
                    'video',
                    // Generic play buttons
                    '[class*="play-button"]', '[class*="playbutton"]', '#play-button', '.btn-play',
                    'button[aria-label*="play" i]', 'button[title*="play" i]', '[class*="big-play"]',
                    // Site-specific
                    '.start-button', '.load-player', '.watch-video',
                    // Wrapper areas
                    '.click-to-play', '.video-wrapper', '.player-wrapper'
                ];
                
                for (var i = 0; i < selectors.length; i++) {
                    var elements = document.querySelectorAll(selectors[i]);
                    for (var j = 0; j < elements.length; j++) {
                        if (isVisible(elements[j])) {
                            log('Found play target: ' + selectors[i]);
                            if (simulateClick(elements[j])) return true;
                        }
                    }
                }
                return false;
            }
            
            function clickCenterOfScreen() {
                var cx = window.innerWidth / 2;
                var cy = window.innerHeight / 2;
                var el = document.elementFromPoint(cx, cy);
                if (el) {
                    simulateClick(el, cx, cy);
                    return true;
                }
                // No element at center — try native click directly
                if (typeof SnifferBridge !== 'undefined' && SnifferBridge.requestNativeClick) {
                    var density = window.devicePixelRatio || 1;
                    SnifferBridge.requestNativeClick(cx * density, cy * density);
                    return true;
                }
                return false;
            }
            
            var nuclearAttempts = 0;

            // Check if any video is actually playing (not just that we clicked something)
            function isActuallyPlaying() {
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                    if (!videos[i].paused && videos[i].currentTime > 0) return true;
                }
                return false;
            }
            
            // ===== AUTO-PLAY LOGIC =====
            function attemptAutoPlay() {
                // If video is already playing, stop clicking
                if (isActuallyPlaying()) {
                    playStarted = true;
                    return true;
                }
                
                log('Auto-play attempt...');
                
                if (findAndClickPlayButton()) {
                    log('Play button clicked, will verify playback...');
                    // Verify after a short delay — only set playStarted if video actually plays
                    setTimeout(function() {
                        if (isActuallyPlaying()) {
                            playStarted = true;
                            log('Playback confirmed');
                        } else {
                            log('Click did not start playback, will retry');
                        }
                    }, 1500);
                    nuclearAttempts = 0;
                    return true;
                }
                
                // Try direct video.play()
                var videos = document.querySelectorAll('video');
                if (videos.length > 0) {
                    nuclearAttempts = 0;
                    videos.forEach(function(v) {
                        if (v.paused) {
                            v.muted = true;
                            v.volume = 0;
                            v.play().then(function() {
                                log('Video.play() succeeded');
                                playStarted = true;
                            }).catch(function(e) {
                                log('Video.play() blocked: ' + e.message);
                                simulateClick(v);
                            });
                        }
                    });
                } else {
                    var iframes = document.querySelectorAll('iframe');
                    if (iframes.length > 0 && clickCount < 5) {
                        log('No video found, clicking center (iframes present)');
                        clickCenterOfScreen();
                    } else if (iframes.length === 0) {
                        nuclearAttempts++;
                        if (nuclearAttempts > 2 && clickCount < 5) {
                            log('Nuclear: blind center click #' + nuclearAttempts);
                            clickCenterOfScreen();
                        }
                    }
                }
                return false;
            }
            
            // ===== AD SKIP =====
            function skipAds() {
                try {
                    // Speed up short videos (pre-roll ads)
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
                            simulateClick(btn);
                            log('Skipped ad: ' + sel);
                        }
                    });
                } catch(e) {}
            }
            
            // ===== VIDEO SOURCE EXTRACTION =====
            function isSegmentUrl(url) {
                if (url.startsWith('blob:')) return true;
                var patterns = [
                    /[\/\_-]seg(ment)?[0-9]/i, /[\/\_-]part[0-9]/i,
                    /[\/\_-]chunk[0-9]/i, /[\/\_-]frag[0-9]/i,
                    /[\/\_-]init\./i, /\.m4s(\?|$)/i,
                    /[\/\&?](start|end|byte|range)=/i,
                    /\/[0-9]+\/[^/]+\.(ts|m4s|aac)$/
                ];
                return patterns.some(function(p) { return p.test(url); });
            }
            
            function extractSources() {
                if (sourcesSent) return;
                var sources = [];
                
                // 1. Video elements
                document.querySelectorAll('video').forEach(function(v) {
                    if (v.src && v.src.length > 40 && !isSegmentUrl(v.src)) {
                        sources.push({url: v.src, label: 'Video'});
                    }
                    v.querySelectorAll('source').forEach(function(s) {
                        if (s.src && s.src.length > 40 && !isSegmentUrl(s.src)) {
                            sources.push({url: s.src, label: s.type || 'Source'});
                        }
                    });
                });
                
                // 2. JWPlayer
                try {
                    if (typeof jwplayer !== 'undefined') {
                        var player = jwplayer();
                        if (player && player.getPlaylistItem) {
                            var item = player.getPlaylistItem();
                            if (item && item.sources) {
                                item.sources.forEach(function(src) {
                                    if (src.file && src.file.length > 40 && !isSegmentUrl(src.file)) {
                                        sources.push({url: src.file, label: src.label || 'JW'});
                                    }
                                });
                            }
                        }
                    }
                } catch(e) {}
                
                // 3. Global player objects
                try {
                    if (window.player && window.player.src && !isSegmentUrl(window.player.src)) {
                        sources.push({url: window.player.src, label: 'Global'});
                    }
                    if (window.videojs && window.videojs.players) {
                        Object.values(window.videojs.players).forEach(function(p) {
                            if (p.src && p.src() && !isSegmentUrl(p.src())) {
                                sources.push({url: p.src(), label: 'VideoJS'});
                            }
                        });
                    }
                } catch(e) {}
                
                // 4. HLS.js
                try {
                    document.querySelectorAll('video').forEach(function(v) {
                        if (v.hls && v.hls.url && (v.hls.url.indexOf('.m3u8') !== -1 || v.hls.url.indexOf('http') === 0)) {
                            sources.push({url: v.hls.url, label: 'HLS.js'});
                        }
                    });
                    if (window.hls && window.hls.url && (window.hls.url.indexOf('.m3u8') !== -1 || window.hls.url.indexOf('http') === 0)) {
                        sources.push({url: window.hls.url, label: 'HLS.js Global'});
                    }
                } catch(e) {}
                
                // 5. DASH.js
                try {
                    if (window.dashjs && window.dashjs.MediaPlayer && window.dashjs.MediaPlayer.instances) {
                        window.dashjs.MediaPlayer.instances.forEach(function(p) {
                            if (p.getSource && p.getSource()) {
                                sources.push({url: p.getSource(), label: 'DASH.js'});
                            }
                        });
                    }
                } catch(e) {}
                
                // 6. Data attributes
                try {
                    document.querySelectorAll('[data-src], [data-url], [data-manifest]').forEach(function(el) {
                        var url = el.getAttribute('data-src') || el.getAttribute('data-url') || el.getAttribute('data-manifest');
                        if (url && url.length > 40 && !isSegmentUrl(url)) {
                            sources.push({url: url, label: 'Data Attr'});
                        }
                    });
                } catch(e) {}
                
                // Send via bridge
                if (sources.length > 0 && typeof SnifferBridge !== 'undefined') {
                    log('Found ' + sources.length + ' video sources');
                    sourcesSent = true;
                    SnifferBridge.onSourcesFound(JSON.stringify(sources));
                }
            }
            
            // ===== PLAY STATE DETECTION (for WebView-as-player fallback) =====
            // Exposed globally so Kotlin can call it via evaluateJavascript
            window.__snifferIsVideoPlaying = function() {
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    if (!v.paused && v.currentTime > 0 && v.readyState > 2) {
                        return true;
                    }
                }
                return false;
            };
            
            // ===== MUTATION OBSERVER =====
            var observer = new MutationObserver(function() {
                if (!playStarted) findAndClickPlayButton();
                extractSources();
            });
            
            observer.observe(document.body, {
                childList: true, subtree: true,
                attributes: true, attributeFilter: ['src', 'data-src']
            });
            
            // ===== EXECUTION =====
            log('Auto-click system active');
            
            // Immediate + retry schedule
            setTimeout(function() { if (!playStarted) attemptAutoPlay(); }, 500);
            
            [1000, 2000, 3000, 4000, 5000, 7000, 10000, 15000].forEach(function(delay) {
                setTimeout(function() {
                    if (!sourcesSent && !playStarted) {
                        log('Retry @' + (delay/1000) + 's');
                        attemptAutoPlay();
                        skipAds();
                        extractSources();
                    }
                }, delay);
            });
            
            // Continuous monitoring (no more clicking after play starts)
            setInterval(function() {
                if (!sourcesSent) {
                    skipAds();
                    extractSources();
                }
            }, 3000);
            
            return 'OK';
            } catch(err) {
                console.log('[VideoSniffer] Init error: ' + err.message);
                return 'ERROR: ' + err.message;
            }
        })();
    """.trimIndent()
}
