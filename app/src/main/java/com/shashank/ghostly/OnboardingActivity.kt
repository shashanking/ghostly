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
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * First run: splash, a short tutorial, Google sign-in, choosing a character, granting the
 * permissions the overlay actually needs, then a final launch. Every later app open lands here
 * too, briefly — [Prefs.onboardingComplete] is what decides whether that's a real splash or an
 * instant hand-off to [MainActivity].
 */
class OnboardingActivity : Activity() {

    private enum class Step { SPLASH, TUTORIAL_INTRO, TUTORIAL_ABILITIES, TUTORIAL_CONTROLS, TUTORIAL_MOOD, LOGIN, AVATAR, PERMISSIONS, LAUNCH }

    private data class TutorialPage(val glyph: IconGlyph, val title: String, val body: String)

    private val tutorialPages = listOf(
        TutorialPage(
            IconGlyph.HOME, "What is Ghostly?",
            "A tiny translucent companion who lives on your screen. He drifts, blinks and reacts " +
                "to you like a real pet — not just an icon you glance at."
        ),
        TutorialPage(
            IconGlyph.STYLE, "What he can do",
            "He floats above your home screen and every other app, gets hungry and tired, has real " +
                "moods, plays fetch, and remembers how you've treated him."
        ),
        TutorialPage(
            IconGlyph.PLAY, "How to control him",
            "Tap him and he bolts. Hold still on him and he settles in for a pet. Feed, Play and " +
                "Nap are always one tap away — Play and treats spend a small daily token allowance."
        ),
        TutorialPage(
            IconGlyph.HAPPINESS, "Mood & behaviour",
            "Neglect him too long and he gets genuinely upset — restless, a red glow, a shorter " +
                "fuse. A treat or a gift wins him back. Each species carries themselves differently."
        )
    )

    private lateinit var root: FrameLayout
    private var currentStep = Step.SPLASH
    private var selectedSpecies = Species.GHOST
    private val speciesCards = mutableListOf<Pair<Species, LinearLayout>>()
    private val idleCallbacks = mutableListOf<Choreographer.FrameCallback>()
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.onboardingComplete(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        root = FrameLayout(this).apply { setBackgroundColor(Palette.ink) }
        setContentView(root)
        goTo(Step.SPLASH)
    }

    override fun onResume() {
        super.onResume()
        // Permission grants happen in an external Settings screen; re-check on the way back.
        if (currentStep == Step.PERMISSIONS) goTo(Step.PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (currentStep == Step.PERMISSIONS) goTo(Step.PERMISSIONS)
    }

    override fun onDestroy() {
        stopAllIdlePreviews()
        activityScope.cancel()
        super.onDestroy()
    }

    // region navigation

    private fun goTo(step: Step) {
        stopAllIdlePreviews()
        speciesCards.clear()
        currentStep = step
        root.removeAllViews()
        root.addView(
            when (step) {
                Step.SPLASH -> buildSplash()
                Step.TUTORIAL_INTRO -> buildTutorialPage(0)
                Step.TUTORIAL_ABILITIES -> buildTutorialPage(1)
                Step.TUTORIAL_CONTROLS -> buildTutorialPage(2)
                Step.TUTORIAL_MOOD -> buildTutorialPage(3)
                Step.LOGIN -> buildLogin()
                Step.AVATAR -> buildAvatar()
                Step.PERMISSIONS -> buildPermissions()
                Step.LAUNCH -> buildLaunch()
            }
        )
    }

    private fun nextTutorialStep(index: Int): Step = when (index) {
        0 -> Step.TUTORIAL_ABILITIES
        1 -> Step.TUTORIAL_CONTROLS
        2 -> Step.TUTORIAL_MOOD
        else -> Step.LOGIN
    }

    // endregion

    // region steps

    private fun buildSplash(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val ghost = GhostView(this)
        col.addView(ghost, LinearLayout.LayoutParams(dp(96), dp(96)))
        startIdlePreview(ghost)
        col.addView(TextView(this).apply {
            text = "Ghostly"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) }
        })
        col.addView(TextView(this).apply {
            text = "Your Ghostly is loading…"
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        })

        root.postDelayed({ if (!isFinishing && currentStep == Step.SPLASH) goTo(Step.TUTORIAL_INTRO) }, 1400)

