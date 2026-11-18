package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

/** Keep a remote-focused card below the fixed navigation bar. */
class LibraryScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val delta = super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val inset = LibraryViews.dp(80)
        if (rect.top < scrollY + inset && rect.height() < height - inset) {
            return (rect.top - scrollY - inset).coerceAtLeast(-scrollY)
        }
        return delta
    }
}
