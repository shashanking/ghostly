package com.shashank.ghostly

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * The one and only screen: try the ghost out, then let it loose on top of every other app.
 */
class MainActivity : Activity() {

    private lateinit var statusLabel: TextView
    private lateinit var primaryButton: Button
    private lateinit var blockedCard: LinearLayout
    private lateinit var modeSwitch: Switch
    private lateinit var footerLabel: TextView
    private lateinit var playground: GhostPlayground
    private lateinit var hungerBar: ProgressBar
    private lateinit var energyBar: ProgressBar
    private lateinit var happinessBar: ProgressBar
    private lateinit var angerBar: ProgressBar
    private lateinit var moodLabel: TextView
    private lateinit var tokensLabel: TextView
    private lateinit var nameLabel: TextView
    private lateinit var streakLabel: TextView
    private lateinit var restButton: Button
    private lateinit var treatButton: Button
    private lateinit var giftButton: Button
    private lateinit var playButton: Button
    private val sizeButtons = mutableListOf<Pair<Int, Button>>()
    private val speciesButtons = mutableListOf<Pair<Species, Button>>()

    private var lastKnownRunning: Boolean? = null

    private val stateWatcher = object : Runnable {
        override fun run() {
            if (lastKnownRunning != GhostOverlayService.isRunning) refreshState() else refreshNeeds()
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

        val previousOpen = Prefs.lastOpenedAt(this)
        val now = System.currentTimeMillis()
        Prefs.saveLastOpenedAt(this, now)
        Streak.touch(this)
        if (previousOpen != 0L && now - previousOpen > WELCOME_BACK_GAP_MS) {
            Toast.makeText(this, "${petName()} missed you!", Toast.LENGTH_LONG).show()
        }

        refreshState()
        // The overlay can come and go without this screen being told — stopped from its
        // notification, or restarted by the system — so keep the button honest while we're visible.
        primaryButton.removeCallbacks(stateWatcher)
        primaryButton.postDelayed(stateWatcher, WATCH_INTERVAL_MS)
    }

    private fun petName(): String = Prefs.name(this) ?: Prefs.species(this).label

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

        val identityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(16) }
        }
        nameLabel = TextView(this).apply {
            setTextColor(mint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { showRenameDialog() }
        }
        identityRow.addView(nameLabel)
        streakLabel = TextView(this).apply {
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        identityRow.addView(streakLabel)
        column.addView(identityRow)

        // Playground
        playground = GhostPlayground(this).apply {
            background = rounded(card, dp(24).toFloat(), Color.parseColor("#2B2650"))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(300))
                .apply { topMargin = dp(22) }
        }
        column.addView(playground)

        // Needs
        column.addView(sectionLabel("Needs"))
        moodLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        column.addView(moodLabel)
        val needsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, dp(18).toFloat(), Color.parseColor("#2B2650"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        hungerBar = addNeedRow(needsCard, "Hunger")
        energyBar = addNeedRow(needsCard, "Energy")
        happinessBar = addNeedRow(needsCard, "Happiness")
        angerBar = addNeedRow(needsCard, "Anger", tint = Color.parseColor("#FF5252"))
        column.addView(needsCard)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        actionsRow.addView(actionButton("Feed") {
            PetStats.feed(this@MainActivity)
            refreshNeeds()
        })
        playButton = actionButton("Play · ${Emotions.PLAY_COST}", leftMargin = true) {
            when (Emotions.playWithToken(this@MainActivity)) {
                Emotions.PlayOutcome.SUCCESS -> {
                    refreshNeeds()
                    playground.startFetch()
                }
                Emotions.PlayOutcome.NO_TOKENS ->
                    Toast.makeText(this@MainActivity, "Out of tokens for today — more tomorrow", Toast.LENGTH_SHORT).show()
                Emotions.PlayOutcome.TOO_TIRED ->
                    Toast.makeText(this@MainActivity, "Too worn out to play right now", Toast.LENGTH_SHORT).show()
            }
        }
        actionsRow.addView(playButton)
        restButton = actionButton("Let him nap", leftMargin = true) {
            val sleepingNow = PetStats.snapshot(this@MainActivity).sleeping
            PetStats.setSleeping(this@MainActivity, !sleepingNow)
            refreshNeeds()
        }
        actionsRow.addView(restButton)
        column.addView(actionsRow)

        tokensLabel = TextView(this).apply {
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(14) }
        }
        column.addView(tokensLabel)

        val treatsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        treatButton = actionButton("Treat · ${Emotions.TREAT_COST}") {
            if (Emotions.giveTreat(this@MainActivity)) {
                refreshNeeds()
            } else {
                Toast.makeText(this@MainActivity, "Out of tokens for today — more tomorrow", Toast.LENGTH_SHORT).show()
            }
        }
        treatsRow.addView(treatButton)
        giftButton = actionButton("Gift · ${Emotions.GIFT_COST}", leftMargin = true) {
            if (Emotions.giveGift(this@MainActivity)) {
                refreshNeeds()
            } else {
                Toast.makeText(this@MainActivity, "Out of tokens for today — more tomorrow", Toast.LENGTH_SHORT).show()
            }
        }
        treatsRow.addView(giftButton)
        column.addView(treatsRow)

        // Character
        column.addView(sectionLabel("Character"))
        val speciesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        Species.entries.forEachIndexed { index, species ->
            val button = Button(this).apply {
                text = species.label
                isAllCaps = false
                stateListAnimator = null
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    if (index > 0) leftMargin = dp(10)
                }
                setOnClickListener { chooseSpecies(species) }
            }
            speciesButtons += species to button
            speciesRow.addView(button)
        }
        column.addView(speciesRow)

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

        // Share
        column.addView(sectionLabel("Share"))
        val shareRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        shareRow.addView(actionButton("Share his card") { sharePetCard() })
        shareRow.addView(actionButton("Add to home screen", leftMargin = true) { requestPinWidget() })
        column.addView(shareRow)

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

    private fun addNeedRow(container: LinearLayout, label: String, tint: Int = mint): ProgressBar {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (container.childCount > 0) topMargin = dp(14)
            }
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(tint)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(10)).apply { topMargin = dp(6) }
        }
        row.addView(bar)
        container.addView(row)
        return bar
    }

    private fun actionButton(label: String, leftMargin: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(card, dp(14).toFloat(), Color.parseColor("#2B2650"))
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (leftMargin) this.leftMargin = dp(10)
            }
            setOnClickListener { onClick() }
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

        val currentSpecies = Prefs.species(this)
        speciesButtons.forEach { (species, button) ->
            val selected = species == currentSpecies
            button.background = rounded(
                if (selected) accent else card,
                dp(14).toFloat(),
                if (selected) accent else Color.parseColor("#2B2650")
            )
            button.setTextColor(if (selected) Color.WHITE else dim)
        }

        refreshNeeds()
    }

    private fun refreshNeeds() {
        val s = Emotions.snapshot(this)
        hungerBar.progress = s.body.hunger.toInt()
        energyBar.progress = s.body.energy.toInt()
        happinessBar.progress = s.body.happiness.toInt()
        angerBar.progress = s.anger.toInt()
        restButton.text = if (s.body.sleeping) "Wake him up" else "Let him nap"

        val name = petName()
        moodLabel.text = when {
            s.body.sleeping -> "$name is resting."
            s.mood == Mood.ANGRY -> "$name is angry with you — a gift would help."
            s.mood == Mood.SAD -> "$name is a little down."
            else -> "$name is content."
        }
        nameLabel.text = Prefs.name(this)?.let { "$it · tap to rename" } ?: "Tap to name him"

        val streak = Prefs.streak(this)
        streakLabel.text = if (streak <= 1) "1 day streak" else "$streak day streak"

        tokensLabel.text = "Tokens today: ${s.tokens}/${Emotions.DAILY_TOKENS} — free daily allowance"

        playButton.isEnabled = s.tokens >= Emotions.PLAY_COST
        playButton.alpha = if (playButton.isEnabled) 1f else 0.5f
        treatButton.isEnabled = s.tokens >= Emotions.TREAT_COST
        treatButton.alpha = if (treatButton.isEnabled) 1f else 0.5f
        giftButton.isEnabled = s.tokens >= Emotions.GIFT_COST
        giftButton.alpha = if (giftButton.isEnabled) 1f else 0.5f

        runCatching { GhostlyWidgetProvider.refreshAll(this) }
    }

    /** Renders his current look and stats to a PNG in the private share cache, then hands it to
     *  whatever app the user picks — via [ShareFileProvider], since the app carries no library
     *  (AndroidX's FileProvider included) to do this for us. */
    private fun sharePetCard() {
        runCatching {
            val fileName = "pet_card.png"
            val file = File(ShareFileProvider.shareDir(this), fileName)
            FileOutputStream(file).use { out ->
                renderPetCard().compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = ShareFileProvider.uriFor(fileName)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${petName()}"))
        }.onFailure {
            Toast.makeText(this, "Couldn't create the share card", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderPetCard(): Bitmap {
        val w = dp(360)
        val h = dp(420)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ink)

        val ghostSize = dp(180)
        val view = GhostView(this)
        view.species = Prefs.species(this)
        val s = Emotions.snapshot(this)
        view.setMood(s.mood, s.body.sleeping)
        val spec = View.MeasureSpec.makeMeasureSpec(ghostSize, View.MeasureSpec.EXACTLY)
        view.measure(spec, spec)
        view.layout(0, 0, ghostSize, ghostSize)
        canvas.save()
        canvas.translate((w - ghostSize) / 2f, dp(36).toFloat())
        view.draw(canvas)
        canvas.restore()

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(22).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        canvas.drawText(petName(), w / 2f, dp(250).toFloat(), namePaint)

        val statPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dim
            textSize = dp(14).toFloat()
            textAlign = Paint.Align.CENTER
        }
        val statusText = when {
            s.body.sleeping -> "Resting"
            s.mood == Mood.ANGRY -> "Angry"
            s.mood == Mood.SAD -> "A little down"
            else -> "Content"
        }
        canvas.drawText("${Prefs.species(this).label} · $statusText", w / 2f, dp(278).toFloat(), statPaint)
        canvas.drawText(
            "Hunger ${s.body.hunger.toInt()} · Energy ${s.body.energy.toInt()} · Happiness ${s.body.happiness.toInt()}",
            w / 2f, dp(304).toFloat(), statPaint
        )
        canvas.drawText("Ghostly", w / 2f, (h - dp(20)).toFloat(), statPaint)
        return bitmap
    }

    private fun requestPinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(
                this,
                "Your launcher doesn't support this — add him from your home screen's widget picker instead",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        manager.requestPinAppWidget(ComponentName(this, GhostlyWidgetProvider::class.java), null, null)
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(Prefs.name(this@MainActivity) ?: "")
            hint = Prefs.species(this@MainActivity).label
            setTextColor(Color.WHITE)
            setHintTextColor(dim)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Name him")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setName(this, input.text.toString())
                refreshNeeds()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseSpecies(species: Species) {
        Prefs.setSpecies(this, species)
        playground.setSpecies(species)
        restartOverlayIfRunning()
        refreshState()
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
        const val WELCOME_BACK_GAP_MS = 12 * 60 * 60 * 1_000L
    }

    // endregion
}
