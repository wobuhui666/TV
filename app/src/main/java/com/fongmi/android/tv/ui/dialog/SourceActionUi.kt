package com.fongmi.android.tv.ui.dialog

import android.app.Activity
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.fongmi.android.tv.App
import com.fongmi.android.tv.Constant
import com.github.catvod.Init
import com.github.catvod.crawler.Spider
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.function.Supplier

/** A source action owns its queued UI and windows until the user finishes or leaves its page. */
class SourceActionUi private constructor(
    activity: FragmentActivity,
    page: LifecycleOwner,
    private var actionKey: String?,
    private var isCurrentPage: BooleanSupplier?,
    private var onCancel: Runnable?,
) : Supplier<Activity?>, LifecycleEventObserver {
    private val activity = WeakReference(activity)
    private val page = WeakReference(page)
    private val closed = AtomicBoolean()
    private val windows = mutableMapOf<View, WindowManager>()
    private val sourceNames = ConcurrentHashMap.newKeySet<String>()
    private val constructors = mutableSetOf<String>()
    @Volatile private var closedAt = 0L
    private var actionFinished = false
    @Volatile private var awaitingLegacyWindow = false
    private var pendingWindows = 0
    private val windowDeadline = Runnable { awaitingLegacyWindow = false; releaseIfFinished() }
    private val retireLegacyBindings = Runnable {
        removeSourceBindings()
        retiredConstructors.entries.toList().forEach { (name, request) ->
            if (request === this) retiredConstructors.remove(name, this)
        }
    }

    private fun removeSourceBindings() {
        sourceNames.forEach { name -> sources.remove(name, this) }
    }

    private fun bindSource(name: String) {
        if (closed.get() && SystemClock.uptimeMillis() - closedAt >= Constant.TIMEOUT_VOD) return
        sourceNames.add(name)
        awaitingLegacyWindow = true
        sources[name] = this
        if (closed.get()) App.post {
            App.removeCallbacks(retireLegacyBindings)
            App.post(retireLegacyBindings, (closedAt + Constant.TIMEOUT_VOD - SystemClock.uptimeMillis()).coerceAtLeast(0))
        }
    }

    private fun rememberConstructor(trace: Array<StackTraceElement>) {
        val frame = trace.firstOrNull { frame -> sourceNames.any { matchesSource(frame, it) } } ?: return
        val key = constructorKey(frame)
        constructors.add(key)
        if (closed.get()) {
            if (SystemClock.uptimeMillis() - closedAt < Constant.TIMEOUT_VOD) retiredConstructors[key] = this
            removeSourceBindings()
        }
    }

    override fun get(): Activity? = activity.get()?.takeIf { !closed.get() && !it.isFinishing && !it.isDestroyed }

    private fun isUsable(): Boolean = get() != null && isCurrentPage?.asBoolean == true &&
        activity.get()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true &&
        page.get()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    fun finishAction(hasFeedback: Boolean) {
        actionFinished = true
        if (hasFeedback) awaitingLegacyWindow = false
        if (awaitingLegacyWindow) App.post(windowDeadline, Constant.TIMEOUT_VOD.toLong())
        releaseIfFinished()
    }

    fun cancel() = release(true)

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) cancel()
    }

    private fun releaseIfFinished() {
        if (actionFinished && !awaitingLegacyWindow && pendingWindows == 0 && windows.isEmpty()) release(false)
    }

    private fun release(cancel: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed.get()) return
        closedAt = SystemClock.uptimeMillis()
        if (!closed.compareAndSet(false, true)) return
        // Once a window is known, only its constructor can be a late callback.
        // Other login/configuration forms in the same JAR keep their own UI.
        if (constructors.isNotEmpty()) {
            constructors.forEach { retiredConstructors[it] = this }
            removeSourceBindings()
        } else if (!awaitingLegacyWindow) removeSourceBindings()
        if (sourceNames.isNotEmpty()) App.post(retireLegacyBindings, Constant.TIMEOUT_VOD.toLong())
        activity.get()?.lifecycle?.removeObserver(this)
        page.get()?.lifecycle?.removeObserver(this)
        activity.get()?.let { active.remove(it, this) }
        App.removeCallbacks(windowDeadline)
        awaitingLegacyWindow = false
        isCurrentPage = null
        actionKey = null
        val cancellation = onCancel
        onCancel = null
        // Public dialogs close through their futures first. Legacy windows then close
        // through their Window callback, without changing the source's dismiss listener.
        if (cancel) cancellation?.run()
        val remaining = windows.toMap()
        windows.clear()
        // Close during ON_PAUSE, while the owner's token and dialog state are
        // still live; waiting until destruction can leave a detached Dialog open.
        remaining.forEach { (view, manager) -> closeWindow(view, manager) }
    }

    @RequiresApi(29)
    private fun captureWindow(manager: WindowManager, userAction: Boolean) {
        val before = WindowInspector.getGlobalWindowViews().toSet()
        val hadWindow = windows.keys.any { it.parent != null || it.isAttachedToWindow }
        pendingWindows++
        awaitingLegacyWindow = false
        App.removeCallbacks(windowDeadline)
        // Capture before Dialog constructs its window. This callback runs after its
        // addView in the same UI message, before the new window's first traversal.
        App.post {
            try {
                val added = WindowInspector.getGlobalWindowViews().filter { it !in before &&
                    (it.layoutParams as? WindowManager.LayoutParams)?.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION }
                for (view in added) {
                    // One constructor may query WINDOW_SERVICE more than once.
                    // Overlapping captures must not reject the window already owned here.
                    if (view in windows) continue
                    if (!isUsable() || ((hadWindow || windows.isNotEmpty()) && !userAction)) {
                        closeWindow(view, manager)
                    } else {
                        windows[view] = manager
                        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(view: View) = Unit
                            override fun onViewDetachedFromWindow(view: View) {
                                view.removeOnAttachStateChangeListener(this)
                                windows.remove(view)
                                // AlertDialog posts the next user step before its dismiss
                                // callback. Let that queued constructor retain the owner.
                                App.post { releaseIfFinished() }
                            }
                        })
                    }
                }
            } finally {
                pendingWindows--
                releaseIfFinished()
            }
        }
    }

    companion object {
        private val active = ConcurrentHashMap<FragmentActivity, SourceActionUi>()
        // Unscoped legacy callbacks have a bounded grace period. Retain weak pages
        // and observed constructors, never a permanent block on a whole source package.
        private val sources = ConcurrentHashMap<String, SourceActionUi>()
        private val retiredConstructors = ConcurrentHashMap<String, SourceActionUi>()

        private fun matchesSource(frame: StackTraceElement, name: String): Boolean =
            if (name.endsWith(".")) frame.className.startsWith(name)
            else frame.className == name || frame.className.startsWith(name + "\$")

        private fun constructorKey(frame: StackTraceElement): String = frame.className + "#" + frame.methodName

        @JvmStatic
        fun start(activity: FragmentActivity, page: LifecycleOwner, actionKey: String,
                  isCurrentPage: BooleanSupplier, onCancel: Runnable): SourceActionUi? {
            check(Looper.myLooper() == Looper.getMainLooper())
            active[activity]?.let { previous ->
                if (!previous.actionFinished || previous.pendingWindows > 0 || previous.windows.isNotEmpty() ||
                    (previous.awaitingLegacyWindow && previous.actionKey == actionKey)) return null
                previous.cancel()
            }
            val request = SourceActionUi(activity, page, actionKey, isCurrentPage, onCancel)
            if (!request.isUsable() || !activity.hasWindowFocus()) return null
            active[activity] = request
            activity.lifecycle.addObserver(request)
            if (page !== activity) page.lifecycle.addObserver(request)
            return request
        }

        @JvmStatic
        fun bind(spider: Spider) {
            val request = Init.currentActivityOwner() as? SourceActionUi ?: return
            if (!spider.javaClass.name.startsWith("com.github.catvod.spider.")) return
            request.bindSource("com.github.catvod.spider.")
        }

        @JvmStatic
        fun bind(bridge: Class<*>) {
            val request = Init.currentActivityOwner() as? SourceActionUi ?: return
            request.bindSource(bridge.name)
        }

        /** Activity must return an actual WindowManagerImpl: Window casts it internally. */
        @JvmStatic
        fun windowManager(original: WindowManager): WindowManager {
            if (Looper.myLooper() != Looper.getMainLooper()) return original
            val trace = Thread.currentThread().stackTrace
            if (trace.none { it.className == "android.app.Dialog" && it.methodName == "<init>" }) return original
            val current = Init.currentActivityOwner()
            val request = if (current != null) current as? SourceActionUi else {
                val bound = trace.firstNotNullOfOrNull { frame ->
                    sources.entries.firstOrNull { (name, _) -> matchesSource(frame, name) }?.value
                }
                bound?.takeUnless { it.closed.get() }
                    ?: trace.firstNotNullOfOrNull { retiredConstructors[constructorKey(it)] }
                    ?: bound
            }
            if (request == null) return original
            request.rememberConstructor(trace)
            val manager = request.get()?.windowManager ?: original
            if (Build.VERSION.SDK_INT >= 29) {
                val userAction = trace.any { it.methodName in setOf("performClick", "performItemClick") ||
                    it.className.endsWith("AlertController\$ButtonHandler") }
                request.captureWindow(manager, userAction)
            }
            return manager
        }

        private fun closeWindow(view: View, manager: WindowManager) {
            if (view.parent == null && !view.isAttachedToWindow) return
            // Android 13+ Dialog ignores BACK key events when predictive back is
            // enabled. An outside-cancel event still runs its public cancel path,
            // updates isShowing and preserves the source's dismiss listener.
            val now = SystemClock.uptimeMillis()
            val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try { view.dispatchTouchEvent(outside) }
            finally { outside.recycle() }
            if (view.parent == null && !view.isAttachedToWindow) return
            // A just-added DecorView has not received its first attach/traversal yet.
            // The tracked UP also cancels a legacy dialog before that first frame.
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK,
                0, 0, 0, 0, KeyEvent.FLAG_TRACKING))
            if (view.parent != null || view.isAttachedToWindow) manager.removeViewImmediate(view)
        }
    }
}
