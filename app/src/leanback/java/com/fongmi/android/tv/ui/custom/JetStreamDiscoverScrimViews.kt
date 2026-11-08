package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.theme.JetStreamWallpaper
import com.fongmi.android.tv.ui.theme.RexTheme
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlin.math.min

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

/** Blend the complete artwork into the page canvas, retaining independent text and controls. */
class JetStreamDiscoverArtworkLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private var blendWallpaper = JetStreamWallpaper.isVisible()

    init {
        bindJetStreamTheme { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) EventBus.getDefault().register(this)
        blendWallpaper = JetStreamWallpaper.isVisible()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        if (!isInEditMode) EventBus.getDefault().unregister(this)
        super.onDetachedFromWindow()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onWallpaperVisibility(event: JetStreamWallpaper.VisibilityEvent) {
        blendWallpaper = JetStreamWallpaper.isVisible()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fadePaint.shader = if (h > 1) {
            val fadeHeight = min(jetStreamDp(128), h * 0.32f).coerceAtLeast(2f)
            LinearGradient(
                0f, h - fadeHeight, 0f, h - 1f,
                intArrayOf(0, 11, 40, 81, 127, 174, 215, 244, 255)
                    .map { Color.argb(it, 255, 255, 255) }.toIntArray(),
                null, Shader.TileMode.CLAMP
            )
        } else null
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!blendWallpaper || fadePaint.shader == null) {
            super.dispatchDraw(canvas)
            return
        }
        // A translucent color scrim would expose the hero image, not the wallpaper below it.
        // Mask the image and both scrims together, revealing the actual shared page canvas.
        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
        canvas.restoreToCount(checkpoint)
    }
}

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
