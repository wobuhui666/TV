package com.fongmi.android.tv.ui.activity;

import android.view.View;

import androidx.lifecycle.Lifecycle;

import com.fongmi.android.tv.ui.base.BaseActivity;

/** One focus generation for the input pane and its results, owned by the activity thread. */
final class SearchFocusGuard {

    private final BaseActivity activity;
    private long generation;
    private boolean closed;

    SearchFocusGuard(BaseActivity activity) {
        this.activity = activity;
    }

    long snapshot() {
        return generation;
    }

    void invalidate() {
        generation++;
    }

    void close() {
        closed = true;
        invalidate();
    }

    boolean canFocus(long request, View target) {
        return !closed && request == generation && !activity.isFinishing() && !activity.isDestroyed()
                && activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                && activity.hasWindowFocus() && target != null && target.isAttachedToWindow()
                && target.isShown() && target.isEnabled();
    }

    void post(View target, long request, long delay, Runnable action) {
        target.postDelayed(() -> {
            if (canFocus(request, target)) action.run();
        }, delay);
    }
}
