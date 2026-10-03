package com.fongmi.android.tv.ui.custom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.compose.ui.graphics.toArgb
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.NestedScrollView
import com.fongmi.android.tv.ui.components.TvFocusStyle
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView

/**
 * 给系统 MaterialAlertDialog 的按钮着当前主题色。
 * 主题的 colorPrimary 是静态资源，无法随调色板运行时变化，弹出后手动补色。
 */
object JetStreamDialogDecor {

    @JvmStatic
    fun tintButtons(dialog: AlertDialog) {
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            val button = dialog.getButton(which) ?: continue
            button.setTextColor(JetStreamPalette.controlText())
            button.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = button.jetStreamDp(12)
                    setColor(TvFocusStyle.FocusedContainer.toArgb())
                    setStroke(button.jetStreamDpInt(1), TvFocusStyle.FocusOutline.toArgb())
                })
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        }
    }
}

class JetStreamDialogScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    init {
        setFillViewport(true)
        overScrollMode = View.OVER_SCROLL_NEVER
        background = jetStreamDialogBackground(cornerRadii = FloatArray(8) { jetStreamDp(28) })
        elevation = 0f
        clipToOutline = true
    }
}

class JetStreamSheetSurfaceLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.JetStreamSheetSurfaceLayout, defStyleAttr, 0)
        val edge = typedArray.getInt(R.styleable.JetStreamSheetSurfaceLayout_jetStreamSheetEdge, SHEET_EDGE_BOTTOM)
        typedArray.recycle()

        clipChildren = edge == SHEET_EDGE_RIGHT
        clipToPadding = edge == SHEET_EDGE_RIGHT
        background = jetStreamDialogBackground(cornerRadii = if (edge == SHEET_EDGE_RIGHT) rightSheetCornerRadii() else bottomSheetCornerRadii())
        elevation = jetStreamDp(10)
        clipToOutline = true
    }

    private fun bottomSheetCornerRadii(): FloatArray {
        return floatArrayOf(jetStreamDp(28), jetStreamDp(28), jetStreamDp(28), jetStreamDp(28), 0f, 0f, 0f, 0f)
    }

    private fun rightSheetCornerRadii(): FloatArray {
        return floatArrayOf(jetStreamDp(28), jetStreamDp(28), 0f, 0f, 0f, 0f, jetStreamDp(28), jetStreamDp(28))
    }

    private companion object {
        private const val SHEET_EDGE_BOTTOM = 0
        private const val SHEET_EDGE_RIGHT = 1
    }
}

class JetStreamDialogSurfaceLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
        background = jetStreamDialogBackground(cornerRadii = FloatArray(8) { jetStreamDp(28) })
        elevation = 0f
        clipToOutline = true
    }
}

class JetStreamDialogRelativeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
        background = jetStreamDialogBackground(cornerRadii = FloatArray(8) { jetStreamDp(28) })
        elevation = 0f
        clipToOutline = true
    }
}

class JetStreamDialogEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CustomEditText(context, attrs) {

    init {
        applyJetStreamDialogInputSurface(attrs, 0)
    }
}

class JetStreamMonospaceDialogEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CustomEditText(context, attrs) {

    init {
        applyJetStreamDialogInputSurface(attrs, 0)
        val typedArray = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.fontFamily), 0, 0)
        val hasFontFamily = typedArray.hasValue(0)
        typedArray.recycle()
        if (!hasFontFamily) typeface = Typeface.MONOSPACE
    }
}

class JetStreamDialogTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextInputEditText(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogInputSurface(attrs, defStyleAttr)
    }
}

class JetStreamCompactDialogTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextInputEditText(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogInputSurface(attrs, defStyleAttr, defaultTextSizeSp = 16f)
    }
}

class JetStreamDenseDialogTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextInputEditText(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogInputSurface(attrs, defStyleAttr, defaultTextSizeSp = 14f)
    }
}

class JetStreamSubtitleIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamSubtitleIconSurface()
        applyJetStreamControlIconTint()
    }
}

class JetStreamDialogButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogButtonSurface()
        clipToOutline = true
        JetStreamAnimator.bindFocus(this, 1f, 0)
    }
}

class JetStreamDialogToggleGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialButtonToggleGroup(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
    }
}

class JetStreamFilterChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.chipStyle
) : Chip(context, attrs, defStyleAttr) {

    init {
        applyJetStreamFilterChipSurface()
        minimumHeight = maxOf(minimumHeight, jetStreamDpInt(40))
        clipToOutline = true
        JetStreamAnimator.bindFocus(this, 1f, 0)
    }
}

class JetStreamFilterChipGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ChipGroup(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
    }
}

class JetStreamSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialSwitchStyle
) : MaterialSwitch(context, attrs, defStyleAttr) {

    init {
        thumbTintList = jetStreamColorStateList(R.color.jetstream_switch_thumb)
        trackTintList = jetStreamColorStateList(R.color.jetstream_switch_track)
        trackTintMode = PorterDuff.Mode.SRC_IN
        minimumWidth = maxOf(minimumWidth, jetStreamDpInt(52))
        minimumHeight = maxOf(minimumHeight, jetStreamDpInt(32))
    }
}

class JetStreamSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.sliderStyle
) : Slider(context, attrs, defStyleAttr) {

    init {
        haloTintList = jetStreamColorStateList(R.color.jetstream_scrim_medium)
        thumbTintList = jetStreamColorStateList(R.color.jetstream_primary)
        trackActiveTintList = jetStreamColorStateList(R.color.jetstream_primary)
        trackInactiveTintList = jetStreamColorStateList(R.color.jetstream_outline_variant)
        trackHeight = jetStreamDpInt(6)
        isFocusable = true
        isFocusableInTouchMode = true
    }
}

class JetStreamSliderTitleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogTextDefaults(attrs, defStyleAttr, R.color.jetstream_slider_title_text, 16f)
    }
}

class JetStreamSliderValueTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogTextDefaults(attrs, defStyleAttr, R.color.jetstream_slider_value_text, 14f)
    }
}

class JetStreamErrorLabelTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogTextDefaults(attrs, defStyleAttr, R.color.jetstream_error, 14f)
    }
}

class JetStreamCheckBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCheckBox(context, attrs, defStyleAttr) {

    init {
        buttonTintList = JetStreamPalette.controlText()
        buttonIconTintList = JetStreamPalette.controlContainer()
        JetStreamAnimator.bindFocus(this, 1f, 0)
    }
}

class JetStreamCircularProgressIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CircularProgressIndicator(context, attrs, defStyleAttr) {

    init {
        isIndeterminate = true
        setIndicatorColor(jetStreamColor(R.color.jetstream_primary))
        setIndicatorSize(resources.getDimensionPixelSize(R.dimen.progress_indicator_size))
        setTrackColor(jetStreamColor(R.color.jetstream_outline_variant))
        setTrackCornerRadius(resources.getDimensionPixelSize(R.dimen.progress_indicator_corner_radius))
        setTrackThickness(resources.getDimensionPixelSize(R.dimen.progress_indicator_track_thickness))
    }
}

class JetStreamSmallCircularProgressIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CircularProgressIndicator(context, attrs, defStyleAttr) {

    init {
        isIndeterminate = true
        setIndicatorColor(jetStreamColor(R.color.jetstream_primary))
        setIndicatorSize(jetStreamDpInt(32))
        setTrackThickness(jetStreamDpInt(2))
    }
}

class JetStreamDanmakuSectionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDanmakuSectionSurface()
    }
}

class JetStreamSliderSectionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogSectionSurface()
    }
}

open class JetStreamSettingControlRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        applyJetStreamSettingControlRowSurface()
        JetStreamAnimator.bindFocus(this, 1f, 0)
    }
}

class JetStreamDanmakuControlRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : JetStreamSettingControlRowLayout(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDanmakuControlRowSurface()
    }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        if (params is ViewGroup.MarginLayoutParams && params.bottomMargin == 0) {
            params.bottomMargin = jetStreamDpInt(8)
        }
        super.setLayoutParams(params)
    }
}

class JetStreamSheetToolbarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        applyJetStreamSheetToolbarSurface()
    }
}

class JetStreamDialogActionRailLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogActionRailSurface()
    }
}

class JetStreamDialogButtonRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        applyJetStreamDialogButtonRowSurface()
    }
}

class JetStreamDialogContentLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
    }
}

class JetStreamDialogSpacerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)

class JetStreamSheetChipRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    init {
        applyJetStreamSheetChipRowSurface()
    }
}

class JetStreamDialogListRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CustomRecyclerView(context, attrs, defStyleAttr) {

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.JetStreamDialogListRecyclerView, defStyleAttr, 0)
        val framed = typedArray.getBoolean(R.styleable.JetStreamDialogListRecyclerView_jetStreamListFramed, true)
        typedArray.recycle()

        clipChildren = false
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        if (framed) {
            background = jetStreamOverlayBackground(
                orientation = GradientDrawable.Orientation.TL_BR,
                cornerRadii = FloatArray(8) { jetStreamDp(22) }
            )
            elevation = jetStreamDp(6)
            clipToOutline = true
        } else {
            background = null
            elevation = 0f
            clipToOutline = false
        }
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        if (direction == View.FOCUS_UP && focused != null && nextFocusUpId != View.NO_ID) {
            val movedWithin = result != null && result !== focused && isDescendant(result)
            if (!movedWithin) {
                val target = rootView?.findViewById<View>(nextFocusUpId)
                if (target != null && target.isFocusable && target.isShown && target.isEnabled) return target
            }
        }
        return result
    }

    private fun isDescendant(view: View): Boolean {
        var parent: android.view.ViewParent? = view.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }
}

