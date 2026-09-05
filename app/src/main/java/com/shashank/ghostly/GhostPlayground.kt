package com.shashank.ghostly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    /** Set by [setMood] (from the Home tab's periodic refresh) — while true, idle wandering is
     *  suspended so a sleeping pet actually reads as asleep here instead of drifting around. */
    private var asleep = false

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

    // Fetch: a toy to chase, grab, and carry back home — started from the Play button.
    private var fetchState = FetchState.NONE
    private var toyX = 0f
    private var toyY = 0f
    private var fetchEndsAt = 0f
    private var grabEndsAt = 0f
    private var fetchHomeX = 0f
    private var fetchHomeY = 0f
    private val toyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD166") }
    private val toyRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#66C9A227")
    }

    private enum class FetchState { NONE, CHASING, GRABBING, RETURNING }

    // Delivery: a treat or gift drops from a corner, he sprints to it, then reacts — started from
    // Feed/Treat/Gift.
    private var deliveryState = DeliveryState.NONE
    private var deliveryKind = DeliveryKind.TREAT
    private var itemX = 0f
    private var itemY = 0f
    private var itemVelY = 0f
    private var itemLandY = 0f
    private var reactionEndsAt = 0f
    private val treatDrawable = IconDrawable(IconGlyph.TREAT, Color.parseColor("#E8B84F"))
    private val giftDrawable = IconDrawable(IconGlyph.GIFT, Color.parseColor("#E86BA8"))

    private enum class DeliveryState { NONE, FALLING, CHASING, REACTING }
    private enum class DeliveryKind { TREAT, GIFT }

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
        addView(ghost, LayoutParams(size, size))
    }

    /** Called by the settings screen when the character picker changes. */
    fun setSpecies(species: Species) {
        ghost.species = species
        ghost.invalidate()
    }

    /** Called by the settings screen when the colour swatch changes. */
    fun setTint(hue: Float?) {
        ghost.setTint(hue)
    }

    /** Called from the Home tab's periodic refresh so this preview actually reflects his current
     *  mood and sleep state, instead of always drawing an awake, drifting pet. */
    fun setMood(mood: Mood, sleeping: Boolean) {
        asleep = sleeping
        ghost.setMood(mood, sleeping)
    }

    /** Drops a toy in for him to chase, grab, and carry back home — called when Play succeeds.
     *  Purely a visual flourish; the actual stat effects are already applied by the time this
     *  runs. */
    fun startFetch() {
        if (!placed || width <= 0 || height <= 0) return
        val margin = size * 0.6f
        toyX = margin + Random.nextFloat() * (width - margin * 2f).coerceAtLeast(1f)
        toyY = margin + Random.nextFloat() * (height - margin * 2f).coerceAtLeast(1f)
        fetchHomeX = posX
        fetchHomeY = posY
        fetchState = FetchState.CHASING
        fetchEndsAt = clock + FETCH_TIMEOUT_SECONDS
        ghost.notice()
    }

    /** Drops a treat from a corner for him to sprint after and eat — called on Feed/Treat. Purely
     *  a visual flourish; the actual stat effects are already applied by the time this runs. */
    fun startFeeding() = startDelivery(DeliveryKind.TREAT)

    /** Drops a gift from a corner for him to sprint after and unwrap — called on Gift. Purely a
     *  visual flourish; the actual stat effects are already applied by the time this runs. */
    fun startGift() = startDelivery(DeliveryKind.GIFT)

    private fun startDelivery(kind: DeliveryKind) {
        if (!placed || width <= 0 || height <= 0) return
        deliveryKind = kind
        val margin = size * 0.4f
        itemX = if (Random.nextBoolean()) margin else width - margin
        itemY = -size * 0.3f
        itemVelY = 0f
        itemLandY = height * (0.55f + Random.nextFloat() * 0.15f)
        deliveryState = DeliveryState.FALLING
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
    private fun pet() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPetAt < PET_ANIMATION_MS) return
        lastPetAt = now
        val s = PetStats.snapshot(context)
        val happiness = (s.happiness + 3f).coerceAtMost(PetStats.MAX)
        Prefs.saveStats(context, s.hunger, s.energy, happiness, s.sleeping, System.currentTimeMillis())
        ghost.startPetting()
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
        if (fetchState != FetchState.NONE) {
            val r = size * 0.16f
            canvas.drawCircle(toyX, toyY, r, toyPaint)
            canvas.drawCircle(toyX, toyY, r, toyRimPaint)
        }
        if (deliveryState == DeliveryState.FALLING || deliveryState == DeliveryState.CHASING) {
            val r = (size * 0.18f).toInt()
            val drawable = if (deliveryKind == DeliveryKind.TREAT) treatDrawable else giftDrawable
            drawable.setBounds((itemX - r).toInt(), (itemY - r).toInt(), (itemX + r).toInt(), (itemY + r).toInt())
            drawable.draw(canvas)
        }
        canvas.drawText("tap to spook him, hold to pet him", width / 2f, height - 18f * density, hintPaint)
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

        if (deliveryState != DeliveryState.NONE) {
            tickDelivery(dt)
            return
        }

        if (fetchState != FetchState.NONE) {
            tickFetch(dt)
            return
        }

        if (asleep) {
            // Settle to a stop and stay put rather than drifting off mid-nap — same rule as the
            // overlay.
            val settle = 1f - exp(-2.5f * dt)
            velX -= velX * settle
            velY -= velY * settle
            posX += velX * dt
            posY += velY * dt
            ghost.setMotion(velX, velY)
            ghost.advance(dt)
            ghost.invalidate()
            apply()
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

    /** He beelines for the toy, grabs it in his mouth, then carries it back home before letting
     *  go — a proper fetch, not just a chase. */
    private fun tickFetch(dt: Float) {
        when (fetchState) {
            FetchState.CHASING -> {
                val cx = posX + size / 2f
                val cy = posY + size / 2f
                val dx = toyX - cx
                val dy = toyY - cy
                val dist = hypot(dx, dy)
                if (dist < size * 0.55f) {
                    fetchState = FetchState.GRABBING
                    grabEndsAt = clock + GRAB_DURATION
                    velX = 0f
                    velY = 0f
                    ghost.setMotion(0f, 0f)
                    ghost.startGrab()
                    invalidate()
                } else if (clock > fetchEndsAt) {
                    // Gave up — the toy just vanishes rather than making him trail an unclaimed one.
                    fetchState = FetchState.NONE
                    invalidate()
                } else {
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
                    apply()
                    invalidate() // the toy itself is drawn by this view, not the ghost
                }
                ghost.advance(dt)
                ghost.invalidate()
            }
            FetchState.GRABBING -> {
                // Held in his mouth while he savours the catch.
                toyX = posX + size / 2f
                toyY = posY + size * 0.62f
                if (clock > grabEndsAt) fetchState = FetchState.RETURNING
                ghost.advance(dt)
                ghost.invalidate()
                invalidate()
            }
            FetchState.RETURNING -> {
                val dx = fetchHomeX - posX
                val dy = fetchHomeY - posY
                val dist = hypot(dx, dy)
                toyX = posX + size / 2f
                toyY = posY + size * 0.62f
                if (dist < size * 0.25f) {
                    fetchState = FetchState.NONE
                    ghost.startWiggle()
                    ghost.spawnHeart()
                    invalidate()
                } else {
                    val speed = driftSpeed * 5f
                    val settle = 1f - exp(-2.2f * dt)
                    velX += (dx / dist * speed - velX) * settle
                    velY += (dy / dist * speed - velY) * settle
                    posX += velX * dt
                    posY += velY * dt
                    val maxX = (width - size).toFloat()
                    val maxY = (height - size).toFloat()
                    posX = posX.coerceIn(0f, maxX)
                    posY = posY.coerceIn(0f, maxY)
                    ghost.setMotion(velX, velY)
                    ghost.lookAt(dx / dist, dy / dist)
                    apply()
                    invalidate()
                }
                ghost.advance(dt)
                ghost.invalidate()
            }
            FetchState.NONE -> Unit
        }
    }

    /** Falls to a landing spot, then he sprints over and reacts — eating a treat, or unwrapping a
     *  gift — see [startFeeding]/[startGift]. */
    private fun tickDelivery(dt: Float) {
        when (deliveryState) {
            DeliveryState.FALLING -> {
                itemVelY += GRAVITY * density * dt
                itemY += itemVelY * dt
                if (itemY >= itemLandY) {
                    itemY = itemLandY
                    deliveryState = DeliveryState.CHASING
                }
                ghost.advance(dt)
                ghost.invalidate()
                invalidate() // the item itself is drawn by this view, not the ghost
            }
            DeliveryState.CHASING -> {
                val cx = posX + size / 2f
                val cy = posY + size / 2f
                val dx = itemX - cx
                val dy = itemY - cy
                val dist = hypot(dx, dy)
                if (dist < size * 0.5f) {
                    deliveryState = DeliveryState.REACTING
                    reactionEndsAt = clock + REACTION_DURATION
                    velX = 0f
                    velY = 0f
                    ghost.setMotion(0f, 0f)
                    when (deliveryKind) {
                        DeliveryKind.TREAT -> ghost.startEating(REACTION_DURATION)
                        DeliveryKind.GIFT -> ghost.startGiftJoy(REACTION_DURATION)
                    }
                    invalidate() // item is gone now — clear its last drawn position
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
            DeliveryState.REACTING -> {
                if (clock > reactionEndsAt) deliveryState = DeliveryState.NONE
                ghost.advance(dt)
                ghost.invalidate()
            }
            DeliveryState.NONE -> Unit
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

    private fun apply() {
        ghost.translationX = posX
        ghost.translationY = posY
    }

    private companion object {
        const val PET_HOLD_MS = 1_000L
        const val PET_ANIMATION_MS = 2_000L
        const val FETCH_TIMEOUT_SECONDS = 6f
        const val GRAB_DURATION = 0.6f
        const val GRAVITY = 900f
        const val REACTION_DURATION = 1.6f
    }
}
