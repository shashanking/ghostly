package com.shashank.ghostly

import android.content.Context
import android.content.SharedPreferences

/** Today, as a UTC day count — good enough for "was that today or a different day", the only
 *  question the daily allowance and streak ever ask. */
fun epochDay(): Long = System.currentTimeMillis() / 86_400_000L

/** Tiny wrapper around the app's SharedPreferences. */
object Prefs {
    private const val FILE = "ghostly"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_SIZE = "size_dp"
    private const val KEY_COLOR_HUE = "color_hue"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_CLICK_THROUGH = "click_through"
    private const val KEY_SPECIES = "species"
    private const val KEY_HUNGER = "hunger"
    private const val KEY_ENERGY = "energy"
    private const val KEY_HAPPINESS = "happiness"
    private const val KEY_SLEEPING = "sleeping"
    private const val KEY_STATS_AT = "stats_at"
    private const val KEY_SLEEP_STARTED_AT = "sleep_started_at"
    private const val KEY_ANGER = "anger"
    private const val KEY_TOKENS = "tokens"
    private const val KEY_TOKENS_GRANTED_DAY = "tokens_granted_day"
    private const val KEY_STREAK = "streak"
    private const val KEY_STREAK_DAY = "streak_day"
    private const val KEY_LAST_OPENED_AT = "last_opened_at"
    private const val KEY_NAME = "name"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_DISPLAY_NAME = "user_display_name"

    /** Starting point for a freshly installed pet — content, but with room to grow or fade. */
    private const val DEFAULT_STAT = 80f

    const val SIZE_MIN = 16
    const val SIZE_MAX = 72
    const val SIZE_DEFAULT = 32

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The raw store, for callers that need to listen for changes (see [GhostOverlayService]). */
    fun raw(context: Context): SharedPreferences = prefs(context)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()

    fun lastX(context: Context, default: Float) = prefs(context).getFloat(KEY_X, default)

    fun lastY(context: Context, default: Float) = prefs(context).getFloat(KEY_Y, default)

    fun savePosition(context: Context, x: Float, y: Float) =
        prefs(context).edit().putFloat(KEY_X, x).putFloat(KEY_Y, y).apply()

    fun sizeDp(context: Context): Int {
        val stored = prefs(context).getInt(KEY_SIZE, SIZE_DEFAULT)
        // Guards against a stray value from a much older build's size scale, not against the
        // slider itself — the slider already clamps to [SIZE_MIN, SIZE_MAX].
        return stored.coerceIn(SIZE_MIN, SIZE_MAX)
    }

    fun setSizeDp(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SIZE, value.coerceIn(SIZE_MIN, SIZE_MAX)).apply()

    /** A hue in degrees [0, 360) the whole body is rotated to, or null for his original colours —
     *  see [GhostView.setTint]. */
    fun colorHue(context: Context): Float? {
        val value = prefs(context).getFloat(KEY_COLOR_HUE, -1f)
        return if (value < 0f) null else value
    }

    fun setColorHue(context: Context, hue: Float?) =
        prefs(context).edit().putFloat(KEY_COLOR_HUE, hue ?: -1f).apply()

    /**
     * When true the ghost is intangible: every touch goes straight to the app underneath and he
     * never blocks a button or a keyboard key. He still notices taps — Android just will not tell
     * an overlay *where* an outside tap landed, so in this mode he cannot be poked precisely or
     * dragged.
     */
    fun clickThrough(context: Context) = prefs(context).getBoolean(KEY_CLICK_THROUGH, true)

    fun setClickThrough(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLICK_THROUGH, value).apply()

