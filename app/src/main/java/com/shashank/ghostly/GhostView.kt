package com.shashank.ghostly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The little fellow himself: a translucent ghost body with two googly eyes.
 *
 * Everything is sized off the view's own width, so he stays in proportion at any size — and he is
 * small now, which means the face is drawn a touch oversized and the fussier details (glints,
 * blush) drop out below the size where they would just turn to mush.
 *
 * The view only draws. Where the ghost *is* on screen is the overlay service's job; this class
 * reacts to the motion it is told about (lean, squash, wide eyes when startled) and adds the
 * never-quite-still part: bobbing, a slow sway, and a hem that ripples as he drifts.
 */
class GhostView(context: Context) : View(context) {

    /** How solid the body is. Low enough to read as a ghost, high enough to see on a busy screen. */
    private val bodyAlpha = 188

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = bodyAlpha
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** A see-through body disappears on a white screen; the outline keeps his shape readable. */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#8C6C63C9")
    }
    private val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5EDEFFF") }
    private val scleraRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#556C63C9")
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF15122B") }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D915122B") }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3DFF7BC1") }

    /** The glow, as a plain gradient. `setShadowLayer` would look similar but forces the whole view
     *  through software rendering every frame, which is far too expensive to run all day. */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bodyPath = Path()
    private val rect = RectF()
    private var shadeShaderFor = -1f
    private val density = resources.displayMetrics.density

    private var phase = 0f

    // Blinking
    private var blinkProgress = 1f // 1 = fully open
    private var nextBlinkAt = 1.4f
    private var blinkStartedAt = -1f

    // Set by the owner every frame.
    private var velX = 0f
    private var velY = 0f

    /** Where the eyes point, in view-relative units (-1..1). */
    private var lookX = 0f
    private var lookY = 0f
    private var targetLookX = 0f
    private var targetLookY = 0f

    /** 1 while freshly spooked, decays back to 0. */
    private var startle = 0f

    /** 1 right after he notices something: eyes wide for a beat, then back to normal. */
    private var alert = 0f

    /** Eyes snap to a new target and settle, the way real ones do, rather than gliding smoothly. */
    private var saccade = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Glow and outline scale with the body so neither swamps a small ghost.
        outlinePaint.strokeWidth = w * 0.035f
        if (w > 0 && h > 0) {
            glowPaint.shader = RadialGradient(
                w / 2f, h * 0.46f, w * 0.52f,
                intArrayOf(
                    Color.parseColor("#5567E8FF"),
                    Color.parseColor("#2867E8FF"),
                    Color.parseColor("#0067E8FF")
                ),
                floatArrayOf(0.42f, 0.66f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        scleraRimPaint.strokeWidth = w * 0.012f
    }

    /** Called by the owner with the ghost's current velocity in px/s. */
    fun setMotion(vx: Float, vy: Float) {
        velX = vx
        velY = vy
    }

    /** Point the eyes somewhere, as a direction from him (-1..1 on each axis). */
    fun lookAt(nx: Float, ny: Float) {
        val tx = nx.coerceIn(-1f, 1f)
        val ty = ny.coerceIn(-1f, 1f)
        // A real gaze shift is a flick, not a drift: mark big changes so they snap across.
        if (abs(tx - targetLookX) + abs(ty - targetLookY) > 0.35f) saccade = 1f
        targetLookX = tx
        targetLookY = ty
    }

    /** Something happened on screen: eyes open a little wider for a moment. */
    fun notice() {
        alert = 1f
        if (blinkStartedAt < 0f) nextBlinkAt = phase + 1.4f
    }

    /** A small bump — used when the ghost hits the edge of the screen. */
    fun spookLightly() {
        startle = maxOf(startle, 0.45f)
    }

    /** Wide eyes + open mouth for a moment. */
    fun spook() {
        startle = 1f
        blinkProgress = 1f
        blinkStartedAt = -1f
        nextBlinkAt = phase + 1.2f
    }

    /**
     * Advance the animation by [dt] seconds. Called by whoever owns the frame loop — the overlay
     * service on screen, or the playground inside the app.
     */
    fun advance(dt: Float) {
        phase += dt
        startle = (startle - 1.3f * dt).coerceAtLeast(0f)
        alert = (alert - 1.1f * dt).coerceAtLeast(0f)
        // Fast while the flick is in progress, slow and steady once the eyes are on target.
        val rate = 1f - kotlin.math.exp(-(10f + 30f * saccade) * dt)
        saccade = (saccade - 5.4f * dt).coerceAtLeast(0f)
        lookX += (targetLookX - lookX) * rate
        lookY += (targetLookY - lookY) * rate

        if (blinkStartedAt < 0f && phase >= nextBlinkAt) {
            blinkStartedAt = phase
        }
        if (blinkStartedAt >= 0f) {
            val t = (phase - blinkStartedAt) / 0.16f
            if (t >= 1f) {
                blinkProgress = 1f
                blinkStartedAt = -1f
                nextBlinkAt = phase + 2.2f + (Math.random() * 3.0).toFloat()
            } else {
                // Down and back up.
                blinkProgress = abs(1f - 2f * t)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val speed = hypot(velX, velY)
        val fast = min(1f, speed / 420f)

        // Always-on idle motion: a bob, a slow sway, and a breath.
        val bob = sin(phase * 2.4f) * h * 0.035f
        val sway = sin(phase * 1.35f) * 3.2f * (1f - fast)
        val breath = 1f + sin(phase * 1.9f) * 0.022f

        val lean = (velX / 900f).coerceIn(-0.32f, 0.32f)
        val stretch = min(0.14f, speed / 4200f)

        canvas.save()
        canvas.translate(0f, bob)
        canvas.rotate(sway + lean * 26f, w / 2f, h * 0.75f)
        canvas.scale((1f - stretch) * breath, (1f + stretch) * breath, w / 2f, h)

        val pad = w * 0.10f
        val left = pad
        val right = w - pad
        val top = pad
        val bottom = h - pad
        val gw = right - left
        val r = gw / 2f
        val cx = left + r

        // Body: domed head, straight sides, three hem waves that ripple as he moves.
        bodyPath.reset()
        rect.set(left, top, right, top + gw)
        bodyPath.addArc(rect, 180f, 180f)
        val waveTop = bottom - gw * 0.22f
        val humps = 3
        val hw = gw / humps
        val ripple = gw * (0.05f + 0.05f * fast)
        val rippleSpeed = 4.2f + 5f * fast
        bodyPath.lineTo(right, waveTop + sin(phase * rippleSpeed) * ripple)
        for (i in 0 until humps) {
            val x0 = right - i * hw
            val x1 = x0 - hw
            val drop = gw * 0.26f + sin(phase * rippleSpeed - i * 1.1f) * ripple
            val endY = waveTop + sin(phase * rippleSpeed - (i + 1) * 1.1f) * ripple
            bodyPath.cubicTo(
                x0 - hw * 0.12f, waveTop + drop,
                x1 + hw * 0.12f, waveTop + drop,
                x1, endY
            )
        }
        bodyPath.lineTo(left, top + r)
        bodyPath.close()

        canvas.drawCircle(w / 2f, h * 0.46f, w * 0.52f, glowPaint)
        canvas.drawPath(bodyPath, bodyPaint)
        canvas.drawPath(bodyPath, outlinePaint)

        // Soft inner shading so the body reads as volume rather than a flat blob. The shader only
        // depends on the size, so it is built once rather than sixty times a second.
        if (shadeShaderFor != gw) {
            shadeShaderFor = gw
            shadePaint.shader = RadialGradient(
                cx - r * 0.35f, top + r * 0.55f, gw * 1.05f,
                intArrayOf(Color.parseColor("#00FFFFFF"), Color.parseColor("#3D6C63C9")),
                floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.save()
        canvas.clipPath(bodyPath)
        canvas.drawRect(0f, 0f, w, h, shadePaint)
        canvas.restore()

        // Face. Deliberately oversized — at this size a subtle face just disappears.
        // Each eye is a pale sclera with a dark pupil that actually travels inside it, so you can
        // see what he is looking at from across the room.
        val eyeR = gw * (0.175f + 0.03f * startle + 0.02f * alert)
        val eyeY = top + r * (1.0f - 0.05f * startle)
        val eyeDx = gw * 0.235f
        val pupilR = eyeR * 0.5f
        val travel = eyeR - pupilR * 1.12f
        val sx = lookX * travel
        val sy = lookY * travel
        val detailed = gw > 26f * density

        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * eyeDx
            val squish = blinkProgress.coerceAtLeast(0.06f)
            canvas.save()
            canvas.scale(1f, squish, ex, eyeY)

            canvas.drawCircle(ex, eyeY, eyeR, scleraPaint)
            canvas.drawCircle(ex, eyeY, eyeR, scleraRimPaint)
            canvas.drawCircle(ex + sx, eyeY + sy, pupilR, pupilPaint)
            if (detailed) {
                canvas.drawCircle(
                    ex + sx - pupilR * 0.34f, eyeY + sy - pupilR * 0.38f,
                    pupilR * 0.34f, glintPaint
                )
            }
            canvas.restore()
        }

        // Mouth: a dot at rest, a little "o" when spooked.
        val mouthW = gw * (0.085f + 0.1f * startle)
        val mouthH = gw * (0.05f + 0.15f * startle)
        val mouthY = eyeY + eyeR * 1.9f
        rect.set(
            cx - mouthW / 2f + sx * 0.5f, mouthY - mouthH / 2f,
            cx + mouthW / 2f + sx * 0.5f, mouthY + mouthH / 2f
        )
        canvas.drawOval(rect, mouthPaint)

        if (detailed) {
            canvas.drawCircle(cx - eyeDx - eyeR * 0.55f, mouthY, eyeR * 0.45f, blushPaint)
            canvas.drawCircle(cx + eyeDx + eyeR * 0.55f, mouthY, eyeR * 0.45f, blushPaint)
        }

        canvas.restore()
    }
}
