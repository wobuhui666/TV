package com.fongmi.android.tv.test;

import android.widget.FrameLayout;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.ui.base.BaseActivity;

/** Blank debug host with the same source-window interception as the production home. */
public final class SourceDialogTestActivity extends BaseActivity {

    @Override
    protected ViewBinding getBinding() {
        FrameLayout root = new FrameLayout(this);
        return () -> root;
    }

    @Override
    protected boolean customWall() {
        return false;
    }
}
