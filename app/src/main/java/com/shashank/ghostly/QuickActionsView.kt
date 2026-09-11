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

/** One button in the row. [id] is whatever the service wants to switch on; this view never reads it. */
data class QuickAction(
    val id: String,
    val glyph: IconGlyph,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * The row of buttons that unfurls beside the ghost when you hold him — the chat-head menu.
 *
 * It draws a row and says which button was pressed, and that is all it knows. It has no idea it is
 * in an overlay window, where that window is, or which pet it belongs to: the service positions it
 * next to the ghost and sizes it from [desiredWidth]/[desiredHeight].
 *
 * Those two numbers have to be right to the pixel. An overlay window is exactly as big as the
 * service asks for, and anything drawn past its edge is clipped away with no warning — a label one
 * pixel wider than the window simply loses its last letter. So the geometry lives in [Companion] as
 * plain arithmetic over dp: the same numbers size the window and lay out the row, and neither side
 * can drift from the other.
 */
class QuickActionsView(context: Context) : View(context) {

    companion object {
        /** Comfortably past the 48dp-minus-slop that a finger actually needs, and no bigger: this
         *  row sits on top of somebody else's app, so it should cover as little of it as it can. */
        const val BUTTON_DP = 44f
        const val GAP_DP = 8f

        /** Breathing room between a button and its label, and between the label and the scrim edge. */
        const val LABEL_GAP_DP = 4f
        const val LABEL_DP = 9f

        /** Inset of the row inside the scrim. */
        const val PAD_DP = 10f

        /**
         * The label's line box, fixed rather than measured.
         *
         * The window is sized before the view has ever drawn — and on some devices before it is
         * attached at all — so asking a [Paint] for its font metrics here would size the window
         * from whichever face happened to have loaded. A fixed box keeps [desiredHeight] a pure
         * function of density, and the baseline is placed inside it by the same constant.
         */
        const val LABEL_LINE_DP = LABEL_DP * 1.35f

        /** Where the baseline sits inside that box, down from its top. */
        private const val LABEL_BASELINE_DP = LABEL_DP * 1.05f

        /** One button and the gap that follows it: the pitch the row steps along. */
        private fun pitchPx(density: Float): Float = (BUTTON_DP + GAP_DP) * density

        fun widthPx(count: Int, density: Float): Int {
            if (count <= 0) return 0
            val row = count * BUTTON_DP * density + (count - 1) * GAP_DP * density
            return (row + PAD_DP * density * 2f).toInt()
        }

        fun heightPx(density: Float): Int =
            ((BUTTON_DP + LABEL_GAP_DP + LABEL_LINE_DP + PAD_DP * 2f) * density).toInt()

        /** Centre of button [index], measured from the view's left edge. */
        fun centreXPx(index: Int, density: Float): Float =
            (PAD_DP + BUTTON_DP / 2f) * density + index * pitchPx(density)

        /** How much of the button's diameter the glyph fills. Any more and the icons touch the
         *  circle's edge, which at this size reads as a smudge rather than a symbol. */
        private const val ICON_FRACTION = 0.46f

        /** A disabled button is dimmed rather than dropped: the row keeps the same shape whichever
         *  actions happen to be available, so the button you want is always where you last saw it. */
        private const val DISABLED_ALPHA = 0.3f

        /** Where a button starts from on the way in. Not zero — a circle growing out of nothing
         *  reads as a pop; starting most of the way there reads as it arriving. */
        private const val SCALE_FROM = 0.72f

        private const val STAGGER_MS = 32L

        /** Leaving is faster than arriving, and closes up tighter: you have already decided. */
        private const val DISMISS_STAGGER_MS = 18L

        /** Decelerating, so a button is most of the way there in the first third of its slot —
         *  the row has to feel like it was already open by the time you look at it. */
        private fun easeOut(t: Float): Float {
            val inv = 1f - t
            return 1f - inv * inv * inv
        }
    }

    private val density = resources.displayMetrics.density

    /**
     * Contrast, without a shadow.
     *
     * This floats over an arbitrary app — a white document, a black terminal, somebody's photo —
     * and a bone circle on a white page is invisible. The fix is the app's own card language: a
     * near-opaque [Palette.ink] slab with a [Palette.cardStroke] hairline, which needs no blur to
     * separate from anything underneath.
     *
     * `setShadowLayer` is banned in [GhostView] because it drags the whole view through software
     * rendering on every one of its thirty frames a second. This view is static once revealed, so
     * that argument does not apply here directly — but the frames it *does* draw are the entry and
     * exit animations, which are exactly the frames that have to feel instant under a finger. A
     * soft shadow would tax only those. It buys nothing the scrim does not already give, so it is
     * still not worth having.
     */
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.ink
        alpha = 232
    }
    private val scrimStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    /** The glyphs, built once in [setActions] with their bounds already set: [onDraw] allocates
     *  nothing, for the same reason [GhostView] and [IconDrawable] hoist theirs. */
    private var icons: Array<IconDrawable> = emptyArray()

    /** Labels trimmed to the pitch. A label wider than its own cell would either collide with the
     *  next one or push the buttons apart — and pushing them apart would make the window's width
     *  depend on text measurement, which is the one thing [widthPx] must not do. */
    private var labels: Array<String> = emptyArray()

    var onPick: ((QuickAction) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private var pressed = -1

    private enum class Phase { HIDDEN, REVEALING, SHOWN, DISMISSING }

    private var phase = Phase.HIDDEN
    private var phaseStart = 0L
    private var afterDismiss: (() -> Unit)? = null

    /**
     * The unfurl. Each button starts [STAGGER_MS] after the one before it, so the row reads as
     * opening outward from the ghost rather than blinking into existence all at once.
     */
    private val revealMs = 150L
    private val dismissMs = 100L

    fun setActions(actions: List<QuickAction>) {
        this.actions = actions
        val side = (BUTTON_DP * density * ICON_FRACTION).toInt()
        val top = ((PAD_DP + BUTTON_DP / 2f) * density - side / 2f).toInt()
        icons = Array(actions.size) { i ->
            IconDrawable(actions[i].glyph, Palette.ink).apply {
                val left = (centreXPx(i, density) - side / 2f).toInt()
                setBounds(left, top, left + side, top + side)
            }
        }
        val maxLabel = (BUTTON_DP + GAP_DP) * density
        labels = Array(actions.size) { i ->
            TextUtils.ellipsize(actions[i].label, labelPaint, maxLabel, TextUtils.TruncateAt.END).toString()
        }
        pressed = -1
        requestLayout()
        invalidate()
    }

    /** Zero until [setActions] has been called — the service must set the actions before it sizes
     *  the window, not after. */
    fun desiredWidth(): Int = widthPx(actions.size, density)

    fun desiredHeight(): Int = heightPx(density)

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
        // Reversed, so the row furls back the way it came instead of collapsing onto the ghost.
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

        val elapsed = SystemClock.uptimeMillis() - phaseStart
        // The slab fades as one piece; only the buttons stagger. A scrim that arrived a button at a
        // time would look like the background was tearing.
        val slab = progressOf(if (phase == Phase.DISMISSING) actions.size - 1 else 0, elapsed)
        val inset = scrimStrokePaint.strokeWidth / 2f
        val radius = h * 0.30f
        rect.set(inset, inset, w - inset, h - inset)
        scrimPaint.alpha = (232f * slab).toInt()
        scrimStrokePaint.alpha = (255f * slab).toInt()
        canvas.drawRoundRect(rect, radius, radius, scrimPaint)
        canvas.drawRoundRect(rect, radius, radius, scrimStrokePaint)

        val r = BUTTON_DP * density / 2f
        val cy = (PAD_DP * density) + r
        val baseline = cy + r + (LABEL_GAP_DP + LABEL_BASELINE_DP) * density

        for (i in actions.indices) {
            val t = progressOf(i, elapsed)
            if (t <= 0f) continue
            val action = actions[i]
            val cx = centreXPx(i, density)
            val alpha = (255f * t * (if (action.enabled) 1f else DISABLED_ALPHA)).toInt().coerceIn(0, 255)

            canvas.save()
            // Grown from the button's own centre, so a half-revealed row is still a straight line
            // of buttons rather than a row that slides sideways as it opens.
            canvas.scale(SCALE_FROM + (1f - SCALE_FROM) * t, SCALE_FROM + (1f - SCALE_FROM) * t, cx, cy)

            val fill = if (i == pressed) pressedPaint else buttonPaint
            fill.alpha = alpha
            canvas.drawCircle(cx, cy, r, fill)
            icons[i].setAlpha(alpha)
            icons[i].draw(canvas)
            canvas.restore()

            labelPaint.alpha = alpha
            canvas.drawText(labels[i], cx, baseline, labelPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(desiredWidth(), desiredHeight())
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
                pressed = cellAt(event.x, event.y)
                invalidate()
                // Consumed even on a miss inside the scrim: a tap on the slab between two buttons is
                // a fumble, not a request to close, and letting it fall through would close the menu.
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Sliding off the button you pressed is how you back out of a tap you did not mean.
                if (pressed >= 0 && cellAt(event.x, event.y) != pressed) {
                    pressed = -1
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val hit = pressed
                pressed = -1
                invalidate()
                if (hit >= 0 && cellAt(event.x, event.y) == hit) {
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
     * The target is the button's whole column — full height of the view, one pitch wide — not the
     * circle. The label belongs to its button and people aim at it, and at 44dp with a scrim around
     * it there is nothing else nearby to hit by mistake. A disabled action reports -1 so it can
     * neither highlight nor fire, while the touch itself is still swallowed above.
     */
    private fun cellAt(x: Float, y: Float): Int {
        if (y < 0f || y > height.toFloat()) return -1
        val first = PAD_DP * density
        val index = ((x - first) / pitchPx(density)).toInt()
        if (index < 0 || index >= actions.size) return -1
        if (x < first || x > width - PAD_DP * density) return -1
        if (!actions[index].enabled) return -1
        return index
    }
}
