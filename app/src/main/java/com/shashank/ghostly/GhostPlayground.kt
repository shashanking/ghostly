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
import kotlin.math.abs
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
    private var size = (Prefs.sizeDp(context) * density).toInt()
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

    /**
     * Touched while he is out floating. The box is his one place to be handled, so rather than
     * ignoring the touch it asks whoever owns the box to bring him in for a moment.
     */
    var onSummon: (() -> Unit)? = null
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
        // Wider than he is on purpose: the fixed-size bubble spills into the side room.
        ghost.setBodySize(size)
        addView(
            ghost,
            LayoutParams(
                size + GhostView.bubbleSidePx(density, size) * 2,
                size + GhostView.headroomPx(density, size) + GhostView.haloPadPx(size)
            )
        )
    }

    /** Called by the settings screen when the character picker changes. */
    /** Called by the app whenever the overlay starts or stops. */
    fun setAway(value: Boolean) {
        if (away == value) return
        away = value
        if (value) {
            // He has just been handed to the overlay, which is already drawing him in this exact
            // spot — so this fades out under him rather than blinking him away.
            ghost.animate().alpha(0f).setDuration(HANDOVER_MS).withEndAction {
                if (away) ghost.visibility = INVISIBLE
            }.start()
        } else {
            // He comes home to the middle of the box unless he was placed somewhere first.
            if (!entryPlaced) {
                posX = (width - size) / 2f
                posY = (height - size) / 2f
            }
            entryPlaced = false
            pinned = false
            velX = 0f
            velY = 0f
            apply()
            ghost.animate().cancel()
            ghost.visibility = VISIBLE
            // Full strength at once, not a dissolve. The overlay is still drawing him on this
            // exact spot, at this exact point in his bob — the two are the same picture, so the
            // box simply starts drawing it too and the overlay fades out underneath. Cross-fading
            // instead meant the two half-opacities never added back up to one, and he blinked out
            // for a fifth of a second in the middle of coming home.
            ghost.alpha = 1f
            resume()
        }
        invalidate()
    }

    /** Set when [placeBodyAtScreen] has already chosen where he lands, so [setAway] leaves it. */
    private var entryPlaced = false

    /**
     * Holds him exactly where he is for a hand-off. He would otherwise keep drifting during the
     * moment it takes the overlay window to come up, and lift off from where he used to be.
     */
    fun holdStill() {
        velX = 0f
        velY = 0f
        pinned = true
        ghost.setMotion(0f, 0f)
    }

    /**
     * Parked for a hand-over: he stops wandering the box, but keeps breathing. Stopping the frame
     * loop outright was most of what made being sent out look like a cut — the overlay fades in
     * bobbing, over a box ghost frozen mid-bob, and for those two hundred milliseconds you can see
     * there are two of him.
     */
    private var pinned = false

    /** Undoes [holdStill] — he wanders the box again. For a hand-over that never happened. */
    fun letGo() {
        pinned = false
    }

    /** Takes the overlay's idle bob over, so he comes home mid-stride rather than mid-fade. */
    fun adoptIdleClock(value: Float) {
        ghost.syncIdleClock(value)
    }

    /** His idle bob right now, for the overlay to carry on from when he is sent out. */
    fun idleClock(): Float = ghost.idleClock()

    /** His body's top-left in screen pixels — what the overlay needs to pick him up mid-flow. */
    fun bodyScreenPos(): FloatArray {
        getLocationOnScreen(locOnScreen)
        return floatArrayOf(locOnScreen[0] + posX, locOnScreen[1] + posY)
    }

    /** Where his body's top-left would be if he stood in the middle of the box. */
    fun centreScreenPos(): FloatArray {
        getLocationOnScreen(locOnScreen)
        return floatArrayOf(
            locOnScreen[0] + (width - size) / 2f,
            locOnScreen[1] + (height - size) / 2f
        )
    }

    /** Puts him at a screen point, clamped into the box — how he arrives from the overlay. */
    fun placeBodyAtScreen(x: Float, y: Float) {
        getLocationOnScreen(locOnScreen)
        posX = (x - locOnScreen[0]).coerceIn(0f, (width - size).coerceAtLeast(0).toFloat())
        posY = (y - locOnScreen[1]).coerceIn(0f, (height - size).coerceAtLeast(0).toFloat())
        velX = 0f
        velY = 0f
        pinned = false
        entryPlaced = true
        apply()
    }

    private val locOnScreen = IntArray(2)

    /**
     * Re-reads his whole look — kind, shade and size — from what is stored. The box builds itself
     * once and is then only shown and hidden, so without this a style chosen while he was out
     * floating never reached the ghost who came home.
     */
    fun applyLook() {
        setSpecies(Prefs.species(context))
        setShade(Prefs.shade(context))
        setGhostSize((Prefs.sizeDp(context) * density).toInt())
    }

    /** Resizes him in place, keeping him inside the box and centred on where he already was. */
    fun setGhostSize(px: Int) {
        if (px <= 0 || px == size) return
        val cx = posX + size / 2f
        val cy = posY + size / 2f
        size = px
        ghost.setBodySize(size)
        ghost.layoutParams = LayoutParams(
            size + GhostView.bubbleSidePx(density, size) * 2,
            size + GhostView.headroomPx(density, size) + GhostView.haloPadPx(size)
        )
        posX = (cx - size / 2f).coerceIn(0f, (width - size).coerceAtLeast(0).toFloat())
        posY = (cy - size / 2f).coerceIn(0f, (height - size).coerceAtLeast(0).toFloat())
        apply()
    }

    fun setSpecies(species: Species) {
        if (ghost.species == species) return
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
        // Anywhere across the box, not just the two edges — it used to be a coin flip between
        // hard left and hard right, which made every feed look like the last one.
        val margin = size * 0.55f
        val span = (width - margin * 2f).coerceAtLeast(1f)
        itemX = margin + Random.nextFloat() * span
        // ...but never right on top of him, or there is no chase: nudge it to the roomier side.
        val ghostCentre = posX + size / 2f
        if (abs(itemX - ghostCentre) < size) {
            itemX = if (ghostCentre < width / 2f) {
                margin + span * (0.55f + Random.nextFloat() * 0.45f)
            } else {
                margin + span * (Random.nextFloat() * 0.45f)
            }
        }
        itemY = -size * 0.3f
        itemVelY = 0f
        itemLandY = height * (0.45f + Random.nextFloat() * 0.32f)
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
        if (away) {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) onSummon?.invoke()
            return false
        }
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
            "happy" -> species.callHappy
            "hungry" -> species.callHungry
            else -> species.callIdle
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

        if (pinned) {
            ghost.advance(dt)
            ghost.invalidate()
            return
        }

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
                    // He says something as he gets it — the reaction moved here from the moment
                    // the treat was dropped, so it lands with the catch rather than before it.
                    when (deliveryKind) {
                        DeliveryKind.TREAT -> {
                            ghost.startEating(REACTION_DURATION)
                            vocalise("happy")
                        }
                        DeliveryKind.GIFT -> {
                            ghost.startGiftJoy(REACTION_DURATION)
                            vocalise("affectionate")
                        }
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
    private val sideRoom: Float get() = GhostView.bubbleSidePx(density, size).toFloat()

    private companion object {
        /** Matches the overlay's own fade, so the two overlap rather than leaving a gap. */
        const val HANDOVER_MS = 170L

        const val PET_HOLD_MS = 1_000L
        const val PET_ANIMATION_MS = 2_000L
        const val FETCH_TIMEOUT_SECONDS = 6f
        const val GRAB_DURATION = 0.6f
        const val GRAVITY = 900f
        const val REACTION_DURATION = 1.6f
    }
}
