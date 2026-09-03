package com.shashank.ghostly

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/**
 * Three faces, loaded once.
 *
 * Instrument Serif for anything that speaks in the ghost's voice — his name, a headline, the line
 * that says how he is. Space Grotesk for interface text. JetBrains Mono for numerals and the small
 * uppercase labels, where digits need to line up and a label needs to read as machine-set.
 *
 * They are bundled rather than downloaded: the app has no network requirement, and a pet whose name
 * renders in a fallback face on first run looks broken.
 */
object Type {

    private var serifCache: Typeface? = null
    private var serifItalicCache: Typeface? = null
    private var sansCache: Typeface? = null
    private var sansMediumCache: Typeface? = null
    private var monoCache: Typeface? = null

    fun serif(context: Context): Typeface =
        serifCache ?: load(context, R.font.instrument_serif, Typeface.SERIF).also { serifCache = it }

    fun serifItalic(context: Context): Typeface = serifItalicCache
        ?: load(context, R.font.instrument_serif_italic, Typeface.create(Typeface.SERIF, Typeface.ITALIC))
            .also { serifItalicCache = it }

    fun sans(context: Context): Typeface =
        sansCache ?: load(context, R.font.space_grotesk, Typeface.SANS_SERIF).also { sansCache = it }

    /** Space Grotesk is a variable font; ask for 500 where the platform can interpolate. */
    fun sansMedium(context: Context): Typeface = sansMediumCache ?: run {
        val base = sans(context)
        val medium = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, 500, false)
        } else {
            Typeface.create(base, Typeface.BOLD)
        }
        medium.also { sansMediumCache = it }
    }

    fun mono(context: Context): Typeface =
        monoCache ?: load(context, R.font.jetbrains_mono, Typeface.MONOSPACE).also { monoCache = it }

    private fun load(context: Context, id: Int, fallback: Typeface): Typeface =
        runCatching { context.resources.getFont(id) }.getOrDefault(fallback)
}
