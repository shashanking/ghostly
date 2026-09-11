package com.shashank.ghostly

/**
 * One pet: who he is, and what he looks like.
 *
 * His stats are deliberately not here. Hunger, energy, happiness and anger change every few
 * seconds and are read and written by half the app; keeping them out means a [Pet] can be passed
 * around and held onto without going stale. They live in [Prefs] instead, keyed by [slot], and are
 * read through [PetStats] and [Emotions].
 *
 * ### Slots
 *
 * [slot] is the identity, not a position on screen. It is assigned once, never reused while the
 * pet lives, and is what every per-pet preference is keyed by.
 *
 * **Slot 1 is special and must stay special.** Every install that exists today has exactly one pet,
 * stored under flat, unsuffixed preference keys (`species`, `hunger`, `name`…). Slot 1 reads and
 * writes those same keys — see [Prefs.key] — so the first pet of an existing user is picked up
 * exactly where it was, with no migration step to get wrong. Slots 2 to [PetStore.MAX_PETS] suffix
 * their keys and so start empty, which is correct: they did not exist before.
 *
 * Slot 1 is also the only permanent slot. The others are leased, and [PetStore.expiresAt] says
 * until when.
 */
data class Pet(
    /** 1..[PetStore.MAX_PETS]. See the note on slots above. */
    val slot: Int,
    val species: Species,
    /** Null until the user names him; callers fall back to [PetStore.displayName]. */
    val name: String?,
    val shade: Shade,
    val sizeDp: Int,
) {
    /** True for the pet everyone has had all along, who never expires and cannot be dismissed. */
    val isPrimary: Boolean get() = slot == PetStore.PRIMARY_SLOT

    /**
     * What to call him on screen. An unnamed first pet goes by the app's own name, because
     * "Ghostly is floating" reads like a pet and "Dog ghost is floating" reads like a product.
     * An unnamed later pet has no such luck — there would be two Ghostlys — so he goes by what he
     * is, which at least tells the two of them apart.
     */
    val displayName: String get() = name ?: if (isPrimary) "Ghostly" else species.label
}