@SuppressLint("ResourceType")
private fun TextView.applyJetStreamDialogInputSurface(
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defaultTextSizeSp: Float = 18f
) {
    val typedArray = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.textSize, android.R.attr.textAppearance), defStyleAttr, 0)
    val hasTextSize = typedArray.hasValue(0)
    val hasTextAppearance = typedArray.hasValue(1)
    typedArray.recycle()

    includeFontPadding = true
    if (!hasTextSize && !hasTextAppearance) setTextSize(TypedValue.COMPLEX_UNIT_SP, defaultTextSizeSp)
    setHintTextColor(jetStreamColor(R.color.jetstream_on_surface_variant))
    setTextColor(jetStreamColor(R.color.jetstream_on_surface))
    applyJetStreamTypeface()
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), jetStreamDialogInputDrawable(R.color.jetstream_surface_container_high, R.color.jetstream_primary, 2))
        addState(intArrayOf(), jetStreamDialogInputDrawable(R.color.jetstream_surface_container_high, R.color.jetstream_outline_variant, 1))
    }
}

@SuppressLint("ResourceType")
private fun MaterialTextView.applyJetStreamDialogTextDefaults(
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defaultTextColorRes: Int,
    defaultTextSizeSp: Float
) {
    val typedArray = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.textColor, android.R.attr.textSize, android.R.attr.textAppearance), defStyleAttr, 0)
    val hasTextColor = typedArray.hasValue(0)
    val hasTextSize = typedArray.hasValue(1)
    val hasTextAppearance = typedArray.hasValue(2)
    typedArray.recycle()

    if (!hasTextColor) setTextColor(jetStreamColorStateList(defaultTextColorRes))
    if (!hasTextSize && !hasTextAppearance) setTextSize(TypedValue.COMPLEX_UNIT_SP, defaultTextSizeSp)
    includeFontPadding = false
    applyJetStreamTypeface()
}

private fun View.applyJetStreamSubtitleIconSurface() {
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = jetStreamDp(10)
            setColor(TvFocusStyle.FocusedContainer.toArgb())
            setStroke(jetStreamDpInt(1), TvFocusStyle.FocusOutline.toArgb())
        })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = jetStreamDp(10)
            setColor(TvFocusStyle.Container.toArgb())
        })
    }
    minimumWidth = jetStreamDpInt(40)
    minimumHeight = jetStreamDpInt(40)
    setPadding(jetStreamDpInt(10), jetStreamDpInt(10), jetStreamDpInt(10), jetStreamDpInt(10))
}

private fun AppCompatImageView.applyJetStreamControlIconTint() {
    if (ImageViewCompat.getImageTintList(this) == null) {
        ImageViewCompat.setImageTintList(this, JetStreamPalette.controlText())
    }
}

private fun MaterialButton.applyJetStreamDialogButtonSurface() {
    minimumHeight = maxOf(minimumHeight, jetStreamDpInt(40))
    insetTop = 0
    insetBottom = 0
    iconPadding = jetStreamDpInt(8)
    setPaddingRelative(maxOf(paddingStart, jetStreamDpInt(16)), paddingTop, maxOf(paddingEnd, jetStreamDpInt(16)), paddingBottom)
    setTextColor(JetStreamPalette.controlText())
    backgroundTintList = JetStreamPalette.controlContainer()
    iconTint = JetStreamPalette.controlText()
    rippleColor = jetStreamColorStateList(R.color.jetstream_scrim_medium)
    strokeColor = JetStreamPalette.controlOutline()
    strokeWidth = jetStreamDpInt(1)
    shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(jetStreamDp(10)).build()
    applyJetStreamTypeface()
}

private fun Chip.applyJetStreamFilterChipSurface() {
    isCheckable = true
    isCheckedIconVisible = true
    setTextColor(JetStreamPalette.controlText())
    checkedIconTint = JetStreamPalette.controlText()
    chipBackgroundColor = JetStreamPalette.controlContainer()
    chipStrokeColor = JetStreamPalette.controlOutline()
    chipStrokeWidth = jetStreamDp(1)
    rippleColor = jetStreamColorStateList(R.color.jetstream_scrim_medium)
    shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(jetStreamDp(10)).build()
    applyJetStreamTypeface()
}

