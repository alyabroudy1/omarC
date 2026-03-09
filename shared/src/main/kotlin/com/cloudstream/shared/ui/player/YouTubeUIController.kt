package com.cloudstream.shared.ui.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Handles the programmatic construction of the native Android UI overlay.
 * Uses no XML resource files to remain compatible with Plugin classloaders.
 */
class YouTubeUIController(private val context: Context) {

    lateinit var rootView: FrameLayout private set
    
    // UI Elements exposed for event binding in YouTubePlayer
    lateinit var btnPlayPause: ImageButton private set
    lateinit var btnRewind: ImageButton private set
    lateinit var btnForward: ImageButton private set
    lateinit var btnSpeed: ImageButton private set
    lateinit var btnQuality: ImageButton private set
    lateinit var btnCaptions: ImageButton private set
    lateinit var btnScale: ImageButton private set
    lateinit var btnExit: ImageButton private set
    lateinit var seekBar: SeekBar private set
    lateinit var textCurrentTime: TextView private set
    lateinit var textDuration: TextView private set

    var isVisible = false
        private set

    init {
        buildUI()
    }

    private fun buildUI() {
        // Overlay container (transparent so video underneath shows)
        rootView = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        // ===== TOP BAR =====
        val topBar = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
        }

        btnSpeed = createHeaderButton(android.R.drawable.ic_menu_recent_history, "Speed")
        btnQuality = createHeaderButton(android.R.drawable.ic_menu_preferences, "Quality")
        btnCaptions = createHeaderButton(android.R.drawable.ic_menu_more, "Captions")
        btnScale = createHeaderButton(android.R.drawable.ic_menu_crop, "Scale: Fit")
        btnExit = createHeaderButton(android.R.drawable.ic_menu_close_clear_cancel, "Close")

        topBar.addView(btnSpeed)
        topBar.addView(btnQuality)
        topBar.addView(btnCaptions)
        topBar.addView(btnScale)
        topBar.addView(btnExit)

        // ===== CENTER CONTROLS =====
        val centerControls = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        btnRewind = createCenterButton(android.R.drawable.ic_media_rew, "Rewind 10s", 64)
        btnPlayPause = createCenterButton(android.R.drawable.ic_media_pause, "Play/Pause", 88)
        btnForward = createCenterButton(android.R.drawable.ic_media_ff, "Forward 10s", 64)

        centerControls.addView(btnRewind)
        centerControls.addView(btnPlayPause)
        centerControls.addView(btnForward)

        // ===== BOTTOM BAR =====
        val bottomBar = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xDD000000.toInt(), 0x00000000)
            )
        }

        val timeRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        textCurrentTime = TextView(context).apply {
            text = "0:00"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        textDuration = TextView(context).apply {
            text = "0:00"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        seekBar = createSeekBar()

        timeRow.addView(textCurrentTime)
        timeRow.addView(spacer)
        timeRow.addView(textDuration)

        bottomBar.addView(seekBar)
        bottomBar.addView(timeRow)

        rootView.addView(topBar)
        rootView.addView(centerControls)
        rootView.addView(bottomBar)
    }

    fun show() {
        if (!isVisible) {
            rootView.visibility = View.VISIBLE
            rootView.animate().alpha(1f).setDuration(200).setListener(null)
            isVisible = true
            btnPlayPause.requestFocus() // TV friendly
        }
    }

    fun hide() {
        if (isVisible) {
            val anim = AlphaAnimation(1f, 0f).apply {
                duration = 200
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationEnd(a: Animation?) { rootView.visibility = View.GONE }
                    override fun onAnimationStart(a: Animation?) {}
                    override fun onAnimationRepeat(a: Animation?) {}
                })
            }
            rootView.startAnimation(anim)
            isVisible = false
        }
    }

    fun updateProgress(currentTime: Double, duration: Double) {
        val progress = if (duration > 0) ((currentTime / duration) * 100).toInt() else 0
        seekBar.progress = progress
        textCurrentTime.text = formatTime(currentTime.toInt())
        textDuration.text = formatTime(duration.toInt())
    }

    fun setPlayingUI(isPlaying: Boolean) {
        try {
            btnPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        } catch(e:Exception) {}
    }

    // --- Helpers for formatting and programmatic views ---

    private fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return if (sec < 10) "$min:0$sec" else "$min:$sec"
    }

    private fun dp(dps: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dps * density + 0.5f).toInt()
    }

    private fun createHeaderButton(iconRes: Int, desc: String): ImageButton {
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33FFFFFF)
            setStroke(dp(2), Color.RED)
        }
        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
            addState(intArrayOf(), normalBg)
        }

        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(8)
            }
            background = stateList
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = desc
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }

    private fun createCenterButton(iconRes: Int, desc: String, sizeDp: Int = 72): ImageButton {
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x44000000)
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33FFFFFF)
            setStroke(dp(3), Color.RED)
        }
        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
            addState(intArrayOf(), normalBg)
        }

        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
            }
            background = stateList
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = desc
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }

    private fun createSeekBar(): SeekBar {
        return SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(24)
            ).apply { topMargin = dp(8) }
            max = 100
            progress = 0
            isFocusable = true
            isFocusableInTouchMode = true

            val trackHeight = dp(3)
            val trackBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(0x66FFFFFF)
                setSize(0, trackHeight)
            }
            val trackProgress = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(Color.RED)
                setSize(0, trackHeight)
            }
            val clip = ClipDrawable(trackProgress, Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(trackBg, clip)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }

            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
                setSize(dp(16), dp(16))
                setStroke(dp(2), Color.WHITE)
            }
            thumbOffset = dp(8)
        }
    }
}
