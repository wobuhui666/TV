package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.databinding.FragmentFolderBinding;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;

import java.util.HashMap;
import java.util.Optional;

public class FolderFragment extends BaseFragment {

    private FragmentFolderBinding mBinding;
    private Boolean mPendingFilter;
    private boolean mPendingRefresh;
    private Class mType;

    public static FolderFragment newInstance(String key, Class type) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putParcelable("type", type);
        FolderFragment fragment = new FolderFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    public Class getType() {
        return getArguments().getParcelable("type");
    }

    private TypeFragment getChild() {
        return (TypeFragment) getChildFragmentManager().findFragmentById(R.id.container);
    }

    private VodActivity getParent() {
        return (VodActivity) getActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentFolderBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mType = getType();
        if (mPendingFilter == null) mPendingFilter = mType.getFilter();
        getChildFragmentManager().beginTransaction().replace(R.id.container, TypeFragment.newInstance(getKey(), mType.getTypeId(), mType.getStyle(), getExtend(), mType.isFolder())).runOnCommit(this::applyPendingState).commit();
    }

    private HashMap<String, String> getExtend() {
        HashMap<String, String> extend = new HashMap<>();
        for (Filter filter : Cache.get(mType)) if (filter.getInit() != null) extend.put(filter.getKey(), filter.getInit());
        return extend;
    }

    public void openFolder(String typeId, HashMap<String, String> extend) {
        FragmentManager manager = getChildFragmentManager();
        if (mType == null || !isAdded() || isHidden() || !getUserVisibleHint() || manager.isStateSaved()) return;
        TypeFragment next = TypeFragment.newInstance(getKey(), typeId, mType.getStyle(), extend, mType.isFolder());
        FragmentTransaction ft = manager.beginTransaction();
        Optional.ofNullable(getParent()).ifPresent(VodActivity::closeFilter);
        Optional.ofNullable(getChild()).ifPresent(ft::hide);
        ft.add(R.id.container, next);
        ft.addToBackStack(null);
        ft.commit();
        manager.executePendingTransactions();
    }

    public void toggleFilter(boolean visible) {
        TypeFragment child = getChild();
        if (child == null) {
            mPendingFilter = visible;
            return;
        }
        mPendingFilter = null;
        child.toggleFilter(visible);
    }

    private void applyPendingState() {
        TypeFragment child = getChild();
        if (child == null) return;
        if (mPendingFilter != null) {
            boolean visible = mPendingFilter;
            mPendingFilter = null;
            child.toggleFilter(visible);
        }
        if (mPendingRefresh) {
            mPendingRefresh = false;
            child.onRefresh();
        }
    }

    public void onRefresh() {
        TypeFragment child = getChild();
        if (child == null) {
            mPendingRefresh = true;
            return;
        }
        mPendingRefresh = false;
        child.onRefresh();
    }

    public boolean canBack() {
        return getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    public void goBack() {
        FragmentManager manager = getChildFragmentManager();
        if (!manager.isStateSaved()) manager.popBackStackImmediate();
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (mBinding != null && !isVisibleToUser) Optional.ofNullable(getChild()).ifPresent(f -> f.setUserVisibleHint(false));
    }
}
