package com.shashank.ghostly

import android.graphics.Color

/**
 * How solid he is.
 *
 * A black-and-white ghost has no hue to choose, so the choice is how much of the screen you can see
 * through him — plus [INK], which inverts him for anyone who lives on a pale wallpaper.
 */
enum class Shade(
    val id: String,
    val label: String,
    /** Body fill and its alpha. */
    val bodyColor: Int,
    val bodyAlpha: Int,
    /**
     * Outline. It exists to keep a see-through ghost readable on a busy screen, and no more than
     * that: at any real weight it stops being an edge and becomes a border drawn round him. The
     * same barely-there 5% on every shade — his silhouette, the shading and the wash behind him
     * are what actually hold him together.
     */
    val outlineColor: Int,
    /** Eye ink — inverted along with the body for [INK]. */
    val inkColor: Int,
    val scleraColor: Int,
) {
    BONE(
        "bone", "Bone",
        Color.WHITE, 210,
        Color.parseColor("#0DFFFFFF"),
        Color.parseColor("#FF15122B"), Color.parseColor("#F5EDEFFF"),
    ),
    ASH(
        "ash", "Ash",
        Color.parseColor("#C6C8D2"), 205,
        Color.parseColor("#0DFFFFFF"),
        Color.parseColor("#FF15122B"), Color.parseColor("#F5E9EBF6"),
    ),
    VAPOUR(
        "vapour", "Vapour",
        Color.WHITE, 120,
        Color.parseColor("#0DFFFFFF"),
        Color.parseColor("#E015122B"), Color.parseColor("#D9EDEFFF"),
    ),
    INK(
        "ink", "Ink",
        Color.parseColor("#22222C"), 232,
        // Faint on purpose. A dark, near-solid ghost does not need an outline to stay readable —
        // that is a job for the pale wash he carries behind him — and at the weight the pale
        // shades use it stopped being an edge and became a border drawn round him.
        Color.parseColor("#0DFFFFFF"),
        Color.parseColor("#FFF2F2F4"), Color.parseColor("#FF3A3A46"),
    ),
    ;

    companion object {
        val DEFAULT = BONE

        fun fromId(id: String?): Shade = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
