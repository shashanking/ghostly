package com.shashank.ghostly

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws what he is wearing, in [GhostView]'s own coordinates, riding every transform the body does
 * — so a hat tumbles with him through a rollover and squashes with him on a landing.
 *
 * The app is monochrome glass; his clothes are the one place colour is let in, and only soft,
 * candy-shop colour at that. Everything is built from circles, ovals and a few paths into a
 * reused [Path] — nothing allocated per frame, no shadow layers, no clipping.
 *
 * Every measurement is a fraction of [gw], his body width, so the same outfit reads the same on a
 * Wisp as on a Haunt.
 */
class OutfitPainter {

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()
    private val rect = RectF()

    // Party hat
    private val partyPink = fill(Color.parseColor("#FF9EC7"))
    private val partyStripe = fill(Color.parseColor("#FFF0A6"))
    private val partyPom = fill(Color.parseColor("#FFFFFF"))

    // Witch hat
    private val witchPurple = fill(Color.parseColor("#6E55A8"))
    private val witchBrim = fill(Color.parseColor("#5A4492"))
    private val witchBand = fill(Color.parseColor("#FFB84D"))

    // Crown
    private val gold = fill(Color.parseColor("#FFD35C"))
    private val goldEdge = stroke(Color.parseColor("#E8A93A"))
    private val goldBand = fill(Color.parseColor("#FFE38F"))
    private val gemPink = fill(Color.parseColor("#FF6FA3"))
    private val gemBlue = fill(Color.parseColor("#6FD0FF"))

    // Halo
    private val haloRing = stroke(Color.parseColor("#FFE27A"))
    private val haloGlow = stroke(Color.parseColor("#55FFE9A8"))

    // Flower crown
    private val petalColors = intArrayOf(
        Color.parseColor("#FFA8C5"),
        Color.parseColor("#C9B0FF"),
        Color.parseColor("#FFC98B"),
    )
    private val petal = fill(Color.WHITE)
    private val flowerHeart = fill(Color.parseColor("#FFE066"))
    private val leaf = fill(Color.parseColor("#8FD694"))

    // Bow
    private val bowPink = fill(Color.parseColor("#FF8FB8"))
    private val bowFold = fill(Color.parseColor("#F06A9C"))

    // Beanie
    private val beanie = fill(Color.parseColor("#86CDB9"))
    private val beanieRib = fill(Color.parseColor("#6DB8A3"))
    private val beanieLine = stroke(Color.parseColor("#5AA590"))
    private val pom = fill(Color.parseColor("#FFF6E6"))

    // Face
    private val frameLight = stroke(Color.parseColor("#3B3140"))
    private val frameDark = stroke(Color.parseColor("#EDE6F2"))
    private val lens = fill(Color.parseColor("#33FFFFFF"))
    private val heartLens = fill(Color.parseColor("#EEFF5E8E"))
    private val starLens = fill(Color.parseColor("#F2FFC83D"))
    private val starEdge = stroke(Color.parseColor("#F2A516"))
    private val glint = fill(Color.parseColor("#B3FFFFFF"))
    private val rosy = fill(Color.parseColor("#99FF8FB0"))

    // Neck
    private val scarf = fill(Color.parseColor("#FF7B6B"))
    private val scarfStripe = fill(Color.parseColor("#FFD9D1"))
    private val scarfFringe = stroke(Color.parseColor("#FF7B6B"))
    private val bowTie = fill(Color.parseColor("#7C8CFF"))
    private val bowTieKnot = fill(Color.parseColor("#6272F0"))
    private val collar = fill(Color.parseColor("#E9677F"))
    private val bellSlit = stroke(Color.parseColor("#B8862B"))

