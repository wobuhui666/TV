package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.api.rex.DoubanBridgeClient;
import com.fongmi.android.tv.api.rex.RexNowClient;
import com.fongmi.android.tv.api.rex.RexTmdbClient;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.DiscoverRequestState;
import com.fongmi.android.tv.bean.RexSection;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityDiscoverResultBinding;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.ui.rex.RexSections;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 栏目列表承接页：精选大卡点击进入；TMDB 源可翻页续拉，feed/云桥源直接展示已载入集合。 */
public class RexSectionActivity extends BaseActivity implements VodPresenter.OnClickListener, CustomScroller.Callback {

    private static final int COLUMN = 6;
    private final Object tag = new Object();
    private ActivityDiscoverResultBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private CustomScroller scroller;
    private RexSection section;
    private int page;
    private int totalPages = 1;
    private final List<ArrayObjectAdapter> rows = new ArrayList<>();
    private final DiscoverRequestState requestState = new DiscoverRequestState();
    private PosterWallFilterController filters;
    private int generation;
    private boolean initialFocus = true;

    public static void start(Activity activity, RexSection section) {
        Intent intent = new Intent(activity, RexSectionActivity.class);
        intent.putExtra("section_key", section.getKey());
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDiscoverResultBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        section = RexSections.byKey(getIntent().getStringExtra("section_key"));
        if (section == null) {
            finish();
            return;
        }
        mBinding.title.setText(section.getLabelRes());
        applySourceLabel();
        setRecyclerView();
        filters = new PosterWallFilterController(mBinding.filterContainer, section.getQuery(), this::onFiltersChanged);
        generation = requestState.reset();
        load(1);
    }

