package com.shashank.ghostly

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The brain: given a situation, does it pick something sensible, and does it stop repeating? */
class BehaviourEngineTest {

    private val pack: BehaviourPack =
        BehaviourPack.parse(JSONObject(File("src/main/assets/behaviour-pack.json").readText()))

    private fun context(
        species: Species = Species.GHOST,
        timeOfDay: TimeOfDay = TimeOfDay.AFTERNOON,
        hunger: Float = 70f,
        energy: Float = 70f,
        happiness: Float = 70f,
        anger: Float = 0f,
        sleeping: Boolean = false,
        slot: Int = PetStore.PRIMARY_SLOT,
    ) = PetContext(
        species = species,
        timeOfDay = timeOfDay,
        dayOfWeek = 3,
        isWeekend = false,
        batteryBucket = BatteryBucket.MID,
        charging = false,
        silentMode = false,
        headphones = false,
        hunger = hunger,
        energy = energy,
        happiness = happiness,
        anger = anger,
        sleeping = sleeping,
        streakDays = 2,
        unlocksToday = 5,
        minutesSinceInteraction = 10,
        lastEvent = null,
        slot = slot,
    )

    /** A pack with exactly one reaction, eligible in any situation, whose cooldown is long enough
     *  that firing it once takes it out of the running for the rest of a test. */
    private fun oneShotPack(): BehaviourPack = BehaviourPack.parse(
        JSONObject(
            """{"reactions":[{"id":"only","emote":"happy","locomotion":"drift","weight":10,"cooldownSec":3600}]}"""
        )
    )

    @Test
    fun `an empty pack proposes nothing rather than crashing`() {
        val engine = BehaviourEngine(null)
        assertNull(engine.next(context()))
        assertTrue(!engine.isLoaded)
    }

    @Test
    fun `a loaded pack proposes something for an ordinary afternoon`() {
        assertNotNull(BehaviourEngine(pack).next(context()))
    }

    @Test
    fun `it never proposes another species' reaction`() {
        val engine = BehaviourEngine(pack)
        val ids = pack.reactions.associateBy { it.id }
        repeat(200) {
            val chosen = engine.next(context(species = Species.CAT), now = it * 60_000L) ?: return@repeat
            val allowed = ids.getValue(chosen.id).conditions.species
            assertTrue(
                "${chosen.id} is not a cat reaction",
                allowed == null || Species.CAT.id in allowed,
            )
        }
    }

    @Test
    fun `a reaction on cooldown is not proposed again immediately`() {
        val engine = BehaviourEngine(pack)
        val first = engine.next(context(), now = 0L) ?: error("nothing proposed")
        val cooldown = pack.reactions.first { it.id == first.id }.cooldownSec
        // Same instant, same situation: whatever comes back, it must not be the one just used.
        repeat(50) {
            val again = engine.next(context(), now = 1_000L) ?: return@repeat
            if (cooldown > 1) assertTrue("${first.id} repeated inside its cooldown", again.id != first.id)
        }
    }

    @Test
    fun `conditions are respected — a stuffed pet is never hungry`() {
        val engine = BehaviourEngine(pack)
        val ids = pack.reactions.associateBy { it.id }
        repeat(200) {
            val chosen = engine.next(context(hunger = 100f), now = it * 60_000L) ?: return@repeat
            val max = ids.getValue(chosen.id).conditions.maxHunger
            assertTrue("${chosen.id} needs hunger <= $max but hunger was 100", max == null || max >= 100f)
        }
    }

    @Test
    fun `a pet's cooldowns are his own, not the roster's`() {
        // The whole reason a roster gets one engine each over one shared pack: with a single engine
        // between them, the first pet to use a reaction would put it on cooldown for all of them,
        // and five pets would behave like one pet with a stutter.
        val pack = oneShotPack()
        val first = BehaviourEngine(pack, PetStore.PRIMARY_SLOT)
        val second = BehaviourEngine(pack, 2)

        assertNotNull(first.next(context(), now = 0L))
        assertNull("his own cooldown did not hold", first.next(context(), now = 1_000L))
        assertNotNull(
            "one pet's cooldown silenced another",
            second.next(context(slot = 2), now = 1_000L),
        )
    }

    @Test
    fun `an engine with no slot given belongs to the pet every install already has`() {
        // Both defaults matter: every call site that predates the roster keeps building a brain
        // and a context for the primary, with no change and nothing to update.
        assertEquals(PetStore.PRIMARY_SLOT, BehaviourEngine(oneShotPack()).slot)
        assertEquals(PetStore.PRIMARY_SLOT, context().slot)
    }

    @Test
    fun `time of day buckets split the clock without gaps`() {
        val covered = (0..23).map { TimeOfDay.of(it) }
        assertEquals(24, covered.size)
        assertEquals(TimeOfDay.LATE_NIGHT, TimeOfDay.of(2))
        assertEquals(TimeOfDay.MORNING, TimeOfDay.of(9))
        assertEquals(TimeOfDay.AFTERNOON, TimeOfDay.of(14))
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.of(23))
    }

    @Test
    fun `battery buckets cover every percentage`() {
        assertEquals(BatteryBucket.CRITICAL, BatteryBucket.of(0))
        assertEquals(BatteryBucket.CRITICAL, BatteryBucket.of(10))
        assertEquals(BatteryBucket.LOW, BatteryBucket.of(11))
        assertEquals(BatteryBucket.MID, BatteryBucket.of(50))
        assertEquals(BatteryBucket.HIGH, BatteryBucket.of(90))
        assertEquals(BatteryBucket.FULL, BatteryBucket.of(100))
    }
}
