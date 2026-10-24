package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.theme.RexTheme

class JetStreamDiscoverDetailScrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        val scrim = context.getDrawable(R.drawable.bg_discover_detail_scrim)!!.mutate() as GradientDrawable
        background = scrim
        bindJetStreamTheme {
            val surface = if (RexTheme.isLight) jetStreamColor(R.color.jetstream_background) else 0xFF0C1017.toInt()
            val centerAlpha = if (RexTheme.isLight) 0xF2 else 0xE0
            val endAlpha = if (RexTheme.isLight) 0xDD else 0x90
            scrim.colors = intArrayOf(surface, ColorUtils.setAlphaComponent(surface, centerAlpha), ColorUtils.setAlphaComponent(surface, endAlpha))
        }
    }
}

class JetStreamDiscoverArtworkLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr)

class JetStreamDiscoverLeftScrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                jetStreamColor(R.color.jetstream_overlay_surface),
                jetStreamColor(R.color.jetstream_overlay_surface_light),
                Color.TRANSPARENT
            )
        )
    }
}

class JetStreamDiscoverBottomScrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, jetStreamColor(R.color.jetstream_background))
        )
    }
}
