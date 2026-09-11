package com.shashank.ghostly

import android.content.Context

/**
 * The roster: who you currently have, and which slots are free.
 *
 * Everything about a pet other than his stats is stored as ordinary preferences keyed by slot (see
 * [Prefs.key]); this is the small amount of bookkeeping on top — which slots are occupied at all,
 * and when a leased one runs out.
 *
 * ### Why slots are leased
 *
 * Slot 1 is yours forever. Slots 2 to [MAX_PETS] are lent, and [expiresAt] says until when. When a
 * lease runs out the pet is not deleted — his name, look and stats stay exactly where they are, in
 * his own keys — he simply stops being part of [all] and stops floating. Renew the lease and he is
 * back, the same pet, rather than a new one wearing his name. That matters: a pet you have fed for
 * a week should not be destroyed by a clock.
 */
object PetStore {

    /** Five, and the fifth is as many windows as the overlay should ever put on one screen. */
    const val MAX_PETS = 5

    /** The pet every install already has. Never leased, never dismissed. */
    const val PRIMARY_SLOT = 1

    /** Every pet you have right now, primary first. Never empty. */
    fun all(context: Context): List<Pet> = occupiedSlots(context).map { read(context, it) }

    /** Just the slots, primary first, expired leases already dropped. */
    fun occupiedSlots(context: Context): List<Int> {
        val now = System.currentTimeMillis()
        val live = Prefs.petSlots(context).filter { slot ->
            slot == PRIMARY_SLOT || expiresAt(context, slot) > now
        }
        // The primary is not optional — an install with no roster written yet still has him.
        return (listOf(PRIMARY_SLOT) + live).distinct().sorted()
    }

    /** The one everyone has. */
    fun primary(context: Context): Pet = read(context, PRIMARY_SLOT)

    fun get(context: Context, slot: Int): Pet? =
        if (slot in occupiedSlots(context)) read(context, slot) else null

    /** Reads a slot whether or not it is occupied — for the lease-expired pet who may come back. */
    fun read(context: Context, slot: Int): Pet = Pet(
        slot = slot,
        species = Prefs.species(context, slot),
        name = Prefs.name(context, slot),
        shade = Prefs.shade(context, slot),
        sizeDp = Prefs.sizeDp(context, slot),
    )

    /** The lowest slot not currently in use, or null when the roster is full. */
    fun nextFreeSlot(context: Context): Int? {
        val taken = occupiedSlots(context).toSet()
        return (PRIMARY_SLOT..MAX_PETS).firstOrNull { it !in taken }
    }

    /**
     * Takes a lease on the next free slot until [until] (epoch millis).
     *
     * [species] is only applied to a slot that has never held a pet. A returning tenant keeps who
     * he was — see the note on leases above.
     */
    fun lease(context: Context, species: Species, until: Long): Pet? {
        val slot = nextFreeSlot(context) ?: return null
        if (!Prefs.hasPet(context, slot)) Prefs.setSpecies(context, species, slot)
        Prefs.setExpiresAt(context, slot, until)
        Prefs.setPetSlots(context, (Prefs.petSlots(context) + slot).distinct().sorted())
        return read(context, slot)
    }

    /** Extends an existing lease, from now rather than from whenever it happened to run out. */
    fun renew(context: Context, slot: Int, forMillis: Long) {
        if (slot == PRIMARY_SLOT) return
        val from = maxOf(System.currentTimeMillis(), expiresAt(context, slot))
        Prefs.setExpiresAt(context, slot, from + forMillis)
        Prefs.setPetSlots(context, (Prefs.petSlots(context) + slot).distinct().sorted())
    }

    /** Ends a lease early. Keeps everything about the pet, so renewing brings the same one back. */
    fun release(context: Context, slot: Int) {
        if (slot == PRIMARY_SLOT) return
        Prefs.setExpiresAt(context, slot, 0L)
        Prefs.setPetSlots(context, Prefs.petSlots(context).filterNot { it == slot })
    }

    /** When this slot's lease runs out, epoch millis. 0 for never leased; [Long.MAX_VALUE] for the
     *  primary, who does not expire. */
    fun expiresAt(context: Context, slot: Int): Long =
        if (slot == PRIMARY_SLOT) Long.MAX_VALUE else Prefs.expiresAt(context, slot)

    /** Milliseconds left on a lease, floored at zero. [Long.MAX_VALUE] for the primary. */
    fun millisRemaining(context: Context, slot: Int): Long {
        val until = expiresAt(context, slot)
        if (until == Long.MAX_VALUE) return Long.MAX_VALUE
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** What to call him, for callers that have a slot rather than a [Pet]. */
    fun displayName(context: Context, slot: Int): String = read(context, slot).displayName
}
