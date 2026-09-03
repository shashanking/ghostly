package com.shashank.ghostly

import android.graphics.Color

/**
 * Monochrome glass.
 *
 * A ghost is light on a void, so the app is too: near-black ground, translucent white surfaces with
 * a hairline edge, and exactly one chromatic value in the whole product — [angerRed], which only
 * ever appears where anger is being measured. Anything that wants emphasis gets weight, size or
 * [bone], never a hue.
 *
 * The old two-hue names are kept so every call site reskins without being touched; they now resolve
 * to greys.
 */
object Palette {
    /** The ground. Not pure black — a hair of blue keeps it from looking like a dead pixel. */
    val ink = Color.parseColor("#08080B")

    /** Panels. Translucent white over [ink]; pair with [cardStroke] and a blur where you can. */
    val card = Color.parseColor("#12121A")
    val cardStroke = Color.parseColor("#26262F")
    val badge = Color.parseColor("#1A1A22")

    /** Raised glass — a selected chip, a pressed row. */
    val glass = Color.parseColor("#1B1B24")
    val glassStroke = Color.parseColor("#33333F")

    /** Primary. Light on dark: the button is bone, its label is [ink]. */
    val bone = Color.parseColor("#F2F2F4")
    val accent = Color.parseColor("#F2F2F4")
    val accentDeep = Color.parseColor("#C9C9CF")
    val mint = Color.parseColor("#E4E4E8")

    /** The one colour. Reserved for the anger meter and the angry glow. */
    val angerRed = Color.parseColor("#C4504E")
    val angerDeep = Color.parseColor("#A03F3D")

    val dim = Color.parseColor("#8C8C99")
    val textFaint = Color.parseColor("#5C5C68")
}
