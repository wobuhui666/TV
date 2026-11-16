package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.LibraryViews;
import com.fongmi.android.tv.ui.custom.LibraryPalette;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public final class LibraryImagesActivity extends BaseActivity {
    private ImageView image;
    private LinearLayout root;
    private List<String> images;
    private int position;

    public static void start(Activity activity, List<String> images, int position) {
        activity.startActivity(new Intent(activity, LibraryImagesActivity.class).putStringArrayListExtra("images", new ArrayList<>(images)).putExtra("position", position));
    }
    @Override protected ViewBinding getBinding() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(LibraryPalette.surface());
        return new ViewBinding() { @Override public android.view.View getRoot() { return root; } };
    }
    @Override protected boolean customWall() { return false; }
    @Override protected void onThemeChanged() { root.setBackgroundColor(LibraryPalette.surface()); }
    @Override protected void initView(Bundle saved) {
        LibraryViews.bindAppearance(root, () -> root.setBackgroundColor(LibraryPalette.surface()));
        images = getIntent().getStringArrayListExtra("images");
        if (images == null || images.isEmpty()) { finish(); return; }
        position = saved == null ? getIntent().getIntExtra("position", 0) : saved.getInt("position");
        image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER); controls.setPadding(0, 0, 0, LibraryViews.dp(16)); root.addView(controls);
        TextView previous = LibraryViews.button(this, getString(R.string.library_prev_image)); controls.addView(previous); previous.setOnClickListener(view -> move(-1));
        TextView next = LibraryViews.button(this, getString(R.string.library_next_image)); controls.addView(next); next.setOnClickListener(view -> move(1));
        LinearLayout.LayoutParams previousLayout = (LinearLayout.LayoutParams) previous.getLayoutParams();
        previousLayout.setMarginEnd(LibraryViews.dp(12)); previous.setLayoutParams(previousLayout);
        previous.requestFocus(); move(0);
    }
    private void move(int by) { position = Math.floorMod(position + by, images.size()); ImgUtil.loadArtwork("", images.get(position), image, null); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && images != null && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) { move(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1); return true; }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("position", position); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { if (image != null) ImgUtil.clear(image); super.onDestroy(); }
}
