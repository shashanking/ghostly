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
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    // The three faces, resolved once per screen.
    private val uiMedium: android.graphics.Typeface by lazy { Type.sansMedium(this) }
    private val serifFace: android.graphics.Typeface by lazy { Type.serif(this) }
    private val serifItalicFace: android.graphics.Typeface by lazy { Type.serifItalic(this) }
    private val mono: android.graphics.Typeface by lazy { Type.mono(this) }


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

    /** Only alive while the avatar step is on screen. */
    private var nameField: EditText? = null

    /** Marks the column inside a page, so the entrance animation can find it to stagger. */
    private val PAGE_COLUMN_TAG = "onboarding-column"
    private var selectedSpecies = Species.GHOST
    private val speciesCards = mutableListOf<Pair<Species, LinearLayout>>()
    private val speciesPreviews = mutableListOf<Pair<Species, GhostView>>()
    private var speciesPreviewCallback: Choreographer.FrameCallback? = null
    private val idleCallbacks = mutableListOf<Choreographer.FrameCallback>()
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.onboardingComplete(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        // A rotation or a low-memory recreation (including the round trip to the system
        // permission screen) would otherwise silently reset progress and avatar choice back to
        // the very first step.
        savedInstanceState?.getString(KEY_STEP)?.let { name ->
            runCatching { Step.valueOf(name) }.getOrNull()?.let { currentStep = it }
        }
        savedInstanceState?.getString(KEY_SPECIES)?.let { id -> selectedSpecies = Species.fromId(id) }
        root = FrameLayout(this).apply { setBackgroundColor(Palette.ink) }
        setContentView(root)
        goTo(currentStep)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STEP, currentStep.name)
        outState.putString(KEY_SPECIES, selectedSpecies.id)
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

    /**
     * Every onboarding step is centred in the screen rather than stacked from the top, so a short
     * page does not leave a screenful of empty space under it. [ScrollView.isFillViewport] is what
     * lets the column be as tall as the screen — without it the column shrinks to its content and
     * there is nothing to centre within — and a page taller than the screen still scrolls.
     */
    private fun centredPage(column: LinearLayout): View {
        column.tag = PAGE_COLUMN_TAG
        // The column keeps its own height and is centred by the frame around it. Relying on the
        // scroller to stretch it instead left every page sitting in the top half of the screen.
        val holder = FrameLayout(this).apply {
            addView(
                column,
                FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(holder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun goTo(step: Step) {
        stopAllIdlePreviews()
        speciesCards.clear()
        speciesPreviews.clear()
        speciesPreviewCallback = null
        currentStep = step
        root.removeAllViews()
        val page = (
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
        root.addView(page)
        animateIn(page)
    }

    /**
     * The step settles into place rather than appearing: the page itself fades up, and whatever it
     * is built from rises into place one item after another. Every screen in the app moves; these
     * were the only ones that did not.
     */
    private fun animateIn(page: View) {
        page.alpha = 0f
        page.animate().alpha(1f).setDuration(220L).start()

        // The rows to stagger are the children of whatever column the page is built around.
        val column = page.findViewWithTag<ViewGroup>(PAGE_COLUMN_TAG) ?: return
        val rise = dp(18).toFloat()
        var delay = 40L
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.alpha = 0f
            child.translationY = rise
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(300L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
            delay += 55L
        }
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
        col.addView(ghost, LinearLayout.LayoutParams(dp(96), dp(126)))
        startIdlePreview(ghost)
        col.addView(TextView(this).apply {
            text = getString(R.string.ghostly)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = serifFace
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) }
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.your_ghostly_is_loading)
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
            // Centred, so the page reads as one thing rather than a stack pinned to the top of a
            // mostly empty screen.
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(20), dp(28), dp(28))
        }

        column.addView(TextView(this).apply {
            text = getString(R.string.skip)
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { goTo(Step.LOGIN) }
        })

        // He is the hero of every page, not just the first: the glyph sits beside him as a badge
        // rather than standing in for him, so the thing being explained is on screen throughout.
        val hero = FrameLayout(this).apply {
            background = rounded(Palette.card, dp(30).toFloat(), Palette.cardStroke)
            layoutParams = LinearLayout.LayoutParams(dp(190), dp(190)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(40)
            }
        }
        val ghost = GhostView(this).apply {
            setBodySize(dp(96))
        }
        hero.addView(
            ghost,
            FrameLayout.LayoutParams(
                dp(96) + GhostView.bubbleSidePx(resources.displayMetrics.density, dp(96)) * 2,
                dp(96) + GhostView.headroomPx(resources.displayMetrics.density, dp(96)) +
                    GhostView.haloPadPx(dp(96)),
            ).apply { gravity = Gravity.CENTER },
        )
        startIdlePreview(ghost)
        if (index > 0) {
            hero.addView(
                iconView(page.glyph, Palette.bone, 26).apply {
                    (this.layoutParams as FrameLayout.LayoutParams).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = dp(14)
                        marginEnd = dp(14)
                    }
                },
            )
        }
        column.addView(hero)

        column.addView(TextView(this).apply {
            text = page.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
            typeface = serifFace
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(30) }
        })
        column.addView(TextView(this).apply {
            text = page.body
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(5).toFloat(), 1f)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(12)
                marginStart = dp(6)
                marginEnd = dp(6)
            }
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
            // Ink on bone. The accent is near-white in this palette, so white-on-accent — which is
            // what these buttons used to be — put white text on a white button.
            setTextColor(Palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = uiMedium
            background = gradientRounded(Palette.accent, Palette.accentDeep, dp(18).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(24) }
            setOnClickListener { goTo(nextTutorialStep(index)) }
            addPressBounce(this)
        })

        return centredPage(column)
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
        hero.addView(ghost, FrameLayout.LayoutParams(dp(76), dp(100)).apply { gravity = Gravity.CENTER })
        startIdlePreview(ghost)
        column.addView(hero)

        column.addView(TextView(this).apply {
            text = getString(R.string.welcome_to_ghostly)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = serifFace
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(24) }
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.onboarding_login_body)
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        })

        column.addView(Button(this).apply {
            text = getString(R.string.continue_with_google)
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = uiMedium
            background = rounded(Color.WHITE, dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { topMargin = dp(32) }
            setOnClickListener { signInWithGoogle() }
            addPressBounce(this)
        })

        column.addView(TextView(this).apply {
            text = getString(R.string.skip_for_now)
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setOnClickListener { goTo(Step.AVATAR) }
        })

        return centredPage(column)
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
                    // The token is what makes this an account rather than a name: exchange it for a
                    // server session in the background, then let the sync layer take it from there.
                    val idToken = googleId.idToken
                    Thread {
                        val ok = runCatching { GhostlyApi.authGoogle(applicationContext, idToken) }.getOrDefault(false)
                        if (ok) ContentSync.schedule(applicationContext, forceContent = true)
                    }.start()
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Signed in as ${googleId.displayName ?: googleId.id}",
                        Toast.LENGTH_SHORT
                    ).show()
                    goTo(Step.AVATAR)
                } else {
                    Toast.makeText(this@OnboardingActivity, getString(R.string.that_didn_t_look_like_a_google_account), Toast.LENGTH_SHORT).show()
                }
            } catch (e: GetCredentialException) {
                // The real cause (SHA-1/config mismatch, no Google account on device, R8 having
                // stripped something in a release build, etc.) matters a lot more than this toast
                // lets on — it's swallowed otherwise, which makes a Play Store-only failure
                // nearly undiagnosable. Logged, not shown, since the message is meaningless to a
                // real user.
                Log.e("GhostlyAuth", "Google sign-in failed: ${e::class.simpleName} — ${e.message}", e)
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
            text = getString(R.string.choose_your_ghost)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = serifFace
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.onboarding_avatar_hint)
            setTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        })

        // Twelve cards, so the row scrolls rather than dividing the screen twelve ways.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speciesCards.clear()
        speciesPreviews.clear()
        Species.entries.forEachIndexed { index, species ->
            val speciesCard = buildSpeciesCard(species, leftMargin = index > 0)
            speciesCards += species to speciesCard
            row.addView(speciesCard)
        }
        column.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .apply { topMargin = dp(28) }
            }
        )
        animateSelectedSpecies()

        // Naming him is what turns a floating shape into someone's ghost, so it sits right here
        // with the choice of what he is. Left blank he is simply "Ghost", and can be named later.
        column.addView(TextView(this).apply {
            text = getString(R.string.and_his_name)
            setTextColor(Palette.textFaint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.16f
            typeface = uiMedium
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(26) }
        })
        nameField = EditText(this).apply {
            hint = getString(R.string.give_him_a_name)
            setHintTextColor(Palette.textFaint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = serifFace
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(18))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background = roundedField()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setText(Prefs.name(this@OnboardingActivity).orEmpty())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        column.addView(nameField)

        column.addView(Button(this).apply {
            text = getString(R.string.action_continue)
            isAllCaps = false
            stateListAnimator = null
            // Ink on bone. The accent is near-white in this palette, so white-on-accent — which is
            // what these buttons used to be — put white text on a white button.
            setTextColor(Palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = uiMedium
            background = gradientRounded(Palette.accent, Palette.accentDeep, dp(18).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(28) }
            setOnClickListener {
                Prefs.setSpecies(this@OnboardingActivity, selectedSpecies)
                Prefs.setName(this@OnboardingActivity, nameField?.text?.toString())
                goTo(Step.PERMISSIONS)
            }
            addPressBounce(this)
        })

        return centredPage(column)
    }

    /** The one text input in the app: a glass card, same shape as everything else. */
    private fun roundedField(): GradientDrawable = GradientDrawable().apply {
        setColor(Palette.glass)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Palette.glassStroke)
    }

    private fun buildSpeciesCard(species: Species, leftMargin: Boolean): LinearLayout {
        val speciesCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = speciesCardBackground(species)
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(150)).apply {
                if (leftMargin) marginStart = dp(10)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedSpecies = species
                refreshSpeciesCards()
            }
        }
        // Taller than it is wide: GhostView sizes his body off the width and sits it at the bottom,
        // so the spare height above becomes room for ears, antlers and horns. In a square preview
        // there is only the body's own padding up there, and the tall ones come out flat-topped.
        val ghost = GhostView(this).apply { this.species = species }
        speciesPreviews += species to ghost
        speciesCard.addView(ghost, LinearLayout.LayoutParams(dp(64), dp(84)).apply { topMargin = dp(4) })
        speciesCard.addView(TextView(this).apply {
            text = species.short
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
        animateSelectedSpecies()
    }

    /**
     * Only the chosen card bobs. Twelve Choreographer chains, each invalidating its own view every
     * single frame, is a great deal of work for a page where eleven of them are not being looked
     * at — and a still ghost reads perfectly well as a portrait. The one that moves is the one he
     * is about to be, which is the selection saying so a second time.
     */
    private fun animateSelectedSpecies() {
        speciesPreviewCallback?.let { stopIdlePreview(it) }
        val chosen = speciesPreviews.firstOrNull { it.first == selectedSpecies }?.second
        speciesPreviewCallback = chosen?.let { startIdlePreview(it) }
    }

    private fun buildPermissions(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(40), dp(28), dp(28))
        }

        column.addView(TextView(this).apply {
            text = getString(R.string.one_more_thing)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = serifFace
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.onboarding_permissions_body)
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
            text = getString(R.string.action_continue)
            isAllCaps = false
            stateListAnimator = null
            // Ink on bone. The accent is near-white in this palette, so white-on-accent — which is
            // what these buttons used to be — put white text on a white button.
            setTextColor(Palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = uiMedium
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

        return centredPage(column)
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
            typeface = uiMedium
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
                text = getString(R.string.granted)
                setTextColor(Palette.mint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = uiMedium
            })
        } else {
            row.addView(Button(this).apply {
                text = getString(R.string.grant)
                isAllCaps = false
                stateListAnimator = null
                // Ink on bone. The accent is near-white, so white here is an invisible label.
                setTextColor(Palette.ink)
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
        hero.addView(ghost, FrameLayout.LayoutParams(dp(84), dp(110)).apply { gravity = Gravity.CENTER })
        startIdlePreview(ghost)
        column.addView(hero)

        val name = Prefs.userDisplayName(this)?.substringBefore(" ")
        column.addView(TextView(this).apply {
            text = getString(R.string.you_re_all_set)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = serifFace
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
            text = getString(R.string.let_him_float)
            isAllCaps = false
            stateListAnimator = null
            // Ink on bone. The accent is near-white in this palette, so white-on-accent — which is
            // what these buttons used to be — put white text on a white button.
            setTextColor(Palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = uiMedium
            background = gradientRounded(Palette.accent, Palette.accentDeep, dp(18).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(32) }
            setOnClickListener { finishOnboarding() }
            addPressBounce(this)
        })

        return centredPage(column)
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

    private fun startIdlePreview(ghost: GhostView): Choreographer.FrameCallback {
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
        return callback
    }

    private fun stopIdlePreview(callback: Choreographer.FrameCallback) {
        idleCallbacks -= callback
        Choreographer.getInstance().removeFrameCallback(callback)
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

    companion object {
        // Web-application OAuth client from the "cvs-leadgen" Google Cloud project — used only as
        // the ID token audience (serverClientId); Play Services separately checks the Android
        // client (package name + SHA-1) to confirm the calling app is legitimate.
        const val GOOGLE_WEB_CLIENT_ID = "736699818889-jio6642o3pl2c3mnok99ebvasjf2goro.apps.googleusercontent.com"

        const val KEY_STEP = "onboarding_step"
        const val KEY_SPECIES = "onboarding_species"
    }
}