    /**
     * Hats: drawn after his body, before his face, so eyes that sit high — the frog's, up on their
     * bulges — are drawn over a brim rather than hidden under it.
     *
     * [crown] is the top of his dome.
     */
    fun drawHead(canvas: Canvas, id: String?, cx: Float, crown: Float, r: Float, gw: Float, phase: Float, frog: Boolean) {
        if (id == null) return
        // The frog's eyes stand above his crown; anything on his head sits up on top of them.
        val top = if (frog) crown - gw * 0.11f else crown
        when (id) {
            "head_party" -> drawPartyHat(canvas, cx, top, gw)
            "head_witch" -> drawWitchHat(canvas, cx, top, gw)
            "head_crown" -> drawCrown(canvas, cx, top, gw)
            "head_halo" -> drawHalo(canvas, cx, top, gw, phase)
            "head_flowers" -> drawFlowerCrown(canvas, cx, crown, r, gw)
            "head_bow" -> drawBow(canvas, cx + gw * 0.25f, crown + gw * 0.13f, gw * 0.9f, 22f)
            "head_beanie" -> drawBeanie(canvas, cx, crown, r, gw)
        }
    }

    /** Glasses and cheeks, over his eyes. [dark] is the Ink shade, which needs pale frames. */
    fun drawFace(
        canvas: Canvas,
        id: String?,
        cx: Float,
        eyeY: Float,
        eyeDx: Float,
        eyeR: Float,
        mouthY: Float,
        gw: Float,
        dark: Boolean,
    ) {
        if (id == null) return
        when (id) {
            "face_round" -> drawRoundGlasses(canvas, cx, eyeY, eyeDx, eyeR, gw, dark)
            "face_hearts" -> drawShades(canvas, cx, eyeY, eyeDx, eyeR, gw, dark, hearts = true)
            "face_stars" -> drawShades(canvas, cx, eyeY, eyeDx, eyeR, gw, dark, hearts = false)
            "face_blush" -> {
                for (side in SIDES) {
                    val x = cx + side * (eyeDx + eyeR * 0.5f)
                    val y = mouthY - gw * 0.03f
                    rect.set(x - eyeR * 0.62f, y - eyeR * 0.42f, x + eyeR * 0.62f, y + eyeR * 0.42f)
                    canvas.drawOval(rect, rosy)
                    canvas.drawCircle(x - eyeR * 0.2f, y - eyeR * 0.12f, eyeR * 0.1f, glint)
                }
            }
        }
    }

    /** Round his middle, just under his mouth. */
    fun drawNeck(canvas: Canvas, id: String?, cx: Float, mouthY: Float, gw: Float, phase: Float) {
        if (id == null) return
        val y = mouthY + gw * 0.09f
        when (id) {
            "neck_bowtie" -> drawBowTie(canvas, cx, y, gw)
            "neck_scarf" -> drawScarf(canvas, cx, y, gw, phase)
            "neck_bell" -> drawBell(canvas, cx, y, gw, phase)
        }
    }

    // region hats

    private fun drawPartyHat(canvas: Canvas, cx: Float, top: Float, gw: Float) {
        val baseY = top + gw * 0.07f
        val half = gw * 0.19f
        val height = gw * 0.42f
        val x = cx + gw * 0.07f
        canvas.save()
        canvas.rotate(12f, x, baseY)
        path.reset()
        path.moveTo(x - half, baseY)
        path.lineTo(x, baseY - height)
        path.lineTo(x + half, baseY)
        path.quadTo(x, baseY + gw * 0.05f, x - half, baseY)
        path.close()
        canvas.drawPath(path, partyPink)
        // Stripes as bands across the cone, cut to its sides by hand rather than by a clip.
        for ((from, to) in STRIPES) {
            val y0 = baseY - height * from
            val y1 = baseY - height * to
            val w0 = half * (1f - from)
            val w1 = half * (1f - to)
            path.reset()
            path.moveTo(x - w0, y0)
            path.lineTo(x - w1, y1)
            path.lineTo(x + w1, y1)
            path.lineTo(x + w0, y0)
            path.close()
            canvas.drawPath(path, partyStripe)
        }
        canvas.drawCircle(x, baseY - height, gw * 0.065f, partyPom)
        canvas.drawCircle(x - gw * 0.02f, baseY - height - gw * 0.02f, gw * 0.022f, partyStripe)
        canvas.restore()
    }

