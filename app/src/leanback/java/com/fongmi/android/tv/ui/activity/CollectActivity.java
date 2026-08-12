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
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.model.SiteSearchSnapshot;
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
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    private View mOldView;
    private String mAnchorSiteKey = "all";
    private String mAnchorVodId = "";

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, CollectActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    @Nullable
    private CollectFragment getFragment() {
        if (mBinding.pager.getAdapter() == null || mAdapter.getItemCount() == 0) return null;
        return (CollectFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, 0);
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
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getSearchSnapshot().observe(this, snapshot -> {
            if (snapshot == null) return;
            String selected = selectedSiteKey();
            CollectFragment fragment = getFragment();
            if (fragment != null) {
                String[] anchor = fragment.getAnchor();
                if (anchor != null) {
                    mAnchorSiteKey = anchor[0];
                    mAnchorVodId = anchor[1];
                }
            }
            if (fragment != null) fragment.replaceVideo(snapshot.all());
            List<Collect> items = new ArrayList<>();
            items.add(Collect.all());
            for (SiteSearchSnapshot.Entry entry : snapshot.entries()) items.add(new Collect(entry.site(), new ArrayList<>(entry.items())).state(entry.state().name()));
            mAdapter.setItems(items);
            mBinding.pager.getAdapter().notifyDataSetChanged();
            restoreSite(selected);
        });
    }

    private String selectedSiteKey() {
        int position = clampPosition(mBinding.recycler.getSelectedPosition());
        return position == RecyclerView.NO_POSITION ? "all" : mAdapter.get(position).getSite().getKey();
    }

    private void restoreSite(String key) {
        for (int i = 0; i < mAdapter.getItemCount(); i++) {
            if (!mAdapter.get(i).getSite().getKey().equals(key)) continue;
            mBinding.recycler.setSelectedPosition(i);
            mBinding.pager.setCurrentItem(i, false);
            restoreResultAnchor(i);
            return;
        }
    }

    private void restoreResultAnchor(int sitePosition) {
        if (mAnchorVodId.isEmpty()) return;
        CollectFragment fragment = (CollectFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, sitePosition);
        fragment.restoreAnchor(mAnchorSiteKey, mAnchorVodId);
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
        mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager()));
    }

    private void search() {
        if (mSites.isEmpty()) return;
        mAdapter.add(Collect.all());
        requestRecyclerFocus();
        mBinding.pager.getAdapter().notifyDataSetChanged();
        mViewModel.searchContent(mSites, getKeyword(), false);
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child) {
        if (mOldView != null) mOldView.setSelected(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setSelected(true);
        App.post(mRunnable, 100);
    }

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            int position = clampPosition(mBinding.recycler.getSelectedPosition());
            if (position != RecyclerView.NO_POSITION) mBinding.pager.setCurrentItem(position);
        }
    };

    private int clampPosition(int position) {
        int size = mAdapter.getItemCount();
        if (size <= 0) return RecyclerView.NO_POSITION;
        if (position < 0) return 0;
        return Math.min(position, size - 1);
    }

    private void requestRecyclerFocus() {
        mBinding.recycler.post(() -> {
            int position = clampPosition(mBinding.recycler.getSelectedPosition());
            if (position == RecyclerView.NO_POSITION || !canRequestFocus(mBinding.recycler)) return;
            mBinding.recycler.setSelectedPosition(position);
            mBinding.recycler.postDelayed(() -> {
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

    class PageAdapter extends FragmentStatePagerAdapter {

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
            return mAdapter.getItemCount();
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            return POSITION_NONE;
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
