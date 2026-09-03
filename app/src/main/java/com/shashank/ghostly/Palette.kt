package com.shashank.ghostly

import android.graphics.Color

/** The app's whole colour system in one place — a tight two-hue palette: purple for brand and
 *  selection, a calmer teal for everything else that needs a tint. Anger red is the one deliberate
 *  exception, reserved for the anger meter where it's actually meaningful. */
object Palette {
    val ink = Color.parseColor("#0B0A14")
    val card = Color.parseColor("#16142B")
    val cardStroke = Color.parseColor("#2B2650")
    val badge = Color.parseColor("#241F45")
    val accent = Color.parseColor("#8B6BFF")
    val accentDeep = Color.parseColor("#6B4FE0")
    val mint = Color.parseColor("#5FC9C0")
    val angerRed = Color.parseColor("#E8615F")
    val angerDeep = Color.parseColor("#C94A48")
    val dim = Color.parseColor("#9A93C4")
    val textFaint = Color.parseColor("#6F6A96")
}
