package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.FragmentFolderBinding;
import com.fongmi.android.tv.ui.base.BaseFragment;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class FolderFragment extends BaseFragment {

    private Class mType;
    private TypeFragment mPendingChild;
    private boolean mViewReady;
    private boolean mPendingRefresh;
    private boolean mPendingScrollToTop;
    private final Map<String, Value> mPendingFilters = new LinkedHashMap<>();
    private final FragmentManager.OnBackStackChangedListener mBackStackChangedListener = this::onBackStackChanged;

    public static FolderFragment newInstance(String key, Class type, int y) {
        Bundle args = new Bundle();
        args.putInt("y", y);
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

    private int getY() {
        return getArguments().getInt("y");
    }

    @Nullable
    private VodFragment getParent() {
        return getParentFragment() instanceof VodFragment fragment ? fragment : null;
    }

    @Nullable
    private TypeFragment getChild() {
        if (mPendingChild != null) return mPendingChild;
        Fragment fragment = getChildFragmentManager().findFragmentById(R.id.container);
        return fragment instanceof TypeFragment ? (TypeFragment) fragment : null;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return FragmentFolderBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mType = getType();
        getChildFragmentManager().addOnBackStackChangedListener(mBackStackChangedListener);
        TypeFragment current = getChild();
        if (current != null) {
            mViewReady = true;
            dispatchPendingCommands();
            return;
        }
        TypeFragment child = TypeFragment.newInstance(getKey(), mType.getTypeId(), mType.getStyle(), getExtend(), mType.isFolder(), getY());
        mPendingChild = child;
        mViewReady = true;
        dispatchPendingCommands();
        getChildFragmentManager().beginTransaction().replace(R.id.container, child).runOnCommit(() -> onChildCommitted(child)).commit();
    }

    private HashMap<String, String> getExtend() {
        HashMap<String, String> extend = new HashMap<>();
        for (Filter filter : mType.getFilters()) if (filter.getInit() != null) extend.put(filter.getKey(), filter.setSelected(filter.getInit()));
        return extend;
    }

    public void openFolder(String typeId, HashMap<String, String> extend) {
        FragmentManager manager = getChildFragmentManager();
        if (!mViewReady || !isAdded() || isHidden() || !getUserVisibleHint() || mPendingChild != null || manager.isStateSaved()) return;
        TypeFragment next = TypeFragment.newInstance(getKey(), typeId, mType.getStyle(), extend, mType.isFolder(), getY());
        TypeFragment current = getChild();
        FragmentTransaction ft = manager.beginTransaction();
        if (current != null) ft.hide(current);
        mPendingChild = next;
        dispatchPendingCommands();
        ft.add(R.id.container, next);
        ft.addToBackStack(null);
        ft.commit();
        manager.executePendingTransactions();
    }

    public Result getResult() {
        VodFragment parent = getParent();
        return parent == null ? new Result() : parent.getResult();
    }

    public void onRefresh() {
        if (!mViewReady) {
            mPendingRefresh = true;
            return;
        }
        TypeFragment child = getChild();
        if (child == null) mPendingRefresh = true;
        else child.onRefresh();
    }

    public void scrollToTop() {
        if (!mViewReady) {
            mPendingScrollToTop = true;
            return;
        }
        TypeFragment child = getChild();
        if (child == null) mPendingScrollToTop = true;
        else child.scrollToTop();
    }

    public void setFilter(String key, Value value) {
        if (!mViewReady) {
            mPendingFilters.put(key, value.copy());
            mPendingRefresh = false;
            return;
        }
        TypeFragment child = getChild();
        if (child == null) {
            mPendingFilters.put(key, value.copy());
            mPendingRefresh = false;
        } else child.setFilter(key, value);
    }

    public void setFilters(Map<String, Value> filters) {
        if (filters.isEmpty()) return;
        if (!mViewReady) {
            for (Map.Entry<String, Value> entry : filters.entrySet()) mPendingFilters.put(entry.getKey(), entry.getValue().copy());
            mPendingRefresh = false;
            return;
        }
        TypeFragment child = getChild();
        if (child == null) {
            for (Map.Entry<String, Value> entry : filters.entrySet()) mPendingFilters.put(entry.getKey(), entry.getValue().copy());
            mPendingRefresh = false;
        } else {
            child.setFilters(filters);
        }
    }

    private void dispatchPendingCommands() {
        if (!mViewReady) return;
        TypeFragment child = getChild();
        if (child == null) return;
        boolean hasFilters = !mPendingFilters.isEmpty();
        if (hasFilters) {
            Map<String, Value> filters = new LinkedHashMap<>(mPendingFilters);
            mPendingFilters.clear();
            child.setFilters(filters);
        }
        if (mPendingRefresh && !hasFilters) child.onRefresh();
        if (mPendingScrollToTop) child.scrollToTop();
        mPendingRefresh = false;
        mPendingScrollToTop = false;
    }

    private void onChildCommitted(TypeFragment child) {
        dispatchPendingCommands();
        if (mPendingChild == child) mPendingChild = null;
    }

    private void onBackStackChanged() {
        if (mPendingChild != null && !mPendingChild.isAdded()) return;
        dispatchPendingCommands();
        mPendingChild = null;
    }

    public boolean canBack() {
        return getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    public void goBack() {
        FragmentManager manager = getChildFragmentManager();
        if (!manager.isStateSaved()) manager.popBackStackImmediate();
    }

    @Override
    public void onDestroyView() {
        mViewReady = false;
        getChildFragmentManager().removeOnBackStackChangedListener(mBackStackChangedListener);
        mPendingChild = null;
        super.onDestroyView();
    }
}
