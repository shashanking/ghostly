package com.shashank.ghostly

/**
 * How many days in a row the app has been opened. Purely a habit hook — it doesn't feed back into
 * his needs or mood, it's just something to keep going.
 */
object Streak {

    /** Call once per app open (see [MainActivity.onResume]). Extends the streak if today follows
     *  the last counted day, holds it if today was already counted, and resets to 1 otherwise. */
    fun touch(context: android.content.Context): Int {
        val today = epochDay()
        val lastDay = Prefs.streakDay(context)
        val current = Prefs.streak(context)
        val updated = when (today) {
            lastDay -> current
            lastDay + 1 -> current + 1
            else -> 1
        }
        if (updated != current || lastDay != today) Prefs.saveStreak(context, updated, today)
        return updated
    }
}
