package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.VodAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private VodAdapter mAdapter;
    private boolean mViewReady;
    private boolean mPendingRefresh;
    private boolean mPendingScrollToTop;
    private final Map<String, Value> mPendingFilters = new LinkedHashMap<>();

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder, int y) {
        Bundle args = new Bundle();
        args.putInt("y", y);
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

    private Style getStyle() {
        return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style"));
    }

    private HashMap<String, String> getExtend() {
        return (HashMap<String, String>) getArguments().getSerializable("extend");
    }

    private int getY() {
        return getArguments().getInt("y");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private boolean isHome() {
        return "home".equals(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    @Nullable
    private FolderFragment getParent() {
        return getParentFragment() instanceof FolderFragment fragment ? fragment : null;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.progressLayout.showProgress();
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        if (mExtends == null) mExtends = new HashMap<>();
        applyPendingFilters();
        setRecyclerView();
        setViewModel();
        mViewReady = true;
        mPendingRefresh = false;
        boolean scrollToTop = mPendingScrollToTop;
        mPendingScrollToTop = false;
        getVideo();
        if (scrollToTop) scrollToTop();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
    }

    private void setRecyclerView() {
        mBinding.recycler.setTranslationY(-ResUtil.dp2px(getY()));
        mBinding.recycler.setHasFixedSize(true);
        setStyle(getStyle());
    }

    private void setStyle(Style style) {
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, Product.getSpec(requireActivity(), style)));
        mBinding.recycler.setLayoutManager(style.isList() ? new LinearLayoutManager(requireActivity()) : new GridLayoutManager(getContext(), Product.getColumn(requireActivity(), style)));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> Notify.show(result.getMsg()));
    }

    private void getHome() {
        if (!isViewReady() || mAdapter == null || mViewModel == null) return;
        VodAdapter adapter = mAdapter;
        adapter.clear(() -> {
            if (isViewReady() && mAdapter == adapter && mViewModel != null) mViewModel.homeContent();
        });
    }

    private void getVideo() {
        if (!isViewReady() || mAdapter == null || mScroller == null || mViewModel == null) return;
        mScroller.reset();
        VodAdapter adapter = mAdapter;
        adapter.clear(() -> {
            if (!isViewReady() || mAdapter != adapter || mScroller == null || mViewModel == null) return;
            if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
            FolderFragment parent = getParent();
            if (isHome() && parent != null) setAdapter(parent.getResult());
            else if (!isHome()) getVideo(getTypeId(), "1");
        });
    }

    private void getVideo(String typeId, String page) {
        if (!isViewReady() || mViewModel == null) return;
        mViewModel.categoryContent(getKey(), typeId, page, true, mExtends);
    }

    private void setAdapter(Result result) {
        if (!isViewReady() || mAdapter == null || mScroller == null || result == null) return;
        boolean first = mScroller.first();
        int size = result.getList().size();
        mBinding.progressLayout.showContent(first, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
    }

    private void addVideo(Result result) {
        if (!isViewReady() || mAdapter == null || result == null) return;
        Style style = result.getVod().getStyle(getStyle());
        if (!style.equals(mAdapter.getStyle())) setStyle(style);
        mAdapter.addAll(result.getList(), this::checkMore);
    }

    private void checkMore() {
        if (!isViewReady() || mScroller == null) return;
        FragmentTypeBinding binding = mBinding;
        binding.recycler.post(() -> {
            if (!isViewReady() || mBinding != binding || mScroller == null) return;
            if (isHome()) return;
            mScroller.checkMore(binding.recycler);
        });
    }

    public void scrollToTop() {
        if (!isViewReady()) {
            mPendingScrollToTop = true;
            return;
        }
        mPendingScrollToTop = false;
        mBinding.recycler.smoothScrollToPosition(0);
    }

    public void setFilter(String key, Value value) {
        if (!isViewReady() || mExtends == null) {
            mPendingFilters.put(key, value.copy());
            mPendingRefresh = false;
            return;
        }
        applyFilter(key, value);
        onRefresh();
    }

    public void setFilters(Map<String, Value> filters) {
        if (filters.isEmpty()) return;
        if (!isViewReady() || mExtends == null) {
            for (Map.Entry<String, Value> entry : filters.entrySet()) mPendingFilters.put(entry.getKey(), entry.getValue().copy());
            mPendingRefresh = false;
            return;
        }
        for (Map.Entry<String, Value> entry : filters.entrySet()) applyFilter(entry.getKey(), entry.getValue());
        onRefresh();
    }

    private void applyPendingFilters() {
        for (Map.Entry<String, Value> entry : mPendingFilters.entrySet()) applyFilter(entry.getKey(), entry.getValue());
        mPendingFilters.clear();
    }

    private void applyFilter(String key, Value value) {
        if (value.isSelected()) mExtends.put(key, value.getV());
        else mExtends.remove(key);
    }

    @Override
    public void onRefresh() {
        if (!isViewReady() || mAdapter == null || mScroller == null || mViewModel == null) {
            mPendingRefresh = true;
            return;
        }
        mPendingRefresh = false;
        if (isHome()) getHome();
        else getVideo();
    }

    @Override
    public boolean onLoadMore(String page) {
        if (!isInteractive() || mViewModel == null || isHome()) return false;
        getVideo(getTypeId(), page);
        return true;
    }

    @Override
    public void onItemClick(Vod item) {
        if (!isInteractive() || mViewModel == null || item == null) return;
        if (item.isAction()) {
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            FolderFragment parent = getParent();
            if (parent != null) parent.openFolder(item.getId(), mExtends);
        } else {
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (getSite().isIndex()) SearchActivity.start(activity, item.getName());
            else VideoActivity.start(activity, getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (!isInteractive() || item == null) return false;
        if (item.isAction() || item.isFolder()) return false;
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        SearchActivity.start(activity, item.getName());
        return true;
    }

    private boolean isViewReady() {
        return mViewReady && mBinding != null;
    }

    private boolean isInteractive() {
        FolderFragment parent = getParent();
        return isViewReady() && isAdded() && !isHidden() && getUserVisibleHint() && parent != null && parent.isAdded() && !parent.isHidden() && parent.getUserVisibleHint();
    }

    @Override
    public void onDestroyView() {
        mViewReady = false;
        if (mBinding != null) {
            mBinding.swipeLayout.setOnRefreshListener(null);
            if (mScroller != null) mBinding.recycler.removeOnScrollListener(mScroller);
            mBinding.recycler.setAdapter(null);
        }
        mBinding = null;
        mAdapter = null;
        mScroller = null;
        mViewModel = null;
        super.onDestroyView();
    }
}
