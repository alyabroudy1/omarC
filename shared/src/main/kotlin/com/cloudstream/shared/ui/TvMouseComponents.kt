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
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A hardware-accelerated, physics-based mouse cursor overlay for TV interfaces.
 * Designed to be plug-and-play with any WebView.
 */
class TvMouseController(
    private val context: Context,
    private val webView: WebView
) : Choreographer.FrameCallback {

    private var overlay: TvMouseOverlay? = null
    private var container: FrameLayout? = null

    // Physics constants
    private val ACCELERATION = 2.0f
    private val MAX_VELOCITY = 25.0f
    private val FRICTION = 0.90f
    private val SCROLL_SPEED = 15
    private val EDGE_THRESHOLD = 50 // pixels from edge to trigger scroll

    // State
    private var position = PointF(0f, 0f)
    private var velocity = PointF(0f, 0f)
    private var lastFrameTime = 0L
    private var isAttached = false

    // Input state
    private var isUpPressed = false
    private var isDownPressed = false
    private var isLeftPressed = false
    private var isRightPressed = false

    /**
     * Attach the mouse controller to the WebView.
     * Use this when you have a container that holds the WebView.
     * Ideally, the WebView should be inside a FrameLayout.
     */
    fun attach(parentContainer: FrameLayout) {
        if (isAttached) return
        this.container = parentContainer

        // Initialize position to center
        position.x = parentContainer.width / 2f
        position.y = parentContainer.height / 2f
        if (position.x == 0f) {
             // Fallback if view not laid out yet
             position.x = 500f
             position.y = 500f
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
        lastFrameTime = System.nanoTime()
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
     * Returns true if handled.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isAttached) return false

        val isDown = event.action == KeyEvent.ACTION_DOWN
        
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { isUpPressed = isDown; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { isDownPressed = isDown; return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { isLeftPressed = isDown; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { isRightPressed = isDown; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isDown) { // Trigger click on DOWN or UP? Standard is UP usually, but for TV immediate feedback is good.
                    // Let's do click on UP to avoid repeat clicks if held
                } else {
                    simulateClick()
                }
                return true
            }
        }
        return false
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAttached || overlay == null) return

        // Calculate delta time in seconds (approx 0.016 for 60fps)
        // Capping to avoid huge jumps if frame interaction lags
        val dt = 1.0f // Using constant step for consistent physics logic feels better on TV than variable dt sometimes

        // Update Velocity based on Input
        if (isUpPressed) velocity.y -= ACCELERATION
        if (isDownPressed) velocity.y += ACCELERATION
        if (isLeftPressed) velocity.x -= ACCELERATION
        if (isRightPressed) velocity.x += ACCELERATION

        // Apply Friction
        velocity.x *= FRICTION
        velocity.y *= FRICTION

        // Snap to 0 if very low
        if (abs(velocity.x) < 0.1f) velocity.x = 0f
        if (abs(velocity.y) < 0.1f) velocity.y = 0f

        // Clamp Velocity
        velocity.x = max(-MAX_VELOCITY, min(velocity.x, MAX_VELOCITY))
        velocity.y = max(-MAX_VELOCITY, min(velocity.y, MAX_VELOCITY))

        // Update Position
        position.x += velocity.x
        position.y += velocity.y

        // Constraints & Edge Scrolling
        val width = overlay!!.width.toFloat()
        val height = overlay!!.height.toFloat()

        // X Axis Constraints
        if (position.x < 0) position.x = 0f
        if (position.x > width) position.x = width
        
        // Edge Scrolling X
        if (position.x < EDGE_THRESHOLD) webView.scrollBy(-SCROLL_SPEED, 0)
        if (position.x > width - EDGE_THRESHOLD) webView.scrollBy(SCROLL_SPEED, 0)


        // Y Axis Constraints
        if (position.y < 0) position.y = 0f
        if (position.y > height) position.y = height

        // Edge Scrolling Y
        if (position.y < EDGE_THRESHOLD) webView.scrollBy(0, -SCROLL_SPEED)
        if (position.y > height - EDGE_THRESHOLD) webView.scrollBy(0, SCROLL_SPEED)


        // Update Visuals
        overlay!!.setCursorPosition(position.x, position.y)

        // Request next frame
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun simulateClick() {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis() + 100
        val x = position.x
        val y = position.y
        
        // Offset relative to webview scroll? 
        // dispatchTouchEvent uses view-local coordinates (pixels relative to top-left of view)
        // Since overlay matches webview size/position, position.x/y are correct relative to webview.
        
        val metaState = 0
        val motionEventDown = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            metaState
        )
        
        val motionEventUp = MotionEvent.obtain(
            downTime,
            eventTime,
            MotionEvent.ACTION_UP,
            x,
            y,
            metaState
        )

        webView.dispatchTouchEvent(motionEventDown)
        
        // Dispatch UP after a small delay to ensure it registers as a click
        webView.postDelayed({
            webView.dispatchTouchEvent(motionEventUp)
            motionEventDown.recycle()
            motionEventUp.recycle()
        }, 50)
    }
}

/**
 * Custom View to draw the cursor.
 */
class TvMouseOverlay(context: Context) : View(context) {

    private val cursorPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
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
    
    // Scale the cursor
    private val scale = 1.5f
    private val pointerX = 0f
    private val pointerY = 0f
    
    // Current draw position
    private var cx = 0f
    private var cy = 0f

    init {
        // Hardware acceleration should be on by default for Views, but force it just in case
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
        canvas.drawPath(cursorPath, cursorPaint)
        canvas.restore()
    }
}
