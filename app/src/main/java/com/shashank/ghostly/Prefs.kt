package com.shashank.ghostly

import android.content.Context
import android.content.SharedPreferences

/** Today, as a UTC day count — good enough for "was that today or a different day", the only
 *  question the daily allowance and streak ever ask. */
fun epochDay(): Long = System.currentTimeMillis() / 86_400_000L

/** Tiny wrapper around the app's SharedPreferences. */
object Prefs {
    private const val FILE = "ghostly"

    /**
     * Credentials live in their own file so backup can leave them out. Android's auto-backup works
     * a whole prefs file at a time — there is no way to exclude one key — and a bearer token that
     * is still valid for a month has no business being copied into cloud backup and restored onto
     * some other device.
     */
    private const val SESSION_FILE = "ghostly-session"
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
    private const val KEY_UPDATE_PROMPTED_AT = "update_prompted_at"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_DISPLAY_NAME = "user_display_name"
    private const val KEY_FED_AT = "fed_at"
    private const val KEY_SHADE = "shade"
    private const val KEY_UNLOCKS_TODAY = "unlocks_today"
    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_PET_SERVER_ID = "pet_server_id"
    private const val KEY_LAST_SYNCED_STATS_AT = "last_synced_stats_at"
    private const val KEY_CONTENT_VERSION = "content_version"
    private const val KEY_CONTENT_SYNCED_AT = "content_synced_at"
    private const val KEY_PENDING_EVENTS = "pending_events"
    private const val KEY_DAILY = "daily"
    private const val KEY_UNLOCKS_DAY = "unlocks_day"
    private const val KEY_STREAK_LONGEST = "streak_longest"
    private const val KEY_STREAK_FREEZES = "streak_freezes"
    private const val KEY_STREAK_FREEZE_WEEK = "streak_freeze_week"
    private const val KEY_WELCOME_TOKENS = "welcome_tokens_given"
    private const val KEY_OWNED = "owned_items"
    private const val KEY_NOTIFY_STREAK = "notify_streak"
    private const val KEY_NOTIFY_MOOD = "notify_mood"
    private const val KEY_NOTIFY_MISSING = "notify_missing"
    private const val KEY_LAST_CARE_AT = "last_care_at"

    /** What he has on, one key per slot — see [Outfit]. Public so the overlay can listen for them. */
    const val KEY_WEAR_HEAD = "wear_head"
    const val KEY_WEAR_FACE = "wear_face"
    const val KEY_WEAR_NECK = "wear_neck"
    const val KEY_WEAR_EMOJI = "wear_emoji"
    val OUTFIT_KEYS = setOf(KEY_WEAR_HEAD, KEY_WEAR_FACE, KEY_WEAR_NECK, KEY_WEAR_EMOJI)

    /** Starting point for a freshly installed pet — content, but with room to grow or fade. */
    private const val DEFAULT_STAT = 80f

    const val SIZE_MIN = 16
    const val SIZE_MAX = 72
    const val SIZE_DEFAULT = 36

    /** The three sizes he comes in, as named options rather than a slider. */
    const val SIZE_WISP = 30
    const val SIZE_SPOOK = 36
    const val SIZE_HAUNT = 52

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

    /** The three named sizes, smallest first. */
    val SIZES = listOf(SIZE_WISP, SIZE_SPOOK, SIZE_HAUNT)

    fun sizeDp(context: Context): Int {
        val stored = prefs(context).getInt(KEY_SIZE, SIZE_DEFAULT)
        // Size is a choice of three now, not a slider. A value that is not one of them came from
        // the slider this replaced, so it is treated as never having been chosen: everyone starts
        // at Spook, and the picker always has exactly one option lit.
        return if (stored in SIZES) stored else SIZE_DEFAULT
    }

    fun setSizeDp(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SIZE, value.coerceIn(SIZE_MIN, SIZE_MAX)).apply()

    /** A hue in degrees [0, 360) the whole body is rotated to, or null for his original colours —
     *  see [GhostView.setTint]. */
    /**
     * Always null: he is monochrome now, and [Shade] carries the choice that hue used to.
     *
     * A hue stored by an older build would otherwise leave someone with, say, a green ghost and no
     * control left to clear it — the swatches that set it are gone. So the first read after the
     * update drops the key for good.
     */
    fun colorHue(context: Context): Float? {
        val store = prefs(context)
        if (store.contains(KEY_COLOR_HUE)) store.edit().remove(KEY_COLOR_HUE).apply()
        return null
    }

    fun setColorHue(context: Context, hue: Float?) =
        prefs(context).edit().putFloat(KEY_COLOR_HUE, hue ?: -1f).apply()

    /**
     * When true (the default) the ghost is intangible outside the app: every touch goes straight
     * to whatever is underneath him and he never blocks a button or a keyboard key. He still
     * notices taps — Android just will not tell an overlay *where* an outside tap landed, so in
     * this mode he cannot be poked precisely or dragged.
     *
     * When false he is solid outside the app too: he can be tapped, dragged and held to pet out
     * there, at the cost of a small halo around him swallowing whatever is underneath it. See
     * [GhostOverlayService] for why flipping this tears the overlay window down and puts up a
     * fresh one rather than updating it in place.
     */
    fun clickThrough(context: Context): Boolean = prefs(context).getBoolean(KEY_CLICK_THROUGH, true)

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