        return FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        }
    }

    private fun buildTutorialPage(index: Int): View {
        val page = tutorialPages[index]
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(28))
        }

        column.addView(TextView(this).apply {
            text = "Skip"
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { goTo(Step.LOGIN) }
        })

        val hero = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(160)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(28)
            }
        }
        if (index == 0) {
            hero.background = rounded(Palette.card, dp(28).toFloat(), Palette.cardStroke)
            val ghost = GhostView(this)
            hero.addView(ghost, FrameLayout.LayoutParams(dp(88), dp(88)).apply { gravity = Gravity.CENTER })
            startIdlePreview(ghost)
        } else {
            hero.background = rounded(Palette.badge, dp(28).toFloat())
            hero.addView(iconView(page.glyph, Palette.accent, 64).apply {
                (this.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
            })
        }
        column.addView(hero)

        column.addView(TextView(this).apply {
            text = page.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) }
        })
        column.addView(TextView(this).apply {
            text = page.body
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(4).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        })

        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) }
        }
        tutorialPages.indices.forEach { i ->
            dots.addView(View(this).apply {
                background = rounded(if (i == index) Palette.accent else Palette.cardStroke, dp(4).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(if (i == index) 20 else 8), dp(8)).apply {
                    if (i > 0) marginStart = dp(6)
                }
            })
        }
        column.addView(dots)

        val isLast = index == tutorialPages.lastIndex
        column.addView(Button(this).apply {
            text = if (isLast) "Get Started" else "Next"
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = gradientRounded(Palette.accent, Palette.accentDeep, dp(18).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(24) }
            setOnClickListener { goTo(nextTutorialStep(index)) }
            addPressBounce(this)
        })

        return ScrollView(this).apply { addView(column) }
    }

    private fun buildLogin(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(48), dp(32), dp(32))
        }

        val hero = FrameLayout(this).apply {
            background = rounded(Palette.card, dp(28).toFloat(), Palette.cardStroke)
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(140))
        }
        val ghost = GhostView(this)
        hero.addView(ghost, FrameLayout.LayoutParams(dp(76), dp(76)).apply { gravity = Gravity.CENTER })
        startIdlePreview(ghost)
        column.addView(hero)

        column.addView(TextView(this).apply {
            text = "Welcome to Ghostly"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(24) }
        })
        column.addView(TextView(this).apply {
            text = "Sign in with Google so this Ghostly stays yours."
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        })

        column.addView(Button(this).apply {
            text = "Continue with Google"
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(Color.WHITE, dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { topMargin = dp(32) }
            setOnClickListener { signInWithGoogle() }
            addPressBounce(this)
        })

        column.addView(TextView(this).apply {
            text = "Skip for now"
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), dp(16), dp(8), dp(8))
            setOnClickListener { goTo(Step.AVATAR) }
        })

        return ScrollView(this).apply { addView(column) }
    }

    private fun signInWithGoogle() {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(GOOGLE_WEB_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

        activityScope.launch {
            try {
                val response = CredentialManager.create(this@OnboardingActivity)
                    .getCredential(this@OnboardingActivity, request)
                val credential = response.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleId = GoogleIdTokenCredential.createFrom(credential.data)
                    Prefs.saveSignedInUser(this@OnboardingActivity, googleId.id, googleId.displayName)
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Signed in as ${googleId.displayName ?: googleId.id}",
                        Toast.LENGTH_SHORT
                    ).show()
                    goTo(Step.AVATAR)
                } else {
                    Toast.makeText(this@OnboardingActivity, "That didn't look like a Google account", Toast.LENGTH_SHORT).show()
                }
            } catch (e: GetCredentialException) {
                Toast.makeText(
                    this@OnboardingActivity,
                    "Sign-in didn't complete — you can try again or skip for now",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildAvatar(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(28))
        }

        column.addView(TextView(this).apply {
            text = "Choose your Ghost"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        column.addView(TextView(this).apply {
            text = "You can change this later in Style."
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) }
        }
        Species.entries.forEachIndexed { index, species ->
            val speciesCard = buildSpeciesCard(species, leftMargin = index > 0)
            speciesCards += species to speciesCard
            row.addView(speciesCard)
        }
        column.addView(row)

        column.addView(Button(this).apply {
            text = "Continue"
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = gradientRounded(Palette.accent, Palette.accentDeep, dp(18).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(28) }
            setOnClickListener {
                Prefs.setSpecies(this@OnboardingActivity, selectedSpecies)
                goTo(Step.PERMISSIONS)
            }
            addPressBounce(this)
        })

        return ScrollView(this).apply { addView(column) }
    }

    private fun buildSpeciesCard(species: Species, leftMargin: Boolean): LinearLayout {
        val speciesCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = speciesCardBackground(species)
            layoutParams = LinearLayout.LayoutParams(0, dp(150), 1f).apply { if (leftMargin) marginStart = dp(10) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedSpecies = species
                refreshSpeciesCards()
            }
        }
        val ghost = GhostView(this).apply { this.species = species }
        speciesCard.addView(ghost, LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(4) })
        startIdlePreview(ghost)
        speciesCard.addView(TextView(this).apply {
            text = species.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        })
        addPressBounce(speciesCard)
        return speciesCard
    }

    private fun speciesCardBackground(species: Species) =
        rounded(Palette.card, dp(18).toFloat(), if (species == selectedSpecies) Palette.accent else Palette.cardStroke)

    private fun refreshSpeciesCards() {
        speciesCards.forEach { (species, view) -> view.background = speciesCardBackground(species) }
    }

    private fun buildPermissions(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(40), dp(28), dp(28))
        }

        column.addView(TextView(this).apply {
            text = "One more thing"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        column.addView(TextView(this).apply {
            text = "Ghostly needs a couple of permissions to actually float."
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        })

        val overlayGranted = Settings.canDrawOverlays(this)
        column.addView(
            permissionCard(
                IconGlyph.HOME, "Display over other apps",
                "This is the whole app — without it he can't leave this screen.",
                overlayGranted
            ) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        )

        val notifGranted = notificationsGranted()
        column.addView(
            permissionCard(
                IconGlyph.SETTINGS, "Notifications",
                "Android requires an ongoing notification while he's floating, with a Stop button.",
                notifGranted
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
            }
        )

        column.addView(Button(this).apply {
            text = "Continue"
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = gradientRounded(Palette.accent, Palette.accentDeep, dp(18).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(24) }
            isEnabled = overlayGranted
            alpha = if (overlayGranted) 1f else 0.5f
            setOnClickListener { goTo(Step.LAUNCH) }
            addPressBounce(this)
        })

        if (!overlayGranted) {
            column.addView(TextView(this).apply {
                text = "Grant \"Display over other apps\" above to continue."
                setTextColor(Palette.textFaint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
            })
        }

        return ScrollView(this).apply { addView(column) }
    }

    private fun permissionCard(
        glyph: IconGlyph,
        title: String,
        desc: String,
        granted: Boolean,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.card, dp(18).toFloat(), Palette.cardStroke)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        val badge = FrameLayout(this).apply {
            background = rounded(Palette.badge, dp(14).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        badge.addView(iconView(glyph, Palette.mint, 24).apply {
            (this.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        textCol.addView(TextView(this).apply {
            text = desc
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) }
        })
        row.addView(textCol)

        if (granted) {
            row.addView(TextView(this).apply {
                text = "Granted"
                setTextColor(Palette.mint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        } else {
            row.addView(Button(this).apply {
                text = "Grant"
                isAllCaps = false
                stateListAnimator = null
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = rounded(Palette.accent, dp(12).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(80), dp(40))
                setOnClickListener { onClick() }
                addPressBounce(this)
            })
        }
        return row
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildLaunch(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(48), dp(32), dp(32))
        }

        val hero = FrameLayout(this).apply {
            background = rounded(Palette.card, dp(28).toFloat(), Palette.cardStroke)
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(150))
        }
        val ghost = GhostView(this).apply { species = selectedSpecies }
        hero.addView(ghost, FrameLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER })
        startIdlePreview(ghost)
        column.addView(hero)

        val name = Prefs.userDisplayName(this)?.substringBefore(" ")
        column.addView(TextView(this).apply {
            text = "You're all set!"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(22) }
        })
        column.addView(TextView(this).apply {
            text = (if (name != null) "$name, tap" else "Tap") + " below to send him out into the world."
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        })

        column.addView(Button(this).apply {
            text = "Let him float"
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = gradientRounded(Palette.accent, Palette.accentDeep, dp(18).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(32) }
            setOnClickListener { finishOnboarding() }
            addPressBounce(this)
        })

        return ScrollView(this).apply { addView(column) }
    }

    private fun finishOnboarding() {
        GhostOverlayService.start(this)
        Prefs.setOnboardingComplete(this, true)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // endregion

    // region small shared helpers (deliberately not shared with MainActivity — a working file
    // shouldn't be touched just to save twenty lines)

    private fun startIdlePreview(ghost: GhostView) {
        var last = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!idleCallbacks.contains(this)) return
                val dt = if (last == 0L) 0.016f else ((frameTimeNanos - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = frameTimeNanos
                ghost.advance(dt)
                ghost.invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        idleCallbacks += callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun stopAllIdlePreviews() {
        idleCallbacks.forEach { Choreographer.getInstance().removeFrameCallback(it) }
        idleCallbacks.clear()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun gradientRounded(startColor: Int, endColor: Int, radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(startColor, endColor)
    ).apply { cornerRadius = radius }

    private fun iconView(glyph: IconGlyph, tint: Int, sizeDp: Int) = android.widget.ImageView(this).apply {
        setImageDrawable(IconDrawable(glyph, tint))
        layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

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

    // endregion

    private companion object {
        // Web-application OAuth client from the "cvs-leadgen" Google Cloud project — used only as
        // the ID token audience (serverClientId); Play Services separately checks the Android
        // client (package name + SHA-1) to confirm the calling app is legitimate.
        const val GOOGLE_WEB_CLIENT_ID = "736699818889-jio6642o3pl2c3mnok99ebvasjf2goro.apps.googleusercontent.com"
    }
}
