package com.shashank.ghostly

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * A tabbed home screen: Home (the pet itself), Shop (the token economy), Style (character/size)
 * and Settings (behaviour, battery, sharing). A persistent HUD along the top keeps his name,
 * streak and token balance visible no matter which tab you're on — the closest this app gets to
 * a game's always-on status bar.
 */
class MainActivity : Activity() {

    private enum class AppTab { HOME, SHOP, STYLE, SETTINGS }

    private class TabEntry(
        val tab: AppTab,
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val glyph: IconGlyph
    )

    // Shell
    private lateinit var hudBar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var homePage: View
    private lateinit var shopPage: View
    private lateinit var stylePage: View
    private lateinit var settingsPage: View
    private val tabEntries = mutableListOf<TabEntry>()

    // HUD
    private lateinit var nameLabel: TextView
    private lateinit var streakChipText: TextView
    private lateinit var tokensChipText: TextView

    // Home
    private lateinit var playground: GhostPlayground
    private lateinit var moodLabel: TextView
    private lateinit var hungerBar: ProgressBar
    private lateinit var energyBar: ProgressBar
    private lateinit var happinessBar: ProgressBar
    private lateinit var angerBar: ProgressBar
    private lateinit var playLabel: TextView
    private lateinit var playRoot: View
    private lateinit var restLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var primaryButton: Button
    private lateinit var blockedCard: LinearLayout

    // Shop
    private lateinit var tokensBigText: TextView
    private lateinit var treatButton: Button
    private lateinit var treatCard: View
    private lateinit var giftButton: Button
    private lateinit var giftCard: View

    // Style
    private val speciesButtons = mutableListOf<Pair<Species, Button>>()
    private lateinit var sizeSeekBar: SeekBar
    private lateinit var sizeValueLabel: TextView
    private val colorSwatches = mutableListOf<Pair<Float?, View>>()

    // Settings
    private lateinit var modeSwitch: Switch
    private lateinit var footerLabel: TextView

    private var lastKnownRunning: Boolean? = null