private fun LinearLayoutCompat.applyJetStreamDialogSectionSurface() {
    background = jetStreamOverlayBackground(
        orientation = GradientDrawable.Orientation.TL_BR,
        cornerRadii = FloatArray(8) { jetStreamDp(24) }
    )
    elevation = 0f
    clipToOutline = true
    if (paddingLeft == 0 && paddingTop == 0 && paddingRight == 0 && paddingBottom == 0) {
        setPadding(0, jetStreamDpInt(12), 0, jetStreamDpInt(12))
    }
}

private fun LinearLayoutCompat.applyJetStreamDanmakuSectionSurface() {
    clipChildren = false
    clipToPadding = false
    background = null
    elevation = 0f
    if (paddingLeft == 0 && paddingTop == 0 && paddingRight == 0 && paddingBottom == 0) {
        setPadding(0, 0, 0, jetStreamDpInt(8))
    }
}

private fun LinearLayoutCompat.applyJetStreamSettingControlRowSurface() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    orientation = LinearLayoutCompat.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), jetStreamSettingControlRowDrawable(R.color.jetstream_primary_container, R.color.jetstream_primary, 2))
        addState(intArrayOf(android.R.attr.state_pressed), jetStreamSettingControlRowDrawable(R.color.jetstream_primary_container, R.color.jetstream_primary, 2))
        addState(intArrayOf(), jetStreamSettingControlRowDrawable(R.color.jetstream_scrim_light, 0, 0))
    }
    minimumHeight = maxOf(minimumHeight, jetStreamDpInt(48))
    clipToOutline = true
    setPadding(left, top, right, bottom)
}

private fun LinearLayoutCompat.applyJetStreamDanmakuControlRowSurface() {
    if (paddingLeft == 0 && paddingTop == 0 && paddingRight == 0 && paddingBottom == 0) {
        setPadding(jetStreamDpInt(24), jetStreamDpInt(6), jetStreamDpInt(24), jetStreamDpInt(6))
    }
}

private fun LinearLayoutCompat.applyJetStreamSheetToolbarSurface() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    background = jetStreamSheetToolbarDrawable()
    clipToOutline = true
    setPadding(left, top, right, bottom)
}

private fun LinearLayoutCompat.applyJetStreamDialogActionRailSurface() {
    background = jetStreamOverlayBackground(
        orientation = GradientDrawable.Orientation.TL_BR,
        cornerRadii = FloatArray(8) { jetStreamDp(24) }
    )
    elevation = 0f
    clipToOutline = true
    if (paddingLeft == 0 && paddingTop == 0 && paddingRight == 0 && paddingBottom == 0) {
        val padding = jetStreamDpInt(10)
        setPadding(padding, padding, padding, padding)
    }
}

private fun LinearLayoutCompat.applyJetStreamDialogButtonRowSurface() {
    clipChildren = false
    clipToPadding = false
}

private fun LinearLayoutCompat.applyJetStreamSheetChipRowSurface() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    background = jetStreamDialogRowDrawable()
    minimumHeight = jetStreamDpInt(56)
    clipToOutline = true
    setPadding(left, if (top == 0) jetStreamDpInt(8) else top, right, bottom)
}

private fun View.jetStreamSettingControlRowDrawable(colorRes: Int, strokeColorRes: Int, strokeWidthDp: Int): GradientDrawable {
    return GradientDrawable().apply {
        cornerRadius = jetStreamDp(18)
        setColor(jetStreamColor(colorRes))
        if (strokeWidthDp > 0) {
            setStroke(jetStreamDpInt(strokeWidthDp), jetStreamColor(strokeColorRes))
        } else {
            setStroke(0, Color.TRANSPARENT)
        }
    }
}

private fun View.jetStreamSheetToolbarDrawable(): GradientDrawable {
    return GradientDrawable().apply {
        cornerRadius = jetStreamDp(24)
        setColor(jetStreamColor(R.color.jetstream_scrim_light))
    }
}

private fun View.jetStreamDialogRowDrawable(): GradientDrawable {
    return GradientDrawable().apply {
        cornerRadius = jetStreamDp(24)
        setColor(jetStreamColor(R.color.jetstream_scrim_light))
    }
}

private fun View.jetStreamDialogInputDrawable(colorRes: Int, strokeColorRes: Int, strokeWidthDp: Int): GradientDrawable {
    return GradientDrawable().apply {
        cornerRadius = jetStreamDp(20)
        setColor(jetStreamColor(colorRes))
        setStroke(jetStreamDpInt(strokeWidthDp), jetStreamColor(strokeColorRes))
    }
}
