package com.fongmi.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.custom.JetStreamWallView;
import com.fongmi.android.tv.ui.custom.JetStreamNativeTheme;
import com.fongmi.android.tv.ui.theme.JetStreamThemeController;
import com.fongmi.android.tv.ui.theme.JetStreamWallpaper;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.ui.theme.RexTheme;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import me.jessyan.autosize.AutoSizeCompat;

public abstract class BaseActivity extends AppCompatActivity {

    private JetStreamWallView wallpaper;
    private boolean appliedLightTheme;

    protected abstract ViewBinding getBinding();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedLightTheme = RexTheme.isLight();
        if (appliedLightTheme) setTheme(R.style.Theme_App_Light);
        super.onCreate(savedInstanceState);
        JetStreamWallpaper.start();
        applyThemeWindow();
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
        refreshSystemTheme();
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
        appliedLightTheme = RexTheme.isLight();
        setTheme(appliedLightTheme ? R.style.Theme_App_Light : R.style.Theme_App);
        JetStreamThemeController.refresh();
        applyThemeWindow();
        JetStreamNativeTheme.refresh(findViewById(android.R.id.content));
        onThemeChanged();
    }

    private void applyThemeWindow() {
        getWindow().setBackgroundDrawable(new ColorDrawable(JetStreamPalette.resolveColor(this, R.color.jetstream_background)));
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(RexTheme.isLight());
        controller.setAppearanceLightNavigationBars(RexTheme.isLight());
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
        refreshSystemTheme();
        Util.hideSystemUI(this);
    }

    private void refreshSystemTheme() {
        if (appliedLightTheme != RexTheme.isLight() && !isFinishing() && !isDestroyed()) RefreshEvent.theme();
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