    private fun drawWitchHat(canvas: Canvas, cx: Float, top: Float, gw: Float) {
        val brimY = top + gw * 0.08f
        canvas.save()
        canvas.rotate(-6f, cx, brimY)
        rect.set(cx - gw * 0.4f, brimY - gw * 0.065f, cx + gw * 0.4f, brimY + gw * 0.065f)
        canvas.drawOval(rect, witchBrim)
        val half = gw * 0.22f
        path.reset()
        path.moveTo(cx - half, brimY)
        path.quadTo(cx - gw * 0.12f, brimY - gw * 0.3f, cx - gw * 0.02f, brimY - gw * 0.46f)
        path.quadTo(cx + gw * 0.1f, brimY - gw * 0.5f, cx + gw * 0.2f, brimY - gw * 0.44f)
        path.quadTo(cx + gw * 0.08f, brimY - gw * 0.36f, cx + gw * 0.08f, brimY - gw * 0.26f)
        path.quadTo(cx + gw * 0.12f, brimY - gw * 0.1f, cx + half, brimY)
        path.close()
        canvas.drawPath(path, witchPurple)
        // The band, following the cone's sides near its base.
        path.reset()
        path.moveTo(cx - half, brimY - gw * 0.005f)
        path.lineTo(cx - gw * 0.19f, brimY - gw * 0.085f)
        path.lineTo(cx + gw * 0.2f, brimY - gw * 0.085f)
        path.lineTo(cx + half, brimY - gw * 0.005f)
        path.close()
        canvas.drawPath(path, witchBand)
        rect.set(cx - gw * 0.045f, brimY - gw * 0.1f, cx + gw * 0.045f, brimY + gw * 0.01f)
        canvas.drawRoundRect(rect, gw * 0.015f, gw * 0.015f, witchBrim)
        canvas.restore()
    }

    private fun drawCrown(canvas: Canvas, cx: Float, top: Float, gw: Float) {
        val base = top + gw * 0.08f
        val half = gw * 0.2f
        canvas.save()
        canvas.rotate(-7f, cx, base)
        path.reset()
        path.moveTo(cx - half, base)
        path.lineTo(cx - half - gw * 0.02f, base - gw * 0.2f)
        path.lineTo(cx - gw * 0.1f, base - gw * 0.1f)
        path.lineTo(cx, base - gw * 0.25f)
        path.lineTo(cx + gw * 0.1f, base - gw * 0.1f)
        path.lineTo(cx + half + gw * 0.02f, base - gw * 0.2f)
        path.lineTo(cx + half, base)
        path.close()
        goldEdge.strokeWidth = gw * 0.018f
        canvas.drawPath(path, gold)
        canvas.drawPath(path, goldEdge)
        rect.set(cx - half, base - gw * 0.06f, cx + half, base)
        canvas.drawRect(rect, goldBand)
        val gem = gw * 0.035f
        canvas.drawCircle(cx - half - gw * 0.02f, base - gw * 0.2f, gem, gemPink)
        canvas.drawCircle(cx, base - gw * 0.25f, gem * 1.1f, gemBlue)
        canvas.drawCircle(cx + half + gw * 0.02f, base - gw * 0.2f, gem, gemPink)
        canvas.drawCircle(cx, base - gw * 0.03f, gem * 0.8f, gemPink)
        canvas.restore()
    }

    private fun drawHalo(canvas: Canvas, cx: Float, top: Float, gw: Float, phase: Float) {
        val y = top - gw * 0.13f + sin(phase * 2.2f) * gw * 0.018f
        rect.set(cx - gw * 0.23f, y - gw * 0.065f, cx + gw * 0.23f, y + gw * 0.065f)
        haloGlow.strokeWidth = gw * 0.1f
        canvas.drawOval(rect, haloGlow)
        haloRing.strokeWidth = gw * 0.045f
        canvas.drawOval(rect, haloRing)
    }

