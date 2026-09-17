package com.shashank.ghostly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * Holds the ghost in a system overlay window and runs his motion loop.
 *
 * There are two ways to run him, because Android forces a choice:
 *
 * - **Intangible** (default): the window is `FLAG_NOT_TOUCHABLE`, so every touch — including one
 *   right on top of him — goes to the app underneath and nothing he sits on is ever blocked. He is
 *   still told *that* a tap happened (`FLAG_WATCH_OUTSIDE_TOUCH`) but never *where*: the platform
 *   zeroes the coordinates of outside touches (measured on Android 15: `rawX=0, rawY=0`). So he
 *   reacts to any tap, with habituation so that typing does not send him into a panic.
 * - **Solid**: the window is touchable, with a small halo of personal space around him. Now he can
 *   be poked precisely, dragged and long-pressed — at the cost of swallowing taps where he floats.
 *
 * ### One pet, in a shape that holds more
 *
 * Everything that belongs to *a* floating pet — his window, his position, his velocity, his mood,
 * his brain — lives on [FloatingPet], and the service holds a list of them. Exactly one is created:
 * the primary, on the same flat preference keys he has always used. What stays out here is what is
 * genuinely shared and must stay single no matter how many of him there are: the display bounds,
 * the clock, the foreground notification, the screen on/off receiver, the preference listener, and
 * above all the frame loop — see [frameCallback].
 */
class GhostOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.shashank.ghostly.START"
        const val ACTION_STOP = "com.shashank.ghostly.STOP"

        /** He steps off the screen and into the box for a moment — see [setVisiting]. */
        const val ACTION_VISIT = "com.shashank.ghostly.VISIT"
        const val EXTRA_VISITING = "visiting"

        /** He flies to a point on screen and waits there — see [comeHome]. */
        const val ACTION_COME_HOME = "com.shashank.ghostly.COME_HOME"

        /**
         * Something was done to him in the app while he was out here — see [react].
         *
         * The app used to fly him into his box for these, which is what made him appear to wander
         * back indoors on his own. He stays where he is now and the reaction comes to him.
         */
        const val ACTION_REACT = "com.shashank.ghostly.REACT"
        const val EXTRA_REACTION = "reaction"
        const val EXTRA_SLOT = "slot"

        /**
         * Where his body's top-left should be, in screen pixels. Carried on a start, on the end of
         * a visit and on [ACTION_COME_HOME], so that he appears exactly where the app last drew
         * him instead of popping into being somewhere else.
         */
        const val EXTRA_BODY_X = "bodyX"
        const val EXTRA_BODY_Y = "bodyY"

        /**
         * How far through his idle bob the app's ghost was at the moment of the hand-over, so the
         * one out here carries on from the same place rather than cross-fading out of step.
         */
        const val EXTRA_IDLE_CLOCK = "idleClock"
        private const val CHANNEL_ID = "ghost_overlay"
        private const val NOTIFICATION_ID = 7
        private const val WATCHDOG_INTERVAL_MS = 2_000L

        /**
         * Writes that can change how he is feeling, or how he looks. Nothing else needs a redraw.
         *
         * These are *base* names. A stored key carries the slot it belongs to on the end for every
         * pet but the first (`hunger#3`), so they are matched through [Prefs.baseOf] rather than
         * against the stored key itself — see the listener in [attachOverlay].
         */
        private val MOOD_KEYS = setOf("hunger", "energy", "happiness", "anger", "sleeping", "fed_at")
        private val LOOK_KEYS = setOf("species", "shade", "size_dp", "color_hue", "name")

        /**
         * Whether he can be tapped outside the app. Unlike [MOOD_KEYS]/[LOOK_KEYS] this is not a
         * redraw: it decides `FLAG_NOT_TOUCHABLE`, which is only read when the window is created
         * and cannot be flipped on a live one — see [recreateWindow]. It is also the account's
         * setting rather than a pet's, so it has no slot and is matched exactly.
         */
        private const val CLICK_THROUGH_KEY = "click_through"

        /** Whether he wanders at all. Account-wide, like [CLICK_THROUGH_KEY], and read live. */
        private const val STAY_PUT_KEY = "stay_put"

        /** Frame budgets for the two quiet states — see the note in the frame callback. */
        private const val IDLE_FRAME_SECONDS = 1f / 15f
        private const val SLEEP_FRAME_SECONDS = 1f / 8f
        private const val BEHAVIOUR_INTERVAL_MS = 15_000L
        private const val SYNC_INTERVAL_MS = 10L * 60 * 1000
        private const val FIRST_SYNC_DELAY_MS = 4_000L
        private const val MIN_FRAME_SECONDS = 1f / 30f

        /** Transparent ring around the ghost that still reacts to a tap, in dp. */
        private const val HALO_DP = 22f

        /** At or above this, he reads as brimming with energy: quicker, puffed, a little arrogant. */
        private const val ENERGY_FULL_THRESHOLD = 90f

        /** Long enough to read as a dissolve, short enough that he is never missing. */
        private const val FADE_MS = 170L

        /** Set pieces that make sense as a way of getting off a wall — none of them edge-seeking. */
        private val EDGE_RECOVERY_MOVES = listOf(
            Locomotion.ROLLOVER,
            Locomotion.ORBIT,
            Locomotion.PACE,
            Locomotion.BOUNCE,
        )

        /** How long he may stay against a wall before he thinks better of it. */
        private const val PINNED_SECONDS = 1.3f

        /** How far he stays out of the rounded corners, measured along the short way out. */
        private const val CORNER_KEEPOUT_DP = 10f

        /** How much of the screen, top and bottom, he keeps out of. */
        private const val BAND_MARGIN = 0.05f

        /** Longer than any flight across a screen; a cap, not a schedule. */
        private const val HOMING_TIMEOUT_SECONDS = 4f

        private const val PET_HOLD_MS = 1_000L

        /** How long the quick actions wait to be used before taking themselves away. It is sitting
         *  on top of somebody else's app; it should not need dismissing. */
        private const val MENU_IDLE_MS = 6_000L

        /** Air between him and the row of buttons, so the two read as separate things. */
        private const val MENU_GAP_DP = 10f
        private const val PET_ANIMATION_MS = 2_000L
        private const val DOUBLE_TAP_MS = 300L

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * True while he is in the app's box instead of over your apps. He is still floating —
         * the service keeps running and the notification stays — he is simply not drawn out
         * there, because there is only ever one of him and right now he is in the box.
         */
        @Volatile
        var isVisiting: Boolean = false
            private set

        /** His body's top-left on screen right now, so the app can pick him up where he is. */
        @Volatile
        var bodyX: Float = 0f
            private set

        @Volatile
        var bodyY: Float = 0f
            private set

        /** Set once he has reached the point [comeHome] sent him to. */
        @Volatile
        var arrivedHome: Boolean = false
            private set

        /** His idle bob, for the box to pick up when he is handed back — see [EXTRA_IDLE_CLOCK]. */
        @Volatile
        var idleClock: Float = 0f
            private set

        /**
         * Returns false when Android refused the start. From the foreground this always works; from
         * a broadcast it can be refused — `MY_PACKAGE_REPLACED` is not one of the exemptions for
         * starting a foreground service, and an unhandled refusal crashes the process.
         */
        fun start(
            context: Context,
            bodyX: Float? = null,
            bodyY: Float? = null,
            idleClock: Float? = null,
        ): Boolean = runCatching {
            val intent = Intent(context, GhostOverlayService::class.java).setAction(ACTION_START)
            if (bodyX != null && bodyY != null) {
                intent.putExtra(EXTRA_BODY_X, bodyX).putExtra(EXTRA_BODY_Y, bodyY)
            }
            if (idleClock != null) intent.putExtra(EXTRA_IDLE_CLOCK, idleClock)
            context.startForegroundService(intent)
        }.isSuccess

        /**
         * Sends him flying to a point on screen — the middle of the app's box — and sets
         * [arrivedHome] when he gets there. The app waits for that before taking him over, so he
         * travels home instead of blinking out of one place and into another.
         */
        fun comeHome(context: Context, bodyX: Float, bodyY: Float) {
            if (!isRunning) return
            arrivedHome = false
            runCatching {
                context.startService(
                    Intent(context, GhostOverlayService::class.java)
                        .setAction(ACTION_COME_HOME)
                        .putExtra(EXTRA_BODY_X, bodyX)
                        .putExtra(EXTRA_BODY_Y, bodyY)
                )
            }
        }

        /**
         * Hides or restores the floating ghost without stopping the service, so feeding, playing
         * and petting can happen in the box — where they belong — while he is out floating, and he
         * drifts straight back out afterwards.
         */
        fun setVisiting(
            context: Context,
            visiting: Boolean,
            bodyX: Float? = null,
            bodyY: Float? = null,
            idleClock: Float? = null,
        ) {
            if (!isRunning) return
            runCatching {
                val intent = Intent(context, GhostOverlayService::class.java)
                    .setAction(ACTION_VISIT)
                    .putExtra(EXTRA_VISITING, visiting)
                if (bodyX != null && bodyY != null) {
                    intent.putExtra(EXTRA_BODY_X, bodyX).putExtra(EXTRA_BODY_Y, bodyY)
                }
                if (idleClock != null) intent.putExtra(EXTRA_IDLE_CLOCK, idleClock)
                context.startService(intent)
            }
        }

        /**
         * Plays a reaction on the pet who is already floating: he has been fed, played with or
         * given something from inside the app, and none of that is a reason to move him.
         *
         * The stat changes have already been applied by the caller — this is the animation only,
         * so nothing is counted twice.
         */
        fun react(context: Context, reaction: String, slot: Int = PetStore.PRIMARY_SLOT) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, GhostOverlayService::class.java)
                        .setAction(ACTION_REACT)
                        .putExtra(EXTRA_REACTION, reaction)
                        .putExtra(EXTRA_SLOT, slot)
                )
            }
        }

        fun stop(context: Context) {
            // Marked as gone here, not in onDestroy. Tearing the service down takes a few dozen
            // milliseconds, and everything that asks [isRunning] in between — the app's own
            // refreshState above all — would otherwise be told he is still out there and empty the
            // box he has just been handed into, for as long as the teardown takes. He was visibly
            // blinking out of the box at the end of every "call him home" because of it.
            isRunning = false
            isVisiting = false
            runCatching {
                context.startService(
                    Intent(context, GhostOverlayService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }

    // region shared state

    private lateinit var windowManager: WindowManager

    private var density = 1f
    private var clickThrough = true

    /**
     * He holds his position instead of drifting. Unlike click-through this needs no window rebuild
     * — it only decides whether anything moves him — so it is read live and takes effect on the
     * next frame.
     */
    private var stayPut = false
    private var touchSlop = 0

    /** Smallest movement worth a window relayout — a dp, not a pixel. See [FloatingPet.applyPosition]. */
    private var moveThresholdPx = 1

    /** The whole display. */
    private val bounds = Rect()

    /**
     * Where he is actually allowed to float: the display minus the status bar, the navigation or
     * gesture bar and any cutout. Without this he drifts underneath the bottom bar and disappears.
     */
    private val usable = Rect()

    /**
     * Every pet currently out there.
     *
     * Exactly one for now — the primary — and that is the point of the list rather than a reason
     * against it: everything per pet already lives on [FloatingPet], so growing the roster is a
     * change to what goes in here and to nothing else.
     */
    private val pets = mutableListOf<FloatingPet>()

    /** The one the app's box hands back and forth, and the one the notification speaks for. */
    private fun primary(): FloatingPet? = pets.firstOrNull { it.pet.isPrimary }

    /** The shared timeline every pet's timers are measured against. */
    private var clock = 0f
    private var lastFrameNanos = 0L
    private var lastFrameAt = 0L
    private var looping = false
    private val handler = Handler(Looper.getMainLooper())

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var receiverRegistered = false

    /**
     * The content pack, parsed once for everyone.
     *
     * It is a quarter of a megabyte of JSON and identical for every pet, so it is read here and
     * handed to each pet's own [BehaviourEngine]. The engines themselves must stay per pet: what
     * an engine remembers is what it has just had *this* pet say, and sharing that would have five
     * ghosts taking it in turns to speak out of one mouth.
     */
    private var behaviourPack: BehaviourPack? = null
    private var packVersion = Int.MIN_VALUE

    private fun pack(): BehaviourPack? {
        val version = BehaviourPack.loadedVersion(this)
        if (version != packVersion) {
            behaviourPack = BehaviourPack.load(this)
            packVersion = version
        }
        return behaviourPack
    }

    /**
     * The last outside touch already dealt with, by the time the platform stamped it.
     *
     * `ACTION_OUTSIDE` is delivered to every watching overlay window, so one tap on screen arrives
     * once per pet. [FloatingPet.noticeTap] counts taps in a four-second window to work out whether
     * you are typing at it, so letting all the copies through would read one deliberate poke as a
     * five-tap burst and drop every pet straight into its keyboard cooldown — he would stop
     * reacting to being tapped at all. The event time is identical across the copies, because they
     * are one input event fanned out, and that is what tells a copy from a second tap.
     */
    private var lastOutsideTapAt = Long.MIN_VALUE

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            ContentSync.schedule(this@GhostOverlayService)
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private val behaviourRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            // A behaviour chosen while the screen is off, or while he is hidden in the app's box,
            // is decided, animated and thrown away unseen.
            if (looping) {
                for (p in pets) p.proposeBehaviour()
            }
            handler.postDelayed(this, BEHAVIOUR_INTERVAL_MS)
        }
    }

    /**
     * One callback for all of them, however many there are, and it has to stay that way: a bare
     * chain — each frame scheduling the next — freezes for good if the platform drops the pending
     * callback at display-off (reported on a Galaxy S24), and one chain to keep alive is already
     * enough. [startLoop]/[stopLoop], the screen on/off receiver and [watchdog] exist to guard this
     * single loop; a callback per pet would need all three again, per pet, and any one of them
     * dropped would leave that pet frozen over your apps forever.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning || !looping) return
            val elapsed = (frameTimeNanos - lastFrameNanos) / 1e9f
            // A slow drifting ghost does not need 60 or 120 frames a second, and every frame moves
            // a window, which is far from free — measured at roughly double the CPU at 45fps versus
            // 30. Thirty is indistinguishable at this speed.
            // What makes stepping visible is SPEED, not what he happens to be doing. Splitting the
            // budget by state got this wrong: a zoomies launch sets no routine, so it dropped to
            // fifteen frames while moving over a thousand pixels a second — a hundred-pixel jump
            // per frame, which is exactly as choppy as it sounds. Anything moving faster than a
            // gentle drift gets the full rate; only genuinely slow motion is allowed to save.
            //
            // With a roster that stops being one decision. The loop has to wake as often as the
            // neediest pet needs it — a finger dragging one of them cannot be made to stutter
            // because the others are asleep — but the saving is per pet and must stay per pet, or
            // one dragged ghost would drag all five up to full rate and with them every window
            // relayout the cap was measured to avoid. So: the loop runs at the fastest budget
            // anyone is asking for, and each pet takes only the frames his own budget allows,
            // inside [FloatingPet.step].
            // Indexed loops and a running minimum, not `minOfOrNull` and `for (p in pets)`. Both
            // of those allocate on a path that runs thirty times a second — the selector overload
            // returns a boxed `Float?`, and each `for..in` over a list takes an iterator. This
            // file already hoists a two-element IntArray for exactly that reason, and with one pet
            // the allocations were pure loss against the code this replaced.
            // Each pet's budget is computed once here and remembered, so [FloatingPet.step] can
            // re-apply it without deciding twice; it costs a hypot per pet per frame.
            var minFrame = IDLE_FRAME_SECONDS
            for (i in pets.indices) {
                val budget = pets[i].takeFrameBudget()
                if (budget < minFrame) minFrame = budget
            }
            if (lastFrameNanos != 0L && elapsed < minFrame) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val dt = if (lastFrameNanos == 0L) 0.016f else elapsed.coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos
            lastFrameAt = SystemClock.elapsedRealtime()
            clock += dt
            for (i in pets.indices) pets[i].step(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Frame callbacks are not guaranteed to survive the display going off — on some devices the
     * pending one is simply dropped, and since each frame is what schedules the next, the ghost
     * freezes for good. This notices that within a couple of seconds and starts the loop again.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val stalled = SystemClock.elapsedRealtime() - lastFrameAt > 1_500
            if (!looping || stalled) startLoop()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /** The ghost has nothing to do while nobody can see him — and hours of that is a flat battery. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // The menu is anchored to a pet who is about to stop moving behind a dark
                    // screen. Leaving it up means finding it again on unlock, over whatever is
                    // there by then.
                    closeQuickActions()
                    stopLoop()
                }
                Intent.ACTION_SCREEN_ON -> startLoop()
                Intent.ACTION_USER_PRESENT -> {
                    Prefs.recordUnlock(this@GhostOverlayService)
                    startLoop()
                }
            }
        }
    }

    private fun startLoop() {
        if (!looping) {
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        }
        looping = true
        lastFrameNanos = 0L
        lastFrameAt = SystemClock.elapsedRealtime()
        // Every pet's own frame clock restarts with the loop's, exactly as lastFrameNanos does:
        // the first step after the screen comes back on is a fresh 16ms, not however long the
        // phone spent in the dark clamped down to the 50ms ceiling.
        for (p in pets) p.resetFrameClock()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
        for (p in pets) p.invalidate()
    }

    private fun stopLoop() {
        looping = false
        // Nothing is being drawn, so nothing needs watching. This used to keep waking the main
        // looper every two seconds all night for no purpose.
        handler.removeCallbacks(watchdog)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /** Picks up the box's idle bob, if this intent brought one. */
    private fun Intent.adoptIdleClock() {
        if (!hasExtra(EXTRA_IDLE_CLOCK)) return
        primary()?.adoptIdleClock(getFloatExtra(EXTRA_IDLE_CLOCK, 0f))
    }

    /** Body top-left in screen pixels, as carried on an intent — null when it wasn't. */
    private fun Intent.bodyPoint(): Pair<Float, Float>? {
        if (!hasExtra(EXTRA_BODY_X) || !hasExtra(EXTRA_BODY_Y)) return null
        return getFloatExtra(EXTRA_BODY_X, 0f) to getFloatExtra(EXTRA_BODY_Y, 0f)
    }

    /**
     * A tap landed somewhere on screen, told to us once per overlay window — see [lastOutsideTapAt].
     * The first copy through fans it out to everyone; the rest are dropped.
     */
    private fun noticeOutsideTap(eventTime: Long) {
        if (eventTime == lastOutsideTapAt) return
        lastOutsideTapAt = eventTime
        for (p in pets) p.noticeTap()
    }

    /**
     * Steps him off the screen and into the app's box, or brings him back out. The window stays
     * added and the service stays in the foreground — only the drawing stops, so coming back is
     * instant and none of his position or state is lost.
     */
    private fun setVisiting(visiting: Boolean) {
        // He is stepping into the app's box, or out of it. Either way the menu is pointing at a
        // ghost who will not be there.
        if (visiting) closeQuickActions()
        if (isVisiting == visiting) return
        isVisiting = visiting
        if (pets.isEmpty()) return
        if (visiting) {
            // The loop is stopped under the last pet's fade rather than the first's: stopping it
            // sooner would leave the others frozen halfway through dissolving.
            val last = pets.last()
            for (p in pets) p.fadeOut(if (p === last) Runnable { stopLoop() } else null)
        } else {
            // Same again coming back out of a visit: the box is still drawing him where he is
            // about to appear, so he takes over at full strength rather than dissolving in.
            for (p in pets) p.fadeIn()
            startLoop()
        }
    }

    // endregion

    // region one pet

    /**
     * One floating pet: his window, where he is, where he is going, how he feels, and his brain.
     *
     * Everything in here is his alone. Nothing on it may be hoisted back onto the service for
     * convenience — that is exactly how a second pet ends up wearing the first one's velocity —
     * and nothing genuinely shared may be duplicated into it, which is why the display bounds,
     * the clock, the handler and the frame loop are reached through the outer class instead.
     */
    private inner class FloatingPet(initial: Pet) {
        /**
         * Who he is and what he looks like, kept honest by [syncAppearance]. His stats are
         * deliberately not in here — see [Pet].
         */
        var pet: Pet = initial
            private set

        /** His identity, and the suffix on every preference key that is about him. */
        val slot: Int get() = pet.slot

        /** The service, as a [Context]. `this` inside here is the pet, not the service. */
        private val ctx: Context get() = this@GhostOverlayService

        private lateinit var params: WindowManager.LayoutParams
        private var root: FrameLayout? = null
        private var ghost: GhostView? = null

        /** Size of the ghost, and of the window that carries him (ghost + halo on every side). */
        private var ghostPx = 0
        private var windowPx = 0
        private var haloPx = 0

        /**
         * A small, invisible, touchable window sitting exactly on his body — the only thing in
         * solid mode that swallows a tap.
         *
         * It exists because the drawn window cannot do this job. That one has to be far bigger than
         * he is: it carries the speech bubble's headroom above him and the contrast wash below, so
         * at the default size it measures 297x323 where his body is 101 across (measured on a
         * Motorola Edge 70 Pro, 450dpi). Making *that* touchable, which is what solid mode used to
         * do, put a dead rectangle nine times his own area under the user's finger and dragged it
         * around the screen with him.
         *
         * The obvious fix — one window, touchable only over a sub-region — needs
         * ViewTreeObserver.OnComputeInternalInsetsListener and TOUCHABLE_INSETS_REGION, which are
         * @hide and @UnsupportedAppUsage in AOSP. That is restricted non-SDK surface and not
         * something to build a shipping feature on at API 36. Two windows is the public answer: the
         * drawn one stays intangible forever, and this one, his size plus a small grab margin,
         * catches what lands on him.
         */
        private var touchPatch: View? = null
        private var patchParams: WindowManager.LayoutParams? = null

        /** Space above him inside the window, so a speech bubble is never clipped. */
        private var headroomPx = 0

        /**
         * Blank room on each side of his body, inside the window. The speech bubble is a fixed size
         * at every ghost size, so on a small ghost it is wider than he is and needs somewhere to
         * go. [posX] still means the left edge of his body's own window — only the window we hand
         * the window manager is wider, and it is pushed left by this much to keep him where he was.
         */
        private var sidePx = 0

        /** Blank room below his body inside the window, so the contrast wash is not cut off. */
        private var haloPadPx = 0

        // Position of the window's top-left corner, kept as floats so motion stays smooth.
        private var posX = 0f
        private var posY = 0f
        private var velX = 0f
        private var velY = 0f

        // He never stops: this is the heading he keeps drifting along between scares.
        private var driftAngle = Random.nextFloat() * 2f * PI.toFloat()
        private var driftSpeed = 0f

        /**
         * A movement set piece that has taken him over for a few seconds — a roll, a bounce, a
         * loop. Null the rest of the time, when he is simply drifting.
         */
        private var routine: Locomotion? = null
        private var routineUntil = 0f
        private var routineAnchorX = 0f
        private var routineAnchorY = 0f
        private var routineAngle = 0f
        private var routineDir = 1f
        private var routineSpeed = 0f

        /** When he first ended up against a wall, so being stuck on one can be noticed and undone. */
        private var pinnedSince = 0f

        /** He is flying to a point the app named, rather than drifting — see [comeHome]. */
        private var homing = false
        private var homingToX = 0f
        private var homingToY = 0f

        /** A flight that never lands — the app died mid-hand-off — must not strand him hovering. */
        private var homingUntil = 0f

        // Hue is not part of [Pet] — it is always null now, and only kept so an older build's
        // stored value can be cleared — so unlike size, shade and species it needs its own memory
        // of what the view was last set to.
        private var lastTintHue: Float? = -999f

        /** The frame he was last stepped on, which is not every frame the loop runs — see [step]. */
        private var lastStepNanos = 0L

        // Reacting to taps he cannot locate: recent tap times, so he can get used to a burst.
        private val recentTaps = ArrayDeque<Long>()
        private var lastReactionAt = 0L

        // Where he is looking, in screen pixels, and when to pick somewhere new.
        private var gazeScreenX = 0f
        private var gazeScreenY = 0f
        private var nextGlanceAt = 0f

        // Mood, read from Emotions. Refreshed on a slow timer plus whenever a stat changes (feeding,
        // playing, a manual nap, a treat or gift) so the two screens never drift far apart.
        var sleeping = false
            private set
        var mood: Mood = Mood.CONTENT
            private set
        private var energyFull = false
        private var nextStatsTickAt = 0f

        // A hungry buzz + meow/woof, or a happy little vocalisation, every so often.
        private var nextFlourishAt = 20f

        // Puffed up, floating and holding in a corner for a few seconds — a random idle quirk.
        private var goofyUntil = 0f
        private var nextGoofyCheckAt = 45f
        private var goofyCornerX = 0f
        private var goofyCornerY = 0f

        // So the notification is only rebuilt when what it would say actually changes.
        private var notifiedSleeping = false
        private var lastExpression: Expression = Expression.NONE
        private var notifiedMood: Mood = Mood.CONTENT

        // Drag bookkeeping
        private var dragging = false
        private var downRawX = 0f
        private var downRawY = 0f
        private var downPosX = 0f
        private var downPosY = 0f
        private var downTime = 0L
        private var lastDragX = 0f
        private var lastDragY = 0f
        private var lastDragNanos = 0L

        // Petting: a hold that starts and stays on him, as opposed to a quick poke or a drag past
        // him — only reachable in solid (non-click-through) mode, where a touch's exact position is
        // actually known. Mirrors GhostPlayground's hold/animation cadence.
        private var pettingArmed = false
        private var petTriggered = false
        private var lastPetAt = 0L
        private val holdRunnable = Runnable {
            if (!pettingArmed || dragging) return@Runnable
            // A hold used to start petting and keep it going for as long as the finger stayed put.
            // It opens the quick actions instead now, and petting is one of them — a hold is the
            // only gesture he has spare, and a menu you can reach from inside someone else's app
            // is worth more than a stroke you can already give him in the box.
            pettingArmed = false
            openQuickActions(this)
        }

        private val petRunnable = Runnable {
            if (!pettingArmed || dragging) return@Runnable
            petTriggered = true
            stroke()
        }

        // Double tap: a second quick tap close to the first, within the platform's usual double-tap
        // window, opens the app instead of fleeing. A lone tap still flees, just after that same
        // brief window closes with no second tap to pair it with.
        private var pendingTapRunnable: Runnable? = null
        private var lastTapUpAt = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f

        /** What the brain wants to know: what this pet last had done to him, and when. */
        private var lastInteractionAt = 0L
        private var lastPetEvent: PetEvent? = null

        /** His own brain. Content-driven; with no pack loaded it simply never proposes anything. */
        private var behaviourEngine: BehaviourEngine? = null
        private var enginePackVersion = Int.MIN_VALUE

        /** Rebuilt whenever a newer pack has been downloaded since it was last built. */
        private fun engine(): BehaviourEngine {
            val shared = pack()
            val current = behaviourEngine
            if (current != null && packVersion == enginePackVersion) return current
            return BehaviourEngine(shared).also {
                behaviourEngine = it
                enginePackVersion = packVersion
            }
        }

        private var lastAppliedX = Int.MIN_VALUE
        private var lastAppliedY = Int.MIN_VALUE

        // region his window

        /**
         * Builds his window and puts it up. [spawn] is his body's top-left, handed over from the
         * app's box; without one he goes back to wherever he was last saved.
         */
        fun attach(spawn: Pair<Float, Float>?) {
            // Re-read rather than trust the object we were constructed with: a rebuild after a
            // click-through change can be minutes after the roster was assembled.
            pet = PetStore.read(ctx, slot)
            ghostPx = (pet.sizeDp * density).toInt()
            // Always zero now. The halo used to fatten the drawn window in solid mode so there was
            // something to grab beyond his outline; that margin moved to [touchPatch], which is the
            // only window that can be grabbed at all. Keeping this at zero means the drawn window
            // has one geometry in both modes — the one the default mode has always used.
            haloPx = 0
            windowPx = ghostPx + haloPx * 2
            headroomPx = GhostView.headroomPx(density, ghostPx)
            sidePx = GhostView.bubbleSidePx(density, ghostPx)
            haloPadPx = GhostView.haloPadPx(ghostPx)
            driftSpeed = 18f * density

            val view = GhostView(ctx)
            view.setBodySize(ghostPx)
            view.setShade(pet.shade)
            view.species = pet.species
            lastTintHue = Prefs.colorHue(ctx)
            view.setTint(lastTintHue)
            ghost = view
            val container = FrameLayout(ctx).apply {
                addView(
                    view,
                    FrameLayout.LayoutParams(
                        ghostPx + sidePx * 2,
                        ghostPx + headroomPx + haloPadPx,
                        android.view.Gravity.CENTER
                    )
                )
            }
            root = container

            // The drawn window never takes a touch, in either mode. What changes between them is
            // whether [touchPatch] exists alongside it. FLAG_WATCH_OUTSIDE_TOUCH stays on in both,
            // because it is how he notices a tap going past him and that is not a solid-mode idea.
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

            params = WindowManager.LayoutParams(
                windowPx + sidePx * 2,
                windowPx + headroomPx + haloPadPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                // Android's touch-filtering treats a FLAG_NOT_TOUCHABLE overlay left at the default
                // alpha (1.0) as fully opaque for tapjacking purposes, regardless of how transparent
                // its actual drawing is — since Android 12 that blocks the tap from reaching the app
                // underneath entirely. The platform will clamp this for us if we don't (visible as a
                // "setting alpha to 0.80" warning in logcat), but relying on that silent correction
                // instead of setting it ourselves isn't guaranteed across OS versions/OEM skins.
                // Unconditional now that the drawn window is never touchable, which also means he
                // looks identical in both modes rather than a shade more solid in one of them.
                alpha = 0.8f
            }

            // Sent out from the box, he starts exactly where the box was drawing him, so the
            // hand-off between the two is invisible: same ghost, same spot, and only then does he
            // drift off.
            if (spawn != null) {
                posX = spawn.first
                posY = spawn.second - headroomPx
            } else {
                posX = Prefs.lastX(ctx, bounds.width() * 0.72f, slot)
                posY = Prefs.lastY(ctx, bounds.height() * 0.35f, slot)
            }
            clampIntoBounds()
            params.x = posX.toInt() - sidePx
            params.y = posY.toInt()

            // Only outside taps reach the drawn window; anything landing on him is the patch's.
            container.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) noticeOutsideTap(event.eventTime)
                false
            }
            // Handed over from the box he arrives at full strength, on the spot and at the point in
            // his bob the box last drew him at — the same picture, so the box can drop away beneath
            // him without anything showing. Only a cold start (the notification, the watchdog) has
            // nothing to take over from, and fades in.
            container.alpha = if (spawn != null) 1f else 0f
            windowManager.addView(container, params)
            if (spawn == null) container.animate().alpha(1f).setDuration(FADE_MS).start()
            if (!clickThrough) attachTouchPatch()

            refreshMood()
        }

        /** Half his own width again, so he can be grabbed without having to be hit dead centre. */
        private fun grabPx(): Int = (ghostPx * 0.18f).toInt()

        /** His body's centre on screen. The drawn window starts [headroomPx] above his crown. */
        fun bodyCentreX(): Float = posX + ghostPx / 2f
        fun bodyCentreY(): Float = posY + headroomPx + ghostPx / 2f
        fun bodyHalfPx(): Float = ghostPx / 2f

        private fun patchSpan(): Int = ghostPx + grabPx() * 2

        /**
         * Puts up the small touchable window and hands it the gestures. Only in solid mode — in
         * the default one nothing of his is touchable at all, which is the whole point of it.
         */
        private fun attachTouchPatch() {
            if (touchPatch != null) return
            val patch = View(ctx)
            // Every gesture he has is on this window now, so the listener is the real one.
            patch.setOnTouchListener { _, event -> onGhostTouch(event) }
            val lp = WindowManager.LayoutParams(
                patchSpan(),
                patchSpan(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            positionPatch(lp)
            touchPatch = patch
            patchParams = lp
            runCatching { windowManager.addView(patch, lp) }
                .onFailure { touchPatch = null; patchParams = null }
        }

        private fun detachTouchPatch() {
            val patch = touchPatch ?: return
            touchPatch = null
            patchParams = null
            runCatching { windowManager.removeView(patch) }
        }

        /** Centres the patch on his body, which sits [headroomPx] down inside the drawn window. */
        private fun positionPatch(lp: WindowManager.LayoutParams) {
            val grab = grabPx()
            lp.x = posX.toInt() - grab
            lp.y = posY.toInt() + headroomPx - grab
        }

        /** Takes his window down, leaving where he was behind for the next [attach] to pick up. */
        fun detach() {
            cancelGestures()
            detachTouchPatch()
            val view = root ?: return
            Prefs.savePosition(ctx, posX, posY, slot)
            runCatching { windowManager.removeView(view) }
            root = null
            ghost = null
            // Stale relayout dedupe from the window we just tore down; force the first position
            // after a rebuild through rather than have it skipped as "no real movement".
            lastAppliedX = Int.MIN_VALUE
            lastAppliedY = Int.MIN_VALUE
        }

        /** Drops everything mid-gesture or mid-flight that is tied to the window being replaced. */
        fun cancelGestures() {
            if (menuOwner === this) closeQuickActions()
            handler.removeCallbacks(petRunnable)
            handler.removeCallbacks(holdRunnable)
            pendingTapRunnable?.let { handler.removeCallbacks(it) }
            pendingTapRunnable = null
            dragging = false
            pettingArmed = false
            homing = false
            routine = null
        }

        fun fadeOut(onEnd: Runnable?) {
            // Straight away, not on the fade's end action: the patch is invisible, so there is
            // nothing to fade, and leaving it up for the length of the fade leaves a dead square
            // over the app he is stepping into.
            detachTouchPatch()
            val view = root ?: return
            view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                if (isVisiting) view.visibility = View.GONE
                onEnd?.run()
            }.start()
        }

        fun fadeIn() {
            val view = root ?: return
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 1f
            if (!clickThrough) attachTouchPatch()
        }

        /** Put back into the hidden, stopped state a visit left him in, after a window rebuild. */
        fun hideForVisit() {
            // See [fadeOut]: a hidden ghost must not leave a hole in the screen where he was.
            detachTouchPatch()
            val view = root ?: return
            view.animate().cancel()
            view.alpha = 0f
            view.visibility = View.GONE
        }

        fun invalidate() {
            ghost?.invalidate()
        }

        fun adoptIdleClock(value: Float) {
            ghost?.syncIdleClock(value)
        }

        // endregion

        // region the frame

        /**
         * The shortest gap he is prepared to be redrawn at, in seconds. Zero means every frame the
         * display has. The loop asks all of them and runs at the smallest answer — see the long
         * note on [frameCallback] for why the saving then has to be taken again, per pet, in [step].
         */
        fun frameBudget(): Float = when {
            // Every frame the display has, for the two moments the eye is actually following him:
            // the flight home, and a finger dragging him about. Both are short, both are
            // deliberate, and the cap was costing them badly — homing runs at up to thirty-odd
            // times drift speed, which at thirty frames is a sixty-pixel jump between frames.
            homing || dragging -> 0f
            hypot(velX, velY) > driftSpeed * 2f || routine != null -> MIN_FRAME_SECONDS
            sleeping -> SLEEP_FRAME_SECONDS
            else -> IDLE_FRAME_SECONDS
        }

        /**
         * One frame of him — if this is a frame he is owed.
         *
         * Skipping here skips the whole step: physics, view and window alike. That is deliberate.
         * The 30fps cap was measured on what a frame *costs* — a window relayout, a recomposite
         * and a view advance — not on the cost of being called, so stepping him anyway and only
         * throttling the relayout would give the cap away while looking like it was still there.
         */
        /** This frame's budget, decided once by the loop — see the note in [frameCallback]. */
        private var budget = IDLE_FRAME_SECONDS

        fun takeFrameBudget(): Float {
            budget = frameBudget()
            return budget
        }

        fun step(frameTimeNanos: Long) {
            val elapsed = (frameTimeNanos - lastStepNanos) / 1e9f
            if (lastStepNanos != 0L && elapsed < budget) return
            val dt = if (lastStepNanos == 0L) 0.016f else elapsed.coerceIn(0.001f, 0.05f)
            lastStepNanos = frameTimeNanos
            tick(dt)
            watchForPinning()
            ghost?.let { view ->
                view.advance(dt)
                view.invalidate()
                // The box picks his bob up from here when he is handed back, and there is one box.
                if (pet.isPrimary) idleClock = view.idleClock()
            }
        }

        fun resetFrameClock() {
            lastStepNanos = 0L
        }

        // endregion

        // region mood and looks

        /**
         * Catches his stats and his mood up to now, pushes the result onto the view, and keeps the
         * notification honest about how he's doing.
         */
        fun refreshMood() {
            val s = Emotions.snapshot(ctx, slot)
            sleeping = s.body.sleeping
            mood = s.mood
            energyFull = s.body.energy >= ENERGY_FULL_THRESHOLD
            ghost?.setMood(mood, sleeping)
            // A face that follows from the numbers — only re-shown when it changes, so it does not
            // restart itself every refresh.
            val face = expressionFor(s.body)
            if (face != lastExpression) {
                lastExpression = face
                if (face != Expression.NONE) ghost?.showExpression(face, 3.4f)
            }

            if (sleeping != notifiedSleeping || mood != notifiedMood) {
                notifiedSleeping = sleeping
                notifiedMood = mood
                // The notification and the widget both speak for the primary; the others changing
                // how they feel is not news either of them can carry.
                if (pet.isPrimary) {
                    refreshNotification()
                    runCatching { GhostlyWidgetProvider.refreshAll(ctx) }
                }
            }
        }

        /**
         * Resizes, recolours and reshapes the live window in place when the Style tab changes,
         * keeping him centred at the same spot rather than snapping to a corner or flickering off
         * and back on.
         *
         * Species used to be missing from here even though it is in [LOOK_KEYS], which is why
         * changing it needed the whole service restarted and he visibly blinked out and back.
         * Nothing about his silhouette touches the window's geometry — ears and horns live inside
         * the body's own padding — so it is an assignment and a redraw, and that is all.
         */
        fun syncAppearance() {
            val view = ghost ?: return
            val fresh = PetStore.read(ctx, slot)

            val newHue = Prefs.colorHue(ctx)
            if (newHue != lastTintHue) {
                lastTintHue = newHue
                view.setTint(newHue)
            }

            if (fresh.species != pet.species) {
                view.species = fresh.species
                // The loop may be throttled down to eight frames a second, or stopped altogether
                // while he is in the box, so the redraw is asked for rather than waited for.
                view.invalidate()
            }

            if (fresh.sizeDp != pet.sizeDp) {
                val container = root ?: return
                val newGhostPx = (fresh.sizeDp * density).toInt()
                val newWindowPx = newGhostPx + haloPx * 2
                val delta = (newWindowPx - windowPx) / 2f
                posX -= delta
                posY -= delta
                ghostPx = newGhostPx
                windowPx = newWindowPx
                headroomPx = GhostView.headroomPx(density, ghostPx)
                haloPadPx = GhostView.haloPadPx(ghostPx)
                // The side room follows his size now, so it has to be recomputed here too.
                sidePx = GhostView.bubbleSidePx(density, ghostPx)
                clampIntoBounds()
                params.width = windowPx + sidePx * 2
                params.height = windowPx + headroomPx + haloPadPx
                params.x = posX.toInt() - sidePx
                params.y = posY.toInt()
                view.setBodySize(ghostPx)
                view.layoutParams = FrameLayout.LayoutParams(
                    ghostPx + sidePx * 2,
                    ghostPx + headroomPx + haloPadPx,
                    android.view.Gravity.CENTER
                )
                runCatching { windowManager.updateViewLayout(container, params) }
                lastAppliedX = params.x
                lastAppliedY = params.y
                // The grab area is a fraction of his width, so a resize moves it too. Without this
                // a Wisp promoted to a Haunt would keep a Wisp-sized patch and be nearly unhittable
                // — and the reverse would leave a Haunt-sized dead zone around a tiny ghost.
                patchParams?.let { lp ->
                    lp.width = patchSpan()
                    lp.height = patchSpan()
                    positionPatch(lp)
                    touchPatch?.let { patch ->
                        runCatching { windowManager.updateViewLayout(patch, lp) }
                    }
                }
            }

            if (fresh.shade != pet.shade) view.setShade(fresh.shade)

            // Renaming him changes nothing you can see out here — but the notification says his
            // name, and until this it went on saying the old one until he was next put to bed.
            val renamed = fresh.name != pet.name

            pet = fresh
            if (renamed && pet.isPrimary) refreshNotification()
        }

        /** Feeding and napping happen in the app, not on the overlay; they arrive as pref changes. */
        fun noteEvent(base: String) {
            when (base) {
                "fed_at" -> noteInteraction(PetEvent.FED)
                "sleeping" -> if (Prefs.sleeping(ctx, slot)) noteInteraction(PetEvent.NAPPED)
            }
        }

        /** Something the user did — the brain wants to know what happened last, and when. */
        private fun noteInteraction(event: PetEvent) {
            lastPetEvent = event
            lastInteractionAt = System.currentTimeMillis()
            ContentSync.recordEvent(ctx, event.id)
        }

        // endregion

        // region the brain

        /** Ask the brain what he feels like doing, and then do it. */
        fun proposeBehaviour() {
            val brain = engine()
            if (!brain.isLoaded) return
            val view = ghost ?: return
            val petContext = PetContext.of(ctx, lastPetEvent, lastInteractionAt, slot)
            val behaviour = brain.next(petContext, ContentSync.dailyBoosts(ctx)) ?: return
            perform(behaviour, view)
        }

        /**
         * Turn a decision into something you can see.
         *
         * The pack says what he feels and how he should move; this is the only place that knows how
         * to express either. Nothing here overrides an act the user just took — a behaviour that
         * arrives while he is being petted, eating or asleep is dropped, because his own moment
         * beats the content pack's suggestion.
         */
        private fun perform(behaviour: Behaviour, view: GhostView) {
            if (sleeping || petTriggered) return

            behaviour.vocal?.let { view.showBubble(it, 1.9f) }

            when (behaviour.emote) {
                Emote.HAPPY -> {
                    view.showExpression(Expression.SMILE, 2.2f)
                    view.startWiggle()
                }
                Emote.AFFECTION -> {
                    view.showExpression(Expression.DELIGHTED, 2.4f)
                    view.spawnHeart()
                }
                Emote.SLEEPY -> view.showExpression(Expression.SLEEPY, 3.0f)
                Emote.CURIOUS -> view.showExpression(Expression.CONFUSED, 2.2f)
                Emote.SPOOKED -> view.spook()
                Emote.MOODY -> view.spookLightly()
                Emote.GOOFY -> view.setPuffTarget(0.30f * behaviour.intensity)
                Emote.CONFIDENT -> view.setPuffTarget(0.10f * behaviour.intensity)
                Emote.HUNGRY -> view.showExpression(Expression.CONFUSED, 1.6f)
            }
            if (behaviour.emote != Emote.GOOFY && behaviour.emote != Emote.CONFIDENT) {
                view.setPuffTarget(0f)
            }

            // Kept in one place: he still gets the face, the noise and the flourish the pack
            // chose, but not the part that would carry him off. Returning before the locomotion
            // rather than swallowing it in tick() matters — a routine set here and never advanced
            // stays non-null for good, and frameBudget reads a live routine as "moving", so he
            // would sit perfectly still while drawing at thirty frames a second. Measured: that
            // mistake cost more than letting him wander.
            if (stayPut) return

            // Locomotion is a nudge to the drift, never a teleport: he is a ghost, he glides.
            val speed = driftSpeed * (0.6f + behaviour.intensity)
            when (behaviour.locomotion) {
                Locomotion.DRIFT -> driftAngle = Random.nextFloat() * 2f * PI.toFloat()
                Locomotion.FLEE -> launch(angleTowardsOpenSpace())
                Locomotion.APPROACH ->
                    aimAt(usable.centerX().toFloat(), usable.centerY().toFloat(), speed * 2f)
                Locomotion.PERCH_CORNER -> aimAt(nearestCornerX(), nearestCornerY(), speed * 1.6f)
                Locomotion.ZOOMIES -> {
                    launch(Random.nextFloat() * 2f * PI.toFloat())
                    view.startWiggle()
                }
                Locomotion.STILL -> {
                    velX = 0f
                    velY = 0f
                }
                Locomotion.ROLLOVER, Locomotion.BOUNCE, Locomotion.ORBIT,
                Locomotion.PACE, Locomotion.EDGE_SLIDE, Locomotion.PEEK ->
                    startRoutine(behaviour.locomotion, behaviour.intensity, speed, view)
            }
        }

        // endregion

        // region touch

        private fun onGhostTouch(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                // Fires for every tap on screen while intangible, once per window. No coordinates —
                // see the class doc. The deduping is the service's; see [noticeOutsideTap].
                noticeOutsideTap(event.eventTime)
                return false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downPosX = posX
                    downPosY = posY
                    downTime = SystemClock.uptimeMillis()
                    lastDragX = event.rawX
                    lastDragY = event.rawY
                    lastDragNanos = System.nanoTime()
                    velX = 0f
                    velY = 0f
                    val cx = posX + windowPx / 2f
                    val cy = posY + headroomPx + windowPx / 2f
                    ghost?.lookAt(
                        (event.rawX - cx) / (windowPx / 2f),
                        (event.rawY - cy) / (windowPx / 2f)
                    )
                    petTriggered = false
                    pettingArmed = true
                    handler.postDelayed(holdRunnable, PET_HOLD_MS)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        pettingArmed = false
                        // Carrying him off is an answer to the menu too: it was anchored to where
                        // he was standing, and he is not standing there any more.
                        if (menuOwner === this) closeQuickActions()
                        handler.removeCallbacks(petRunnable)
            handler.removeCallbacks(holdRunnable)
                    }
                    if (dragging) {
                        posX = downPosX + dx
                        posY = downPosY + dy
                        clampIntoBounds()
                        applyPosition()

                        val now = System.nanoTime()
                        val dt = ((now - lastDragNanos) / 1e9f).coerceAtLeast(0.004f)
                        velX = (event.rawX - lastDragX) / dt
                        velY = (event.rawY - lastDragY) / dt
                        lastDragX = event.rawX
                        lastDragY = event.rawY
                        lastDragNanos = now
                        ghost?.setMotion(velX * 0.35f, velY * 0.35f)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(petRunnable)
            handler.removeCallbacks(holdRunnable)
                    pettingArmed = false
                    if (!dragging) {
                        if (petTriggered) {
                            // Already petted via the hold — nothing more to do on release.
                        } else if (sleeping) {
                            // Stirred, not startled: a poke while napping just wakes him.
                            wakeUp()
                        } else {
                            val now = SystemClock.uptimeMillis()
                            val isSecondTap = now - lastTapUpAt < DOUBLE_TAP_MS &&
                                hypot(event.rawX - lastTapX, event.rawY - lastTapY) < touchSlop * 3
                            if (isSecondTap) {
                                // Caught the pending flee from the first tap in time — open the app
                                // instead of letting him bolt.
                                pendingTapRunnable?.let { handler.removeCallbacks(it) }
                                pendingTapRunnable = null
                                lastTapUpAt = 0L
                                openApp()
                            } else {
                                lastTapUpAt = now
                                lastTapX = event.rawX
                                lastTapY = event.rawY
                                val fx = event.rawX
                                val fy = event.rawY
                                val runnable = Runnable { fleeFrom(fx, fy) }
                                pendingTapRunnable = runnable
                                handler.postDelayed(runnable, DOUBLE_TAP_MS)
                            }
                        }
                    } else {
                        // Released mid-drag: keep the throw, but keep it sane.
                        val speed = hypot(velX, velY)
                        val maxSpeed = 700f * density
                        if (speed > maxSpeed) {
                            velX = velX / speed * maxSpeed
                            velY = velY / speed * maxSpeed
                        }
                        driftAngle = atan2(velY, velX)
                        Prefs.savePosition(ctx, posX, posY, slot)
                    }
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(petRunnable)
            handler.removeCallbacks(holdRunnable)
                    pettingArmed = false
                    dragging = false
                    return true
                }
            }
            return false
        }

        /**
         * A tap landed somewhere on screen while he is intangible. He cannot know where, so he
         * reacts to all of them — but he gets used to them: a burst of taps (you are typing) earns
         * a longer cooldown and a smaller flinch than one deliberate poke out of the blue.
         */
        fun noticeTap() {
            if (sleeping) {
                wakeUp()
                return
            }
            val now = SystemClock.uptimeMillis()
            while (recentTaps.isNotEmpty() && now - recentTaps.first() > 4_000) recentTaps.removeFirst()
            recentTaps.addLast(now)
            val burst = recentTaps.size

            // He looks towards whatever seems to be going on: a flurry of taps is almost always the
            // keyboard, so he watches the bottom of the screen; a lone tap just makes him glance
            // about.
            if (burst >= 3) {
                lookAtScreen(bounds.width() * 0.5f, bounds.height() * 0.86f)
            } else {
                lookAtScreen(
                    bounds.width() * (0.2f + Random.nextFloat() * 0.6f),
                    bounds.height() * (0.3f + Random.nextFloat() * 0.5f)
                )
            }
            nextGlanceAt = clock + 1.6f

            // Angry, he's a good deal less patient with commotion — shorter fuse, bigger flinch.
            val angry = mood == Mood.ANGRY
            val cooldown = when {
                burst >= 6 -> 2_600L   // busy screen: he settles down and mostly just watches
                burst >= 3 -> 1_100L
                else -> 320L
            } / (if (angry) 2 else 1)
            val view = ghost ?: return
            if (now - lastReactionAt < cooldown) {
                view.notice()
                return
            }
            lastReactionAt = now

            // Drift away from the commotion. Direction is random — with a lean towards open screen,
            // so he does not spend his life pinned against an edge.
            val angle = angleTowardsOpenSpace()
            val impulse = (if (burst >= 3) 90f else 190f + Random.nextFloat() * 120f) *
                density * (if (angry) 1.4f else 1f)
            velX += cos(angle) * impulse
            velY += sin(angle) * impulse
            driftAngle = angle
            view.notice()
            if (burst < 3) buzz()
        }

        /** A hand strokes his head for a couple of seconds; still held after that, it happens again. */
        private fun stroke() {
            noteInteraction(PetEvent.PETTED)
            val now = SystemClock.uptimeMillis()
            if (now - lastPetAt < PET_ANIMATION_MS) return
            lastPetAt = now
            val s = PetStats.snapshot(ctx, slot = slot)
            val happiness = (s.happiness + 3f).coerceAtMost(PetStats.MAX)
            Prefs.saveStats(ctx, s.hunger, s.energy, happiness, s.sleeping, System.currentTimeMillis(), slot)
            ghost?.startPetting()
        }

        /**
         * Does one of the quick actions to him. Everything here is the same call the app's own
         * Home tab makes — the difference is only that you did not have to go and find it.
         */
        fun runQuickAction(id: String) {
            when (id) {
                "feed" -> {
                    PetStats.feed(ctx, slot)
                    // The app watches this key to play the same drop-and-eat in the box, and so
                    // does [noteEvent] out here — which is why the animation is not started twice.
                    Prefs.markFed(ctx, slot)
                    ghost?.startEating()
                    ghost?.showExpression(Expression.DELIGHTED, 2f)
                }
                "play" -> when (Emotions.playWithToken(ctx, slot)) {
                    Emotions.PlayOutcome.SUCCESS -> playNow()
                    // No toast out here — there is no app to put one in front of. He answers
                    // instead, which is the only vocabulary the overlay has.
                    Emotions.PlayOutcome.TOO_TIRED -> ghost?.showExpression(Expression.SLEEPY, 2f)
                    Emotions.PlayOutcome.NO_TOKENS -> ghost?.showExpression(Expression.CONFUSED, 2f)
                }
                "pet" -> strokeOnce()
                "nap" -> toggleNap()
            }
            refreshMood()
        }

        /**
         * Called when "keep him in one place" is switched either way. Turning it on drops whatever
         * he was in the middle of — a routine mid-flight would otherwise carry on to its end and
         * only then stop, which reads as the setting not working.
         */
        fun settleForStayPut() {
            if (!stayPut) return
            routine = null
            velX = 0f
            velY = 0f
            ghost?.setMotion(0f, 0f)
            clampIntoBounds()
            applyPosition(force = true)
        }

        /** True while his quick actions are open — see [tick]. */
        private var menuHeld = false

        fun holdForMenu(held: Boolean) {
            menuHeld = held
        }

        /**
         * The visible half of something the app already did to him. Stats and tokens were settled
         * there; this is only what it looks like out here.
         */
        fun playReaction(reaction: String) {
            when (reaction) {
                "eat" -> {
                    ghost?.startEating()
                    ghost?.showExpression(Expression.DELIGHTED, 2f)
                }
                "play" -> playNow()
                "gift" -> {
                    ghost?.startGiftJoy()
                    ghost?.showExpression(Expression.DELIGHTED, 2.4f)
                }
            }
            refreshMood()
        }

        /** A small look-up as the buttons unfurl, so the menu reads as his and not the system's. */
        fun perkUp() {
            ghost?.notice()
        }

        /** One stroke, from the menu rather than from a finger held on him. */
        fun strokeOnce() {
            lastPetAt = 0L
            stroke()
            handler.removeCallbacks(petRunnable)
        }

        /** The zoomies a successful play earns him, wherever the play came from. */
        fun playNow() {
            noteInteraction(PetEvent.PLAYED)
            ghost?.startWiggle()
            ghost?.showExpression(Expression.DELIGHTED, 2.2f)
            // The wiggle is the play; the dash across the screen is optional, and someone who
            // asked for him to stay in one place did not ask for an exception when he is happy.
            if (!stayPut) launch(Random.nextFloat() * 2f * PI.toFloat())
        }

        /** Puts him down for a nap, or stirs him if he is already having one. */
        fun toggleNap() {
            if (sleeping) {
                wakeUp()
            } else {
                PetStats.setSleeping(ctx, true, slot)
                sleeping = true
                ghost?.setMood(mood, true)
            }
        }

        /** A poke while napping: stir awake with a small startle rather than a full bolt. */
        private fun wakeUp() {
            PetStats.wake(ctx, slot)
            sleeping = false
            ghost?.setMood(mood, false)
            ghost?.notice()
            ghost?.spookLightly()
        }

        // endregion

        // region motion

        /** Puts his body's top-left at a screen point, clamped to where he is allowed to be. */
        fun placeBodyAt(x: Float, y: Float) {
            posX = x
            posY = y - headroomPx
            velX = 0f
            velY = 0f
            clampIntoBounds()
            applyPosition()
        }

        /**
         * The flight home. He eases towards the point the app named and stops there, and nothing
         * else — drift, flourishes, the behaviour engine — gets a say until he lands.
         */
        fun startHoming(x: Float, y: Float) {
            homingToX = x
            homingToY = y - headroomPx
            homing = true
            homingUntil = clock + HOMING_TIMEOUT_SECONDS
        }

        /** Dash off in a random direction, generally away from [fromX], [fromY]. */
        private fun fleeFrom(fromX: Float, fromY: Float) {
            noteInteraction(PetEvent.SPOOKED)
            val cx = posX + windowPx / 2f
            val cy = posY + headroomPx + windowPx / 2f
            var dx = cx - fromX
            var dy = cy - fromY
            val len = hypot(dx, dy)
            val baseAngle = if (len < 1f) {
                Random.nextFloat() * 2f * PI.toFloat()
            } else {
                dx /= len
                dy /= len
                atan2(dy, dx)
            }
            // Away from the finger, but with a wide random spread so it never feels scripted.
            launch(baseAngle + (Random.nextFloat() - 0.5f) * 1.9f)
        }

        private fun launch(angle: Float) {
            // Ghostly, not startled-cat: he glides away rather than snapping across the screen.
            // Angry, that glide gets a noticeably harder edge.
            val angryBoost = if (mood == Mood.ANGRY) 1.5f else 1f
            val speed = (320f + Random.nextFloat() * 260f) * density * angryBoost
            velX = cos(angle) * speed
            velY = sin(angle) * speed
            driftAngle = angle
            ghost?.spook()
            ghost?.setMotion(velX, velY)
            buzz()
        }

        /** Point him at a spot and give him just enough push to drift there. */
        private fun aimAt(targetX: Float, targetY: Float, speed: Float) {
            val cx = posX + windowPx / 2f
            val cy = posY + headroomPx + windowPx / 2f
            val angle = atan2(targetY - cy, targetX - cx)
            driftAngle = angle
            velX = cos(angle) * speed
            velY = sin(angle) * speed
            ghost?.setMotion(velX, velY)
        }

        private fun nearestCornerX(): Float =
            if (posX + windowPx / 2f < usable.centerX()) {
                usable.left + windowPx / 2f
            } else {
                usable.right - windowPx / 2f
            }

        // "Corner" now means the side of the screen at a comfortable height, not the actual corner:
        // the real ones are in the band he stays out of.
        private fun nearestCornerY(): Float =
            (if (posY < usable.centerY()) usable.top + windowPx else usable.bottom - windowPx)
                .toFloat()
                .coerceIn(minY(), maxY())

        /** A heading that generally points back into the middle of the screen, plus a wide spread. */
        private fun angleTowardsOpenSpace(): Float {
            val cx = posX + windowPx / 2f
            val cy = posY + headroomPx + windowPx / 2f
            val toCentre = atan2(bounds.height() / 2f - cy, bounds.width() / 2f - cx)
            return toCentre + (Random.nextFloat() - 0.5f) * 3.0f
        }

        /** Point his eyes at a spot on the screen. */
        private fun lookAtScreen(x: Float, y: Float) {
            gazeScreenX = x
            gazeScreenY = y
        }

        private fun updateGaze(dt: Float) {
            val view = ghost ?: return
            val speed = hypot(velX, velY)

            // Gliding fast? He watches where he is going. Otherwise he looks around the room.
            if (speed > 90f * density) {
                view.lookAt(velX / speed, velY / speed)
                nextGlanceAt = clock + 0.8f
                return
            }

            if (clock > nextGlanceAt) {
                lookAtScreen(
                    bounds.width() * (0.08f + Random.nextFloat() * 0.84f),
                    bounds.height() * (0.08f + Random.nextFloat() * 0.84f)
                )
                nextGlanceAt = clock + 1.4f + Random.nextFloat() * 2.6f
            }

            val cx = posX + windowPx / 2f
            val cy = posY + headroomPx + windowPx / 2f
            val dx = gazeScreenX - cx
            val dy = gazeScreenY - cy
            val len = hypot(dx, dy)
            if (len < 1f) return
            // Normalised direction; anything more than a screen-quarter away is a full-strength look.
            val reach = (len / (bounds.width() * 0.25f)).coerceAtMost(1f)
            view.lookAt(dx / len * reach, dy / len * reach)
        }

        private fun tick(dt: Float) {
            val view = ghost ?: return
            if (dragging) return
            // Kept where he was put. Everything that is not motion carries on — he breathes,
            // blinks, reacts, speaks and sleeps; the behaviour engine still proposes things and
            // [perform] still plays their faces and voices. Only the part that would carry him
            // across the screen is skipped, which is also the part that costs a window relayout,
            // so a pet standing still is close to free.
            if (stayPut && !homing) {
                // Anything that did set one — a flourish, an edge recovery — is dropped rather
                // than left to hang, for the frame-budget reason spelled out in [perform].
                routine = null
                velX = 0f
                velY = 0f
                view.setMotion(0f, 0f)
                if (clock > nextStatsTickAt) {
                    nextStatsTickAt = clock + 10f
                    refreshMood()
                }
                return
            }
            // His quick actions are open beside him, anchored to where he is. Letting him drift on
            // would either drag the menu around under the finger reaching for it or leave it
            // pointing at empty screen. He waits; he keeps breathing, because the view advances
            // whatever this does.
            if (menuHeld) {
                velX = 0f
                velY = 0f
                view.setMotion(0f, 0f)
                return
            }

            if (clock > nextStatsTickAt) {
                nextStatsTickAt = clock + 10f
                refreshMood()
            }

            if (homing) {
                tickHoming(dt)
                return
            }

            if (routine != null) {
                tickRoutine(dt)
                return
            }

            if (sleeping) {
                // Settle to a stop and stay put rather than drifting off mid-nap.
                val settle = 1f - exp(-2.5f * dt)
                velX -= velX * settle
                velY -= velY * settle
                posX += velX * dt
                posY += velY * dt
                clampIntoBounds()
                view.setMotion(velX, velY)
                applyPosition()
                return
            }

            updateGaze(dt)

            if (clock > nextFlourishAt) {
                nextFlourishAt = clock + 25f + Random.nextFloat() * 25f
                runFlourish()
            }

            if (clock < goofyUntil) {
                // Puffed up, floating and holding in a corner rather than the usual drift.
                val settle = 1f - exp(-1.6f * dt)
                velX += ((goofyCornerX - posX) * 1.4f - velX) * settle
                velY += ((goofyCornerY - posY) * 1.4f - velY) * settle
                posX += velX * dt
                posY += velY * dt
                clampIntoBounds()
                view.setMotion(velX, velY)
                applyPosition()
                return
            }
            view.setPuffTarget(if (energyFull) 0.1f else 0f)
            if (clock > nextGoofyCheckAt) {
                nextGoofyCheckAt = clock + 45f + Random.nextFloat() * 40f
                triggerGoofy()
            }

            // He is never quite still: the heading wanders, and the speed always settles back to a
            // slow float rather than to zero. Angry, that float turns into a restless, erratic pace
            // — he's not going anywhere, but he's clearly not settled either. Brimming with energy,
            // the float turns quicker and more purposeful instead.
            val angryJitter = if (mood == Mood.ANGRY) 2.6f else 1f
            val angrySpeed = if (mood == Mood.ANGRY) 1.6f else if (energyFull) 1.3f else 1f
            driftAngle += (sin(clock * 0.31f) + sin(clock * 0.17f + 1.3f)) * 0.4f * angryJitter * dt
            val targetX = cos(driftAngle) * driftSpeed * angrySpeed
            val targetY = sin(driftAngle) * driftSpeed * angrySpeed
            // A pull back up out of the bottom of the screen, cubed so it is nothing in the middle
            // and firm by the time he is down near the gesture bar. Downward only: the top of the
            // screen is his to use, and pulling him off it was why he never seemed to reach the bar
            // up there.
            val bandMid = (minY() + maxY()) / 2f
            val bandHalf = ((maxY() - minY()) / 2f).coerceAtLeast(1f)
            val strayed = ((posY - bandMid) / bandHalf).coerceIn(0f, 1f)
            val recentre = -(strayed * strayed * strayed) * driftSpeed * 0.9f

            val settle = 1f - exp(-0.85f * dt)
            velX += (targetX - velX) * settle
            velY += (targetY + recentre - velY) * settle

            posX += velX * dt
            posY += velY * dt

            // Bounce off the screen edges, losing a bit of energy each time.
            if (posX < minX()) {
                posX = minX(); bounceHorizontally()
            } else if (posX > maxX()) {
                posX = maxX(); bounceHorizontally()
            }
            if (posY < minY()) {
                posY = minY(); bounceVertically()
            } else if (posY > maxY()) {
                posY = maxY(); bounceVertically()
            }

            view.setMotion(velX, velY)
            applyPosition()
        }

        /** A hungry buzz + species call, or — when he's doing well — a happy little vocalisation. */
        private fun runFlourish() {
            val view = ghost ?: return
            val s = Emotions.snapshot(ctx, slot)
            val species = pet.species
            when {
                s.body.hunger <= PetStats.HUNGRY_THRESHOLD -> {
                    buzz()
                    view.showBubble(species.callHungry)
                }
                mood == Mood.CONTENT && s.body.happiness >= 70f -> {
                    view.showBubble(species.callHappy)
                    // Half the time he shimmies about it as well, so a good mood doesn't always
                    // look like exactly the same two seconds.
                    if (Random.nextBoolean()) view.startWiggle()
                }
            }
        }

        /** Puffs him up and picks a screen corner to float over to and hold in for a few seconds. */
        private fun triggerGoofy() {
            if (sleeping || mood != Mood.CONTENT) return
            goofyUntil = clock + 4.5f
            goofyCornerX = if (Random.nextBoolean()) minX() else maxX()
            goofyCornerY = if (Random.nextBoolean()) minY() else maxY()
            ghost?.setPuffTarget(0.32f)
        }

        /**
         * Nothing should hold him against the side of the screen for long — not a set piece that
         * ran out of room, not a launch that spent itself into a corner, not a target that happened
         * to sit on the edge. If he is still there after a second and a bit, he peels off and does
         * something else.
         */
        private fun watchForPinning() {
            if (dragging || homing || sleeping) {
                pinnedSince = 0f
                return
            }
            if (!atEdge()) {
                pinnedSince = 0f
                return
            }
            if (pinnedSince == 0f) {
                pinnedSince = clock
                return
            }
            if (clock - pinnedSince < PINNED_SECONDS) return
            pinnedSince = 0f
            val view = ghost ?: return
            // Away from whichever wall he is on, then on with something new.
            val awayX = when {
                posX <= minX() + 1f -> 1f
                posX >= maxX() - 1f -> -1f
                else -> 0f
            }
            val awayY = when {
                posY <= minY() + 1f -> 1f
                posY >= maxY() - 1f -> -1f
                else -> 0f
            }
            driftAngle = atan2(
                if (awayY == 0f) (Random.nextFloat() - 0.5f) else awayY,
                if (awayX == 0f) (Random.nextFloat() - 0.5f) else awayX
            )
            velX = cos(driftAngle) * driftSpeed * 1.4f
            velY = sin(driftAngle) * driftSpeed * 1.4f
            routine = null
            if (Random.nextFloat() < 0.4f) {
                startRoutine(EDGE_RECOVERY_MOVES.random(), 0.6f, driftSpeed * 1.5f, view)
            }
        }

        /**
         * Starts one of the set-piece movements. Each is given a few seconds, an anchor at wherever
         * he is standing, and then runs itself in [tickRoutine] until its time is up.
         */
        private fun startRoutine(kind: Locomotion, intensity: Float, speed: Float, view: GhostView) {
            routine = kind
            routineAnchorX = posX
            routineAnchorY = posY
            routineDir = if (Random.nextBoolean()) 1f else -1f
            routineSpeed = speed
            velX = 0f
            velY = 0f
            when (kind) {
                Locomotion.ROLLOVER -> {
                    val seconds = 1.5f + intensity * 1.2f
                    routineUntil = clock + seconds
                    // Tips over as he drifts: the sideways glide is what stops it reading as a spin.
                    velX = routineDir * speed * 1.4f
                    velY = -speed * 0.25f
                    view.startRoll(seconds, if (intensity > 0.8f) 2f else 1f)
                }
                Locomotion.BOUNCE -> {
                    routineUntil = clock + 3.4f
                    velX = routineDir * speed * 0.9f
                    velY = -speed * 3.2f
                }
                Locomotion.ORBIT -> {
                    routineUntil = clock + 3.2f + intensity * 2f
                    // Anchored on the middle of the loop, not on him, so he circles something.
                    val radius = minOf(usable.width(), usable.height()) * 0.16f
                    routineAngle = Random.nextFloat() * 2f * PI.toFloat()
                    routineAnchorX = posX - cos(routineAngle) * radius
                    routineAnchorY = posY - sin(routineAngle) * radius
                    routineSpeed = radius
                }
                Locomotion.PACE -> {
                    routineUntil = clock + 4f
                    routineSpeed = minOf(usable.width() * 0.22f, ghostPx * 3.2f)
                    velX = routineDir * speed * 1.6f
                }
                Locomotion.EDGE_SLIDE -> {
                    routineUntil = clock + 3.6f
                    routineAnchorX = if (posX + windowPx / 2f < usable.centerX()) minX() else maxX()
                    velY = routineDir * speed * 1.5f
                }
                Locomotion.PEEK -> {
                    routineUntil = clock + 3.8f
                    // Off the near edge by most of himself, so only a sliver of him is left showing.
                    val leaving = posX + windowPx / 2f < usable.centerX()
                    routineAnchorX =
                        if (leaving) minX() - ghostPx * 0.62f else maxX() + ghostPx * 0.62f
                    routineDir = if (leaving) -1f else 1f
                }
                else -> routine = null
            }
        }

        /**
         * Runs whichever set piece is active. Each one steers him directly rather than nudging his
         * drift, and when its time is up he is handed back to the ordinary wander.
         */
        private fun tickRoutine(dt: Float) {
            val view = ghost ?: return
            val kind = routine ?: return
            if (clock > routineUntil) {
                routine = null
                driftAngle = atan2(velY, velX)
                return
            }
            val settle = 1f - exp(-4f * dt)
            when (kind) {
                Locomotion.BOUNCE -> {
                    velY += 1_500f * density * dt
                    posX += velX * dt
                    posY += velY * dt
                    if (posY >= maxY()) {
                        posY = maxY()
                        // Each landing takes a bite out of the bounce, so it dies down rather than
                        // going forever, and he squashes on impact.
                        velY = -velY * 0.62f
                        if (abs(velY) < 60f * density) {
                            velY = 0f
                            routineUntil = clock
                        } else {
                            view.squash(minOf(1.2f, abs(velY) / (600f * density)))
                            buzz()
                        }
                    }
                }
                Locomotion.ORBIT -> {
                    routineAngle += dt * (1.6f + routineSpeed / (240f * density))
                    val nx = routineAnchorX + cos(routineAngle) * routineSpeed
                    val ny = routineAnchorY + sin(routineAngle) * routineSpeed
                    velX = (nx - posX) / dt.coerceAtLeast(0.001f)
                    velY = (ny - posY) / dt.coerceAtLeast(0.001f)
                    posX = nx
                    posY = ny
                }
                Locomotion.PACE -> {
                    posX += velX * dt
                    if (abs(posX - routineAnchorX) > routineSpeed) {
                        velX = -velX
                        posX = routineAnchorX + routineSpeed * (if (posX > routineAnchorX) 1f else -1f)
                        view.lookAt(if (velX > 0f) 1f else -1f, 0f)
                    }
                }
                Locomotion.EDGE_SLIDE -> {
                    velX += ((routineAnchorX - posX) * 3.4f - velX) * settle
                    posX += velX * dt
                    posY += velY * dt
                    if (posY <= minY() || posY >= maxY()) velY = -velY
                }
                Locomotion.PEEK -> {
                    // Out for the first half, leaning back in for the second.
                    val goingOut = clock < routineUntil - 1.6f
                    val targetX =
                        if (goingOut) routineAnchorX else routineAnchorX - routineDir * ghostPx * 1.4f
                    velX += ((targetX - posX) * 3.2f - velX) * settle
                    posX += velX * dt
                    view.lookAt(-routineDir, 0f)
                }
                Locomotion.ROLLOVER -> {
                    posX += velX * dt
                    posY += velY * dt
                    velY += 40f * density * dt
                }
                else -> Unit
            }
            // Peek and the bounce's floor are *meant* to press against an edge; everything else that
            // reaches one has run out of room, and grinding along the wall until its few seconds are
            // up is the one thing that makes him look like a bug rather than a pet.
            val pressing = kind != Locomotion.PEEK
            val hitX = pressing && (posX < minX() || posX > maxX())
            val hitY = pressing && kind != Locomotion.BOUNCE && (posY < minY() || posY > maxY())
            clampIntoBounds()
            if (hitX || hitY) {
                if (hitX) velX = -abs(velX) * sign(if (posX <= minX()) 1f else -1f)
                if (hitY) velY = -abs(velY) * sign(if (posY <= minY()) 1f else -1f)
                hitEdgeMidMovement(view)
                return
            }
            view.setMotion(velX, velY)
            applyPosition()
        }

        /**
         * He has run into the side of the screen partway through doing something. Rather than
         * pressing on into it for the rest of the routine, he turns away — and half the time
         * changes his mind about what he was doing altogether and starts something else.
         */
        private fun hitEdgeMidMovement(view: GhostView) {
            routine = null
            driftAngle = atan2(velY, velX)
            ghost?.spookLightly()
            if (Random.nextFloat() < 0.5f) {
                val next = EDGE_RECOVERY_MOVES.random()
                startRoutine(next, 0.6f, driftSpeed * (1.2f + Random.nextFloat()), view)
            }
            view.setMotion(velX, velY)
            applyPosition()
        }

        private fun tickHoming(dt: Float) {
            val view = ghost ?: return
            val dx = homingToX - posX
            val dy = homingToY - posY
            val dist = hypot(dx, dy)
            if (dist < 4f || clock > homingUntil) {
                posX = homingToX
                posY = homingToY
                velX = 0f
                velY = 0f
                view.setMotion(0f, 0f)
                applyPosition(force = true)
                homing = false
                // [arrivedHome] is what the app waits on before it takes him over, and there is one
                // box — only the pet it is waiting for may report having landed in it.
                if (pet.isPrimary) arrivedHome = true
                return
            }
            // Fast, but eased, so he arrives settling rather than slamming into place.
            val speed = (dist * 4.5f).coerceIn(driftSpeed * 6f, driftSpeed * 34f)
            val settle = 1f - exp(-9f * dt)
            velX += (dx / dist * speed - velX) * settle
            velY += (dy / dist * speed - velY) * settle
            posX += velX * dt
            posY += velY * dt
            view.setMotion(velX, velY)
            view.lookAt(dx / dist, dy / dist)
            applyPosition()
        }

        private fun bounceHorizontally() {
            velX = -velX * 0.5f
            driftAngle = PI.toFloat() - driftAngle
            ghost?.spookLightly()
        }

        private fun bounceVertically() {
            velY = -velY * 0.5f
            driftAngle = -driftAngle
            ghost?.spookLightly()
        }

        // endregion

        // region bounds

        // The ghost — not the window's transparent halo — is what has to stay on screen.
        // A sliver of overhang still looks good — he nuzzles the edge — but never enough to hide him
        // behind a system bar.
        private fun overhang() = ghostPx * 0.05f

        /**
         * The strip of screen he is allowed in. At the top he may go right up to the system bar —
         * there is nothing up there he gets in the way of. At the bottom he stops [BAND_MARGIN]
         * short, which keeps him off the gesture bar and off whatever an app puts along its own
         * bottom edge. Enforced through [minY]/[maxY], so drift, perching, bouncing and every set
         * piece inherit it rather than each having to remember.
         */
        // The very top of the display, not the top of the *usable* area: the usable rect starts
        // below the status bar, which left him stopping a bar's height short of where he should be
        // able to go.
        private fun bandTop(): Float = bounds.top.toFloat()

        private fun bandBottom(): Float =
            minOf(usable.bottom.toFloat(), bounds.height() * (1f - BAND_MARGIN))

        private fun minX() = usable.left - haloPx - overhang()
        private fun maxX() = usable.right - windowPx + haloPx + overhang()
        // His body starts headroomPx below the top of the window, so the vertical bounds shift by
        // it: he may sit at the very top of the screen with the bubble space hanging off-screen
        // above.
        private fun minY() = bandTop() - haloPx - headroomPx
        // The window is windowPx + headroomPx tall and his body sits in the BOTTOM of it, so the
        // floor has to come up by the headroom as well — without this he sinks below the navigation
        // bar by exactly the height of his own speech bubble.
        private fun maxY() = bandBottom() - headroomPx - windowPx + haloPx

        /** Against any wall right now, near enough. */
        private fun atEdge(): Boolean =
            posX <= minX() + 1f || posX >= maxX() - 1f || posY <= minY() + 1f || posY >= maxY() - 1f

        fun clampIntoBounds() {
            posX = posX.coerceIn(minX(), maxX())
            posY = posY.coerceIn(minY(), maxY())
            keepOutOfCorners()
        }

        /**
         * Never in the very corner of the screen. Phone screens are rounded there, so a ghost in a
         * corner is a ghost with a bite taken out of him — and the top corners are where the clock
         * and the status icons live. He is slid along whichever edge he is on until he is clear.
         */
        private fun keepOutOfCorners() {
            val pad = CORNER_KEEPOUT_DP * density
            val nearSide = posX <= minX() + pad || posX >= maxX() - pad
            if (!nearSide) return
            if (posY <= minY() + pad) posY = minY() + pad
            else if (posY >= maxY() - pad) posY = maxY() - pad
        }

        /**
         * [force] skips the movement threshold. Used where the exact pixel matters rather than the
         * saved relayout: landing at the end of a flight home, where the box is about to fade in on
         * the precise point he was sent to and a leftover dp of slack shows up as a jump.
         */
        private fun applyPosition(force: Boolean = false) {
            val view = root ?: return
            val nx = posX.toInt()
            val ny = posY.toInt()
            // Moving the window is a system relayout and recomposite — by far the most expensive
            // thing done per frame. A single pixel of drift is not worth one, and at this speed the
            // old pixel-exact test let almost every frame through.
            if (!force &&
                abs(nx - lastAppliedX) < moveThresholdPx &&
                abs(ny - lastAppliedY) < moveThresholdPx
            ) {
                return
            }
            lastAppliedX = nx
            lastAppliedY = ny
            params.x = nx - sidePx
            params.y = ny
            // The app reads these to pick him up where he is, and it only ever picks up one of them.
            if (pet.isPrimary) {
                bodyX = posX
                bodyY = posY + headroomPx
            }
            runCatching { windowManager.updateViewLayout(view, params) }
            // The patch rides with him. It is a second relayout on the same frame, which is the
            // price of the drawn window never being touchable — and it is only paid in solid mode,
            // by the pets that have one.
            patchParams?.let { lp ->
                positionPatch(lp)
                touchPatch?.let { patch -> runCatching { windowManager.updateViewLayout(patch, lp) } }
            }
        }

        // endregion
    }

    // endregion

    // region lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isVisiting = false
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_VISIT) {
            val visiting = intent.getBooleanExtra(EXTRA_VISITING, false)
            // Coming back out, he reappears exactly where the box was drawing him.
            if (!visiting) {
                intent.bodyPoint()?.let { (x, y) -> primary()?.placeBodyAt(x, y) }
                intent.adoptIdleClock()
            }
            setVisiting(visiting)
            return START_STICKY
        }

        if (intent?.action == ACTION_REACT) {
            val reaction = intent.getStringExtra(EXTRA_REACTION)
            val slot = intent.getIntExtra(EXTRA_SLOT, PetStore.PRIMARY_SLOT)
            if (reaction != null) pets.firstOrNull { it.slot == slot }?.playReaction(reaction)
            return START_STICKY
        }

        if (intent?.action == ACTION_COME_HOME) {
            intent.bodyPoint()?.let { (x, y) ->
                // The box holds one ghost, so it is the primary who flies to it.
                primary()?.let { p ->
                    p.startHoming(x, y)
                    arrivedHome = false
                    startLoop()
                }
            }
            return START_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        val spawn = intent?.bodyPoint()
        if (pets.isEmpty()) {
            attachOverlay(spawn)
        } else {
            spawn?.let { (x, y) -> primary()?.placeBodyAt(x, y) }
            startLoop()
        }
        intent?.adoptIdleClock()
        Prefs.setEnabled(this, true)
        // If the system ever kills us off — Samsung's battery manager is fond of it — this brings
        // him back without the user having to open the app.
        Watchdog.schedule(this)
        Recall.clearNotification(this)
        return START_STICKY
    }

    override fun onDestroy() {
        // Torn down rather than dismissed: the animation would be running against a view whose
        // service is already going, and a window left behind by a dead service is not recoverable.
        menuView?.let { view ->
            menuView = null
            menuParams = null
            menuOwner = null
            runCatching { windowManager.removeView(view) }
        }
        handler.removeCallbacks(menuTimeout)
        isVisiting = false
        isRunning = false
        stopLoop()
        handler.removeCallbacksAndMessages(null)
        // Remembers where each of them was, so they reappear in the same spots next time.
        for (p in pets) p.detach()
        pets.clear()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        prefsListener?.let { runCatching { Prefs.raw(this).unregisterOnSharedPreferenceChangeListener(it) } }
        prefsListener = null
        Watchdog.cancel(this)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshBounds()
        for (p in pets) p.clampIntoBounds()
    }

    // endregion

    // region setup

    /**
     * Puts the roster on screen and wires up everything shared: the screen receiver, the preference
     * listener, the frame loop and the two timers.
     *
     * Exactly one pet goes up — the primary, on his flat preference keys. He is assembled through
     * [PetStore] rather than read straight out of [Prefs] so that adding the rest is a change to
     * this one line.
     */
    private fun attachOverlay(spawn: Pair<Float, Float>? = null) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        clickThrough = Prefs.clickThrough(this)
        stayPut = Prefs.stayPut(this)
        moveThresholdPx = maxOf(1, density.toInt())
        refreshBounds()

        pets.clear()
        pets += FloatingPet(PetStore.primary(this))
        // Only the pet the app handed over starts on the spot the app names; the rest go back to
        // wherever they were left.
        for (p in pets) p.attach(if (p.pet.isPrimary) spawn else null)

        isRunning = true
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        )
        receiverRegistered = true
        startLoop()

        // Feeding, playing or napping from the app writes straight to Prefs; catch it here too, so
        // he doesn't wait up to ten seconds to visibly react. A size, shade or species change from
        // the Style tab lands here too, applied in place rather than needing a restart.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            key ?: return@OnSharedPreferenceChangeListener
            // Click-through is the account's setting, not a pet's: no slot, and it rebuilds every
            // window there is.
            if (key == CLICK_THROUGH_KEY) {
                if (Prefs.clickThrough(this) != clickThrough) recreateWindow()
                return@OnSharedPreferenceChangeListener
            }
            if (key == STAY_PUT_KEY) {
                // No rebuild: nothing about the window changes, only whether he is moved in it.
                // Waking the loop is what makes it visible at once — he may be parked at eight
                // frames a second, or stopped, when this is turned back off.
                stayPut = Prefs.stayPut(this)
                for (p in pets) p.settleForStayPut()
                startLoop()
                return@OnSharedPreferenceChangeListener
            }
            // Everything else per-pet arrives under a key with the slot on the end for every pet
            // but the first — `hunger#3` — so matching the stored key against a flat name would
            // quietly stop noticing anyone but the primary. Take it apart, then hand the change to
            // the pet it is actually about.
            val base = Prefs.baseOf(key)
            // Any write at all used to trigger both of these — including the stats this very
            // service writes on its own ten-second tick, and the sync layer's bookkeeping.
            if (base !in MOOD_KEYS && base !in LOOK_KEYS) return@OnSharedPreferenceChangeListener
            val target = pets.firstOrNull { it.slot == Prefs.slotOf(key) }
                ?: return@OnSharedPreferenceChangeListener
            target.noteEvent(base)
            if (base in MOOD_KEYS) target.refreshMood()
            if (base in LOOK_KEYS) target.syncAppearance()
        }
        prefsListener = listener
        Prefs.raw(this).registerOnSharedPreferenceChangeListener(listener)
        handler.postDelayed(behaviourRunnable, BEHAVIOUR_INTERVAL_MS)
        handler.postDelayed(syncRunnable, FIRST_SYNC_DELAY_MS)
    }

    /**
     * The click-through setting decides `FLAG_NOT_TOUCHABLE` and `FLAG_WATCH_OUTSIDE_TOUCH`, both
     * read only when a window is added — there is no `updateViewLayout` for flags the way there
     * is for size. So a change tears every window down and puts fresh ones up, each at the same
     * spot, rather than trying to mutate them live.
     *
     * If they are visiting the box when the setting changes, the rebuilt windows are put back into
     * that same hidden, stopped state instead of fading in over the app — leaving them alone here
     * would mean the old flags silently outlive the toggle until the next full service restart,
     * since coming back out of a visit reuses the existing windows rather than rebuilding them.
     */
    private fun recreateWindow() {
        if (pets.isEmpty()) return
        val wasVisiting = isVisiting

        stopLoop()
        handler.removeCallbacks(behaviourRunnable)
        handler.removeCallbacks(syncRunnable)
        // Same hand-off the service already uses across a process restart: each of them leaves a
        // position behind for his own attach() to pick up, rather than threading it through as a
        // spawn point.
        for (p in pets) p.detach()

        clickThrough = Prefs.clickThrough(this)
        stayPut = Prefs.stayPut(this)
        for (p in pets) p.attach(spawn = null)

        startLoop()
        handler.postDelayed(behaviourRunnable, BEHAVIOUR_INTERVAL_MS)
        handler.postDelayed(syncRunnable, FIRST_SYNC_DELAY_MS)

        if (wasVisiting) {
            // attach() always fades a fresh window in and startLoop() sets it running — undo both
            // so the rebuilt windows land back in the same hidden, stopped state setVisiting(true)
            // left them in.
            for (p in pets) p.hideForVisit()
            stopLoop()
        }
    }

    private fun refreshBounds() {
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            bounds.set(metrics.bounds)
            // Ignoring visibility keeps the play area stable: the bars hiding for a full-screen
            // video shouldn't let him wander somewhere he'll be clipped a moment later.
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
            usable.set(
                bounds.left + bars.left,
                bounds.top + bars.top,
                bounds.right - bars.right,
                bounds.bottom - bars.bottom
            )
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            bounds.set(0, 0, size.x, size.y)
            usable.set(bounds)
        }
    }

    // endregion

    // region quick actions

    /**
     * The row of buttons a long press puts beside him — feed, play, pet, nap — so the things you
     * do to him no longer require going and finding the app first.
     *
     * There is one of these for the whole service, not one per pet. Two menus open at once would be
     * two windows fighting over the same finger, and the menu names the pet it belongs to anyway.
     *
     * It only exists in solid mode, because it is opened by a long press and a long press needs a
     * touch with coordinates — see the class doc on the two modes. That is [FloatingPet.touchPatch]'s
     * doing, and the reason it is small enough to be worth having.
     */
    private var menuView: QuickActionsView? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuOwner: FloatingPet? = null

    /** Set the moment a close begins, so a second outside tap mid-animation is ignored. */
    private var menuClosing = false

    private val menuTimeout = Runnable { closeQuickActions() }

    private fun openQuickActions(owner: FloatingPet) {
        if (menuView != null) return
        val view = QuickActionsView(this)
        // Sizing reads the actions, so they have to be in before the window is measured.
        view.setActions(actionsFor(owner))
        val w = view.desiredWidth()
        val h = view.desiredHeight()
        if (w <= 0 || h <= 0) return

        val lp = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable, unlike everything else this service puts up: it has buttons. Not
            // focusable, so it never takes the keyboard off whatever is underneath.
            // FLAG_WATCH_OUTSIDE_TOUCH is what lets a tap anywhere else close it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        placeMenu(lp, owner, w, h)

        view.onPick = { action ->
            owner.runQuickAction(action.id)
            closeQuickActions()
        }
        view.onDismiss = { closeQuickActions() }

        menuView = view
        menuParams = lp
        menuOwner = owner
        menuClosing = false
        val added = runCatching { windowManager.addView(view, lp) }.isSuccess
        if (!added) {
            menuView = null
            menuParams = null
            menuOwner = null
            return
        }
        view.reveal()
        owner.perkUp()
        owner.holdForMenu(true)
        // It is sitting on top of somebody else's app. If it is not being used it should go.
        handler.postDelayed(menuTimeout, MENU_IDLE_MS)
    }

    /**
     * Beside him, on whichever side has room, with the buttons level with his middle.
     *
     * The buttons occupy the top of the window rather than its centre — the labels hang below them
     * — so lining the row up with him means offsetting by half a button plus the padding above it,
     * not by half the window.
     */
    private fun placeMenu(lp: WindowManager.LayoutParams, owner: FloatingPet, w: Int, h: Int) {
        val gap = MENU_GAP_DP * density
        val leftOf = owner.bodyCentreX() - owner.bodyHalfPx() - gap - w
        val rightOf = owner.bodyCentreX() + owner.bodyHalfPx() + gap
        // His own side of the screen first: a menu that opens away from the edge he is clinging to
        // is the one that fits.
        lp.x = if (rightOf + w <= usable.right) {
            rightOf.toInt()
        } else if (leftOf >= usable.left) {
            leftOf.toInt()
        } else {
            // Nowhere beside him: centre it on him and let the clamp below sort it out.
            (owner.bodyCentreX() - w / 2f).toInt()
        }
        val buttonCentreFromTop = (QuickActionsView.PAD_DP + QuickActionsView.BUTTON_DP / 2f) * density
        lp.y = (owner.bodyCentreY() - buttonCentreFromTop).toInt()
        lp.x = lp.x.coerceIn(usable.left, (usable.right - w).coerceAtLeast(usable.left))
        lp.y = lp.y.coerceIn(usable.top, (usable.bottom - h).coerceAtLeast(usable.top))
    }

    /** What he can be offered right now. A greyed button says more than a missing one. */
    private fun actionsFor(p: FloatingPet): List<QuickAction> {
        val s = Emotions.snapshot(this, p.slot)
        val asleep = s.body.sleeping
        return listOf(
            QuickAction("feed", IconGlyph.HUNGER, getString(R.string.quick_feed)),
            QuickAction(
                "play",
                IconGlyph.PLAY,
                getString(R.string.quick_play),
                enabled = !asleep && s.tokens >= Emotions.PLAY_COST,
            ),
            QuickAction("pet", IconGlyph.HAPPINESS, getString(R.string.quick_pet), enabled = !asleep),
            QuickAction(
                "nap",
                IconGlyph.NAP,
                getString(if (asleep) R.string.quick_wake else R.string.quick_nap),
            ),
        )
    }

    fun closeQuickActions() {
        val view = menuView ?: return
        if (menuClosing) return
        menuClosing = true
        handler.removeCallbacks(menuTimeout)
        menuOwner?.holdForMenu(false)
        view.dismiss {
            runCatching { windowManager.removeView(view) }
            if (menuView === view) {
                menuView = null
                menuParams = null
                menuOwner = null
            }
            menuClosing = false
        }
    }

    // endregion

    // region shared odds and ends

    /**
     * The faces that follow from the numbers: worn out enough to yawn, run right down to a swoon.
     * Called from the same place the mood is refreshed, so it never fights it.
     */
    private fun expressionFor(snapshot: PetStats.Snapshot): Expression = when {
        snapshot.sleeping -> Expression.NONE
        snapshot.energy < 8f -> Expression.FAINT
        snapshot.energy < 22f -> Expression.SLEEPY
        snapshot.happiness > 92f && snapshot.hunger > 70f -> Expression.DELIGHTED
        else -> Expression.NONE
    }

    private fun buzz() {
        if (!Prefs.hapticsEnabled(this)) return
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(18, 60))
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GhostOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        // One notification, and it speaks for the primary — he is the one the box hands back and
        // forth and the one every install has. On a cold start it is built before any window is up,
        // which is the null case: a name from prefs and a content mood, corrected by the first
        // refreshMood() a moment later.
        val lead = primary()
        val name = lead?.pet?.displayName ?: Prefs.displayName(this)
        val asleep = lead?.sleeping ?: false
        val feeling = lead?.mood ?: Mood.CONTENT

        // Both of these used to talk about tapping him. Taps go straight through him now — the
        // app's box is the only place he can be handled — so the notification says so.
        val (title, text) = when {
            asleep -> "$name is napping" to "Open Ghostly to wake him, or Stop to send him away."
            feeling == Mood.ANGRY ->
                "$name is upset with you" to "He's been neglected too long — a gift would help."
            feeling == Mood.SAD ->
                "$name is floating" to "He's a little down today. Open Ghostly and say hello."
            else -> "$name is floating" to "Drifting over your apps. Stop to send him home."
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ghost)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    // endregion
}
