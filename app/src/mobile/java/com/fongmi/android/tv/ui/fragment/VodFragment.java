package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.FragmentVodBinding;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.FilterListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.HistoryActivity;
import com.fongmi.android.tv.ui.activity.KeepActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.FilterDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LinkDialog;
import com.fongmi.android.tv.ui.dialog.ReceiveDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.appbar.AppBarLayout;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class VodFragment extends BaseFragment implements ConfigListener, SiteListener, FilterListener, TypeAdapter.OnClickListener {

    private FragmentVodBinding mBinding;
    private SiteViewModel mViewModel;
    private TypeAdapter mAdapter;
    private PageAdapter mPageAdapter;
    private AppBarLayout.OnOffsetChangedListener mAppBarListener;
    private ViewPager.OnPageChangeListener mPageChangeListener;
    private Result mResult;
    private boolean mPendingRefresh;
    private boolean mPendingScrollToTop;
    private final Map<String, Value> mPendingFilters = new LinkedHashMap<>();

    public static VodFragment newInstance() {
        return new VodFragment();
    }

    @Nullable
    private FolderFragment getFragment() {
        if (mBinding == null || mPageAdapter == null) return null;
        return mPageAdapter.getCurrentFragment(mBinding.pager.getCurrentItem());
    }

    private void dispatchPendingCommands() {
        FolderFragment fragment = getFragment();
        if (fragment == null) return;
        boolean hasFilters = !mPendingFilters.isEmpty();
        if (hasFilters) {
            Map<String, Value> filters = new LinkedHashMap<>(mPendingFilters);
            mPendingFilters.clear();
            fragment.setFilters(filters);
        }
        if (mPendingRefresh && !hasFilters) fragment.onRefresh();
        if (mPendingScrollToTop) fragment.scrollToTop();
        mPendingRefresh = false;
        mPendingScrollToTop = false;
    }

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentVodBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.title.setSelected(true);
        setRecyclerView();
        setViewModel();
        showProgress();
        setTitle();
        setLogo();
    }

    @Override
    protected void initEvent() {
        mBinding.top.setOnClickListener(this::onTop);
        mBinding.logo.setOnClickListener(this::onLogo);
        mBinding.link.setOnClickListener(this::onLink);
        mBinding.title.setOnClickListener(this::onSite);
        mBinding.filter.setOnClickListener(this::onFilter);
        mBinding.filter.setOnLongClickListener(this::onLink);
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        mAppBarListener = (appBarLayout, verticalOffset) -> {
            if (mBinding == null) return;
            float factor = Math.abs(verticalOffset * 1f / appBarLayout.getTotalScrollRange());
            int padding = (int) (ResUtil.dp2px(12) * factor);
            if (mBinding.type.getPaddingTop() == padding) return;
            mBinding.type.setPadding(mBinding.type.getPaddingStart(), padding, mBinding.type.getPaddingEnd(), mBinding.type.getPaddingBottom());
        };
        mBinding.appBar.addOnOffsetChangedListener(mAppBarListener);
        mPageChangeListener = new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (mBinding == null || mAdapter == null || position < 0 || position >= mAdapter.getItemCount()) return;
                mBinding.type.smoothScrollToPosition(position);
                mAdapter.setSelected(position);
                setFabVisible(position);
            }
        };
        mBinding.pager.addOnPageChangeListener(mPageChangeListener);
    }

    private void setRecyclerView() {
        mBinding.type.setHasFixedSize(true);
        mBinding.type.setItemAnimator(null);
        mBinding.type.setAdapter(mAdapter = new TypeAdapter(this));
        installPagerAdapter();
    }

    private void installPagerAdapter() {
        mBinding.pager.setAdapter(mPageAdapter = new PageAdapter(getChildFragmentManager()));
    }

    private void replacePagerAdapter() {
        if (mPageAdapter != null) mPageAdapter.dispose();
        installPagerAdapter();
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
    }

    private void setAdapter(Result result) {
        mAdapter.addAll(mResult = result);
        mBinding.pager.getAdapter().notifyDataSetChanged();
        setFabVisible(0);
        hideProgress();
        showContent();
    }

    private void setFabVisible(int position) {
        if (mAdapter.getItemCount() == 0) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.VISIBLE);
            mBinding.filter.setVisibility(View.GONE);
        } else if (!mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.show();
        } else if (position == 0 || mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.setVisibility(View.GONE);
            mBinding.link.show();
        }
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void onTop(View view) {
        FolderFragment fragment = getFragment();
        if (fragment == null) mPendingScrollToTop = true;
        else fragment.scrollToTop();
        mBinding.top.setVisibility(View.INVISIBLE);
        if (mBinding.filter.getVisibility() == View.INVISIBLE) mBinding.filter.show();
        else if (mBinding.link.getVisibility() == View.INVISIBLE) mBinding.link.show();
    }

    private boolean onLink(View view) {
        LinkDialog.show(this);
        return true;
    }

    private void onLogo(View view) {
        HistoryDialog.create().vod().readOnly().show(this);
    }

    private void onSite(View view) {
        SiteDialog.create().change().show(this);
    }

    private void onFilter(View view) {
        if (mAdapter.getItemCount() > 0) FilterDialog.create().filter(mAdapter.get(mBinding.pager.getCurrentItem()).getFilters()).show(this);
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.keep) KeepActivity.start(requireActivity());
        else if (item.getItemId() == R.id.search) SearchActivity.start(requireActivity());
        else if (item.getItemId() == R.id.history) HistoryActivity.start(requireActivity());
        return true;
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
    }

    private void hideContent() {
        mBinding.type.setVisibility(View.INVISIBLE);
        mBinding.pager.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        mBinding.type.setVisibility(View.VISIBLE);
        mBinding.pager.setVisibility(View.VISIBLE);
    }

    private void homeContent() {
        showProgress();
        setFabVisible(0);
        mPendingRefresh = false;
        mPendingScrollToTop = false;
        mPendingFilters.clear();
        mAdapter.clear();
        mViewModel.homeContent();
        replacePagerAdapter();
    }

    public Result getResult() {
        return mResult == null ? new Result() : mResult;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) setLogo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                setTitle();
            case SIZE:
                homeContent();
                break;
            case CATEGORY:
                FolderFragment fragment = getFragment();
                if (fragment == null) mPendingRefresh = true;
                else fragment.onRefresh();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStateEvent(StateEvent event) {
        switch (event.type()) {
            case EMPTY:
                hideProgress();
                break;
            case PROGRESS:
                showProgress();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        ReceiveDialog.create().event(event).show(this);
    }

    @Override
    public void setConfig(Config config) {
        VodConfig.load(config, new Callback() {
            @Override
            public void start() {
                if (mBinding == null) return;
                showProgress();
                hideContent();
                setTitle();
                setLogo();
            }

            @Override
            public void error(String msg) {
                if (mBinding == null) return;
                Notify.dismiss();
                Notify.show(msg);
                showContent();
            }
        });
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void onItemClick(int position, Class item) {
        if (mBinding == null || mAdapter == null || item == null || position < 0 || position >= mAdapter.getItemCount()) return;
        mBinding.pager.setCurrentItem(position);
        mAdapter.setSelected(position);
    }

    @Override
    public void setFilter(String key, Value value) {
        FolderFragment fragment = getFragment();
        if (fragment == null) {
            mPendingFilters.put(key, value.copy());
            mPendingRefresh = false;
        }
        else fragment.setFilter(key, value);
    }

    @Override
    public boolean canBack() {
        if (mBinding == null) return true;
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return true;
        FolderFragment fragment = getFragment();
        if (fragment == null || !fragment.canBack()) return true;
        fragment.goBack();
        return false;
    }

    @Override
    public void onDestroyView() {
        EventBus.getDefault().unregister(this);
        if (mBinding != null && mAppBarListener != null) mBinding.appBar.removeOnOffsetChangedListener(mAppBarListener);
        if (mBinding != null && mPageChangeListener != null) mBinding.pager.removeOnPageChangeListener(mPageChangeListener);
        mAppBarListener = null;
        mPageChangeListener = null;
        mPageAdapter = null;
        mAdapter = null;
        mBinding = null;
        super.onDestroyView();
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        private final FragmentManager mFragmentManager;
        private FolderFragment mCurrentFragment;
        private int mCurrentPosition = -1;

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
            mFragmentManager = fm;
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            return FolderFragment.newInstance(getHome().getKey(), type, 4);
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
            dispatchPendingCommands();
        }

        @Nullable
        private FolderFragment getCurrentFragment(int position) {
            return mCurrentPosition == position ? mCurrentFragment : null;
        }

        private void dispose() {
            if (!mFragmentManager.isDestroyed()) {
                androidx.fragment.app.FragmentTransaction transaction = mFragmentManager.beginTransaction();
                boolean changed = false;
                for (Fragment fragment : mFragmentManager.getFragments()) {
                    if (!(fragment instanceof FolderFragment) || !fragment.isAdded()) continue;
                    transaction.remove(fragment);
                    changed = true;
                }
                if (changed) transaction.commitNowAllowingStateLoss();
            }
            mCurrentFragment = null;
            mCurrentPosition = -1;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }
    }
}
