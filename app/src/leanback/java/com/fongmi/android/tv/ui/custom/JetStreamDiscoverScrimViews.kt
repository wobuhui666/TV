package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import com.fongmi.android.tv.R

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
            intArrayOf(Color.TRANSPARENT, jetStreamColor(R.color.jetstream_overlay_surface))
        )
    }
}
