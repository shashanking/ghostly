package com.shashank.ghostly

import kotlin.random.Random

/**
 * His voice.
 *
 * The pet never speaks. Each species has a small set of syllables and, per mood, a pattern built
 * from them; a line is that pattern repeated a random number of times within the mood's range. The
 * result is consistent enough that a cat always sounds like a cat, and varied enough that he never
 * says exactly the same thing twice in a row.
 */
object Vocalisation {

    /**
     * [key] is a mood name in the species block, a literal prefixed with "!", or null for silence.
     */
    fun resolve(pack: BehaviourPack, species: Species, key: String?): String? {
        if (key.isNullOrBlank()) return null
        if (key.startsWith("!")) return key.removePrefix("!").ifBlank { null }

        // A pack that predates a species — the bundled one is the floor, but a downloaded pack
        // can be older in content while newer in version — leaves him without a voice of his
        // own. Falling back on his kin's is better than going silent.
        val profile = pack.species[species.id] ?: pack.species[species.kinId] ?: return null
        val pattern = profile.byMood[key] ?: profile.byMood[key.lowercase()] ?: return fallback(profile)

        val repeats = if (pattern.repeatMax > pattern.repeatMin) {
            Random.nextInt(pattern.repeatMin, pattern.repeatMax + 1)
        } else {
            pattern.repeatMin.coerceAtLeast(1)
        }
        val body = pattern.pattern.ifBlank { return fallback(profile) }
        return List(repeats) { body }.joinToString(" ")
    }

    /** No pattern for that mood — improvise from the species' syllables rather than going silent. */
    private fun fallback(profile: BehaviourPack.SpeciesProfile): String? {
        if (profile.syllables.isEmpty()) return null
        val count = Random.nextInt(1, 3)
        return List(count) { profile.syllables.random() }.joinToString("-")
    }
}
