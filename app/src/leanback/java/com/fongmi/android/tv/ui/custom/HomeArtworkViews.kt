package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.utils.HomeArtworkPolicy

/** Wide artwork fills the hero; vertical posters keep their full composition at the right. */
class HomeArtworkView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        composeArtwork()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        composeArtwork()
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.clipRect(0, 0, width, height)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    private fun composeArtwork() {
        val image = drawable ?: return
        val placement = HomeArtworkPolicy.place(width, height, image.intrinsicWidth, image.intrinsicHeight)
        imageMatrix = Matrix().apply {
            setScale(placement.scale(), placement.scale())
            postTranslate(placement.x(), placement.y())
        }
    }
}

class HomeContinueCardLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        background = GradientDrawable().apply {
            cornerRadius = jetStreamDp(10)
            setColor(jetStreamColor(R.color.jetstream_surface_container))
        }
        foreground = jetStreamFocusForeground(cornerRadiusDp = 10, strokeWidthDp = 2)
        clipToOutline = true
        JetStreamAnimator.bindFocus(this, JetStreamAnimator.FOCUS_SCALE_CARD, 0)
    }
}

class HomeWatchProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = jetStreamColor(R.color.jetstream_on_surface)
        paint.alpha = 45
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.alpha = 235
        canvas.drawRect(0f, 0f, width * progress, height.toFloat(), paint)
    }
}
