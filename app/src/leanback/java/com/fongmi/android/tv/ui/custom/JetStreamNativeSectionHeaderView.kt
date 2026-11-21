package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import com.fongmi.android.tv.R

/** The shared section style without a composition for frequently edited search labels. */
class JetStreamNativeSectionHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private val label = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = jetStreamDpInt(52)
        setPadding(0, jetStreamDpInt(16), 0, jetStreamDpInt(8))
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        val accent = GradientDrawable().apply { cornerRadius = jetStreamDp(2) }
        addView(View(context).apply { background = accent }, LayoutParams(jetStreamDpInt(4), jetStreamDpInt(22)))
        bindJetStreamTheme { accent.setColor(jetStreamColor(R.color.jetstream_primary)) }

        label.apply {
            textSize = 22f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (semibold == null) semibold = runCatching { ResourcesCompat.getFont(context, R.font.misans_semibold) }.getOrNull()
            if (semibold != null) typeface = semibold else applyJetStreamTypeface()
            TextViewCompat.setLineHeight(this, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28f, resources.displayMetrics).toInt())
            bindJetStreamTheme { setTextColor(jetStreamColor(R.color.jetstream_on_surface)) }
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = jetStreamDpInt(12) })
        val attributes = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.text), defStyleAttr, 0)
        setText(attributes.getText(0))
        attributes.recycle()
    }

    fun setText(text: CharSequence?) {
        val value = text?.toString().orEmpty()
        if (label.text.toString() != value) label.text = value
    }

    fun setText(resId: Int) { setText(if (resId == 0) "" else context.getText(resId)) }

    private companion object {
        var semibold: Typeface? = null
    }
}
