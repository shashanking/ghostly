package com.shashank.ghostly

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The one and only screen: try the ghost out, then let it loose on top of every other app.
 */
class MainActivity : Activity() {

    private lateinit var statusLabel: TextView
    private lateinit var primaryButton: Button
    private lateinit var blockedCard: LinearLayout
    private lateinit var modeSwitch: Switch
    private lateinit var footerLabel: TextView
    private val sizeButtons = mutableListOf<Pair<Int, Button>>()

    private var lastKnownRunning: Boolean? = null

    private val stateWatcher = object : Runnable {
        override fun run() {
            if (lastKnownRunning != GhostOverlayService.isRunning) refreshState()
            primaryButton.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    private val ink = Color.parseColor("#0B0A14")
    private val card = Color.parseColor("#16142B")
    private val accent = Color.parseColor("#8B6BFF")
    private val mint = Color.parseColor("#4ADEDE")
    private val dim = Color.parseColor("#9A93C4")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildUi()
        setContentView(root)
        applyEdgeToEdgeInsets(root)
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        // The overlay can come and go without this screen being told — stopped from its
        // notification, or restarted by the system — so keep the button honest while we're visible.
        primaryButton.removeCallbacks(stateWatcher)
        primaryButton.postDelayed(stateWatcher, WATCH_INTERVAL_MS)
    }

    override fun onPause() {
        primaryButton.removeCallbacks(stateWatcher)
        super.onPause()
    }

    /**
     * Android 16 (API 36) draws every app edge to edge with no opt-out, so the content has to keep
     * itself clear of the status and navigation bars.
     */
    private fun applyEdgeToEdgeInsets(root: View) {
        val start = root.paddingTop
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
            view.setPadding(view.paddingLeft, start + bars.top, view.paddingRight, bottom + bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    // region ui

    private fun buildUi(): View {
        val root = ScrollView(this).apply { setBackgroundColor(ink) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(28))
        }

        column.addView(TextView(this).apply {
            text = "Ghostly"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.02f
        })

        column.addView(TextView(this).apply {
            text = "A shy little ghost that floats over everything.\n" +
                "He watches what you're doing, and drifts off when you tap."
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(4).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        })

        // Playground
        column.addView(GhostPlayground(this).apply {
            background = rounded(card, dp(24).toFloat(), Color.parseColor("#2B2650"))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(300))
                .apply { topMargin = dp(22) }
        })

        statusLabel = TextView(this).apply {
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(20) }
        }
        column.addView(statusLabel)

        primaryButton = Button(this).apply {
            setTextColor(Color.parseColor("#0B0A14"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            stateListAnimator = null
            background = rounded(mint, dp(18).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56))
                .apply { topMargin = dp(12) }
            setOnClickListener { onPrimaryClicked() }
        }
        column.addView(primaryButton)

        blockedCard = buildBlockedCard()
        column.addView(blockedCard)

        // Size picker
        column.addView(sectionLabel("Size"))
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        listOf("Small" to Prefs.SIZE_SMALL, "Medium" to Prefs.SIZE_MEDIUM, "Large" to Prefs.SIZE_LARGE)
            .forEachIndexed { index, (label, value) ->
                val button = Button(this).apply {
                    text = label
                    isAllCaps = false
                    stateListAnimator = null
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        if (index > 0) leftMargin = dp(10)
                    }
                    setOnClickListener { chooseSize(value) }
                }
                sizeButtons += value to button
                sizeRow.addView(button)
            }
        column.addView(sizeRow)

        // Behaviour
        column.addView(sectionLabel("Behaviour"))
        modeSwitch = Switch(this).apply {
            text = "Let taps pass through him"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isChecked = Prefs.clickThrough(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(14) }
            setOnCheckedChangeListener { _, checked -> chooseMode(checked) }
        }
        column.addView(modeSwitch)

        column.addView(TextView(this).apply {
            text = "On: nothing he floats over is ever blocked — buttons and keyboard keys still " +
                "work through him. He notices taps but can't tell where they landed, so he can't " +
                "be poked precisely or dragged.\n" +
                "Off: he's solid — tap to poke, drag to move, long-press to open this screen — but " +
                "he swallows taps where he sits."
            setTextColor(Color.parseColor("#6F6A96"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        })

        // Haptics
        column.addView(Switch(this).apply {
            text = "Buzz when he runs"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isChecked = Prefs.hapticsEnabled(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(22) }
            setOnCheckedChangeListener { _, checked ->
                Prefs.setHapticsEnabled(this@MainActivity, checked)
            }
        })

        column.addView(sectionLabel("If he vanishes"))
        column.addView(TextView(this).apply {
            text = "Phones put background apps to sleep to save battery — Samsung especially. If " +
                "Ghostly stops floating after an hour or two, set his battery usage to " +
                "Unrestricted and turn off \"Put app to sleep\" for him."
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        })
        column.addView(Button(this).apply {
            text = "Open battery settings"
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(card, dp(14).toFloat(), Color.parseColor("#2B2650"))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(46))
                .apply { topMargin = dp(12) }
            setOnClickListener { openBatterySettings() }
        })

        footerLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#6F6A96"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(20) }
        }
        column.addView(footerLabel)

        column.addView(Button(this).apply {
            text = "Permission blocked by Android?"
            isAllCaps = false
            stateListAnimator = null
            setTextColor(mint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = rounded(Color.TRANSPARENT, 0f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(44))
                .apply { topMargin = dp(6) }
            setOnClickListener { openAppInfo() }
        })

        // On a tablet, a single column stretched across the whole width reads badly, so cap it and
        // centre it the way large-screen layouts are expected to behave.
        val maxWidth = dp(600)
        val columnWidth =
            if (resources.displayMetrics.widthPixels > maxWidth) maxWidth else MATCH_PARENT
        root.addView(
            column,
            android.widget.FrameLayout.LayoutParams(columnWidth, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        return root
    }

    /**
     * Android refuses the "display over other apps" permission to apps installed from outside a
     * store — the dialog says "App was denied access" and offers nothing useful. The way out is
     * buried in the app's own info screen, so hand the user the steps and a button that goes there.
     */
    private fun buildBlockedCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#1D1A33"), dp(18).toFloat(), Color.parseColor("#3A3363"))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(12) }

        addView(TextView(this@MainActivity).apply {
            text = "Android says \"App was denied access\"?"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        addView(TextView(this@MainActivity).apply {
            text = "That block is Android's, not Ghostly's — it applies to any app installed " +
                "outside the Play Store. Unlock it once:\n\n" +
                "1.  Open app info below\n" +
                "2.  Tap  ⋮  in the top-right corner\n" +
                "3.  Tap \"Allow restricted settings\"\n" +
                "4.  Come back and grant \"Display over other apps\""
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        })

        addView(Button(this@MainActivity).apply {
            text = "Open app info"
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(accent, dp(14).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(46))
                .apply { topMargin = dp(14) }
            setOnClickListener { openAppInfo() }
        })
    }

    /**
     * Sends the user to the battery screen where the "put this app to sleep" behaviour lives. The
     * exact screen differs by manufacturer, so fall back to the app's own info page.
     */
    private fun openBatterySettings() {
        val batteryList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (batteryList.resolveActivity(packageManager) != null) {
            startActivity(batteryList)
            Toast.makeText(this, "Find Ghostly and allow it to run in the background", Toast.LENGTH_LONG).show()
        } else {
            openAppInfo()
        }
    }

    private fun openAppInfo() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Open Settings › Apps › Ghostly", Toast.LENGTH_LONG).show() }
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(Color.parseColor("#6F6A96"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.14f
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(26) }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        if (stroke != null) setStroke(dp(1), stroke)
    }

    // endregion

    // region state

    private fun refreshState() {
        val canOverlay = Settings.canDrawOverlays(this)
        val floating = GhostOverlayService.isRunning
        lastKnownRunning = floating

        statusLabel.text = when {
            !canOverlay -> "Ghostly needs the \"Display over other apps\" permission to leave this screen."
            floating -> "Floating now — go open any app, he's still there."
            else -> "Ready when you are."
        }

        primaryButton.text = when {
            !canOverlay -> "Grant permission"
            floating -> "Call him back"
            else -> "Let him float"
        }
        primaryButton.background = rounded(if (floating) Color.parseColor("#FF7A8F") else mint, dp(18).toFloat())

        blockedCard.visibility = if (canOverlay) View.GONE else View.VISIBLE
        footerLabel.text = if (Prefs.clickThrough(this)) {
            "He's intangible: taps go straight through to whatever is underneath. Stop him from " +
                "his notification."
        } else {
            "He's solid: drag him anywhere, long-press to open this screen, or use the Stop " +
                "action in his notification."
        }

        val current = Prefs.sizeDp(this)
        sizeButtons.forEach { (value, button) ->
            val selected = value == current
            button.background = rounded(
                if (selected) accent else card,
                dp(14).toFloat(),
                if (selected) accent else Color.parseColor("#2B2650")
            )
            button.setTextColor(if (selected) Color.WHITE else dim)
        }
    }

    private fun onPrimaryClicked() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, "Turn Ghostly on, then come back", Toast.LENGTH_LONG).show()
            return
        }

        if (GhostOverlayService.isRunning) {
            GhostOverlayService.stop(this)
        } else {
            GhostOverlayService.start(this)
        }
        // The service flips its own flag; give it a beat before redrawing.
        primaryButton.postDelayed({ refreshState() }, 250)
    }

    private fun chooseMode(clickThrough: Boolean) {
        Prefs.setClickThrough(this, clickThrough)
        restartOverlayIfRunning()
        refreshState()
    }

    private fun restartOverlayIfRunning() {
        if (!GhostOverlayService.isRunning) return
        // The window flags are fixed when the overlay is added, so he has to be re-summoned.
        GhostOverlayService.stop(this)
        primaryButton.postDelayed({
            GhostOverlayService.start(this)
            primaryButton.postDelayed({ refreshState() }, 250)
        }, 200)
    }

    private fun chooseSize(sizeDp: Int) {
        Prefs.setSizeDp(this, sizeDp)
        restartOverlayIfRunning()
        refreshState()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val WATCH_INTERVAL_MS = 1_000L
    }

    // endregion
}
