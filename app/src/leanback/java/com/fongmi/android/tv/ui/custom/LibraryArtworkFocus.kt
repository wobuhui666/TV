package com.fongmi.android.tv.ui.custom

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import com.fongmi.android.tv.ui.theme.JetStreamPalette

/** A focus edge follows the artwork, leaving the caption and the rail layout still. */
internal class LibraryArtworkFocus(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val frame = RectF()
    private var intensity = 0f
    private var opacity = 255
    private var animation: ValueAnimator? = null
    var circular = false

    fun animate(focused: Boolean, enabled: Boolean) {
        animation?.cancel()
        animation = null
        val target = if (focused) 1f else 0f
        if (!enabled) {
            intensity = target
            invalidateSelf()
            return
        }
        animation = ValueAnimator.ofFloat(intensity, target).apply {
            duration = JetStreamAnimator.FOCUS_DURATION
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                intensity = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        if (intensity <= 0f) return
        frame.set(bounds)
        frame.inset(1.25f * density, 1.25f * density)
        val radius = if (circular) frame.height() / 2f else 8f * density
        paint.color = if (LibraryPalette.isLight()) Color.WHITE else Color.BLACK
        paint.alpha = (opacity * intensity * .22f).toInt()
        paint.strokeWidth = 3f * density
        canvas.drawRoundRect(frame, radius, radius, paint)
        paint.color = JetStreamPalette.primaryInt()
        paint.alpha = (opacity * intensity * .9f).toInt()
        paint.strokeWidth = 1.5f * density
        canvas.drawRoundRect(frame, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
