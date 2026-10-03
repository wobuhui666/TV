package com.fongmi.android.tv.ui.activity;

import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ViewSearchResultsBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.CollectAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.fragment.CollectFragment;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/** Shared ordinary-search results for the search workspace and external collection entry points. */
final class SearchResultsController {

    private final BaseActivity activity;
    private final ViewSearchResultsBinding mBinding;
    private final boolean compact;
    private final SearchFocusGuard focus;
    private String keyword = "";
    private String siteSignature = "";
    private boolean disposed;
    private boolean searching;
    private int resultCount;
    private final Runnable finishStatus = () -> {
        searching = false;
        if (!isInactive()) updateStatus(false);
    };
    private CollectAdapter mAdapter;
    private PageAdapter mPageAdapter;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    private View mOldView;
    private boolean mSearchStarted;
    private final List<Vod> mPending = new ArrayList<>();

    SearchResultsController(BaseActivity activity, ViewSearchResultsBinding binding, boolean compact, SearchFocusGuard focus) {
        this.activity = activity;
        this.mBinding = binding;
        this.compact = compact;
        this.focus = focus;
        setRecyclerView();
        setViewModel();
        initEvent();
    }

    @Nullable
    private CollectFragment getFragment() {
        if (isInactive() || mPageAdapter == null) return null;
        return mPageAdapter.getAllFragment();
    }

    private String getKeyword() {
        return keyword;
    }

    boolean hasResults() {
        return mSearchStarted;
    }

    boolean search(String query) {
        focus.invalidate();
        String next = query == null ? "" : query.trim();
        List<Site> sites = VodConfig.get().getSites().stream().filter(Site::isSearchable).toList();
        String signature = VodConfig.getCid() + ":" + sites.stream().map(Site::getKey).collect(java.util.stream.Collectors.joining("|"));
        if (next.isEmpty() || isInactive()) return false;
        if (mSearchStarted && (searching || resultCount > 0) && next.equals(keyword) && signature.equals(siteSignature)) return false;
        mSearchStarted = false;
        mViewModel.stopSearch();
        App.removeCallbacks(mRunnable);
        App.removeCallbacks(finishStatus);
        mPending.clear();
        mOldView = null;
        mAdapter.clear();
        resultCount = 0;
        keyword = next;
        siteSignature = signature;
        mSites = sites;
        mBinding.title.setText(activity.getString(R.string.tv_search_results_title, keyword));
        saveKeyword();
        setPager();
        mAdapter.add(Collect.all());
        mSearchStarted = true;
        searching = !sites.isEmpty();
        mPageAdapter.notifyDataSetChanged();
        updateStatus(!sites.isEmpty());
        if (!sites.isEmpty()) {
            mViewModel.searchContent(mSites, keyword, false);
            App.post(finishStatus, Constant.TIMEOUT_SEARCH + 250);
        }
        return true;
    }

    private void updateStatus(boolean waiting) {
        if (mSites.isEmpty()) mBinding.status.setText(R.string.tv_search_no_sources);
        else if (resultCount > 0) mBinding.status.setText(activity.getString(R.string.tv_search_result_count, resultCount, Math.max(0, mAdapter.getItemCount() - 1)));
        else mBinding.status.setText(waiting ? R.string.tv_search_waiting : R.string.tv_search_empty);
    }

