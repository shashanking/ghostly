package com.shashank.ghostly

import android.content.Context

/**
 * Each species' character sheet. Besides their silhouette and their voice, this is the whole of
 * what tells one from another — how needy they are, how quickly neglect gets under their skin,
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

    /** Grazes all day and startles at everything, but never stays cross for long. */
    val BUNNY = Personality(
        hungerRate = 1.35f, energyRate = 0.9f, patience = 0.8f, forgiveness = 1.3f,
        favoriteTreat = "something to nibble"
    )

    /** Restless and always half-scheming: hard to tire out, and keeps his own counsel. */
    val FOX = Personality(
        hungerRate = 1.1f, energyRate = 1.15f, patience = 0.9f, forgiveness = 0.9f,
        favoriteTreat = "something he stole"
    )

    /** Eats enormously, tires slowly, and takes a great deal to genuinely annoy. */
    val BEAR = Personality(
        hungerRate = 1.45f, energyRate = 0.65f, patience = 1.5f, forgiveness = 1.1f,
        favoriteTreat = "a fistful of berries"
    )

    /** Tiny, so he burns through everything fast — and forgets a slight just as fast. */
    val MOUSE = Personality(
        hungerRate = 1.4f, energyRate = 1.3f, patience = 0.7f, forgiveness = 1.5f,
        favoriteTreat = "a crumb of cheese"
    )

    /** Watchful and calm. Very slow to anger, and only middling in a hurry to let it go. */
    val DEER = Personality(
        hungerRate = 0.8f, energyRate = 0.85f, patience = 1.8f, forgiveness = 0.9f,
        favoriteTreat = "a handful of moss"
    )

    /** Keeps odd hours and spends them moving, but is easy enough about the whole business. */
    val BAT = Personality(
        hungerRate = 0.95f, energyRate = 1.1f, patience = 1f, forgiveness = 1.2f,
        favoriteTreat = "a moth at the window"
    )

    /** Almost impossible to rile. Also in no particular hurry to come round afterwards. */
    val FROG = Personality(
        hungerRate = 0.7f, energyRate = 0.7f, patience = 1.9f, forgiveness = 0.8f,
        favoriteTreat = "a passing fly"
    )

    /** Proud, quick to take offence, and the slowest of the lot to forgive one. */
    val DRAGON = Personality(
        hungerRate = 1.2f, energyRate = 0.75f, patience = 0.5f, forgiveness = 0.55f,
        favoriteTreat = "something shiny"
    )

    /** Serene about everything, barely spends any energy, and holds nothing against anyone. */
    val AXOLOTL = Personality(
        hungerRate = 0.85f, energyRate = 0.6f, patience = 1.7f, forgiveness = 1.6f,
        favoriteTreat = "a cool dark corner"
    )

    fun of(species: Species): Personality = when (species) {
        Species.GHOST -> GHOST
        Species.CAT -> CAT
        Species.DOG -> DOG
        Species.BUNNY -> BUNNY
        Species.FOX -> FOX
        Species.BEAR -> BEAR
        Species.MOUSE -> MOUSE
        Species.DEER -> DEER
        Species.BAT -> BAT
        Species.FROG -> FROG
        Species.DRAGON -> DRAGON
        Species.AXOLOTL -> AXOLOTL
    }
}

enum class Mood { CONTENT, SAD, ANGRY }

