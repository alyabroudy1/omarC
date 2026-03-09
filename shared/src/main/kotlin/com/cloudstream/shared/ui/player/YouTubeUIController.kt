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
    
    // New UI Elements
    lateinit var textTitle: TextView private set
    lateinit var textChannel: TextView private set
    lateinit var btnDescription: TextView private set
    lateinit var imgAvatar: android.widget.ImageView private set

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
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
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
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(16), dp(16), dp(16), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
        }

        // Add a Title and Channel placeholders (to be populated later via JSBridge)
        val infoContainer = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
        }
        
        textTitle = TextView(context).apply {
            text = "Video Title"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }
        
        textChannel = TextView(context).apply {
            text = "Channel Name"
            setTextColor(0xDDFFFFFF.toInt())
            textSize = 14f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }
        infoContainer.addView(textTitle)
        infoContainer.addView(textChannel)

        // Top Right: Group containing Scale, CC, Speed, Quality, and Exit
        val topRightGroup = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val rightPillContainer = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48)
            )
            orientation = LinearLayout.HORIZONTAL
            background = createGlassPillBackground()
            setPaddingRelative(dp(8), 0, dp(8), 0)
        }

        btnScale = createTransparentIconButton(android.R.drawable.ic_menu_crop, "Scale: Fit")
        btnCaptions = createTransparentIconButton(android.R.drawable.ic_menu_more, "Captions")
        btnSpeed = createTransparentIconButton(android.R.drawable.ic_menu_recent_history, "Speed")
        btnQuality = createTransparentIconButton(android.R.drawable.ic_menu_preferences, "Quality")

        rightPillContainer.addView(btnScale)
        rightPillContainer.addView(btnCaptions)
        rightPillContainer.addView(btnSpeed)
        rightPillContainer.addView(btnQuality)

        btnExit = createPillIconButton(android.R.drawable.ic_menu_close_clear_cancel, "Close")
        (btnExit.layoutParams as LinearLayout.LayoutParams).marginStart = dp(12)

        topRightGroup.addView(rightPillContainer)
        topRightGroup.addView(btnExit)

        topBar.addView(infoContainer)
        topBar.addView(topRightGroup)

        // ===== BOTTOM CONTAINER =====
        val bottomContainer = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(24), dp(16), dp(24), dp(32))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xDD000000.toInt(), 0x00000000)
            )
        }

        // 1. SEEK BAR ROW
        seekBar = createSeekBar()
        
        val timeRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(dp(8), dp(4), dp(8), dp(12))
        }

        textCurrentTime = TextView(context).apply {
            text = "0:00"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val spacer = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        textDuration = TextView(context).apply {
            text = "0:00"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 12f
        }
        timeRow.addView(textCurrentTime)
        timeRow.addView(spacer)
        timeRow.addView(textDuration)

        bottomContainer.addView(seekBar)
        bottomContainer.addView(timeRow)

        // ===== CENTER CONTAINER =====
        val centerContainer = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnRewind = createCenterSecondaryButton(android.R.drawable.ic_media_rew, "Rewind")
        (btnRewind.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(32)
        
        btnPlayPause = createPrimaryPlayButton()
        
        btnForward = createCenterSecondaryButton(android.R.drawable.ic_media_ff, "Fast Forward")
        (btnForward.layoutParams as LinearLayout.LayoutParams).marginStart = dp(32)

        centerContainer.addView(btnRewind)
        centerContainer.addView(btnPlayPause)
        centerContainer.addView(btnForward)

        rootView.addView(topBar)
        rootView.addView(centerContainer)
        rootView.addView(bottomContainer)
    }

    fun show() {
        if (!isVisible) {
            rootView.visibility = View.VISIBLE
            // Use translation + alpha for a smoother "slide up" modern feel
            rootView.alpha = 0f
            rootView.translationY = dp(20).toFloat()
            rootView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setListener(null)
            isVisible = true
            btnPlayPause.requestFocus() // TV friendly
        }
    }

    fun hide() {
        if (isVisible) {
            rootView.animate()
                .alpha(0f)
                .translationY(dp(20).toFloat())
                .setDuration(300)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .setListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        rootView.visibility = View.GONE
                    }
                })
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
            // Replace with standard android playback icons colored appropriately
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

    /**
     * Creates a glassmorphic pill background: translucent dark gray with a very subtle white border.
     */
    private fun createGlassPillBackground(): android.graphics.drawable.Drawable {
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(0x66000000) // Translucent Black
            setStroke(dp(1), 0x22FFFFFF) // Subtle white border
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(0x88000000.toInt())
            setStroke(dp(2), Color.WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
            addState(intArrayOf(), normalBg)
        }
    }

    /**
     * Transparent icon button used inside a group pill container.
     */
    private fun createTransparentIconButton(iconRes: Int, desc: String): ImageButton {
        val focusOverlay = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33FFFFFF)
        }
        val normalBg = ColorDrawable(Color.TRANSPARENT)
        
        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusOverlay)
            addState(intArrayOf(android.R.attr.state_pressed), focusOverlay)
            addState(intArrayOf(), normalBg)
        }

        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
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

    /**
     * Standalone pill button with an icon.
     */
    private fun createPillIconButton(iconRes: Int, desc: String): ImageButton {
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) }
            background = createGlassPillBackground()
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = desc
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }

    /**
     * Large solid white circular button matching the screenshot's Play/Pause button.
     */
    private fun createPrimaryPlayButton(): ImageButton {
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFEEEEEE.toInt())
            setStroke(dp(4), 0x66FFFFFF)
        }
        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
            addState(intArrayOf(), normalBg)
        }

        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            background = stateList
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.BLACK) // Black icon on white background
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Play/Pause"
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }

    private fun createCenterSecondaryButton(iconResId: Int, contentDesc: String): ImageButton {
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageResource(iconResId)
            background = createGlassPillBackground()
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = contentDesc
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }

    private fun createSeekBar(): SeekBar {
        return SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28)
            )
            max = 100
            progress = 0
            isFocusable = true
            isFocusableInTouchMode = true
            splitTrack = false

            // Track Background: Very Fine Solid White Line, 1.5dp thick
            val trackHeight = dp(2).coerceAtLeast(1)
            val trackBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x66FFFFFF) // Translucent white for unplayed
                setSize(0, trackHeight)
            }
            
            // Track Progress: Very Fine Solid Red Line
            val trackProgress = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.RED)
                setSize(0, trackHeight)
            }
            val clip = ClipDrawable(trackProgress, Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(trackBg, clip)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }

            // Thumb: Red circle crossing the fine line
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
                setSize(dp(12), dp(12))
            }
            thumbOffset = 0 // Allows the fine line to perfectly hit the center of the thumb
        }
    }
}
