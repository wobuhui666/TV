package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.FrameLayout

/** The artwork's parallax stays inside the hero, even when outer rails allow overflow. */
class LibraryHeroFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
