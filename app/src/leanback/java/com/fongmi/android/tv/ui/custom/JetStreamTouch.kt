package com.fongmi.android.tv.ui.custom

import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.compose.ui.platform.AbstractComposeView
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R

/** Coordinate TV native touch focus with the same anchors used by the D-pad. */
object JetStreamTouch {
    @JvmStatic
    fun install(recycler: RecyclerView) = RecyclerTouchFocus.install(recycler, true)

    @JvmStatic
    fun bindDirectTouch(view: View) {
        if (view is EditText || view is AbstractComposeView || view is RecyclerView) return
        if (view.getTag(R.id.jetstream_touch_direct_bound) == true) return
        view.setTag(R.id.jetstream_touch_direct_bound, true)
        view.setOnTouchListener { target, event ->
            focusDirect(target, event)
            false
        }
    }

    @JvmStatic
    fun focusDirect(view: View, event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN || event.pointerCount != 1) return
        if (!view.isEnabled || !view.isFocusable || (!view.isClickable && !view.isLongClickable)) return
        var parent = view.parent
        while (parent != null) {
            if (parent is RecyclerView) return // Recycler taps focus on UP so a drag never jumps.
            parent = parent.parent
        }
        view.isFocusableInTouchMode = true
        view.requestFocus()
    }
}