    /** Epoch day the daily tokens were last paid in. 0 means "never". Written as a UTC day by older
     *  builds and a local one since; the two never differ by more than a day, once. */
    fun tokensGrantedDay(context: Context) = prefs(context).getLong(KEY_TOKENS_GRANTED_DAY, 0L)

    fun saveTokensGrantedDay(context: Context, day: Long) =
        prefs(context).edit().putLong(KEY_TOKENS_GRANTED_DAY, day).apply()

    /**
     * The streak's whole memory. The count and its day keep the keys the old open-the-app streak
     * used, so a streak that was going carries straight on into this one.
     */
    fun streakState(context: Context): StreakState {
        val p = prefs(context)
        val current = p.getInt(KEY_STREAK, 0)
        return StreakState(
            current = current,
            longest = maxOf(p.getInt(KEY_STREAK_LONGEST, 0), current),
            lastDay = p.getLong(KEY_STREAK_DAY, 0L),
            freezes = p.getInt(KEY_STREAK_FREEZES, StreakRules.FREEZES_PER_WEEK),
            freezeWeek = p.getLong(KEY_STREAK_FREEZE_WEEK, 0L),
        )
    }

    fun saveStreakState(context: Context, s: StreakState) {
        prefs(context).edit()
            .putInt(KEY_STREAK, s.current)
            .putInt(KEY_STREAK_LONGEST, s.longest)
            .putLong(KEY_STREAK_DAY, s.lastDay)
            .putInt(KEY_STREAK_FREEZES, s.freezes)
            .putLong(KEY_STREAK_FREEZE_WEEK, s.freezeWeek)
            .apply()
    }

    /** The one-off starter tokens, so the Shop is not a wall of things you can't have yet. */
    fun welcomeTokensGiven(context: Context) = prefs(context).getBoolean(KEY_WELCOME_TOKENS, false)

    fun setWelcomeTokensGiven(context: Context) =
        prefs(context).edit().putBoolean(KEY_WELCOME_TOKENS, true).apply()

    /** Ids of everything bought for keeps — see [ShopCatalog]. */
    fun ownedItems(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_OWNED, emptySet())?.toSet() ?: emptySet()

    fun addOwnedItem(context: Context, id: String) =
        prefs(context).edit().putStringSet(KEY_OWNED, ownedItems(context) + id).apply()

    fun worn(context: Context, key: String): String? = prefs(context).getString(key, null)

    fun setWorn(context: Context, key: String, id: String?) =
        prefs(context).edit().putString(key, id).apply()

    // Notifications, one switch per kind. All on unless turned off.
    fun notifyStreak(context: Context) = prefs(context).getBoolean(KEY_NOTIFY_STREAK, true)
    fun setNotifyStreak(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_NOTIFY_STREAK, v).apply()
    fun notifyMood(context: Context) = prefs(context).getBoolean(KEY_NOTIFY_MOOD, true)
    fun setNotifyMood(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_NOTIFY_MOOD, v).apply()
    fun notifyMissing(context: Context) = prefs(context).getBoolean(KEY_NOTIFY_MISSING, true)
    fun setNotifyMissing(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_NOTIFY_MISSING, v).apply()

    /** The last time he was actually looked after in the app — what "he misses you" counts from.
     *  0 means never, in which case the last app open stands in for it. */
    fun lastCareAt(context: Context) = prefs(context).getLong(KEY_LAST_CARE_AT, 0L)

    fun saveLastCareAt(context: Context, at: Long) =
        prefs(context).edit().putLong(KEY_LAST_CARE_AT, at).apply()

    /** The notification engine's own bookkeeping, kept as one JSON blob — see [Nudges]. */
    fun nudgeLog(context: Context): String = prefs(context).getString("nudge_log", "{}") ?: "{}"

    fun saveNudgeLog(context: Context, json: String) =
        prefs(context).edit().putString("nudge_log", json).apply()

    /** 0 means "never opened before" — used to tell a first run from a real welcome-back. */
    fun lastOpenedAt(context: Context) = prefs(context).getLong(KEY_LAST_OPENED_AT, 0L)

    fun saveLastOpenedAt(context: Context, at: Long) =
        prefs(context).edit().putLong(KEY_LAST_OPENED_AT, at).apply()

    /** Null until the user picks one — callers fall back to the species label. */
    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    /**
     * What to call him on screen. Unnamed he goes by the app's own name rather than by what he is
     * — "Ghostly is floating" reads like a pet; "Dog ghost is floating" reads like a product.
     */
    fun displayName(context: Context): String = name(context) ?: "Ghostly"

    /** When the update offer was last put in front of the user, so it isn't put there daily. */
    fun updatePromptedAt(context: Context) = prefs(context).getLong(KEY_UPDATE_PROMPTED_AT, 0L)

    fun saveUpdatePromptedAt(context: Context, at: Long) =
        prefs(context).edit().putLong(KEY_UPDATE_PROMPTED_AT, at).apply()

