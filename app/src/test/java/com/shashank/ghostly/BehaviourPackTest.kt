package com.shashank.ghostly

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the shipped content pack.
 *
 * The pack is 850-odd hand-written reactions referencing enums by name, and nothing in the app
 * fails loudly when one of those names is wrong — a bad locomotion silently becomes DRIFT, a bad
 * vocal key silently becomes silence. That is exactly the kind of mistake that reaches a user and
 * is never noticed, so it is checked here instead.
 */
class BehaviourPackTest {

    private val pack: BehaviourPack by lazy {
        val json = File("src/main/assets/behaviour-pack.json").readText()
        BehaviourPack.parse(JSONObject(json))
    }

    @Test
    fun `the bundled pack parses`() {
        assertTrue("no reactions parsed", pack.reactions.isNotEmpty())
        assertTrue("suspiciously few reactions", pack.reactions.size > 800)
    }

    @Test
    fun `every reaction id is unique`() {
        val duplicates = pack.reactions.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertEquals("duplicate ids: $duplicates", emptySet<String>(), duplicates)
    }

    @Test
    fun `every reaction is playable`() {
        // weight drives the weighted pick; a zero would make the reaction unreachable, a negative
        // one would corrupt the running total for every reaction after it.
        val bad = pack.reactions.filter { it.weight < 1 || it.cooldownSec < 0 }
        assertEquals("unplayable reactions: ${bad.map { it.id }}", emptyList<Any>(), bad)
    }

    @Test
    fun `every vocal key exists for the species that use it`() {
        val missing = mutableListOf<String>()
        for (reaction in pack.reactions) {
            val vocal = reaction.vocal ?: continue
            if (vocal.startsWith("!")) continue // a literal line, not a key into the profile
            val species = reaction.conditions.species ?: Species.entries.map { it.id }.toSet()
            for (id in species) {
                val profile = pack.species[id] ?: continue
                // Checked against the profile directly: resolve() deliberately falls back to a
                // generic noise for an unknown key, which would hide the mistake from this test.
                if (vocal !in profile.byMood) missing += "${reaction.id}: '$vocal' missing for $id"
            }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `every species has a voice`() {
        for (species in Species.entries) {
            assertTrue("no profile for ${species.id}", pack.species.containsKey(species.id))
        }
    }

    @Test
    fun `the set-piece movements are actually used`() {
        // They were added with the app; if a regenerated pack ever drops them the ghost quietly
        // goes back to nothing but drifting, which is easy to miss and hard to explain.
        val used = pack.reactions.map { it.locomotion }.toSet()
        for (piece in listOf(
            Locomotion.ROLLOVER,
            Locomotion.BOUNCE,
            Locomotion.ORBIT,
            Locomotion.PACE,
            Locomotion.EDGE_SLIDE,
            Locomotion.PEEK,
        )) {
            assertTrue("no reaction uses $piece", piece in used)
        }
    }
}
