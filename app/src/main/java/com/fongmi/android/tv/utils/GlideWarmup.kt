package com.fongmi.android.tv.utils

import android.app.Activity
import android.content.ContextWrapper
import android.os.Process
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.fongmi.android.tv.App
import java.util.IdentityHashMap

/** A first image waits asynchronously for initialization, without taking Glide's cold lock on main. */
object GlideWarmup {
    private val lock = Any()
    private val pending = IdentityHashMap<ImageView, Pending>()
    @Volatile private var ready = false
    private var starting = false

    @JvmStatic fun start() {
        FontWarmup.start()
        synchronized(lock) {
            if (ready || starting) return
            starting = true
        }
        Task.execute {
            val priority = Process.getThreadPriority(Process.myTid())
            var initialized = false
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                // Background retrieval uses the application manager, with no Activity or View lifetime.
                Glide.with(App.get())
                initialized = true
            } catch (error: Throwable) {
                error.printStackTrace()
            } finally {
                Process.setThreadPriority(priority)
                val requests = synchronized(lock) {
                    ready = initialized
                    starting = false
                    pending.values.toList()
                }
                val success = initialized
                // Each callback removes its retained view on success, failure, replacement or clear.
                // Dispatch individually so creating all visible requests cannot occupy one long turn.
                for (request in requests) App.post { deliver(request, success) }
            }
        }
    }

    @JvmStatic fun defer(view: ImageView, load: Runnable, failure: Runnable): Boolean {
        synchronized(lock) {
            if (ready) return false
            pending[view] = Pending(view, load, failure)
        }
        start()
        return true
    }

    @JvmStatic fun cancel(view: ImageView) {
        synchronized(lock) { pending.remove(view) }
    }

    private fun deliver(request: Pending, success: Boolean) {
        synchronized(lock) {
            if (pending[request.view] !== request) return
            pending.remove(request.view)
        }
        var context = request.view.context
        while (context is ContextWrapper) {
            if (context is Activity && (context.isFinishing || context.isDestroyed)) return
            val base = context.baseContext
            if (base === context) break
            context = base
        }
        if (success) request.load.run() else request.failure.run()
    }

    private data class Pending(val view: ImageView, val load: Runnable, val failure: Runnable)
}
