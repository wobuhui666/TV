package com.fongmi.android.tv.ui.theme

/** Compatibility entry point for TV surfaces; the Rex theme owns the global preference. */
object RexTheme {
    @get:JvmStatic
    val isLight: Boolean
        get() = com.fongmi.android.tv.ui.rex.RexTheme.isLight()

    @JvmStatic
    fun setLight(light: Boolean) {
        com.fongmi.android.tv.ui.rex.RexTheme.setLight(light)
    }
}
