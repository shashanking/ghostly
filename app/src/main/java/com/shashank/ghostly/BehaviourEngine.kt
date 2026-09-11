package com.shashank.ghostly

import android.content.Context
import kotlin.random.Random

/**
 * The pet's brain.
 *
 * Given the world as [PetContext], it picks what he should do next out of the content pack. Two
 * rules do most of the work of making him feel alive rather than scripted:
 *
 * - **Weighted, not ordered.** Every reaction that matches is eligible; one is drawn at random in
 *   proportion to its weight. The same situation twice rarely produces the same behaviour.
 * - **Cooldowns.** A reaction that just fired is out of the running until its cooldown expires, so
 *   he can't get stuck repeating his one best trick.
 *
 * With no pack loaded this returns null and the caller keeps him drifting — the pack is content,
 * never a dependency.
 *
 * ### One brain each, one pack between them
 *
 * [lastFiredAt] and [recent] are what those two rules are made of, and they are per instance on
 * purpose: they are this pet's memory of what *he* just did. Sharing one engine across a roster
 * would have the first pet's cooldowns silencing the second, so five pets would look like one pet
 * with a stutter — the exact opposite of what having five is for.
 *
 * The [pack] is the other way round. It is immutable once parsed and it is a quarter of a megabyte
 * of JSON, so it is loaded once for the process and every engine reads the same copy. Build engines
 * through [forPets] (or [forPet]) rather than the constructor to get that for free.
 */
class BehaviourEngine(
    private val pack: BehaviourPack?,
    /** Whose brain this is. Not used in picking — it is here so a mismatched engine and
     *  [PetContext] are visible to a reader (and a log line) instead of silently plausible. */
    val slot: Int = PetStore.PRIMARY_SLOT,
) {

    private val lastFiredAt = HashMap<String, Long>()

    /** Reactions that fired recently, most recent last — used to avoid immediate repeats. */
    private val recent = ArrayDeque<String>()

    val isLoaded: Boolean get() = pack != null && pack.reactions.isNotEmpty()

    fun next(
        context: PetContext,
        boosts: Map<String, Float> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): Behaviour? {
        val pack = pack ?: return null

        val eligible = pack.reactions.filter { reaction ->
            if (!reaction.conditions.matches(context)) return@filter false
            val last = lastFiredAt[reaction.id] ?: return@filter true
            now - last >= reaction.cooldownSec * 1000L
        }
        if (eligible.isEmpty()) return null

        // Anything used in the last few picks is heavily discouraged, but not banned — a small
        // chance of a repeat is better than running out of pet.
        // Today's editor event can lean on specific reactions without a release.
        val weighted = eligible.map { reaction ->
            val penalty = if (reaction.id in recent) 0.15f else 1f
            val boost = boosts[reaction.id] ?: 1f
            reaction to (reaction.weight * penalty * boost)
        }
        val total = weighted.sumOf { it.second.toDouble() }
        if (total <= 0.0) return null

        var roll = Random.nextDouble(total)
        val chosen = weighted.firstOrNull { (_, weight) ->
            roll -= weight
            roll <= 0.0
        }?.first ?: eligible.last()

        lastFiredAt[chosen.id] = now
        recent.addLast(chosen.id)
        while (recent.size > RECENT_MEMORY) recent.removeFirst()

        return Behaviour(
            id = chosen.id,
            emote = chosen.emote,
            locomotion = chosen.locomotion,
            vocal = Vocalisation.resolve(pack, context.species, chosen.vocal),
            bubble = chosen.bubble,
            intensity = intensityFor(chosen, context),
            durationMs = durationFor(chosen)
        )
    }

    /**
     * How hard he plays it. The same reaction should land differently when he is desperate than
     * when he is merely peckish — that variation is what stops eight hunger animations a day from
     * looking like the same animation eight times.
     */
    private fun intensityFor(reaction: BehaviourPack.Reaction, context: PetContext): Float {
        val base = when (reaction.emote) {
            Emote.HUNGRY -> 1f - (context.hunger / 100f)
            Emote.SLEEPY -> 1f - (context.energy / 100f)
            Emote.MOODY -> (context.anger / 100f).coerceAtLeast(0.3f)
            Emote.AFFECTION, Emote.HAPPY -> context.happiness / 100f
            Emote.CONFIDENT -> context.energy / 100f
            else -> 0.6f
        }
        return (base * 0.8f + 0.2f).coerceIn(0.2f, 1f)
    }

    private fun durationFor(reaction: BehaviourPack.Reaction): Int = when (reaction.locomotion) {
        Locomotion.ZOOMIES -> 3_500
        Locomotion.FLEE -> 1_800
        Locomotion.PERCH_CORNER -> 6_000
        Locomotion.STILL -> 4_000
        else -> 2_500
    }

    companion object {
        private const val RECENT_MEMORY = 12

        /** The one parsed pack the process shares, and the version it was parsed from. */
        @Volatile
        private var cachedPack: BehaviourPack? = null

        @Volatile
        private var cachedVersion = -1

        private val packLock = Any()

        /**
         * The pack, parsed at most once per version.
         *
         * [BehaviourPack.load] opens and fully parses up to two copies of the content — the bundled
         * one and anything downloaded since — which is far too much to do once per pet per rebuild.
         * The cache key is [BehaviourPack.loadedVersion], deliberately the same one
         * [GhostOverlayService] already uses to decide its engine is stale, so a pack downloaded by
         * [ContentSync] still takes effect exactly when it did before, and never later.
         *
         * A failed load is not cached: [cachedVersion] only moves on success, so a pack that was
         * missing or malformed is retried on the next ask rather than leaving him drifting for the
         * life of the process.
         */
        fun pack(context: Context): BehaviourPack? {
            val version = BehaviourPack.loadedVersion(context)
            cachedPack?.let { if (version == cachedVersion) return it }
            return synchronized(packLock) {
                val cached = cachedPack
                if (cached != null && version == cachedVersion) {
                    cached
                } else {
                    BehaviourPack.load(context)?.also {
                        cachedPack = it
                        cachedVersion = version
                    }
                }
            }
        }

        /** A brain of his own for every pet in [slots], all reading one shared pack. */
        fun forPets(context: Context, slots: List<Int>): Map<Int, BehaviourEngine> {
            val loaded = pack(context)
            return slots.associateWith { BehaviourEngine(loaded, it) }
        }

        fun forPet(context: Context, slot: Int = PetStore.PRIMARY_SLOT): BehaviourEngine =
            BehaviourEngine(pack(context), slot)

        fun fromAssets(context: Context): BehaviourEngine = forPet(context)
    }
}
