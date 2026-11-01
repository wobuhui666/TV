package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.core.graphics.ColorUtils
import com.fongmi.android.tv.R

/** A noninteractive origin label inside the existing Material track selection target. */
class JetStreamTrackChoiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MaterialPlaybackButton(context, attrs, role = Role.CHOICE) {
    private val badgeText = context.getString(R.string.track_local_subtitle)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = this@JetStreamTrackChoiceButton.typeface
        textAlign = Paint.Align.CENTER
    }
    private val badgeWidth = badgePaint.measureText(badgeText) + jetStreamDp(12)
    private val originalEndPadding = paddingEnd
    private val badgeBounds = RectF()

    var localSubtitle = false
        set(value) {
            if (field == value) return
            field = value
            setPaddingRelative(paddingStart, paddingTop,
                originalEndPadding + if (value) (badgeWidth + jetStreamDp(8)).toInt() else 0,
                paddingBottom)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!localSubtitle) return
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) originalEndPadding.toFloat()
            else width - originalEndPadding - badgeWidth
        val top = (height - jetStreamDp(22)) / 2
        badgeBounds.set(left, top, left + badgeWidth, top + jetStreamDp(22))
        // The button's text colour comes from the shared palette's control states,
        // keeping the label readable on both ordinary and focused controls.
        badgePaint.color = ColorUtils.setAlphaComponent(currentTextColor, 28)
        canvas.drawRoundRect(badgeBounds, jetStreamDp(6), jetStreamDp(6), badgePaint)
        badgePaint.color = ColorUtils.setAlphaComponent(currentTextColor, 230)
        val metrics = badgePaint.fontMetrics
        canvas.drawText(badgeText, badgeBounds.centerX(),
            badgeBounds.centerY() - (metrics.ascent + metrics.descent) / 2, badgePaint)
    }
}