/**
 * The pet's soul, sitting on top of [PetStats]' body. Where PetStats tracks hunger/energy/
 * happiness, this tracks how he *feels* about how he's been treated: an anger meter that builds
 * under sustained neglect and falls under sustained (or bought) care, shaped by his [Personality].
 * It decays against real elapsed time the same lazy way PetStats does, and it's what turns "a bit
 * sad" into "he's genuinely upset with you" if neglect drags on.
 *
 * ### Two different things live in here, and they are not scoped the same way
 *
 * Anger, mood and the stats underneath them belong to **one pet** and are keyed by [Pet.slot].
 * The token wallet, the daily allowance and the streak belong to the **account**: one balance, one
 * reset, however many pets you are keeping. Spending a token on pet 3 has to leave pet 1 with one
 * fewer token, or five pets would mean five allowances and the economy stops meaning anything.
 *
 * So the two halves are deliberately separate functions. The naming carries it: `give…` buys the
 * thing (account: it can fail for want of a token) and then hands it to a pet; the bare verb —
 * [treat], [gift] — is only what the pet receives, and never touches the wallet.
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

    /**
     * One pet's mood, and the account's wallet alongside it.
     *
     * Whose mood it is comes from [body]`.slot` rather than a field of its own — one source for it
     * means the two can never disagree. [tokens] is the account's balance and says nothing about
     * this pet; it rides along because every screen that shows a mood also shows the wallet.
     */
    data class Snapshot(
        val body: PetStats.Snapshot,
        val anger: Float,
        val mood: Mood,
        val tokens: Int,
        val personality: Personality
    )

    /** Bring anger up to date with real elapsed time (same window PetStats just caught up over),
     *  and combine it with the body snapshot into a mood. Personality comes from *this pet's*
     *  species: two pets of different species, treated identically, are meant to sour at
     *  different rates. */
    fun snapshot(context: Context, slot: Int = PetStore.PRIMARY_SLOT): Snapshot {
        val personality = Personalities.of(Prefs.species(context, slot))
        val lastBefore = Prefs.statsUpdatedAt(context, slot)
        val body = PetStats.snapshot(context, personality, slot)
        val now = System.currentTimeMillis()

        var anger = Prefs.anger(context, slot)
        val elapsedSeconds = (now - lastBefore) / 1000f
        if (elapsedSeconds >= 1f) {
            val e = elapsedSeconds.coerceAtMost(60f * 60f * 24f * 3f)
            val neglected = body.happiness <= PetStats.SAD_THRESHOLD || body.hunger <= PetStats.HUNGRY_THRESHOLD
            anger = if (neglected) {
                (anger + RISE_RATE / personality.patience * e).coerceIn(MIN, MAX)
            } else {
                (anger - FALL_RATE * personality.forgiveness * e).coerceIn(MIN, MAX)
            }
            // Same reasoning as the stats above: recomputed on every read, so it only needs
            // writing when it has actually moved by something worth storing.
            if (kotlin.math.abs(anger - Prefs.anger(context, slot)) >= 1f) Prefs.saveAnger(context, anger, slot)
        }

        val mood = when {
            anger >= ANGRY_THRESHOLD -> Mood.ANGRY
            body.happiness <= PetStats.SAD_THRESHOLD || body.hunger <= PetStats.HUNGRY_THRESHOLD -> Mood.SAD
            else -> Mood.CONTENT
        }
        return Snapshot(body, anger, mood, tokens(context), personality)
    }

    /**
     * Catches the daily allowance up to today: a new day resets it to [DAILY_TOKENS] rather than
     * adding to it, so tokens don't bank up over a week away — it's a daily allowance, not income.
     *
     * The wallet is the account's, not a pet's. Keeping five pets does not earn five allowances.
     *
     * TODO(phase 4): this **overwrites** the balance, so any token earned by some means other than
     * the daily grant is destroyed at the next UTC midnight rather than kept. Today nothing else
     * grants tokens, so the reset is only ever a reset and this is invisible. The moment a rewarded
     * ad (or a purchase, or a gift) can add to the balance, someone who watches an ad at 23:55 and
     * doesn't spend it loses what they earned five minutes later, with no message and no way to
     * tell it happened — the worst possible shape for a bug in something people paid attention for.
     * The fix is for the grant to be additive — top up to at least [DAILY_TOKENS], leave anything
     * above it alone — which is a real behaviour change and so does not belong in this phase.
     */
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

    /** Spends one token if there is one to spend. Account-wide — see the note on [tokens]. */
    private fun spendToken(context: Context): Boolean {
        val t = tokens(context)
        if (t < 1) return false
        Prefs.saveTokens(context, t - 1)
        return true
    }

    // ---- what a pet receives (no wallet in any of these) ---------------------------------------

    /**
     * A pick-me-up: better than a free feed, and knocks a chunk off anger. How much of the anger it
     * knocks off is his own [Personality.forgiveness] — the same treat wins a dog back and barely
     * dents a dragon.
     */
    fun treat(context: Context, slot: Int = PetStore.PRIMARY_SLOT) {
        val s = snapshot(context, slot)
        val hunger = (s.body.hunger + 20f).coerceAtMost(MAX)
        val happiness = (s.body.happiness + 15f).coerceAtMost(MAX)
        Prefs.saveStats(context, hunger, s.body.energy, happiness, s.body.sleeping, System.currentTimeMillis(), slot)
        Prefs.saveAnger(context, (s.anger - 10f * s.personality.forgiveness).coerceIn(MIN, MAX), slot)
    }

    /** The real apology: a big happiness boost and the anger-reducer that actually wins him back. */
    fun gift(context: Context, slot: Int = PetStore.PRIMARY_SLOT) {
        val s = snapshot(context, slot)
        val happiness = (s.body.happiness + 30f).coerceAtMost(MAX)
        Prefs.saveStats(
            context, s.body.hunger, s.body.energy, happiness, s.body.sleeping, System.currentTimeMillis(), slot,
        )
        Prefs.saveAnger(context, (s.anger - 40f * s.personality.forgiveness).coerceIn(MIN, MAX), slot)
    }

    // ---- what it costs (account-wide) -----------------------------------------------------------

    /** Buys a [treat] for the pet in [slot]. Returns false, changing nothing, if there's no token
     *  to spend — the pet is never touched when the wallet says no. */
    fun giveTreat(context: Context, slot: Int = PetStore.PRIMARY_SLOT): Boolean {
        if (!spendToken(context)) return false
        treat(context, slot)
        return true
    }

    /** Buys a [gift] for the pet in [slot]. Returns false, changing nothing, if there's no token
     *  to spend. */
    fun giveGift(context: Context, slot: Int = PetStore.PRIMARY_SLOT): Boolean {
        if (!spendToken(context)) return false
        gift(context, slot)
        return true
    }

    /** Play costs a token too — the balance is checked before it is spent, so a token is never
     *  wasted on a play attempt that was going to fail anyway (asleep, or too worn out). Note the
     *  order: the wallet can veto it, but only *this* pet's own state decides TOO_TIRED. */
    fun playWithToken(context: Context, slot: Int = PetStore.PRIMARY_SLOT): PlayOutcome {
        if (tokens(context) < 1) return PlayOutcome.NO_TOKENS
        if (!PetStats.play(context, slot)) return PlayOutcome.TOO_TIRED
        spendToken(context)
        return PlayOutcome.SUCCESS
    }
}
