package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
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
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.CollectAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.fragment.CollectFragment;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

public class CollectActivity extends BaseActivity {

    private ActivityCollectBinding mBinding;
    private CollectAdapter mAdapter;
    private PageAdapter mPageAdapter;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    private View mOldView;
    private boolean mSearchStarted;
    private final List<Vod> mPending = new ArrayList<>();

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, CollectActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    @Nullable
    private CollectFragment getFragment() {
        if (isInactive() || mPageAdapter == null) return null;
        return mPageAdapter.getAllFragment();
    }

    private String getKeyword() {
        return getIntent().getStringExtra("keyword");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        getIntent().putExtras(intent);
        mSearchStarted = false;
        if (mViewModel != null) mViewModel.stopSearch();
        mPending.clear();
        mAdapter.clear();
        setPager();
        search();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        setViewModel();
        saveKeyword();
        setSites();
        setPager();
        search();
    }

    @Override
    protected void initEvent() {
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (isInactive()) return;
                int safePosition = clampPosition(position);
                if (safePosition == RecyclerView.NO_POSITION) return;
                if (safePosition == 0) flushPending();
                mBinding.recycler.setSelectedPosition(safePosition);
                requestRecyclerFocus();
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
        mBinding.recyclerPanel.setClipToOutline(true);
        mBinding.recycler.setClipChildren(false);
        mBinding.recycler.setClipToPadding(false);
        mBinding.recycler.setClipToOutline(false);
        mBinding.recycler.setPadding(ResUtil.dp2px(8), 0, ResUtil.dp2px(16), 0);
        mBinding.recycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recycler.setAdapter(mAdapter = new CollectAdapter());
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class).init();
        mViewModel.getSearch().observe(this, result -> {
            if (isInactive() || !mSearchStarted || result == null || result.getList().isEmpty()) return;
            List<Vod> items = result.getList();
            mPending.addAll(items);
            mAdapter.add(Collect.create(items));
            if (mBinding.pager.getAdapter() != null) mBinding.pager.getAdapter().notifyDataSetChanged();
            flushPending();
            mBinding.pager.post(this::flushPending);
        });
    }

    private void flushPending() {
        flushPending(getFragment());
    }

    private void flushPending(@Nullable CollectFragment fragment) {
        if (fragment == null || mPending.isEmpty()) return;
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

    private void setSites() {
        mSites = VodConfig.get().getSites().stream().filter(Site::isSearchable).toList();
    }

    private void setPager() {
        disposePages();
        mBinding.pager.setAdapter(mPageAdapter = new PageAdapter(getSupportFragmentManager()));
    }

    private void disposePages() {
        FragmentManager manager = getSupportFragmentManager();
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

    private void search() {
        if (isInactive() || mSites == null || mSites.isEmpty() || mAdapter == null || mViewModel == null) return;
        mAdapter.add(Collect.all());
        mSearchStarted = true;
        requestRecyclerFocus();
        if (mBinding.pager.getAdapter() != null) mBinding.pager.getAdapter().notifyDataSetChanged();
        mViewModel.searchContent(mSites, getKeyword(), false);
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child) {
        if (isInactive()) return;
        if (mOldView != null) mOldView.setSelected(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setSelected(true);
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

    private void requestRecyclerFocus() {
        mBinding.recycler.post(() -> {
            if (isInactive()) return;
            int position = clampPosition(mBinding.recycler.getSelectedPosition());
            if (position == RecyclerView.NO_POSITION || !canRequestFocus(mBinding.recycler)) return;
            mBinding.recycler.setSelectedPosition(position);
            mBinding.recycler.postDelayed(() -> {
                if (isInactive()) return;
                RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(position);
                View target = holder == null ? null : findFocusable(holder.itemView);
                if (target != null && target.requestFocus()) return;
                if (canRequestFocus(mBinding.recycler)) mBinding.recycler.requestFocus();
            }, 50);
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

    @Override
    protected void onBackInvoked() {
        mViewModel.stopSearch();
        super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        mSearchStarted = false;
        App.removeCallbacks(mRunnable);
        if (mViewModel != null) mViewModel.stopSearch();
        mPending.clear();
        super.onDestroy();
    }

    private boolean isInactive() {
        return isFinishing() || isDestroyed();
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
            return CollectFragment.newInstance(getKeyword(), collect);
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
