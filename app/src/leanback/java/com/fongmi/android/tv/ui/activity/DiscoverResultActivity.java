package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverFacet;
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

public class DiscoverResultActivity extends BaseActivity implements VodPresenter.OnClickListener, CustomScroller.Callback {

    private static final int COLUMN = 6;
    private final Object requestTag = new Object();
    private ActivityDiscoverResultBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private CustomScroller scroller;
    private DiscoverFacet facet;
    private int page;

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
        page = targetPage;
        if (targetPage == 1) mBinding.progressLayout.showProgress();
        DiscoverApi.fetchFiltered(facet, targetPage, BuildConfig.TMDB_API_KEY, requestTag, new DiscoverApi.Listener() {
            @Override
            public void onSuccess(DiscoverApi.Row row, List<Vod> items) {
                if (isInactive()) return;
                scroller.endLoading(newResult(items));
                if (items.isEmpty()) scroller.setEnable(1);
                if (targetPage == 1) mBinding.progressLayout.showContent(true, items.size());
                add(items);
            }

            @Override
            public void onError(DiscoverApi.Row row, Exception e) {
                if (isInactive()) return;
                scroller.endLoading(newResult(List.of()));
                scroller.setEnable(1);
                if (targetPage == 1) mBinding.progressLayout.showContent(true, 0);
            }
        });
    }

    private com.fongmi.android.tv.bean.Result newResult(List<Vod> items) {
        com.fongmi.android.tv.bean.Result result = new com.fongmi.android.tv.bean.Result();
        result.setList(items);
        return result;
    }

    private void add(List<Vod> items) {
        if (items.isEmpty()) return;
        int width = (ResUtil.getScreenWidth() - ResUtil.dp2px(112 + 16 * (COLUMN - 1))) / COLUMN;
        VodPresenter presenter = new VodPresenter(this, Style.rect(), new int[]{width, Math.round(width / 0.75f)});
        for (List<Vod> part : Lists.partition(items, COLUMN)) {
            ArrayObjectAdapter row = new ArrayObjectAdapter(presenter);
            row.addAll(0, part);
            mAdapter.add(new ListRow(row));
        }
        if (page == 1) requestFocus();
    }

    private void requestFocus() {
        mBinding.recycler.postDelayed(() -> {
            if (isInactive()) return;
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
        if (isInactive()) return false;
        load(page + 1);
        return true;
    }

    @Override
    protected void onDestroy() {
        DiscoverApi.cancel(requestTag);
        if (scroller != null) mBinding.recycler.removeOnScrollListener(scroller);
        super.onDestroy();
    }

    private boolean isInactive() {
        return isFinishing() || isDestroyed();
    }
}
