package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityVodBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.fragment.FolderFragment;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Optional;

public class VodActivity extends BaseActivity implements TypeAdapter.OnClickListener {

    private ActivityVodBinding mBinding;
    private TypeAdapter mAdapter;
    private PageAdapter mPageAdapter;
    private View mOldView;

    public static void start(Activity activity, Result result) {
        start(activity, VodConfig.get().getHome().getKey(), result);
    }

    public static void start(Activity activity, String key, Result result) {
        if (result == null || result.getTypes().isEmpty()) return;
        Intent intent = new Intent(activity, VodActivity.class);
        intent.putExtra("key", key);
        intent.putExtra("result", result);
        activity.startActivity(intent);
    }

    private String getKey() {
        return getIntent().getStringExtra("key");
    }

    private Result getResult() {
        return getIntent().getParcelableExtra("result");
    }

    @Nullable
    private Class getType() {
        if (isInactive() || mBinding == null || mAdapter == null) return null;
        int position = clampPosition(mBinding.pager.getCurrentItem());
        return position == RecyclerView.NO_POSITION ? null : mAdapter.get(position);
    }

    @Nullable
    private FolderFragment getFragment() {
        if (isInactive() || mBinding == null || mAdapter == null) return null;
        return getFragment(mBinding.pager.getCurrentItem());
    }

    @Nullable
    private FolderFragment getFragment(int position) {
        if (isInactive() || mBinding == null || mAdapter == null || mPageAdapter == null) return null;
        position = clampPosition(position);
        return position == RecyclerView.NO_POSITION ? null : mPageAdapter.getCurrentFragment(position);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVodBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        setTypes();
        setPager();
    }

    @Override
    protected void initEvent() {
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (isInactive()) return;
                int safePosition = clampPosition(position);
                if (safePosition == RecyclerView.NO_POSITION) return;
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
        mBinding.recycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recycler.setAdapter(mAdapter = new TypeAdapter(this));
    }

    private void setTypes() {
        mAdapter.addAll(getResult().getTypes());
        requestRecyclerFocus();
    }

    private void setPager() {
        mBinding.pager.setAdapter(mPageAdapter = new PageAdapter(getSupportFragmentManager()));
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
        if (isInactive() || mBinding == null || mAdapter == null) return;
        mBinding.recycler.post(() -> {
            if (isInactive() || mBinding == null || mAdapter == null) return;
            int position = clampPosition(mBinding.recycler.getSelectedPosition());
            if (position == RecyclerView.NO_POSITION || !canRequestFocus(mBinding.recycler)) return;
            mBinding.recycler.setSelectedPosition(position);
            mBinding.recycler.postDelayed(() -> {
                if (isInactive() || mBinding == null || mAdapter == null) return;
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

    private void updateFilter() {
        Class item = mBinding != null && mBinding.recycler.hasFocus() ? getSelectedType() : getType();
        Optional.ofNullable(item).ifPresent(this::updateFilter);
    }

    @Nullable
    private Class getSelectedType() {
        if (isInactive() || mBinding == null || mAdapter == null) return null;
        int position = clampPosition(mBinding.recycler.getSelectedPosition());
        return position == RecyclerView.NO_POSITION ? null : mAdapter.get(position);
    }

    @Nullable
    private FolderFragment showFragment(Class item) {
        if (isInactive() || item == null || mBinding == null || mAdapter == null) return null;
        int position = mAdapter.indexOf(item);
        if (position < 0 || position >= mAdapter.getItemCount()) return null;
        App.removeCallbacks(mRunnable);
        mBinding.pager.setCurrentItem(position);
        return getFragment(position);
    }

    private void updateFilter(Class item) {
        if (isInactive() || item == null) return;
        FolderFragment fragment = showFragment(item);
        if (fragment == null) return;
        item.setFilter(!item.getFilter());
        fragment.toggleFilter(item.getFilter());
        int position = mAdapter.indexOf(item);
        if (position != -1) mAdapter.notifyItemRangeChanged(position, 1);
    }

    public void closeFilter() {
        Class item = getType();
        if (item != null && item.getFilter()) updateFilter(item);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isInactive() || event == null) return;
        if (event.getType() == RefreshEvent.Type.CATEGORY) Optional.ofNullable(getFragment()).ifPresent(FolderFragment::onRefresh);
    }

    @Override
    public void onItemClick(Class item) {
        if (isInactive()) return;
        updateFilter(item);
    }

    @Override
    public void onRefresh(Class item) {
        if (isInactive()) return;
        Optional.ofNullable(showFragment(item)).ifPresent(FolderFragment::onRefresh);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isInactive()) return super.dispatchKeyEvent(event);
        if (KeyUtil.isMenuKey(event)) updateFilter();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onBackInvoked() {
        if (isInactive()) return;
        Class item = getType();
        FolderFragment fragment = getFragment();
        if (item != null && item.getFilter()) updateFilter(item);
        else if (fragment != null && fragment.canBack()) fragment.goBack();
        else super.onBackInvoked();
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            int safePosition = clampPosition(position);
            if (safePosition == RecyclerView.NO_POSITION) return new Fragment();
            return FolderFragment.newInstance(getKey(), mAdapter.get(safePosition));
        }

        @Override
        public int getCount() {
            return mAdapter == null ? 0 : mAdapter.getItemCount();
        }

        @Override
        public void setPrimaryItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            super.setPrimaryItem(container, position, object);
            if (!(object instanceof FolderFragment fragment)) return;
            mCurrentFragment = fragment;
            mCurrentPosition = position;
        }

        @Nullable
        private FolderFragment getCurrentFragment(int position) {
            return mCurrentPosition == position ? mCurrentFragment : null;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }

        private FolderFragment mCurrentFragment;
        private int mCurrentPosition = -1;
    }

    @Override
    protected void onDestroy() {
        App.removeCallbacks(mRunnable);
        super.onDestroy();
    }

    private boolean isInactive() {
        return isFinishing() || isDestroyed();
    }
}