    private fun drawFlowerCrown(canvas: Canvas, cx: Float, crown: Float, r: Float, gw: Float) {
        val cy = crown + r
        val ringR = r * 0.97f
        // Leaves sit between the flowers, laid along the curve of his head.
        for (deg in LEAF_ANGLES) {
            val a = deg * DEG
            val x = cx + cos(a) * ringR
            val y = cy + sin(a) * ringR
            canvas.save()
            canvas.rotate(deg + 90f, x, y)
            rect.set(x - gw * 0.07f, y - gw * 0.03f, x + gw * 0.07f, y + gw * 0.03f)
            canvas.drawOval(rect, leaf)
            canvas.restore()
        }
        val f = gw * 0.058f
        FLOWER_ANGLES.forEachIndexed { i, deg ->
            val a = deg * DEG
            val x = cx + cos(a) * ringR
            val y = cy + sin(a) * ringR
            val big = if (i == 2) 1.2f else 1f
            petal.color = petalColors[i % petalColors.size]
            for (p in 0 until 5) {
                val pa = p * (2f * PI.toFloat() / 5f) + i
                canvas.drawCircle(x + cos(pa) * f * 0.85f * big, y + sin(pa) * f * 0.85f * big, f * 0.62f * big, petal)
            }
            canvas.drawCircle(x, y, f * 0.5f * big, flowerHeart)
        }
    }

    private fun drawBow(canvas: Canvas, x: Float, y: Float, gw: Float, tilt: Float) {
        canvas.save()
        canvas.rotate(tilt, x, y)
        // Tails first, so the loops sit over them.
        for (side in SIDES) {
            path.reset()
            path.moveTo(x, y)
            path.lineTo(x + side * gw * 0.06f, y + gw * 0.14f)
            path.lineTo(x + side * gw * 0.02f, y + gw * 0.12f)
            path.lineTo(x + side * gw * 0.005f, y + gw * 0.15f)
            path.close()
            canvas.drawPath(path, bowFold)
        }
        for (side in SIDES) {
            canvas.save()
            val lx = x + side * gw * 0.1f
            canvas.rotate(side * -14f, lx, y)
            rect.set(lx - gw * 0.1f, y - gw * 0.07f, lx + gw * 0.1f, y + gw * 0.07f)
            canvas.drawOval(rect, bowPink)
            rect.set(lx - gw * 0.045f, y - gw * 0.03f, lx + gw * 0.045f, y + gw * 0.03f)
            rect.offset(-side * gw * 0.03f, 0f)
            canvas.drawOval(rect, bowFold)
            canvas.restore()
        }
        canvas.drawCircle(x, y, gw * 0.045f, bowPink)
        canvas.drawCircle(x - gw * 0.012f, y - gw * 0.012f, gw * 0.014f, glint)
        canvas.restore()
    }

    private fun drawBeanie(canvas: Canvas, cx: Float, crown: Float, r: Float, gw: Float) {
        // A cap of a circle a touch bigger than his dome, cut off where the rib band goes.
        val capR = r * 1.06f
        val capCy = crown - gw * 0.04f + capR
        val bandY = crown + gw * 0.2f
        val s = ((bandY - capCy) / capR).coerceIn(-1f, 1f)
        // Where the band crosses the circle: left of centre at 180° − asin, right at 360° + asin.
        val a = asin(s) * 180f / PI.toFloat()
        val start = 180f - a
        val sweep = 180f + 2f * a
        rect.set(cx - capR, capCy - capR, cx + capR, capCy + capR)
        path.reset()
        path.arcTo(rect, start, sweep, true)
        path.close()
        canvas.drawPath(path, beanie)
        val chord = capR * cos(asin(s))
        beanieLine.strokeWidth = gw * 0.012f
        for (i in -2..2) {
            val x = cx + i * chord * 0.33f
            canvas.drawLine(x, bandY - gw * 0.02f, x * 0.92f + cx * 0.08f, crown + gw * 0.02f, beanieLine)
        }
        rect.set(cx - chord - gw * 0.03f, bandY - gw * 0.055f, cx + chord + gw * 0.03f, bandY + gw * 0.055f)
        canvas.drawRoundRect(rect, gw * 0.05f, gw * 0.05f, beanieRib)
        for (i in -4..4) {
            val x = cx + i * (chord / 4.5f)
            canvas.drawLine(x, bandY - gw * 0.035f, x, bandY + gw * 0.035f, beanieLine)
        }
        canvas.drawCircle(cx, crown - gw * 0.07f, gw * 0.085f, pom)
    }

