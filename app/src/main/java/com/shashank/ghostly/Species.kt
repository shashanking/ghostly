package com.shashank.ghostly

/**
 * Which little fellow this is. Body, motion and behaviour are shared — what tells a bat from an
 * axolotl is his silhouette (drawn by [GhostView]), his temperament ([Personalities]) and his
 * voice (a profile in the behaviour pack, keyed by [id]).
 *
 * [kinId] is the one he takes after. The pack's 850-odd reactions were written for the original
 * three and name them explicitly, so everyone since borrows the pool — and, if the pack in hand
 * has no voice of his own, the voice — of whichever of those three he most resembles. Without it
 * a new species would only ever reach the handful of reactions that name no species at all, and
 * would spend nearly all of his time drifting.
 */
enum class Species(
    val id: String,
    /** His full name, for prose and for the status line. */
    val label: String,
    /** One word, for the pickers, where twelve of these sit side by side. */
    val short: String,
    /** Whose reactions and voice he falls back on — [GHOST], [CAT] or [DOG], by id. */
    val kinId: String,
    /** The few flourishes that don't go through the pack pick from these three instead. */
    val callHappy: String,
    val callHungry: String,
    val callIdle: String,
) {
    GHOST("ghost", "Ghost", "Ghost", "ghost", "boo-oo", "boo?", "boo…"),
    CAT("cat", "Cat ghost", "Cat", "cat", "Purr~", "Meow", "mrr"),
    DOG("dog", "Dog ghost", "Dog", "dog", "Woof!", "Woof?", "wf"),
    BUNNY("bunny", "Bunny ghost", "Bunny", "cat", "Squeak!", "Squeak?", "nff"),
    FOX("fox", "Fox ghost", "Fox", "dog", "Yip!", "Yip?", "rff"),
    BEAR("bear", "Bear ghost", "Bear", "dog", "Hurff!", "Grrum?", "hrm"),
    MOUSE("mouse", "Mouse ghost", "Mouse", "cat", "Eep!", "Eep?", "sk-sk"),
    DEER("deer", "Deer ghost", "Deer", "ghost", "Hnn~", "Hnn?", "hn…"),
    BAT("bat", "Bat ghost", "Bat", "ghost", "Kree!", "Kree?", "fft"),
    FROG("frog", "Frog ghost", "Frog", "ghost", "Brrp!", "Brrp?", "blp"),
    DRAGON("dragon", "Dragon ghost", "Dragon", "dog", "Chrr!", "Grrk?", "hss"),
    AXOLOTL("axolotl", "Axolotl ghost", "Axolotl", "cat", "Blub~", "Blub?", "blb"),
    ;

    /** Himself, for the original three. */
    val kin: Species get() = fromId(kinId)

    companion object {
        val DEFAULT = GHOST

        fun fromId(id: String?): Species = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
