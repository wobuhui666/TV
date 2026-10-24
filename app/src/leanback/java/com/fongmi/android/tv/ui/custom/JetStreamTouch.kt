package com.fongmi.android.tv.ui.custom

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** Keep the touched card as the next D-pad anchor without stealing a drag. */
object JetStreamTouch {
    @JvmStatic
    fun install(recycler: RecyclerView) {
        if (recycler.getTag(com.fongmi.android.tv.R.id.jetstream_touch_installed) == true) return
        recycler.setTag(com.fongmi.android.tv.R.id.jetstream_touch_installed, true)
        val slop = ViewConfiguration.get(recycler.context).scaledTouchSlop
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f
            private var dragging = false
            private var target: View? = null

            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        dragging = false
                        target = rv.findChildViewUnder(event.x, event.y)
                    }
                    MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) dragging = true
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                        dragging = true
                        target = null
                    }
                    MotionEvent.ACTION_UP -> {
                        val child = target
                        if (!dragging && child != null && child === rv.findChildViewUnder(event.x, event.y) && !containsRecycler(child)) {
                            focusable(child)?.let { view ->
                                view.isFocusableInTouchMode = true
                                view.requestFocus()
                            }
                        }
                        target = null
                    }
                }
                return false
            }
        })
    }

    // A TV page often nests horizontal rails inside a vertical grid. The inner
    // recycler owns the tapped card; focusing its host here can restore an old item.
    private fun containsRecycler(view: View): Boolean {
        if (view is RecyclerView) return true
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (containsRecycler(view.getChildAt(index))) return true
        }
        return false
    }

    private fun focusable(view: View): View? {
        if (!view.isShown || !view.isEnabled) return null
        if (view.isFocusable) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            focusable(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
