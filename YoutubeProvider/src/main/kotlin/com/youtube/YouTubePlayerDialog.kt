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
    private lateinit var btnScale: ImageButton
    private lateinit var btnExit: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var textCurrentTime: TextView
    private lateinit var textDuration: TextView

    // State
    private var isPlaying = true
    private var isVisible = false
    private var isDestroyed = false // Prevent calls after destroy
    private var isScaleCover = false // false = contain (fit), true = cover (zoom/fill)
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
        if (::overlayRoot.isInitialized) {
            rootLayout.addView(overlayRoot)
        } else {
            Log.e(TAG, "onCreate: overlayRoot not initialized (layout failure?)")
        }

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
            // Apply Match Parent with no padding/margin
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                
                // Remove any default padding from dialog
                decorView?.setPadding(0, 0, 0, 0)
                
                // For API 30+: Handle cutout/notch properly
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    attributes?.layoutInDisplayCutoutMode = 
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            // Immersive Mode - setDecorFitsSystemWindows(true) to properly handle insets
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // Use TRUE here so content fits within safe area, then hide bars
                window?.setDecorFitsSystemWindows(true)
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
                // Enable viewport meta tag support
                useWideViewPort = true
                loadWithOverviewMode = true
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



    // Externally injected resources from Plugin Context
    var pluginResources: android.content.res.Resources? = null
    var pluginPackageName: String = "com.youtube"

    private fun getResId(name: String, type: String): Int {
        val res = pluginResources ?: context.resources
        // 1. Try injected package name
        var id = res.getIdentifier(name, type, pluginPackageName)
        if (id != 0) return id
        
        // 2. Try explicit com.youtube (if injected was different)
        if (pluginPackageName != "com.youtube") {
             id = res.getIdentifier(name, type, "com.youtube")
             if (id != 0) return id
        }

        // 3. Fallback to host app
        id = res.getIdentifier(name, type, "com.lagradost.cloudstream3")
        
        if (id == 0) {
            Log.e(TAG, "getResId FAILED for $name type $type. Tried: $pluginPackageName, com.youtube, com.lagradost.cloudstream3")
        }
        return id
    }
    
    private fun createOverlayUI() {
        Log.d(TAG, "createOverlayUI: Building programmatic UI")
        
        // Root container - full screen overlay (transparent, no background to show video)
        overlayRoot = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x00000000) // Fully transparent - video shows through
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        
        // ===== TOP HEADER BAR (Close, Audio, Captions, Quality, Speed) =====
        val topBar = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(8))
            // Gradient background from black to transparent
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
        }
        
        // Helper to create header buttons (smaller, icon-based) with focus effect
        fun createHeaderButton(iconRes: Int, desc: String): ImageButton {
            // Create focus state drawable for header
            val normalBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x00000000) // Transparent
            }
            val focusedBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x33FFFFFF) // Light tint
                setStroke(dpToPx(2), 0xFFFF0000.toInt()) // Red border
            }
            val stateList = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedBg)
                addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
                addState(intArrayOf(), normalBg)
            }
            
            return ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply {
                    marginStart = dpToPx(8)
                }
                background = stateList
                setImageResource(iconRes)
                setColorFilter(0xFFFFFFFF.toInt())
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                contentDescription = desc
                isFocusable = true
                isFocusableInTouchMode = true
            }
        }
        
        btnSpeed = createHeaderButton(android.R.drawable.ic_menu_recent_history, "Speed")
        btnQuality = createHeaderButton(android.R.drawable.ic_menu_preferences, "Quality")
        btnCaptions = createHeaderButton(android.R.drawable.ic_menu_more, "Captions")
        btnScale = createHeaderButton(android.R.drawable.ic_menu_crop, "Scale: Fit") // Default Fit
        btnAudio = createHeaderButton(android.R.drawable.ic_lock_silent_mode_off, "Audio")
        btnExit = createHeaderButton(android.R.drawable.ic_menu_close_clear_cancel, "Close")
        
        topBar.addView(btnSpeed)
        topBar.addView(btnQuality)
        topBar.addView(btnCaptions)
        topBar.addView(btnScale) // Add before audio
        topBar.addView(btnAudio)
        topBar.addView(btnExit)
        
        // ===== CENTER PLAYBACK CONTROLS (Rewind, Play/Pause, Forward) =====
        val centerControls = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x00000000) // Transparent
        }
        
        // Create circular background drawable with focus state
        fun createCircleBackground(normalColor: Int, focusColor: Int, size: Int): android.graphics.drawable.StateListDrawable {
            val normalDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(normalColor)
            }
            val focusedDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x33FFFFFF) // Light translucent
                setStroke(dpToPx(3), focusColor) // Red border when focused
            }
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
                addState(intArrayOf(android.R.attr.state_pressed), focusedDrawable)
                addState(intArrayOf(), normalDrawable)
            }
        }
        
        // Helper to create large center buttons with circular bg and focus effect
        fun createCenterButton(iconRes: Int, desc: String, size: Int = 72): ImageButton {
            return ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(size), dpToPx(size)).apply {
                    marginStart = dpToPx(20)
                    marginEnd = dpToPx(20)
                }
                background = createCircleBackground(0x44000000, 0xFFFF0000.toInt(), size)
                setImageResource(iconRes)
                setColorFilter(0xFFFFFFFF.toInt())
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
                contentDescription = desc
                isFocusable = true
                isFocusableInTouchMode = true
            }
        }
        
        btnRewind = createCenterButton(android.R.drawable.ic_media_rew, "Rewind 10s", 64)
        btnPlayPause = createCenterButton(android.R.drawable.ic_media_pause, "Play/Pause", 88)
        btnForward = createCenterButton(android.R.drawable.ic_media_ff, "Forward 10s", 64)
        
        centerControls.addView(btnRewind)
        centerControls.addView(btnPlayPause)
        centerControls.addView(btnForward)
        
        // ===== BOTTOM BAR (Timeline/SeekBar + Time) =====
        val bottomBar = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(32))
            // Gradient background from transparent to black
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xDD000000.toInt(), 0x00000000)
            )
        }
        
        // Time display row
        val timeRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        
        textCurrentTime = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = "0:00"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        
        textDuration = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = "0:00"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 12f
        }
        
        timeRow.addView(textCurrentTime)
        timeRow.addView(spacer)
        timeRow.addView(textDuration)
        
        // Custom SeekBar with thin gray track + red progress + large red thumb
        seekBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(24) // Height for touch target
            ).apply {
                topMargin = dpToPx(8)
            }
            max = 100
            progress = 0
            isFocusable = true
            isFocusableInTouchMode = true
            
            // Create thin custom track drawable
            val trackHeight = dpToPx(3)
            val trackBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(2).toFloat()
                setColor(0x66FFFFFF) // Gray track
                setSize(0, trackHeight)
            }
            val trackProgress = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(2).toFloat()
                setColor(0xFFFF0000.toInt()) // Red progress
                setSize(0, trackHeight)
            }
            val clip = android.graphics.drawable.ClipDrawable(
                trackProgress, 
                Gravity.START, 
                android.graphics.drawable.ClipDrawable.HORIZONTAL
            )
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(trackBg, clip)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            
            // Large red thumb
            thumb = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFF0000.toInt())
                setSize(dpToPx(16), dpToPx(16))
                setStroke(dpToPx(2), 0xFFFFFFFF.toInt())
            }
            thumbOffset = dpToPx(8)
        }
        
        bottomBar.addView(seekBar) // SeekBar first (on top)
        bottomBar.addView(timeRow)  // Time below
        
        // Add all sections to overlay
        overlayRoot.addView(topBar)
        overlayRoot.addView(centerControls)
        overlayRoot.addView(bottomBar)
        
        Log.d(TAG, "createOverlayUI: Layout built successfully")
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

        btnScale.setOnClickListener {
            toggleScaleMode()
        }

        // SeekBar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    resetAutoHide() // Keep overlay visible while seeking
                    val time = (progress.toDouble() / 100.0) * videoDuration
                    textCurrentTime.text = formatTime(time.toInt())
                    textDuration.text = formatTime(videoDuration.toInt())
                    
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
        webView.loadUrl(url)
    }

    private fun injectFullscreenCSS() {
        Log.d(TAG, "injectFullscreenCSS: Injecting CSS with viewport fix")
        val inject = {
            val js = """
                (function() {
                    try {
                        // Inject viewport meta tag for proper scaling
                        var viewport = document.querySelector('meta[name="viewport"]');
                        if (!viewport) {
                            viewport = document.createElement('meta');
                            viewport.name = 'viewport';
                            document.head.appendChild(viewport);
                        }
                        viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                        
                        // CSS Content - aggressive fullscreen
                        var css = `
                          /* Reset everything */
                          * { box-sizing: border-box !important; }
                          
                          /* Full viewport container */
                          html, body {
                            background: #000 !important; 
                            width: 100% !important; height: 100% !important;
                            min-height: 100% !important;
                            margin: 0 !important; padding: 0 !important; 
                            overflow: hidden !important;
                            position: fixed !important;
                            top: 0 !important; left: 0 !important;
                          }
                          
                          /* YouTube's main container */
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
                          
                          /* VIDEO element - object-fit cover to fill, or contain to fit */
                          video, .video-stream, .html5-main-video {
                            position: absolute !important;
                            top: 0 !important; left: 0 !important;
                            width: 100% !important; height: 100% !important;
                            min-height: 100% !important;
                            object-fit: contain !important;
                            object-position: center center !important;
                            background: #000 !important;
                            z-index: 1 !important;
                          }
                          
                          /* Hide ALL YouTube chrome/UI elements */
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
            
            // Re-apply scale preference (Fit vs Fill)
            updateScaleMode()
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
                            textCurrentTime.text = formatTime(curr.toInt())
                            textDuration.text = formatTime(dur.toInt())
                        }
                        
                        // Force Icon Update if Mismatch
                        if (isPlaying == paused) { 
                            isPlaying = !paused
                            try {
                                btnPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                            } catch(e:Exception) {}
                        }
                        // Also proactively check if icon matches state to fix "wrong icon on start" bug 
                        // (e.g., if isPlaying=true but icon is Play)
                        // Note: setImageResource is lightweight, safe to call redundantly if needed, 
                        // but cleaner to check drawable constant? Hard with system resources.
                        // We rely on isPlaying state being accurate now.
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

    private fun toggleScaleMode() {
        isScaleCover = !isScaleCover
        updateScaleMode()
        
        val mode = if (isScaleCover) "Zoom/Fill" else "Fit to Screen"
        android.widget.Toast.makeText(context, mode, android.widget.Toast.LENGTH_SHORT).show()
        btnScale.contentDescription = "Scale: $mode"
    }

    private fun updateScaleMode() {
        val objectFit = if (isScaleCover) "cover" else "contain"
        val js = """
            (function() {
                try {
                    var style = document.getElementById('cloudstream-scale-mode');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'cloudstream-scale-mode';
                        document.head.appendChild(style);
                    }
                    // Use textContent to comply with Trusted Types (innerHTML is blocked)
                    style.textContent = 'video, .video-stream, .html5-main-video, .html5-video-container { object-fit: $objectFit !important; }';
                    console.log('Scaled video to: $objectFit');
                } catch(e) { console.error(e); }
            })();
        """
        if (!isDestroyed) {
             try { webView.evaluateJavascript(js, null) } catch(e: Exception) {}
        }
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

    private lateinit var btnAudio: ImageButton

    // ... inside createOverlayUI ...
    // btnAudio = view.findViewById(R.id.btn_audio)

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
            if (res != null) Log.d(TAG, "CAPTIONS_RESULT: $res")
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
    
    // ...

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
                   if (name.isEmpty()) name = t.optJSONObject("name")?.optString("simpleText") ?: ""
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
        if (!isDestroyed) {
             try {
                webView.evaluateJavascript("(function(){ $js })();", null)
             } catch(e: Exception) {
                 Log.e(TAG, "executeJs failed: ${e.message}")
             }
        }
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
        if (isDestroyed) return
        isDestroyed = true
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
