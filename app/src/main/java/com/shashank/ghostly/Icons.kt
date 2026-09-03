package com.shashank.ghostly

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Every icon the UI needs, drawn rather than shipped as image assets — consistent with the rest
 *  of the app (no third-party dependencies, nothing beyond plain Kotlin and the platform SDK).
 *  A [Drawable] rather than a custom View so the same glyph works as a button's compound drawable,
 *  an ImageView's source, or a tab icon without three different wiring paths. */
enum class IconGlyph {
    HUNGER, ENERGY, HAPPINESS, ANGER, TREAT, GIFT, TOKEN,
    HOME, SHOP, STYLE, SETTINGS, PLAY, NAP, SHARE, PIN
}

class IconDrawable(val glyph: IconGlyph, tint: Int = Color.WHITE) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = tint
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        alpha = 110
    }
    private val path = Path()
    private val rect = RectF()

    fun withTint(color: Int): IconDrawable = IconDrawable(glyph, color)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        strokePaint.strokeWidth = w * 0.09f

        when (glyph) {
            IconGlyph.HUNGER -> drawHunger(canvas, w, h)
            IconGlyph.ENERGY -> drawEnergy(canvas, w, h)
            IconGlyph.HAPPINESS -> drawHeart(canvas, w, h)
            IconGlyph.ANGER -> drawFlame(canvas, w, h)
            IconGlyph.TREAT -> drawTreat(canvas, w, h)
            IconGlyph.GIFT -> drawGift(canvas, w, h)
            IconGlyph.TOKEN -> drawToken(canvas, w, h)
            IconGlyph.HOME -> drawHome(canvas, w, h)
            IconGlyph.SHOP -> drawShop(canvas, w, h)
            IconGlyph.STYLE -> drawStar(canvas, w, h)
            IconGlyph.SETTINGS -> drawSliders(canvas, w, h)
            IconGlyph.PLAY -> drawPlay(canvas, w, h)
            IconGlyph.NAP -> drawNap(canvas, w, h)
            IconGlyph.SHARE -> drawShare(canvas, w, h)
            IconGlyph.PIN -> drawPin(canvas, w, h)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        dimPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun drawHeart(canvas: Canvas, w: Float, h: Float) {
        val r = w * 0.22f
        val cx = w / 2f
        val topY = h * 0.34f
        canvas.drawCircle(cx - r * 0.95f, topY, r, fillPaint)
        canvas.drawCircle(cx + r * 0.95f, topY, r, fillPaint)
        path.reset()
        path.moveTo(cx - r * 1.9f, topY)
        path.lineTo(cx, h * 0.84f)
        path.lineTo(cx + r * 1.9f, topY)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawEnergy(canvas: Canvas, w: Float, h: Float) {
        path.reset()
        path.moveTo(w * 0.58f, h * 0.08f)
        path.lineTo(w * 0.30f, h * 0.55f)
        path.lineTo(w * 0.48f, h * 0.55f)
        path.lineTo(w * 0.40f, h * 0.92f)
        path.lineTo(w * 0.72f, h * 0.42f)
        path.lineTo(w * 0.52f, h * 0.42f)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawHunger(canvas: Canvas, w: Float, h: Float) {
        val r = w * 0.30f
        canvas.drawCircle(w * 0.38f, h * 0.58f, r, fillPaint)
        canvas.drawCircle(w * 0.62f, h * 0.58f, r, fillPaint)
        strokePaint.strokeWidth = w * 0.07f
        canvas.drawLine(w * 0.52f, h * 0.28f, w * 0.60f, h * 0.12f, strokePaint)
    }

    private fun drawFlame(canvas: Canvas, w: Float, h: Float) {
        path.reset()
        path.moveTo(w * 0.5f, h * 0.08f)
        path.cubicTo(w * 0.82f, h * 0.38f, w * 0.72f, h * 0.62f, w * 0.5f, h * 0.92f)
        path.cubicTo(w * 0.28f, h * 0.62f, w * 0.18f, h * 0.38f, w * 0.5f, h * 0.08f)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawTreat(canvas: Canvas, w: Float, h: Float) {
        canvas.drawCircle(w / 2f, h / 2f, w * 0.38f, fillPaint)
        val dotR = w * 0.05f
        canvas.drawCircle(w * 0.40f, h * 0.40f, dotR, dimPaint)
        canvas.drawCircle(w * 0.60f, h * 0.45f, dotR, dimPaint)
        canvas.drawCircle(w * 0.45f, h * 0.62f, dotR, dimPaint)
        canvas.drawCircle(w * 0.62f, h * 0.65f, dotR, dimPaint)
    }

    private fun drawGift(canvas: Canvas, w: Float, h: Float) {
        rect.set(w * 0.16f, h * 0.40f, w * 0.84f, h * 0.86f)
        canvas.drawRoundRect(rect, w * 0.05f, w * 0.05f, fillPaint)
        rect.set(w * 0.10f, h * 0.28f, w * 0.90f, h * 0.42f)
        canvas.drawRoundRect(rect, w * 0.03f, w * 0.03f, fillPaint)
        canvas.drawCircle(w * 0.38f, h * 0.20f, w * 0.08f, fillPaint)
        canvas.drawCircle(w * 0.62f, h * 0.20f, w * 0.08f, fillPaint)
    }

    private fun drawToken(canvas: Canvas, w: Float, h: Float) {
        canvas.drawCircle(w / 2f, h / 2f, w * 0.38f, fillPaint)
        val ring = Paint(strokePaint).apply { strokeWidth = w * 0.05f; alpha = 150 }
        canvas.drawCircle(w / 2f, h / 2f, w * 0.22f, ring)
    }

    private fun drawHome(canvas: Canvas, w: Float, h: Float) {
        path.reset()
        path.moveTo(w * 0.5f, h * 0.12f)
        path.lineTo(w * 0.88f, h * 0.46f)
        path.lineTo(w * 0.74f, h * 0.46f)
        path.lineTo(w * 0.74f, h * 0.88f)
        path.lineTo(w * 0.26f, h * 0.88f)
        path.lineTo(w * 0.26f, h * 0.46f)
        path.lineTo(w * 0.12f, h * 0.46f)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawShop(canvas: Canvas, w: Float, h: Float) {
        path.reset()
        path.moveTo(w * 0.22f, h * 0.32f)
        path.lineTo(w * 0.78f, h * 0.32f)
        path.lineTo(w * 0.86f, h * 0.88f)
        path.lineTo(w * 0.14f, h * 0.88f)
        path.close()
        canvas.drawPath(path, fillPaint)
        rect.set(w * 0.32f, h * 0.08f, w * 0.68f, h * 0.40f)
        canvas.drawArc(rect, 180f, 180f, false, strokePaint)
    }

    private fun drawStar(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val outerR = w * 0.44f
        val innerR = w * 0.18f
        val points = 4
        path.reset()
        for (i in 0 until points * 2) {
            val angle = (Math.PI / points * i - Math.PI / 2).toFloat()
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawSliders(canvas: Canvas, w: Float, h: Float) {
        strokePaint.strokeWidth = w * 0.10f
        val ys = floatArrayOf(h * 0.25f, h * 0.5f, h * 0.75f)
        val knobX = floatArrayOf(w * 0.65f, w * 0.35f, w * 0.55f)
        for (i in 0..2) {
            canvas.drawLine(w * 0.12f, ys[i], w * 0.88f, ys[i], strokePaint)
            canvas.drawCircle(knobX[i], ys[i], w * 0.09f, fillPaint)
        }
    }

    private fun drawPlay(canvas: Canvas, w: Float, h: Float) {
        path.reset()
        path.moveTo(w * 0.30f, h * 0.16f)
        path.lineTo(w * 0.30f, h * 0.84f)
        path.lineTo(w * 0.84f, h * 0.5f)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawNap(canvas: Canvas, w: Float, h: Float) {
        val big = Path().apply { addCircle(w * 0.45f, h * 0.5f, w * 0.36f, Path.Direction.CW) }
        val cut = Path().apply { addCircle(w * 0.64f, h * 0.36f, w * 0.30f, Path.Direction.CW) }
        val crescent = Path()
        crescent.op(big, cut, Path.Op.DIFFERENCE)
        canvas.drawPath(crescent, fillPaint)
    }

    private fun drawShare(canvas: Canvas, w: Float, h: Float) {
        val p1x = w * 0.26f; val p1y = h * 0.5f
        val p2x = w * 0.74f; val p2y = h * 0.22f
        val p3x = w * 0.74f; val p3y = h * 0.78f
        strokePaint.strokeWidth = w * 0.06f
        canvas.drawLine(p1x, p1y, p2x, p2y, strokePaint)
        canvas.drawLine(p1x, p1y, p3x, p3y, strokePaint)
        val r = w * 0.13f
        canvas.drawCircle(p1x, p1y, r, fillPaint)
        canvas.drawCircle(p2x, p2y, r, fillPaint)
        canvas.drawCircle(p3x, p3y, r, fillPaint)
    }

    private fun drawPin(canvas: Canvas, w: Float, h: Float) {
        canvas.drawCircle(w * 0.5f, h * 0.38f, w * 0.26f, fillPaint)
        path.reset()
        path.moveTo(w * 0.32f, h * 0.46f)
        path.lineTo(w * 0.68f, h * 0.46f)
        path.lineTo(w * 0.5f, h * 0.90f)
        path.close()
        canvas.drawPath(path, fillPaint)
    }
}
