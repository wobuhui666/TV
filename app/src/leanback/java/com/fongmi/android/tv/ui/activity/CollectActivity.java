package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

/** Keeps links from discovery, favorites, and playback searches available. */
public class CollectActivity extends BaseActivity {

    private ActivityCollectBinding mBinding;
    private SearchResultsController results;
    private final SearchFocusGuard focus = new SearchFocusGuard(this);
    private long resumeFocusGeneration;
    private boolean initialFocus = true;

    public static void start(Activity activity, String keyword) {
        if (com.fongmi.android.tv.setting.ExperienceSettings.usesLibrary()) {
            LibrarySearchActivity.start(activity, keyword);
            return;
        }
        Intent intent = new Intent(activity, CollectActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (com.fongmi.android.tv.setting.ExperienceSettings.usesLibrary()) {
            LibrarySearchActivity.start(this, getIntent().getStringExtra("keyword"));
            finish();
            return;
        }
        results = new SearchResultsController(this, mBinding.results, false, focus);
        results.search(getIntent().getStringExtra("keyword"));
        results.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (results == null) return;
        results.search(intent.getStringExtra("keyword"));
        initialFocus = !hasWindowFocus();
        results.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) focus.invalidate();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (results != null) results.refreshFilter();
        focus.invalidate();
        resumeFocusGeneration = focus.snapshot();
        if (hasWindowFocus()) requestInitialFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) requestInitialFocus();
    }

    private void requestInitialFocus() {
        if (!initialFocus || results == null) return;
        initialFocus = false;
        results.requestFocus(resumeFocusGeneration);
    }

    @Override
    protected void onPause() {
        focus.invalidate();
        super.onPause();
    }

    @Override
    protected void onBackInvoked() {
        focus.invalidate();
        super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        focus.close();
        if (results != null) results.dispose();
        super.onDestroy();
    }
}
