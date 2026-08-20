package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.setting.SourceSelectionSetting;
import com.fongmi.android.tv.source.SourceAggregator;
import com.fongmi.android.tv.source.SourceSelectionMode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

public class CollectFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener {

    private static final int PAGE_HORIZONTAL_PADDING = 128;
    private static final int ROW_HORIZONTAL_SPACING = 16;

    private FragmentTypeBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private Collect mCollect;
    private String mKeyword;
    private final SourceAggregator mAggregator = new SourceAggregator();
    private final List<Vod> mAggregated = new ArrayList<>();

    public static CollectFragment newInstance(String keyword, Collect collect) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        CollectFragment fragment = new CollectFragment().setCollect(collect);
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return mKeyword = mKeyword == null ? getArguments().getString("keyword") : mKeyword;
    }

    private CollectFragment setCollect(Collect collect) {
        this.mCollect = collect;
        return this;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setRecyclerView();
        setViewModel();
        addVideo(mCollect);
    }

    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.addOnScrollListener(mScroller = new CustomScroller(this));
        mBinding.recycler.setHeader(getActivity(), R.id.recyclerPanel, R.id.recycler);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            mScroller.endLoading(result);
            addVideo(result.getList());
        });
    }

    private boolean checkLastSize(List<Vod> items) {
        if (mLast == null || items.isEmpty()) return false;
        int size = Product.getColumn() - mLast.size();
        if (size == 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addVideo(items.subList(size, items.size()));
        return true;
    }

    private void addVideo(Collect collect) {
        if (collect != null) addVideo(collect.getList());
    }

    public void addVideo(List<Vod> items) {
        if (isAllPage() && SourceSelectionSetting.getMode() != SourceSelectionMode.LEGACY) {
            mAggregator.mergeInto(mAggregated, items);
            items = new ArrayList<>(mAggregated);
            mAdapter.clear();
            mLast = null;
        }
        if (checkLastSize(items) || getActivity() == null || getActivity().isFinishing()) return;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, Style.rect(), getPageSpec(Style.rect()));
        for (List<Vod> part : Lists.partition(items, Product.getColumn())) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private int[] getPageSpec(Style style) {
        int column = Product.getColumn(style);
        int space = ResUtil.dp2px(PAGE_HORIZONTAL_PADDING) + ResUtil.dp2px(ROW_HORIZONTAL_SPACING * (column - 1));
        if (style.isOval()) space += ResUtil.dp2px(column * 16);
        return Product.getSpec(space, column, style);
    }

    @Override
    public void onItemClick(Vod item) {
        onItemClick(item, null);
    }

    @Override
    public void onItemClick(Vod item, View poster) {
        requireActivity().setResult(Activity.RESULT_OK);
        if (item.isFolder()) VodActivity.start(requireActivity(), item.getSiteKey(), Result.folder(item));
        else chooseSource(item, poster);
    }

    private void chooseSource(Vod item, View poster) {
        List<Vod> options = item.getSourceOptions();
        if (SourceSelectionSetting.getMode() != SourceSelectionMode.GROUP_ONLY || options.size() <= 1) {
            VideoActivity.collect(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), item.getPic(), item.getSourceCandidates(), poster);
            return;
        }
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            Vod option = options.get(i);
            labels[i] = option.getSiteName().isEmpty() ? option.getName() : option.getSiteName();
        }
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(item.getName())
                .setSingleChoiceItems(labels, 0, (dialog, which) -> {
                    Vod selected = options.get(which);
                    List<Vod> remaining = new ArrayList<>(options);
                    remaining.remove(which);
                    VideoActivity.collect(requireActivity(), selected.getSiteKey(), selected.getId(), selected.getName(), selected.getPic(), remaining, poster);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }

    @Override
    public boolean onLoadMore(String page) {
        if (mCollect == null || isAllPage()) return false;
        mViewModel.searchContent(mCollect.getSite(), getKeyword(), false, page);
        return true;
    }

    private boolean isAllPage() {
        return mCollect != null && "all".equals(mCollect.getSite().getKey());
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (mBinding != null && !isVisibleToUser) mBinding.recycler.moveToTop();
    }
}
