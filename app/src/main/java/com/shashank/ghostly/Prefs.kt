package com.shashank.ghostly

import android.content.Context

/** Tiny wrapper around the app's SharedPreferences. */
object Prefs {
    private const val FILE = "ghostly"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_SIZE = "size_dp"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_CLICK_THROUGH = "click_through"

    const val SIZE_SMALL = 22
    const val SIZE_MEDIUM = 32
    const val SIZE_LARGE = 44

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()

    fun lastX(context: Context, default: Float) = prefs(context).getFloat(KEY_X, default)

    fun lastY(context: Context, default: Float) = prefs(context).getFloat(KEY_Y, default)

    fun savePosition(context: Context, x: Float, y: Float) =
        prefs(context).edit().putFloat(KEY_X, x).putFloat(KEY_Y, y).apply()

    fun sizeDp(context: Context): Int {
        val stored = prefs(context).getInt(KEY_SIZE, SIZE_MEDIUM)
        // The ghost used to be several times bigger; fold any old preference back into the new scale.
        return if (stored > SIZE_LARGE) SIZE_MEDIUM else stored
    }

    fun setSizeDp(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SIZE, value).apply()

    /**
     * When true the ghost is intangible: every touch goes straight to the app underneath and he
     * never blocks a button or a keyboard key. He still notices taps — Android just will not tell
     * an overlay *where* an outside tap landed, so in this mode he cannot be poked precisely or
     * dragged.
     */
    fun clickThrough(context: Context) = prefs(context).getBoolean(KEY_CLICK_THROUGH, true)

    fun setClickThrough(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLICK_THROUGH, value).apply()

    fun hapticsEnabled(context: Context) = prefs(context).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_HAPTICS, value).apply()
}
