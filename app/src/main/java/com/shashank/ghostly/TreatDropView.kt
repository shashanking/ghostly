package com.shashank.ghostly

import android.content.Context
import android.graphics.Canvas
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * A treat falling onto the floating ghost.
 *
 * His box has done this since the beginning: something drops in and he goes and takes it. Feeding
 * him out on the overlay used to borrow that by flying him indoors for the occasion, and once it
 * stopped doing that a feed became a chew with nothing falling — the nicest part of feeding him had
 * quietly gone missing. So the drop came out here too.
 *
 * The view is the falling item and nothing else. It is given its own small, intangible window
 * directly above his crown by [GhostOverlayService]; it knows nothing about windows, and nothing
 * about the ghost beyond the fact that he is waiting at the bottom edge.
 */
class TreatDropView(context: Context) : View(context) {

    companion object {
        /** How far it falls. Long enough to read as a fall rather than a flicker, and no longer:
         *  every pixel of this window is composited over whatever app you were looking at. */
        private const val FALL_DP = 148f

        /** The item itself. A shade under the ghost's smallest size, so it reads as his, not a UI. */
        private const val ITEM_DP = 26f

        /** Side room for the spin: a square tilted 45 degrees needs its own diagonal. */
        private const val SPIN_SLACK = 1.45f

        /** Seconds of fall. Matched to the box's own delivery, which lands in about this. */
        private const val FALL_SECONDS = 0.42f

        /** Squash and rebound on impact, then still. */
        private const val SQUASH_SECONDS = 0.26f

        /** How long he is left holding it before it fades — time enough to be seen taking it. */
        private const val HOLD_SECONDS = 0.34f

        private const val FADE_SECONDS = 0.18f

        /** Turns it makes on the way down. Barely more than one: it is dropped, not thrown. */
        private const val SPIN_TURNS = 1.15f
    }

    private val density = resources.displayMetrics.density
    private val itemPx = ITEM_DP * density
    private val fallPx = FALL_DP * density

    private var drawable: IconDrawable? = null

    private var startedAt = 0L
    private var running = false
    private var landedFired = false

    /** Cleared before it is called, so tearing the window down from inside it cannot re-enter. */
    private var pendingFinish: (() -> Unit)? = null

    var onLanded: (() -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    /**
     * Built once, here, rather than per frame: [IconDrawable] allocates paints and a path, and this
     * view redraws every frame it is alive.
     */
    fun setItem(glyph: IconGlyph, tint: Int) {
        val d = IconDrawable(glyph, tint)
        val side = itemPx.toInt()
        // Bounded at the origin once. Each frame walks the canvas to where it has fallen to,
        // instead of moving the bounds, which would allocate a Rect comparison's worth of work
        // and force the drawable to re-measure.
        d.setBounds(0, 0, side, side)
        drawable = d
        invalidate()
    }

    fun desiredWidth(): Int = (itemPx * SPIN_SLACK).toInt()

    /** The fall, plus the item's own height at the bottom where it comes to rest. */
    fun desiredHeight(): Int = (fallPx + itemPx).toInt()

    fun drop() {
        if (running || drawable == null) return
        running = true
        landedFired = false
        startedAt = 0L
        pendingFinish = { onFinished?.invoke() }
        postOnAnimation { step() }
    }

    /**
     * A posted callback rather than a [android.animation.ValueAnimator], for the reason
     * [QuickActionsView] gives: the platform drops it when the view detaches, which is the
     * behaviour wanted here for free — the service may take this window away at any moment, and an
     * animator would go on ticking against a view that is no longer in one.
     */
    private fun step() {
        if (!running) return
        if (startedAt == 0L) startedAt = android.os.SystemClock.uptimeMillis()
        val t = (android.os.SystemClock.uptimeMillis() - startedAt) / 1000f

        if (!landedFired && t >= FALL_SECONDS) {
            landedFired = true
            onLanded?.invoke()
        }
        invalidate()

        if (t >= FALL_SECONDS + SQUASH_SECONDS + HOLD_SECONDS + FADE_SECONDS) {
            running = false
            finishOnce()
            return
        }
        postOnAnimation { step() }
    }

    private fun finishOnce() {
        val finish = pendingFinish ?: return
        pendingFinish = null
        finish()
    }

    /**
     * A window can be taken away mid-flight — the screen goes off, he is called into his box, the
     * service ends. The service is waiting on [onFinished] to know it may drop its reference, so it
     * has to arrive even when the fall never completed.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        finishOnce()
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawable ?: return
        if (!running && startedAt == 0L) return
        val t = (android.os.SystemClock.uptimeMillis() - startedAt) / 1000f
        val w = width.toFloat()

        // Gravity, not a slide: a constant-speed drop reads as the item being carried down.
        val fallT = min(1f, t / FALL_SECONDS)
        val y = fallPx * fallT * fallT

        // Squash on impact and rebound past neutral before settling — the same shape GhostView
        // uses for his own landing, so the two read as the same physics.
        val squash = if (t > FALL_SECONDS) {
            val s = ((t - FALL_SECONDS) / SQUASH_SECONDS).coerceIn(0f, 1f)
            sin(s * Math.PI.toFloat()) * (1f - s) * 0.34f
        } else {
            0f
        }

        val fadeStart = FALL_SECONDS + SQUASH_SECONDS + HOLD_SECONDS
        val alpha = if (t > fadeStart) {
            (1f - (t - fadeStart) / FADE_SECONDS).coerceIn(0f, 1f)
        } else {
            1f
        }
        d.alpha = (255 * alpha).toInt()

        val cx = w / 2f
        val cy = y + itemPx / 2f

        canvas.save()
        canvas.translate(cx, cy)
        // Stops turning the moment it lands; a treat spinning on his head is a different idea.
        if (t < FALL_SECONDS) canvas.rotate(SPIN_TURNS * 360f * fallT)
        canvas.scale(1f + squash, 1f - squash)
        canvas.translate(-itemPx / 2f, -itemPx / 2f)
        d.draw(canvas)
        canvas.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(desiredWidth(), desiredHeight())
    }
}
