package com.shashank.ghostly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
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

        /**
         * Clear space between the ghost's body and the near edge of a button.
         *
         * He has to look surrounded, not hemmed in: with the ring pressed against him the buttons
         * read as parts of the ghost — ears, growths — rather than as a menu he is standing inside.
         */
        const val RING_GAP_DP = 14f

        /** How far a finger may miss a button and still count — see [hitRadiusPx]. */
        const val LABEL_GAP_DP = 4f

        /** Margin between the outermost button and the window edge, so an antialiased circle never
         *  lands on the clip boundary and comes out as a flat cut. */
        const val PAD_DP = 10f

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

        /**
         * Half the view's span: the ring plus a button, so the view is square and its centre is
         * the ghost.
         *
         * The captions used to live out past this and they dominated it — the window was 687px on
         * a 1080px screen, most of it reserved for the widest a caption might turn out to be at
         * whichever angle it landed. Icons alone, it is nearer 420. Every pixel here is composited
         * over whatever app you were looking at, so that is most of the cost of the menu gone for
         * four words nobody needed: an apple, a triangle, a heart and a moon say it already.
         */
        fun halfSpanPx(density: Float, clearancePx: Float): Float =
            ringRadiusPx(density, clearancePx) + (BUTTON_DP / 2f + PAD_DP) * density

        /**
         * How close to a button's centre a finger counts as hitting it.
         *
         * Comfortably past the circle, and it may safely overlap a neighbour's: [buttonAt] takes
         * the *nearest* centre, so an overlap is an ordering, not an ambiguity.
         */
        fun hitRadiusPx(density: Float): Float = (BUTTON_DP / 2f + LABEL_GAP_DP) * density

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
     * each filled [Palette.bone] circle is its own plate, with an ink glyph on it, and everything
     * between them stays transparent.
     *
     * That leaves a bone circle on a pale background as the one weak case, and it is survivable —
     * the glyph inside it is ink, so the symbol reads even where its plate does not.
     *
     * `setShadowLayer` is banned in [GhostView] because it drags the whole view through software
     * rendering on every one of its thirty frames a second. This view is static once revealed, so
     * that argument does not apply here directly — but the frames it *does* draw are the entry and
     * exit animations, which are exactly the frames that have to feel instant under a finger. A
     * soft shadow would tax only those, and buy little enough that it is still not worth having.
     */
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.bone }

    /** A pressed button goes down to the dimmer bone rather than growing a ring — a ring at this
     *  size reads as a second, smaller button. */
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.accentDeep }

    private var actions: List<QuickAction> = emptyList()

    /** The glyphs, built once in [setActions]: [onDraw] allocates nothing, for the same reason
     *  [GhostView] and [IconDrawable] hoist theirs. Their bounds are set at the origin and the
     *  canvas is walked to the button, so a change of arc never has to touch them. */
    private var icons: Array<IconDrawable> = emptyArray()
    private var iconSide = 0

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
        pressed = -1
        // The captions are not drawn any more — an apple, a triangle, a heart and a moon say it
        // without them, and the room they took was most of the window. They are still carried on
        // [QuickAction] and spoken here, because an icon-only control that says nothing to a screen
        // reader is a control those users do not have.
        contentDescription = actions.joinToString(", ") { it.label }
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
        requestLayout()
        invalidate()
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
        val r = BUTTON_DP * density / 2f
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
