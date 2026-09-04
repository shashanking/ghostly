package com.shashank.ghostly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A hairline meter: a rounded track with a rounded fill, and nothing else — no number, no
 * percentage, no tick marks. It is meant to be glanced at, not read.
 *
 * The four bars were taken off this screen once because a pet you read off a dashboard stops being
 * a pet. This is the compromise: the same numbers, at a size that says "he is a bit hungry" without
 * inviting anyone to manage him down to the last point.
 */
class MeterView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.glass }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.bone }
    private val rect = RectF()

    /** 0..1, what is actually drawn — [setValue] eases towards the target rather than snapping. */
    private var shown = 0f
    private var target = 0f

    fun setTint(color: Int) {
        fillPaint.color = color
        invalidate()
    }

    fun setValue(value: Float, animate: Boolean = true) {
        target = value.coerceIn(0f, 1f)
        if (!animate || !isAttachedToWindow) {
            shown = target
            invalidate()
            return
        }
        animate().withEndAction(null).cancel()
        step()
    }

    private fun step() {
        val delta = target - shown
        if (kotlin.math.abs(delta) < 0.002f) {
            shown = target
            invalidate()
            return
        }
        shown += delta * 0.18f
        invalidate()
        postOnAnimation { step() }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        // Never a sliver so thin it reads as empty: an almost-empty meter still shows its cap.
        val filled = (w * shown).coerceAtLeast(if (shown > 0f) h else 0f)
        if (filled <= 0f) return
        rect.set(0f, 0f, filled, h)
        canvas.drawRoundRect(rect, r, r, fillPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, (3f * density).toInt())
    }
}