    // endregion

    // region face

    private fun drawRoundGlasses(canvas: Canvas, cx: Float, eyeY: Float, eyeDx: Float, eyeR: Float, gw: Float, dark: Boolean) {
        val frame = if (dark) frameDark else frameLight
        frame.strokeWidth = gw * 0.028f
        val lensR = eyeR * 1.28f
        for (side in SIDES) {
            val x = cx + side * eyeDx
            canvas.drawCircle(x, eyeY, lensR, lens)
            canvas.drawCircle(x, eyeY, lensR, frame)
            canvas.drawLine(x + side * lensR, eyeY - lensR * 0.2f, x + side * (lensR + gw * 0.07f), eyeY - lensR * 0.35f, frame)
        }
        val inner = eyeDx - lensR
        rect.set(cx - inner - gw * 0.01f, eyeY - lensR * 0.55f, cx + inner + gw * 0.01f, eyeY - lensR * 0.05f)
        canvas.drawArc(rect, 200f, 140f, false, frame)
    }

    private fun drawShades(canvas: Canvas, cx: Float, eyeY: Float, eyeDx: Float, eyeR: Float, gw: Float, dark: Boolean, hearts: Boolean) {
        val frame = if (dark) frameDark else frameLight
        frame.strokeWidth = gw * 0.022f
        val size = eyeR * 1.5f
        for (side in SIDES) {
            val x = cx + side * eyeDx
            if (hearts) heartPath(x, eyeY, size) else starPath(x, eyeY, size)
            canvas.drawPath(path, if (hearts) heartLens else starLens)
            if (!hearts) {
                starEdge.strokeWidth = gw * 0.014f
                canvas.drawPath(path, starEdge)
            }
            canvas.drawCircle(x - size * 0.32f, eyeY - size * 0.28f, size * 0.13f, glint)
        }
        canvas.drawLine(cx - eyeDx + size * 0.7f, eyeY - size * 0.25f, cx + eyeDx - size * 0.7f, eyeY - size * 0.25f, frame)
    }

