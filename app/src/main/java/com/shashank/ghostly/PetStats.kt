package com.shashank.ghostly

import android.content.Context

/**
 * Hunger, energy and happiness — the pet's needs, on a 0..100 scale each.
 *
 * They decay against real elapsed time rather than a running clock, so a value read after the app
 * (and the overlay) have been closed for a day reflects that whole day, not just the time since
 * something was last on screen. Every read "catches up" the stored numbers to now before handing
 * them back; a write from that catch-up is skipped for gaps under a second, which also keeps a
 * [Prefs] change listener reacting to it from looping back into itself.
 */
object PetStats {
    const val MAX = 100f
    const val MIN = 0f

    // Time for a neglected stat to run from full to empty (or, for energy asleep, empty to the
    // wake threshold), left entirely alone.
    private const val HUNGER_EMPTY_HOURS = 10f
    private const val ENERGY_AWAKE_EMPTY_HOURS = 14f
    private const val ENERGY_ASLEEP_FILL_HOURS = 3f
    private const val HAPPINESS_EMPTY_HOURS = 20f

    private const val HUNGER_RATE = MAX / (HUNGER_EMPTY_HOURS * 3600f)
    private const val ENERGY_AWAKE_RATE = MAX / (ENERGY_AWAKE_EMPTY_HOURS * 3600f)
    private const val ENERGY_ASLEEP_RATE = MAX / (ENERGY_ASLEEP_FILL_HOURS * 3600f)
    private const val HAPPINESS_RATE = MAX / (HAPPINESS_EMPTY_HOURS * 3600f)

    /** A gap longer than this (phone off for days) still only counts as this much decay. */
    private const val MAX_CAUGHT_UP_SECONDS = 60f * 60f * 24f * 3f

    const val HUNGRY_THRESHOLD = 25f
    const val SAD_THRESHOLD = 30f
    const val WAKE_ENERGY_THRESHOLD = 55f

    /** A nap always lasts at least this long before the energy-threshold check can end it — without
     *  this, telling him to nap while energy is already above the threshold (the common case) would
     *  wake him again on the very next tick, a second or so later. */
    private const val MIN_NAP_MILLIS = 120_000L

    data class Snapshot(val hunger: Float, val energy: Float, val happiness: Float, val sleeping: Boolean)

    /**
     * Bring the stored values up to date with real elapsed time, and return them. [personality]
     * scales how fast hunger and energy move — a low-maintenance species drains slower, a needy
     * one faster. It says nothing about mood; that's [Emotions]' job, layered on top of this.
     */
    fun snapshot(context: Context, personality: Personality = Personality.NEUTRAL): Snapshot {
        val now = System.currentTimeMillis()
        val last = Prefs.statsUpdatedAt(context)
        var hunger = Prefs.hunger(context)
        var energy = Prefs.energy(context)
        var happiness = Prefs.happiness(context)
        var sleeping = Prefs.sleeping(context)
        var sleepStartedAt = Prefs.sleepStartedAt(context)

        val elapsedSeconds = (now - last) / 1000f
        if (elapsedSeconds >= 1f) {
            val e = elapsedSeconds.coerceAtMost(MAX_CAUGHT_UP_SECONDS)
            hunger = (hunger - HUNGER_RATE * personality.hungerRate * e).coerceIn(MIN, MAX)
            energy = if (sleeping) {
                (energy + ENERGY_ASLEEP_RATE * e).coerceIn(MIN, MAX)
            } else {
                (energy - ENERGY_AWAKE_RATE * personality.energyRate * e).coerceIn(MIN, MAX)
            }
            // Going hungry drags his mood down faster than idle time alone would.
            val neglect = if (hunger <= HUNGRY_THRESHOLD) 1.6f else 1f
            happiness = (happiness - HAPPINESS_RATE * neglect * e).coerceIn(MIN, MAX)

            if (!sleeping && energy <= MIN) {
                sleeping = true
                sleepStartedAt = now
            }
            if (sleeping && energy >= WAKE_ENERGY_THRESHOLD && now - sleepStartedAt >= MIN_NAP_MILLIS) {
                sleeping = false
            }

            Prefs.saveStats(context, hunger, energy, happiness, sleeping, now)
            if (sleepStartedAt != Prefs.sleepStartedAt(context)) Prefs.setSleepStartedAt(context, sleepStartedAt)
        }
        return Snapshot(hunger, energy, happiness, sleeping)
    }

    fun feed(context: Context) {
        val s = snapshot(context)
        val hunger = (s.hunger + 35f).coerceAtMost(MAX)
        val happiness = (s.happiness + 4f).coerceAtMost(MAX)
        Prefs.saveStats(context, hunger, s.energy, happiness, s.sleeping, System.currentTimeMillis())
    }

    /** Returns false, changing nothing, if he's asleep or too worn out to play. */
    fun play(context: Context): Boolean {
        val s = snapshot(context)
        if (s.sleeping || s.energy < 10f) return false
        val energy = (s.energy - 8f).coerceAtLeast(MIN)
        val happiness = (s.happiness + 18f).coerceAtMost(MAX)
        Prefs.saveStats(context, s.hunger, energy, happiness, s.sleeping, System.currentTimeMillis())
        return true
    }

    fun setSleeping(context: Context, sleeping: Boolean) {
        val s = snapshot(context)
        if (sleeping && !s.sleeping) Prefs.setSleepStartedAt(context, System.currentTimeMillis())
        Prefs.saveStats(context, s.hunger, s.energy, s.happiness, sleeping, System.currentTimeMillis())
    }

    /** A tap or poke while he's asleep stirs him awake. */
    fun wake(context: Context) = setSleeping(context, false)
}
