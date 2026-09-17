package com.shashank.ghostly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One button on the arc. [id] is whatever the service wants to switch on; this view never reads it. */
data class QuickAction(
    val id: String,
    val glyph: IconGlyph,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * The ring of buttons that unfurls around the ghost when you hold him — the chat-head menu.
 *
 * It draws an arc and says which button was pressed, and that is all it knows. It has no idea it is
 * in an overlay window, where that window is, or which pet it belongs to: the service centres it on
 * the ghost and sizes it from [desiredSpan].
 *
 * The view is **square and the ghost is at its exact centre**. That is the whole trick — the middle
 * is left transparent and his own window shows through it, so the buttons read as orbiting him
 * rather than sitting next to him. Anything that breaks the square, or moves the drawing off centre,
 * breaks that illusion and there is no other mechanism holding it up.
 *
 * [desiredSpan] has to be right to the pixel. An overlay window is exactly as big as the service
 * asks for, and anything drawn past its edge is clipped away with no warning — a label one pixel
 * wider than the window simply loses its last letter. So the geometry lives in [Companion] as plain
 * arithmetic over dp and the ghost's own half-width: the same numbers size the window and place the
 * buttons, and neither side can drift from the other.
 */
class QuickActionsView(context: Context) : View(context) {

    companion object {
        /** Comfortably past the 48dp-minus-slop that a finger actually needs, and no bigger: this
         *  menu sits on top of somebody else's app, so it should cover as little of it as it can. */
        const val BUTTON_DP = 44f

        /** Minimum clear space between two neighbouring label pills. Below this they read as one
         *  smeared bar rather than two captions, which is what [relabel] trims text to avoid. */
        const val GAP_DP = 8f

        /**
         * Clear space between the ghost's body and the near edge of a button.
         *
         * He has to look surrounded, not hemmed in: with the ring pressed against him the buttons
         * read as parts of the ghost — ears, growths — rather than as a menu he is standing inside.
         */
        const val RING_GAP_DP = 14f

        /** Breathing room between a button and its label pill. */
        const val LABEL_GAP_DP = 4f
        const val LABEL_DP = 9f

        /** How wide a label is ever allowed to get, before the arc trims it further. Fixed rather
         *  than measured, for the same reason the line box below is. */
        const val LABEL_MAX_DP = 56f

        /** The pill's inset around its text. Horizontal is generous and vertical is mean on
         *  purpose: a short caption needs end caps to look deliberate, not a thicker bar. */
        const val PILL_PAD_H_DP = 5f
        const val PILL_PAD_V_DP = 2f

        /** Margin between the outermost pill and the window edge, so an antialiased rounded end
         *  never lands on the clip boundary and comes out as a flat cut. */
        const val PAD_DP = 10f

        /**
         * The label's line box, fixed rather than measured.
         *
         * The window is sized before the view has ever drawn — and on some devices before it is
         * attached at all — so asking a [Paint] for its font metrics here would size the window
         * from whichever face happened to have loaded. A fixed box keeps [halfSpanPx] a pure
         * function of density, and the baseline is placed inside it by the same constant.
         */
        const val LABEL_LINE_DP = LABEL_DP * 1.35f

        /** Where the baseline sits inside that box, down from its top. */
        private const val LABEL_BASELINE_DP = LABEL_DP * 1.05f

        /**
         * Where button [index] of [count] sits on an arc of [sweep] radians centred on [centre].
         *
         * Public because the service has to run the same arithmetic before it opens the menu — it
         * tries progressively narrower sweeps until every button lands on screen — and two copies
         * of this formula would be two menus, one of them wrong.
         */
        fun angleOf(index: Int, count: Int, centre: Float, sweep: Float): Float =
            if (count <= 1) centre else centre - sweep / 2f + index * sweep / (count - 1)

        /** Radius of the ring the buttons sit on, measured from the view's centre. */
        fun ringRadiusPx(density: Float, clearancePx: Float): Float =
            clearancePx + (RING_GAP_DP + BUTTON_DP / 2f) * density

        /** The pill's box. Height is fixed; width is the *widest* a pill may ever be — [relabel]
         *  may trim a label to less, but never draws one wider, which is what lets the window be
         *  sized before a single character has been measured. */
        fun pillHeightPx(density: Float): Float = (LABEL_LINE_DP + PILL_PAD_V_DP * 2f) * density

        fun pillWidthPx(density: Float): Float = (LABEL_MAX_DP + PILL_PAD_H_DP * 2f) * density

        /** Radius of the circle the label pills' centres sit on — one pill-height further out than
         *  the buttons. See [relabel] for why labels go outward rather than straight down. */
        fun labelRadiusPx(density: Float, clearancePx: Float): Float =
            ringRadiusPx(density, clearancePx) +
                (BUTTON_DP / 2f + LABEL_GAP_DP) * density + pillHeightPx(density) / 2f

        /**
         * Half the view's span: ring + button + label, so the view is square and its centre is the
         * ghost.
         *
         * Worst case over every angle, deliberately. A pill at the side of the ring sticks out by
         * half its width, one at the top by half its height, and the window is committed before the
         * arc is known to be either — so the span assumes the wider of the two at full radius. It
         * costs some empty window, and empty window here is transparent and only swallows touches
         * (see [buttonAt]); a clipped label would cost a letter.
         */
        fun halfSpanPx(density: Float, clearancePx: Float): Float =
            labelRadiusPx(density, clearancePx) +
                maxOf(pillWidthPx(density), pillHeightPx(density)) / 2f +
                PAD_DP * density

        /**
         * How close to a button's centre a finger counts as hitting it.
         *
         * Bigger than the circle, because people aim at the caption as much as the icon, and it may
         * safely overlap a neighbour's: [buttonAt] takes the *nearest* centre, so an overlap is an
         * ordering, not an ambiguity.
         */
        fun hitRadiusPx(density: Float): Float =
            (BUTTON_DP / 2f + LABEL_GAP_DP + LABEL_LINE_DP / 2f) * density

        /** How much of the button's diameter the glyph fills. Any more and the icons touch the
         *  circle's edge, which at this size reads as a smudge rather than a symbol. */
        private const val ICON_FRACTION = 0.46f

        /** A disabled button is dimmed rather than dropped: the ring keeps the same shape whichever
         *  actions happen to be available, so the button you want is always where you last saw it. */
        private const val DISABLED_ALPHA = 0.3f

        /** Where a button starts from on the way in. Not zero — a circle growing out of nothing
         *  reads as a pop; starting most of the way there reads as it arriving. */
        private const val SCALE_FROM = 0.72f

        private const val STAGGER_MS = 32L

        /** Leaving is faster than arriving, and closes up tighter: you have already decided. */
        private const val DISMISS_STAGGER_MS = 18L

        /** Decelerating, so a button is most of the way there in the first third of its slot —
         *  the arc has to feel like it was already open by the time you look at it. */
        private fun easeOut(t: Float): Float {
            val inv = 1f - t
            return 1f - inv * inv * inv
        }
    }

    private val density = resources.displayMetrics.density

    /**
     * Contrast, without a slab.
     *
     * This floats over an arbitrary app — a white document, a black terminal, somebody's photo —
     * and a bone circle on a white page is invisible. Beside him that was solved with one
     * near-opaque plate behind the whole row; centred on him a plate would cover the ghost, which
     * is the one thing this layout exists to show. So the contrast is carried per button instead:
     * the filled [Palette.bone] circle is its own plate, and the label — small white text, the part
     * that actually dies over a busy wallpaper — gets a [Palette.ink] pill with the app's
     * [Palette.cardStroke] hairline. Everything between them stays transparent.
     *
     * `setShadowLayer` is banned in [GhostView] because it drags the whole view through software
     * rendering on every one of its thirty frames a second. This view is static once revealed, so
     * that argument does not apply here directly — but the frames it *does* draw are the entry and
     * exit animations, which are exactly the frames that have to feel instant under a finger. A
     * soft shadow would tax only those. It buys nothing the pill does not already give, so it is
     * still not worth having.
     */
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.ink
        alpha = 232
    }
    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.cardStroke
        strokeWidth = density
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.bone }

    /** A pressed button goes down to the dimmer bone rather than growing a ring — a ring at this
     *  size reads as a second, smaller button. */
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.accentDeep }

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.bone
        textAlign = Paint.Align.CENTER
        textSize = LABEL_DP * density
        typeface = Type.sansMedium(context)
    }

    private val rect = RectF()

    private var actions: List<QuickAction> = emptyList()

    /** The glyphs, built once in [setActions]: [onDraw] allocates nothing, for the same reason
     *  [GhostView] and [IconDrawable] hoist theirs. Their bounds are set at the origin and the
     *  canvas is walked to the button, so a change of arc never has to touch them. */
    private var icons: Array<IconDrawable> = emptyArray()
    private var iconSide = 0

    /** Labels trimmed in [relabel]. Pre-ellipsized rather than clipped at draw time: [onDraw] runs
     *  under a finger and must not measure text, and a label clipped by the canvas loses its last
     *  glyph mid-stroke, which reads as a rendering fault rather than an abbreviation. */
    private var labels: Array<String> = emptyArray()

    /** Each trimmed label's width, measured once in [relabel]. [onDraw] runs every frame of the
     *  unfurl and has no business asking a [Paint] to measure anything. */
    private var labelWidths: FloatArray = FloatArray(0)

    /** The arc, until [setArc] says otherwise: a half-circle above him, hugging nothing. Sane
     *  defaults only so a caller that forgets still draws something recognisable. */
    private var centreAngleRad = -Math.PI.toFloat() / 2f
    private var sweepRad = Math.PI.toFloat()
    private var clearancePx = 0f

    var onPick: ((QuickAction) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private var pressed = -1

    private enum class Phase { HIDDEN, REVEALING, SHOWN, DISMISSING }

    private var phase = Phase.HIDDEN
    private var phaseStart = 0L
    private var afterDismiss: (() -> Unit)? = null

    /**
     * The unfurl. Each button starts [STAGGER_MS] after the one before it, and index order is arc
     * order, so the ring sweeps open from one end rather than blinking into existence all at once.
     */
    private val revealMs = 150L
    private val dismissMs = 100L

    fun setActions(actions: List<QuickAction>) {
        this.actions = actions
        iconSide = (BUTTON_DP * density * ICON_FRACTION).toInt()
        icons = Array(actions.size) { i ->
            IconDrawable(actions[i].glyph, Palette.ink).apply { setBounds(0, 0, iconSide, iconSide) }
        }
        relabel()
        pressed = -1
        requestLayout()
        invalidate()
    }

    /**
     * Where the arc goes.
     *
     * @param centreAngleRad direction the arc is centred on, screen coords, 0 = right, -PI/2 = up
     * @param sweepRad total angular spread of the arc
     * @param clearancePx half the ghost's body width
     *
     * The clearance comes from the service because only the service knows how big he is: species
     * differ, he breathes, and the ring has to clear whichever body is actually on screen.
     */
    fun setArc(centreAngleRad: Float, sweepRad: Float, clearancePx: Float) {
        this.centreAngleRad = centreAngleRad
        this.sweepRad = sweepRad
        this.clearancePx = clearancePx
        // The trim depends on how close the neighbours ended up, so it has to be redone here and
        // not only in setActions.
        relabel()
        requestLayout()
        invalidate()
    }

    /**
     * Labels, trimmed to whatever room this particular arc left them.
     *
     * A row gave every label a cell of its own. An arc does not: squeeze four buttons into a narrow
     * sweep and neighbouring captions overlap, which is worse than useless — two half-words on top
     * of each other say less than one. Two things keep them apart.
     *
     * First, a label sits *radially outward* from its button rather than always below it. Outward
     * is free separation — the labels ride a bigger circle than the buttons, so the same angle buys
     * more gap — and it also guarantees nothing is ever drawn between a button and the ghost, which
     * is the part of the view that has to stay clear.
     *
     * Second, when the sweep is tight enough that even that is not enough, the text is ellipsized
     * harder rather than the pill being moved further out or dropped. Pushing labels outward would
     * grow the radius, and the window is square and centred on him, so every dp of radius costs
     * four times its area in window that sits over someone else's app. "Fe…" under a bowl icon is
     * still legible with the glyph right above it; a caption that has wandered a centimetre from
     * its button is not obviously its caption at all.
     *
     * The floor is about two glyphs plus the ellipsis: below that the label says nothing and the
     * pill is only clutter, and a sweep that tight is one the service should not have picked.
     */
    private fun relabel() {
        val cap = pillWidthPx(density) - PILL_PAD_H_DP * 2f * density
        val count = actions.size
        var room = cap
        if (count > 1) {
            val step = abs(angleOf(1, count, centreAngleRad, sweepRad) - angleOf(0, count, centreAngleRad, sweepRad))
            // Straight-line distance between neighbouring pill centres: the chord, not the arc, is
            // what two horizontal pills actually have to share.
            val chord = 2f * labelRadiusPx(density, clearancePx) * abs(sin(step / 2f))
            val fit = chord - GAP_DP * density - PILL_PAD_H_DP * 2f * density
            room = minOf(cap, fit).coerceAtLeast(LABEL_DP * 2.5f * density)
        }
        labels = Array(count) { i ->
            TextUtils.ellipsize(actions[i].label, labelPaint, room, TextUtils.TruncateAt.END).toString()
        }
        labelWidths = FloatArray(count) { i -> labelPaint.measureText(labels[i]) }
    }

    /** Zero until [setActions] has been called — the service must set the actions before it sizes
     *  the window, not after. Square, so there is one number: the ghost is at its centre. */
    fun desiredSpan(): Int =
        if (actions.isEmpty()) 0 else (halfSpanPx(density, clearancePx) * 2f).toInt()

    /** Kept as the names the service already calls; the shape is a square either way. */
    fun desiredWidth(): Int = desiredSpan()

    fun desiredHeight(): Int = desiredSpan()

    /** Play the entry animation. */
    fun reveal() {
        phase = Phase.REVEALING
        phaseStart = SystemClock.uptimeMillis()
        pressed = -1
        invalidate()
        postOnAnimation(stepRunnable)
    }

    /** Play the exit animation, then call [then]. */
    fun dismiss(then: () -> Unit) {
        if (phase == Phase.DISMISSING) return
        afterDismiss = then
        // Nothing detached ever gets another animation frame, so the callback the service is
        // waiting on to tear its window down would never arrive. Skip the flourish instead.
        if (!isAttachedToWindow) {
            phase = Phase.HIDDEN
            finishDismiss()
            return
        }
        phase = Phase.DISMISSING
        phaseStart = SystemClock.uptimeMillis()
        pressed = -1
        invalidate()
        postOnAnimation(stepRunnable)
    }

    /**
     * Driven from [postOnAnimation] rather than a [android.animation.ValueAnimator], the way
     * [MeterView.step] is.
     *
     * A ValueAnimator would have to be held, cancelled and null-checked across a reveal that can be
     * interrupted by a dismiss at any moment, and it keeps ticking against a view whose window the
     * service may already have removed. A posted callback is dropped by the platform the instant
     * the view detaches, which is the behaviour we want for free — and it allocates nothing per
     * frame, which the animator's interpolated values do not.
     */
    private val stepRunnable = Runnable { step() }

    private fun step() {
        val elapsed = SystemClock.uptimeMillis() - phaseStart
        val total = when (phase) {
            Phase.REVEALING -> revealMs + STAGGER_MS * maxOf(actions.size - 1, 0)
            Phase.DISMISSING -> dismissMs + DISMISS_STAGGER_MS * maxOf(actions.size - 1, 0)
            else -> return
        }
        invalidate()
        if (elapsed < total) {
            postOnAnimation(stepRunnable)
            return
        }
        if (phase == Phase.DISMISSING) {
            phase = Phase.HIDDEN
            finishDismiss()
        } else {
            phase = Phase.SHOWN
        }
    }

    /** Cleared before the call so a service that tears the view down inside its own callback cannot
     *  come back round and fire it twice. */
    private fun finishDismiss() {
        val done = afterDismiss ?: return
        afterDismiss = null
        done()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The window went away mid-animation — the service still needs its completion.
        phase = Phase.HIDDEN
        finishDismiss()
    }

    /** How far button [index] is into its own part of the animation, 0..1. */
    private fun progressOf(index: Int, elapsed: Long): Float = when (phase) {
        Phase.HIDDEN -> 0f
        Phase.SHOWN -> 1f
        Phase.REVEALING -> easeOut(((elapsed - index * STAGGER_MS).toFloat() / revealMs).coerceIn(0f, 1f))
        // Reversed, so the arc furls back the way it came instead of collapsing onto the ghost.
        Phase.DISMISSING -> {
            val from = (actions.size - 1 - index) * DISMISS_STAGGER_MS
            1f - easeOut(((elapsed - from).toFloat() / dismissMs).coerceIn(0f, 1f))
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (actions.isEmpty() || phase == Phase.HIDDEN) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Measured, not [halfSpanPx]: if the window ever came back a pixel off what was asked for,
        // the ghost is still under the middle of what we actually got.
        val ox = w / 2f
        val oy = h / 2f

        val elapsed = SystemClock.uptimeMillis() - phaseStart
        val ring = ringRadiusPx(density, clearancePx)
        val labelRing = labelRadiusPx(density, clearancePx)
        val r = BUTTON_DP * density / 2f
        val pillH = pillHeightPx(density)
        val baselineFromPillTop = (PILL_PAD_V_DP + LABEL_BASELINE_DP) * density
        val strokeInset = pillStrokePaint.strokeWidth / 2f
        val count = actions.size

        for (i in 0 until count) {
            val t = progressOf(i, elapsed)
            if (t <= 0f) continue
            val action = actions[i]
            val angle = angleOf(i, count, centreAngleRad, sweepRad)
            val cosA = cos(angle)
            val sinA = sin(angle)
            val cx = ox + ring * cosA
            val cy = oy + ring * sinA
            val alpha = (255f * t * (if (action.enabled) 1f else DISABLED_ALPHA)).toInt().coerceIn(0, 255)

            // The pill first, so where a tight arc does let one reach under a neighbouring button
            // the button wins — it is the target, and it is the thing that must stay circular.
            val label = labels[i]
            if (label.isNotEmpty()) {
                val pw = labelWidths[i] + PILL_PAD_H_DP * 2f * density
                val px = ox + labelRing * cosA
                val py = oy + labelRing * sinA
                rect.set(
                    px - pw / 2f + strokeInset,
                    py - pillH / 2f + strokeInset,
                    px + pw / 2f - strokeInset,
                    py + pillH / 2f - strokeInset,
                )
                // The pill fades with the unfurl but is never dimmed for a disabled action: it is
                // the background the text has to survive on, and dimming it dims the contrast too.
                pillPaint.alpha = (232f * t).toInt()
                pillStrokePaint.alpha = (255f * t).toInt()
                canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, pillPaint)
                canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, pillStrokePaint)
                labelPaint.alpha = alpha
                canvas.drawText(label, px, py - pillH / 2f + baselineFromPillTop, labelPaint)
            }

            canvas.save()
            // Grown from the button's own centre, so a half-revealed arc is still a ring of buttons
            // rather than one that slides around as it opens.
            canvas.scale(SCALE_FROM + (1f - SCALE_FROM) * t, SCALE_FROM + (1f - SCALE_FROM) * t, cx, cy)

            val fill = if (i == pressed) pressedPaint else buttonPaint
            fill.alpha = alpha
            canvas.drawCircle(cx, cy, r, fill)
            canvas.translate(cx - iconSide / 2f, cy - iconSide / 2f)
            icons[i].setAlpha(alpha)
            icons[i].draw(canvas)
            canvas.restore()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(desiredSpan(), desiredSpan())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            // The service sets FLAG_WATCH_OUTSIDE_TOUCH, which is the only reason this arrives at
            // all. It carries no usable coordinates (see the class doc on GhostOverlayService), but
            // none are needed: anywhere-but-here is the whole meaning of the event.
            onDismiss?.invoke()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = buttonAt(event.x, event.y)
                if (pressed < 0 && insideRing(event.x, event.y)) {
                    // The ghost is under this window now, and a touchable window swallows what lands
                    // on it — so his own tap target is gone for as long as the menu is open. Tapping
                    // him is how you put a menu away, so honour it here rather than eating it.
                    onDismiss?.invoke()
                    return true
                }
                invalidate()
                // Consumed even on a miss out in the ring: a tap that fell between two buttons is a
                // fumble, not a request to close, and closing on it loses the menu you just opened.
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Sliding off the button you pressed is how you back out of a tap you did not mean.
                if (pressed >= 0 && buttonAt(event.x, event.y) != pressed) {
                    pressed = -1
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val hit = pressed
                pressed = -1
                invalidate()
                if (hit >= 0 && buttonAt(event.x, event.y) == hit) {
                    performClick()
                    onPick?.invoke(actions[hit])
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Which button is under the finger, or -1.
     *
     * Nearest centre wins, within [hitRadiusPx] of it. A row could be divided into columns; a ring
     * cannot be divided into anything simple, and the honest question on an arc is which button you
     * were reaching for. Nearest-centre answers it without the targets having to tile, which is
     * what lets them be generous enough to cover the captions people aim at.
     *
     * A disabled action reports -1 so it can neither highlight nor fire, while the touch itself is
     * still swallowed above.
     */
    private fun buttonAt(x: Float, y: Float): Int {
        if (actions.isEmpty()) return -1
        val ox = width / 2f
        val oy = height / 2f
        val ring = ringRadiusPx(density, clearancePx)
        val limit = hitRadiusPx(density)
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in actions.indices) {
            val angle = angleOf(i, actions.size, centreAngleRad, sweepRad)
            val d = hypot(x - (ox + ring * cos(angle)), y - (oy + ring * sin(angle)))
            if (d <= limit && d < bestDist) {
                bestDist = d
                best = i
            }
        }
        if (best >= 0 && !actions[best].enabled) return -1
        return best
    }

    /** Inside the clear middle — which is to say, on the ghost. */
    private fun insideRing(x: Float, y: Float): Boolean =
        hypot(x - width / 2f, y - height / 2f) < ringRadiusPx(density, clearancePx) - BUTTON_DP * density / 2f
}
