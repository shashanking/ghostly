package com.shashank.ghostly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A miniature of the overlay, used on the app's home screen so the ghost can be tried out before
 * (and after) it is set loose over everything else. Same rules: poke it, it bolts.
 */
class GhostPlayground @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val size = (Prefs.sizeDp(context) * density).toInt()
    private val driftSpeed = 18f * density
    private val ghost = GhostView(context)
    private var driftAngle = Random.nextFloat() * 2f * PI.toFloat()

    private var posX = 0f
    private var posY = 0f
    private var velX = 0f
    private var velY = 0f
    private var lastFrameNanos = 0L
    private var clock = 0f
    private var nextGlanceAt = 1.5f
    private var placed = false

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }

    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // `isAttachedToWindow` stays true once the app is in the background, so on its own it
            // would leave this preview animating — invisibly, forever — while the user is elsewhere.
            if (!running || !isShown) {
                running = false
                return
            }
            val dt = if (lastFrameNanos == 0L) 0.016f
            else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos
            clock += dt
            tick(dt)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setWillNotDraw(false)
        addView(ghost, LayoutParams(size, size))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) resume() else pause()
    }

    private fun resume() {
        if (running || !isShown) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!placed && w > 0 && h > 0) {
            posX = (w - size) / 2f
            posY = (h - size) / 2f
            placed = true
            apply()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lookAt(event.x, event.y)
                fleeFrom(event.x, event.y)
                performClick()
                return true
            }
            // In here we get real coordinates, so he can properly follow your finger around.
            MotionEvent.ACTION_MOVE -> {
                lookAt(event.x, event.y)
                nextGlanceAt = clock + 1.5f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun lookAt(x: Float, y: Float) {
        val dx = x - (posX + size / 2f)
        val dy = y - (posY + size / 2f)
        val len = hypot(dx, dy)
        if (len < 1f) return
        val reach = (len / (width * 0.3f)).coerceAtMost(1f)
        ghost.lookAt(dx / len * reach, dy / len * reach)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText("drag a finger around — he watches", width / 2f, height - 18f * density, hintPaint)
    }

    private fun fleeFrom(fromX: Float, fromY: Float) {
        val cx = posX + size / 2f
        val cy = posY + size / 2f
        var dx = cx - fromX
        var dy = cy - fromY
        val len = hypot(dx, dy)
        val base = if (len < 1f) Random.nextFloat() * 2f * PI.toFloat() else {
            dx /= len; dy /= len; atan2(dy, dx)
        }
        val angle = base + (Random.nextFloat() - 0.5f) * 1.9f
        val speed = (320f + Random.nextFloat() * 260f) * density
        velX = cos(angle) * speed
        velY = sin(angle) * speed
        driftAngle = angle
        ghost.spook()
    }

    private fun tick(dt: Float) {
        if (!placed) return

        // Same rule as the overlay: always drifting, never parked.
        driftAngle += (sin(clock * 0.31f) + sin(clock * 0.17f + 1.3f)) * 0.4f * dt
        val settle = 1f - exp(-0.85f * dt)
        velX += (cos(driftAngle) * driftSpeed - velX) * settle
        velY += (sin(driftAngle) * driftSpeed - velY) * settle

        posX += velX * dt
        posY += velY * dt

        // Looks around the card when nothing else is going on.
        if (clock > nextGlanceAt) {
            lookAt(width * (0.1f + Random.nextFloat() * 0.8f), height * (0.1f + Random.nextFloat() * 0.8f))
            nextGlanceAt = clock + 1.4f + Random.nextFloat() * 2.4f
        }

        val maxX = (width - size).toFloat()
        val maxY = (height - size).toFloat()
        if (posX < 0f) { posX = 0f; bounceX() }
        else if (posX > maxX) { posX = maxX; bounceX() }
        if (posY < 0f) { posY = 0f; bounceY() }
        else if (posY > maxY) { posY = maxY; bounceY() }

        ghost.setMotion(velX, velY)
        ghost.advance(dt)
        ghost.invalidate()
        apply()
    }

    private fun bounceX() {
        velX = -velX * 0.5f
        driftAngle = PI.toFloat() - driftAngle
        ghost.spookLightly()
    }

    private fun bounceY() {
        velY = -velY * 0.5f
        driftAngle = -driftAngle
        ghost.spookLightly()
    }

    private fun apply() {
        ghost.translationX = posX
        ghost.translationY = posY
    }
}
