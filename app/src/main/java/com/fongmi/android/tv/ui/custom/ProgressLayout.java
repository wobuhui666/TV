package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.fongmi.android.tv.databinding.ViewEmptyBinding;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.List;

public class ProgressLayout extends RelativeLayout {

    private static final String TAG_PROGRESS = "ProgressLayout.TAG_PROGRESS";

    public enum State {
        CONTENT, PROGRESS, EMPTY
    }

    private List<View> mContentViews;
    private View mProgressView;
    private View mEmptyView;
    private View mLastFocus;
    private State mState;

    public ProgressLayout(Context context) {
        super(context);
        init();
    }

    public ProgressLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ProgressLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        mState = State.CONTENT;
        mContentViews = new ArrayList<>();
        initView();
    }

    private void initView() {
        mEmptyView = ViewEmptyBinding.inflate(LayoutInflater.from(getContext())).getRoot();
        mEmptyView.setTag(TAG_PROGRESS);
        mEmptyView.setVisibility(GONE);
        mProgressView = ViewProgressBinding.inflate(LayoutInflater.from(getContext())).getRoot();
        mProgressView.setTag(TAG_PROGRESS);
        mProgressView.setVisibility(GONE);
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(CENTER_IN_PARENT);
        addView(mProgressView, params);
        addView(mEmptyView, params);
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        if (child.getTag() == null || !child.getTag().equals(TAG_PROGRESS)) {
            mContentViews.add(child);
        }
    }

    public void showProgress() {
        switchState(State.PROGRESS);
    }

    public void showEmpty() {
        switchState(State.EMPTY);
    }

    public void showContent() {
        switchState(State.CONTENT);
    }

    public void showContent(boolean flag, int size) {
        if (flag && size == 0) showEmpty();
        else showContent();
    }

    public boolean isProgress() {
        return mState == State.PROGRESS;
    }

    public boolean isContent() {
        return mState == State.CONTENT;
    }

    public boolean isEmpty() {
        return mState == State.EMPTY;
    }

    public void switchState(State state) {
        if (mState == state) return;
        boolean restoreFocus = shouldRestoreFocus(state);
        mState = state;
        switch (state) {
            case CONTENT:
                hideStateView(mEmptyView);
                hideStateView(mProgressView);
                setContentVisibility(true);
                if (restoreFocus) restoreContentFocus();
                else clearStateFocusability();
                break;
            case PROGRESS:
                hideStateView(mEmptyView);
                showStateView(mProgressView);
                setContentVisibility(false);
                requestStateFocus(State.PROGRESS, restoreFocus);
                break;
            case EMPTY:
                showStateView(mEmptyView);
                hideStateView(mProgressView);
                setContentVisibility(false);
                requestStateFocus(State.EMPTY, restoreFocus);
                break;
        }
    }

    private boolean shouldRestoreFocus(State state) {
        if (!Util.isLeanback()) return false;
        if (state == State.CONTENT) {
            boolean restoreFocus = hasFocus();
            if (!restoreFocus) mLastFocus = null;
            return restoreFocus;
        }
        for (View view : mContentViews) {
            if (view.hasFocus()) {
                mLastFocus = findFocus();
                return true;
            }
        }
        return false;
    }

    private void requestStateFocus(State state, boolean restoreFocus) {
        if (!restoreFocus) return;
        setFocusable(true);
        setFocusableInTouchMode(true);
        post(() -> {
            if (mState != state) return;
            if (isShown() && isEnabled()) requestFocus();
        });
    }

    private void restoreContentFocus() {
        postDelayed(() -> {
            if (mState != State.CONTENT) return;
            View target = canRequestFocus(mLastFocus) ? mLastFocus : findFocusableContent();
            mLastFocus = null;
            if (target != null) target.requestFocus();
            clearStateFocusability();
        }, 200);
    }

    private void clearStateFocusability() {
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    private View findFocusableContent() {
        for (View view : mContentViews) {
            View target = findFocusable(view);
            if (target != null) return target;
        }
        return null;
    }

    private View findFocusable(View view) {
        if (!canRequestFocus(view)) return null;
        if (view.isFocusable()) return view;
        if (!(view instanceof ViewGroup group)) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View target = findFocusable(group.getChildAt(i));
            if (target != null) return target;
        }
        return null;
    }

    private boolean canRequestFocus(View view) {
        return view != null && view.isShown() && view.isEnabled();
    }

    private void setContentVisibility(boolean visible) {
        for (View view : mContentViews) {
            if (visible) showView(view);
            else hideView(view);
        }
    }

    private void showView(View view) {
        view.animate().cancel();
        if (!view.isLaidOut()) {
            view.setAlpha(1f);
            view.setTranslationY(0f);
            view.setVisibility(VISIBLE);
            return;
        }
        if (!Util.isLeanback()) {
            view.setAlpha(0f);
            view.setVisibility(VISIBLE);
            view.animate().alpha(1f).setDuration(100);
            return;
        }
        view.setAlpha(0f);
        view.setTranslationY(dp(10));
        view.setVisibility(VISIBLE);
        view.animate().alpha(1f).translationY(0f).setDuration(180);
    }

    private void hideView(View view) {
        view.animate().cancel();
        if (!Util.isLeanback() || !view.isLaidOut()) {
            view.setVisibility(INVISIBLE);
            return;
        }
        view.animate().alpha(0f).translationY(dp(8)).setDuration(140).withEndAction(() -> {
            if (mState != State.CONTENT) view.setVisibility(INVISIBLE);
            view.setAlpha(1f);
            view.setTranslationY(0f);
        });
    }

    private void showStateView(View view) {
        view.animate().cancel();
        if (!Util.isLeanback() || !view.isLaidOut()) {
            view.setVisibility(VISIBLE);
            return;
        }
        view.setAlpha(0f);
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.setVisibility(VISIBLE);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180);
    }

    private void hideStateView(View view) {
        view.animate().cancel();
        view.setVisibility(GONE);
        view.setAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTranslationY(0f);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
