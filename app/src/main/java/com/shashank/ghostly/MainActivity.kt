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
import android.widget.HorizontalScrollView
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
    // The three faces, resolved once per screen.
    private val uiMedium: android.graphics.Typeface by lazy { Type.sansMedium(this) }
    private val serifFace: android.graphics.Typeface by lazy { Type.serif(this) }
    private val serifItalicFace: android.graphics.Typeface by lazy { Type.serifItalic(this) }
    private val mono: android.graphics.Typeface by lazy { Type.mono(this) }


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
    private lateinit var hungerMeter: MeterView
    private lateinit var energyMeter: MeterView
    private lateinit var happinessMeter: MeterView
    private lateinit var angerMeter: MeterView
    private lateinit var temperColumn: LinearLayout

    /** He is in the box for a moment while still being out on the overlay — see [visitBox]. */
    private var visiting = false
    private val returnToFloating = Runnable { endVisit() }

    /**
     * A hand-off between the box and the overlay is in flight. While it is, the box is left exactly
     * as the hand-off put it — refreshing the screen must not snatch him back or shove him out
     * halfway through the move.
     */
    private var handingOver = false
    private lateinit var hungerBar: ProgressBar
    private lateinit var energyBar: ProgressBar
    private lateinit var happinessBar: ProgressBar
    private lateinit var angerBar: ProgressBar
    private lateinit var playLabel: TextView
    private lateinit var playRoot: View
    private var homeActionsRow: LinearLayout? = null
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
    private var speciesScroller: HorizontalScrollView? = null
    private val sizeTiles = mutableListOf<Pair<Int, OptionTile>>()
    private val shadeTiles = mutableListOf<Pair<Shade, OptionTile>>()

    // Settings

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

        // Tapping the "ready to install" notification lands here and finishes the job.
        if (intent?.action == ACTION_FINISH_UPDATE) {
            intent.action = null
            AppUpdates.clearNotification(this)
            AppUpdates.complete(this)
        }
        AppUpdates.check(this) { start -> offerUpdate(start) }

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
        // Leaving the app is the other one — his stats have just been changed by hand.
        ContentSync.schedule(this)
        primaryButton.removeCallbacks(stateWatcher)
        // A visit is only a visit while you are here to watch it; otherwise he is out floating.
        endVisit()
        AppUpdates.release(this)
        super.onPause()
    }

    private fun petName(): String = Prefs.displayName(this)

    /**
     * Android 16 (API 36) draws every app edge to edge with no opt-out: the HUD keeps the status
     * bar clear of the name/streak/tokens, and the tab bar keeps the gesture nav clear of labels.
     */
    private fun applyEdgeToEdgeInsets() {
        // WindowInsets.Type and the matching getInsets(Int) overload only exist from API 30 —
        // referencing them unconditionally would crash on load on every Android 8–9 device this
        // app (minSdk 26) is supposed to support. Older versions never draw under the system bars
        // in the first place, so there's nothing to compensate for there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
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
    /**
     * Keeps a phone-shaped column on a tablet rather than stretching everything to the width of the
     * screen. On a large screen it is centred both ways: capped to 600dp it would otherwise sit in
     * the top half with a screenful of black underneath, which reads as an app that has not been
     * looked at on a tablet.
     */
    private fun capWidth(content: View): View {
        val maxWidth = dp(600)
        val wide = resources.displayMetrics.widthPixels > maxWidth
        val large = resources.configuration.smallestScreenWidthDp >= 600
        val gravity = Gravity.CENTER_HORIZONTAL or if (large) Gravity.CENTER_VERTICAL else Gravity.TOP
        return FrameLayout(this).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    if (wide) maxWidth else MATCH_PARENT,
                    if (large) WRAP_CONTENT else MATCH_PARENT,
                ).apply { this.gravity = gravity },
            )
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
        // The bar carries the app, not the pet: his name belongs next to him, on Home.
        bar.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = serifFace
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })

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
            typeface = uiMedium
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
        if (tab != AppTab.HOME) endVisit()
        homePage.visibility = if (tab == AppTab.HOME) View.VISIBLE else View.GONE
        shopPage.visibility = if (tab == AppTab.SHOP) View.VISIBLE else View.GONE
        stylePage.visibility = if (tab == AppTab.STYLE) View.VISIBLE else View.GONE
        settingsPage.visibility = if (tab == AppTab.SETTINGS) View.VISIBLE else View.GONE
        tabEntries.forEach { entry ->
            val selected = entry.tab == tab
            entry.icon.setImageDrawable(IconDrawable(entry.glyph, if (selected) accent else dim))
            entry.label.setTextColor(if (selected) accent else dim)
            entry.container.isSelected = selected
            entry.container.contentDescription =
                getString(if (selected) R.string.a11y_tab_selected else R.string.a11y_tab, entry.label.text)
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

        playground = GhostPlayground(this).apply {
            // Reaching into the empty box while he is out is a way of asking for him.
            onSummon = { visitBox() }
            contentDescription = getString(R.string.a11y_box)
            background = rounded(card, dp(28).toFloat(), cardStroke)
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(348)).apply { topMargin = dp(4) }
        }
        column.addView(playground)

        // His name, right under him, and tapping it renames him.
        nameLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = serifFace
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
            setOnClickListener { showRenameDialog() }
        }
        column.addView(nameLabel)

        // How he is, said in his own voice rather than drawn as four bars.
        moodLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = serifItalicFace
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) }
        }
        column.addView(moodLabel)

        // The four meters are gone from the screen. They still exist — the engine reads them, the
        // ghost shows them — but a pet you read off a dashboard is a dashboard, not a pet. The bars
        // stay allocated (never attached) so every refresh path keeps working untouched.
        val hiddenNeeds = LinearLayout(this).apply { visibility = View.GONE }
        hungerBar = addNeedRow(hiddenNeeds, "Hunger", IconGlyph.HUNGER, mint)
        energyBar = addNeedRow(hiddenNeeds, "Energy", IconGlyph.ENERGY, mint)
        happinessBar = addNeedRow(hiddenNeeds, "Happiness", IconGlyph.HAPPINESS, mint)
        angerBar = addNeedRow(hiddenNeeds, "Anger", IconGlyph.ANGER, angerRed)
        column.addView(hiddenNeeds)

        // Hairline meters under his mood line: enough to see he is hungry, not enough to manage.
        // Temper only appears when there is a temper to speak of.
        val meters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) }
        }
        hungerMeter = addMeterColumn(meters, "Fed")
        energyMeter = addMeterColumn(meters, "Rested")
        happinessMeter = addMeterColumn(meters, "Happy")
        temperColumn = LinearLayout(this)
        angerMeter = addMeterColumn(meters, "Temper", angerRed, temperColumn)
        column.addView(meters)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val feed = quickActionButton("Feed", IconGlyph.HUNGER) {
            PetStats.feed(this@MainActivity)
            Prefs.markFed(this@MainActivity)
            pulse(hungerBar)
            refreshNeeds()
            // The treat drops once he is actually in the box — he may still be flying in.
            visitBox { playground.startFeeding() }
        }
        actionsRow.addView(feed.root)

        val play = quickActionButton("Play · ${Emotions.PLAY_COST}", IconGlyph.PLAY, leftMargin = true) {
            when (Emotions.playWithToken(this@MainActivity)) {
                Emotions.PlayOutcome.SUCCESS -> {
                    refreshNeeds()
                    pulse(playground)
                    visitBox { playground.startFetch() }
                }
                Emotions.PlayOutcome.NO_TOKENS -> toastNoTokens()
                Emotions.PlayOutcome.TOO_TIRED ->
                    Toast.makeText(this@MainActivity, getString(R.string.too_worn_out_to_play_right_now), Toast.LENGTH_SHORT).show()
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
        homeActionsRow = actionsRow
        column.addView(actionsRow)

        statusLabel = TextView(this).apply {
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) }
        }
        column.addView(statusLabel)

        primaryButton = Button(this).apply {
            setTextColor(Palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = uiMedium
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
        background = rounded(Palette.glass, dp(18).toFloat(), Palette.glassStroke)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(12) }

        addView(TextView(this@MainActivity).apply {
            text = "Android says \"App was denied access\"?"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = uiMedium
        })

        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.blocked_explainer)
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        })

        addView(Button(this@MainActivity).apply {
            text = getString(R.string.open_app_info)
            isAllCaps = false
            stateListAnimator = null
            // Ink on bone, like every other filled button — accent is near-white.
            setTextColor(Palette.ink)
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
            text = getString(R.string.shop)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = serifFace
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.shop_intro)
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        })

        val tokensCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = gradientRounded(Palette.badge, Palette.card, dp(22).toFloat())
            elevation = dp(3).toFloat()
            setPadding(dp(16), dp(20), dp(16), dp(20))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) }
        }
        tokensCard.addView(iconView(IconGlyph.TOKEN, mint, 34))
        tokensBigText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = serifFace
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        tokensCard.addView(tokensBigText)
        tokensCard.addView(TextView(this).apply {
            text = getString(R.string.tokens_left_today)
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
                Prefs.markFed(this@MainActivity)
                // The animation plays in his box on the Home tab — jump there so it is actually
                // seen rather than happening silently behind the Shop page, and call him in for
                // it if he is out floating, or the treat drops into an empty box.
                showTab(AppTab.HOME)
                refreshNeeds()
                // After the tab switch the box has only just been made visible: it has no position
                // on screen until it has been laid out, and calling him to a box at 0,0 sends him
                // to the wrong place. One frame is all it needs.
                playground.post { visitBox { playground.startFeeding() } }
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
                showTab(AppTab.HOME)
                refreshNeeds()
                playground.post { visitBox { playground.startGift() } }
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
            setTextColor(Palette.textFaint)
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
            background = rounded(Palette.badge, dp(14).toFloat())
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
            typeface = uiMedium
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
            setTextColor(Palette.ink)
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
        Toast.makeText(this, getString(R.string.out_of_tokens_for_today_more_tomorrow), Toast.LENGTH_SHORT).show()

    // endregion

    // region style

    private fun buildStylePage(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        column.addView(TextView(this).apply {
            text = getString(R.string.style)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = serifFace
        })

        column.addView(sectionLabel("Kind"))
        // There are twelve of them. Sharing the width between twelve buttons leaves each one about
        // as wide as its own text, so the row keeps a readable width per button and scrolls
        // sideways instead — and [refreshPickers] brings whichever one is his into view.
        val speciesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Species.entries.forEachIndexed { index, species ->
            val button = pickerButton(species.short) { chooseSpecies(species) }
            button.layoutParams = LinearLayout.LayoutParams(dp(88), dp(46)).apply {
                if (index > 0) marginStart = dp(10)
            }
            speciesButtons += species to button
            speciesRow.addView(button)
        }
        column.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(speciesRow)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .apply { topMargin = dp(10) }
                speciesScroller = this
            }
        )

        // Size: three named options, each showing him at that size, rather than a number on a rail.
        column.addView(sectionLabel("Size"))
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        listOf(
            Triple("Wisp", Prefs.SIZE_WISP, 26),
            Triple("Spook", Prefs.SIZE_SPOOK, 34),
            Triple("Haunt", Prefs.SIZE_HAUNT, 48),
        ).forEachIndexed { index, (label, sizeDp, previewDp) ->
            val tile = optionTile(label, previewDp) { chooseSize(sizeDp) }
            if (index > 0) (tile.root.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            sizeTiles += sizeDp to tile
            sizeRow.addView(tile.root)
        }
        column.addView(sizeRow)

        // Shade: named looks. A black-and-white ghost has no hue to pick, so the choice is how much
        // of the screen you see through him — and Ink, for anyone on a pale wallpaper.
        column.addView(sectionLabel("Shade"))
        val shadeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        Shade.entries.forEachIndexed { index, shade ->
            val tile = optionTile(shade.label, 30, shade) { chooseShade(shade) }
            if (index > 0) (tile.root.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            shadeTiles += shade to tile
            shadeRow.addView(tile.root)
        }
        column.addView(shadeRow)

        scroll.addView(column)
        return scroll
    }

    private class OptionTile(val root: LinearLayout, val label: TextView, val preview: GhostView)

    /**
     * One choice, showing the thing itself: a real [GhostView] at the size (or in the shade) the
     * option selects, so picking is a matter of looking rather than reading a number.
     */
    private fun optionTile(
        label: String,
        previewDp: Int,
        shade: Shade? = null,
        onClick: () -> Unit,
    ): OptionTile {
        val preview = GhostView(this).apply {
            species = Prefs.species(this@MainActivity)
            setShade(shade ?: Prefs.shade(this@MainActivity))
            isClickable = false
            layoutParams = FrameLayout.LayoutParams(
                dp(previewDp),
                dp(previewDp) + GhostView.headroomPx(resources.displayMetrics.density, dp(previewDp)) +
                    GhostView.haloPadPx(dp(previewDp)),
                android.view.Gravity.CENTER,
            )
        }
        // A fixed-height well so all three size options are the same size of tile.
        val well = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(76))
            addView(preview)
        }
        val text = TextView(this).apply {
            this.text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(well)
            addView(text)
            setOnClickListener { onClick() }
        }
        addPressBounce(root)
        return OptionTile(root, text, preview)
    }

    /**
     * [speciesNow] is passed in because the tiles are built once and kept: the preview inside each
     * one used to keep whatever kind he was at startup, so picking Fox left every size and shade
     * tile still showing a plain ghost until the app was restarted. Barely visible with three
     * kinds; obvious with twelve.
     */
    private fun optionTileState(tile: OptionTile, selected: Boolean, speciesNow: Species) {
        if (tile.preview.species != speciesNow) {
            tile.preview.species = speciesNow
            tile.preview.invalidate()
        }
        tile.root.background = if (selected) {
            rounded(Palette.glass, dp(18).toFloat(), Palette.bone)
        } else {
            rounded(Palette.card, dp(18).toFloat(), Palette.cardStroke)
        }
        tile.label.setTextColor(if (selected) Color.WHITE else Palette.dim)
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
            text = getString(R.string.settings)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = serifFace
        })

        column.addView(sectionLabel("Behaviour"))
        column.addView(TextView(this).apply {
            text = getString(R.string.settings_behaviour_body)
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        })

        column.addView(Switch(this).apply {
            text = getString(R.string.buzz_when_he_runs)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Type.sans(this@MainActivity)
            thumbTintList = ColorStateList.valueOf(Palette.bone)
            trackTintList = ColorStateList.valueOf(Palette.cardStroke)
            isChecked = Prefs.hapticsEnabled(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) }
            setOnCheckedChangeListener { _, checked -> Prefs.setHapticsEnabled(this@MainActivity, checked) }
        })

        column.addView(Switch(this).apply {
            text = getString(R.string.settings_touchable)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Type.sans(this@MainActivity)
            thumbTintList = ColorStateList.valueOf(Palette.bone)
            trackTintList = ColorStateList.valueOf(Palette.cardStroke)
            // Stored the other way round: clickThrough true (the default) means taps go through him.
            isChecked = !Prefs.clickThrough(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
            setOnCheckedChangeListener { _, checked -> Prefs.setClickThrough(this@MainActivity, !checked) }
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.settings_touchable_body)
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        })

        column.addView(sectionLabel("If he vanishes"))
        column.addView(TextView(this).apply {
            text = getString(R.string.settings_battery_body)
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

        column.addView(sectionLabel("Account"))
        // The settings page is built once and only shown and hidden, so signing in has to be able
        // to rewrite this section afterwards rather than leaving it saying "Not signed in".
        accountStatus = TextView(this).apply {
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        column.addView(accountStatus)
        accountActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        column.addView(accountActions)
        refreshAccountSection()

        column.addView(sectionLabel("Trouble"))
        column.addView(settingsRowButton("Permission blocked by Android?", null) { openAppInfo() })
        column.addView(settingsRowButton("Overlay permission not sticking?", null) { showOverlaySettingsGuide() })

        scroll.addView(column)
        return scroll
    }

    private var accountStatus: TextView? = null
    private var accountActions: LinearLayout? = null

    /** Re-renders the Account section from whatever is stored now. Safe to call any number of times. */
    private fun refreshAccountSection() {
        val actions = accountActions ?: return
        val email = Prefs.userEmail(this)
        accountStatus?.text =
            if (email != null) "Signed in as $email" else "Not signed in — the pet lives only on this phone."
        actions.removeAllViews()
        if (email != null) {
            actions.addView(settingsRowButton("Delete my account and server data", null) { confirmDeleteAccount() })
        } else {
            actions.addView(settingsRowButton("Sign in with Google", null) { signInFromSettings() })
            actions.addView(TextView(this).apply {
                text = getString(R.string.settings_account_body)
                setTextColor(Palette.textFaint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(dp(3).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            })
        }
    }

    /** The same Credential Manager flow onboarding uses, reachable for anyone who skipped it. */
    private fun signInFromSettings() {
        val option = com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(OnboardingActivity.GOOGLE_WEB_CLIENT_ID)
            .build()
        val request = androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(option).build()
        Toast.makeText(this, getString(R.string.opening_google_sign_in), Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching {
                kotlinx.coroutines.runBlocking {
                    androidx.credentials.CredentialManager.create(this@MainActivity)
                        .getCredential(this@MainActivity, request)
                }
            }
            val credential = result.getOrNull()?.credential
            val idToken = (credential as? androidx.credentials.CustomCredential)
                ?.takeIf { it.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL }
                ?.let { com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(it.data) }
            if (idToken == null) {
                val why = result.exceptionOrNull()?.message ?: "no Google credential offered"
                android.util.Log.w("GhostlyAuth", "sign-in failed: $why")
                runOnUiThread { Toast.makeText(this, getString(R.string.sign_in_didn_t_complete), Toast.LENGTH_LONG).show() }
                return@Thread
            }
            Prefs.saveSignedInUser(this, idToken.id, idToken.displayName)
            val ok = runCatching { GhostlyApi.authGoogle(applicationContext, idToken.idToken) }
            android.util.Log.i("GhostlyAuth", "server session: ${ok.getOrNull()} ${ok.exceptionOrNull()?.message ?: ""}")
            runOnUiThread {
                Toast.makeText(this, if (ok.getOrDefault(false)) "Signed in" else "Signed in on device only", Toast.LENGTH_LONG).show()
                showTab(AppTab.SETTINGS)
            }
            ContentSync.schedule(applicationContext, forceContent = true)
        }.start()
    }

    /**
     * Play requires an in-app way to delete the account. The pet on this phone survives — only the
     * server copy (account, pets, history) is removed.
     */
    private fun confirmDeleteAccount() {
        android.app.AlertDialog.Builder(this, R.style.Theme_Ghostly_Dialog)
            .setTitle(getString(R.string.delete_your_account))
            .setMessage(
                "This removes your account and everything saved on the server: your pets, their " +
                    "stats and history. The pet on this phone stays, but he'll no longer sync."
            )
            .setNegativeButton(getString(R.string.keep_it), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                Thread {
                    val ok = runCatching { GhostlyApi.deleteAccount(applicationContext) }.getOrDefault(false)
                    runOnUiThread {
                        if (ok) {
                            Prefs.saveSignedInUser(this, null, null)
                            Toast.makeText(this, getString(R.string.account_deleted), Toast.LENGTH_LONG).show()
                            showTab(AppTab.SETTINGS)
                        } else {
                            Toast.makeText(this, getString(R.string.couldn_t_reach_the_server_try_again_later), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .show()
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
            Toast.makeText(this, getString(R.string.find_ghostly_and_allow_it_to_run_in_the_back), Toast.LENGTH_LONG).show()
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
            .onFailure { Toast.makeText(this, getString(R.string.open_settings_apps_ghostly), Toast.LENGTH_LONG).show() }
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
            Toast.makeText(this, getString(R.string.couldn_t_create_the_share_card), Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderPetCard(): Bitmap {
        val w = dp(360)
        val h = dp(420)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ink)

        val ghostSize = dp(180)
        // Measured taller than wide, the same as the in-app previews: his body is sized off the
        // width and sits at the bottom, so the spare height is what a pair of antlers is drawn
        // into. Rendered square they are sliced off flat at the top of the bitmap.
        val ghostHeight = ghostSize * 13 / 10
        val view = GhostView(this)
        view.species = Prefs.species(this)
        val s = Emotions.snapshot(this)
        view.setMood(s.mood, s.body.sleeping)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(ghostSize, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ghostHeight, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, ghostSize, ghostHeight)
        // Lifted by however far down its own view the body now sits, so he lands exactly where he
        // did when the render was square and the name below him does not have to move.
        val bodyTop = (ghostHeight - GhostView.haloPadPx(ghostSize) - ghostSize).coerceAtLeast(0)
        canvas.save()
        canvas.translate((w - ghostSize) / 2f, dp(36).toFloat() - bodyTop)
        view.draw(canvas)
        canvas.restore()

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(22).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = uiMedium
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

    /**
     * A new Ghostly is on Play. Offered rather than imposed: it downloads in the background while
     * you carry on, and the notification picks it up if you leave before it lands.
     */
    private fun offerUpdate(start: () -> Unit) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this, R.style.Theme_Ghostly_Dialog)
            .setTitle(getString(R.string.a_new_ghostly))
            .setMessage(
                "There's a newer version on the Play Store. It downloads in the background — " +
                    "he keeps floating while it does."
            )
            .setNegativeButton(getString(R.string.not_now)) { _, _ -> AppUpdates.snooze(this) }
            .setPositiveButton(getString(R.string.update)) { _, _ -> start() }
            .show()
    }

    /**
     * Heavily customised Android skins gate the floating overlay behind their OWN extra switch,
     * separate from stock Android's "Display over other apps" screen — granting that alone isn't
     * always enough on these phones, and there's no single API to detect or fix it, so this is a
     * plain per-brand guide instead.
     */
    private fun showOverlaySettingsGuide() {
        AlertDialog.Builder(this, R.style.Theme_Ghostly_Dialog)
            .setTitle(getString(R.string.some_phones_hide_a_second_switch))
            .setMessage(
                "Heavily customised phones (Xiaomi, Oppo, Vivo, and similar) often gate the " +
                    "floating overlay behind their OWN extra permission, separate from the " +
                    "standard \"Display over other apps\" screen you already granted. If he still " +
                    "won't float, check your phone's brand:\n\n" +
                    "XIAOMI / REDMI / POCO (MIUI or HyperOS)\n" +
                    "Settings → Apps → Manage apps → Ghostly → Other permissions → turn on " +
                    "\"Display pop-up windows while running in the background\" and \"Display " +
                    "pop-up window\". Also open the Security app → Autostart, and allow Ghostly.\n\n" +
                    "SAMSUNG (One UI)\n" +
                    "Settings → Apps → Ghostly → turn on \"Allow background activity\", and set " +
                    "battery usage to \"Unrestricted\" (Optimized isn't enough).\n\n" +
                    "OPPO / REALME / ONEPLUS (ColorOS)\n" +
                    "Settings → App management → Ghostly → Battery usage → allow background " +
                    "running. Then Settings → Privacy → Permission manager → check \"Floating " +
                    "window\" is on for Ghostly.\n\n" +
                    "VIVO / IQOO (Funtouch OS / OriginOS)\n" +
                    "Settings → Battery → Background power consumption management → find Ghostly " +
                    "→ Allow. Then open i Manager → App manager → Autostart manager → enable " +
                    "Ghostly.\n\n" +
                    "ANY OTHER PHONE\n" +
                    "Search your Settings app for \"floating window\", \"pop-up window\", " +
                    "\"display over other apps\", or \"overlay\" — there's almost always a second " +
                    "copy of this permission hiding somewhere in the phone-maker's own settings."
            )
            .setPositiveButton(getString(R.string.open_app_info)) { _, _ -> openAppInfo() }
            .setNegativeButton(getString(R.string.got_it), null)
            .show()
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(Prefs.name(this@MainActivity) ?: "")
            hint = getString(R.string.ghostly)
            setTextColor(Color.WHITE)
            setHintTextColor(dim)
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(18))
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        AlertDialog.Builder(this, R.style.Theme_Ghostly_Dialog)
            .setTitle(getString(R.string.what_s_his_name))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                Prefs.setName(this, input.text.toString())
                refreshNeeds()
                runCatching { GhostlyWidgetProvider.refreshAll(this) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // endregion

    // region shared ui helpers

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(Palette.textFaint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = mono
        letterSpacing = 0.16f
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

    /** Purely decorative: every icon in this app sits next to text that already says what it is. */
    private fun iconView(glyph: IconGlyph, tint: Int, sizeDp: Int) = ImageView(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setImageDrawable(IconDrawable(glyph, tint))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /**
     * One hairline meter with a small caps label above it, as an equal-width column of [container].
     * Pass [holder] to get a handle on the column itself, for the ones that come and go.
     */
    private fun addMeterColumn(
        container: LinearLayout,
        label: String,
        tint: Int = Palette.bone,
        holder: LinearLayout? = null
    ): MeterView {
        val column = (holder ?: LinearLayout(this)).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (container.childCount > 0) marginStart = dp(12)
            }
        }
        column.addView(TextView(this).apply {
            text = label.uppercase()
            setTextColor(Palette.textFaint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            letterSpacing = 0.14f
            typeface = uiMedium
        })
        val meter = MeterView(this).apply {
            setTint(tint)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(7) }
        }
        column.addView(meter)
        container.addView(column)
        return meter
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
            // The card is the button. Announced as one thing, so a screen reader says "Feed" once
            // rather than walking an icon and a label that mean nothing apart.
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
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
        refreshAccountSection()
        val canOverlay = Settings.canDrawOverlays(this)
        val floating = GhostOverlayService.isRunning
        lastKnownRunning = floating
        // One ghost: the box empties the moment he goes out, and refills when he comes home —
        // wearing whatever was chosen for him while he was away.
        if (::playground.isInitialized) {
            playground.applyLook()
            if (!handingOver) playground.setAway(floating && !visiting)
        }

        statusLabel.visibility = if (canOverlay && floating && !visiting) View.GONE else View.VISIBLE
        statusLabel.text = when {
            !canOverlay -> "Ghostly needs the \"Display over other apps\" permission to leave this screen."
            visiting -> "He's popped into the box — he drifts back out on his own."
            floating -> "Floating now — go open any app, he's still there."
            else -> "Ready when you are."
        }

        primaryButton.text = when {
            !canOverlay -> "Grant permission"
            floating -> "Call him home"
            else -> "Let him float"
        }
        // Sending him out is the primary act, so it is bone; calling him home is the quiet inverse.
        // Red stays reserved for anger.
        primaryButton.background = if (floating) {
            rounded(Palette.glass, dp(20).toFloat(), Palette.glassStroke)
        } else {
            rounded(Palette.bone, dp(20).toFloat())
        }
        primaryButton.setTextColor(if (floating) Palette.bone else Palette.ink)

        blockedCard.visibility = if (canOverlay) View.GONE else View.VISIBLE

        // Feeding, playing and petting always happen inside the box — that is the whole rule — but
        // they no longer wait for him to be called home: tapping one brings him in for a moment.
        homeActionsRow?.let { row ->
            row.alpha = 1f
            for (i in 0 until row.childCount) row.getChildAt(i).isEnabled = true
        }

        val currentSize = Prefs.sizeDp(this)
        val speciesNow = Prefs.species(this)
        sizeTiles.forEach { (sizeDp, tile) -> optionTileState(tile, sizeDp == currentSize, speciesNow) }
        val currentShade = Prefs.shade(this)
        shadeTiles.forEach { (shade, tile) -> optionTileState(tile, shade == currentShade, speciesNow) }

        val currentSpecies = Prefs.species(this)
        speciesButtons.forEach { (species, button) -> stylePickerState(button, species == currentSpecies) }
        // Posted, not immediate: on the first pass through here the row has not been laid out yet,
        // so the button has no position to scroll to.
        speciesButtons.firstOrNull { it.first == currentSpecies }?.second?.let { selected ->
            speciesScroller?.post {
                val scroller = speciesScroller ?: return@post
                scroller.smoothScrollTo(
                    (selected.left - (scroller.width - selected.width) / 2).coerceAtLeast(0), 0
                )
            }
        }

        refreshNeeds()
    }

    private fun stylePickerState(button: Button, selected: Boolean) {
        button.background = rounded(
            if (selected) accent else card,
            dp(14).toFloat(),
            if (selected) accent else cardStroke
        )
        button.setTextColor(if (selected) Palette.ink else dim)
    }

    private fun refreshNeeds() {
        val s = Emotions.snapshot(this)
        playground.setMood(s.mood, s.body.sleeping)
        animateProgress(hungerBar, s.body.hunger.toInt())
        animateProgress(energyBar, s.body.energy.toInt())
        animateProgress(happinessBar, s.body.happiness.toInt())
        animateProgress(angerBar, s.anger.toInt())
        hungerMeter.setValue(s.body.hunger / 100f)
        energyMeter.setValue(s.body.energy / 100f)
        happinessMeter.setValue(s.body.happiness / 100f)
        angerMeter.setValue(s.anger / 100f)
        // A calm ghost has no temper to show, so the column is simply not there.
        temperColumn.visibility = if (s.anger >= SHOW_TEMPER_ABOVE) View.VISIBLE else View.GONE
        restLabel.text = if (s.body.sleeping) "Wake him up" else "Let him nap"

        val name = petName()
        // With the meters gone this line is the whole readout, so it says the most pressing thing
        // first: asleep, then angry, then hungry, then tired, then sad, then contentment.
        moodLabel.text = when {
            GhostOverlayService.isRunning && !visiting -> "somewhere over your apps"
            s.body.sleeping -> "fast asleep"
            s.mood == Mood.ANGRY -> "cross with you — a gift might help"
            s.body.hunger < PetStats.HUNGRY_THRESHOLD -> "peckish, and looking at you"
            s.body.energy < 25f -> "worn out"
            s.mood == Mood.SAD -> "a little down"
            s.body.happiness > 85f -> "delighted with everything"
            else -> "content, and watching you"
        }
        nameLabel.text = if (Prefs.name(this) == null) "Tap to name him" else name
        nameLabel.setTextColor(if (Prefs.name(this) == null) dim else Color.WHITE)

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

    /**
     * The box hands him to the overlay. He is spawned at the exact screen point the box was
     * drawing him at, and only once the overlay is actually up does the box let go — the two
     * cross-fade on the same spot, so what you see is one ghost lifting off, not a swap.
     */
    private fun sendOutFloating() {
        // Freeze him first: the overlay takes a moment to come up, and he must lift off from where
        // he is standing at that moment, not from where he was when the button was pressed.
        playground.holdStill()
        val from = playground.bodyScreenPos()
        handingOver = true
        if (!GhostOverlayService.start(this, from[0], from[1], playground.idleClock())) {
            handingOver = false
            playground.letGo()
            refreshState()
            return
        }
        awaitOverlay({ GhostOverlayService.isRunning }) {
            playground.setAway(true)
            handingOver = false
            refreshState()
        }
    }

    /**
     * The overlay hands him back. He flies across the screen to the middle of the box first, and
     * the box only takes him over once he has landed there — so he travels home rather than
     * vanishing from one place and appearing in another. [then] runs at the moment of hand-over.
     */
    private fun flyHome(then: () -> Unit) {
        if (!GhostOverlayService.isRunning) {
            then()
            refreshState()
            return
        }
        val target = playground.centreScreenPos()
        handingOver = true
        GhostOverlayService.comeHome(this, target[0], target[1])
        awaitOverlay({ GhostOverlayService.arrivedHome }) {
            // He has landed on the spot. The box takes over first — same place, same point in
            // his bob, full strength — and only then does the overlay fade out from underneath
            // him. In that order there is never a moment with less than one ghost on screen.
            playground.placeBodyAtScreen(target[0], target[1])
            playground.adoptIdleClock(GhostOverlayService.idleClock)
            playground.setAway(false)
            GhostOverlayService.setVisiting(this, true)
            primaryButton.postDelayed({
                then()
                handingOver = false
                refreshState()
            }, HANDOVER_OVERLAP_MS)
        }
    }

    /** Polls for a hand-off condition, and gives up rather than hanging if it never comes. */
    private fun awaitOverlay(ready: () -> Boolean, then: () -> Unit) {
        val startedAt = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (ready() || android.os.SystemClock.uptimeMillis() - startedAt > HANDOVER_TIMEOUT_MS) {
                    then()
                    return
                }
                primaryButton.postDelayed(this, HANDOVER_POLL_MS)
            }
        }
        step.run()
    }

    /**
     * Brings him off the overlay and into the box for a moment so he can be fed, played with or
     * petted — every one of which happens inside the box and nowhere else, floating or not. He
     * goes back out on his own [VISIT_GRACE_MS] after the last thing you did to him.
     *
     * Does nothing when he is already home; then the box is simply where he lives.
     */
    private fun visitBox(then: () -> Unit = {}) {
        if (!GhostOverlayService.isRunning) {
            then()
            return
        }
        primaryButton.removeCallbacks(returnToFloating)
        if (visiting) {
            primaryButton.postDelayed(returnToFloating, VISIT_GRACE_MS)
            then()
            return
        }
        visiting = true
        flyHome {
            GhostOverlayService.setVisiting(this, true)
            primaryButton.postDelayed(returnToFloating, VISIT_GRACE_MS)
            then()
        }
    }

    /** Ends a visit early — leaving Home, or leaving the app, sends him straight back out. */
    private fun endVisit() {
        if (!visiting) return
        visiting = false
        primaryButton.removeCallbacks(returnToFloating)
        // He lifts off from wherever he is standing in the box, not from wherever he left it.
        val from = playground.bodyScreenPos()
        GhostOverlayService.setVisiting(this, false, from[0], from[1], playground.idleClock())
        playground.setAway(true)
        handingOver = false
        refreshState()
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
            Toast.makeText(this, getString(R.string.turn_ghostly_on_then_come_back), Toast.LENGTH_LONG).show()
            return
        }

        if (GhostOverlayService.isRunning) {
            // Calling him home ends any visit: he is not popping in any more, he is staying.
            visiting = false
            primaryButton.removeCallbacks(returnToFloating)
            flyHome { GhostOverlayService.stop(this) }
        } else {
            sendOutFloating()
        }
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
        // The overlay's own prefs listener resizes it live, in place — no restart needed. The box
        // has no such listener, so it is told directly.
        Prefs.setSizeDp(this, sizeDp)
        playground.applyLook()
        refreshState()
    }

    private fun chooseShade(shade: Shade) {
        Prefs.setShade(this, shade)
        playground.setShade(shade)
        shadeTiles.forEach { (s2, tile) -> optionTileState(tile, s2 == shade, Prefs.species(this)) }
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

    companion object {
        /** The update notification opens straight into finishing the install. */
        const val ACTION_FINISH_UPDATE = "com.shashank.ghostly.FINISH_UPDATE"

        private const val WATCH_INTERVAL_MS = 1_000L
        private const val WELCOME_BACK_GAP_MS = 12 * 60 * 60 * 1_000L

        /** Below this he is simply calm, and the Temper meter is not shown at all. */
        private const val SHOW_TEMPER_ABOVE = 8f

        /** How long he stays in the box after the last thing you did, before drifting back out. */
        private const val VISIT_GRACE_MS = 6_000L

        /** Long enough for the two to cross-dissolve on the same spot before the overlay lets go. */
        private const val HANDOVER_OVERLAP_MS = 210L

        /** A hand-off that never completes must not leave the screen stuck mid-move. */
        private const val HANDOVER_TIMEOUT_MS = 3_000L

        /**
         * How often the hand-over is checked for. A frame, not the old forty milliseconds: this is
         * dead time between him landing and the box taking him over, and two and a half frames of
         * it is long enough to see as a pause at the end of the flight.
         */
        private const val HANDOVER_POLL_MS = 16L
    }

    // endregion
}
