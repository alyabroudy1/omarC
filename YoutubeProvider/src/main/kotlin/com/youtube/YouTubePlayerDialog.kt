package com.youtube

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * A self-contained, plugin-ready YouTube Player Dialog.
 * Uses programmatic UI generation to avoid XML resource dependencies in plugins.
 */
class YouTubePlayerDialog(
    private val activity: Activity,
    private val url: String
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val TAG = "YouTubePlayerDialog"
    private val AUTO_HIDE_DELAY_MS = 5000L
    private val PROGRESS_UPDATE_INTERVAL_MS = 1000L
    private val handler = Handler(Looper.getMainLooper())
    private var previousOrientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // UI Components
    private lateinit var webView: WebView
    private lateinit var overlayRoot: ViewGroup
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnQuality: ImageButton
    private lateinit var btnSpeed: ImageButton
    private lateinit var btnCaptions: ImageButton
    private lateinit var btnAudio: ImageButton
    private lateinit var btnExit: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var textCurrentTime: TextView
    private lateinit var textDuration: TextView

    // State
    private var isPlaying = true
    private var isVisible = false
    private var isSeeking = false
    private var videoDuration = 0.0
    private var currentQuality = "auto"

    private val qualityOptions = arrayOf(
        "auto" to "Auto",
        "hd2160" to "4K (2160p)",
        "hd1440" to "1440p",
        "hd1080" to "1080p",
        "hd720" to "720p",
        "large" to "480p",
        "medium" to "360p",
        "small" to "240p",
        "tiny" to "144p"
    )
    private val speedValues = doubleArrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
    private val speedLabels = arrayOf("0.25x", "0.5x", "0.75x", "Normal", "1.25x", "1.5x", "1.75x", "2x")

    private val autoHideRunnable = Runnable { hideOverlay() }
    private val progressUpdateRunnable = Runnable { updateProgress() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Initializing YouTubePlayerDialog")
        
        // 1. Force Landscape (SENSOR_LANDSCAPE is value 6)
        forceLandscape()
        
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // build UI
        val rootLayout = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        initializeWebView()
        rootLayout.addView(webView)

        createOverlayUI()
        rootLayout.addView(overlayRoot)

        setContentView(rootLayout)

        setupListeners()
        loadVideo()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Applying Layout and Immersive Mode")
        // 1. Force Landscape STRICT
        forceLandscape()
        
        try {
            // Apply Match Parent
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))

            // Immersive Mode
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window?.setDecorFitsSystemWindows(false)
                window?.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                window?.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window?.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            }
            
            window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            
            // Watchdog: If layout happens and we are seemingly in portrait, force again.
            window?.decorView?.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val w = right - left
                val h = bottom - top
                if (h > w) { // Portrait usage detected
                    Log.d(TAG, "Layout Listener: Portrait dimensions detected ($w x $h) - Re-forcing LANDSCAPE")
                    forceLandscape()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "onStart: Failed to apply layout/immersive settings", e)
        }
    }

    private fun forceLandscape() {
        try {
            val act = scanForActivity(context)
            if (act != null) {
                if (previousOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    previousOrientation = act.requestedOrientation
                }
                if (act.requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    Log.d(TAG, "forceLandscape: Requested STRICT LANDSCAPE on ${act.localClassName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "forceLandscape: Failed", e)
        }
    }

    private fun scanForActivity(cont: android.content.Context?): Activity? {
        if (cont == null) return null
        if (cont is Activity) return cont
        if (cont is android.content.ContextWrapper) return scanForActivity(cont.baseContext)
        return null
    }

    private fun initializeWebView() {
        Log.d(TAG, "initializeWebView: Setting up WebView")
        webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(TAG, "JS Console: ${consoleMessage?.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                   Log.d(TAG, "onPageFinished: URL loaded $url")
                   
                   if (url?.contains("consent.youtube.com") == true) {
                       Log.d(TAG, "Detected Consent Screen - Attempting Auto-Accept")
                       val consentJs = """
                           (function() {
                               var buttons = document.querySelectorAll('button');
                               for (var i = 0; i < buttons.length; i++) {
                                   var t = buttons[i].innerText.toLowerCase();
                                   if (t.includes('accept') || t.includes('agree') || t.includes('mein zustimmen')) {
                                       console.log('Clicking consent button: ' + t);
                                       buttons[i].click();
                                       return;
                                   }
                               }
                               // Fallback form submit
                               var form = document.querySelector('form[action*="consent"]');
                               if (form) { 
                                   console.log('Submitting consent form');
                                   form.submit(); 
                               }
                           })();
                       """
                       view?.evaluateJavascript(consentJs, null)
                   } else {
                       injectFullscreenCSS()
                       showOverlay()
                       dumpDiagnostics()
                   }
                }
            }
        }
    }

    private fun createOverlayUI() {
        Log.d(TAG, "createOverlayUI: Inflating XML layout")
        
        // Inflate the strict XML layout
        val view = layoutInflater.inflate(R.layout.dialog_youtube_player, null)
        overlayRoot = view as ViewGroup
        overlayRoot.visibility = View.GONE
        overlayRoot.isClickable = true
        overlayRoot.isFocusable = true
        
        // Bind Views
        btnPlayPause = view.findViewById(R.id.btn_play_pause)
        btnRewind = view.findViewById(R.id.btn_rewind)
        btnForward = view.findViewById(R.id.btn_forward)
        
        // Extended Controls
        btnSpeed = view.findViewById(R.id.btn_speed)
        btnQuality = view.findViewById(R.id.btn_quality) // was btn_settings
        btnCaptions = view.findViewById(R.id.btn_captions)
        btnAudio = view.findViewById(R.id.btn_audio)
        btnExit = view.findViewById(R.id.btn_close)
        
        seekBar = view.findViewById(R.id.seekbar)
        textCurrentTime = view.findViewById(R.id.text_time)
        textDuration = TextView(context) 
        
        // Ensure standard accessibility references
        btnPlayPause.contentDescription = "Play"
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause) // Default to Pause icon as we autoplay
        btnExit.contentDescription = "Close"
        btnSpeed.contentDescription = "Speed"
        btnQuality.contentDescription = "Quality"
        btnCaptions.contentDescription = "Captions"
    }

    private var seekDebouncerRunnable: Runnable? = null
    private var isTouching = false

    private fun setupListeners() {
        btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        btnRewind.setOnClickListener {
            seekRelative(-10)
        }

        btnForward.setOnClickListener {
            seekRelative(10)
        }

        btnSpeed.setOnClickListener {
            showSpeedMenu()
        }

        btnQuality.setOnClickListener {
           showQualityDialog()
        }
        
        btnExit.setOnClickListener {
            dismiss()
        }
        
        btnCaptions.setOnClickListener {
            showCaptionMenu()
        }
        
        btnAudio.setOnClickListener {
            showAudioMenu()
        }

        // SeekBar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    resetAutoHide() // Keep overlay visible while seeking
                    val time = (progress.toDouble() / 100.0) * videoDuration
                    textCurrentTime.text = formatTime(time.toInt()) + " / " + formatTime(videoDuration.toInt())
                    
                    if (!isTouching) {
                        // D-Pad Seeking detected
                        isSeeking = true
                        
                        // Debounce seek
                        seekDebouncerRunnable?.let { handler.removeCallbacks(it) }
                        seekDebouncerRunnable = Runnable {
                            seekTo(time)
                            isSeeking = false
                        }
                        handler.postDelayed(seekDebouncerRunnable!!, 500)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                resetAutoHide()
                isSeeking = true
                isTouching = true
                seekDebouncerRunnable?.let { handler.removeCallbacks(it) }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                isTouching = false
                seekDebouncerRunnable?.let { handler.removeCallbacks(it) }
                
                val progress = seekBar?.progress ?: 0
                val time = (progress.toDouble() / 100.0) * videoDuration
                seekTo(time)
            }
        })
        
        overlayRoot.setOnClickListener {
            if (isVisible) {
                // Clicking opacity background hides it
                hideOverlay() 
            } else {
                 showOverlay()
            }
        }
        
        // Critical: Tap on WebView to show overlay
        webView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (!isVisible) {
                    showOverlay()
                } else {
                    hideOverlay()
                }
            }
            false // Propagate click
        }
    }

    private fun loadVideo() {
        Log.d(TAG, "loadVideo: $url")
        // User Requested Test Video
        val testUrl = "https://www.youtube.com/watch?v=pAnGwRiQ4-4"
        webView.loadUrl(testUrl)
    }

    private fun injectFullscreenCSS() {
        Log.d(TAG, "injectFullscreenCSS: Injecting CSS with Trusted Types Fix")
        val inject = {
            val js = """
                (function() {
                    try {
                        // CSS Content
                        var css = `
                          html, body, #player, .player-container, .html5-video-container, video, .video-stream {
                            background: #000 !important; width: 100vw !important; height: 100vh !important;
                            margin: 0 !important; padding: 0 !important; overflow: hidden !important;
                            position: fixed !important; top: 0 !important; left: 0 !important; z-index: 9999 !important;
                          }
                          .mobile-topbar-header, .player-controls-top, .watch-below-the-player, 
                          .ytp-chrome-top, .ytp-chrome-bottom, .ad-showing, .video-ads, .ytp-ad-overlay-container,
                          .ytp-upnext, .ytp-suggestion-set, .ytp-share-panel, .ytp-watermark { 
                              display: none !important; opacity: 0 !important; pointer-events: none !important; 
                          }
                          video { opacity: 1 !important; visibility: visible !important; }
                          
                          /* Force Captions Visible */
                          .caption-window, .ytp-caption-window-container, .ytp-caption-segment {
                              display: block !important;
                              opacity: 1 !important;
                              visibility: visible !important;
                              z-index: 20000 !important;
                          }
                        `;

                        // 1. Create Style Element
                        var style = document.createElement('style');
                        
                        // 2. Use createTextNode to bypass Trusted Types internalHTML blocks
                        style.appendChild(document.createTextNode(css));
                        
                        // 3. Append to head
                        document.head.appendChild(style);
                        console.log("CSS Injected Successfully");

                        // Force Unmute & Play
                        var vid = document.querySelector('video');
                        if(vid) { vid.muted = false; vid.play(); }
                        
                    } catch(e) {
                        console.error("CSS Injection Failed: " + e.message);
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
        inject()
        handler.postDelayed({ inject() }, 1500)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Directional Keys: Smart Wake
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isVisible) {
                        showOverlay(focusPlayButton = false) // Don't focus play, we want text/seek
                        seekBar.requestFocus()
                        // Return super so the key press is processed by the SeekBar (starts seeking immediately)
                        return super.dispatchKeyEvent(event)
                    }
                }
                
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!isVisible) {
                        showOverlay(focusPlayButton = true)
                        return true
                    }
                }
                android.view.KeyEvent.KEYCODE_BACK -> {
                    if (isVisible) {
                        hideOverlay()
                        return true
                    } else {
                        dismiss()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showOverlay(focusPlayButton: Boolean = true) {
        if (!isVisible) {
            overlayRoot.visibility = View.VISIBLE
            overlayRoot.animate().alpha(1f).setDuration(200).setListener(null)
            
            // TV Support: Focus Play button when overlay appears, unless overridden
            if (focusPlayButton) {
                btnPlayPause.requestFocus()
            }
            
            isVisible = true
            startProgressUpdates()
            syncCurrentSettings()
        }
        resetAutoHide()
    }

    private fun hideOverlay() {
        if (isVisible) {
            val anim = AlphaAnimation(1f, 0f).apply {
                duration = 200
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationEnd(a: Animation?) { overlayRoot.visibility = View.GONE }
                    override fun onAnimationStart(a: Animation?) {}
                    override fun onAnimationRepeat(a: Animation?) {}
                })
            }
            overlayRoot.startAnimation(anim)
            isVisible = false
            stopProgressUpdates()
        }
    }

    private fun resetAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun togglePlayPause() {
        resetAutoHide()
        isPlaying = !isPlaying
        // Safely try cloudstream icons, else fallback (could use text or standard)
        try {
            btnPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        } catch(e:Exception) {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play) // Standard fallback
        }
        
        executeJs("var v = document.querySelector('video'); if (v) { ${if (isPlaying) "v.play()" else "v.pause()"}; }")
        showToast(if (isPlaying) "Playing" else "Paused")
    }

    private fun seekRelative(seconds: Int) {
        resetAutoHide()
        executeJs("var v = document.querySelector('video'); if(v) { v.currentTime += $seconds; }")
        showToast(if (seconds > 0) "+10s" else "-10s")
    }

    private fun seekTo(time: Double) {
        executeJs("var v = document.querySelector('video'); if(v) { v.currentTime = $time; }")
    }

    private fun startProgressUpdates() {
        handler.postDelayed(progressUpdateRunnable, PROGRESS_UPDATE_INTERVAL_MS)
    }
    
    private fun stopProgressUpdates() {
        handler.removeCallbacks(progressUpdateRunnable)
    }

    private fun updateProgress() {
        if (!isVisible && !isPlaying) return 
        
        // Simplified Update Progress (Ad logic moved to injected script)
        val js = """
            (function() {
                // Auto-Skip Ads
                var skipBtn = document.querySelector('.ytp-ad-skip-button');
                if (skipBtn) { skipBtn.click(); return 'skipped'; }
                var adOverlay = document.querySelector('.ytp-ad-overlay-close-button');
                if (adOverlay) { adOverlay.click(); }
                
                var v = document.querySelector('video');
                if (v) {
                    return JSON.stringify({
                        curr: v.currentTime,
                        dur: v.duration,
                        paused: v.paused,
                        rate: v.playbackRate
                    });
                }
                return null;
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { res ->
            if (res == "\"skipped\"") {
                showToast("Ad Skipped automatically")
            } else if (res != null && res != "null") {
                try {
                    val k = JSONObject(res.replace("\\\"", "\"").removeSurrounding("\""))
                    val curr = k.optDouble("curr")
                    val dur = k.optDouble("dur")
                    val paused = k.optBoolean("paused")
                    
                    videoDuration = dur
                    
                    handler.post {
                        if (!isSeeking && dur > 0) {
                            seekBar.progress = ((curr / dur) * 100).toInt()
                            // FIX: Combine Current and Duration
                            textCurrentTime.text = formatTime(curr.toInt()) + " / " + formatTime(dur.toInt())
                        }
                        
                        // Force Icon Update if Mismatch
                        if (isPlaying == paused) { 
                            isPlaying = !paused
                            try {
                                btnPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                            } catch(e:Exception) {}
                        }
                    }
                } catch(e: Exception) {}
            }
        }
        
        handler.postDelayed(progressUpdateRunnable, PROGRESS_UPDATE_INTERVAL_MS)
    }

    private var currentSpeed: Double = 1.0

    private fun showQualityDialog() {
        val names = qualityOptions.map { it.second }.toTypedArray()
        val checkedItem = qualityOptions.indexOfFirst { it.first == currentQuality }.coerceAtLeast(0)

        val builder = android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Quality")
        builder.setSingleChoiceItems(names, checkedItem) { dialog, which ->
            val (v, l) = qualityOptions[which]
            setQuality(v, l)
            dialog.dismiss()
        }
        builder.show()
    }

    private fun setQuality(q: String, l: String) {
        executeJs("var p = document.getElementById('movie_player'); if(p && p.setPlaybackQualityRange) { p.setPlaybackQualityRange('$q'); }")
        showToast("Quality: $l")
    }

    private fun showSpeedMenu() {
        resetAutoHide()
        val labels = speedLabels
        // Speed Highlighting
        val checkedItem = speedValues.indexOfFirst { it == currentSpeed }.coerceAtLeast(3) // Default to 1.0x (index 3) if mismatch

        val builder = android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Playback Speed")
        builder.setSingleChoiceItems(labels, checkedItem) { dialog, which ->
            setSpeed(speedValues[which])
            dialog.dismiss()
        }
        builder.show()
    }
    
    private fun setSpeed(rate: Double) {
        currentSpeed = rate
        executeJs("var v = document.querySelector('video'); if(v) { v.playbackRate = $rate; }")
        showToast("Speed: ${rate}x")
    }

    private fun showCaptionMenu() {
        // Parse ytInitialPlayerResponse directly as API is unreliable on Mobile
        val js = """
            (function() {
                try {
                    // Source 1: API (Preferred if available)
                    var apiTracks = [];
                    var p = document.querySelector('#movie_player');
                    if (p && typeof p.getOption === 'function') {
                        apiTracks = p.getOption('captions', 'tracklist') || [];
                    }
                    if (apiTracks.length > 0) return JSON.stringify(apiTracks);

                    // Source 2: ytInitialPlayerResponse (Backup)
                    if (window.ytInitialPlayerResponse && 
                        window.ytInitialPlayerResponse.captions && 
                        window.ytInitialPlayerResponse.captions.playerCaptionsTracklistRenderer &&
                        window.ytInitialPlayerResponse.captions.playerCaptionsTracklistRenderer.captionTracks) {
                        
                        var raw = window.ytInitialPlayerResponse.captions.playerCaptionsTracklistRenderer.captionTracks;
                        return JSON.stringify(raw);
                    }
                    return "[]";
                } catch(e) {
                    return "[]";
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { res ->
            val tracks = ArrayList<String>()
            val trackCodes = ArrayList<String>() // format: "code" or "translate:code"

            // 1. Off
            tracks.add("⏸ Off")
            trackCodes.add("off")

            try {
                // Parse Tracks
                val json = res?.replace("\\\"", "\"")?.removeSurrounding("\"") ?: "[]"
                val jsonArray = org.json.JSONArray(json)
                
                for (i in 0 until jsonArray.length()) {
                    val t = jsonArray.getJSONObject(i)
                    val name = t.optString("displayName", t.optString("name", t.optString("languageName", "?")))
                    val code = t.optString("languageCode", "")
                    if (code.isNotEmpty()) {
                        tracks.add("\uD83D\uDCAC $name") // Speech Bubble
                        trackCodes.add(code)
                    }
                }
                
                // Add Common Translates (if not present)
                tracks.add("🌐 Translate to Arabic")
                trackCodes.add("translate:ar")
                
                tracks.add("🌐 Translate to English")
                trackCodes.add("translate:en")

            } catch (e: Exception) {
                // Fallback if parsing fails
                tracks.add("🇸🇦 Arabic")
                trackCodes.add("ar")
                tracks.add("🇺🇸 English")
                trackCodes.add("en")
            }
            
            // Show Dialog
            android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Captions")
                .setItems(tracks.toTypedArray()) { _, which ->
                    val code = trackCodes.getOrElse(which) { "off" }
                    if (code == "off") {
                        setCaptions(false, null, null)
                    } else if (code.startsWith("translate:")) {
                        val target = code.substringAfter(":")
                        setCaptions(true, null, target)
                    } else {
                        setCaptions(true, code, null)
                    }
                }
                .show()
        }
    }

    private fun setCaptions(enabled: Boolean, languageCode: String?, translateTo: String?) {
        val js = """
            (function() { 
                var result = {method: 'none', success: false, tracks: [], error: ''}; 
                try { 
                    var player = document.querySelector('#movie_player'); 
                    
                    if (player && player.loadModule) { 
                        player.loadModule('captions'); 
                        player.loadModule('cc'); 
                    }

                    var tracks = [];
                    if (window.ytInitialPlayerResponse && 
                        window.ytInitialPlayerResponse.captions && 
                        window.ytInitialPlayerResponse.captions.playerCaptionsTracklistRenderer &&
                        window.ytInitialPlayerResponse.captions.playerCaptionsTracklistRenderer.captionTracks) {
                        tracks = window.ytInitialPlayerResponse.captions.playerCaptionsTracklistRenderer.captionTracks;
                    }
                    if (tracks.length === 0 && player && player.getOption) {
                        tracks = player.getOption('captions', 'tracklist') || [];
                    }

                    if (tracks && tracks.length > 0) { 
                        if (${enabled}) {
                            if ('${translateTo ?: ""}' !== '') {
                                // Translate Mode:
                                // Find a translatable source (prefer English, check is_translateable)
                                var source = tracks.find(function(t) { return (t.languageCode === 'en' || t.languageCode.indexOf('en') === 0) && t.is_translateable; }) 
                                          || tracks.find(function(t) { return t.is_translateable; }) 
                                          || tracks[0];
                                
                                if (source) {
                                    player.setOption('captions', 'track', {languageCode: source.languageCode}); 
                                    try {
                                         player.setOption('captions', 'translationLanguage', {languageCode: '${translateTo}'}); 
                                    } catch(e) { console.log('Trans set failed'); }
                                    
                                    result = {method: 'yt-api-translate', success: true, lang: '${translateTo}', source: source.languageCode}; 
                                } else {
                                    result.error = "No translatable source found";
                                }
                            } else {
                                // Specific Language
                                var code = '${languageCode ?: "en"}';
                                var track = tracks.find(function(t) { return t.languageCode === code; }) || tracks[0]; 
                                player.setOption('captions', 'track', {languageCode: track.languageCode}); 
                                result = {method: 'yt-api-track', success: true, lang: track.languageCode}; 
                            }
                        } else {
                            player.setOption('captions', 'track', {}); 
                            result = {method: 'yt-api-off', success: true}; 
                        }
                        
                         try {
                                var ccBtn = document.querySelector('.ytp-subtitles-button');
                                if (ccBtn) {
                                    var pressed = ccBtn.getAttribute('aria-pressed') === 'true';
                                    if (${enabled} !== pressed) ccBtn.click();
                                }
                         } catch(e) { } 
                        
                        return JSON.stringify(result);
                    } else {
                        result.error = "No tracks found via JSON/API";
                    }
                    
                } catch(e) { 
                    result.error = e.message; 
                } 
                return JSON.stringify(result); 
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { res ->
            if (res != null && (res.contains("success\":true") || res.contains("success\": true"))) {
                 val mode = if (!enabled) "OFF" else (translateTo?.uppercase() ?: languageCode?.uppercase() ?: "ON")
                 showToast("Captions: $mode")
                 btnCaptions.alpha = if (enabled) 1.0f else 0.5f
            } else {
                 var errorMsg = "Unavailable"
                 try {
                     val json = org.json.JSONObject(res ?: "{}")
                     if (json.has("error")) errorMsg = json.getString("error")
                 } catch(e: Exception) {}
                 showToast("Set Failed: $errorMsg")
            }
        }
    }

    private fun dumpDiagnostics() {
        val js = """
            (function() {
                var report = {};
                try {
                    var p = document.querySelector('#movie_player');
                    report.hasPlayer = !!p;
                    if (p) {
                        report.hasGetOption = typeof p.getOption === 'function';
                        report.hasGetAudioTrack = typeof p.getAudioTrack === 'function';
                        if (report.hasGetOption) {
                            report.captions = p.getOption('captions', 'tracklist');
                            report.audioMatches = p.getOption('audioTrack', 'tracklist');
                        }
                        report.apiInterface = Object.keys(p).filter(k => typeof p[k] === 'function').slice(0, 20);
                    }
                    
                    var v = document.querySelector('video');
                    report.hasVideo = !!v;
                    if (v) {
                        report.audioTracks = v.audioTracks ? v.audioTracks.length : 'undefined';
                    }
                    
                    if (window.ytInitialPlayerResponse) {
                         var r = window.ytInitialPlayerResponse;
                         if (r.captions) report.responseCaptions = 'found';
                         if (r.streamingData) report.responseStreaming = 'found';
                         if (r.ps) report.playerResponse_ps = r.ps;
                    }
                    
                    if (window.yt && window.yt.config_) {
                        report.ytConfig = Object.keys(window.yt.config_);
                    }
                } catch(e) {
                    report.error = e.message;
                }
                
                console.log('DIAGNOSTICS_JSON: ' + JSON.stringify(report));
                return JSON.stringify(report);
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { res ->
            Log.d(TAG, "DIAGNOSTICS_CALLBACK: $res")
        }
    }

    private fun showAudioMenu() {
       val js = """
            (function() {
                try {
                    // Source 1: API
                    var p = document.querySelector('#movie_player');
                    if (p && typeof p.getOption === 'function') {
                        var tracks = p.getOption('audioTrack', 'tracklist');
                        if (tracks && tracks.length > 0) return JSON.stringify(tracks);
                    }
                    
                    // Source 2: ytInitialPlayerResponse
                    if (window.ytInitialPlayerResponse && 
                        window.ytInitialPlayerResponse.captions && 
                        window.ytInitialPlayerResponse.captions.playerCaptionsTracklistRenderer &&
                        window.ytInitialPlayerResponse.captions.playerCaptionsTracklistRenderer.audioTracks) {
                        
                        // Return FULL objects
                        return JSON.stringify(window.ytInitialPlayerResponse.captions.playerCaptionsTracklistRenderer.audioTracks);
                    }
                    
                    return "[]";
                } catch(e) {
                    return "[]";
                }
            })();
       """.trimIndent()
       
       webView.evaluateJavascript(js) { res ->
           try {
               val json = res?.replace("\\\"", "\"")?.removeSurrounding("\"") ?: "[]"
               val jsonArray = org.json.JSONArray(json)
               
               if (jsonArray.length() == 0) {
                   showToast("No Audio Tracks Available")
                   return@evaluateJavascript
               }

               val labels = ArrayList<String>()
               val trackJsons = ArrayList<String>()

               for (i in 0 until jsonArray.length()) {
                   val t = jsonArray.getJSONObject(i)
                   
                   // Name Extraction Strategy
                   var name = t.optString("displayName")
                   if (name.isEmpty()) name = t.optJSONObject("name")?.optString("simpleText")
                   if (name.isNullOrEmpty()) name = t.optString("label") // Common fallback
                   if (name.isNullOrEmpty()) name = t.optString("languageName") // Another fallback
                   if (name.isNullOrEmpty()) name = t.optString("name") // Another fallback
                   
                   if (name.isNullOrEmpty()) {
                       // Last Resort: Formatted Language Code
                       val code = t.optString("languageCode", "").uppercase()
                       if (code.isNotEmpty()) name = "Track $code"
                       else name = "Track ${i + 1}"
                   }
                   
                   labels.add("\uD83D\uDD0A $name")
                   trackJsons.add(t.toString())
               }

               android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Audio Track")
                .setItems(labels.toTypedArray()) { _, which ->
                    val trackJson = trackJsons[which]
                    val trackName = labels[which]
                    showToast("Selecting: $trackName") // Immediate feedback
                    setAudioTrack(trackJson)
                }
                .show()

           } catch(e: Exception) {
               showToast("Audio Tracks Unavailable")
           }
       }
    }
    
    private fun setAudioTrack(trackJson: String) {
        val safeJson = trackJson.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function() {
                var res = {success: false, method: 'none'};
                try {
                    var p = document.querySelector('#movie_player');
                    var track = JSON.parse('$safeJson');
                    var id = track.id || ''; // Extract ID safely inside JS
                    
                    // Method 1: setAudioTrack (Direct Object)
                     if (typeof p.setAudioTrack === 'function') {
                         p.setAudioTrack(track); // Try whole object
                         res = {success: true, method: 'direct_setAudioTrack_obj'};
                    }
                    
                    // Method 2: setAudioTrack (ID)
                    if (!res.success && typeof p.setAudioTrack === 'function' && id) {
                         p.setAudioTrack(id); 
                         res = {success: true, method: 'direct_setAudioTrack_id'};
                    }
                    
                    // Method 3: Standard setOption
                    if (!res.success && typeof p.setOption === 'function') {
                        p.setOption('audioTrack', 'track', track);
                        res = {success: true, method: 'setOption_standard'};
                    }
                    
                    // Method 4: properties
                     if (!res.success && p.audioTrack !== undefined) {
                        p.audioTrack = track;
                        res = {success: true, method: 'prop_audioTrack'};
                     }

                } catch(e) {
                    res.error = e.message;
                }
                return JSON.stringify(res);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { res ->
            showToast("Audio: $res")
        }
    }
    
    // Stats for Nerds (Video Info)
 
    private fun syncCurrentSettings() {
        // Implementation similar to overlay class
    }

    private fun executeJs(js: String) {
        webView.evaluateJavascript("(function(){ $js })();", null)
    }

    private fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 100)
        }.show()
    }

    private fun formatTime(sec: Int): String {
        return if (sec >= 3600) 
            "%d:%02d:%02d".format(sec/3600, (sec%3600)/60, sec%60)
        else 
            "%d:%02d".format((sec%3600)/60, sec%60)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun showSeekFeedback(time: Double) {
        showToast("⏱ ${formatTime(time.toInt())}")
    }

    override fun dismiss() {
        Log.d(TAG, "dismiss: destroying player")
        try {
            activity.requestedOrientation = previousOrientation // Restore orientation
        } catch(e: Exception) {}
        webView.loadUrl("about:blank")
        webView.destroy()
        handler.removeCallbacksAndMessages(null)
        super.dismiss()
    }
}
