package com.fongmi.android.tv.ui.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.FilterPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private static final int PAGE_HORIZONTAL_PADDING = 128;
    private static final int ROW_HORIZONTAL_SPACING = 16;

    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private List<Filter> mFilters;
    private boolean headerVisible;
    private boolean filterVisible;
    private boolean mViewReady;
    private boolean mPendingRefresh;
    private int mFilterGeneration;
    private Boolean mPendingFilter;

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putString("typeId", typeId);
        args.putBoolean("folder", folder);
        args.putParcelable("style", style);
        args.putSerializable("extend", extend);
        TypeFragment fragment = new TypeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    private String getTypeId() {
        return getArguments().getString("typeId");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private Style getStyle() {
        return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style"));
    }

    private HashMap<String, String> getExtend() {
        return (HashMap<String, String>) getArguments().getSerializable("extend");
    }

    private List<Filter> getFilter() {
        return Cache.copy(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private FolderFragment getParent() {
        return ((FolderFragment) getParentFragment());
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        mFilters = getFilter();
        setRecyclerView();
        setFilters();
        mViewReady = true;
        applyPendingState();
        setViewModel();
        mPendingRefresh = false;
        getVideo();
    }

    private void applyPendingState() {
        Boolean pendingFilter = mPendingFilter != null ? mPendingFilter : filterVisible;
        mPendingFilter = null;
        filterVisible = false;
        if (Boolean.TRUE.equals(pendingFilter)) toggleFilter(true);
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(8, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ALIGNED), FilterPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setHeader(getActivity(), R.id.recyclerPanel, R.id.recycler);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> Notify.show(result.getMsg()));
    }

    private void setFilters() {
        for (Filter filter : mFilters) {
            if (mExtends.containsKey(filter.getKey())) {
                filter.setSelected(mExtends.get(filter.getKey()));
            }
        }
    }

    private void setClick(ArrayObjectAdapter adapter, String key, Value item) {
        for (int i = 0; i < adapter.size(); i++) ((Value) adapter.get(i)).setSelected(item);
        adapter.notifyArrayItemRangeChanged(0, adapter.size());
        if (item.isSelected()) mExtends.put(key, item.getV());
        else mExtends.remove(key);
        onRefresh();
    }

    private void getVideo() {
        if (!mViewReady || mBinding == null || mAdapter == null || mScroller == null || mViewModel == null) return;
        mLast = null;
        checkFilter();
        mScroller.reset();
        getVideo(getTypeId(), "1");
    }

    private void getVideo(String typeId, String page) {
        if (!mViewReady || mViewModel == null) return;
        mViewModel.categoryContent(getKey(), typeId, page, true, mExtends);
    }

    private void setAdapter(Result result) {
        if (!mViewReady || mBinding == null || mAdapter == null || mScroller == null || result == null) return;
        boolean first = mScroller.first();
        boolean flag = mExtends.isEmpty();
        int size = result.getList().size();
        mBinding.progressLayout.showContent(first & flag, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
    }

    private void addVideo(Result result) {
        if (!mViewReady || mBinding == null || mAdapter == null || result == null) return;
        Style style = result.getStyle(getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
        checkMore();
    }

    private void checkMore() {
        if (!mViewReady || mAdapter == null || mScroller == null) return;
        if (mScroller.isDisable() || mAdapter.size() >= 5) return;
        mScroller.checkMore();
    }

    private boolean checkLastSize(List<Vod> items, Style style) {
        if (mLast == null || items.isEmpty()) return false;
        int column = Product.getColumn(style);
        if (column <= 0) return false;
        int size = column - mLast.size();
        if (size <= 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addGrid(items.subList(size, items.size()), style);
        return true;
    }

    private void addGrid(List<Vod> items, Style style) {
        if (checkLastSize(items, style)) return;
        int column = Product.getColumn(style);
        if (column <= 0 || items.isEmpty()) return;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style, getPageSpec(style));
        for (List<Vod> part : Lists.partition(items, column)) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private int[] getPageSpec(Style style) {
        int column = Product.getColumn(style);
        int space = ResUtil.dp2px(PAGE_HORIZONTAL_PADDING) + ResUtil.dp2px(ROW_HORIZONTAL_SPACING * (column - 1));
        if (style.isOval()) space += ResUtil.dp2px(column * 16);
        return Product.getSpec(space, column, style);
    }

    private ListRow getRow(Filter filter) {
        FilterPresenter presenter = new FilterPresenter(filter.getKey());
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
        presenter.setOnClickListener((key, item) -> setClick(adapter, key, item));
        adapter.setItems(filter.getValue(), null);
        return new ListRow(adapter);
    }

    private void showFilter(int generation) {
        if (!mViewReady || mBinding == null || mAdapter == null || mFilters == null) return;
        List<ListRow> rows = new ArrayList<>();
        for (Filter filter : mFilters) rows.add(getRow(filter));
        mBinding.recycler.postDelayed(() -> {
            if (!mViewReady || mBinding == null || mAdapter == null || !filterVisible || generation != mFilterGeneration) return;
            mBinding.recycler.scrollToPosition(0);
            requestRecyclerFocus(0, generation);
        }, 48);
        mAdapter.addAll(0, rows);
    }

    private void hideFilter() {
        if (!mViewReady || mBinding == null || mAdapter == null || mFilters == null) return;
        boolean restoreFocus = mBinding.recycler.hasFocus() && mBinding.recycler.getSelectedPosition() < mFilters.size();
        mAdapter.removeItems(0, mFilters.size());
        if (restoreFocus) requestRecyclerFocus(0);
    }

    public void toggleFilter(boolean visible) {
        if (!mViewReady || mBinding == null || mAdapter == null || mFilters == null) {
            mPendingFilter = visible;
            return;
        }
        if (mFilters.isEmpty()) return;
        mPendingFilter = null;
        if (filterVisible == visible) return;
        this.filterVisible = visible;
        int generation = ++mFilterGeneration;
        if (visible) showFilter(generation);
        else hideFilter();
    }

    private void checkFilter() {
        int adapterSize = mAdapter.size();
        int filterSize = filterVisible ? mFilters.size() : 0;
        boolean showProgress = adapterSize == 0 || filterSize == 0;
        boolean restoreFocus = mBinding.recycler.hasFocus() && mBinding.recycler.getSelectedPosition() >= filterSize;
        if (showProgress) mBinding.progressLayout.showProgress();
        if (adapterSize > filterSize) mAdapter.removeItems(filterSize, mAdapter.size() - filterSize);
        if (restoreFocus && !showProgress) requestRecyclerFocus(Math.max(0, filterSize - 1));
        if (!showProgress) mBinding.swipeLayout.setRefreshing(true);
    }

    public void onRefresh() {
        if (!mViewReady || mBinding == null || mAdapter == null || mScroller == null || mViewModel == null) {
            mPendingRefresh = true;
            return;
        }
        mPendingRefresh = false;
        getVideo();
    }

    @Override
    public void onItemClick(Vod item) {
        onItemClick(item, null);
    }

    @Override
    public void onItemClick(Vod item, View poster) {
        if (!isInteractive() || mViewModel == null || item == null) return;
        if (item.isAction()) {
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            getParent().openFolder(item.getId(), mExtends);
            headerVisible = mBinding.recycler.isHeaderVisible();
        } else {
            if (getSite().isIndex()) CollectActivity.start(requireActivity(), item.getName());
            else VideoActivity.start(requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null, poster);
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (!isInteractive() || item == null) return false;
        if (item.isAction() || item.isFolder()) return false;
        CollectActivity.start(requireActivity(), item.getName());
        return true;
    }

    @Override
    public boolean onLoadMore(String page) {
        if (!isInteractive() || mViewModel == null || mScroller == null) return false;
        getVideo(getTypeId(), page);
        return true;
    }

    private boolean isInteractive() {
        FolderFragment parent = getParent();
        return mViewReady && mBinding != null && isAdded() && !isHidden() && getUserVisibleHint() && parent.isAdded() && !parent.isHidden() && parent.getUserVisibleHint();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!mViewReady || mBinding == null) return;
        if (hidden) {
            mBinding.recycler.showHeader();
        } else {
            if (headerVisible) mBinding.recycler.showHeader();
            else mBinding.recycler.hideHeader();
            requestRecyclerFocus();
        }
    }

    private void requestRecyclerFocus() {
        requestRecyclerFocus(RecyclerView.NO_POSITION);
    }

    private void requestRecyclerFocus(int position) {
        requestRecyclerFocus(position, -1);
    }

    private void requestRecyclerFocus(int position, int filterGeneration) {
        if (!mViewReady || mBinding == null || mAdapter == null) return;
        mBinding.recycler.post(() -> {
            if (filterGeneration >= 0 && (!filterVisible || filterGeneration != mFilterGeneration)) return;
            if (!mViewReady || mBinding == null || mAdapter == null || mAdapter.size() == 0 || !mBinding.recycler.isShown() || !mBinding.recycler.isEnabled()) return;
            int target = position == RecyclerView.NO_POSITION ? mBinding.recycler.getSelectedPosition() : position;
            target = Math.max(0, Math.min(target, mAdapter.size() - 1));
            mBinding.recycler.setSelectedPosition(target);
            int focusTarget = target;
            mBinding.recycler.postDelayed(() -> {
                if (filterGeneration >= 0 && (!filterVisible || filterGeneration != mFilterGeneration)) return;
                if (!mViewReady || mBinding == null || mAdapter == null) return;
                RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(focusTarget);
                View focus = holder == null ? null : findFocusable(holder.itemView);
                if (focus != null && focus.requestFocus()) return;
                if (mBinding.recycler.isShown() && mBinding.recycler.isEnabled()) mBinding.recycler.requestFocus();
            }, 50);
        });
    }

    private View findFocusable(View view) {
        if (view == null || !view.isShown() || !view.isEnabled()) return null;
        if (view.isFocusable()) return view;
        if (!(view instanceof ViewGroup group)) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View target = findFocusable(group.getChildAt(i));
            if (target != null) return target;
        }
        return null;
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (mViewReady && mBinding != null) mBinding.recycler.moveToTop();
    }

    @Override
    public void onDestroyView() {
        mViewReady = false;
        mFilterGeneration++;
        if (mBinding != null && mScroller != null) mBinding.recycler.removeOnScrollListener(mScroller);
        mBinding = null;
        mAdapter = null;
        mLast = null;
        mScroller = null;
        super.onDestroyView();
    }
}