    private val stateWatcher = object : Runnable {
        override fun run() {
            if (lastKnownRunning != GhostOverlayService.isRunning) refreshState() else refreshNeeds()
            primaryButton.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    // See Palette.kt — the whole app's colour system lives there now, shared with onboarding.
    private val ink = Palette.ink
    private val card = Palette.card
    private val cardStroke = Palette.cardStroke
    private val accent = Palette.accent
    private val accentDeep = Palette.accentDeep
    private val mint = Palette.mint
    private val angerRed = Palette.angerRed
    private val dim = Palette.dim

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildUi()
        setContentView(root)
        applyEdgeToEdgeInsets()
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

    override fun onPause() {
        primaryButton.removeCallbacks(stateWatcher)
        super.onPause()
    }

    private fun petName(): String = Prefs.name(this) ?: Prefs.species(this).label

    /**
     * Android 16 (API 36) draws every app edge to edge with no opt-out: the HUD keeps the status
     * bar clear of the name/streak/tokens, and the tab bar keeps the gesture nav clear of labels.
     */
    private fun applyEdgeToEdgeInsets() {
        val hudTop = hudBar.paddingTop
        val tabBottom = tabBar.paddingBottom
        hudBar.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
            view.setPadding(view.paddingLeft, hudTop + bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
        hudBar.requestApplyInsets()
        tabBar.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, tabBottom + bars.bottom)
            insets
        }
        tabBar.requestApplyInsets()
    }

    // region shell

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink)
        }

        hudBar = buildHud()
        root.addView(hudBar)

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        homePage = capWidth(buildHomePage())
        shopPage = capWidth(buildShopPage())
        stylePage = capWidth(buildStylePage())
        settingsPage = capWidth(buildSettingsPage())
        contentFrame.addView(homePage)
        contentFrame.addView(shopPage)
        contentFrame.addView(stylePage)
        contentFrame.addView(settingsPage)
        root.addView(contentFrame)

        tabBar = buildTabBar()
        root.addView(tabBar)

        showTab(AppTab.HOME)
        return root
    }

    /** On a tablet a full-width column reads badly, so cap it and centre it like a large-screen
     *  layout is expected to behave. */
    private fun capWidth(content: View): View {
        val maxWidth = dp(600)
        val columnWidth = if (resources.displayMetrics.widthPixels > maxWidth) maxWidth else MATCH_PARENT
        return FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(columnWidth, MATCH_PARENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }

    private fun buildHud(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(ink)
            elevation = dp(4).toFloat()
        }
        nameLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { showRenameDialog() }
        }
        bar.addView(nameLabel)

        val (streakChip, streakText) = hudChip(IconGlyph.ANGER, accent)
        streakChipText = streakText
        bar.addView(streakChip)

        val (tokensChip, tokensText) = hudChip(IconGlyph.TOKEN, mint)
        tokensChipText = tokensText
        (tokensChip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        bar.addView(tokensChip)

        return bar
    }

    private fun hudChip(glyph: IconGlyph, tint: Int): Pair<LinearLayout, TextView> {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(card, dp(20).toFloat(), cardStroke)
            setPadding(dp(10), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        chip.addView(iconView(glyph, tint, 15))
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }
        }
        chip.addView(text)
        return chip to text
    }

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(card)
            elevation = dp(10).toFloat()
            setPadding(0, dp(6), 0, dp(6))
        }
        val tabs = listOf(
            Triple(AppTab.HOME, "Home", IconGlyph.HOME),
            Triple(AppTab.SHOP, "Shop", IconGlyph.SHOP),
            Triple(AppTab.STYLE, "Style", IconGlyph.STYLE),
            Triple(AppTab.SETTINGS, "Settings", IconGlyph.SETTINGS)
        )
        tabs.forEach { (tab, label, glyph) ->
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            val text = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(3) }
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener { showTab(tab) }
            }
            container.addView(icon)
            container.addView(text)
            bar.addView(container)
            tabEntries += TabEntry(tab, container, icon, text, glyph)
        }
        return bar
    }

    private fun showTab(tab: AppTab) {
        homePage.visibility = if (tab == AppTab.HOME) View.VISIBLE else View.GONE
        shopPage.visibility = if (tab == AppTab.SHOP) View.VISIBLE else View.GONE
        stylePage.visibility = if (tab == AppTab.STYLE) View.VISIBLE else View.GONE
        settingsPage.visibility = if (tab == AppTab.SETTINGS) View.VISIBLE else View.GONE
        tabEntries.forEach { entry ->
            val selected = entry.tab == tab
            entry.icon.setImageDrawable(IconDrawable(entry.glyph, if (selected) accent else dim))
            entry.label.setTextColor(if (selected) accent else dim)
        }
        refreshState()
    }

    // endregion

    // region home

    private fun buildHomePage(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        column.addView(TextView(this).apply {
            text = "A shy little ghost that floats over everything.\n" +
                "He watches what you're doing, and drifts off when you tap."
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        playground = GhostPlayground(this).apply {
            background = rounded(card, dp(24).toFloat(), cardStroke)
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(300)).apply { topMargin = dp(16) }
        }
        column.addView(playground)

        moodLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        column.addView(moodLabel)

        val needsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, dp(18).toFloat(), cardStroke)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        hungerBar = addNeedRow(needsCard, "Hunger", IconGlyph.HUNGER, mint)
        energyBar = addNeedRow(needsCard, "Energy", IconGlyph.ENERGY, mint)
        happinessBar = addNeedRow(needsCard, "Happiness", IconGlyph.HAPPINESS, mint)
        angerBar = addNeedRow(needsCard, "Anger", IconGlyph.ANGER, angerRed)
        column.addView(needsCard)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val feed = quickActionButton("Feed", IconGlyph.HUNGER) {
            PetStats.feed(this@MainActivity)
            pulse(hungerBar)
            refreshNeeds()
        }
        actionsRow.addView(feed.root)

        val play = quickActionButton("Play · ${Emotions.PLAY_COST}", IconGlyph.PLAY, leftMargin = true) {
            when (Emotions.playWithToken(this@MainActivity)) {
                Emotions.PlayOutcome.SUCCESS -> {
                    refreshNeeds()
                    pulse(playground)
                    playground.startFetch()
                }
                Emotions.PlayOutcome.NO_TOKENS -> toastNoTokens()
                Emotions.PlayOutcome.TOO_TIRED ->
                    Toast.makeText(this@MainActivity, "Too worn out to play right now", Toast.LENGTH_SHORT).show()
            }
        }
        playRoot = play.root
        playLabel = play.label
        actionsRow.addView(play.root)

        val rest = quickActionButton("Let him nap", IconGlyph.NAP, leftMargin = true) {
            val sleepingNow = PetStats.snapshot(this@MainActivity).sleeping
            PetStats.setSleeping(this@MainActivity, !sleepingNow)
            refreshNeeds()
        }
        restLabel = rest.label
        actionsRow.addView(rest.root)
        column.addView(actionsRow)

        statusLabel = TextView(this).apply {
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) }
        }
        column.addView(statusLabel)

        primaryButton = Button(this).apply {
            setTextColor(Color.parseColor("#0B0A14"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            stateListAnimator = null
            background = gradientRounded(accent, accentDeep, dp(18).toFloat())
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(12) }
            setOnClickListener { onPrimaryClicked() }
        }
        addPressBounce(primaryButton)
        column.addView(primaryButton)

        blockedCard = buildBlockedCard()
        column.addView(blockedCard)

        scroll.addView(column)
        return scroll
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

    // endregion

    // region shop

    private fun buildShopPage(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        column.addView(TextView(this).apply {
            text = "Shop"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        column.addView(TextView(this).apply {
            text = "Feed and letting him nap are always free. Everything below runs on a daily " +
                "allowance of tokens."
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        })

        val tokensCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = gradientRounded(Color.parseColor("#241F45"), Color.parseColor("#16142B"), dp(22).toFloat())
            elevation = dp(3).toFloat()
            setPadding(dp(16), dp(20), dp(16), dp(20))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) }
        }
        tokensCard.addView(iconView(IconGlyph.TOKEN, mint, 34))
        tokensBigText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        tokensCard.addView(tokensBigText)
        tokensCard.addView(TextView(this).apply {
            text = "tokens left today"
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        column.addView(tokensCard)

        column.addView(sectionLabel("Spend them"))

        val (treatCardView, treatBtn) = shopItemCard(
            IconGlyph.TREAT, "Treat", "A tastier pick-me-up than a free feed",
            "Give · ${Emotions.TREAT_COST}"
        ) {
            if (Emotions.giveTreat(this@MainActivity)) {
                pulse(treatCard)
                refreshNeeds()
            } else {
                toastNoTokens()
            }
        }
        treatCard = treatCardView
        treatButton = treatBtn
        column.addView(treatCard)

        val (giftCardView, giftBtn) = shopItemCard(
            IconGlyph.GIFT, "Gift", "The real apology — wins him back when he's upset",
            "Give · ${Emotions.GIFT_COST}"
        ) {
            if (Emotions.giveGift(this@MainActivity)) {
                pulse(giftCard)
                refreshNeeds()
            } else {
                toastNoTokens()
            }
        }
        giftCard = giftCardView
        giftButton = giftBtn
        column.addView(giftCard)

        column.addView(TextView(this).apply {
            text = "Tokens reset to ${Emotions.DAILY_TOKENS} every day — they don't carry over, so " +
                "there's no reason to hoard them."
            setTextColor(Color.parseColor("#6F6A96"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
        })

        scroll.addView(column)
        return scroll
    }

    private fun shopItemCard(
        glyph: IconGlyph,
        title: String,
        desc: String,
        buttonLabel: String,
        onClick: () -> Unit
    ): Pair<View, Button> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(card, dp(18).toFloat(), cardStroke)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        }

        val badge = FrameLayout(this).apply {
            background = rounded(Color.parseColor("#241F45"), dp(14).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        badge.addView(iconView(glyph, mint, 24).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.CENTER }
        })
        row.addView(badge)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
                marginEnd = dp(10)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        textCol.addView(TextView(this).apply {
            text = desc
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) }
        })
        row.addView(textCol)

        val button = Button(this).apply {
            text = buttonLabel
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.parseColor("#0B0A14"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = rounded(mint, dp(14).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(94), dp(44))
            setOnClickListener { onClick() }
        }
        addPressBounce(button)
        row.addView(button)

        return row to button
    }

    private fun toastNoTokens() =
        Toast.makeText(this, "Out of tokens for today — more tomorrow", Toast.LENGTH_SHORT).show()

    // endregion

    // region style

    private fun buildStylePage(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        column.addView(TextView(this).apply {
            text = "Style"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        column.addView(sectionLabel("Character"))
        val speciesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        Species.entries.forEachIndexed { index, species ->
            val button = pickerButton(species.label) { chooseSpecies(species) }
            if (index > 0) (button.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            speciesButtons += species to button
            speciesRow.addView(button)
        }
        column.addView(speciesRow)

        val sizeHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) }
        }
        sizeHeaderRow.addView(TextView(this).apply {
            text = "Size"
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        sizeValueLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        sizeHeaderRow.addView(sizeValueLabel)
        column.addView(sizeHeaderRow)

        sizeSeekBar = SeekBar(this).apply {
            max = Prefs.SIZE_MAX - Prefs.SIZE_MIN
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    sizeValueLabel.text = "${Prefs.SIZE_MIN + progress} dp"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    chooseSize(Prefs.SIZE_MIN + seekBar.progress)
                }
            })
        }
        column.addView(sizeSeekBar)

        column.addView(sectionLabel("Colour"))
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val swatches = listOf<Float?>(null, 189f, 265f, 340f, 25f, 145f)
        swatches.forEachIndexed { index, hue ->
            val swatch = colorSwatch(hue) { chooseTint(hue) }
            if (index > 0) (swatch.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            colorSwatches += hue to swatch
            colorRow.addView(swatch)
        }
        column.addView(colorRow)

        scroll.addView(column)
        return scroll
    }

    /** A round tap target — his own colours for "no tint", a solid hue for everything else. */
    private fun colorSwatch(hue: Float?, onClick: () -> Unit) = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        val fillColor = if (hue == null) Color.parseColor("#8C6C63C9") else
            Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }
        setOnClickListener { onClick() }
        addPressBounce(this)
    }

    private fun pickerButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        setOnClickListener { onClick() }
        addPressBounce(this)
    }

    // endregion

    // region settings

    private fun buildSettingsPage(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        column.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        column.addView(sectionLabel("Behaviour"))
        modeSwitch = Switch(this).apply {
            text = "Let taps pass through him"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isChecked = Prefs.clickThrough(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
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
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        })

        footerLabel = TextView(this).apply {
            setTextColor(mint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        column.addView(footerLabel)

        column.addView(Switch(this).apply {
            text = "Buzz when he runs"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isChecked = Prefs.hapticsEnabled(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) }
            setOnCheckedChangeListener { _, checked -> Prefs.setHapticsEnabled(this@MainActivity, checked) }
        })

        column.addView(sectionLabel("If he vanishes"))
        column.addView(TextView(this).apply {
            text = "Phones put background apps to sleep to save battery — Samsung especially. If " +
                "Ghostly stops floating after an hour or two, set his battery usage to " +
                "Unrestricted and turn off \"Put app to sleep\" for him."
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        })
        column.addView(settingsRowButton("Open battery settings", null) { openBatterySettings() })

        column.addView(sectionLabel("Share"))
        val shareRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val shareBtn = iconTextButton("Share his card", IconGlyph.SHARE) { sharePetCard() }
        shareBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        val pinBtn = iconTextButton("Add to home screen", IconGlyph.PIN) { requestPinWidget() }
        pinBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
        shareRow.addView(shareBtn)
        shareRow.addView(pinBtn)
        column.addView(shareRow)

        column.addView(sectionLabel("Trouble"))
        column.addView(settingsRowButton("Permission blocked by Android?", null) { openAppInfo() })

        scroll.addView(column)
        return scroll
    }

    private fun settingsRowButton(label: String, glyph: IconGlyph?, onClick: () -> Unit) =
        iconTextButton(label, glyph, onClick).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        }

    /** Standalone by default (full width, for a vertical column); callers placing this inside a
     *  horizontal row — see the Share row — override layoutParams to weight afterwards. */
    private fun iconTextButton(label: String, glyph: IconGlyph?, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(card, dp(14).toFloat(), cardStroke)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        if (glyph != null) {
            row.addView(iconView(glyph, mint, 18).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
            })
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        })
        addPressBounce(row)
        return row
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
        if (manager == null || !manager.isRequestPinAppWidgetSupported) {
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

    // endregion

    // region shared ui helpers

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(Color.parseColor("#6F6A96"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.14f
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun gradientRounded(startColor: Int, endColor: Int, radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(startColor, endColor)
    ).apply { cornerRadius = radius }

    private fun iconView(glyph: IconGlyph, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageDrawable(IconDrawable(glyph, tint))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun addNeedRow(container: LinearLayout, label: String, glyph: IconGlyph, tint: Int): ProgressBar {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (container.childCount > 0) topMargin = dp(14)
            }
        }
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(iconView(glyph, tint, 14).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6)
        })
        labelRow.addView(TextView(this).apply {
            text = label
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        row.addView(labelRow)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(tint)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(10)).apply { topMargin = dp(6) }
        }
        row.addView(bar)
        container.addView(row)
        return bar
    }

    private class QuickAction(val root: View, val label: TextView)

    private fun quickActionButton(text: String, glyph: IconGlyph, leftMargin: Boolean = false, onClick: () -> Unit): QuickAction {
        val label = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(card, dp(16).toFloat(), cardStroke)
            elevation = dp(2).toFloat()
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(76), 1f).apply { if (leftMargin) marginStart = dp(10) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        root.addView(iconView(glyph, mint, 22))
        root.addView(label)
        addPressBounce(root)
        return QuickAction(root, label)
    }

    /** A quick tactile bounce on press — the closest thing to a game-y button feel without pulling
     *  in a whole animation/haptics library. */
    private fun addPressBounce(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    /** A little "that landed" pulse — used after a treat, gift or successful play. */
    private fun pulse(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(140).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }.start()
    }

    private fun animateProgress(bar: ProgressBar, to: Int) {
        val target = to.coerceIn(0, 100)
        if (bar.progress == target) return
        ObjectAnimator.ofInt(bar, "progress", bar.progress, target).setDuration(420).start()
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
        primaryButton.background = if (floating) {
            gradientRounded(angerRed, Color.parseColor("#C94A48"), dp(18).toFloat())
        } else {
            gradientRounded(accent, accentDeep, dp(18).toFloat())
        }

        blockedCard.visibility = if (canOverlay) View.GONE else View.VISIBLE
        footerLabel.text = if (Prefs.clickThrough(this)) {
            "He's intangible: taps go straight through to whatever is underneath. Stop him from " +
                "his notification."
        } else {
            "He's solid: drag him anywhere, long-press to open this screen, or use the Stop " +
                "action in his notification."
        }

        val currentSize = Prefs.sizeDp(this)
        sizeSeekBar.progress = currentSize - Prefs.SIZE_MIN
        sizeValueLabel.text = "$currentSize dp"

        val currentSpecies = Prefs.species(this)
        speciesButtons.forEach { (species, button) -> stylePickerState(button, species == currentSpecies) }

        val currentHue = Prefs.colorHue(this)
        colorSwatches.forEach { (hue, swatch) -> swatchSelectedState(swatch, hue == currentHue) }

        refreshNeeds()
    }

    private fun stylePickerState(button: Button, selected: Boolean) {
        button.background = rounded(
            if (selected) accent else card,
            dp(14).toFloat(),
            if (selected) accent else cardStroke
        )
        button.setTextColor(if (selected) Color.WHITE else dim)
    }

    private fun swatchSelectedState(swatch: View, selected: Boolean) {
        swatch.scaleX = if (selected) 1.15f else 1f
        swatch.scaleY = if (selected) 1.15f else 1f
        swatch.alpha = if (selected) 1f else 0.75f
    }

    private fun refreshNeeds() {
        val s = Emotions.snapshot(this)
        animateProgress(hungerBar, s.body.hunger.toInt())
        animateProgress(energyBar, s.body.energy.toInt())
        animateProgress(happinessBar, s.body.happiness.toInt())
        animateProgress(angerBar, s.anger.toInt())
        restLabel.text = if (s.body.sleeping) "Wake him up" else "Let him nap"

        val name = petName()
        moodLabel.text = when {
            s.body.sleeping -> "$name is resting."
            s.mood == Mood.ANGRY -> "$name is angry with you — a gift would help."
            s.mood == Mood.SAD -> "$name is a little down."
            else -> "$name is content."
        }
        nameLabel.text = name

        val streak = Prefs.streak(this)
        streakChipText.text = streak.toString()

        playRoot.isEnabled = s.tokens >= Emotions.PLAY_COST
        playRoot.alpha = if (playRoot.isEnabled) 1f else 0.5f

        tokensChipText.text = s.tokens.toString()
        tokensBigText.text = "${s.tokens}/${Emotions.DAILY_TOKENS}"

        treatButton.isEnabled = s.tokens >= Emotions.TREAT_COST
        treatButton.alpha = if (treatButton.isEnabled) 1f else 0.5f
        giftButton.isEnabled = s.tokens >= Emotions.GIFT_COST
        giftButton.alpha = if (giftButton.isEnabled) 1f else 0.5f

        runCatching { GhostlyWidgetProvider.refreshAll(this) }
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
        // The overlay's own prefs listener resizes it live, in place — no restart needed.
        Prefs.setSizeDp(this, sizeDp)
        refreshState()
    }

    private fun chooseTint(hue: Float?) {
        // Likewise retinted live by the overlay's prefs listener.
        Prefs.setColorHue(this, hue)
        playground.setTint(hue)
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
