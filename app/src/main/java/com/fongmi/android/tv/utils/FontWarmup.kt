package com.fongmi.android.tv.utils

import android.graphics.Typeface
import android.os.Process
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.fongmi.android.tv.App
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Cache brand fonts off main; a cold view retains its current font until the exact face is ready. */
object FontWarmup {
    private val fonts = ConcurrentHashMap<Int, Typeface>()
    private val loading = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()
    private val desired = WeakHashMap<TextView, Selection>()
    private var started = false

    @JvmStatic fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        Task.execute {
            val context = App.get()
            // The mobile source set has no TV font resources.
            for (name in arrayOf("misans", "misans_medium", "misans_semibold")) {
                val resource = context.resources.getIdentifier(name, "font", context.packageName)
                if (resource != 0) request(resource)
            }
        }
    }

    @JvmStatic fun apply(view: TextView, resource: Int, preserveStyle: Boolean) {
        val selection = Selection(resource, if (preserveStyle) view.typeface?.style ?: Typeface.NORMAL else null)
        synchronized(lock) { desired[view] = selection }
        val cached = fonts[resource]
        if (cached != null) applyFace(view, cached, selection.style)
        else request(resource)
    }

    private fun request(resource: Int) {
        if (fonts.containsKey(resource) || !loading.add(resource)) return
        Task.execute {
            val priority = Process.getThreadPriority(Process.myTid())
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val font = ResourcesCompat.getFont(App.get(), resource) ?: return@execute
                fonts[resource] = font
                val views = synchronized(lock) {
                    desired.entries.filter { it.value.resource == resource }
                        .map { WeakReference(it.key) to it.value }
                }
                for ((reference, selection) in views) App.post {
                    val view = reference.get() ?: return@post
                    if (synchronized(lock) { desired[view] === selection }) applyFace(view, font, selection.style)
                }
            } catch (error: Throwable) {
                error.printStackTrace()
            } finally {
                loading.remove(resource)
                Process.setThreadPriority(priority)
            }
        }
    }

    private fun applyFace(view: TextView, font: Typeface, style: Int?) {
        if (style == null || style == Typeface.NORMAL) view.typeface = font
        else view.setTypeface(font, style)
    }

    private data class Selection(val resource: Int, val style: Int?)
}
