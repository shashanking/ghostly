package com.shashank.ghostly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slot-to-preference-key scheme.
 *
 * This is load-bearing in a way that is easy to miss: every install in the wild stores its one pet
 * under flat, unsuffixed keys. If [Prefs.key] ever stopped returning the bare key for slot 1,
 * every existing user would open the app to a brand-new pet with no name, no stats and no streak,
 * and their real one would still be sitting in preferences that nothing reads any more. There is no
 * recovering from that in an update, so it is pinned here.
 */
class PetSlotTest {

    @Test
    fun `the primary pet keeps the flat keys every install already uses`() {
        for (base in listOf("species", "hunger", "energy", "happiness", "anger", "name", "shade", "x", "y")) {
            assertEquals(base, Prefs.key(base, PetStore.PRIMARY_SLOT))
        }
    }

    @Test
    fun `every other slot gets a key of its own`() {
        val keys = (1..PetStore.MAX_PETS).map { Prefs.key("hunger", it) }
        assertEquals("two slots share a key", keys.size, keys.toSet().size)
        assertEquals("hunger#2", Prefs.key("hunger", 2))
        assertEquals("hunger#5", Prefs.key("hunger", 5))
    }

    @Test
    fun `a key says which pet and which value it belongs to`() {
        // The overlay's preference listener gets a flat key name and nothing else; without these
        // two it cannot tell "pet 3 got hungry" from "somebody changed a shade".
        for (slot in PetStore.PRIMARY_SLOT..PetStore.MAX_PETS) {
            val k = Prefs.key("happiness", slot)
            assertEquals(slot, Prefs.slotOf(k))
            assertEquals("happiness", Prefs.baseOf(k))
        }
    }

    @Test
    fun `an unsuffixed key belongs to the primary`() {
        assertEquals(PetStore.PRIMARY_SLOT, Prefs.slotOf("hunger"))
        assertEquals("hunger", Prefs.baseOf("hunger"))
    }

    @Test
    fun `keys that are not ours are not claimed by some pet`() {
        // Global settings live in the same file. Reading a slot out of one would attribute the
        // token balance, or the session token, to a pet.
        for (global in listOf("tokens", "streak", "click_through", "onboarding_complete")) {
            assertEquals(PetStore.PRIMARY_SLOT, Prefs.slotOf(global))
            assertEquals(global, Prefs.baseOf(global))
        }
    }

    @Test
    fun `a malformed suffix falls back to the primary rather than throwing`() {
        // Nothing writes these, but a corrupted or hand-edited preferences file is a real thing and
        // it must not crash the overlay's listener.
        assertEquals(PetStore.PRIMARY_SLOT, Prefs.slotOf("hunger#"))
        assertEquals(PetStore.PRIMARY_SLOT, Prefs.slotOf("hunger#x"))
        assertEquals("hunger", Prefs.baseOf("hunger#x"))
    }

    @Test
    fun `the primary is the only pet that never expires`() {
        val pet = Pet(PetStore.PRIMARY_SLOT, Species.GHOST, null, Shade.BONE, Prefs.SIZE_SPOOK)
        assertTrue(pet.isPrimary)
        for (slot in 2..PetStore.MAX_PETS) {
            assertFalse(Pet(slot, Species.CAT, null, Shade.BONE, Prefs.SIZE_SPOOK).isPrimary)
        }
    }

    @Test
    fun `an unnamed later pet goes by what he is, so two of them read apart`() {
        // The primary keeps the app's own name — "Ghostly is floating" reads like a pet. A second
        // unnamed one cannot have it too, or the notification names them both the same thing.
        assertEquals("Ghostly", Pet(1, Species.GHOST, null, Shade.BONE, 36).displayName)
        assertEquals(Species.FOX.label, Pet(2, Species.FOX, null, Shade.BONE, 36).displayName)
        assertEquals("Bramble", Pet(2, Species.FOX, "Bramble", Shade.BONE, 36).displayName)
    }
}
