package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.api.rex.RexTmdbClient;
import com.fongmi.android.tv.api.rex.RexTmdbQuery;
import com.fongmi.android.tv.bean.DiscoverFacet;
import com.fongmi.android.tv.bean.DiscoverRequestState;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityDiscoverResultBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.ArrayList;

public class DiscoverResultActivity extends BaseActivity implements VodPresenter.OnClickListener, CustomScroller.Callback {

    private static final int COLUMN = 6;
    private final Object requestTag = new Object();
    private ActivityDiscoverResultBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private CustomScroller scroller;
    private DiscoverFacet facet;
    private int page;
    private int totalPages = 1;
    private int generation;
    private final List<ArrayObjectAdapter> rows = new ArrayList<>();
    private final DiscoverRequestState requestState = new DiscoverRequestState();
    private PosterWallFilterController filters;
    private boolean initialFocus = true;

    public static void start(Activity activity, DiscoverFacet facet) {
        Intent intent = new Intent(activity, DiscoverResultActivity.class);
        intent.putExtra("facet", facet);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDiscoverResultBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        facet = getIntent().getParcelableExtra("facet");
        if (facet == null) {
            finish();
            return;
        }
        mBinding.title.setText(facet.getName());
        setRecyclerView();
        filters = new PosterWallFilterController(mBinding.filterContainer, RexTmdbQuery.forFacet(facet), this::onFiltersChanged);
        generation = requestState.reset();
        load(1);
    }

    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_NONE), VodPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.addOnScrollListener(scroller = new CustomScroller(this));
    }

    private void load(int targetPage) {
        if (targetPage == 1) mBinding.progressLayout.showProgress();
        int ticket = generation;
        RexTmdbClient.fetchPage(filters.query(), targetPage, BuildConfig.TMDB_API_KEY, requestTag, new RexTmdbClient.PageListener() {
            @Override
            public void onSuccess(List<Vod> items, int responsePage, int pages) {
                if (isInactive() || ticket != generation) return;
                page = responsePage;
                totalPages = pages;
                scroller.endLoading(newResult(items));
                scroller.setEnable(totalPages);
                if (targetPage == 1) mBinding.progressLayout.showContent(true, items.size());
                add(requestState.addAllAndGetAdded(generation, items));
            }

            @Override
            public void onError(Exception e) {
                if (isInactive() || ticket != generation) return;
                scroller.endLoading(newResult(List.of()));
                scroller.setEnable(totalPages);
                if (targetPage == 1) mBinding.progressLayout.showContent(true, 0);
            }
        });
    }

    private void onFiltersChanged() {
        initialFocus = false;
        RexTmdbClient.cancel(requestTag);
        generation = requestState.reset();
        page = 0;
        totalPages = 1;
        rows.clear();
        mAdapter.clear();
        scroller.reset();
        load(1);
    }

    private com.fongmi.android.tv.bean.Result newResult(List<Vod> items) {
        com.fongmi.android.tv.bean.Result result = new com.fongmi.android.tv.bean.Result();
        result.setList(items);
        return result;
    }

    private void add(List<Vod> items) {
        if (items.isEmpty()) return;
        int offset = 0;
        if (!rows.isEmpty()) {
            ArrayObjectAdapter last = rows.get(rows.size() - 1);
            int count = Math.min(COLUMN - last.size(), items.size());
            if (count > 0) {
                last.addAll(last.size(), items.subList(0, count));
                offset = count;
            }
        }
        int width = (ResUtil.getScreenWidth() - ResUtil.dp2px(112 + 16 * (COLUMN - 1))) / COLUMN;
        VodPresenter presenter = new VodPresenter(this, Style.rect(), new int[]{width, Math.round(width / 0.75f)});
        for (List<Vod> part : Lists.partition(items.subList(offset, items.size()), COLUMN)) {
            ArrayObjectAdapter row = new ArrayObjectAdapter(presenter);
            row.addAll(0, part);
            rows.add(row);
            mAdapter.add(new ListRow(row));
        }
        if (initialFocus) {
            initialFocus = false;
            requestFocus();
        }
    }

    private void requestFocus() {
        int ticket = generation;
        mBinding.recycler.postDelayed(() -> {
            if (isInactive() || ticket != generation || mBinding.filterContainer.hasFocus()) return;
            RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(0);
            View target = holder == null ? null : findFocusable(holder.itemView);
            if (target != null && target.requestFocus()) return;
            mBinding.recycler.requestFocus();
        }, 80);
    }

    private View findFocusable(View view) {
        if (view == null || !view.isShown() || !view.isEnabled()) return null;
        if (view.isFocusable()) return view;
        if (!(view instanceof android.view.ViewGroup group)) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View result = findFocusable(group.getChildAt(i));
            if (result != null) return result;
        }
        return null;
    }

    @Override
    public void onItemClick(Vod item) {
        openDetail(item, null);
    }

    @Override
    public void onItemClick(Vod item, View poster) {
        openDetail(item, poster);
    }

    private void openDetail(Vod item, View poster) {
        if (com.fongmi.android.tv.setting.BrowseExperienceSettings.isDetailSourcesEnabled()) {
            DiscoverDetailActivity.start(this, item, poster);
            return;
        }
        com.fongmi.android.tv.bean.DiscoverMediaKey key = com.fongmi.android.tv.bean.DiscoverMediaKey.parse(item.getId());
        if (key == null) {
            CollectActivity.start(this, item.getName());
            return;
        }
        DiscoverDetailActivity.start(this, key, item.getName(), item.getPic(), item.getBackdrop(), item.getContent(), item.getYear(), item.getRemarks(), poster);
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }

    @Override
    public boolean onLoadMore(String ignored) {
        if (isInactive() || page >= totalPages) return false;
        load(page + 1);
        return true;
    }

    @Override
    protected void onDestroy() {
        DiscoverApi.cancel(requestTag);
        RexTmdbClient.cancel(requestTag);
        if (scroller != null) mBinding.recycler.removeOnScrollListener(scroller);
        if (filters != null) filters.release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && filters != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) filters.focus();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && filters != null
                && mBinding.filterContainer.hasFocus() && event.getAction() == KeyEvent.ACTION_UP && filters.collapse()) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean isInactive() {
        return isFinishing() || isDestroyed();
    }
}
