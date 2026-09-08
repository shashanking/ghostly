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

    /** Waking him up always leaves at least this much energy in the tank — without this, waking
     *  him early (energy still at MIN, which is *why* he was asleep) would satisfy the auto-sleep
     *  condition again on the very next stats catch-up, sending him straight back to sleep a
     *  second or two later and making "wake him up" look like it does nothing. */
    private const val WAKE_ENERGY_FLOOR = 20f

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

        if (last == 0L) {
            // First read ever for this pet — there's no real elapsed time to catch up on yet, only
            // an anchor to persist so the *next* read measures against a real moment instead of
            // "now" forever (which would mean he never ages a second, no matter how long the app
            // stays closed).
            Prefs.saveStats(context, hunger, energy, happiness, sleeping, now)
            return Snapshot(hunger, energy, happiness, sleeping)
        }

        val elapsedSeconds = (now - last) / 1000f
        if (elapsedSeconds >= 1f) {
            val e = elapsedSeconds.coerceAtMost(MAX_CAUGHT_UP_SECONDS)
            hunger = (hunger - HUNGER_RATE * personality.hungerRate * e).coerceIn(MIN, MAX)

            // Energy (and with it, sleep) is simulated in at most two segments rather than
            // picking a single rate for the whole gap — otherwise a long-enough absence either
            // pins him at 0 energy forever (awake the entire time, even past the point he'd have
            // fallen asleep and started recovering) or skips a wake-up he'd genuinely have had
            // (asleep the entire time, even past the point he'd have woken and started draining
            // again). A third transition inside one gap is left unmodelled — rare enough, and
            // the next real catch-up corrects it anyway.
            if (sleeping) {
                val alreadyAsleep = ((last - sleepStartedAt) / 1000f).coerceAtLeast(0f)
                val minNapRemaining = (MIN_NAP_MILLIS / 1000f - alreadyAsleep).coerceAtLeast(0f)
                val fillNeeded = (WAKE_ENERGY_THRESHOLD - energy).coerceAtLeast(0f)
                val fillTime = if (fillNeeded <= 0f) 0f else fillNeeded / ENERGY_ASLEEP_RATE
                val sleepSegment = maxOf(minNapRemaining, fillTime).coerceAtMost(e)
                energy = (energy + ENERGY_ASLEEP_RATE * sleepSegment).coerceIn(MIN, MAX)
                val awakeSegment = e - sleepSegment
                if (awakeSegment > 0f) {
                    sleeping = false
                    energy = (energy - ENERGY_AWAKE_RATE * personality.energyRate * awakeSegment).coerceIn(MIN, MAX)
                    if (energy <= MIN) {
                        sleeping = true
                        sleepStartedAt = now
                    }
                }
            } else {
                val drainNeeded = (energy - MIN).coerceAtLeast(0f)
                val drainTime = if (drainNeeded <= 0f) 0f else drainNeeded / (ENERGY_AWAKE_RATE * personality.energyRate)
                val awakeSegment = drainTime.coerceAtMost(e)
                energy = (energy - ENERGY_AWAKE_RATE * personality.energyRate * awakeSegment).coerceIn(MIN, MAX)
                val sleepSegment = e - awakeSegment
                if (sleepSegment > 0f) {
                    sleeping = true
                    sleepStartedAt = now - (sleepSegment * 1000).toLong()
                    energy = (energy + ENERGY_ASLEEP_RATE * sleepSegment).coerceIn(MIN, MAX)
                    if (energy >= WAKE_ENERGY_THRESHOLD && sleepSegment >= MIN_NAP_MILLIS / 1000f) {
                        sleeping = false
                    }
                }
            }

            // Going hungry drags his mood down faster than idle time alone would.
            val neglect = if (hunger <= HUNGRY_THRESHOLD) 1.6f else 1f
            happiness = (happiness - HAPPINESS_RATE * neglect * e).coerceIn(MIN, MAX)

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
        // Waking him up (by request or by a poke) needs a floor under his energy, or the very
        // next stats catch-up sees it still at MIN and immediately puts him back to sleep.
        val energy = if (sleeping) s.energy else s.energy.coerceAtLeast(WAKE_ENERGY_FLOOR)
        Prefs.saveStats(context, s.hunger, energy, s.happiness, sleeping, System.currentTimeMillis())
    }

    /** A tap or poke while he's asleep stirs him awake. */
    fun wake(context: Context) = setSleeping(context, false)
}
