package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentCollectBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.FolderActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.CollectAdapter;
import com.fongmi.android.tv.ui.adapter.SearchAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.setting.SourceSelectionSetting;
import com.fongmi.android.tv.source.SourceAggregator;
import com.fongmi.android.tv.source.SourceSelectionMode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class CollectFragment extends BaseFragment implements MenuProvider, CollectAdapter.OnClickListener, SearchAdapter.OnClickListener, CustomScroller.Callback {

    private FragmentCollectBinding mBinding;
    private CollectAdapter mCollectAdapter;
    private SearchAdapter mSearchAdapter;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    private boolean mViewReady;
    private final SourceAggregator mAggregator = new SourceAggregator();
    private final List<Vod> mAggregated = new ArrayList<>();

    public static CollectFragment newInstance(String keyword) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        CollectFragment fragment = new CollectFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return getArguments().getString("keyword");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initMenu() {
        if (mBinding == null || isHidden()) return;
        AppCompatActivity activity = (AppCompatActivity) requireActivity();
        activity.setSupportActionBar(mBinding.toolbar);
        activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        activity.addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        activity.setTitle(getKeyword());
    }

    @Override
    protected void initView() {
        mScroller = new CustomScroller(this);
        setRecyclerView();
        mViewReady = true;
        mAggregated.clear();
        setViewModel();
        setSites();
        setWidth();
        search();
    }

    @Override
    protected void initEvent() {
        mBinding.toolbar.setOnClickListener(v -> {
            Bundle result = new Bundle();
            result.putBoolean("edit", true);
            getParentFragmentManager().setFragmentResult("result", result);
            getParentFragmentManager().popBackStack();
        });
    }

    private void setRecyclerView() {
        mBinding.collect.setItemAnimator(null);
        mBinding.collect.setHasFixedSize(true);
        mBinding.collect.setAdapter(mCollectAdapter = new CollectAdapter(this));
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.addOnScrollListener(mScroller);
        mBinding.recycler.setAdapter(mSearchAdapter = new SearchAdapter(this));
        ((GridLayoutManager) (mBinding.recycler.getLayoutManager())).setSpanCount(getCount());
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class).init();
        mViewModel.getSearch().observe(getViewLifecycleOwner(), this::setCollect);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setSearch);
    }

    private void setSites() {
        mSites = VodConfig.get().getSites().stream().filter(Site::isSearchable).toList();
    }

    private void setWidth() {
        int width = 0;
        int space = ResUtil.dp2px(48);
        int maxWidth = ResUtil.getScreenWidth() / (getCount() + 1) - ResUtil.dp2px(40);
        for (Site site : mSites) width = Math.max(width, ResUtil.getTextWidth(site.getName(), 14));
        int contentWidth = width + space;
        int minWidth = ResUtil.dp2px(120);
        int finalWidth = Math.clamp(contentWidth, minWidth, Math.max(minWidth, maxWidth));
        ViewGroup.LayoutParams params = mBinding.collect.getLayoutParams();
        params.width = finalWidth;
        mBinding.collect.setLayoutParams(params);
    }

    private void search() {
        if (!mViewReady || mSites == null || mSites.isEmpty() || mCollectAdapter == null || mViewModel == null) return;
        mCollectAdapter.reset(() -> {
            if (mViewReady && mViewModel != null) mViewModel.searchContent(mSites, getKeyword(), false);
        });
    }

    private int getCount() {
        int count = ResUtil.isLand(requireActivity()) ? 2 : 1;
        if (ResUtil.isPad()) count++;
        return count;
    }

    private void setCollect(Result result) {
        if (!mViewReady || mCollectAdapter == null || mSearchAdapter == null || result == null || result.getList().isEmpty()) return;
        if (mCollectAdapter.getPosition() == 0) {
            if (SourceSelectionSetting.getMode() == SourceSelectionMode.LEGACY) mSearchAdapter.append(result.getList());
            else {
                mAggregator.mergeInto(mAggregated, result.getList());
                mSearchAdapter.replace(new ArrayList<>(mAggregated));
            }
        }
        mCollectAdapter.add(result.getList());
    }

    private void setSearch(Result result) {
        if (!mViewReady || mScroller == null || mCollectAdapter == null || mSearchAdapter == null || result == null) return;
        mScroller.endLoading(result);
        if (mCollectAdapter.getItemCount() == 0 || result.getList().isEmpty()) return;
        Collect activated = mCollectAdapter.getActivated();
        boolean same = activated != null && activated.getSite().equals(result.getVod().getSite());
        if (same) activated.getList().addAll(result.getList());
        if (same) mSearchAdapter.append(result.getList());
    }

    @Override
    public void onItemClick(int position, Collect item) {
        if (!mViewReady || mBinding == null || mCollectAdapter == null || mSearchAdapter == null || mScroller == null || item == null) return;
        if (position < 0 || position >= mCollectAdapter.getItemCount()) return;
        SearchAdapter adapter = mSearchAdapter;
        adapter.replace(item.getList(), () -> {
            if (mViewReady && mBinding != null && mSearchAdapter == adapter) mBinding.recycler.scrollToPosition(0);
        });
        mCollectAdapter.setSelected(position);
        mScroller.setPage(item.getPage());
    }

    @Override
    public void onItemClick(Vod item) {
        Activity activity = getActiveActivity();
        if (activity == null || item == null) return;
        if (item.isFolder()) FolderActivity.start(activity, item.getSiteKey(), Result.folder(item));
        else chooseSource(activity, item);
    }

    private void chooseSource(Activity activity, Vod item) {
        List<Vod> options = item.getSourceOptions();
        if (SourceSelectionSetting.getMode() != SourceSelectionMode.GROUP_ONLY || options.size() <= 1) {
            VideoActivity.collect(activity, item.getSiteKey(), item.getId(), item.getName(), item.getPic(), item.getSourceCandidates());
            return;
        }
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            Vod option = options.get(i);
            labels[i] = option.getSiteName().isEmpty() ? option.getName() : option.getSiteName();
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(item.getName())
                .setSingleChoiceItems(labels, 0, (dialog, which) -> {
                    Activity current = getActiveActivity();
                    if (current == null) {
                        dialog.dismiss();
                        return;
                    }
                    Vod selected = options.get(which);
                    List<Vod> remaining = new ArrayList<>(options);
                    remaining.remove(which);
                    VideoActivity.collect(current, selected.getSiteKey(), selected.getId(), selected.getName(), selected.getPic(), remaining);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    @Override
    public boolean onLoadMore(String page) {
        if (!isInteractive() || mCollectAdapter == null || mViewModel == null || mScroller == null || mCollectAdapter.getItemCount() == 0) return false;
        Collect activated = mCollectAdapter.getActivated();
        if (activated == null || "all".equals(activated.getSite().getKey())) return false;
        mViewModel.searchContent(activated.getSite(), getKeyword(), false, page);
        activated.setPage(Integer.parseInt(page));
        return true;
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        FragmentActivity activity = getActiveActivity();
        if (activity != null && menuItem.getItemId() == android.R.id.home) activity.getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) {
            Activity activity = getActivity();
            if (activity instanceof AppCompatActivity appCompatActivity) appCompatActivity.removeMenuProvider(this);
        } else if (mViewReady) initMenu();
    }

    @Override
    public void onDestroyView() {
        mViewReady = false;
        if (mBinding != null && mScroller != null) mBinding.recycler.removeOnScrollListener(mScroller);
        if (mBinding != null) mBinding.recycler.setAdapter(null);
        if (mViewModel != null) mViewModel.stopSearch();
        Activity activity = getActivity();
        if (activity instanceof AppCompatActivity appCompatActivity) appCompatActivity.removeMenuProvider(this);
        mCollectAdapter = null;
        mSearchAdapter = null;
        mScroller = null;
        mBinding = null;
        super.onDestroyView();
    }

    @Nullable
    private FragmentActivity getActiveActivity() {
        FragmentActivity activity = getActivity();
        return !isInteractive() || activity == null || activity.isFinishing() || activity.isDestroyed() ? null : activity;
    }

    private boolean isInteractive() {
        return mViewReady && mBinding != null && isAdded() && !isHidden() && getUserVisibleHint();
    }
}
