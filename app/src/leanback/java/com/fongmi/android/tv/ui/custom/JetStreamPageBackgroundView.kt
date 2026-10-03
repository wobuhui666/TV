package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.theme.JetStreamWallpaper
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlin.math.roundToInt

/** Quiet canvas, optionally translucent over the user's wallpaper. */
class JetStreamPageBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        refreshCanvas()
    }

    private fun refreshCanvas() {
        setBackgroundColor(ColorUtils.setAlphaComponent(
            jetStreamColor(R.color.jetstream_background),
            (JetStreamWallpaper.canvasAlpha * 255).roundToInt()
        ))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) EventBus.getDefault().register(this)
        refreshCanvas()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onWallpaperVisibility(event: JetStreamWallpaper.VisibilityEvent) {
        refreshCanvas()
    }

    override fun onDetachedFromWindow() {
        if (!isInEditMode) EventBus.getDefault().unregister(this)
        super.onDetachedFromWindow()
    }
}
