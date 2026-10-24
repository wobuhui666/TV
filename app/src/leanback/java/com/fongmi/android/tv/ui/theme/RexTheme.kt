package com.fongmi.android.tv.ui.theme

import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.setting.Setting

/** TV appearance entry point shared by Compose and View surfaces. */
object RexTheme {
    @get:JvmStatic
    val isLight: Boolean
        get() = Setting.isLightTheme()

    @JvmStatic
    fun setLight(light: Boolean) {
        if (isLight == light) return
        Setting.putLightTheme(light)
        RefreshEvent.theme()
    }
}
