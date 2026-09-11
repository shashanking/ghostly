package com.shashank.ghostly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the cast.
 *
 * Adding a species is four separate edits — the enum, a temperament, a silhouette and a voice in
 * the pack — and nothing goes bang when one of them is forgotten. The pet just quietly has no
 * personality, or no reactions, and looks fine in a screenshot.
 */
class SpeciesTest {

    private val originals = setOf(Species.GHOST.id, Species.CAT.id, Species.DOG.id)

    @Test
    fun `ids and labels are unique`() {
        assertEquals(Species.entries.size, Species.entries.map { it.id }.toSet().size)
        assertEquals(Species.entries.size, Species.entries.map { it.label }.toSet().size)
        assertEquals(Species.entries.size, Species.entries.map { it.short }.toSet().size)
    }

    @Test
    fun `every species takes after one of the original three`() {
        for (species in Species.entries) {
            assertTrue(
                "${species.id} takes after '${species.kinId}', which is not one of $originals",
                species.kinId in originals,
            )
            // fromId falls back to the default for a name it doesn't know, so a typo in kinId would
            // silently turn into "takes after the plain ghost" rather than failing anywhere.
            assertEquals(species.kinId, species.kin.id)
        }
    }

    @Test
    fun `the original three take after themselves`() {
        for (species in listOf(Species.GHOST, Species.CAT, Species.DOG)) {
            assertEquals(species.id, species.kinId)
        }
    }

    @Test
    fun `an unknown id falls back to the default rather than throwing`() {
        assertEquals(Species.DEFAULT, Species.fromId(null))
        assertEquals(Species.DEFAULT, Species.fromId("wyvern"))
    }

    @Test
    fun `every species has a temperament of its own`() {
        val sheets = Species.entries.associateWith { Personalities.of(it) }
        assertEquals(
            "two species share a character sheet",
            Species.entries.size,
            sheets.values.toSet().size,
        )
        for ((species, sheet) in sheets) {
            assertTrue("${species.id} has no favourite treat", sheet.favoriteTreat.isNotBlank())
            assertTrue("${species.id} never gets hungry or tired", sheet.hungerRate > 0f && sheet.energyRate > 0f)
            // A zero here divides or multiplies the anger meter by nothing, which pins his mood.
            assertTrue("${species.id} can never calm down", sheet.patience > 0f && sheet.forgiveness > 0f)
        }
    }

    @Test
    fun `every species has something to say`() {
        for (species in Species.entries) {
            assertTrue(species.callHappy.isNotBlank())
            assertTrue(species.callHungry.isNotBlank())
            assertTrue(species.callIdle.isNotBlank())
        }
    }
}
