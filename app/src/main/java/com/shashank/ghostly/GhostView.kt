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
import kotlin.math.cos
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

    companion object {
        /**
         * Extra height above the body, as a fraction of his width, so a speech bubble has somewhere
         * to be. Whoever sizes a GhostView should make it `width * (1 + BUBBLE_HEADROOM)` tall.
         */
        const val BUBBLE_HEADROOM = 0.46f

        /**
         * The bubble does not shrink with him. It is sized for the largest ghost and kept there, so
         * "Meow" is exactly as readable on a Wisp as on a Haunt — the text is for you, not for him.
         */
        const val BUBBLE_TEXT_DP = 9f
        const val BUBBLE_PAD_X_DP = 6f
        const val BUBBLE_PAD_Y_DP = 4f

        /** The little spike under the bubble, pointing back down at him. */
        const val BUBBLE_TAIL_DP = 7f

        /** Clear air between the tip of the tail and the top of his head. */
        const val BUBBLE_LIFT_DP = 4f

        /**
         * The widest a bubble may be. Small on purpose: it sits centred over his head and stays
         * there, so anything wider would hang off the side of the screen when he is near an edge.
         * A longer vocal becomes several of these, one after another.
         */
        const val BUBBLE_MAX_W_DP = 62f

        /** What a bubble needs vertically: its own height, its tail, and the gap above his crown. */
        private val bubbleStackDp = BUBBLE_TEXT_DP + BUBBLE_PAD_Y_DP * 2f + BUBBLE_TAIL_DP + BUBBLE_LIFT_DP

        /**
         * Room on EACH side of his body square, so the fixed-size bubble has somewhere to sit.
         * Without it the view is exactly as wide as he is and a bubble wider than a small ghost
         * gets sliced off at the view edge — which is what happened at Wisp size.
         */
        const val BUBBLE_SIDE_DP = 44f

        fun bubbleSidePx(density: Float): Int = (BUBBLE_SIDE_DP * density).toInt()

        /**
         * How far the contrast wash reaches, as a fraction of his body width. Kept under the side
         * room above, so the wash is never cut off left or right either.
         */
        const val HALO_RADIUS = 0.68f

        /** How far his hem hangs past his square's padding, as a fraction of his width. */
        private const val HEM_DROP = 0.045f

        /** Where the wash is centred on him, down from the top of his square. */
        private const val HALO_CENTRE_Y = 0.46f

        /**
         * Blank room BELOW his square. His body sits at the bottom of the view, so without this the
         * wash — which reaches past his hem — is sliced off in a straight line under his feet.
         */
        fun haloPadPx(ghostPx: Int): Int = (ghostPx * (HALO_RADIUS + HALO_CENTRE_Y - 1f) + 2f).toInt()

        /** Headroom in pixels: proportional for a big ghost, but never less than a bubble needs. */
        fun headroomPx(density: Float, ghostPx: Int): Int =
            maxOf(ghostPx * BUBBLE_HEADROOM, (bubbleStackDp + 2f) * density).toInt()
    }

    /** How solid the body is. Low enough to read as a ghost, high enough to see on a busy screen. */
    private var shade: Shade = Shade.DEFAULT

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = Shade.DEFAULT.bodyAlpha
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** A see-through body disappears on a white screen; the outline keeps his shape readable. */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#8CFFFFFF")
    }
    private val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5EDEFFF") }
    private val scleraRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#556C63C9")
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF15122B") }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D915122B") }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26FFFFFF") }

    /** Closed eyes and a frown share the mouth's ink colour, but stroked rather than filled. */
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#D915122B")
    }
    private val whiskerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#8C6C63C9")
    }
    private val zzzPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C86C63C9")
        textAlign = Paint.Align.CENTER
    }
    private val angryBrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E8FF5252")
    }

    /** The glow, as a plain gradient. `setShadowLayer` would look similar but forces the whole view
     *  through software rendering every frame, which is far too expensive to run all day. Two
     *  versions — calm blue, angry red — swapped by mood rather than rebuilt every frame. */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The dark-on-pale (or pale-on-dark) halo that keeps him visible on any wallpaper. */
    private val contrastHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val angryGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Petting: a small hand that strokes the head for a couple of seconds.
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8B894") }
    private val handShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9976F") }

    // Affection hearts, drifting up and fading — spawned while petted, or now and then when he's
    // simply very happy.
    private class Heart(val dx: Float, val born: Float)
    private val hearts = mutableListOf<Heart>()
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6B7A") }

    // Startled: a couple of sweat drops and a few short speed-lines trailing behind.
    private val sweatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D2EEFF") }
    private val speedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#8CFFFFFF")
    }

    // A little speech bubble — "Meow"/"Woof" when hungry, a happy vocalisation, and so on.
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2EFFB") }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15122B")
        textAlign = Paint.Align.CENTER
    }

    private val bodyPath = Path()
    private val earPath = Path()
    private val rect = RectF()
    private var shadeShaderFor = -1f
    private var shadeShaderHue: Float? = -999f
    private val density = resources.displayMetrics.density

    /** Which silhouette to draw. Body, motion and behaviour are otherwise identical. */
    var species: Species = Species.GHOST

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

    /** How he's currently feeling — drives droopy eyes, a frown, furrowed brows, a red glow. */
    private var mood: Mood = Mood.CONTENT

    /**
     * A momentary face that overrides the mood one — a grin after a treat, a yawn before a nap, a
     * baffled look, a swoon. It expires on its own, so callers never have to clear it.
     */
    private var expression: Expression = Expression.NONE
    private var expressionEndsAt = 0f

    /** Set by the owner: settled down for a nap — eyes shut, no idle glancing. */
    private var asleep = false

    /** A hue in degrees, or null for his original colours. See [setTint]. */
    private var tintHue: Float? = null

    // Petting: held on for a beat, a small hand strokes his head.
    private var petting = false
    private var pettingEndsAt = 0f

    // A gentle puff — big for a "goofy" moment, subtle and constant while brimming with energy.
    private var puffTarget = 0f
    private var puffAmount = 0f

    // An extra wiggle-sway on top of the normal idle sway, for a happy little shimmy.
    private var wiggleUntil = 0f
    private var rollFrom = 0f
    private var rollUntil = 0f
    private var rollTurns = 1f
    private var squashFrom = 0f
    private var squashUntil = 0f
    private var squashAmount = 1f

    // Speech bubble text and when it should clear itself.
    private var bubbleText: String? = null
    private var bubbleEndsAt = 0f

    /** What he still has left to say, one bubble at a time — see [showBubble]. */
    private val bubbleQueue = ArrayDeque<String>()
    private var bubbleSegmentSeconds = 0f

    // Eating: mouth chomps rapidly and he dips toward the food, sending up little hearts as he
    // goes — see startEating.
    private var eating = false
    private var eatingEndsAt = 0f
    private var nextEatingHeartAt = 0f

    /**
     * The width of his body square. The view is allowed to be wider than that — its owner gives it
     * [BUBBLE_SIDE_DP] on each side so a speech bubble is never clipped — and he stays centred in
     * it. Left at 0 the body simply fills the view's width, which is what the in-app previews want.
     */
    private var bodySizePx = 0


    fun setBodySize(px: Int) {
        if (px == bodySizePx) return
        bodySizePx = px
        rebuildGlowShaders()
        invalidate()
    }

    private fun bodyW(): Int = if (bodySizePx in 1 until width) bodySizePx else width

    /** Top of his square inside a view of height [h]: the wash keeps the strip below it. */
    private fun bodyTopFor(h: Float, w: Float): Float = (h - haloPadPx(w.toInt()) - w).coerceAtLeast(0f)
    // Grabbing: a single decisive snap the instant he catches something — see startGrab.
    private var grabbing = false
    private var grabEndsAt = 0f

    // Gift joy: big rounded eyes and a burst of hearts — see startGiftJoy.
    private var giftJoy = false
    private var giftJoyEndsAt = 0f
    private var nextGiftHeartAt = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Glow and outline scale with the body so neither swamps a small ghost.
        outlinePaint.strokeWidth = w * 0.035f
        rebuildGlowShaders()
        scleraRimPaint.strokeWidth = w * 0.012f
        linePaint.strokeWidth = w * 0.028f
        whiskerPaint.strokeWidth = w * 0.012f
        zzzPaint.textSize = w * 0.16f
        angryBrowPaint.strokeWidth = w * 0.032f
        bubbleTextPaint.textSize = BUBBLE_TEXT_DP * density
    }

    /** Re-hues a base ARGB colour to the current tint, keeping its own alpha/saturation/value — an
     *  unset tint returns the colour unchanged. */
    private fun rehued(base: Int): Int {
        val hue = tintHue ?: return base
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[0] = hue
        return Color.HSVToColor(Color.alpha(base), hsv)
    }

    /** The glow depends on both size and tint, so both paths funnel through here. */
    private fun rebuildGlowShaders() {
        val w = bodyW()
        val h = height
        if (w <= 0 || h <= 0) return
        // His own square's centre, not the view's: onDraw shifts the canvas across by the side room
        // before any of this is painted, so a shader built around the view centre lands off to one
        // side of him — which is what put a dark smudge beside a pale ghost.
        val cxv = w / 2f
        // Centred on his body, not on the view: the view carries bubble headroom above him.
        val glowCy = (h - haloPadPx(w) - w).coerceAtLeast(0) + w * HALO_CENTRE_Y
        // A pale ghost vanishes on a white wallpaper and a dark one vanishes on a black desk, so he
        // carries the opposite of himself as a soft halo. On a background where it is not needed it
        // simply cannot be seen.
        val haloRgb = (if (shade == Shade.INK) 0xFFFFFF else 0x000000)
        // Densest behind him and fading outward the whole way — a wash on the background, not a
        // ring. Peaking it partway out drew a bright edge that hugged his silhouette and read as a
        // border, which is not what a spread behind him is meant to look like.
        contrastHaloPaint.shader = RadialGradient(
            cxv, glowCy, w * HALO_RADIUS,
            intArrayOf((0x42 shl 24) or haloRgb, (0x24 shl 24) or haloRgb, (0x00 shl 24) or haloRgb),
            floatArrayOf(0f, 0.44f, 1f),
            Shader.TileMode.CLAMP
        )
        val glowRgb = rehued(Color.parseColor("#67E8FF")) and 0x00FFFFFF
        glowPaint.shader = RadialGradient(
            cxv, glowCy, w * 0.52f,
            intArrayOf(
                (0x55 shl 24) or glowRgb,
                (0x28 shl 24) or glowRgb,
                (0x00 shl 24) or glowRgb
            ),
            floatArrayOf(0.42f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )
        // Anger's glow stays red no matter the chosen tint — it's a mood signal, not a body colour.
        angryGlowPaint.shader = RadialGradient(
            w / 2f, glowCy, w * 0.52f,
            intArrayOf(
                Color.parseColor("#66FF5252"),
                Color.parseColor("#33FF5252"),
                Color.parseColor("#00FF5252")
            ),
            floatArrayOf(0.42f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** Which of the four looks he wears. */
    fun setShade(shade: Shade) {
        if (this.shade == shade) return
        this.shade = shade
        applyTint()
        rebuildGlowShaders()
    }

    /** Recolours body, outline and glow to [tintHue] and [shade], keeping each paint's weight. */
    private fun applyTint() {
        bodyPaint.color = if (tintHue == null) shade.bodyColor else Color.HSVToColor(floatArrayOf(tintHue!!, 0.14f, 1f))
        bodyPaint.alpha = shade.bodyAlpha
        pupilPaint.color = shade.inkColor
        scleraPaint.color = shade.scleraColor
        mouthPaint.color = shade.inkColor
        linePaint.color = shade.inkColor
        outlinePaint.color = if (tintHue == null) shade.outlineColor else rehued(Color.parseColor("#8C6C63C9"))
        scleraRimPaint.color = rehued(Color.parseColor("#556C63C9"))
        whiskerPaint.color = rehued(Color.parseColor("#8C6C63C9"))
        zzzPaint.color = rehued(Color.parseColor("#C86C63C9"))
        rebuildGlowShaders()
        invalidate()
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

    /** A face that plays for a beat and then gives way to his mood again. */
    fun showExpression(expression: Expression, durationSeconds: Float = 2.2f) {
        this.expression = expression
        expressionEndsAt = phase + durationSeconds
    }

    /** How he's doing right now, and whether he's napping. */
    fun setMood(mood: Mood, asleep: Boolean) {
        this.mood = mood
        this.asleep = asleep
    }

    /** Recolours body, outline and glow to a new hue, keeping each paint's original weight
     *  (alpha/saturation/brightness) — null restores his original colours exactly. */
    fun setTint(hue: Float?) {
        tintHue = hue
        applyTint()
    }

    /** A hand settles in and strokes his head for a couple of seconds. */
    fun startPetting() {
        petting = true
        pettingEndsAt = phase + 2f
        spawnHeart()
        spawnHeart()
    }

    /** A small heart drifts up from him and fades — used while petted, and now and then when
     *  he's simply content. */
    fun spawnHeart() {
        if (hearts.size >= 4) return
        hearts += Heart(dx = (Math.random().toFloat() - 0.5f) * 0.5f, born = phase)
    }

    /** A little speech bubble above his head for a beat — "Meow", "Woof", a happy "~", and so on. */
    /**
     * Says [text] above his head. A long vocal is broken into bubbles that come one after another
     * rather than being shown as one wide one: the bubble is a fixed size for legibility, so a wide
     * one is a wide one on every ghost, and near the side of the screen there is simply nowhere for
     * it to go — it was being cut off at the edge.
     */
    fun showBubble(text: String, durationSeconds: Float = 1.8f) {
        val parts = splitIntoBubbles(text)
        bubbleQueue.clear()
        if (parts.isEmpty()) {
            bubbleText = null
            return
        }
        bubbleSegmentSeconds = maxOf(0.85f, durationSeconds / parts.size)
        bubbleText = parts.first()
        bubbleEndsAt = phase + bubbleSegmentSeconds
        for (i in 1 until parts.size) bubbleQueue.addLast(parts[i])
    }

    /**
     * Greedily packs words into bubbles no wider than [BUBBLE_MAX_W_DP]. A single word longer than
     * that is left alone — better one over-wide bubble than a word chopped in half.
     */
    private fun splitIntoBubbles(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val maxW = BUBBLE_MAX_W_DP * density - BUBBLE_PAD_X_DP * density * 2f
        if (bubbleTextPaint.measureText(trimmed) <= maxW) return listOf(trimmed)
        val out = mutableListOf<String>()
        val line = StringBuilder()
        for (word in trimmed.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (bubbleTextPaint.measureText(candidate) <= maxW || line.isEmpty()) {
                line.setLength(0)
                line.append(candidate)
            } else {
                out += line.toString()
                line.setLength(0)
                line.append(word)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    /** 0 = normal silhouette. Around 0.08 reads as a subtle confident puff; around 0.3 is the full
     *  cheeks-out "goofy" pose. Eases toward the target rather than snapping. */
    fun setPuffTarget(target: Float) {
        puffTarget = target
    }

    /** A brief extra shimmy on top of the usual idle sway — a happy little wiggle. */
    /**
     * A tumble: [turns] full rotations over [seconds], eased in and out so he tips over rather
     * than spinning like a coin. Rides on top of his usual sway.
     */
    fun startRoll(seconds: Float, turns: Float = 1f) {
        rollFrom = phase
        rollUntil = phase + seconds.coerceAtLeast(0.3f)
        rollTurns = turns
    }

    /** A quick squash and rebound — his weight arriving somewhere. */
    fun squash(amount: Float = 1f) {
        squashUntil = phase + 0.34f
        squashFrom = phase
        squashAmount = amount.coerceIn(0.2f, 1.4f)
    }

    fun startWiggle() {
        wiggleUntil = phase + 1.2f
    }

    /** Chomps happily in place for [durationSeconds], dipping toward the food and sending up
     *  little hearts as he eats — the owner is expected to hold him still for this long. */
    fun startEating(durationSeconds: Float = 1.6f) {
        eating = true
        eatingEndsAt = phase + durationSeconds
        nextEatingHeartAt = phase
    }

    /** True while an eating animation is still playing. */
    fun isEating(): Boolean = eating

    /** A single decisive mouth-snap the instant he catches something, with a happy wiggle and a
     *  heart — distinct from eating's slower, repeating chomp. */
    fun startGrab(durationSeconds: Float = 0.4f) {
        grabbing = true
        grabEndsAt = phase + durationSeconds
        startWiggle()
        spawnHeart()
    }

    /** Big rounded eyes and a burst of hearts for [durationSeconds] — played when he's just been
     *  given a gift. */
    fun startGiftJoy(durationSeconds: Float = 2f) {
        giftJoy = true
        giftJoyEndsAt = phase + durationSeconds
        nextGiftHeartAt = phase
        spawnHeart()
        spawnHeart()
        spawnHeart()
        startWiggle()
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

        if (petting && phase > pettingEndsAt) petting = false
        if (bubbleText != null && phase > bubbleEndsAt) {
            // Straight on to the next thing he has to say, if there is one.
            bubbleText = bubbleQueue.removeFirstOrNull()
            if (bubbleText != null) bubbleEndsAt = phase + bubbleSegmentSeconds
        }
        if (expression != Expression.NONE && phase > expressionEndsAt) expression = Expression.NONE
        if (eating) {
            if (phase > eatingEndsAt) {
                eating = false
            } else if (phase > nextEatingHeartAt) {
                nextEatingHeartAt = phase + 0.45f
                spawnHeart()
            }
        }
        if (grabbing && phase > grabEndsAt) grabbing = false
        if (giftJoy) {
            if (phase > giftJoyEndsAt) {
                giftJoy = false
            } else if (phase > nextGiftHeartAt) {
                nextGiftHeartAt = phase + 0.35f
                spawnHeart()
            }
        }
        hearts.removeAll { phase - it.born > 1.3f }
        // Eases toward the target rather than snapping, so a puff grows/settles instead of popping.
        puffAmount += (puffTarget - puffAmount) * (1f - kotlin.math.exp(-4f * dt))
    }

    override fun onDraw(canvas: Canvas) {
        // He is drawn in his own square; the view around it may be wider, and that side room is
        // what the bubble spills into.
        val vw = width.toFloat()
        val w = bodyW().toFloat()
        val h = height.toFloat()
        val sideInset = (vw - w) / 2f
        if (w <= 0f || h <= 0f) return

        val speed = hypot(velX, velY)
        val fast = min(1f, speed / 420f)

        // Always-on idle motion: a bob, a slow sway, and a breath.
        // While eating, a quick little dip toward the food rides on top of the usual bob, timed
        // with the chomp below.
        val eatingDip = if (eating && !asleep) (0.5f - 0.5f * cos(phase * 16f)) * h * 0.05f else 0f
        val bob = sin(phase * 2.4f) * h * 0.035f + eatingDip
        // A happy little shimmy rides on top of the normal sway while it's active.
        val wiggle = if (phase < wiggleUntil) sin(phase * 16f) * 7f else 0f
        val sway = sin(phase * 1.35f) * 3.2f * (1f - fast) + wiggle
        val breath = 1f + sin(phase * 1.9f) * 0.022f
        // Irritated: a fast, tiny jitter — too quick to read as movement, just as unease.
        // A tumble, eased at both ends so he tips over rather than spinning like a coin.
        val roll = if (phase < rollUntil) {
            val t = ((phase - rollFrom) / (rollUntil - rollFrom)).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            eased * 360f * rollTurns
        } else {
            0f
        }
        // Landing weight: squat, then rebound past neutral before settling.
        val squash = if (phase < squashUntil) {
            val t = ((phase - squashFrom) / (squashUntil - squashFrom)).coerceIn(0f, 1f)
            sin(t * kotlin.math.PI.toFloat()) * (1f - t) * 0.26f * squashAmount
        } else {
            0f
        }
        val moodyShake = if (!asleep && mood == Mood.ANGRY) (sin(phase * 55f) + sin(phase * 71f)) * w * 0.004f else 0f
        // A confident/goofy puff of the whole silhouette.
        val puffScale = 1f + puffAmount * 0.22f

        val lean = (velX / 900f).coerceIn(-0.32f, 0.32f)
        val stretch = min(0.14f, speed / 4200f)

        canvas.save()
        canvas.translate(moodyShake + sideInset, bob)
        canvas.rotate(sway + lean * 26f + roll, w / 2f, bodyTopFor(h, w) + w * 0.5f)
        canvas.scale(
            (1f - stretch + squash) * breath * puffScale,
            (1f + stretch - squash) * breath * puffScale,
            w / 2f,
            bodyTopFor(h, w) + w
        )

        // He is drawn in the bottom square of the view; any extra height above it is headroom for
        // the speech bubble, so a bubble never has to be squashed onto his crown.
        // His square sits above the halo's room, not flush with the bottom of the view.
        val bodyTop = bodyTopFor(h, w)
        val pad = w * 0.10f
        val left = pad
        val right = w - pad
        val top = bodyTop + pad
        // Bottom of HIS square, not of the view: the room below him belongs to the wash, and
        // measuring down to the view's edge simply stretched his hem into it. He is given a little
        // of his own padding back below, so the hem hangs rather than stopping short.
        val bottom = bodyTop + w - pad + w * HEM_DROP
        val gw = right - left
        val r = gw / 2f
        val cx = left + r
        val detailed = gw > 26f * density

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

        val angry = !asleep && mood == Mood.ANGRY
        canvas.drawCircle(w / 2f, bodyTop + w * HALO_CENTRE_Y, w * HALO_RADIUS, contrastHaloPaint)
        canvas.drawCircle(w / 2f, bodyTop + w * HALO_CENTRE_Y, w * 0.52f, if (angry) angryGlowPaint else glowPaint)
        // Ears are drawn before the body: whatever falls inside the dome gets painted over, leaving
        // only the tip poking out — which is what makes them read as attached to the head.
        drawEars(canvas, cx, top, r, gw, detailed)
        canvas.drawPath(bodyPath, bodyPaint)
        canvas.drawPath(bodyPath, outlinePaint)

        // Soft inner shading so the body reads as volume rather than a flat blob. The shader only
        // depends on the size, so it is built once rather than sixty times a second.
        if (shadeShaderFor != gw || shadeShaderHue != tintHue) {
            shadeShaderFor = gw
            shadeShaderHue = tintHue
            shadePaint.shader = RadialGradient(
                cx - r * 0.35f, top + r * 0.55f, gw * 1.05f,
                intArrayOf(Color.parseColor("#00FFFFFF"), rehued(Color.parseColor("#3D6C63C9"))),
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
        val eyeR = gw * (0.175f + 0.03f * startle + 0.02f * alert + 0.06f * (if (giftJoy) 1f else 0f))
        val eyeY = top + r * (1.0f - 0.05f * startle)
        val eyeDx = gw * 0.235f
        val pupilR = eyeR * 0.5f
        val travel = eyeR - pupilR * 1.12f
        val sx = if (asleep) 0f else lookX * travel
        val sy = if (asleep) 0f else lookY * travel
        // Sadness droops the eyes half shut rather than fully closed, so it reads differently from
        // sleep. Anger doesn't droop them at all — furrowed brows carry that mood instead.
        val droop = if (!asleep && mood == Mood.SAD) 0.35f else 0f

        val face = expression
        if (face == Expression.FAINT) {
            // Two crossed strokes per eye, and a swirl of dizziness over the crown.
            for (side in intArrayOf(-1, 1)) {
                val ex = cx + side * eyeDx
                val a = eyeR * 0.62f
                canvas.drawLine(ex - a, eyeY - a, ex + a, eyeY + a, linePaint)
                canvas.drawLine(ex + a, eyeY - a, ex - a, eyeY + a, linePaint)
            }
            rect.set(cx - gw * 0.10f, top - gw * 0.02f, cx + gw * 0.10f, top + gw * 0.18f)
            canvas.drawArc(rect, 40f, 300f, false, linePaint)
        } else if (face == Expression.DELIGHTED) {
            // Eyes squeezed shut with pleasure — arcs bowing up, the inverse of the sleeping ones.
            for (side in intArrayOf(-1, 1)) {
                val ex = cx + side * eyeDx
                rect.set(ex - eyeR * 0.85f, eyeY - eyeR * 0.45f, ex + eyeR * 0.85f, eyeY + eyeR * 0.75f)
                canvas.drawArc(rect, 200f, 140f, false, linePaint)
            }
        } else if (face == Expression.SLEEPY) {
            // Heavy lids: the open eye, squashed, with a lid drawn across the top.
            for (side in intArrayOf(-1, 1)) {
                val ex = cx + side * eyeDx
                canvas.save()
                canvas.scale(1f, 0.45f, ex, eyeY)
                canvas.drawCircle(ex, eyeY, eyeR, scleraPaint)
                canvas.drawCircle(ex + sx, eyeY + sy - eyeR * 0.1f, pupilR, pupilPaint)
                canvas.restore()
                rect.set(ex - eyeR, eyeY - eyeR * 0.75f, ex + eyeR, eyeY + eyeR * 0.25f)
                canvas.drawArc(rect, 200f, 140f, false, linePaint)
            }
        } else if (asleep) {
            for (side in intArrayOf(-1, 1)) {
                val ex = cx + side * eyeDx
                rect.set(ex - eyeR, eyeY - eyeR * 0.55f, ex + eyeR, eyeY + eyeR * 0.55f)
                canvas.drawArc(rect, 20f, 140f, false, linePaint)
            }
        } else {
            for (side in intArrayOf(-1, 1)) {
                val ex = cx + side * eyeDx
                val squish = (blinkProgress.coerceAtLeast(0.06f)) * (1f - droop)
                canvas.save()
                canvas.scale(1f, squish.coerceAtLeast(0.08f), ex, eyeY)

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
            if (angry) drawAngryBrows(canvas, cx, eyeDx, eyeY, eyeR)
            if (face == Expression.CONFUSED) drawConfusedBrows(canvas, cx, eyeDx, eyeY, eyeR)
        }

        val mouthY = eyeY + eyeR * 1.9f
        if (face == Expression.SMILE || face == Expression.DELIGHTED) {
            // A grin is the frown's arc swept the other way, so it bows down in the middle.
            val w2 = gw * (if (face == Expression.DELIGHTED) 0.22f else 0.19f)
            val h2 = gw * 0.11f
            rect.set(cx - w2 / 2f + sx * 0.5f, mouthY - h2 * 0.7f, cx + w2 / 2f + sx * 0.5f, mouthY + h2)
            canvas.drawArc(rect, 0f, 180f, false, linePaint)
        } else if (face == Expression.SLEEPY) {
            val w2 = gw * 0.13f
            val h2 = gw * 0.19f
            rect.set(cx - w2 / 2f, mouthY - h2 / 2f + 2f, cx + w2 / 2f, mouthY + h2 / 2f + 2f)
            canvas.drawOval(rect, mouthPaint)
        } else if (face == Expression.CONFUSED || face == Expression.FAINT) {
            val w2 = gw * 0.15f
            canvas.drawLine(cx - w2 / 2f, mouthY, cx - w2 * 0.1f, mouthY - 3f, linePaint)
            canvas.drawLine(cx - w2 * 0.1f, mouthY - 3f, cx + w2 * 0.2f, mouthY + 3f, linePaint)
            canvas.drawLine(cx + w2 * 0.2f, mouthY + 3f, cx + w2 / 2f, mouthY, linePaint)
        } else if (eating && !asleep) {
            // Chewing: mouth snaps between nearly shut and wide open, in time with the head dip.
            val chomp = 0.5f - 0.5f * cos(phase * 16f)
            val mouthW = gw * 0.15f
            val mouthH = gw * (0.03f + 0.16f * chomp)
            rect.set(
                cx - mouthW / 2f + sx * 0.5f, mouthY - mouthH / 2f,
                cx + mouthW / 2f + sx * 0.5f, mouthY + mouthH / 2f
            )
            canvas.drawOval(rect, mouthPaint)
        } else if (grabbing && !asleep) {
            // A single decisive snap — wide open, held for the whole brief grab.
            val mouthW = gw * 0.16f
            val mouthH = gw * 0.14f
            rect.set(
                cx - mouthW / 2f + sx * 0.5f, mouthY - mouthH / 2f,
                cx + mouthW / 2f + sx * 0.5f, mouthY + mouthH / 2f
            )
            canvas.drawOval(rect, mouthPaint)
        } else if (!asleep && mood != Mood.CONTENT) {
            // Sad or angry: a small downward frown instead of the usual dot.
            val frownW = gw * 0.16f
            val frownH = gw * 0.09f
            rect.set(
                cx - frownW / 2f + sx * 0.5f, mouthY - frownH / 2f,
                cx + frownW / 2f + sx * 0.5f, mouthY + frownH / 2f
            )
            canvas.drawArc(rect, 180f, 180f, false, linePaint)
        } else if (!asleep) {
            // Mouth: a dot at rest, a little "o" when spooked.
            val mouthW = gw * (0.085f + 0.1f * startle)
            val mouthH = gw * (0.05f + 0.15f * startle)
            rect.set(
                cx - mouthW / 2f + sx * 0.5f, mouthY - mouthH / 2f,
                cx + mouthW / 2f + sx * 0.5f, mouthY + mouthH / 2f
            )
            canvas.drawOval(rect, mouthPaint)
        }

        if (detailed && !asleep) {
            canvas.drawCircle(cx - eyeDx - eyeR * 0.55f, mouthY, eyeR * 0.45f, blushPaint)
            canvas.drawCircle(cx + eyeDx + eyeR * 0.55f, mouthY, eyeR * 0.45f, blushPaint)
        }

        if (detailed && species == Species.CAT && !asleep) drawWhiskers(canvas, cx, mouthY, gw)

        if (asleep && detailed) {
            val t = (phase % 2.4f) / 2.4f
            zzzPaint.alpha = ((1f - t) * 210f).toInt().coerceIn(0, 210)
            canvas.drawText(
                "z", cx + eyeDx * 1.35f, eyeY - eyeR * 1.5f - t * gw * 0.4f, zzzPaint
            )
        }

        if (!asleep && startle > 0.35f) drawSpookEffects(canvas, cx, top, r, gw)
        if (petting) drawPettingHand(canvas, cx, top, r, gw)
        if (hearts.isNotEmpty()) drawHearts(canvas, w, cx, top, gw)

        canvas.restore()

        // Outside every transform, on purpose. Drawn inside them it tumbled with him through a
        // rollover and swung out past the edge of the view, where it was clipped. His words stay
        // upright and where they were put; only he turns over underneath them. It still rides the
        // idle bob, so it stays attached to his head rather than hanging in the air.
        if (bubbleText != null) drawBubble(canvas, vw / 2f, top + bob)
    }

    /** One brow arched high, the other flat and low — the whole reading of confusion sits here. */
    private fun drawConfusedBrows(canvas: Canvas, cx: Float, eyeDx: Float, eyeY: Float, eyeR: Float) {
        val hi = eyeY - eyeR * 1.5f
        val lo = eyeY - eyeR * 1.15f
        val half = eyeR * 0.75f
        rect.set(cx - eyeDx - half, hi - eyeR * 0.35f, cx - eyeDx + half, hi + eyeR * 0.35f)
        canvas.drawArc(rect, 200f, 140f, false, angryBrowPaint)
        canvas.drawLine(cx + eyeDx - half, lo, cx + eyeDx + half, lo + 2f, angryBrowPaint)
    }

    /** A furrowed "\  /" brow over both eyes — each line's inner (nose-side) end sits lower than
     *  its outer end, so together they knit down toward the centre. */
    private fun drawAngryBrows(canvas: Canvas, cx: Float, eyeDx: Float, eyeY: Float, eyeR: Float) {
        val browY = eyeY - eyeR * 1.25f
        val browHalf = eyeR * 0.55f
        val drop = eyeR * 0.32f
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * eyeDx
            val outerX = ex + side * browHalf
            val innerX = ex - side * browHalf
            canvas.drawLine(outerX, browY - drop, innerX, browY + drop, angryBrowPaint)
        }
    }

    private fun drawWhiskers(canvas: Canvas, cx: Float, mouthY: Float, gw: Float) {
        val len = gw * 0.16f
        for (side in intArrayOf(-1, 1)) {
            val startX = cx + side * gw * 0.14f
            for (row in -1..1) {
                val y = mouthY + row * gw * 0.045f
                canvas.drawLine(startX, y, startX + side * len, y + row * gw * 0.012f, whiskerPaint)
            }
        }
    }

    /** A small hand settling in above the head, swaying side to side as it strokes. */
    private fun drawPettingHand(canvas: Canvas, cx: Float, top: Float, r: Float, gw: Float) {
        val sway = sin(phase * 6f) * gw * 0.11f
        val handCx = cx + sway
        val handCy = top + r * 0.16f
        val handW = gw * 0.36f
        val handH = gw * 0.20f
        for (i in -1..1) {
            val fx = handCx + i * handW * 0.3f
            rect.set(fx - handW * 0.08f, handCy - handH * 0.9f, fx + handW * 0.08f, handCy - handH * 0.15f)
            canvas.drawRoundRect(rect, handW * 0.08f, handW * 0.08f, handShadePaint)
        }
        rect.set(handCx - handW / 2f, handCy - handH / 2f, handCx + handW / 2f, handCy + handH / 2f)
        canvas.drawRoundRect(rect, handH * 0.5f, handH * 0.5f, handPaint)
    }

    /** Small hearts drifting up from him and fading — see [spawnHeart]. His view is cropped tight
     *  around his silhouette, so the rise is clamped to stay inside it rather than sail off the
     *  top edge into nothing. */
    private fun drawHearts(canvas: Canvas, viewW: Float, cx: Float, top: Float, gw: Float) {
        for (heart in hearts) {
            val age = phase - heart.born
            val t = (age / 1.3f).coerceIn(0f, 1f)
            val s = gw * 0.09f * (1f - t * 0.3f)
            val hx = (cx + heart.dx * gw + sin(age * 3f) * gw * 0.04f).coerceIn(s * 1.2f, viewW - s * 1.2f)
            val hy = (top + gw * 0.08f - t * gw * 0.32f).coerceAtLeast(s * 1.2f)
            heartPaint.alpha = ((1f - t) * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(hx - s * 0.5f, hy, s * 0.55f, heartPaint)
            canvas.drawCircle(hx + s * 0.5f, hy, s * 0.55f, heartPaint)
            earPath.reset()
            earPath.moveTo(hx - s * 0.95f, hy + s * 0.15f)
            earPath.lineTo(hx, hy + s * 1.2f)
            earPath.lineTo(hx + s * 0.95f, hy + s * 0.15f)
            earPath.close()
            canvas.drawPath(earPath, heartPaint)
        }
    }

    /** Sweat drops and a few short speed-lines trailing behind — how a good spook reads. */
    private fun drawSpookEffects(canvas: Canvas, cx: Float, top: Float, r: Float, gw: Float) {
        sweatPaint.alpha = (startle * 235f).toInt().coerceIn(0, 235)
        for (side in intArrayOf(-1, 1)) {
            val dropX = cx + side * r * 0.72f
            val dropY = top + r * 0.55f + sin(phase * 9f + side) * gw * 0.02f
            val s = gw * 0.055f
            rect.set(dropX - s * 0.55f, dropY - s * 0.7f, dropX + s * 0.55f, dropY + s * 0.7f)
            canvas.drawOval(rect, sweatPaint)
        }
        speedLinePaint.strokeWidth = gw * 0.02f
        speedLinePaint.alpha = (startle * 180f).toInt().coerceIn(0, 180)
        val dir = if (velX >= 0f) -1f else 1f
        for (i in 0..2) {
            val ly = top + r * (0.35f + i * 0.28f)
            val lx = cx + dir * r * (1.05f + i * 0.08f)
            canvas.drawLine(lx, ly, lx + dir * gw * 0.22f, ly, speedLinePaint)
        }
    }

    /**
     * The speech bubble: straight above his crown, centred on him, and small.
     *
     * It does not move about. It used to be nudged sideways and then slid along to dodge the screen
     * edge, which meant it wandered over his head depending on where he was standing. A long vocal
     * is broken into several small bubbles instead — see [showBubble].
     */
    private fun drawBubble(canvas: Canvas, cx: Float, top: Float) {
        val text = bubbleText ?: return
        val padX = BUBBLE_PAD_X_DP * density
        val padY = BUBBLE_PAD_Y_DP * density
        val bw = bubbleTextPaint.measureText(text) + padX * 2f
        val bh = bubbleTextPaint.textSize + padY * 2f
        val tail = BUBBLE_TAIL_DP * density
        val tailLean = tail * 0.35f
        // Measured up from his crown, not down from the bubble: the tail hangs below the bubble,
        // and sizing it from the bubble's own height put the tail — and on a small ghost the
        // bubble with it — down on top of his head.
        val bcy = (top - BUBBLE_LIFT_DP * density - tail - bh / 2f).coerceAtLeast(bh / 2f + 1f)

        rect.set(cx - bw / 2f, bcy - bh / 2f, cx + bw / 2f, bcy + bh / 2f)
        canvas.drawRoundRect(rect, bh * 0.42f, bh * 0.42f, bubbleBgPaint)
        earPath.reset()
        earPath.moveTo(cx - tailLean, bcy + bh / 2f - 1f)
        earPath.lineTo(cx - tailLean * 0.4f, bcy + bh / 2f + tail)
        earPath.lineTo(cx + tailLean, bcy + bh / 2f - 1f)
        earPath.close()
        canvas.drawPath(earPath, bubbleBgPaint)
        canvas.drawText(text, cx, bcy + bubbleTextPaint.textSize * 0.32f, bubbleTextPaint)
    }

    /**
     * Ears, drawn before the body fill so only the part outside the dome silhouette stays visible.
     * Ghosts have none; cats get triangles with a pink inner ear; dogs get floppy rotated ovals.
     */
    private fun drawEars(canvas: Canvas, cx: Float, top: Float, r: Float, gw: Float, detailed: Boolean) {
        when (species) {
            Species.GHOST -> return
            Species.CAT -> {
                val earHeight = gw * 0.275f
                val earHalfBase = gw * 0.13f
                for (side in intArrayOf(-1, 1)) {
                    val baseCx = cx + side * r * 0.60f
                    val baseY = top + r * 0.30f
                    val tipX = cx + side * r * 0.98f
                    val tipY = baseY - earHeight

                    earPath.reset()
                    earPath.moveTo(baseCx - earHalfBase, baseY)
                    earPath.lineTo(tipX, tipY)
                    earPath.lineTo(baseCx + earHalfBase, baseY)
                    earPath.close()
                    canvas.drawPath(earPath, bodyPaint)
                    canvas.drawPath(earPath, outlinePaint)

                    if (detailed) {
                        val innerHalf = earHalfBase * 0.42f
                        val innerBaseCx = baseCx + side * earHalfBase * 0.15f
                        val innerBaseY = baseY - earHeight * 0.12f
                        val innerTipX = baseCx + (tipX - baseCx) * 0.75f
                        val innerTipY = baseY + (tipY - baseY) * 0.75f
                        earPath.reset()
                        earPath.moveTo(innerBaseCx - innerHalf, innerBaseY)
                        earPath.lineTo(innerTipX, innerTipY)
                        earPath.lineTo(innerBaseCx + innerHalf, innerBaseY)
                        earPath.close()
                        canvas.drawPath(earPath, blushPaint)
                    }
                }
            }
            Species.DOG -> {
                // Long and narrow, hugging close to the body: the view only has a thin margin
                // (the padding) outside the head silhouette, so width has to come from length, not
                // from swinging wide. canvas.rotate is clockwise for positive degrees, which pulls
                // a shape *toward* the centre on the right side — so the outward lean needs the
                // opposite sign from what you'd first guess.
                val earW = gw * 0.20f
                val earH = gw * 0.50f
                val anchorY = top + r * 0.34f
                for (side in intArrayOf(-1, 1)) {
                    val anchorX = cx + side * r * 0.94f
                    canvas.save()
                    canvas.rotate(-side * 14f, anchorX, anchorY)
                    rect.set(anchorX - earW / 2f, anchorY, anchorX + earW / 2f, anchorY + earH)
                    canvas.drawOval(rect, bodyPaint)
                    canvas.drawOval(rect, outlinePaint)
                    canvas.restore()
                }
            }
        }
    }
}
