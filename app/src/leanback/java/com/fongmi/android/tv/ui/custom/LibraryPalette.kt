package com.fongmi.android.tv.ui.custom

import android.graphics.Color
import com.fongmi.android.tv.App
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.rex.RexTheme
import com.fongmi.android.tv.ui.theme.JetStreamPalette

/** The library follows the same appearance preference as the rest of the TV UI. */
object LibraryPalette {
    @JvmStatic fun isLight() = RexTheme.isLight()
    @JvmStatic fun surface() = JetStreamPalette.resolveColor(App.get(), R.color.jetstream_background)
    @JvmStatic fun primary() = JetStreamPalette.onSurfaceInt()
    @JvmStatic fun secondary() = JetStreamPalette.resolveColor(App.get(), R.color.jetstream_on_surface_variant)
    @JvmStatic fun muted() = secondary()
    @JvmStatic fun placeholder() = JetStreamPalette.resolveColor(App.get(), R.color.jetstream_surface_container)

    internal fun readableText(color: Int): Int {
        return when (textRole(color)) {
            R.color.jetstream_on_surface -> primary()
            R.color.jetstream_on_surface_variant -> secondary()
            else -> color
        }
    }

    internal fun textRole(color: Int): Int? {
        if (color == Color.WHITE) return R.color.jetstream_on_surface
        val channels = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
        val minimum = channels.minOrNull() ?: 0
        val maximum = channels.maxOrNull() ?: 0
        return if (minimum >= 128 && maximum - minimum <= 48) R.color.jetstream_on_surface_variant else null
    }
}
