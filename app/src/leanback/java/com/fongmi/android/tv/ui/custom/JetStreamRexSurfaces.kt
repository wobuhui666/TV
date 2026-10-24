package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import com.fongmi.android.tv.ui.rex.RexTheme
import com.google.android.material.textview.MaterialTextView

/**
 * Rex 精选大卡上的叠加文案：恒白字 + 阴影（不随主题切换），
 * 由 JetStreamRexScrimView 的固定深色底部渐变保证两套主题下的可读性。
 * 图片叠字颜色统一取自 RexTheme。
 */
class JetStreamRexCardTitleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamTypeface()
        setTextColor(RexTheme.spotlightTitleColor())
        setTypeface(typeface, Typeface.BOLD)
        setShadowLayer(6f, 0f, 1.5f, RexTheme.spotlightShadowColor())
    }
}

/** 精选大卡第一行（栏目名）：比片名小一号，略作压暗。 */
class JetStreamRexCardLabelTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamTypeface()
        setTextColor(RexTheme.spotlightLabelColor())
        setTypeface(typeface, Typeface.BOLD)
        setShadowLayer(6f, 0f, 1.5f, RexTheme.spotlightShadowColor())
    }
}

/** 精选大卡左侧装饰引号字形。 */
class JetStreamRexQuoteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialTextView(context, attrs, defStyleAttr) {

    init {
        applyJetStreamTypeface()
        setTextColor(RexTheme.spotlightQuoteColor())
        setTypeface(typeface, Typeface.BOLD)
        setShadowLayer(6f, 0f, 1.5f, RexTheme.spotlightShadowColor())
    }
}

/** Image overlays keep a dark gradient because their title and label stay white. */
class JetStreamRexScrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, RexTheme.spotlightScrimColor())
        ).apply {
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, jetStreamDp(16), jetStreamDp(16), jetStreamDp(16), jetStreamDp(16))
        }
    }
}
