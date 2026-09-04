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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
         * Where his body's top-left should be, in screen pixels. Carried on a start, on the end of
         * a visit and on [ACTION_COME_HOME], so that he appears exactly where the app last drew
         * him instead of popping into being somewhere else.
         */
        const val EXTRA_BODY_X = "bodyX"
        const val EXTRA_BODY_Y = "bodyY"
        private const val CHANNEL_ID = "ghost_overlay"
        private const val NOTIFICATION_ID = 7
        private const val WATCHDOG_INTERVAL_MS = 2_000L
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

        /** Longer than any flight across a screen; a cap, not a schedule. */
        private const val HOMING_TIMEOUT_SECONDS = 4f

        private const val PET_HOLD_MS = 1_000L
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

        /**
         * Returns false when Android refused the start. From the foreground this always works; from
         * a broadcast it can be refused — `MY_PACKAGE_REPLACED` is not one of the exemptions for
         * starting a foreground service, and an unhandled refusal crashes the process.
         */
        fun start(context: Context, bodyX: Float? = null, bodyY: Float? = null): Boolean = runCatching {
            val intent = Intent(context, GhostOverlayService::class.java).setAction(ACTION_START)
            if (bodyX != null && bodyY != null) {
                intent.putExtra(EXTRA_BODY_X, bodyX).putExtra(EXTRA_BODY_Y, bodyY)
            }
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
        fun setVisiting(context: Context, visiting: Boolean, bodyX: Float? = null, bodyY: Float? = null) {
            if (!isRunning) return
            runCatching {
                val intent = Intent(context, GhostOverlayService::class.java)
                    .setAction(ACTION_VISIT)
                    .putExtra(EXTRA_VISITING, visiting)
                if (bodyX != null && bodyY != null) {
                    intent.putExtra(EXTRA_BODY_X, bodyX).putExtra(EXTRA_BODY_Y, bodyY)
                }
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, GhostOverlayService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: FrameLayout? = null
    private var ghost: GhostView? = null

    private var density = 1f
    private var clickThrough = true

    /** The whole display. */
    private val bounds = Rect()

    /**
     * Where he is actually allowed to float: the display minus the status bar, the navigation or
     * gesture bar and any cutout. Without this he drifts underneath the bottom bar and disappears.
     */
    private val usable = Rect()

    /** Size of the ghost, and of the window that carries him (ghost + halo on every side). */
    private var ghostPx = 0
    private var windowPx = 0
    private var haloPx = 0

    /** Space above him inside the window, so a speech bubble is never clipped. */
    private var headroomPx = 0

    /**
     * Blank room on each side of his body, inside the window. The speech bubble is a fixed size at
     * every ghost size, so on a small ghost it is wider than he is and needs somewhere to go.
     * [posX] still means the left edge of his body's own window — only the window we hand the
     * window manager is wider, and it is pushed left by this much to keep him where he was.
     */
    private var sidePx = 0

    /** Blank room below his body inside the window, so the contrast wash is not cut off. */
    private var haloPadPx = 0

    /**
     * A movement set piece that has taken him over for a few seconds — a roll, a bounce, a loop.
     * Null the rest of the time, when he is simply drifting.
     */
    private var routine: Locomotion? = null
    private var routineUntil = 0f
    private var routineAnchorX = 0f
    private var routineAnchorY = 0f
    private var routineAngle = 0f
    private var routineDir = 1f
    private var routineSpeed = 0f

    /** He is flying to a point the app named, rather than drifting — see [comeHome]. */
    private var homing = false
    private var homingToX = 0f
    private var homingToY = 0f

    /** A flight that never lands — the app died mid-hand-off — must not strand him hovering. */
    private var homingUntil = 0f

    // What the window was last resized/retinted to, so the prefs listener only touches the
    // window when the size or colour actually changed rather than on every stat tick.
    private var lastSizeDp = -1
    private var lastTintHue: Float? = -999f

    // Position of the window's top-left corner, kept as floats so motion stays smooth.
    private var posX = 0f
    private var posY = 0f
    private var velX = 0f
    private var velY = 0f

    // He never stops: this is the heading he keeps drifting along between scares.
    private var driftAngle = Random.nextFloat() * 2f * PI.toFloat()
    private var driftSpeed = 0f

    private var lastFrameNanos = 0L
    private var clock = 0f

    // Reacting to taps he cannot locate: recent tap times, so he can get used to a burst of them.
    private val recentTaps = ArrayDeque<Long>()
    private var lastReactionAt = 0L

    // Where he is looking, in screen pixels, and when to pick somewhere new.
    private var gazeScreenX = 0f
    private var gazeScreenY = 0f
    private var nextGlanceAt = 0f

    // Mood, read from Emotions. Refreshed on a slow timer plus whenever a stat changes (feeding,
    // playing, a manual nap, a treat or gift) so the two screens never drift far apart.
    private var sleeping = false
    private var mood: Mood = Mood.CONTENT
    private var energyFull = false
    private var nextStatsTickAt = 0f
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

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
    private var lastShade: Shade = Shade.DEFAULT
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
    private var touchSlop = 0

    // Petting: a hold that starts and stays on him, as opposed to a quick poke or a drag past
    // him — only reachable in solid (non-click-through) mode, where a touch's exact position is
    // actually known. Mirrors GhostPlayground's hold/animation cadence.
    private var pettingArmed = false
    private var petTriggered = false
    private var lastPetAt = 0L
    private val petRunnable = Runnable {
        if (!pettingArmed || dragging) return@Runnable
        petTriggered = true
        pet()
    }

    // Double tap: a second quick tap close to the first, within the platform's usual double-tap
    // window, opens the app instead of fleeing. A lone tap still flees, just after that same
    // brief window closes with no second tap to pair it with.
    private var pendingTapRunnable: Runnable? = null
    private var lastTapUpAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** The pet's brain. Content-driven; with no pack loaded it simply never proposes anything. */
    private var behaviourEngine: BehaviourEngine? = null
    private var enginePackVersion = -1

    /** Rebuilt whenever a newer pack has been downloaded since it was last built. */
    private fun engine(): BehaviourEngine {
        val version = BehaviourPack.loadedVersion(this)
        val current = behaviourEngine
        if (current != null && version == enginePackVersion) return current
        return BehaviourEngine.fromAssets(this).also {
            behaviourEngine = it
            enginePackVersion = version
        }
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            ContentSync.schedule(this@GhostOverlayService)
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }
    private var lastInteractionAt = 0L
    private var lastPetEvent: PetEvent? = null

    private val behaviourRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            proposeBehaviour()
            handler.postDelayed(this, BEHAVIOUR_INTERVAL_MS)
        }
    }

    private var looping = false
    private var lastFrameAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning || !looping) return
            val elapsed = (frameTimeNanos - lastFrameNanos) / 1e9f
            // A slow drifting ghost does not need 60 or 120 frames a second, and every frame moves
            // a window, which is far from free — measured at roughly double the CPU at 45fps versus
            // 30. Thirty is indistinguishable at this speed.
            if (lastFrameNanos != 0L && elapsed < MIN_FRAME_SECONDS) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val dt = if (lastFrameNanos == 0L) 0.016f else elapsed.coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos
            lastFrameAt = SystemClock.elapsedRealtime()
            clock += dt
            tick(dt)
            ghost?.let { view ->
                view.advance(dt)
                view.invalidate()
            }
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
            if (isRunning) {
                val screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
                val stalled = SystemClock.elapsedRealtime() - lastFrameAt > 1_500
                if (screenOn && (!looping || stalled)) startLoop()
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    /** The ghost has nothing to do while nobody can see him — and hours of that is a flat battery. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopLoop()
                Intent.ACTION_SCREEN_ON -> startLoop()
                Intent.ACTION_USER_PRESENT -> {
                    Prefs.recordUnlock(this@GhostOverlayService)
                    startLoop()
                }
            }
        }
    }

    private fun startLoop() {
        looping = true
        lastFrameNanos = 0L
        lastFrameAt = SystemClock.elapsedRealtime()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
        ghost?.invalidate()
    }

    /** Body top-left in screen pixels, as carried on an intent — null when it wasn't. */
    private fun Intent.bodyPoint(): Pair<Float, Float>? {
        if (!hasExtra(EXTRA_BODY_X) || !hasExtra(EXTRA_BODY_Y)) return null
        return getFloatExtra(EXTRA_BODY_X, 0f) to getFloatExtra(EXTRA_BODY_Y, 0f)
    }

    /** Puts his body's top-left at a screen point, clamped to where he is allowed to be. */
    private fun placeBodyAt(x: Float, y: Float) {
        posX = x
        posY = y - headroomPx
        velX = 0f
        velY = 0f
        clampIntoBounds()
        applyPosition()
    }

    private fun stopLoop() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /**
     * Steps him off the screen and into the app's box, or brings him back out. The window stays
     * added and the service stays in the foreground — only the drawing stops, so coming back is
     * instant and none of his position or state is lost.
     */
    private fun setVisiting(visiting: Boolean) {
        if (isVisiting == visiting) return
        isVisiting = visiting
        val view = root ?: return
        if (visiting) {
            view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                if (isVisiting) view.visibility = android.view.View.GONE
                stopLoop()
            }.start()
        } else {
            view.visibility = android.view.View.VISIBLE
            view.animate().alpha(1f).setDuration(FADE_MS).start()
            startLoop()
        }
    }

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
            if (!visiting) intent.bodyPoint()?.let { (x, y) -> placeBodyAt(x, y) }
            setVisiting(visiting)
            return START_STICKY
        }

        if (intent?.action == ACTION_COME_HOME) {
            intent.bodyPoint()?.let { (x, y) ->
                homingToX = x
                homingToY = y - headroomPx
                homing = true
                homingUntil = clock + HOMING_TIMEOUT_SECONDS
                arrivedHome = false
                startLoop()
            }
            return START_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        val spawn = intent?.bodyPoint()
        if (root == null) {
            attachGhost(spawn)
        } else {
            spawn?.let { (x, y) -> placeBodyAt(x, y) }
            startLoop()
        }
        Prefs.setEnabled(this, true)
        // If the system ever kills us off — Samsung's battery manager is fond of it — this brings
        // him back without the user having to open the app.
        Watchdog.schedule(this)
        Recall.clearNotification(this)
        return START_STICKY
    }

    override fun onDestroy() {
        isVisiting = false
        isRunning = false
        // Remember where he was, so he reappears in the same spot next time.
        if (root != null) Prefs.savePosition(this, posX, posY)
        stopLoop()
        handler.removeCallbacksAndMessages(null)
        if (root != null) runCatching { unregisterReceiver(screenReceiver) }
        prefsListener?.let { runCatching { Prefs.raw(this).unregisterOnSharedPreferenceChangeListener(it) } }
        prefsListener = null
        Watchdog.cancel(this)
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        ghost = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshBounds()
        clampIntoBounds()
    }

    // region setup

    private fun attachGhost(spawn: Pair<Float, Float>? = null) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        clickThrough = Prefs.clickThrough(this)
        lastSizeDp = Prefs.sizeDp(this)
        ghostPx = (lastSizeDp * density).toInt()
        haloPx = if (clickThrough) 0 else (HALO_DP * density).toInt()
        windowPx = ghostPx + haloPx * 2
        headroomPx = GhostView.headroomPx(density, ghostPx)
        sidePx = GhostView.bubbleSidePx(density)
        haloPadPx = GhostView.haloPadPx(ghostPx)
        driftSpeed = 18f * density
        refreshBounds()

        val view = GhostView(this)
        view.setBodySize(ghostPx)
        view.setShade(Prefs.shade(this))
        view.species = Prefs.species(this)
        lastTintHue = Prefs.colorHue(this)
        view.setTint(lastTintHue)
        ghost = view
        val container = FrameLayout(this).apply {
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

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (clickThrough) {
            // Nothing is ever swallowed; he only hears the tap go past him.
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        params = WindowManager.LayoutParams(
            windowPx + sidePx * 2,
            windowPx + headroomPx + haloPadPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        // Sent out from the box, he starts exactly where the box was drawing him, so the hand-off
        // between the two is invisible: same ghost, same spot, and only then does he drift off.
        if (spawn != null) {
            posX = spawn.first
            posY = spawn.second - headroomPx
        } else {
            posX = Prefs.lastX(this, bounds.width() * 0.72f)
            posY = Prefs.lastY(this, bounds.height() * 0.35f)
        }
        clampIntoBounds()
        params.x = posX.toInt() - sidePx
        params.y = posY.toInt()

        container.setOnTouchListener { _, event -> onGhostTouch(event) }
        container.alpha = 0f
        windowManager.addView(container, params)
        container.animate().alpha(1f).setDuration(FADE_MS).start()

        isRunning = true
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        )
        startLoop()
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)

        // Feeding, playing or napping from the app writes straight to Prefs; catch it here too, so
        // he doesn't wait up to ten seconds to visibly react. A size or colour change from the
        // Style tab lands here too, resized/retinted in place rather than needing a restart.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            noteEvent(key)
            refreshMood()
            syncAppearance()
        }
        prefsListener = listener
        Prefs.raw(this).registerOnSharedPreferenceChangeListener(listener)
        refreshMood()
        handler.postDelayed(behaviourRunnable, BEHAVIOUR_INTERVAL_MS)
        handler.postDelayed(syncRunnable, FIRST_SYNC_DELAY_MS)
    }

    /** Something the user did — the brain wants to know what happened last, and when. */
    private fun noteInteraction(event: PetEvent) {
        lastPetEvent = event
        lastInteractionAt = System.currentTimeMillis()
        ContentSync.recordEvent(this, event.id)
    }

    /** Feeding and napping happen in the app, not on the overlay; they arrive as pref changes. */
    private fun noteEvent(key: String?) {
        when (key) {
            "fed_at" -> noteInteraction(PetEvent.FED)
            "sleeping" -> if (Prefs.sleeping(this)) noteInteraction(PetEvent.NAPPED)
        }
    }

    /**
     * Ask the brain what he feels like doing. For now the decision is only reported — driving the
     * animations from it is the next step, and belongs on the view side.
     */
    private fun proposeBehaviour() {
        val brain = engine()
        if (!brain.isLoaded) return
        val view = ghost ?: return
        val petContext = PetContext.of(this, lastPetEvent, lastInteractionAt)
        val behaviour = brain.next(petContext, ContentSync.dailyBoosts(this)) ?: return
        perform(behaviour, view)
    }

    /**
     * Turn a decision into something you can see.
     *
     * The pack says what he feels and how he should move; this is the only place that knows how to
     * express either. Nothing here overrides an act the user just took — a behaviour that arrives
     * while he is being petted, eating or asleep is dropped, because his own moment beats the
     * content pack's suggestion.
     */
    private fun perform(behaviour: Behaviour, view: GhostView) {
        if (sleeping || petTriggered) return

        android.util.Log.i(
            "GhostBehaviour",
            "${behaviour.id} -> ${behaviour.emote}/${behaviour.locomotion} " +
                "vocal=${behaviour.vocal} intensity=${behaviour.intensity}"
        )
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

        // Locomotion is a nudge to the drift, never a teleport: he is a ghost, he glides.
        val speed = driftSpeed * (0.6f + behaviour.intensity)
        when (behaviour.locomotion) {
            Locomotion.DRIFT -> driftAngle = Random.nextFloat() * 2f * PI.toFloat()
            Locomotion.FLEE -> launch(angleTowardsOpenSpace())
            Locomotion.APPROACH -> aimAt(usable.centerX().toFloat(), usable.centerY().toFloat(), speed * 2f)
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
        if (posX + windowPx / 2f < usable.centerX()) usable.left + windowPx / 2f else usable.right - windowPx / 2f

    // "Corner" now means the side of the screen at a comfortable height, not the actual corner:
    // the real ones are in the band he stays out of.
    private fun nearestCornerY(): Float =
        (if (posY < usable.centerY()) usable.top + windowPx else usable.bottom - windowPx)
            .toFloat()
            .coerceIn(minY(), maxY())

    /** Resizes and/or retints the live window in place when the Style tab changes, keeping him
     *  centred at the same spot rather than snapping to a corner or flickering off and back on. */
    private fun syncAppearance() {
        val view = ghost ?: return

        val newHue = Prefs.colorHue(this)
        if (newHue != lastTintHue) {
            lastTintHue = newHue
            view.setTint(newHue)
        }

        val newSizeDp = Prefs.sizeDp(this)
        if (newSizeDp != lastSizeDp) {
            lastSizeDp = newSizeDp
            val container = root ?: return
            val newGhostPx = (newSizeDp * density).toInt()
            val newWindowPx = newGhostPx + haloPx * 2
            val delta = (newWindowPx - windowPx) / 2f
            posX -= delta
            posY -= delta
            ghostPx = newGhostPx
            windowPx = newWindowPx
            headroomPx = GhostView.headroomPx(density, ghostPx)
            haloPadPx = GhostView.haloPadPx(ghostPx)
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
        }

        val newShade = Prefs.shade(this)
        if (newShade != lastShade) {
            lastShade = newShade
            ghost?.setShade(newShade)
        }

    }

    /** Catches the stats and his mood up to now, pushes the result onto the view, and keeps the
     *  notification honest about how he's doing. */
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

    private fun refreshMood() {
        val s = Emotions.snapshot(this)
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
            runCatching {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
            }
            runCatching { GhostlyWidgetProvider.refreshAll(this) }
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

    // region touch

    private fun onGhostTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            // Fires for every tap on screen while intangible. No coordinates — see the class doc.
            noticeTap()
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
                ghost?.lookAt((event.rawX - cx) / (windowPx / 2f), (event.rawY - cy) / (windowPx / 2f))
                petTriggered = false
                pettingArmed = true
                handler.postDelayed(petRunnable, PET_HOLD_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    pettingArmed = false
                    handler.removeCallbacks(petRunnable)
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
                    Prefs.savePosition(this, posX, posY)
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(petRunnable)
                pettingArmed = false
                dragging = false
                return true
            }
        }
        return false
    }

    // endregion

    // region motion

    /** A hand strokes his head for a couple of seconds; still held after that, it happens again. */
    private fun pet() {
        noteInteraction(PetEvent.PETTED)
        val now = SystemClock.uptimeMillis()
        if (now - lastPetAt < PET_ANIMATION_MS) return
        lastPetAt = now
        val s = PetStats.snapshot(this)
        val happiness = (s.happiness + 3f).coerceAtMost(PetStats.MAX)
        Prefs.saveStats(this, s.hunger, s.energy, happiness, s.sleeping, System.currentTimeMillis())
        ghost?.startPetting()
        // Still held: keep ticking affection for as long as the finger stays put.
        handler.postDelayed(petRunnable, PET_ANIMATION_MS)
    }

    /** A poke while napping: stir awake with a small startle rather than a full bolt. */
    private fun wakeUp() {
        PetStats.wake(this)
        sleeping = false
        ghost?.setMood(mood, false)
        ghost?.notice()
        ghost?.spookLightly()
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
        // Ghostly, not startled-cat: he glides away rather than snapping across the screen. Angry,
        // that glide gets a noticeably harder edge.
        val angryBoost = if (mood == Mood.ANGRY) 1.5f else 1f
        val speed = (320f + Random.nextFloat() * 260f) * density * angryBoost
        velX = cos(angle) * speed
        velY = sin(angle) * speed
        driftAngle = angle
        ghost?.spook()
        ghost?.setMotion(velX, velY)
        buzz()
    }

    /**
     * A tap landed somewhere on screen while he is intangible. He cannot know where, so he reacts
     * to all of them — but he gets used to them: a burst of taps (you are typing) earns a longer
     * cooldown and a smaller flinch than one deliberate poke out of the blue.
     */
    private fun noticeTap() {
        if (sleeping) {
            wakeUp()
            return
        }
        val now = SystemClock.uptimeMillis()
        while (recentTaps.isNotEmpty() && now - recentTaps.first() > 4_000) recentTaps.removeFirst()
        recentTaps.addLast(now)
        val burst = recentTaps.size

        // He looks towards whatever seems to be going on: a flurry of taps is almost always the
        // keyboard, so he watches the bottom of the screen; a lone tap just makes him glance about.
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

        // Drift away from the commotion. Direction is random — with a lean towards open screen, so
        // he does not spend his life pinned against an edge.
        val angle = angleTowardsOpenSpace()
        val impulse = (if (burst >= 3) 90f else 190f + Random.nextFloat() * 120f) * density * (if (angry) 1.4f else 1f)
        velX += cos(angle) * impulse
        velY += sin(angle) * impulse
        driftAngle = angle
        view.notice()
        if (burst < 3) buzz()
    }

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

        // He is never quite still: the heading wanders, and the speed always settles back to a slow
        // float rather than to zero. Angry, that float turns into a restless, erratic pace — he's
        // not going anywhere, but he's clearly not settled either. Brimming with energy, the float
        // turns quicker and more purposeful instead.
        val angryJitter = if (mood == Mood.ANGRY) 2.6f else 1f
        val angrySpeed = if (mood == Mood.ANGRY) 1.6f else if (energyFull) 1.3f else 1f
        driftAngle += (sin(clock * 0.31f) + sin(clock * 0.17f + 1.3f)) * 0.4f * angryJitter * dt
        val targetX = cos(driftAngle) * driftSpeed * angrySpeed
        val targetY = sin(driftAngle) * driftSpeed * angrySpeed
        // A gentle pull back towards the middle of the band, cubed so it is nothing at all in the
        // middle of the screen and firm by the time he is near the top or bottom of his range. Left
        // to a plain bounce he spent much of his time grazing along one edge or the other.
        val bandMid = (minY() + maxY()) / 2f
        val bandHalf = ((maxY() - minY()) / 2f).coerceAtLeast(1f)
        val strayed = ((posY - bandMid) / bandHalf).coerceIn(-1f, 1f)
        val recentre = -(strayed * strayed * strayed) * driftSpeed * 1.6f

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
        val s = Emotions.snapshot(this)
        val species = Prefs.species(this)
        when {
            s.body.hunger <= PetStats.HUNGRY_THRESHOLD -> {
                buzz()
                view.showBubble(
                    when (species) {
                        Species.CAT -> "Meow"
                        Species.DOG -> "Woof"
                        Species.GHOST -> "..."
                    }
                )
            }
            mood == Mood.CONTENT && s.body.happiness >= 70f -> when (species) {
                Species.CAT -> view.showBubble("Purr~")
                Species.DOG -> {
                    view.showBubble("Woof!")
                    view.startWiggle()
                }
                Species.GHOST -> if (Random.nextBoolean()) view.startWiggle() else view.showBubble("~")
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
     * The flight home. He eases towards the point the app named and stops there, and nothing else —
     * drift, flourishes, the behaviour engine — gets a say until he lands. [arrivedHome] is what
     * the app waits on before it takes him over, so the hand-off happens with him already in place.
     */
    /**
     * Starts one of the set-piece movements. Each is given a few seconds, an anchor at wherever he
     * is standing, and then runs itself in [tickRoutine] until its time is up.
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
                routineAnchorX = if (leaving) minX() - ghostPx * 0.62f else maxX() + ghostPx * 0.62f
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
                val targetX = if (goingOut) routineAnchorX else routineAnchorX - routineDir * ghostPx * 1.4f
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
        clampIntoBounds()
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
            applyPosition()
            homing = false
            arrivedHome = true
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

    // The ghost — not the window's transparent halo — is what has to stay on screen.
    // A sliver of overhang still looks good — he nuzzles the edge — but never enough to hide him
    // behind a system bar.
    private fun overhang() = ghostPx * 0.05f

    /**
     * He keeps off the top and bottom of the screen. Those are where a floating ghost is most in
     * the way — the status bar and the clock above, the gesture bar and whatever the app puts at
     * the bottom below — and where he is most likely to be sitting on something you are reading.
     * The band is enforced through [minY]/[maxY], so drift, perching, bouncing and every set piece
     * inherit it rather than each having to remember.
     */
    private fun topInset() = usable.height() * 0.09f

    private fun bottomInset() = usable.height() * 0.13f

    private fun minX() = usable.left - haloPx - overhang()
    private fun maxX() = usable.right - windowPx + haloPx + overhang()
    // His body starts headroomPx below the top of the window, so the vertical bounds shift by it:
    // he may sit at the very top of the screen with the bubble space hanging off-screen above.
    private fun minY() = usable.top + topInset() - haloPx - headroomPx
    // The window is windowPx + headroomPx tall and his body sits in the BOTTOM of it, so the floor
    // has to come up by the headroom as well — without this he sinks below the navigation bar by
    // exactly the height of his own speech bubble.
    private fun maxY() = usable.bottom - bottomInset() - headroomPx - windowPx + haloPx

    private fun clampIntoBounds() {
        posX = posX.coerceIn(minX(), maxX())
        posY = posY.coerceIn(minY(), maxY())
    }

    private var lastAppliedX = Int.MIN_VALUE
    private var lastAppliedY = Int.MIN_VALUE

    private fun applyPosition() {
        val view = root ?: return
        val nx = posX.toInt()
        val ny = posY.toInt()
        if (nx == lastAppliedX && ny == lastAppliedY) return
        lastAppliedX = nx
        lastAppliedY = ny
        params.x = nx - sidePx
        params.y = ny
        bodyX = posX
        bodyY = posY + headroomPx
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    // endregion

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

        val name = Prefs.displayName(this)
        val (title, text) = when {
            sleeping -> "$name is napping" to "He's resting. Wake him with a tap, or Stop to send him away."
            mood == Mood.ANGRY -> "$name is upset with you" to "He's been neglected too long — a gift would help."
            mood == Mood.SAD -> "$name is floating" to "He's a little down today."
            else -> "$name is floating" to "Tap him and he runs away."
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
}