    fun hapticsEnabled(context: Context) = prefs(context).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_HAPTICS, value).apply()

    fun species(context: Context): Species = Species.fromId(prefs(context).getString(KEY_SPECIES, null))

    fun setSpecies(context: Context, species: Species) =
        prefs(context).edit().putString(KEY_SPECIES, species.id).apply()

    fun hunger(context: Context) = prefs(context).getFloat(KEY_HUNGER, DEFAULT_STAT)
    fun energy(context: Context) = prefs(context).getFloat(KEY_ENERGY, DEFAULT_STAT)
    fun happiness(context: Context) = prefs(context).getFloat(KEY_HAPPINESS, DEFAULT_STAT)
    fun sleeping(context: Context) = prefs(context).getBoolean(KEY_SLEEPING, false)

    /** When the current nap began, so a fresh one can't be cancelled a second later by [PetStats]'s
     *  own energy-threshold wake check. */
    fun sleepStartedAt(context: Context) = prefs(context).getLong(KEY_SLEEP_STARTED_AT, 0L)

    fun setSleepStartedAt(context: Context, at: Long) =
        prefs(context).edit().putLong(KEY_SLEEP_STARTED_AT, at).apply()

    fun anger(context: Context) = prefs(context).getFloat(KEY_ANGER, 0f)

    fun saveAnger(context: Context, anger: Float) =
        prefs(context).edit().putFloat(KEY_ANGER, anger).apply()

    fun tokens(context: Context) = prefs(context).getInt(KEY_TOKENS, 0)

    fun saveTokens(context: Context, tokens: Int) =
        prefs(context).edit().putInt(KEY_TOKENS, tokens.coerceAtLeast(0)).apply()

    /** Epoch day (UTC) the daily token allowance was last granted. 0 means "never". */
    fun tokensGrantedDay(context: Context) = prefs(context).getLong(KEY_TOKENS_GRANTED_DAY, 0L)

    fun saveTokensGrantedDay(context: Context, day: Long) =
        prefs(context).edit().putLong(KEY_TOKENS_GRANTED_DAY, day).apply()

    fun streak(context: Context) = prefs(context).getInt(KEY_STREAK, 0)

    /** Epoch day (UTC) the streak was last extended. 0 means "never". */
    fun streakDay(context: Context) = prefs(context).getLong(KEY_STREAK_DAY, 0L)

    fun saveStreak(context: Context, streak: Int, day: Long) {
        prefs(context).edit().putInt(KEY_STREAK, streak).putLong(KEY_STREAK_DAY, day).apply()
    }

    /** 0 means "never opened before" — used to tell a first run from a real welcome-back. */
    fun lastOpenedAt(context: Context) = prefs(context).getLong(KEY_LAST_OPENED_AT, 0L)

    fun saveLastOpenedAt(context: Context, at: Long) =
        prefs(context).edit().putLong(KEY_LAST_OPENED_AT, at).apply()

    /** Null until the user picks one — callers fall back to the species label. */
    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun setName(context: Context, name: String?) =
        prefs(context).edit().putString(KEY_NAME, name?.trim()?.take(18)?.ifEmpty { null }).apply()

    /** Defaults to now, so a first-ever read sees zero elapsed time rather than a huge one. */
    fun statsUpdatedAt(context: Context) = prefs(context).getLong(KEY_STATS_AT, System.currentTimeMillis())

    fun saveStats(context: Context, hunger: Float, energy: Float, happiness: Float, sleeping: Boolean, at: Long) {
        prefs(context).edit()
            .putFloat(KEY_HUNGER, hunger)
            .putFloat(KEY_ENERGY, energy)
            .putFloat(KEY_HAPPINESS, happiness)
            .putBoolean(KEY_SLEEPING, sleeping)
            .putLong(KEY_STATS_AT, at)
            .apply()
    }

    fun onboardingComplete(context: Context) = prefs(context).getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    /** Null until the user signs in (or if they skip it — sign-in was never required to use the
     *  pet itself, only asked for once during onboarding). */
    fun userEmail(context: Context): String? = prefs(context).getString(KEY_USER_EMAIL, null)

    fun userDisplayName(context: Context): String? = prefs(context).getString(KEY_USER_DISPLAY_NAME, null)

    fun saveSignedInUser(context: Context, email: String?, displayName: String?) {
        prefs(context).edit()
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_DISPLAY_NAME, displayName)
            .apply()
    }
}
