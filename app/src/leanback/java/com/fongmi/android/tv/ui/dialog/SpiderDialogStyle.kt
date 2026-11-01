package com.fongmi.android.tv.ui.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
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
            if (view.tag !is ScrollHints) view.tag = ScrollHints(view)
            return
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) addScrollHints(view.getChildAt(index))
    }

    /** The parent overlay keeps the original ScrollView measurement and button-panel space. */
    private class ScrollHints(private val scroll: ScrollView) : Drawable(), View.OnAttachStateChangeListener {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = scroll.jetStreamDp(1) * 1.5f
        }
        private val scrollChanged = ViewTreeObserver.OnScrollChangedListener { invalidateSelf() }
        private val layoutChanged = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        private var host: View? = null
        private var observer: ViewTreeObserver? = null

        init {
            scroll.bindJetStreamTheme {
                paint.color = scroll.jetStreamColor(R.color.jetstream_on_surface_variant)
                paint.alpha = 112
                invalidateSelf()
            }
            scroll.addOnAttachStateChangeListener(this)
            if (scroll.isAttachedToWindow) attach()
        }

        private fun attach() {
            if (host != null) return
            host = scroll.parent as? View ?: return
            host?.overlay?.add(this)
            observer = scroll.viewTreeObserver.also { it.addOnScrollChangedListener(scrollChanged) }
            scroll.addOnLayoutChangeListener(layoutChanged)
            updateBounds()
        }

        override fun onViewAttachedToWindow(view: View) = attach()

        override fun onViewDetachedFromWindow(view: View) {
            observer?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollChanged)
            observer = null
            scroll.removeOnLayoutChangeListener(layoutChanged)
            host?.overlay?.remove(this)
            host = null
        }

        private fun updateBounds() {
            setBounds(scroll.left, scroll.top, scroll.right, scroll.bottom)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (!scroll.isShown || bounds.isEmpty) return
            val x = bounds.right - scroll.jetStreamDp(12)
            if (scroll.canScrollVertically(-1)) arrow(canvas, x, bounds.top + scroll.jetStreamDp(12), -1)
            if (scroll.canScrollVertically(1)) arrow(canvas, x, bounds.bottom - scroll.jetStreamDp(12), 1)
        }

        private fun arrow(canvas: Canvas, x: Float, y: Float, direction: Int) {
            val halfWidth = scroll.jetStreamDp(3)
            val halfHeight = scroll.jetStreamDp(1) * 1.5f
            path.reset()
            path.moveTo(x - halfWidth, y - direction * halfHeight)
            path.lineTo(x, y + direction * halfHeight)
            path.lineTo(x + halfWidth, y - direction * halfHeight)
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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
