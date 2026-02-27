package com.cloudstream.shared.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A hardware-accelerated, physics-based mouse cursor overlay for TV interfaces.
 * Designed to be plug-and-play with any WebView.
 *
 * Movement model:
 *   - Tap (quick press/release): moves cursor exactly 1 pixel in that direction.
 *   - Hold: starts slow, then smoothly ramps up speed over ~1 second.
 *   - Releasing the key applies friction so the cursor glides to a stop.
 *
 * This gives precise single-pixel control for clicking small buttons
 * while still allowing fast traversal when the key is held down.
 */
class TvMouseController(
    private val context: Context,
    private val webView: WebView
) : Choreographer.FrameCallback {

    private var overlay: TvMouseOverlay? = null
    private var container: FrameLayout? = null

    // ── Movement tuning ──────────────────────────────────────────────
    // How quickly speed builds while D-pad is held (pixels/frame²).
    // Low value = gentle ramp-up, no sudden jumps.
    private val ACCELERATION = 0.35f

    // Terminal velocity (pixels/frame). Reached after ~1s of holding.
    private val MAX_VELOCITY = 12.0f

    // Friction multiplier applied every frame. 0.92 means velocity
    // drops to ~45% after 10 frames (~160ms) once the key is released.
    private val FRICTION = 0.92f

    // Minimum velocity for a single D-pad tap (pixels).
    // Ensures even the quickest tap moves the cursor by 1px.
    private val TAP_IMPULSE = 1.0f

    // Edge scrolling
    private val SCROLL_SPEED = 8
    private val EDGE_THRESHOLD = 40 // pixels from edge to trigger scroll

    // ── State ────────────────────────────────────────────────────────
    private var position = PointF(-1f, -1f)  // -1 = not initialised yet
    private var velocity = PointF(0f, 0f)
    private var isAttached = false

    // Track how many consecutive frames each direction has been held.
    // Used for the ramp-up curve so the first frame produces a tiny nudge.
    private var holdFramesX = 0
    private var holdFramesY = 0

    // Input state
    private var isUpPressed = false
    private var isDownPressed = false
    private var isLeftPressed = false
    private var isRightPressed = false

    /**
     * Attach the mouse controller to the WebView.
     * The WebView should be inside a FrameLayout.
     */
    fun attach(parentContainer: FrameLayout) {
        if (isAttached) return
        this.container = parentContainer

        val containerWidth = parentContainer.width.toFloat()
        val containerHeight = parentContainer.height.toFloat()

        if (containerWidth > 0 && containerHeight > 0) {
            position.x = containerWidth / 2f
            position.y = containerHeight / 2f
        } else {
            parentContainer.post {
                if (!isAttached) return@post
                val w = parentContainer.width.toFloat()
                val h = parentContainer.height.toFloat()
                if (w > 0 && h > 0) {
                    position.x = w / 2f
                    position.y = h / 2f
                    overlay?.setCursorPosition(position.x, position.y)
                } else {
                    position.x = 500f
                    position.y = 500f
                    overlay?.setCursorPosition(position.x, position.y)
                }
            }
        }

        // Create and add overlay
        overlay = TvMouseOverlay(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            elevation = 100f // Ensure it's on top
            isClickable = false
            isFocusable = false
        }
        parentContainer.addView(overlay)

        isAttached = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun detach() {
        isAttached = false
        Choreographer.getInstance().removeFrameCallback(this)
        container?.removeView(overlay)
        overlay = null
        container = null
    }

    /**
     * Handle key events from D-pad.
     * Returns true if the event was consumed.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isAttached) return false

        val isDown = event.action == KeyEvent.ACTION_DOWN

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isDown && !isUpPressed) {
                    // First press — give a tiny impulse so a tap moves at least 1px
                    velocity.y = -TAP_IMPULSE
                    holdFramesY = 0
                }
                isUpPressed = isDown
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isDown && !isDownPressed) {
                    velocity.y = TAP_IMPULSE
                    holdFramesY = 0
                }
                isDownPressed = isDown
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isDown && !isLeftPressed) {
                    velocity.x = -TAP_IMPULSE
                    holdFramesX = 0
                }
                isLeftPressed = isDown
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDown && !isRightPressed) {
                    velocity.x = TAP_IMPULSE
                    holdFramesX = 0
                }
                isRightPressed = isDown
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!isDown) { // Click on key-up to avoid repeat-clicks when held
                    simulateClick()
                }
                return true
            }
        }
        return false
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAttached || overlay == null) return

        // Lazy-init position if the container wasn't laid out at attach time
        if (position.x < 0 || position.y < 0) {
            val width = overlay!!.width.toFloat()
            val height = overlay!!.height.toFloat()
            if (width > 0 && height > 0) {
                position.x = width / 2f
                position.y = height / 2f
            } else {
                position.x = 500f
                position.y = 500f
            }
        }

        // ── Horizontal acceleration ──
        val heldX = isLeftPressed || isRightPressed
        if (heldX) {
            holdFramesX++
            // Ramp factor: starts near 0 and approaches 1 over ~60 frames (~1 second)
            val ramp = min(1.0f, holdFramesX / 60.0f)
            val accel = ACCELERATION * ramp
            if (isLeftPressed) velocity.x -= accel
            if (isRightPressed) velocity.x += accel
        } else {
            holdFramesX = 0
            velocity.x *= FRICTION
        }

        // ── Vertical acceleration ──
        val heldY = isUpPressed || isDownPressed
        if (heldY) {
            holdFramesY++
            val ramp = min(1.0f, holdFramesY / 60.0f)
            val accel = ACCELERATION * ramp
            if (isUpPressed) velocity.y -= accel
            if (isDownPressed) velocity.y += accel
        } else {
            holdFramesY = 0
            velocity.y *= FRICTION
        }

        // Snap to zero when effectively stopped (avoids sub-pixel drift)
        if (abs(velocity.x) < 0.05f) velocity.x = 0f
        if (abs(velocity.y) < 0.05f) velocity.y = 0f

        // Clamp to max speed
        velocity.x = velocity.x.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        velocity.y = velocity.y.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)

        // Update position
        position.x += velocity.x
        position.y += velocity.y

        // Boundary clamping & edge scrolling
        val width = overlay!!.width.toFloat()
        val height = overlay!!.height.toFloat()

        if (position.x < 0) position.x = 0f
        if (position.x > width) position.x = width
        if (position.y < 0) position.y = 0f
        if (position.y > height) position.y = height

        // Scroll the WebView when the cursor reaches the edge
        if (position.x < EDGE_THRESHOLD) webView.scrollBy(-SCROLL_SPEED, 0)
        if (position.x > width - EDGE_THRESHOLD) webView.scrollBy(SCROLL_SPEED, 0)
        if (position.y < EDGE_THRESHOLD) webView.scrollBy(0, -SCROLL_SPEED)
        if (position.y > height - EDGE_THRESHOLD) webView.scrollBy(0, SCROLL_SPEED)

        // Draw
        overlay!!.setCursorPosition(position.x, position.y)

        // Next frame
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun simulateClick() {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis() + 100
        val x = position.x
        val y = position.y

        val metaState = 0
        val motionEventDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y, metaState
        )
        val motionEventUp = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_UP, x, y, metaState
        )

        webView.dispatchTouchEvent(motionEventDown)

        webView.postDelayed({
            webView.dispatchTouchEvent(motionEventUp)
            motionEventDown.recycle()
            motionEventUp.recycle()
        }, 50)
    }
}

/**
 * Custom View that draws the cursor arrow.
 */
class TvMouseOverlay(context: Context) : View(context) {

    private val cursorPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val outlinePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val cursorPath = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 28f)
        lineTo(8f, 22f)
        lineTo(14f, 34f)
        lineTo(18f, 32f)
        lineTo(12f, 20f)
        lineTo(20f, 20f)
        close()
    }

    private val scale = 1.5f

    // Current draw position
    private var cx = 0f
    private var cy = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setCursorPosition(x: Float, y: Float) {
        cx = x
        cy = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        // Draw black outline first, then white fill on top — gives a visible edge on any background
        canvas.drawPath(cursorPath, outlinePaint)
        canvas.drawPath(cursorPath, cursorPaint)
        canvas.restore()
    }
}
