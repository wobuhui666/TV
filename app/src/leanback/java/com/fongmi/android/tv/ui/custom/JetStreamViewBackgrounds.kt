package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.widget.EditText
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.res.ResourcesCompat
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.fongmi.android.tv.ui.theme.RexTheme

internal fun View.jetStreamDialogBackground(cornerRadii: FloatArray): GradientDrawable {
    return jetStreamSolidBackground(R.color.jetstream_surface, cornerRadii)
}

internal fun View.jetStreamOverlayBackground(
    orientation: GradientDrawable.Orientation,
    cornerRadii: FloatArray
): GradientDrawable {
    return jetStreamOverlayBackground(orientation).apply {
        this.cornerRadii = cornerRadii
    }
}

internal fun View.jetStreamOverlayBackground(
    orientation: GradientDrawable.Orientation,
    cornerRadius: Float
): GradientDrawable {
    return jetStreamOverlayBackground(orientation).apply {
        this.cornerRadius = cornerRadius
    }
}

private fun View.jetStreamOverlayBackground(
    orientation: GradientDrawable.Orientation
): GradientDrawable {
    return GradientDrawable(
        orientation,
        intArrayOf(
            jetStreamColor(R.color.jetstream_overlay_surface),
            jetStreamColor(R.color.jetstream_overlay_surface_light)
        )
    ).apply {
        setStroke(0, 0)
    }.also { drawable ->
        bindJetStreamTheme {
            drawable.setColors(intArrayOf(jetStreamColor(R.color.jetstream_overlay_surface), jetStreamColor(R.color.jetstream_overlay_surface_light)))
        }
    }
}

private fun View.jetStreamSolidBackground(
    @ColorRes colorRes: Int,
    cornerRadii: FloatArray
): GradientDrawable {
    return GradientDrawable().apply {
        this.cornerRadii = cornerRadii
        setColor(jetStreamColor(colorRes))
        setStroke(0, 0)
    }.also { drawable ->
        bindJetStreamTheme {
            drawable.setColor(jetStreamColor(colorRes))
        }
    }
}

internal fun View.jetStreamColor(@ColorRes colorRes: Int): Int {
    return JetStreamPalette.resolveColor(context, colorRes)
}

internal fun View.jetStreamColorStateList(@ColorRes colorRes: Int) = JetStreamPalette.resolveColorStateList(context, colorRes)

internal fun View.jetStreamDp(value: Int): Float {
    return value * resources.displayMetrics.density
}

internal fun View.jetStreamDpInt(value: Int): Int {
    return jetStreamDp(value).toInt()
}

/** Platform drawable transitions retain the existing focus states and rounded outline. */
internal fun StateListDrawable.withJetStreamFocusMotion(): StateListDrawable = apply {
    val enabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    val duration = if (enabled) JetStreamAnimator.FOCUS_DURATION.toInt() else 0
    setEnterFadeDuration(duration)
    setExitFadeDuration(duration)
}

private var jetStreamTypefaceCache: Typeface? = null

internal fun jetStreamTypeface(context: Context): Typeface? {
    if (jetStreamTypefaceCache == null) {
        jetStreamTypefaceCache = runCatching { ResourcesCompat.getFont(context, R.font.misans) }.getOrNull()
    }
    return jetStreamTypefaceCache
}

/**
 * 应用品牌字体，保留当前字重/斜体样式。在文本样式辅助函数末尾调用。
 */
internal fun TextView.applyJetStreamTypeface() {
    val family = jetStreamTypeface(context) ?: return
    val style = typeface?.style ?: Typeface.NORMAL
    if (style == Typeface.NORMAL) typeface = family else setTypeface(family, style)
}

/** Theme-aware cursor and selection for TV text input; the shared drawable is white. */
internal fun TextView.applyJetStreamCursor() {
    if (this !is EditText) return
    val defaultHighlight = highlightColor
    bindJetStreamTheme {
        if (RexTheme.isLight) {
            highlightColor = JetStreamPalette.primaryInt().let { (it and 0x00FFFFFF) or 0x40000000 }
        } else {
            val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorHighlight))
            highlightColor = colors.getColor(0, defaultHighlight)
            colors.recycle()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textCursorDrawable = textCursorDrawable?.mutate()?.apply {
                if (RexTheme.isLight) setTint(JetStreamPalette.primaryInt()) else setTintList(null)
            }
        }
    }
}
