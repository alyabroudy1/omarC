package com.cloudstream.shared.ui.player

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog

/**
 * A clean, production-ready YouTube WebView Player orchestrator.
 * Connects the native UI ([YouTubeUIController]) with the JS bridge ([YouTubeJsBridge]).
 */
class YouTubePlayer(
    private val activity: Activity,
    private val url: String
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val TAG = "YouTubePlayer"
    private val AUTO_HIDE_DELAY_MS = 5000L
    private val PROGRESS_UPDATE_INTERVAL_MS = 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var currentSpeedIndex = 3
    private var currentQualityIndex = 0
    private var currentCaptionIndex = 0
    private lateinit var webView: WebView
    private lateinit var ui: YouTubeUIController
    private lateinit var jsBridge: YouTubeJsBridge

    private var isPlaying = true
    private var isSeeking = false
    private var videoDuration = 0.0
    private var isScaleCover = false
    private var previousOrientation: Int = -999
    private var hasFetchedMetadata = false

    private val autoHideRunnable = Runnable { ui.hide() }
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            if (ui.isVisible || isPlaying) {
                jsBridge.pollPlaybackState { currentTime, duration, paused ->
                    if (duration > 0) videoDuration = duration
                    if (!isSeeking) {
                        ui.updateProgress(currentTime, duration)
                    }
                    if (isPlaying == paused) { // Sync state if it drifted
                        isPlaying = !paused
                        ui.setPlayingUI(isPlaying)
                    }
                }
                if (!hasFetchedMetadata) {
                    jsBridge.fetchMetadata { title, channel, avatar ->
                        if (title.isNotEmpty()) {
                            hasFetchedMetadata = true
                            ui.textTitle.text = title
                            if (channel.isNotEmpty()) ui.textChannel.text = channel
                        }
                    }
                }
            }
            handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forceLandscape()
        setupWindowFlags()

        val rootLayout = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        setupWebView()
        rootLayout.addView(webView)

        ui = YouTubeUIController(context)
        rootLayout.addView(ui.rootView)

        setContentView(rootLayout)
        setupListeners()
        
        val finalUrl = if (url.contains("?")) "$url&cc_load_policy=1&cc_lang_pref=ar&hl=ar" else "$url?cc_load_policy=1&cc_lang_pref=ar&hl=ar"
        
        // Pre-approve GDPR consent to avoid the "black screen" redirect
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val consentCookie = "CONSENT=YES+cb.20230101-08-p0.en+FX+0;"
            cookieManager.setCookie("https://youtube.com", consentCookie)
            cookieManager.setCookie("https://www.youtube.com", consentCookie)
            cookieManager.setCookie("https://consent.youtube.com", consentCookie)
            cookieManager.flush()
        } catch (e: Exception) {
            android.util.Log.e("YouTubePlayer", "Failed to set consent cookie", e)
        }
        
        webView.loadUrl(finalUrl)
    }

    private fun setupWindowFlags() {
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        forceLandscape()
        try {
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                decorView?.setPadding(0, 0, 0, 0)
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    attributes?.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            window?.decorView?.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val w = right - left
                val h = bottom - top
                if (h > w) { // Portrait usage detected
                    Log.d(TAG, "Layout Listener: Portrait dimensions detected ($w x $h) - Re-forcing LANDSCAPE")
                    forceLandscape()
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply immersive settings", e)
        }
    }

    private fun forceLandscape() {
        try {
            val act = scanForActivity(context)
            if (act != null) {
                if (previousOrientation == -999) {
                    previousOrientation = act.requestedOrientation
                }
                if (act.requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                    act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    Log.d(TAG, "forceLandscape: Requested Sensor Landscape on ${act.localClassName}")
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

    private fun setupWebView() {
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
                // Use the device's true cached User-Agent to avoid YouTube's anti-bot "consent/login" wall
                userAgentString = com.cloudstream.shared.util.WebConfig.getCachedUserAgent() ?: android.webkit.WebSettings.getDefaultUserAgent(context)
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            }
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "onPageFinished: Launching staggered injection")
                    
                    // Staggered injection exactly like omerFlex5-ref to handle YouTube SPA DOM delays
                    val delays = longArrayOf(1000, 2000, 3000, 5000, 7000, 10000, 15000)
                    for (delay in delays) {
                        handler.postDelayed({ jsBridge.injectFullscreenCss() }, delay)
                    }
                    
                    ui.show()
                    resetAutoHide()
                    handler.post(progressUpdateRunnable)
                }
            }
        }
        jsBridge = YouTubeJsBridge(webView)
    }

    private var seekDebouncerRunnable: Runnable? = null

    private fun setupListeners() {
        ui.btnPlayPause.setOnClickListener {
            resetAutoHide()
            isPlaying = !isPlaying
            ui.setPlayingUI(isPlaying)
            if (isPlaying) jsBridge.play() else jsBridge.pause()
            Toast.makeText(context, if (isPlaying) "Playing" else "Paused", Toast.LENGTH_SHORT).show()
        }



        ui.btnRewind.setOnClickListener {
            resetAutoHide()
            jsBridge.seekRelative(-10)
        }

        ui.btnForward.setOnClickListener {
            resetAutoHide()
            jsBridge.seekRelative(10)
        }
        
        ui.btnSpeed.setOnClickListener {
            resetAutoHide()
            val act = scanForActivity(context) ?: return@setOnClickListener
            val speeds = arrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
            val labels = speeds.map { "${it}x" }
            act.showDialog(labels, currentSpeedIndex, "Playback Speed", false, {}) { index ->
                currentSpeedIndex = index
                jsBridge.setPlaybackSpeed(speeds[index])
                Toast.makeText(context, "Speed: ${labels[index]}", Toast.LENGTH_SHORT).show()
            }
        }

        ui.btnQuality.setOnClickListener {
            resetAutoHide()
            val act = scanForActivity(context) ?: return@setOnClickListener
            val qualities = arrayOf("auto", "hd2160", "hd1440", "hd1080", "hd720", "large", "medium", "small", "tiny")
            val labels = listOf("Auto", "2160p", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p")
            act.showDialog(labels, currentQualityIndex, "Video Quality", false, {}) { index ->
                currentQualityIndex = index
                jsBridge.setPlaybackQuality(qualities[index])
                Toast.makeText(context, "Quality: ${labels[index]}", Toast.LENGTH_SHORT).show()
            }
        }

        ui.btnCaptions.setOnClickListener {
            resetAutoHide()
            val act = scanForActivity(context) ?: return@setOnClickListener
            val labels = listOf("Off", "Arabic", "English", "Auto-translate to Arabic", "Auto-translate to English")
            act.showDialog(labels, currentCaptionIndex, "Captions", false, {}) { index ->
                currentCaptionIndex = index
                when (index) {
                    0 -> jsBridge.setCaptions(false)
                    1 -> jsBridge.setCaptions(true, languageCode = "ar")
                    2 -> jsBridge.setCaptions(true, languageCode = "en")
                    3 -> jsBridge.setCaptions(true, translateTo = "ar")
                    4 -> jsBridge.setCaptions(true, translateTo = "en")
                }
                Toast.makeText(context, "Captions updated", Toast.LENGTH_SHORT).show()
            }
        }

        ui.btnExit.setOnClickListener { dismiss() }

        ui.btnScale.setOnClickListener {
            resetAutoHide()
            isScaleCover = !isScaleCover
            jsBridge.setScaleMode(isScaleCover)
            Toast.makeText(context, if (isScaleCover) "Scale: Fill" else "Scale: Fit", Toast.LENGTH_SHORT).show()
        }

        ui.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    resetAutoHide()
                    val time = (progress.toDouble() / 100.0) * videoDuration
                    ui.updateProgress(time, videoDuration)

                    isSeeking = true
                    seekDebouncerRunnable?.let { handler.removeCallbacks(it) }
                    seekDebouncerRunnable = Runnable {
                        jsBridge.seekTo(time)
                        isSeeking = false
                    }
                    handler.postDelayed(seekDebouncerRunnable!!, 500)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                resetAutoHide()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                val time = (seekBar!!.progress.toDouble() / 100.0) * videoDuration
                jsBridge.seekTo(time)
            }
        })

        ui.rootView.setOnClickListener {
            if (ui.isVisible) ui.hide() else { ui.show(); resetAutoHide() }
        }

        webView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (!ui.isVisible) { ui.show(); resetAutoHide() } else ui.hide()
            }
            false
        }
    }

    private fun resetAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!ui.isVisible) {
                        ui.show()
                        resetAutoHide()
                        ui.seekBar.requestFocus()
                        return super.dispatchKeyEvent(event)
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!ui.isVisible) {
                        ui.show()
                        resetAutoHide()
                        return true
                    }
                    resetAutoHide()
                }
                android.view.KeyEvent.KEYCODE_BACK -> {
                    if (ui.isVisible) {
                        ui.hide()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        try {
            if (previousOrientation != -999) {
                val act = scanForActivity(context)
                if (act != null) {
                    if (previousOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
                    }
                    act.requestedOrientation = previousOrientation
                    Log.d(TAG, "dismiss: Restored orientation to $previousOrientation")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore orientation", e)
        }
        super.dismiss()
    }
}
