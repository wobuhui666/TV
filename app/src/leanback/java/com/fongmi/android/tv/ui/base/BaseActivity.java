package com.fongmi.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.custom.JetStreamWallView;
import com.fongmi.android.tv.ui.theme.JetStreamThemeController;
import com.fongmi.android.tv.ui.theme.JetStreamWallpaper;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import me.jessyan.autosize.AutoSizeCompat;

public abstract class BaseActivity extends AppCompatActivity {

    private JetStreamWallView wallpaper;

    protected abstract ViewBinding getBinding();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        JetStreamWallpaper.start();
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        Util.hideSystemUI(this);
        setBackCallback();
        initEvent();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        wallpaper = null;
        refreshWallpaper();
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    protected boolean customWall() {
        return JetStreamWallpaper.isVisible();
    }

    private void refreshWallpaper() {
        if (isFinishing() || isDestroyed()) return;
        ViewGroup content = findViewById(android.R.id.content);
        if (customWall() && wallpaper == null && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            wallpaper = new JetStreamWallView(this);
            content.addView(wallpaper, 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        } else if (!customWall() && wallpaper != null) {
            content.removeView(wallpaper);
            wallpaper = null;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onWallpaperVisibility(JetStreamWallpaper.VisibilityEvent event) {
        refreshWallpaper();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshWallpaper();
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyDataSetChanged());
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    private Resources hackResources(Resources resources) {
        try {
            AutoSizeCompat.autoConvertDensityOfGlobal(resources);
            return resources;
        } catch (Exception ignored) {
            return resources;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onThemeEvent(RefreshEvent event) {
        if (event.getType() != RefreshEvent.Type.THEME) return;
        JetStreamThemeController.refresh();
        onThemeChanged();
    }

    protected void onThemeChanged() {
        if (!isFinishing() && !isDestroyed()) recreate();
    }

    @Override
    public Resources getResources() {
        return hackResources(super.getResources());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
