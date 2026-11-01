package com.fongmi.android.tv.ui.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.custom.JetStreamDialogDecor
import com.fongmi.android.tv.ui.custom.JetStreamDialogButton
import com.fongmi.android.tv.ui.custom.applyJetStreamCursor
import com.fongmi.android.tv.ui.custom.applyJetStreamTypeface
import com.fongmi.android.tv.ui.custom.bindJetStreamTheme
import com.fongmi.android.tv.ui.custom.jetStreamColor
import com.fongmi.android.tv.ui.custom.jetStreamColorStateList
import com.fongmi.android.tv.ui.custom.jetStreamDp
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

/** The shared source form uses the same palette, typeface and focus treatment as TV sheets. */
object SpiderDialogStyle {
    @JvmStatic
    fun copyButton(context: Context): MaterialButton = JetStreamDialogButton(context)

    @JvmStatic
    fun apply(dialog: AlertDialog) {
        val window = dialog.window ?: return
        val decor = window.decorView
        window.setWindowAnimations(R.style.JetStreamDialogAnim)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setLayout((decor.resources.displayMetrics.widthPixels * 0.68f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        val background = GradientDrawable().apply { cornerRadius = decor.jetStreamDp(28) }
        decor.bindJetStreamTheme { background.setColor(decor.jetStreamColor(R.color.jetstream_surface)) }
        window.setBackgroundDrawable(background)
        style(decor)
        addScrollHints(decor)
        JetStreamDialogDecor.tintButtons(dialog)
    }

    private fun addScrollHints(view: View) {
        if (view is ScrollView) {
            val parent = view.parent as? ViewGroup ?: return
            if (parent is ScrollHints) return
            val index = parent.indexOfChild(view)
            val params = view.layoutParams
            parent.removeViewAt(index)
            parent.addView(ScrollHints(view), index, params)
            return
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) addScrollHints(view.getChildAt(index))
    }

    /** Paint in the form's existing side gutter without adding a focus or touch target. */
    private class ScrollHints(private val scroll: ScrollView) : FrameLayout(scroll.context) {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = jetStreamDp(1) * 1.5f
        }
        private val scrollChanged = ViewTreeObserver.OnScrollChangedListener { invalidate() }

        init {
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            bindJetStreamTheme {
                paint.color = jetStreamColor(R.color.jetstream_on_surface_variant)
                paint.alpha = 112
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            scroll.viewTreeObserver.addOnScrollChangedListener(scrollChanged)
        }

        override fun onDetachedFromWindow() {
            if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnScrollChangedListener(scrollChanged)
            super.onDetachedFromWindow()
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val x = scroll.right - jetStreamDp(12)
            if (scroll.canScrollVertically(-1)) arrow(canvas, x, scroll.top + jetStreamDp(12), -1)
            if (scroll.canScrollVertically(1)) arrow(canvas, x, scroll.bottom - jetStreamDp(12), 1)
        }

        private fun arrow(canvas: Canvas, x: Float, y: Float, direction: Int) {
            val halfWidth = jetStreamDp(3)
            val halfHeight = jetStreamDp(1) * 1.5f
            path.reset()
            path.moveTo(x - halfWidth, y - direction * halfHeight)
            path.lineTo(x, y + direction * halfHeight)
            path.lineTo(x + halfWidth, y - direction * halfHeight)
            canvas.drawPath(path, paint)
        }
    }

    private fun style(view: View) {
        if (view is TextView) {
            view.applyJetStreamTypeface()
            view.applyJetStreamCursor()
            if (view !is MaterialButton) view.bindJetStreamTheme {
                view.setTextColor(view.jetStreamColorStateList(R.color.jetstream_on_surface))
                view.setHintTextColor(view.jetStreamColor(R.color.jetstream_on_surface_variant))
            }
        }
        if (view is TextInputLayout) view.bindJetStreamTheme {
            view.defaultHintTextColor = view.jetStreamColorStateList(R.color.jetstream_on_surface_variant)
            view.setBoxStrokeColorStateList(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(view.jetStreamColor(R.color.jetstream_outline), view.jetStreamColor(R.color.jetstream_primary), view.jetStreamColor(R.color.jetstream_on_surface_variant))
            ))
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) style(view.getChildAt(index))
    }
}