    fun setName(context: Context, name: String?) =
        prefs(context).edit().putString(KEY_NAME, name?.trim()?.take(18)?.ifEmpty { null }).apply()

    /** 0 means "never persisted" — a fresh pet has no anchor to measure elapsed time against
     *  yet. See [PetStats.snapshot]'s first-read branch, which writes a real one immediately. */
    fun statsUpdatedAt(context: Context) = prefs(context).getLong(KEY_STATS_AT, 0L)

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

    /** When he was last fed a treat, so the overlay (running as a separate surface) can notice a
     *  feed from the app and play the same drop-and-eat animation. 0 means "never". */
    fun fedAt(context: Context) = prefs(context).getLong(KEY_FED_AT, 0L)

    /** Screen unlocks so far today (UTC day). Rolls over to zero on the first read of a new day. */
    fun unlocksToday(context: Context): Int {
        val p = prefs(context)
        return if (p.getLong(KEY_UNLOCKS_DAY, 0L) == epochDay()) p.getInt(KEY_UNLOCKS_TODAY, 0) else 0
    }

    fun recordUnlock(context: Context) {
        val p = prefs(context)
        val today = epochDay()
        val count = if (p.getLong(KEY_UNLOCKS_DAY, 0L) == today) p.getInt(KEY_UNLOCKS_TODAY, 0) else 0
        p.edit().putLong(KEY_UNLOCKS_DAY, today).putInt(KEY_UNLOCKS_TODAY, count + 1).apply()
    }

    fun shade(context: Context): Shade = Shade.fromId(prefs(context).getString(KEY_SHADE, null))

    fun setShade(context: Context, shade: Shade) =
        prefs(context).edit().putString(KEY_SHADE, shade.id).apply()

    private fun epochDay(): Long = System.currentTimeMillis() / 86_400_000L

    // ---- server side ------------------------------------------------------------------------

    private fun session(context: Context): SharedPreferences {
        val file = context.getSharedPreferences(SESSION_FILE, Context.MODE_PRIVATE)
        // Anyone signed in before this file existed still has their token in the main one. Move it
        // across on first read, then take it out of the file that gets backed up.
        val stale = prefs(context)
        if (stale.contains(KEY_SESSION_TOKEN)) {
            file.edit()
                .putString(KEY_SESSION_TOKEN, stale.getString(KEY_SESSION_TOKEN, null))
                .putString(KEY_USER_ID, stale.getString(KEY_USER_ID, null))
                .putString(KEY_PET_SERVER_ID, stale.getString(KEY_PET_SERVER_ID, null))
                .apply()
            stale.edit().remove(KEY_SESSION_TOKEN).remove(KEY_USER_ID).remove(KEY_PET_SERVER_ID).apply()
        }
        return file
    }

    fun sessionToken(context: Context): String? = session(context).getString(KEY_SESSION_TOKEN, null)
    fun userId(context: Context): String? = session(context).getString(KEY_USER_ID, null)
    fun saveSession(context: Context, token: String, userId: String?) =
        session(context).edit().putString(KEY_SESSION_TOKEN, token).putString(KEY_USER_ID, userId).apply()
    fun clearSession(context: Context) =
        session(context).edit().remove(KEY_SESSION_TOKEN).remove(KEY_USER_ID).remove(KEY_PET_SERVER_ID).apply()

    fun petServerId(context: Context): String? = session(context).getString(KEY_PET_SERVER_ID, null)
    fun savePetServerId(context: Context, id: String) =
        session(context).edit().putString(KEY_PET_SERVER_ID, id).apply()

    /** The stats timestamp last accepted by the server — see the note in [ContentSync]. */
    fun lastSyncedStatsAt(context: Context) = prefs(context).getLong(KEY_LAST_SYNCED_STATS_AT, 0L)

    fun saveLastSyncedStatsAt(context: Context, at: Long) =
        prefs(context).edit().putLong(KEY_LAST_SYNCED_STATS_AT, at).apply()

    fun contentVersion(context: Context) = prefs(context).getInt(KEY_CONTENT_VERSION, 0)
    fun saveContentVersion(context: Context, v: Int) = prefs(context).edit().putInt(KEY_CONTENT_VERSION, v).apply()
    fun contentSyncedAt(context: Context) = prefs(context).getLong(KEY_CONTENT_SYNCED_AT, 0L)
    fun saveContentSyncedAt(context: Context, at: Long) = prefs(context).edit().putLong(KEY_CONTENT_SYNCED_AT, at).apply()

    fun pendingEvents(context: Context): String = prefs(context).getString(KEY_PENDING_EVENTS, "[]") ?: "[]"
    fun savePendingEvents(context: Context, json: String) = prefs(context).edit().putString(KEY_PENDING_EVENTS, json).apply()
    fun daily(context: Context): String = prefs(context).getString(KEY_DAILY, "{}") ?: "{}"
    fun saveDaily(context: Context, json: String) = prefs(context).edit().putString(KEY_DAILY, json).apply()

    fun markFed(context: Context) =
        prefs(context).edit().putLong(KEY_FED_AT, System.currentTimeMillis()).apply()
}
