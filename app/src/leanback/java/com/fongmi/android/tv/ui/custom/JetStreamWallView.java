package com.fongmi.android.tv.ui.custom;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Lifecycle;

import com.fongmi.android.tv.event.ConfigEvent;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/** Preserves wallpaper playback while allowing the TV background to be switched off. */
public final class JetStreamWallView extends CustomWallView {

    private final ComponentActivity activity;
    private boolean released;

    public JetStreamWallView(ComponentActivity activity) {
        super(activity, null);
        this.activity = activity;
    }

    @Override
    protected void theme() {
        // JetStreamWallpaper computes colors once, in the background, even when hidden.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) onPause(activity);
    }

    @Override
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (released) return;
        super.onConfigEvent(event);
        if (!activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) onPause(activity);
    }

    @Override
    protected void onDetachedFromWindow() {
        activity.getLifecycle().removeObserver(this);
        onDestroy(activity);
        super.onDetachedFromWindow();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        if (released) return;
        released = true;
        super.onDestroy(owner);
    }
}
