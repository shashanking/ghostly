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
import android.content.res.Configuration
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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.PI
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
        private const val CHANNEL_ID = "ghost_overlay"
        private const val NOTIFICATION_ID = 7
        private const val WATCHDOG_INTERVAL_MS = 2_000L
        private const val MIN_FRAME_SECONDS = 1f / 30f

        /** Transparent ring around the ghost that still reacts to a tap, in dp. */
        private const val HALO_DP = 22f

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Returns false when Android refused the start. From the foreground this always works; from
         * a broadcast it can be refused — `MY_PACKAGE_REPLACED` is not one of the exemptions for
         * starting a foreground service, and an unhandled refusal crashes the process.
         */
        fun start(context: Context): Boolean = runCatching {
            val intent = Intent(context, GhostOverlayService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }.isSuccess

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
    private val bounds = Rect()

    /** Size of the ghost, and of the window that carries him (ghost + halo on every side). */
    private var ghostPx = 0
    private var windowPx = 0
    private var haloPx = 0

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
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> startLoop()
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

    private fun stopLoop() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (root == null) attachGhost() else startLoop()
        Prefs.setEnabled(this, true)
        // If the system ever kills us off — Samsung's battery manager is fond of it — this brings
        // him back without the user having to open the app.
        Watchdog.schedule(this)
        Recall.clearNotification(this)
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        // Remember where he was, so he reappears in the same spot next time.
        if (root != null) Prefs.savePosition(this, posX, posY)
        stopLoop()
        handler.removeCallbacksAndMessages(null)
        if (root != null) runCatching { unregisterReceiver(screenReceiver) }
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

    private fun attachGhost() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        clickThrough = Prefs.clickThrough(this)
        ghostPx = (Prefs.sizeDp(this) * density).toInt()
        haloPx = if (clickThrough) 0 else (HALO_DP * density).toInt()
        windowPx = ghostPx + haloPx * 2
        driftSpeed = 18f * density
        refreshBounds()

        val view = GhostView(this)
        ghost = view
        val container = FrameLayout(this).apply {
            addView(
                view,
                FrameLayout.LayoutParams(ghostPx, ghostPx, android.view.Gravity.CENTER)
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
            windowPx,
            windowPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        posX = Prefs.lastX(this, bounds.width() * 0.72f)
        posY = Prefs.lastY(this, bounds.height() * 0.35f)
        clampIntoBounds()
        params.x = posX.toInt()
        params.y = posY.toInt()

        container.setOnTouchListener { _, event -> onGhostTouch(event) }
        windowManager.addView(container, params)

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
    }

    private fun refreshBounds() {
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bounds.set(wm.currentWindowMetrics.bounds)
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            bounds.set(0, 0, size.x, size.y)
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
                val cy = posY + windowPx / 2f
                ghost?.lookAt((event.rawX - cx) / (windowPx / 2f), (event.rawY - cy) / (windowPx / 2f))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
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
                val held = SystemClock.uptimeMillis() - downTime
                if (!dragging) {
                    if (held > 550) {
                        openApp()
                    } else {
                        // Poked on or near him: bolt away from the finger.
                        fleeFrom(event.rawX, event.rawY)
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
                dragging = false
                return true
            }
        }
        return false
    }

    // endregion

    // region motion

    /** Dash off in a random direction, generally away from [fromX], [fromY]. */
    private fun fleeFrom(fromX: Float, fromY: Float) {
        val cx = posX + windowPx / 2f
        val cy = posY + windowPx / 2f
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
        val speed = (320f + Random.nextFloat() * 260f) * density
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

        val cooldown = when {
            burst >= 6 -> 2_600L   // busy screen: he settles down and mostly just watches
            burst >= 3 -> 1_100L
            else -> 320L
        }
        val view = ghost ?: return
        if (now - lastReactionAt < cooldown) {
            view.notice()
            return
        }
        lastReactionAt = now

        // Drift away from the commotion. Direction is random — with a lean towards open screen, so
        // he does not spend his life pinned against an edge.
        val angle = angleTowardsOpenSpace()
        val impulse = (if (burst >= 3) 90f else 190f + Random.nextFloat() * 120f) * density
        velX += cos(angle) * impulse
        velY += sin(angle) * impulse
        driftAngle = angle
        view.notice()
        if (burst < 3) buzz()
    }

    /** A heading that generally points back into the middle of the screen, plus a wide spread. */
    private fun angleTowardsOpenSpace(): Float {
        val cx = posX + windowPx / 2f
        val cy = posY + windowPx / 2f
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
        val cy = posY + windowPx / 2f
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
        updateGaze(dt)

        // He is never quite still: the heading wanders, and the speed always settles back to a slow
        // float rather than to zero.
        driftAngle += (sin(clock * 0.31f) + sin(clock * 0.17f + 1.3f)) * 0.4f * dt
        val targetX = cos(driftAngle) * driftSpeed
        val targetY = sin(driftAngle) * driftSpeed
        val settle = 1f - exp(-0.85f * dt)
        velX += (targetX - velX) * settle
        velY += (targetY - velY) * settle

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
    private fun minX() = -haloPx - ghostPx * 0.15f
    private fun maxX() = bounds.width() - windowPx + haloPx + ghostPx * 0.15f
    private fun minY() = -haloPx - ghostPx * 0.15f
    private fun maxY() = bounds.height() - windowPx + haloPx + ghostPx * 0.15f

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
        params.x = nx
        params.y = ny
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

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ghost)
            .setContentTitle("Ghostly is floating")
            .setContentText("Tap him and he runs away.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
