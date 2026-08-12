package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.imageview.ShapeableImageView

class JetStreamDiscoverHeroImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ShapeableImageView(context, attrs, defStyleAttr) {

    init {
        background = jetStreamDiscoverHeroPlaceholder()
        scaleType = ScaleType.CENTER_CROP
    }

    private fun jetStreamDiscoverHeroPlaceholder() = android.graphics.drawable.GradientDrawable().apply {
        setColor(jetStreamColor(com.fongmi.android.tv.R.color.jetstream_surface_container_high))
    }
}
