package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.fongmi.android.tv.ui.theme.JetStreamPalette

/** One native focus target for a library action and its supporting description. */
class MyActionView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val icon = ImageView(context)
    private val title = TextView(context)
    private val description = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setPadding(jetStreamDpInt(24), jetStreamDpInt(16), jetStreamDpInt(24), jetStreamDpInt(16))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), surface(true))
            addState(intArrayOf(android.R.attr.state_pressed), surface(true))
            addState(intArrayOf(), surface(false))
        }
        icon.isDuplicateParentStateEnabled = true
        icon.imageTintList = JetStreamPalette.controlText()
        addView(icon, LayoutParams(jetStreamDpInt(30), jetStreamDpInt(30)))
        val copy = LinearLayout(context).apply {
            orientation = VERTICAL
            isDuplicateParentStateEnabled = true
        }
        addView(copy, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = jetStreamDpInt(20) })
        for (text in arrayOf(title, description)) {
            text.isDuplicateParentStateEnabled = true
            text.includeFontPadding = false
            text.setTextColor(JetStreamPalette.controlText())
            text.applyJetStreamTypeface()
            copy.addView(text)
        }
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.textSize = 18f
        title.setTypeface(title.typeface, Typeface.BOLD)
        description.textSize = 12f
        description.maxLines = 2
        description.ellipsize = TextUtils.TruncateAt.END
        description.setPadding(0, jetStreamDpInt(7), 0, 0)
        JetStreamAnimator.bindFocus(this, 1f, 0)
    }

    fun setContent(@DrawableRes image: Int, label: String, supportingText: String) {
        icon.setImageResource(image)
        title.text = label
        description.text = supportingText
        contentDescription = "$label, $supportingText"
    }

    private fun surface(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = jetStreamDp(12)
        setColor(JetStreamPalette.controlContainer().getColorForState(if (focused) intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused) else intArrayOf(android.R.attr.state_enabled), 0))
        setStroke(if (focused) jetStreamDpInt(1) else 0, if (focused) JetStreamPalette.controlOutline().getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused), 0) else Color.TRANSPARENT)
    }
}
