package com.shashank.ghostly

import android.content.Context

/**
 * Each species' character sheet. The only thing that tells a ghost, a cat ghost and a dog ghost
 * apart besides their silhouette — how needy they are, how quickly neglect gets under their skin,
 * and how easily they let it go.
 */
data class Personality(
    /** Multiplies [PetStats]' hunger drain. Below 1 = eats less often. */
    val hungerRate: Float,
    /** Multiplies [PetStats]' energy drain while awake. Below 1 = tires less easily. */
    val energyRate: Float,
    /** Divides how fast anger builds under neglect. Above 1 = takes longer to truly anger him. */
    val patience: Float,
    /** Multiplies how fast anger falls, and how much a treat or gift knocks off it. Above 1 =
     *  calms down fast and is easy to win back; below 1 = holds a grudge. */
    val forgiveness: Float,
    val favoriteTreat: String
) {
    companion object {
        val NEUTRAL = Personality(hungerRate = 1f, energyRate = 1f, patience = 1f, forgiveness = 1f, favoriteTreat = "")
    }
}

object Personalities {
    val GHOST = Personality(
        hungerRate = 1f, energyRate = 1f, patience = 1f, forgiveness = 1f,
        favoriteTreat = "quiet company"
    )

    /** Independent and low-maintenance — but a grudge, once earned, is slow to shift. */
    val CAT = Personality(
        hungerRate = 0.75f, energyRate = 0.8f, patience = 1.6f, forgiveness = 0.6f,
        favoriteTreat = "a warm sunbeam"
    )

    /** Needy and quick to sulk if ignored — and just as quick to forgive a treat. */
    val DOG = Personality(
        hungerRate = 1.3f, energyRate = 1.25f, patience = 0.6f, forgiveness = 1.7f,
        favoriteTreat = "a good treat"
    )

    fun of(species: Species): Personality = when (species) {
        Species.GHOST -> GHOST
        Species.CAT -> CAT
        Species.DOG -> DOG
    }
}

enum class Mood { CONTENT, SAD, ANGRY }

/**
 * The pet's soul, sitting on top of [PetStats]' body. Where PetStats tracks hunger/energy/
 * happiness, this tracks how he *feels* about how he's been treated: an anger meter that builds
 * under sustained neglect and falls under sustained (or bought) care, shaped by his [Personality].
 * It decays against real elapsed time the same lazy way PetStats does, and it's what turns "a bit
 * sad" into "he's genuinely upset with you" if neglect drags on.
 */
object Emotions {
    private const val MAX = 100f
    private const val MIN = 0f

    /** Left neglected the whole time, how long a fully calm pet takes to reach full anger. */
    private const val ANGER_RISE_HOURS = 6f

    /** Left well-fed and happy the whole time, how long full anger takes to fall back to zero. */
    private const val ANGER_FALL_HOURS = 3f

    private val RISE_RATE = MAX / (ANGER_RISE_HOURS * 3600f)
    private val FALL_RATE = MAX / (ANGER_FALL_HOURS * 3600f)

    const val ANGRY_THRESHOLD = 55f

    /** Free-to-play: everything costs a token except Feed and letting him nap. One token each. */
    const val TREAT_COST = 1
    const val GIFT_COST = 1
    const val PLAY_COST = 1

    /** How many tokens a new day brings — see [tokens]. */
    const val DAILY_TOKENS = 5

    enum class PlayOutcome { SUCCESS, NO_TOKENS, TOO_TIRED }

    data class Snapshot(
        val body: PetStats.Snapshot,
        val anger: Float,
        val mood: Mood,
        val tokens: Int,
        val personality: Personality
    )

    /** Bring anger up to date with real elapsed time (same window PetStats just caught up over),
     *  and combine it with the body snapshot into a mood. */
    fun snapshot(context: Context): Snapshot {
        val personality = Personalities.of(Prefs.species(context))
        val lastBefore = Prefs.statsUpdatedAt(context)
        val body = PetStats.snapshot(context, personality)
        val now = System.currentTimeMillis()

        var anger = Prefs.anger(context)
        val elapsedSeconds = (now - lastBefore) / 1000f
        if (elapsedSeconds >= 1f) {
            val e = elapsedSeconds.coerceAtMost(60f * 60f * 24f * 3f)
            val neglected = body.happiness <= PetStats.SAD_THRESHOLD || body.hunger <= PetStats.HUNGRY_THRESHOLD
            anger = if (neglected) {
                (anger + RISE_RATE / personality.patience * e).coerceIn(MIN, MAX)
            } else {
                (anger - FALL_RATE * personality.forgiveness * e).coerceIn(MIN, MAX)
            }
            Prefs.saveAnger(context, anger)
        }

        val mood = when {
            anger >= ANGRY_THRESHOLD -> Mood.ANGRY
            body.happiness <= PetStats.SAD_THRESHOLD || body.hunger <= PetStats.HUNGRY_THRESHOLD -> Mood.SAD
            else -> Mood.CONTENT
        }
        return Snapshot(body, anger, mood, tokens(context), personality)
    }

    /** Catches the daily allowance up to today: a new day resets it to [DAILY_TOKENS] rather than
     *  adding to it, so tokens don't bank up over a week away — it's a daily allowance, not income. */
    fun tokens(context: Context): Int {
        val today = epochDay()
        val grantedDay = Prefs.tokensGrantedDay(context)
        if (today > grantedDay) {
            Prefs.saveTokens(context, DAILY_TOKENS)
            Prefs.saveTokensGrantedDay(context, today)
            return DAILY_TOKENS
        }
        return Prefs.tokens(context)
    }

    /** Spends one token if there is one to spend. */
    private fun spendToken(context: Context): Boolean {
        val t = tokens(context)
        if (t < 1) return false
        Prefs.saveTokens(context, t - 1)
        return true
    }

    /** A bought pick-me-up: better than a free feed, and knocks a chunk off anger. Returns false,
     *  changing nothing, if there's no token to spend. */
    fun giveTreat(context: Context): Boolean {
        if (!spendToken(context)) return false
        val s = snapshot(context)
        val hunger = (s.body.hunger + 20f).coerceAtMost(MAX)
        val happiness = (s.body.happiness + 15f).coerceAtMost(MAX)
        Prefs.saveStats(context, hunger, s.body.energy, happiness, s.body.sleeping, System.currentTimeMillis())
        Prefs.saveAnger(context, (s.anger - 10f * s.personality.forgiveness).coerceIn(MIN, MAX))
        return true
    }

    /** The real apology: a big happiness boost and the anger-reducer that actually wins him back.
     *  Returns false, changing nothing, if there's no token to spend. */
    fun giveGift(context: Context): Boolean {
        if (!spendToken(context)) return false
        val s = snapshot(context)
        val happiness = (s.body.happiness + 30f).coerceAtMost(MAX)
        Prefs.saveStats(context, s.body.hunger, s.body.energy, happiness, s.body.sleeping, System.currentTimeMillis())
        Prefs.saveAnger(context, (s.anger - 40f * s.personality.forgiveness).coerceIn(MIN, MAX))
        return true
    }

    /** Play now costs a token too — checked before spending it, so a token is never wasted on a
     *  play attempt that was going to fail anyway (asleep, or too worn out). */
    fun playWithToken(context: Context): PlayOutcome {
        if (tokens(context) < 1) return PlayOutcome.NO_TOKENS
        if (!PetStats.play(context)) return PlayOutcome.TOO_TIRED
        spendToken(context)
        return PlayOutcome.SUCCESS
    }
}