    private void initEvent() {
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (isInactive()) return;
                int safePosition = clampPosition(position);
                if (safePosition == RecyclerView.NO_POSITION) return;
                if (safePosition == 0) flushPending();
                mBinding.recycler.setSelectedPosition(safePosition);
            }
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                onChildSelected(child);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.recyclerPanel.setClipChildren(false);
        mBinding.recyclerPanel.setClipToPadding(false);
        mBinding.recyclerPanel.setClipToOutline(false);
        mBinding.recycler.setClipChildren(false);
        mBinding.recycler.setClipToPadding(false);
        mBinding.recycler.setClipToOutline(false);
        mBinding.recycler.setPadding(ResUtil.dp2px(8), ResUtil.dp2px(6), ResUtil.dp2px(8), ResUtil.dp2px(6));
        mBinding.recycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recycler.setAdapter(mAdapter = new CollectAdapter());
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(activity).get(SiteViewModel.class).init();
        mViewModel.getSearch().observe(activity, result -> {
            if (isInactive() || !mSearchStarted || result == null || result.getList().isEmpty()) return;
            List<Vod> items = result.getList();
            resultCount += items.size();
            mPending.addAll(items);
            mAdapter.add(Collect.create(items));
            updateStatus(true);
            if (mBinding.pager.getAdapter() != null) mBinding.pager.getAdapter().notifyDataSetChanged();
            flushPending();
            mBinding.pager.post(this::flushPending);
        });
    }

    private void flushPending() {
        flushPending(getFragment());
    }

    private void flushPending(@Nullable CollectFragment fragment) {
        if (isInactive() || fragment == null || mPending.isEmpty() || mBinding.pager.getCurrentItem() != 0) return;
        List<Vod> items = new ArrayList<>(mPending);
        mPending.clear();
        fragment.addVideo(items);
    }

    private void saveKeyword() {
        List<String> items = Setting.getKeyword().isEmpty() ? new ArrayList<>() : App.gson().fromJson(Setting.getKeyword(), TypeToken.getParameterized(List.class, String.class).getType());
        items.remove(getKeyword());
        items.add(0, getKeyword());
        if (items.size() > 9) items.remove(9);
        Setting.putKeyword(App.gson().toJson(items));
    }

    private void setPager() {
        mBinding.pager.setAdapter(null);
        disposePages();
        mBinding.pager.setAdapter(mPageAdapter = new PageAdapter(activity.getSupportFragmentManager()));
    }

    private void disposePages() {
        FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.isDestroyed()) return;
        FragmentTransaction transaction = manager.beginTransaction();
        boolean changed = false;
        for (Fragment fragment : manager.getFragments()) {
            if (!(fragment instanceof CollectFragment) || !fragment.isAdded()) continue;
            transaction.remove(fragment);
            changed = true;
        }
        if (changed) transaction.commitNowAllowingStateLoss();
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child) {
        if (isInactive()) return;
        if (mOldView != null) mOldView.setSelected(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setSelected(true);
        App.removeCallbacks(mRunnable);
        App.post(mRunnable, 100);
    }

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            if (isInactive()) return;
            int position = clampPosition(mBinding.recycler.getSelectedPosition());
            if (position != RecyclerView.NO_POSITION) mBinding.pager.setCurrentItem(position);
        }
    };

    private int clampPosition(int position) {
        if (mAdapter == null) return RecyclerView.NO_POSITION;
        int size = mAdapter.getItemCount();
        if (size <= 0) return RecyclerView.NO_POSITION;
        if (position < 0) return 0;
        return Math.min(position, size - 1);
    }

    void requestFocus() {
        requestFocus(focus.snapshot());
    }

    void requestFocus(long generation) {
        focus.post(mBinding.recycler, generation, 0, () -> {
            if (isInactive()) return;
            int position = clampPosition(mBinding.recycler.getSelectedPosition());
            if (position == RecyclerView.NO_POSITION || !canRequestFocus(mBinding.recycler)) return;
            mBinding.recycler.setSelectedPosition(position);
            focus.post(mBinding.recycler, generation, 50, () -> {
                if (isInactive()) return;
                RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(position);
                View target = holder == null ? null : findFocusable(holder.itemView);
                if (focus.canFocus(generation, target) && target.requestFocus()) return;
                if (focus.canFocus(generation, mBinding.recycler)) mBinding.recycler.requestFocus();
            });
        });
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

    void dispose() {
        focus.invalidate();
        disposed = true;
        mSearchStarted = false;
        App.removeCallbacks(mRunnable);
        App.removeCallbacks(finishStatus);
        if (mViewModel != null) mViewModel.stopSearch();
        mPending.clear();
    }

    private boolean isInactive() {
        return disposed || activity.isFinishing() || activity.isDestroyed();
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        private CollectFragment mAllFragment;

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            int safePosition = clampPosition(position);
            Collect collect = safePosition == RecyclerView.NO_POSITION ? Collect.all() : mAdapter.get(safePosition);
            return CollectFragment.newInstance(getKeyword(), collect, compact);
        }

        @Override
        public int getCount() {
            return mAdapter == null ? 0 : mAdapter.getItemCount();
        }

        @Override
        public void setPrimaryItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            super.setPrimaryItem(container, position, object);
            if (position != 0 || !(object instanceof CollectFragment fragment)) return;
            mAllFragment = fragment;
            flushPending(fragment);
        }

        @Nullable
        private CollectFragment getAllFragment() {
            return mAllFragment;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }

        @Nullable
        @Override
        public Parcelable saveState() {
            return null;
        }

        @Override
        public void restoreState(@Nullable Parcelable state, @Nullable ClassLoader loader) {
        }
    }
}
