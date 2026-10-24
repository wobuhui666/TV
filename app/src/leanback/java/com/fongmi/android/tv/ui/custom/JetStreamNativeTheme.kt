package com.fongmi.android.tv.ui.custom

import android.content.res.TypedArray
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import com.fongmi.android.tv.R
import com.fongmi.android.tv.event.RefreshEvent
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Collections
import java.util.WeakHashMap

/** Refresh only explicitly bound theme roles, retaining controls, focus and player surfaces. */
object JetStreamNativeTheme {
    private val attachedViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private var started = false

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onThemeEvent(event: RefreshEvent) {
        if (event.type != RefreshEvent.Type.THEME) return
        // Dialog windows are outside the Activity content tree.
        attachedViews.toList().forEach { view -> bindings(view)?.forEach { it() } }
    }

    @JvmStatic
    fun refresh(root: View) {
        bindings(root)?.forEach { it() }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) refresh(root.getChildAt(index))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindings(view: View): MutableList<() -> Unit>? =
        view.getTag(R.id.jetstream_theme_bindings) as? MutableList<() -> Unit>

    internal fun bind(view: View, update: () -> Unit) {
        if (!started) {
            started = true
            EventBus.getDefault().register(this)
        }
        val updates = bindings(view) ?: mutableListOf<() -> Unit>().also {
            view.setTag(R.id.jetstream_theme_bindings, it)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    attachedViews.add(view)
                    // RecyclerView may reattach a cached holder after the theme event.
                    bindings(view)?.forEach { update -> update() }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    attachedViews.remove(view)
                }
            })
            if (view.isAttachedToWindow) attachedViews.add(view)
        }
        updates.add(update)
        update()
    }
}

internal fun View.bindJetStreamTheme(update: () -> Unit) = JetStreamNativeTheme.bind(this, update)

/** Explicit XML literals and non-theme resources keep their intended colors. */
internal fun TextView.bindJetStreamTextColor(attributes: TypedArray, @ColorRes defaultColor: Int) {
    val color = if (attributes.hasValue(0)) attributes.getResourceId(0, 0) else defaultColor
    if (color == 0) return
    if (color != R.color.text && !resources.getResourceEntryName(color).startsWith("jetstream_")) return
    bindJetStreamTheme { setTextColor(jetStreamColorStateList(color)) }
}
