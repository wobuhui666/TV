package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.view.ViewGroup;

import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.RowPresenter;

import com.fongmi.android.tv.utils.ResUtil;

public class CustomRowPresenter extends ListRowPresenter {

    private final int spacing;
    private final int strategy;
    private final int horizontalInset;

    public CustomRowPresenter(int spacing) {
        // Card views own their focus animation; do not run a second Leanback zoom.
        this(spacing, FocusHighlight.ZOOM_FACTOR_NONE);
    }

    @SuppressLint("RestrictedApi")
    public CustomRowPresenter(int spacing, int focusZoomFactor) {
        this(spacing, focusZoomFactor, HorizontalGridView.FOCUS_SCROLL_ITEM);
    }

    public CustomRowPresenter(int spacing, int focusZoomFactor, int strategy) {
        this(spacing, focusZoomFactor, strategy, 0);
    }

    public CustomRowPresenter(int spacing, int focusZoomFactor, int strategy, int horizontalInset) {
        super(focusZoomFactor);
        this.spacing = spacing;
        this.strategy = strategy;
        this.horizontalInset = horizontalInset;
        setShadowEnabled(false);
        setSelectEffectEnabled(false);
        setKeepChildForeground(false);
    }

    @Override
    @SuppressLint("RestrictedApi")
    protected void initializeRowViewHolder(RowPresenter.ViewHolder holder) {
        super.initializeRowViewHolder(holder);
        ViewHolder vh = (ViewHolder) holder;
        JetStreamTouch.install(vh.getGridView());
        vh.getGridView().setFocusScrollStrategy(strategy);
        vh.getGridView().setHorizontalSpacing(ResUtil.dp2px(spacing));
        vh.getGridView().setClipChildren(false);
        vh.getGridView().setClipToPadding(false);
        // Focus scaling needs real layout room, not only disabled clipping on
        // this child: Leanback's row ancestors still measure unscaled bounds.
        int verticalInset = ResUtil.dp2px(8);
        vh.getGridView().setPaddingRelative(vh.getGridView().getPaddingStart(), verticalInset,
                vh.getGridView().getPaddingEnd(), verticalInset);
        if (vh.view instanceof ViewGroup row) {
            row.setClipChildren(false);
            row.setClipToPadding(false);
        }
        if (horizontalInset > 0) {
            int inset = ResUtil.dp2px(horizontalInset);
            vh.getGridView().setPaddingRelative(inset, vh.getGridView().getPaddingTop(), inset, vh.getGridView().getPaddingBottom());
            vh.getGridView().setClipToPadding(false);
        }
    }
}
