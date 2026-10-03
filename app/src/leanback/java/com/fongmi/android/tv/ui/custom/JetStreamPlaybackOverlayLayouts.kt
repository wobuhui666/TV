package com.fongmi.android.tv.ui.custom

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView.ScaleType
import android.widget.RelativeLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.widget.ImageViewCompat
import com.fongmi.android.tv.R
import com.google.android.material.textview.MaterialTextView

class JetStreamPlaybackTopBarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {

    init {
        background = jetStreamOverlayBackground(
            orientation = GradientDrawable.Orientation.TOP_BOTTOM,
            cornerRadii = FloatArray(8) { 0f }
        )
        elevation = 0f
        clipToOutline = true
    }
}

class JetStreamPlaybackOverlayRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
    }
}

class JetStreamPlaybackWidgetIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackWidgetSurface()
        applyJetStreamPlaybackIconTint()
    }
}

class JetStreamPlaybackPanelIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        adjustViewBounds = true
        scaleType = ScaleType.CENTER_INSIDE
        alpha = 0.92f
        applyJetStreamPlaybackIconTint()
    }
}

class JetStreamPlaybackWidgetTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackWidgetSurface()
    }
}

class JetStreamPlaybackDigitalTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackWidgetSurface()
        applyJetStreamPlaybackTextDefaults(
            attrs,
            defStyleAttr,
            defaultTextColorRes = R.color.jetstream_on_surface,
            defaultTextSizeSp = 64f,
            defaultLetterSpacing = 0.05f
        )
    }
}

class JetStreamPlaybackLabelTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        includeFontPadding = false
    }
}

class JetStreamPlaybackTitleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface, 20f)
        applyJetStreamPlaybackTextShadow()
    }
}

class JetStreamPlaybackClockTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        fontFeatureSettings = "tnum"
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface, 20f)
        applyJetStreamPlaybackTextShadow()
    }
}

class JetStreamPlaybackMetaTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface_variant, 16f)
        applyJetStreamPlaybackTextShadow()
    }
}

class JetStreamPlaybackTrafficTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface_variant, 15f)
    }
}

class JetStreamPlaybackBodyTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface, 16f)
    }
}

class JetStreamPlaybackTimeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        fontFeatureSettings = "tnum"
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface, 16f)
    }
}

class JetStreamPlaybackSecondaryTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface_variant, 16f)
    }
}

class JetStreamLiveChannelNumberTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface_variant, 18f)
    }
}

class JetStreamLiveChannelNameTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface, 17f)
    }
}

class JetStreamPlaybackTimeRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
    }
}

private fun android.view.View.applyJetStreamPlaybackWidgetSurface() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    background = jetStreamOverlayBackground(
        orientation = GradientDrawable.Orientation.TL_BR,
        cornerRadii = FloatArray(8) { jetStreamDp(12) }
    )
    elevation = 0f
    clipToOutline = true
    if (left == 0 && top == 0 && right == 0 && bottom == 0) {
        setPadding(jetStreamDpInt(20), jetStreamDpInt(18), jetStreamDpInt(20), jetStreamDpInt(18))
    } else {
        setPadding(left, top, right, bottom)
    }
}

@SuppressLint("ResourceType")
private fun MaterialTextView.applyJetStreamPlaybackTextDefaults(
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defaultTextColorRes: Int,
    defaultTextSizeSp: Float,
    defaultLetterSpacing: Float? = null
) {
    val typedArray = context.obtainStyledAttributes(
        attrs,
        intArrayOf(android.R.attr.textColor, android.R.attr.textSize, android.R.attr.textAppearance, android.R.attr.letterSpacing),
        defStyleAttr,
        0
    )
    val hasTextColor = typedArray.hasValue(0)
    val hasTextSize = typedArray.hasValue(1)
    val hasTextAppearance = typedArray.hasValue(2)
    val hasLetterSpacing = typedArray.hasValue(3)
    typedArray.recycle()

    includeFontPadding = false
    if (!hasTextColor) setTextColor(jetStreamColorStateList(defaultTextColorRes))
    if (!hasTextSize && !hasTextAppearance) setTextSize(TypedValue.COMPLEX_UNIT_SP, defaultTextSizeSp)
    if (defaultLetterSpacing != null && !hasLetterSpacing) letterSpacing = defaultLetterSpacing
    applyJetStreamTypeface()
}

private fun MaterialTextView.applyJetStreamPlaybackTextShadow() {
    setShadowLayer(2f, 1.5f, 1.5f, jetStreamColor(R.color.jetstream_overlay_surface))
}

private fun AppCompatImageView.applyJetStreamPlaybackIconTint() {
    if (ImageViewCompat.getImageTintList(this) == null) {
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(jetStreamColor(R.color.jetstream_on_surface)))
    }
}

class JetStreamPlaybackPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        background = jetStreamOverlayBackground(
            orientation = GradientDrawable.Orientation.TL_BR,
            cornerRadii = FloatArray(8) { jetStreamDp(12) }
        )
        elevation = 0f
        clipToOutline = true
        if (paddingLeft == 0 && paddingTop == 0 && paddingRight == 0 && paddingBottom == 0) {
            setPadding(jetStreamDpInt(20), jetStreamDpInt(18), jetStreamDpInt(20), jetStreamDpInt(18))
        }
    }
}

class JetStreamLiveBottomBarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        background = jetStreamOverlayBackground(
            orientation = GradientDrawable.Orientation.BOTTOM_TOP,
            cornerRadii = FloatArray(8) { 0f }
        )
        elevation = 0f
        clipToOutline = true
    }
}

class JetStreamPlaybackControlBarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        background = jetStreamOverlayBackground(
            orientation = GradientDrawable.Orientation.BOTTOM_TOP,
            cornerRadii = FloatArray(8) { 0f }
        )
        elevation = 0f
        clipToOutline = true
    }
}

class JetStreamPlaybackActionRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        applyJetStreamPlaybackActionRowSurface()
    }
}

class JetStreamPlaybackActionScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }
}

private fun LinearLayoutCompat.applyJetStreamPlaybackActionRowSurface() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    background = GradientDrawable().apply {
        cornerRadius = jetStreamDp(12)
        setColor(jetStreamColor(R.color.jetstream_scrim_light))
    }
    minimumHeight = jetStreamDpInt(56)
    clipChildren = false
    clipToPadding = false
    clipToOutline = true
    elevation = 0f
    if (left == 0 && top == 0 && right == 0 && bottom == 0) {
        val padding = jetStreamDpInt(8)
        setPadding(padding, padding, padding, padding)
    } else {
        setPadding(left, top, right, bottom)
    }
}

/** A labelled media row; the activity hides its heading together with the chip row. */
class JetStreamPlaybackSectionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {
    init {
        clipChildren = false
        clipToPadding = false
    }
}

class JetStreamPlaybackSectionTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {
    init {
        applyJetStreamPlaybackTextDefaults(attrs, defStyleAttr, R.color.jetstream_on_surface, 17f)
        isFocusable = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
}
