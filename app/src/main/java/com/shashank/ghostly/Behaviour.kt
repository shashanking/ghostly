package com.shashank.ghostly

/**
 * The contract between the pet's brain and its body.
 *
 * [BehaviourEngine] decides *what* he does and why; [GhostView] decides what that looks like. Both
 * sides — and the generated content pack — key off the enums here, so a new animation only needs a
 * new [Emote] value agreed in one place.
 */
enum class Emote {
    GOOFY, MOODY, SPOOKED, AFFECTION, HUNGRY, CONFIDENT, HAPPY, SLEEPY, CURIOUS;

    companion object {
        fun fromId(id: String?): Emote? = entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }
}

/** How he moves while the emote plays. */
enum class Locomotion {
    DRIFT, FLEE, APPROACH, PERCH_CORNER, ZOOMIES, STILL;

    companion object {
        fun fromId(id: String?): Locomotion? =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }
}

/**
 * A single thing the pet has decided to do. [vocal] is already resolved to syllables — the view
 * never has to know about the pack.
 */
data class Behaviour(
    val id: String,
    val emote: Emote,
    val locomotion: Locomotion,
    val vocal: String?,
    val bubble: Bubble,
    val intensity: Float = 0.6f,
    val durationMs: Int = 2_500,
)

/**
 * A closed set of things he can show above his head. Closed on purpose: the pack ships tokens, the
 * app maps them to glyphs, so the content can never reference an asset that doesn't exist.
 */
enum class Bubble(val token: String, val glyph: String) {
    NONE("none", ""),
    SUN("sun", "☀️"),
    MOON("moon", "🌙"),
    STAR("star", "⭐"),
    HEART("heart", "❤️"),
    HEART_BROKEN("heart_broken", "💔"),
    FISH("fish", "🐟"),
    BONE("bone", "🦴"),
    FOOD("food", "🍎"),
    DASH("dash", "💨"),
    ZZZ("zzz", "💤"),
    NOTE("note", "🎵"),
    CLOUD("cloud", "☁️"),
    RAIN("rain", "🌧️"),
    SWEAT("sweat", "💧"),
    ANGER("anger", "💢"),
    QUESTION("question", "❓"),
    SPARKLE("sparkle", "✨"),
    BUG("bug", "🐛"),
    BALL("ball", "🔴"),
    YARN("yarn", "🧶"),
    BATTERY_LOW("battery_low", "🪫"),
    CHARGING("charging", "⚡"),
    PARTY("party", "🎉"),
    EYES("eyes", "👀");

    companion object {
        fun fromToken(token: String?): Bubble =
            entries.firstOrNull { it.token.equals(token, ignoreCase = true) } ?: NONE
    }
}
