package com.fongmi.android.tv.ui.custom

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Short, cancellable transitions that follow the device's animation preference. */
object LibraryMotion {
    @JvmStatic fun enabled(view: View): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    @JvmStatic fun enter(view: View) {
        view.animate().cancel()
        if (!enabled(view)) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = LibraryViews.dp(8).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(JetStreamAnimator.PAGE_DURATION)
            .setInterpolator(DecelerateInterpolator(1.8f)).start()
    }

    @JvmStatic fun fadeIn(view: View) {
        view.animate().cancel()
        if (!enabled(view)) {
            view.alpha = 1f
            return
        }
        view.animate().alpha(1f).setDuration(JetStreamAnimator.PANEL_DURATION)
            .setInterpolator(DecelerateInterpolator(1.8f)).start()
    }
}