    /** TMDB 栏目挂编辑推荐标签；豆瓣系挂豆瓣标签；公开 feed 栏目不显示来源。 */
    private void applySourceLabel() {
        int label = switch (section.getSource()) {
            case TMDB_LIST, TMDB_DISCOVER -> R.string.discover_source_tmdb;
            case BRIDGE_CATALOG, REXNOW_DOUBAN -> R.string.discover_source_douban;
            default -> 0;
        };
        mBinding.sourceLabel.setVisibility(label == 0 ? View.GONE : View.VISIBLE);
        if (label != 0) mBinding.sourceLabel.setText(isRemote() ? getString(label)
                : getString(label) + " · " + getString(R.string.poster_wall_filter_current));
    }

    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_NONE), VodPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.addOnScrollListener(scroller = new CustomScroller(this));
    }

    private void load(int targetPage) {
        if (targetPage == 1 && mAdapter.size() == 0) mBinding.progressLayout.showProgress();
        int ticket = generation;
        switch (section.getSource()) {
            case TMDB_LIST, TMDB_DISCOVER -> RexTmdbClient.fetchPage(filters.query(), targetPage, BuildConfig.TMDB_API_KEY, tag, new RexTmdbClient.PageListener() {
                @Override
                public void onSuccess(List<Vod> items, int target, int pages) {
                    if (isInactive() || ticket != generation) return;
                    page = target;
                    totalPages = pages;
                    complete(items, true);
                }

                @Override
                public void onError(Exception e) {
                    if (isInactive() || ticket != generation) return;
                    com.fongmi.android.tv.bean.Result result = newResult(List.of());
                    scroller.endLoading(result);
                    scroller.setEnable(totalPages);
                    if (targetPage == 1) mBinding.progressLayout.showContent(true, 0);
                }
            });
            case REXNOW_ANIME -> RexNowClient.fetchAnime(tag, new RexNowClient.WeeklyListener() {
                @Override
                public void onSuccess(Map<String, List<Vod>> weekly) {
                    if (isInactive()) return;
                    List<Vod> all = new ArrayList<>();
                    for (List<Vod> group : weekly.values()) all.addAll(group);
                    filters.setLocalItems(RexNowClient.mergeDistinct(List.of(), all, 0), weekly);
                    completeLocal();
                }

                @Override
                public void onError(Exception e) {
                    if (isInactive()) return;
                    completeLocal(List.of());
                }
            });
            case BRIDGE_CATALOG -> loadBridge();
            case REXNOW_REGIONAL -> RexNowClient.fetchRegional(section.getRegion(), section.getCategory(), tag, feedListener());
            case REXNOW_DOUBAN -> RexNowClient.fetchDouban(section.getFeedCategory(), tag, feedListener());
        }
    }

    private RexNowClient.ItemsListener feedListener() {
        return new RexNowClient.ItemsListener() {
            @Override
            public void onSuccess(List<Vod> items) {
                if (isInactive()) return;
                completeLocal(items);
            }

            @Override
            public void onError(Exception e) {
                if (isInactive()) return;
                completeLocal(List.of());
            }
        };
    }

    /** 云桥优先；无 sk / 401 / 失败回退 rexnow 豆瓣 feed（与首页加载器同策略）。 */
    private void loadBridge() {
        String sk = Setting.getBridgeSk();
        if (sk == null || sk.trim().isEmpty()) {
            loadBridgeFallback();
            return;
        }
        DoubanBridgeClient.fetchCatalog(Setting.getBridgeBase(), sk, null, section.getCollectionId(), 0, tag, new DoubanBridgeClient.Listener() {
            @Override
            public void onSuccess(List<Vod> items) {
                if (isInactive()) return;
                if (items.isEmpty() && BrowseExperienceSettings.isCompactHomeEnabled()) loadBridgeFallback();
                else completeLocal(items);
            }

            @Override
            public void onError(Exception e) {
                if (isInactive()) return;
                loadBridgeFallback();
            }

            @Override
            public void onUnauthorized() {
                if (isInactive()) return;
                loadBridgeFallback();
            }
        });
    }

    private void loadBridgeFallback() {
        if (isInactive()) return;
        if (!BrowseExperienceSettings.isCompactHomeEnabled()) {
            RexNowClient.fetchDouban(feedCategory(), tag, feedListener());
            return;
        }
        String type = section.getCollectionId().startsWith("movie") ? DiscoverMediaKey.MOVIE : DiscoverMediaKey.TV;
        RexNowClient.fetchDouban(feedCategory(), tag, new RexNowClient.ItemsListener() {
            @Override public void onSuccess(List<Vod> items) {
                if (isInactive()) return;
                List<Vod> selected = items.stream().filter(item -> type.equals(item.getTypeName())).toList();
                if (selected.isEmpty()) loadDirectDouban();
                else completeLocal(selected);
            }
            @Override public void onError(Exception error) {
                if (!isInactive()) loadDirectDouban();
            }
        });
    }

    private void loadDirectDouban() {
        DiscoverApi.Row row = section.getCollectionId().startsWith("movie")
                ? DiscoverApi.Row.DOUBAN_HOT_MOVIE : DiscoverApi.Row.DOUBAN_HOT_TV;
        int ticket = generation;
        DiscoverApi.fetch(row, BuildConfig.TMDB_API_KEY, tag, new DiscoverApi.Listener() {
            @Override public void onSuccess(DiscoverApi.Row row, List<Vod> items) {
                if (!isInactive() && ticket == generation) completeLocal(items);
            }
            @Override public void onError(DiscoverApi.Row row, Exception error) {
                if (!isInactive() && ticket == generation) completeLocal(List.of());
            }
        });
    }

    private String feedCategory() {
        return section.getFeedCategory().isEmpty() ? "trending" : section.getFeedCategory();
    }

    private boolean isRemote() {
        return section.getSource() == RexSection.Source.TMDB_LIST || section.getSource() == RexSection.Source.TMDB_DISCOVER;
    }

    private void onFiltersChanged() {
        initialFocus = false;
        if (!isRemote()) {
            completeLocal();
            return;
        }
        RexTmdbClient.cancel(tag);
        generation = requestState.reset();
        page = 0;
        totalPages = 1;
        rows.clear();
        mAdapter.clear();
        scroller.reset();
        load(1);
    }

    private void completeLocal(List<Vod> items) {
        filters.setLocalItems(items, null);
        completeLocal();
    }

    private void completeLocal() {
        page = totalPages = 1;
        rows.clear();
        mAdapter.clear();
        complete(filters.filterLocal(), false);
    }

    private void complete(List<Vod> items, boolean remote) {
        scroller.endLoading(newResult(items));
        scroller.setEnable(totalPages);
        if (page == 1) mBinding.progressLayout.showContent(true, items.size());
        add(remote ? requestState.addAllAndGetAdded(generation, items) : items);
    }

    private com.fongmi.android.tv.bean.Result newResult(List<Vod> items) {
        com.fongmi.android.tv.bean.Result result = new com.fongmi.android.tv.bean.Result();
        result.setList(items);
        return result;
    }

    private void add(List<Vod> items) {
        if (items.isEmpty()) return;
        int offset = 0;
        if (!rows.isEmpty()) {
            ArrayObjectAdapter last = rows.get(rows.size() - 1);
            int count = Math.min(COLUMN - last.size(), items.size());
            if (count > 0) {
                last.addAll(last.size(), items.subList(0, count));
                offset = count;
            }
        }
        int width = (ResUtil.getScreenWidth() - ResUtil.dp2px(112 + 16 * (COLUMN - 1))) / COLUMN;
        VodPresenter presenter = new VodPresenter(this, Style.rect(), new int[]{width, Math.round(width / 0.75f)});
        for (List<Vod> part : Lists.partition(items.subList(offset, items.size()), COLUMN)) {
            ArrayObjectAdapter row = new ArrayObjectAdapter(presenter);
            row.addAll(0, part);
            rows.add(row);
            mAdapter.add(new ListRow(row));
        }
        if (initialFocus) {
            initialFocus = false;
            requestFocus();
        }
    }

    private void requestFocus() {
        int ticket = generation;
        mBinding.recycler.postDelayed(() -> {
            if (isInactive() || ticket != generation || mBinding.filterContainer.hasFocus()) return;
            RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(0);
            View target = holder == null ? null : findFocusable(holder.itemView);
            if (target != null && target.requestFocus()) return;
            mBinding.recycler.requestFocus();
        }, 80);
    }

    private View findFocusable(View view) {
        if (view == null || !view.isShown() || !view.isEnabled()) return null;
        if (view.isFocusable()) return view;
        if (!(view instanceof android.view.ViewGroup group)) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View result = findFocusable(group.getChildAt(i));
            if (result != null) return result;
        }
        return null;
    }

    @Override
    public void onItemClick(Vod item) {
        openDetail(item, null);
    }

    @Override
    public void onItemClick(Vod item, View poster) {
        openDetail(item, poster);
    }

    private void openDetail(Vod item, View poster) {
        if (BrowseExperienceSettings.isDetailSourcesEnabled()) {
            DiscoverDetailActivity.start(this, item, poster);
            return;
        }
        DiscoverMediaKey key = DiscoverMediaKey.parse(item.getId());
        if (key == null) {
            CollectActivity.start(this, item.getName());
            return;
        }
        DiscoverDetailActivity.start(this, key, item.getName(), item.getPic(), item.getBackdrop(), item.getContent(), item.getYear(), item.getRemarks(), poster);
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }

    @Override
    public boolean onLoadMore(String ignored) {
        if (isInactive() || !isRemote() || page >= totalPages) return false;
        load(page + 1);
        return true;
    }

    @Override
    protected void onDestroy() {
        DiscoverApi.cancel(tag);
        RexTmdbClient.cancel(tag);
        RexNowClient.cancel(tag);
        DoubanBridgeClient.cancel(tag);
        if (scroller != null) mBinding.recycler.removeOnScrollListener(scroller);
        if (filters != null) filters.release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && filters != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) filters.focus();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && filters != null
                && mBinding.filterContainer.hasFocus() && event.getAction() == KeyEvent.ACTION_UP && filters.collapse()) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean isInactive() {
        return isFinishing() || isDestroyed();
    }
}