    private fun heartPath(x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x, y + s * 0.85f)
        path.cubicTo(x - s * 1.25f, y + s * 0.05f, x - s * 0.75f, y - s * 0.95f, x, y - s * 0.35f)
        path.cubicTo(x + s * 0.75f, y - s * 0.95f, x + s * 1.25f, y + s * 0.05f, x, y + s * 0.85f)
        path.close()
    }

    private fun starPath(x: Float, y: Float, s: Float) {
        path.reset()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) s else s * 0.5f
            val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
            val px = x + cos(a) * rr
            val py = y + sin(a) * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    // endregion

    // region neck

    private fun drawBowTie(canvas: Canvas, cx: Float, y: Float, gw: Float) {
        for (side in SIDES) {
            path.reset()
            path.moveTo(cx, y)
            path.lineTo(cx + side * gw * 0.14f, y - gw * 0.075f)
            path.quadTo(cx + side * gw * 0.16f, y, cx + side * gw * 0.14f, y + gw * 0.075f)
            path.close()
            canvas.drawPath(path, bowTie)
        }
        rect.set(cx - gw * 0.04f, y - gw * 0.045f, cx + gw * 0.04f, y + gw * 0.045f)
        canvas.drawRoundRect(rect, gw * 0.02f, gw * 0.02f, bowTieKnot)
    }

    private fun drawScarf(canvas: Canvas, cx: Float, y: Float, gw: Float, phase: Float) {
        val half = gw * 0.52f
        val sag = gw * 0.035f
        val thick = gw * 0.1f
        // The wrap: a band that dips in the middle, like it is resting on him.
        path.reset()
        path.moveTo(cx - half, y - thick / 2f)
        path.quadTo(cx, y - thick / 2f + sag * 2f, cx + half, y - thick / 2f)
        path.lineTo(cx + half, y + thick / 2f)
        path.quadTo(cx, y + thick / 2f + sag * 2f, cx - half, y + thick / 2f)
        path.close()
        canvas.drawPath(path, scarf)
        for (i in -2..2) {
            val x = cx + i * gw * 0.2f
            val dip = sag * (1f - (i * i) / 6f)
            rect.set(x - gw * 0.035f, y - thick / 2f + dip, x + gw * 0.035f, y + thick / 2f + dip)
            canvas.drawRect(rect, scarfStripe)
        }
        // The loose end, swinging a little as he drifts.
        val tx = cx + gw * 0.2f
        val swing = sin(phase * 2.6f) * 5f
        canvas.save()
        canvas.rotate(-8f + swing, tx, y)
        rect.set(tx - gw * 0.065f, y, tx + gw * 0.065f, y + gw * 0.24f)
        canvas.drawRoundRect(rect, gw * 0.03f, gw * 0.03f, scarf)
        rect.set(tx - gw * 0.065f, y + gw * 0.1f, tx + gw * 0.065f, y + gw * 0.14f)
        canvas.drawRect(rect, scarfStripe)
        scarfFringe.strokeWidth = gw * 0.018f
        for (i in -1..1) {
            val fx = tx + i * gw * 0.04f
            canvas.drawLine(fx, y + gw * 0.24f, fx, y + gw * 0.29f, scarfFringe)
        }
        canvas.restore()
    }

    private fun drawBell(canvas: Canvas, cx: Float, y: Float, gw: Float, phase: Float) {
        val half = gw * 0.5f
        val thick = gw * 0.045f
        path.reset()
        path.moveTo(cx - half, y - thick)
        path.quadTo(cx, y + gw * 0.05f - thick, cx + half, y - thick)
        path.lineTo(cx + half, y)
        path.quadTo(cx, y + gw * 0.05f, cx - half, y)
        path.close()
        canvas.drawPath(path, collar)
        val bellY = y + gw * 0.07f
        canvas.save()
        canvas.rotate(sin(phase * 3.1f) * 12f, cx, y + gw * 0.02f)
        canvas.drawCircle(cx, bellY, gw * 0.075f, gold)
        goldEdge.strokeWidth = gw * 0.012f
        canvas.drawCircle(cx, bellY, gw * 0.075f, goldEdge)
        bellSlit.strokeWidth = gw * 0.014f
        canvas.drawLine(cx - gw * 0.04f, bellY + gw * 0.02f, cx + gw * 0.04f, bellY + gw * 0.02f, bellSlit)
        canvas.drawCircle(cx, bellY + gw * 0.045f, gw * 0.016f, bellSlit)
        canvas.drawCircle(cx - gw * 0.025f, bellY - gw * 0.03f, gw * 0.018f, glint)
        canvas.restore()
    }

    // endregion

    companion object {
        /** How far a hat stands above his crown, as a fraction of his width — what the speech
         *  bubble has to clear. */
        fun hatHeight(id: String?): Float = when (id) {
            "head_party" -> 0.36f
            "head_witch" -> 0.4f
            "head_crown" -> 0.18f
            "head_halo" -> 0.2f
            "head_beanie" -> 0.12f
            "head_flowers" -> 0.06f
            else -> 0f
        }

        private val SIDES = intArrayOf(-1, 1)
        private const val DEG = (PI / 180.0).toFloat()

        /** Fractions of the party hat's height that are striped. */
        private val STRIPES = listOf(0.18f to 0.32f, 0.5f to 0.62f)

        /** Round his dome in canvas degrees — 270 is straight up. */
        private val FLOWER_ANGLES = floatArrayOf(208f, 238f, 270f, 302f, 332f)
        private val LEAF_ANGLES = floatArrayOf(222f, 254f, 286f, 318f)
    }
}
