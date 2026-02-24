package com.cloudstream.shared.webview

/**
 * JavaScript snippets for video sniffing.
 *
 * Contains the auto-play, ad-skip, player detection, and source extraction
 * JavaScript that is injected into web pages during video sniffing sessions.
 */
object VideoSnifferJs {

    const val MIN_VIDEO_URL_LENGTH = 50

    val JS_SCRIPT = """
        (function() {
            console.log('[VideoSniffer] Script starting...');
            var sourcesSent = false;
            var playStarted = false;
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
                    // Overlays - COMMENTED OUT to avoid clicking anti-bot overlays like site-overlay
                    // '.player-overlay', '.video-overlay', '.play-overlay', '[class*="overlay"]',
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
                    playStarted = true; // Stop all further auto-clicking
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
            function isSegmentUrl(url) {
                // Ignore blob URLs (MediaSource segments)
                if (url.startsWith('blob:')) return true;
                
                // Ignore segment/chunk/fragment patterns
                var segmentPatterns = [
                    /[\\/_-]seg(ment)?[0-9]/i,
                    /[\\/_-]part[0-9]/i,
                    /[\\/_-]chunk[0-9]/i,
                    /[\\/_-]frag[0-9]/i,
                    /[\\/_-]init\./i,
                    /\.m4s(\?|$)/i,
                    /[\/&?](start|end|byte|range)=/i,
                    /\/[0-9]+\/[^/]+\.(ts|m4s|aac)$/
                ];
                
                return segmentPatterns.some(function(pattern) {
                    return pattern.test(url);
                });
            }
            
            function extractSources() {
                if (sourcesSent) return;
                
                var sources = [];
                
                // Method 1: Network-intercepted sources (already captured by Kotlin)
                
                // Method 2: Video element sources
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
                
                // Method 3: JWPlayer
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
                
                // Method 4: Global player objects
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
                
                // Method 5: HLS.js - Extract manifest URL when using MediaSource
                try {
                    // Check for hls.js instances
                    if (window.Hls && window.Hls.prototype) {
                        // Try to find hls instance on video element
                        document.querySelectorAll('video').forEach(function(v) {
                            if (v.hls && v.hls.url) {
                                var url = v.hls.url;
                                log('Found HLS.js manifest on video element: ' + url);
                                if (url.indexOf('.m3u8') !== -1 || url.indexOf('http') === 0) {
                                    sources.push({url: url, label: 'HLS.js'});
                                }
                            }
                        });
                    }
                    // Check global hls instance
                    if (window.hls && window.hls.url) {
                        log('Found global HLS.js instance: ' + window.hls.url);
                        if (window.hls.url.indexOf('.m3u8') !== -1 || window.hls.url.indexOf('http') === 0) {
                            sources.push({url: window.hls.url, label: 'HLS.js Global'});
                        }
                    }
                    // Check for hls configs
                    if (window.hls && window.hls.config && window.hls.config.url) {
                        var url = window.hls.config.url;
                        log('Found HLS.js config URL: ' + url);
                        if (url.indexOf('.m3u8') !== -1 || url.indexOf('http') === 0) {
                            sources.push({url: url, label: 'HLS.js Config'});
                        }
                    }
                } catch(e) {
                    log('HLS.js extraction error: ' + e.message);
                }
                
                // Method 6: DASH.js - Extract manifest URL
                try {
                    if (window.dashjs && window.dashjs.MediaPlayer) {
                        var players = window.dashjs.MediaPlayer.instances;
                        if (players && players.length > 0) {
                            players.forEach(function(player) {
                                if (player.getSource && player.getSource()) {
                                    var url = player.getSource();
                                    log('Found DASH.js manifest: ' + url);
                                    if (url.indexOf('.mpd') !== -1 || url.indexOf('http') === 0) {
                                        sources.push({url: url, label: 'DASH.js'});
                                    }
                                }
                            });
                        }
                    }
                } catch(e) {
                    log('DASH.js extraction error: ' + e.message);
                }
                
                // Method 7: Look for source in data attributes
                try {
                    document.querySelectorAll('[data-src], [data-url], [data-manifest]').forEach(function(el) {
                        var url = el.getAttribute('data-src') || el.getAttribute('data-url') || el.getAttribute('data-manifest');
                        if (url && url.length > 40 && !isSegmentUrl(url)) {
                            log('Found source in data attribute: ' + url.substring(0, 60));
                            sources.push({url: url, label: 'Data Attr'});
                        }
                    });
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
                if (!playStarted) findAndClickPlayButton();
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
            setTimeout(function() { if (!playStarted) attemptAutoPlay(); }, 500);
            
            // Retry with increasing delays — stop once play started
            [1000, 2000, 3000, 4000, 5000, 7000, 10000, 15000].forEach(function(delay) {
                setTimeout(function() {
                    if (!sourcesSent && !playStarted) {
                        log('Retry #' + (delay/1000) + 's');
                        attemptAutoPlay();
                        skipAds();
                        extractSources();
                    }
                }, delay);
            });
            
            // Continuous monitoring — NO more auto-clicking after play starts
            setInterval(function() {
                if (!sourcesSent) {
                    skipAds();
                    extractSources();
                }
            }, 3000);
            
            log('Auto-click system active - waiting for player...');
        })();
    """.trimIndent()
}
