package io.github.michealsizemore1.beyondstride

import android.content.Context

/** Screen background: a lighter grey-green so the dark tiles stand out, or the original near-black. */
object Theme {
    const val LIGHT_BG = 0xFF48544F.toInt()
    const val DARK_BG = 0xFF121A17.toInt()
    @Volatile var light = true
    val bg: Int get() = if (light) LIGHT_BG else DARK_BG
    fun load(ctx: Context, prefsName: String) {
        light = ctx.getSharedPreferences(prefsName, 0).getString("bgStyle", "light") != "dark"
    }
}
