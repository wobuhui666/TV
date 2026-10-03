package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import android.view.KeyEvent;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.ActivityKeepBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.KeepAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class KeepActivity extends BaseActivity implements KeepAdapter.OnClickListener {

    private ActivityKeepBinding mBinding;
    private KeepAdapter mAdapter;
    private long focusGeneration;
    private boolean initialFocus = true;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        com.fongmi.android.tv.ui.custom.JetStreamEmptyStateView empty = mBinding.progressLayout.findViewById(R.id.empty_state);
        if (empty != null) empty.setText(R.string.tv_empty_favorites);
        getKeep();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter = new KeepAdapter(this));
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(Product.getColumn(), 16));
    }

    private void getKeep() {
        long generation = ++focusGeneration;
        View focused = getCurrentFocus();
        boolean restoreFocus = focused == null || mBinding.recycler.hasFocus();
        View card = focused == null ? null : mBinding.recycler.findContainingItemView(focused);
        int position = card == null ? RecyclerView.NO_POSITION : mBinding.recycler.getChildAdapterPosition(card);
        String selectedKey = position >= 0 && position < mAdapter.getItemCount() ? mAdapter.getItems().get(position).getKey() : null;
        mAdapter.setItems(Keep.getVodAndDiscover(), () -> {
            if (isFinishing() || isDestroyed()) return;
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            int target = Math.max(0, position);
            if (selectedKey != null) {
                for (int i = 0; i < mAdapter.getItemCount(); i++) {
                    if (selectedKey.equals(mAdapter.getItems().get(i).getKey())) { target = i; break; }
                }
            }
            if (initialFocus) requestInitialFocus();
            else if (restoreFocus) requestFocus(Math.min(target, mAdapter.getItemCount() - 1), generation);
        });
    }

    private void requestFocus(int position, long generation) {
        mBinding.recycler.post(() -> {
            if (!canFocus(generation, mBinding.recycler)) return;
            if (position < 0 || position >= mAdapter.getItemCount()) return;
            mBinding.recycler.scrollToPosition(position);
            mBinding.recycler.postDelayed(() -> {
                if (!canFocus(generation, mBinding.recycler)) return;
                RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(position);
                if (holder != null && canFocus(generation, holder.itemView)) holder.itemView.requestFocus();
                else mBinding.recycler.requestFocus();
            }, 50);
        });
    }

    private boolean canFocus(long generation, View target) {
        return generation == focusGeneration && !isFinishing() && !isDestroyed()
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                && hasWindowFocus() && target.isAttachedToWindow() && target.isShown() && target.isEnabled();
    }

    private void requestInitialFocus() {
        if (!initialFocus || mAdapter == null || mAdapter.getItemCount() == 0 || !canFocus(focusGeneration, mBinding.recycler)) return;
        initialFocus = false;
        requestFocus(0, focusGeneration);
    }

    private void loadConfig(Config config, Keep item) {
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.KEEP) getKeep();
    }

    @Override
    public void onItemClick(Keep item) {
        initialFocus = false;
        focusGeneration++;
        if (item.getType() == Keep.TYPE_DISCOVER) {
            DiscoverMediaKey key = DiscoverMediaKey.parse(item.getKey());
            if (key != null) DiscoverDetailActivity.start(this, key, item.getVodName(), item.getVodPic(), "", "", "", "", null);
            return;
        }
        Config config = Config.find(item.getCid());
        if (config == null) CollectActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(Keep item) {
        initialFocus = false;
        long generation = ++focusGeneration;
        int position = mAdapter.getItems().indexOf(item);
        mAdapter.remove(item.delete(), () -> {
            if (isFinishing() || isDestroyed()) return;
            if (mAdapter.getItemCount() == 0) {
                mAdapter.setDelete(false);
                mBinding.progressLayout.showContent(true, 0);
            } else {
                requestFocus(Math.min(Math.max(position, 0), mAdapter.getItemCount() - 1), generation);
            }
        });
    }

    @Override
    public boolean onLongClick() {
        initialFocus = false;
        focusGeneration++;
        mAdapter.setDelete(true);
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            initialFocus = false;
            focusGeneration++;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        focusGeneration++;
        requestInitialFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) requestInitialFocus();
        else focusGeneration++;
    }

    @Override
    protected void onPause() {
        initialFocus = false;
        focusGeneration++;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        focusGeneration++;
        super.onDestroy();
    }

    @Override
    protected void onBackInvoked() {
        initialFocus = false;
        focusGeneration++;
        if (mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }
}
