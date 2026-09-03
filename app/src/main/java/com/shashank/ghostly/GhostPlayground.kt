package com.shashank.ghostly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * (and after) it is set loose over everything else. A quick tap still spooks him; holding still on
 * him instead pets him. [startFetch] runs a little fetch game here when Play is tapped from the
 * settings screen — a toy appears, he chases it, and catches it.
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

    /**
     * He is out on the overlay, so the box is empty.
     *
     * There is only ever one ghost: the box holds him until "Let him float" sends him out, and
     * "Call him home" brings him back. Drawing him in both places at once was the bug this fixes.
     */
    private var away = false
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3A3A46")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 11f), 0f)
    }
    private val awayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8C8C99")
        textAlign = Paint.Align.CENTER
    }
    private val emptyPath = Path()

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }

    private var running = false

    // Petting: a hold that starts and stays on him, as opposed to a quick poke or a drag past him.
    private val petHandler = Handler(Looper.getMainLooper())
    private var pettingArmed = false
    private var petTriggered = false
    private var downOnHim = false
    private var lastPetAt = 0L
    private val petRunnable = Runnable {
        if (!pettingArmed) return@Runnable
        petTriggered = true
        pet()
    }

    // Fetch: a toy to chase, started from the Play button.
    private var fetching = false
    private var toyX = 0f
    private var toyY = 0f
    private var fetchEndsAt = 0f
    private val toyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD166") }
    private val toyRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#66C9A227")
    }

    // Feeding: a treat drops from a corner, he sprints to it, then eats — started from Feed/Treat.
    private var feedState = FeedState.NONE
    private var treatX = 0f
    private var treatY = 0f
    private var treatVelY = 0f
    private var treatLandY = 0f
    private var eatEndsAt = 0f
    private val treatDrawable = IconDrawable(IconGlyph.TREAT, Color.parseColor("#E8B84F"))

    private enum class FeedState { NONE, FALLING, CHASING, EATING }

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
        ghost.species = Prefs.species(context)
        ghost.setTint(Prefs.colorHue(context))
        // Wider than he is on purpose: the fixed-size bubble spills into the side room.
        ghost.setBodySize(size)
        addView(
            ghost,
            LayoutParams(
                size + GhostView.bubbleSidePx(density) * 2,
                size + GhostView.headroomPx(density, size)
            )
        )
    }

    /** Called by the settings screen when the character picker changes. */
    /** Called by the app whenever the overlay starts or stops. */
    fun setAway(value: Boolean) {
        if (away == value) return
        away = value
        ghost.visibility = if (value) INVISIBLE else VISIBLE
        if (!value) {
            // He comes home to the middle of the box rather than wherever he was left.
            posX = (width - size) / 2f
            posY = (height - size) / 2f
            velX = 0f
            velY = 0f
            apply()
            resume()
        }
        invalidate()
    }

    fun setSpecies(species: Species) {
        ghost.species = species
        ghost.invalidate()
    }

    /** Called by the settings screen when the colour swatch changes. */
    fun setShade(shade: Shade) {
        ghost.setShade(shade)
    }

    fun setTint(hue: Float?) {
        ghost.setTint(hue)
    }

    /** Drops a toy in for him to chase — called when Play succeeds. Purely a visual flourish; the
     *  actual stat effects are already applied by the time this runs. */
    fun startFetch() {
        if (!placed || width <= 0 || height <= 0) return
        val margin = size * 0.6f
        toyX = margin + Random.nextFloat() * (width - margin * 2f).coerceAtLeast(1f)
        toyY = margin + Random.nextFloat() * (height - margin * 2f).coerceAtLeast(1f)
        fetching = true
        fetchEndsAt = clock + FETCH_TIMEOUT_SECONDS
        ghost.notice()
    }

    /** Drops a treat from a corner for him to sprint after and eat — called on Feed/Treat. Purely
     *  a visual flourish; the actual stat effects are already applied by the time this runs. */
    fun startFeeding() {
        ghost.showExpression(Expression.SMILE, 2.0f)
        vocalise("hungry")
        if (!placed || width <= 0 || height <= 0) return
        val margin = size * 0.4f
        treatX = if (Random.nextBoolean()) margin else width - margin
        treatY = -size * 0.3f
        treatVelY = 0f
        treatLandY = height * (0.55f + Random.nextFloat() * 0.15f)
        feedState = FeedState.FALLING
        ghost.notice()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        petHandler.removeCallbacks(petRunnable)
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
        if (away) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lookAt(event.x, event.y)
                downOnHim = isOnGhost(event.x, event.y)
                petTriggered = false
                // Only a touch that actually lands on him counts — a miss nearby is not an
                // interaction, so it neither spooks nor arms petting.
                if (downOnHim) {
                    pettingArmed = true
                    petHandler.postDelayed(petRunnable, PET_HOLD_MS)
                } else {
                    pettingArmed = false
                }
                performClick()
                return true
            }
            // In here we get real coordinates, so he can properly follow your finger around.
            MotionEvent.ACTION_MOVE -> {
                lookAt(event.x, event.y)
                nextGlanceAt = clock + 1.5f
                if (pettingArmed && !isOnGhost(event.x, event.y)) {
                    pettingArmed = false
                    petHandler.removeCallbacks(petRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                petHandler.removeCallbacks(petRunnable)
                // Landed on him but let go before the hold fired: that's a poke, not a pet.
                if (downOnHim && !petTriggered) fleeFrom(event.x, event.y)
                pettingArmed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isOnGhost(x: Float, y: Float): Boolean {
        val cx = posX + size / 2f
        val cy = posY + size / 2f
        return hypot(x - cx, y - cy) <= size * 0.65f
    }

    /** A hand strokes his head for a couple of seconds; still held after that, it happens again. */
    /** What he says, in his own species' voice. */
    private fun vocalise(kind: String) {
        val species = Prefs.species(context)
        val text = when (kind) {
            "happy" -> when (species) {
                Species.CAT -> "Purr~"
                Species.DOG -> "Woof!"
                Species.GHOST -> "boo-oo"
            }
            "hungry" -> when (species) {
                Species.CAT -> "Meow"
                Species.DOG -> "Woof?"
                Species.GHOST -> "boo?"
            }
            else -> when (species) {
                Species.CAT -> "mrr"
                Species.DOG -> "wf"
                Species.GHOST -> "boo…"
            }
        }
        ghost.showBubble(text, 1.9f)
    }

    private fun pet() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPetAt < PET_ANIMATION_MS) return
        lastPetAt = now
        val s = PetStats.snapshot(context)
        val happiness = (s.happiness + 3f).coerceAtMost(PetStats.MAX)
        Prefs.saveStats(context, s.hunger, s.energy, happiness, s.sleeping, System.currentTimeMillis())
        ghost.startPetting()
        ghost.showExpression(Expression.DELIGHTED, 2.4f)
        vocalise("happy")
        // Still held: keep ticking affection for as long as the finger stays put.
        petHandler.postDelayed(petRunnable, PET_ANIMATION_MS)
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
        if (fetching) {
            val r = size * 0.16f
            canvas.drawCircle(toyX, toyY, r, toyPaint)
            canvas.drawCircle(toyX, toyY, r, toyRimPaint)
        }
        if (feedState == FeedState.FALLING || feedState == FeedState.CHASING) {
            val r = (size * 0.18f).toInt()
            treatDrawable.setBounds((treatX - r).toInt(), (treatY - r).toInt(), (treatX + r).toInt(), (treatY + r).toInt())
            treatDrawable.draw(canvas)
        }
        if (away) {
            drawEmpty(canvas)
            return
        }
        canvas.drawText("hold him to pet him \u00b7 this box is his", width / 2f, height - 18f * density, hintPaint)
    }

    /** The shape of him, in dashes, so the box reads as vacated rather than broken. */
    private fun drawEmpty(canvas: Canvas) {
        val s = size * 1.25f
        val left = (width - s) / 2f
        val top = (height - s) / 2f - s * 0.06f
        emptyPaint.strokeWidth = s * 0.028f
        emptyPath.reset()
        val pad = s * 0.10f
        val gw = s - pad * 2f
        val r = gw / 2f
        val cx = left + pad + r
        val rect = RectF(left + pad, top + pad, left + pad + gw, top + pad + gw)
        emptyPath.addArc(rect, 180f, 180f)
        val waveTop = top + s - pad - gw * 0.22f
        val hw = gw / 3f
        emptyPath.lineTo(left + pad + gw, waveTop)
        for (i in 0 until 3) {
            val x0 = left + pad + gw - i * hw
            val x1 = x0 - hw
            emptyPath.cubicTo(x0 - hw * 0.12f, waveTop + gw * 0.26f, x1 + hw * 0.12f, waveTop + gw * 0.26f, x1, waveTop)
        }
        emptyPath.lineTo(left + pad, top + pad + r)
        emptyPath.close()
        canvas.drawPath(emptyPath, emptyPaint)

        awayTextPaint.typeface = Type.serifItalic(context)
        awayTextPaint.textSize = 19f * density
        canvas.drawText("out there somewhere", width / 2f, top + s + 34f * density, awayTextPaint)
        canvas.drawText("drifting over your apps", width / 2f, height - 18f * density, hintPaint)
        java.util.Objects.hash(cx)
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
        if (!placed || away) return

        if (feedState != FeedState.NONE) {
            tickFeeding(dt)
            return
        }

        if (fetching) {
            tickFetch(dt)
            return
        }

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

    /** He beelines for the toy instead of idle-wandering, until he reaches it or time runs out. */
    private fun tickFetch(dt: Float) {
        val cx = posX + size / 2f
        val cy = posY + size / 2f
        val dx = toyX - cx
        val dy = toyY - cy
        val dist = hypot(dx, dy)
        val caught = dist < size * 0.55f

        if (caught || clock > fetchEndsAt) {
            fetching = false
            if (caught) ghost.spook() // a happy little bounce for the catch
            ghost.advance(dt)
            ghost.invalidate()
            invalidate() // the toy itself is drawn by this view, not the ghost — it needs its own redraw
            return
        }

        val eagerSpeed = driftSpeed * 6f
        val settle = 1f - exp(-2.2f * dt)
        velX += (dx / dist * eagerSpeed - velX) * settle
        velY += (dy / dist * eagerSpeed - velY) * settle
        posX += velX * dt
        posY += velY * dt

        val maxX = (width - size).toFloat()
        val maxY = (height - size).toFloat()
        posX = posX.coerceIn(0f, maxX)
        posY = posY.coerceIn(0f, maxY)

        ghost.setMotion(velX, velY)
        ghost.lookAt(dx / dist, dy / dist)
        ghost.advance(dt)
        ghost.invalidate()
        invalidate() // the toy itself is drawn by this view, not the ghost — it needs its own redraw
        apply()
    }

    /** Falls to a landing spot, then he sprints over and eats — see [startFeeding]. */
    private fun tickFeeding(dt: Float) {
        when (feedState) {
            FeedState.FALLING -> {
                treatVelY += GRAVITY * density * dt
                treatY += treatVelY * dt
                if (treatY >= treatLandY) {
                    treatY = treatLandY
                    feedState = FeedState.CHASING
                }
                ghost.advance(dt)
                ghost.invalidate()
                invalidate() // the treat itself is drawn by this view, not the ghost
            }
            FeedState.CHASING -> {
                val cx = posX + size / 2f
                val cy = posY + size / 2f
                val dx = treatX - cx
                val dy = treatY - cy
                val dist = hypot(dx, dy)
                if (dist < size * 0.5f) {
                    feedState = FeedState.EATING
                    eatEndsAt = clock + EAT_DURATION
                    velX = 0f
                    velY = 0f
                    ghost.setMotion(0f, 0f)
                    ghost.startEating(EAT_DURATION)
                    invalidate() // treat is gone now — clear its last drawn position
                } else {
                    val sprintSpeed = driftSpeed * 9f
                    val settle = 1f - exp(-3f * dt)
                    velX += (dx / dist * sprintSpeed - velX) * settle
                    velY += (dy / dist * sprintSpeed - velY) * settle
                    posX += velX * dt
                    posY += velY * dt
                    val maxX = (width - size).toFloat()
                    val maxY = (height - size).toFloat()
                    posX = posX.coerceIn(0f, maxX)
                    posY = posY.coerceIn(0f, maxY)
                    ghost.setMotion(velX, velY)
                    ghost.lookAt(dx / dist, dy / dist)
                    apply()
                }
                ghost.advance(dt)
                ghost.invalidate()
            }
            FeedState.EATING -> {
                if (clock > eatEndsAt) feedState = FeedState.NONE
                ghost.advance(dt)
                ghost.invalidate()
            }
            FeedState.NONE -> Unit
        }
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

    /**
     * posX/posY are his BODY's top-left. The view itself is taller than his body — the extra strip
     * is bubble headroom — so it is shifted up by that much when placed.
     */
    private fun apply() {
        ghost.translationX = posX - sideRoom
        ghost.translationY = posY - headroom
    }

    private val headroom: Float get() = GhostView.headroomPx(density, size).toFloat()

    /** Blank room either side of his body inside the view — see the bubble note in [GhostView]. */
    private val sideRoom: Float get() = GhostView.bubbleSidePx(density).toFloat()

    private companion object {
        const val PET_HOLD_MS = 1_000L
        const val PET_ANIMATION_MS = 2_000L
        const val FETCH_TIMEOUT_SECONDS = 6f
        const val GRAVITY = 900f
        const val EAT_DURATION = 1.6f
    }
}
