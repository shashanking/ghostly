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
 *
 * ### The box holds a roster, not a ghost
 *
 * Everything that is one pet's own — his view, where he is, where he is heading, whether he is out
 * on the overlay, asleep or pinned for a hand-over — lives in a [Resident]. Everything shared by
 * whoever is standing in the box — the frame loop, the box's bounds, the clock, and the games —
 * stays here. Today [residents] holds exactly one entry, the primary, and the box looks and
 * behaves exactly as it did when that was the only shape it could take; the list is the structure
 * a second pet slots into rather than a feature in itself.
 *
 * The public API is widened rather than replaced: a call takes an optional trailing `slot` that
 * defaults to [PetStore.PRIMARY_SLOT], the same shape the per-pet [Prefs] accessors use. An
 * existing caller that knows about one ghost keeps working and means the primary; a caller that
 * knows about the roster names the pet it is talking about. [applyLook] is the one exception,
 * and says there why.
 */
class GhostPlayground @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val driftSpeed = 18f * density

    /**
     * One pet living in the box.
     *
     * [slot] is his identity, not his place in this list — see [Pet]. The list order only decides
     * where he stands when he comes home (see [homePos]), which is why it is read through
     * `indexOf` rather than assumed to be the slot number.
     *
     * posX/posY are his BODY's top-left in box coordinates. His view is wider and taller than his
     * body — the extra is bubble headroom and side room — so [place] shifts it by that much.
     */
    private inner class Resident(val slot: Int, val view: GhostView) {
        var size = 0
        var posX = 0f
        var posY = 0f
        var velX = 0f
        var velY = 0f
        var driftAngle = Random.nextFloat() * 2f * PI.toFloat()
        var nextGlanceAt = 1.5f

        /** Set once the box knows its own size and has stood him somewhere. */
        var placed = false

        /**
         * He is out on the overlay, so his spot in the box is empty.
         *
         * A pet is in one place at a time: the box holds him until "Let him float" sends him out,
         * and "Call him home" brings him back. Drawing him in both places at once was the bug the
         * flag fixes — and with a roster it is per pet, because one can be out floating while
         * another is still in the box.
         */
        var away = false

        /** Set by [setMood] — while true, idle wandering is suspended so a sleeping pet actually
         *  reads as asleep here instead of drifting around. */
        var asleep = false

        /**
         * Parked for a hand-over: he stops wandering the box, but keeps breathing. Stopping the
         * frame loop outright was most of what made being sent out look like a cut — the overlay
         * fades in bobbing, over a box ghost frozen mid-bob, and for those two hundred
         * milliseconds you can see there are two of him. So this is a flag the loop skips over,
         * not a loop that stops: the loop is shared by everyone in the box anyway, and one pet
         * being handed over must not freeze the others.
         */
        var pinned = false

        /** Set when [placeBodyAtScreen] has already chosen where he lands, so [setAway] leaves it. */
        var entryPlaced = false

        /** His own petting cadence — the finger is on one pet, and the others are not being held. */
        var lastPetAt = 0L

        val centreX: Float get() = posX + size / 2f
        val centreY: Float get() = posY + size / 2f

        val headroom: Float get() = GhostView.headroomPx(density, size).toFloat()

        /** Blank room either side of his body inside the view — see the bubble note in [GhostView]. */
        val sideRoom: Float get() = GhostView.bubbleSidePx(density, size).toFloat()

        /** Moves his view to wherever posX/posY now say, allowing for that blank room. */
        fun place() {
            view.translationX = posX - sideRoom
            view.translationY = posY - headroom
        }

        fun clampIntoBox() {
            posX = posX.coerceIn(0f, (width - size).coerceAtLeast(0).toFloat())
            posY = posY.coerceIn(0f, (height - size).coerceAtLeast(0).toFloat())
        }

        /** Resizes him in place, keeping him inside the box and centred on where he already was. */
        fun resize(px: Int) {
            if (px <= 0 || px == size) return
            val cx = centreX
            val cy = centreY
            size = px
            view.setBodySize(size)
            view.layoutParams = viewParams(size)
            posX = cx - size / 2f
            posY = cy - size / 2f
            clampIntoBox()
            place()
        }
    }

    /**
     * Never empty: the primary cannot be dismissed (see [PetStore]), so there is always someone
     * here for the singular API to mean.
     */
    private val residents = mutableListOf<Resident>()

    private fun resident(slot: Int): Resident? = residents.firstOrNull { it.slot == slot }

    private val primary: Resident
        get() = resident(PetStore.PRIMARY_SLOT) ?: residents.first()

    private var lastFrameNanos = 0L
    private var clock = 0f

    /**
     * Touched while he is out floating. The box is his one place to be handled, so rather than
     * ignoring the touch it asks whoever owns the box to bring him in for a moment.
     */
    var onSummon: (() -> Unit)? = null

    /**
     * The same request, for a caller that can tell the pets apart: the slot whose empty outline
     * was reached into. Set this and [onSummon] is left alone.
     */
    var onSummonPet: ((Int) -> Unit)? = null

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

    /** Who the hold is on. Null means nothing is armed — it replaces a bare "armed" flag because
     *  with a roster it matters *which* pet the finger came down on. */
    private var pettingTarget: Resident? = null
    private var petTriggered = false
    private var downOn: Resident? = null
    private val petRunnable = Runnable {
        val target = pettingTarget ?: return@Runnable
        petTriggered = true
        pet(target)
    }

    // Fetch: a toy to chase, grab, and carry back home — started from the Play button.
    //
    // There is one toy, and the box owns it rather than any pet: a second toy in flight would be a
    // second game, and the Play button is one press. What the roster changes is that the game has
    // to name its player — [fetchSlot] — because "he chases it" stops being an answer the moment
    // there is more than one of him.
    private var fetchState = FetchState.NONE
    private var fetchSlot = PetStore.PRIMARY_SLOT
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
    // Feed/Treat/Gift. One item, one named recipient, for the same reason as the toy: feeding is
    // aimed at a pet, and his stats are already the ones that were changed by the time this runs.
    private var deliveryState = DeliveryState.NONE
    private var deliverySlot = PetStore.PRIMARY_SLOT
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
        // Exactly one resident, the primary. Phase 2 builds this from `PetStore.all(context)` and
        // re-syncs it when a lease is taken or runs out. Until then every spread, hit-test and
        // arrival below collapses onto the single-ghost answer the box has always given.
        addResident(PetStore.primary(context), Prefs.colorHue(context))
    }

    /**
     * Builds a pet's view and stands him in the box.
     *
     * [tint] is passed in rather than read here because the hue is not per-pet: it is a legacy
     * global that [Prefs.colorHue] now always reports as null, and reading it has a side effect.
     */
    private fun addResident(pet: Pet, tint: Float?): Resident {
        val view = GhostView(context)
        val r = Resident(pet.slot, view)
        r.size = (pet.sizeDp * density).toInt().coerceAtLeast(1)
        view.species = pet.species
        view.setShade(pet.shade)
        view.setTint(tint)
        // Wider than he is on purpose: the fixed-size bubble spills into the side room.
        view.setBodySize(r.size)
        residents += r
        addView(view, viewParams(r.size))
        // A pet who joins a box that has already been laid out gets his spot now; the first one
        // is added before there is a box to stand in, and waits for onSizeChanged.
        if (width > 0 && height > 0) placeHome(r)
        return r
    }

    private fun viewParams(px: Int) = LayoutParams(
        px + GhostView.bubbleSidePx(density, px) * 2,
        px + GhostView.headroomPx(density, px) + GhostView.haloPadPx(px)
    )

    /** Called by the app whenever the overlay starts or stops. */
    fun setAway(value: Boolean, slot: Int = PetStore.PRIMARY_SLOT) {
        val r = resident(slot) ?: return
        if (r.away == value) return
        r.away = value
        if (value) {
            // He has just been handed to the overlay, which is already drawing him in this exact
            // spot — so this fades out under him rather than blinking him away.
            r.view.animate().alpha(0f).setDuration(HANDOVER_MS).withEndAction {
                if (r.away) r.view.visibility = INVISIBLE
            }.start()
        } else {
            // He comes home to his own spot in the box unless he was placed somewhere first.
            if (!r.entryPlaced) {
                val home = homePos(r)
                r.posX = home[0]
                r.posY = home[1]
            }
            r.entryPlaced = false
            r.pinned = false
            r.velX = 0f
            r.velY = 0f
            r.place()
            r.view.animate().cancel()
            r.view.visibility = VISIBLE
            // Full strength at once, not a dissolve. The overlay is still drawing him on this
            // exact spot, at this exact point in his bob — the two are the same picture, so the
            // box simply starts drawing it too and the overlay fades out underneath. Cross-fading
            // instead meant the two half-opacities never added back up to one, and he blinked out
            // for a fifth of a second in the middle of coming home.
            r.view.alpha = 1f
            resume()
        }
        invalidate()
    }

    /**
     * Holds him exactly where he is for a hand-off. He would otherwise keep drifting during the
     * moment it takes the overlay window to come up, and lift off from where he used to be.
     */
    fun holdStill(slot: Int = PetStore.PRIMARY_SLOT) {
        val r = resident(slot) ?: return
        r.velX = 0f
        r.velY = 0f
        r.pinned = true
        r.view.setMotion(0f, 0f)
    }

    /** Undoes [holdStill] — he wanders the box again. For a hand-over that never happened. */
    fun letGo(slot: Int = PetStore.PRIMARY_SLOT) {
        resident(slot)?.pinned = false
    }

    /** Takes the overlay's idle bob over, so he comes home mid-stride rather than mid-fade. */
    fun adoptIdleClock(value: Float, slot: Int = PetStore.PRIMARY_SLOT) {
        resident(slot)?.view?.syncIdleClock(value)
    }

    /** His idle bob right now, for the overlay to carry on from when he is sent out. Each pet keeps
     *  his own — two pets bobbing off the same clock would breathe in unison, and the hand-over
     *  has to match the one window that is actually being handed over. */
    fun idleClock(slot: Int = PetStore.PRIMARY_SLOT): Float =
        (resident(slot) ?: primary).view.idleClock()

    /** His body's top-left in screen pixels — what the overlay needs to pick him up mid-flow. */
    fun bodyScreenPos(slot: Int = PetStore.PRIMARY_SLOT): FloatArray {
        val r = resident(slot) ?: primary
        getLocationOnScreen(locOnScreen)
        return floatArrayOf(locOnScreen[0] + r.posX, locOnScreen[1] + r.posY)
    }

    /**
     * Where his body's top-left would be when he comes home — in screen pixels, for the overlay to
     * fly him to.
     *
     * One pet lands dead centre, as he always has. More than one cannot: they would arrive on top
     * of each other and the landing would read as one ghost, so [homePos] spreads them around a
     * ring centred on the box instead — see the note there for how wide.
     */
    fun centreScreenPos(slot: Int = PetStore.PRIMARY_SLOT): FloatArray {
        val r = resident(slot) ?: primary
        val home = homePos(r)
        getLocationOnScreen(locOnScreen)
        return floatArrayOf(locOnScreen[0] + home[0], locOnScreen[1] + home[1])
    }

    /**
     * Where in the box this pet stands when he arrives.
     *
     * One resident gets the exact centre — the arrival the box has always done. Beyond that they
     * are spread evenly around a ring about the centre, each on his own bearing, with the radius
     * set so that neighbouring bodies clear each other by a body's width plus a little air
     * (chord = 2·R·sin(π/n)), then capped so nobody lands outside the box. Ring rather than row
     * because the box is nearly square and a row of five would have to shrink them to fit; bearing
     * by list position rather than by slot so a freed slot does not leave a gap in the circle.
     */
    private fun homePos(r: Resident): FloatArray {
        val cx = width / 2f
        val cy = height / 2f
        val n = residents.size
        if (n <= 1) return floatArrayOf(cx - r.size / 2f, cy - r.size / 2f)
        val clearance = r.size * 1.15f
        val fits = ((minOf(width, height) - r.size) / 2f).coerceAtLeast(0f)
        val radius = (clearance / (2f * sin(PI.toFloat() / n))).coerceAtMost(fits)
        val bearing = -PI.toFloat() / 2f + residents.indexOf(r) * 2f * PI.toFloat() / n
        return floatArrayOf(
            (cx + cos(bearing) * radius - r.size / 2f)
                .coerceIn(0f, (width - r.size).coerceAtLeast(0).toFloat()),
            (cy + sin(bearing) * radius - r.size / 2f)
                .coerceIn(0f, (height - r.size).coerceAtLeast(0).toFloat())
        )
    }

    private fun placeHome(r: Resident) {
        val home = homePos(r)
        r.posX = home[0]
        r.posY = home[1]
        r.placed = true
        r.place()
    }

    /** Puts him at a screen point, clamped into the box — how he arrives from the overlay. */
    fun placeBodyAtScreen(x: Float, y: Float, slot: Int = PetStore.PRIMARY_SLOT) {
        val r = resident(slot) ?: return
        getLocationOnScreen(locOnScreen)
        r.posX = x - locOnScreen[0]
        r.posY = y - locOnScreen[1]
        r.clampIntoBox()
        r.velX = 0f
        r.velY = 0f
        r.pinned = false
        r.entryPlaced = true
        r.place()
    }

    private val locOnScreen = IntArray(2)

    /**
     * Re-reads the whole roster's look — kind, shade and size — from what is stored. The box builds
     * itself once and is then only shown and hidden, so without this a style chosen while he was
     * out floating never reached the ghost who came home.
     *
     * Deliberately not a defaulted `slot` like the rest: the no-argument call means "everyone",
     * because that is what its caller is asking for — it fires after any look change at all, and
     * nothing in the app knows which pet was edited. With one resident the two are the same call.
     */
    fun applyLook() {
        residents.forEach { applyLook(it.slot) }
    }

    fun applyLook(slot: Int) {
        if (resident(slot) == null) return
        setSpecies(Prefs.species(context, slot), slot)
        setShade(Prefs.shade(context, slot), slot)
        setGhostSize((Prefs.sizeDp(context, slot) * density).toInt(), slot)
    }

    /** Resizes him in place, keeping him inside the box and centred on where he already was. */
    fun setGhostSize(px: Int, slot: Int = PetStore.PRIMARY_SLOT) {
        resident(slot)?.resize(px)
    }

    fun setSpecies(species: Species, slot: Int = PetStore.PRIMARY_SLOT) {
        val r = resident(slot) ?: return
        if (r.view.species == species) return
        r.view.species = species
        r.view.invalidate()
    }

    /** Called by the settings screen when the colour swatch changes. */
    fun setShade(shade: Shade, slot: Int = PetStore.PRIMARY_SLOT) {
        resident(slot)?.view?.setShade(shade)
    }

    fun setTint(hue: Float?, slot: Int = PetStore.PRIMARY_SLOT) {
        resident(slot)?.view?.setTint(hue)
    }

    /** Called from the Home tab's periodic refresh so this preview actually reflects his current
     *  mood and sleep state, instead of always drawing an awake, drifting pet. */
    fun setMood(mood: Mood, sleeping: Boolean, slot: Int = PetStore.PRIMARY_SLOT) {
        val r = resident(slot) ?: return
        r.asleep = sleeping
        r.view.setMood(mood, sleeping)
    }

    /**
     * Drops a toy in for one named pet to chase, grab, and carry back home — called when Play
     * succeeds. Purely a visual flourish; the actual stat effects are already applied by the time
     * this runs, on the same slot.
     *
     * Phase 2 seam — the onlookers. One toy lands in a box that may hold five, and the four who
     * were not played with should not carry on drifting as though nothing happened: the cheap,
     * right-looking version is that everyone glances at the toy as it appears (the box already has
     * a per-pet `lookAt`), and a bolder one has an idle bystander wander a little way towards it
     * and back. Neither is implemented here, because a reaction that only ever fires for an
     * audience of nobody cannot be judged, and this phase must not change what one pet does. The
     * hook belongs right here, where the toy's position is known and before the chase starts.
     */
    fun startFetch(slot: Int = PetStore.PRIMARY_SLOT) {
        val r = resident(slot) ?: return
        if (!r.placed || width <= 0 || height <= 0) return
        val margin = r.size * 0.6f
        toyX = margin + Random.nextFloat() * (width - margin * 2f).coerceAtLeast(1f)
        toyY = margin + Random.nextFloat() * (height - margin * 2f).coerceAtLeast(1f)
        fetchHomeX = r.posX
        fetchHomeY = r.posY
        fetchSlot = slot
        fetchState = FetchState.CHASING
        fetchEndsAt = clock + FETCH_TIMEOUT_SECONDS
        r.view.notice()
    }

    /** Drops a treat from a corner for him to sprint after and eat — called on Feed/Treat. Purely
     *  a visual flourish; the actual stat effects are already applied by the time this runs. */
    fun startFeeding(slot: Int = PetStore.PRIMARY_SLOT) = startDelivery(DeliveryKind.TREAT, slot)

    /** Drops a gift from a corner for him to sprint after and unwrap — called on Gift. Purely a
     *  visual flourish; the actual stat effects are already applied by the time this runs. */
    fun startGift(slot: Int = PetStore.PRIMARY_SLOT) = startDelivery(DeliveryKind.GIFT, slot)

    /** The same onlooker seam as [startFetch]: the item is aimed at one pet, and what the rest of
     *  the box does while food falls into it is Phase 2's to decide. */
    private fun startDelivery(kind: DeliveryKind, slot: Int) {
        val r = resident(slot) ?: return
        if (!r.placed || width <= 0 || height <= 0) return
        deliveryKind = kind
        deliverySlot = slot
        // Anywhere across the box, not just the two edges — it used to be a coin flip between
        // hard left and hard right, which made every feed look like the last one.
        val margin = r.size * 0.55f
        val span = (width - margin * 2f).coerceAtLeast(1f)
        itemX = margin + Random.nextFloat() * span
        // ...but never right on top of him, or there is no chase: nudge it to the roomier side.
        val ghostCentre = r.centreX
        if (abs(itemX - ghostCentre) < r.size) {
            itemX = if (ghostCentre < width / 2f) {
                margin + span * (0.55f + Random.nextFloat() * 0.45f)
            } else {
                margin + span * (Random.nextFloat() * 0.45f)
            }
        }
        itemY = -r.size * 0.3f
        itemVelY = 0f
        itemLandY = height * (0.45f + Random.nextFloat() * 0.32f)
        deliveryState = DeliveryState.FALLING
        r.view.notice()
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
        if (w <= 0 || h <= 0) return
        // Only whoever is still waiting for a spot: a resize must not sweep everyone who has been
        // wandering for a while back to the middle.
        residents.forEach { if (!it.placed) placeHome(it) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && petAt(event.x, event.y) == null) {
            // Nobody under the finger, and somebody is out: reaching into an empty outline is how
            // you ask for that pet back. Returning false gives the touch up, which is what the box
            // did while its one ghost was away.
            val missing = nearestAway(event.x, event.y)
            if (missing != null) {
                summon(missing)
                return false
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lookAtAll(event.x, event.y)
                downOn = petAt(event.x, event.y)
                petTriggered = false
                // Only a touch that actually lands on him counts — a miss nearby is not an
                // interaction, so it neither spooks nor arms petting.
                if (downOn != null) {
                    pettingTarget = downOn
                    petHandler.postDelayed(petRunnable, PET_HOLD_MS)
                } else {
                    pettingTarget = null
                }
                performClick()
                return true
            }
            // In here we get real coordinates, so he can properly follow your finger around.
            MotionEvent.ACTION_MOVE -> {
                lookAtAll(event.x, event.y)
                residents.forEach { it.nextGlanceAt = clock + 1.5f }
                // Sliding off him cancels the hold — and so does sliding onto the pet next to him,
                // because a stroke that changes who it is on is not one stroke.
                if (pettingTarget != null && petAt(event.x, event.y) !== pettingTarget) {
                    pettingTarget = null
                    petHandler.removeCallbacks(petRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                petHandler.removeCallbacks(petRunnable)
                // Landed on him but let go before the hold fired: that's a poke, not a pet.
                downOn?.let { if (!petTriggered) fleeFrom(it, event.x, event.y) }
                pettingTarget = null
                downOn = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun summon(r: Resident) {
        val named = onSummonPet
        if (named != null) named(r.slot) else onSummon?.invoke()
    }

    /**
     * Which pet, if any, is under this finger.
     *
     * The radius is his own, not the box's: sizes are per pet, so a Wisp and a Haunt standing side
     * by side have very different targets. Among the ones actually being touched — overlapping is
     * ordinary once they wander — the nearest centre wins, which is the body the finger is most
     * obviously on.
     */
    private fun petAt(x: Float, y: Float): Resident? {
        var best: Resident? = null
        var bestDist = Float.MAX_VALUE
        for (r in residents) {
            if (r.away || !r.placed) continue
            val d = hypot(x - r.centreX, y - r.centreY)
            if (d <= r.size * 0.65f && d < bestDist) {
                best = r
                bestDist = d
            }
        }
        return best
    }

    /** The absent pet whose empty outline this touch is nearest to, if any is absent at all. */
    private fun nearestAway(x: Float, y: Float): Resident? =
        residents.filter { it.away }.minByOrNull {
            val home = homePos(it)
            hypot(x - (home[0] + it.size / 2f), y - (home[1] + it.size / 2f))
        }

    /** What he says, in his own species' voice. */
    private fun vocalise(r: Resident, kind: String) {
        val species = Prefs.species(context, r.slot)
        val text = when (kind) {
            "happy" -> species.callHappy
            "hungry" -> species.callHungry
            else -> species.callIdle
        }
        r.view.showBubble(text, 1.9f)
    }

    /** A hand strokes his head for a couple of seconds; still held after that, it happens again. */
    private fun pet(r: Resident) {
        val now = SystemClock.uptimeMillis()
        if (now - r.lastPetAt < PET_ANIMATION_MS) return
        r.lastPetAt = now
        // His own stats, not the box's: both the read and the write name him, and for the primary
        // those are the same unsuffixed keys they have always been (see [Prefs.key]).
        val s = PetStats.snapshot(context, slot = r.slot)
        val happiness = (s.happiness + 3f).coerceAtMost(PetStats.MAX)
        Prefs.saveStats(
            context, s.hunger, s.energy, happiness, s.sleeping, System.currentTimeMillis(), r.slot
        )
        r.view.startPetting()
        r.view.showExpression(Expression.DELIGHTED, 2.4f)
        vocalise(r, "happy")
        // Still held: keep ticking affection for as long as the finger stays put.
        petHandler.postDelayed(petRunnable, PET_ANIMATION_MS)
    }

    /** Everyone in the box watches the finger — it is the one thing happening, and a pet who
     *  ignored it would read as scenery. */
    private fun lookAtAll(x: Float, y: Float) {
        residents.forEach { if (!it.away) lookAt(it, x, y) }
    }

    private fun lookAt(r: Resident, x: Float, y: Float) {
        val dx = x - r.centreX
        val dy = y - r.centreY
        val len = hypot(dx, dy)
        if (len < 1f) return
        val reach = (len / (width * 0.3f)).coerceAtMost(1f)
        r.view.lookAt(dx / len * reach, dy / len * reach)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fetchState != FetchState.NONE) {
            val r = (resident(fetchSlot) ?: primary).size * 0.16f
            canvas.drawCircle(toyX, toyY, r, toyPaint)
            canvas.drawCircle(toyX, toyY, r, toyRimPaint)
        }
        if (deliveryState == DeliveryState.FALLING || deliveryState == DeliveryState.CHASING) {
            val r = ((resident(deliverySlot) ?: primary).size * 0.18f).toInt()
            val drawable = if (deliveryKind == DeliveryKind.TREAT) treatDrawable else giftDrawable
            drawable.setBounds((itemX - r).toInt(), (itemY - r).toInt(), (itemX + r).toInt(), (itemY + r).toInt())
            drawable.draw(canvas)
        }
        residents.forEach { if (it.away) drawEmpty(canvas, it) }
        // The line along the bottom is the box's, not anyone's: it says what the box is for. It
        // only turns into the away line once there is nobody left in here to hold.
        val text = if (residents.all { it.away }) "drifting over your apps"
        else "hold him to pet him \u00b7 this box is his"
        canvas.drawText(text, width / 2f, height - 18f * density, hintPaint)
    }

    /**
     * The shape of him, in dashes, so his spot reads as vacated rather than broken. Drawn on the
     * spot he would come home to, so several outlines sit apart rather than stacking.
     *
     * The caption stays a sentence about the box while there is one pet. With a roster it wants to
     * be his name instead — there is no sense in "out there somewhere" written three times — but
     * that is a wording change, and this phase is not the place for one.
     */
    private fun drawEmpty(canvas: Canvas, r: Resident) {
        val home = homePos(r)
        val bodyCx = home[0] + r.size / 2f
        val bodyCy = home[1] + r.size / 2f
        val s = r.size * 1.25f
        val left = bodyCx - s / 2f
        val top = bodyCy - s / 2f - s * 0.06f
        emptyPaint.strokeWidth = s * 0.028f
        emptyPath.reset()
        val pad = s * 0.10f
        val gw = s - pad * 2f
        val rad = gw / 2f
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
        emptyPath.lineTo(left + pad, top + pad + rad)
        emptyPath.close()
        canvas.drawPath(emptyPath, emptyPaint)

        awayTextPaint.typeface = Type.serifItalic(context)
        awayTextPaint.textSize = 19f * density
        canvas.drawText("out there somewhere", bodyCx, top + s + 34f * density, awayTextPaint)
    }

    private fun fleeFrom(r: Resident, fromX: Float, fromY: Float) {
        var dx = r.centreX - fromX
        var dy = r.centreY - fromY
        val len = hypot(dx, dy)
        val base = if (len < 1f) Random.nextFloat() * 2f * PI.toFloat() else {
            dx /= len; dy /= len; atan2(dy, dx)
        }
        val angle = base + (Random.nextFloat() - 0.5f) * 1.9f
        val speed = (320f + Random.nextFloat() * 260f) * density
        r.velX = cos(angle) * speed
        r.velY = sin(angle) * speed
        r.driftAngle = angle
        r.view.spook()
    }

    /**
     * One loop, everybody in it. Each resident is driven by exactly one thing per frame, in the
     * order a hand-over beats a game beats a nap beats a wander.
     *
     * A game is ticked from inside its own player's turn, which means an item in flight waits if
     * he is handed to the overlay mid-chase — the same thing the single-ghost loop did, since it
     * returned early while he was away.
     */
    private fun tick(dt: Float) {
        if (width <= 0 || height <= 0) return
        for (r in residents) {
            if (!r.placed || r.away) continue
            when {
                r.pinned -> {
                    r.view.advance(dt)
                    r.view.invalidate()
                }
                deliveryState != DeliveryState.NONE && r.slot == deliverySlot -> tickDelivery(dt, r)
                fetchState != FetchState.NONE && r.slot == fetchSlot -> tickFetch(dt, r)
                r.asleep -> tickAsleep(dt, r)
                else -> tickDrift(dt, r)
            }
        }
    }

    /** Settle to a stop and stay put rather than drifting off mid-nap — same rule as the overlay. */
    private fun tickAsleep(dt: Float, r: Resident) {
        val settle = 1f - exp(-2.5f * dt)
        r.velX -= r.velX * settle
        r.velY -= r.velY * settle
        r.posX += r.velX * dt
        r.posY += r.velY * dt
        r.view.setMotion(r.velX, r.velY)
        r.view.advance(dt)
        r.view.invalidate()
        r.place()
    }

    private fun tickDrift(dt: Float, r: Resident) {
        // Same rule as the overlay: always drifting, never parked. The wobble is read off the
        // box's shared clock, so a roster of five would sway in step — Phase 2 wants a per-pet
        // phase offset here. With one resident there is nothing to be in step with.
        r.driftAngle += (sin(clock * 0.31f) + sin(clock * 0.17f + 1.3f)) * 0.4f * dt
        val settle = 1f - exp(-0.85f * dt)
        r.velX += (cos(r.driftAngle) * driftSpeed - r.velX) * settle
        r.velY += (sin(r.driftAngle) * driftSpeed - r.velY) * settle

        r.posX += r.velX * dt
        r.posY += r.velY * dt

        // Looks around the card when nothing else is going on.
        if (clock > r.nextGlanceAt) {
            lookAt(r, width * (0.1f + Random.nextFloat() * 0.8f), height * (0.1f + Random.nextFloat() * 0.8f))
            r.nextGlanceAt = clock + 1.4f + Random.nextFloat() * 2.4f
        }

        val maxX = (width - r.size).toFloat()
        val maxY = (height - r.size).toFloat()
        if (r.posX < 0f) { r.posX = 0f; bounceX(r) }
        else if (r.posX > maxX) { r.posX = maxX; bounceX(r) }
        if (r.posY < 0f) { r.posY = 0f; bounceY(r) }
        else if (r.posY > maxY) { r.posY = maxY; bounceY(r) }

        r.view.setMotion(r.velX, r.velY)
        r.view.advance(dt)
        r.view.invalidate()
        r.place()
    }

    /** He beelines for the toy, grabs it in his mouth, then carries it back home before letting
     *  go — a proper fetch, not just a chase. */
    private fun tickFetch(dt: Float, r: Resident) {
        when (fetchState) {
            FetchState.CHASING -> {
                val dx = toyX - r.centreX
                val dy = toyY - r.centreY
                val dist = hypot(dx, dy)
                if (dist < r.size * 0.55f) {
                    fetchState = FetchState.GRABBING
                    grabEndsAt = clock + GRAB_DURATION
                    r.velX = 0f
                    r.velY = 0f
                    r.view.setMotion(0f, 0f)
                    r.view.startGrab()
                    invalidate()
                } else if (clock > fetchEndsAt) {
                    // Gave up — the toy just vanishes rather than making him trail an unclaimed one.
                    fetchState = FetchState.NONE
                    invalidate()
                } else {
                    val eagerSpeed = driftSpeed * 6f
                    val settle = 1f - exp(-2.2f * dt)
                    r.velX += (dx / dist * eagerSpeed - r.velX) * settle
                    r.velY += (dy / dist * eagerSpeed - r.velY) * settle
                    r.posX += r.velX * dt
                    r.posY += r.velY * dt
                    r.clampIntoBox()
                    r.view.setMotion(r.velX, r.velY)
                    r.view.lookAt(dx / dist, dy / dist)
                    r.place()
                    invalidate() // the toy itself is drawn by this view, not the ghost
                }
                r.view.advance(dt)
                r.view.invalidate()
            }
            FetchState.GRABBING -> {
                // Held in his mouth while he savours the catch.
                toyX = r.centreX
                toyY = r.posY + r.size * 0.62f
                if (clock > grabEndsAt) fetchState = FetchState.RETURNING
                r.view.advance(dt)
                r.view.invalidate()
                invalidate()
            }
            FetchState.RETURNING -> {
                val dx = fetchHomeX - r.posX
                val dy = fetchHomeY - r.posY
                val dist = hypot(dx, dy)
                toyX = r.centreX
                toyY = r.posY + r.size * 0.62f
                if (dist < r.size * 0.25f) {
                    fetchState = FetchState.NONE
                    r.view.startWiggle()
                    r.view.spawnHeart()
                    invalidate()
                } else {
                    val speed = driftSpeed * 5f
                    val settle = 1f - exp(-2.2f * dt)
                    r.velX += (dx / dist * speed - r.velX) * settle
                    r.velY += (dy / dist * speed - r.velY) * settle
                    r.posX += r.velX * dt
                    r.posY += r.velY * dt
                    r.clampIntoBox()
                    r.view.setMotion(r.velX, r.velY)
                    r.view.lookAt(dx / dist, dy / dist)
                    r.place()
                    invalidate()
                }
                r.view.advance(dt)
                r.view.invalidate()
            }
            FetchState.NONE -> Unit
        }
    }

    /** Falls to a landing spot, then he sprints over and reacts — eating a treat, or unwrapping a
     *  gift — see [startFeeding]/[startGift]. */
    private fun tickDelivery(dt: Float, r: Resident) {
        when (deliveryState) {
            DeliveryState.FALLING -> {
                itemVelY += GRAVITY * density * dt
                itemY += itemVelY * dt
                if (itemY >= itemLandY) {
                    itemY = itemLandY
                    deliveryState = DeliveryState.CHASING
                }
                r.view.advance(dt)
                r.view.invalidate()
                invalidate() // the item itself is drawn by this view, not the ghost
            }
            DeliveryState.CHASING -> {
                val dx = itemX - r.centreX
                val dy = itemY - r.centreY
                val dist = hypot(dx, dy)
                if (dist < r.size * 0.5f) {
                    deliveryState = DeliveryState.REACTING
                    reactionEndsAt = clock + REACTION_DURATION
                    r.velX = 0f
                    r.velY = 0f
                    r.view.setMotion(0f, 0f)
                    // He says something as he gets it — the reaction moved here from the moment
                    // the treat was dropped, so it lands with the catch rather than before it.
                    when (deliveryKind) {
                        DeliveryKind.TREAT -> {
                            r.view.startEating(REACTION_DURATION)
                            vocalise(r, "happy")
                        }
                        DeliveryKind.GIFT -> {
                            r.view.startGiftJoy(REACTION_DURATION)
                            vocalise(r, "affectionate")
                        }
                    }
                    invalidate() // item is gone now — clear its last drawn position
                } else {
                    val sprintSpeed = driftSpeed * 9f
                    val settle = 1f - exp(-3f * dt)
                    r.velX += (dx / dist * sprintSpeed - r.velX) * settle
                    r.velY += (dy / dist * sprintSpeed - r.velY) * settle
                    r.posX += r.velX * dt
                    r.posY += r.velY * dt
                    r.clampIntoBox()
                    r.view.setMotion(r.velX, r.velY)
                    r.view.lookAt(dx / dist, dy / dist)
                    r.place()
                }
                r.view.advance(dt)
                r.view.invalidate()
            }
            DeliveryState.REACTING -> {
                if (clock > reactionEndsAt) deliveryState = DeliveryState.NONE
                r.view.advance(dt)
                r.view.invalidate()
            }
            DeliveryState.NONE -> Unit
        }
    }

    private fun bounceX(r: Resident) {
        r.velX = -r.velX * 0.5f
        r.driftAngle = PI.toFloat() - r.driftAngle
        r.view.spookLightly()
    }

    private fun bounceY(r: Resident) {
        r.velY = -r.velY * 0.5f
        r.driftAngle = -r.driftAngle
        r.view.spookLightly()
    }

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
