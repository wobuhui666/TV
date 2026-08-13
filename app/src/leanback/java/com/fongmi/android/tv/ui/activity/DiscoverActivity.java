package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverFacet;
import com.fongmi.android.tv.bean.DiscoverFilterOption;
import com.fongmi.android.tv.bean.DiscoverFilterPanel;
import com.fongmi.android.tv.bean.DiscoverHero;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.DiscoverQuery;
import com.fongmi.android.tv.bean.DiscoverRankItem;
import com.fongmi.android.tv.bean.DiscoverRequestState;
import com.fongmi.android.tv.bean.DoubanDetail;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityDiscoverBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.dialog.DiscoverDialog;
import com.fongmi.android.tv.ui.presenter.DiscoverFilterPanelPresenter;
import com.fongmi.android.tv.ui.presenter.DiscoverHeroPresenter;
import com.fongmi.android.tv.ui.presenter.DiscoverLandscapePresenter;
import com.fongmi.android.tv.ui.presenter.DiscoverRankPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DiscoverActivity extends BaseActivity implements VodPresenter.OnClickListener,
        DiscoverFilterPanelPresenter.Listener, CustomScroller.Callback {

    private static final int FEATURE_REQUESTS = 10;
    private static final int FILTER_MEDIA = 0;
    private static final int FILTER_GENRE = 1;
    private static final int FILTER_REGION = 2;
    private static final int FILTER_YEAR = 3;
    private static final int FILTER_SORT = 4;
    private static final int RANK_LIMIT = 10;

    private final Map<DiscoverApi.Row, List<Vod>> content = new EnumMap<>(DiscoverApi.Row.class);
    private final Map<DiscoverApi.Row, ArrayObjectAdapter> posterRows = new EnumMap<>(DiscoverApi.Row.class);
    private final Map<DiscoverApi.Row, ArrayObjectAdapter> landscapeRows = new EnumMap<>(DiscoverApi.Row.class);
    private final Map<DiscoverApi.Row, ArrayObjectAdapter> rankRows = new EnumMap<>(DiscoverApi.Row.class);
    private final Map<Integer, Integer> sectionPositions = new LinkedHashMap<>();
    private final List<ArrayObjectAdapter> resultAdapters = new ArrayList<>();
    private final DiscoverRequestState requestState = new DiscoverRequestState();
    private final DiscoverHero hero = new DiscoverHero();
    private final DiscoverFilterPanel filterPanel = new DiscoverFilterPanel();
    private final Set<String> heroDetailRequests = new HashSet<>();
    private final Object requestTag = new Object();
    private ActivityDiscoverBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private CustomScroller scroller;
    private List<DiscoverFacet> genres = List.of();
    private String mediaType = DiscoverMediaKey.MOVIE;
    private String genreId = "";
    private String region = "";
    private String dateStart = "";
    private String dateEnd = "";
    private String sort = DiscoverQuery.SORT_POPULAR;
    private DiscoverQuery lastQuery;
    private int featurePending;
    private int queryGeneration;
    private int page = 1;
    private int totalPages = 1;
    private int resultStartPosition;
    private int resultRowCount;
    private boolean featureFinished;
    private boolean queryFinished;
    private boolean destroyed;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DiscoverActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDiscoverBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        buildStablePage();
        loadFeatured();
        loadGenres(mediaType);
        refreshResults(true);
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(DiscoverHero.class, new DiscoverHeroPresenter(this));
        selector.addPresenter(DiscoverFilterPanel.class, new DiscoverFilterPanelPresenter(this));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_NONE), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(18, FocusHighlight.ZOOM_FACTOR_NONE), DiscoverLandscapePresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(10, FocusHighlight.ZOOM_FACTOR_NONE), DiscoverRankPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(10));
        mBinding.recycler.setOnKeyInterceptListener(this::interceptFocusBoundary);
        mBinding.recycler.addOnScrollListener(scroller = new CustomScroller(this));
        mBinding.progressLayout.showProgress();
    }

    private void buildStablePage() {
        mAdapter.add(hero);
        mAdapter.add(filterPanel);
        addLandscapeSection(R.string.discover_today_trending, DiscoverApi.Row.TMDB_DAY);
        addRankSection(R.string.discover_top_ten, DiscoverApi.Row.TMDB_WEEK);
        addPosterSection(R.string.discover_douban_hot_movie, DiscoverApi.Row.DOUBAN_HOT_MOVIE, new int[]{ResUtil.dp2px(132), ResUtil.dp2px(176)});
        addLandscapeSection(R.string.discover_week_trending, DiscoverApi.Row.TMDB_WEEK);
        addPosterSection(R.string.discover_douban_hot_tv, DiscoverApi.Row.DOUBAN_HOT_TV, new int[]{ResUtil.dp2px(132), ResUtil.dp2px(176)});
        addPosterSection(R.string.discover_douban_new_movie, DiscoverApi.Row.DOUBAN_NEW_MOVIE, new int[]{ResUtil.dp2px(132), ResUtil.dp2px(176)});
        addPosterSection(R.string.discover_now_playing, DiscoverApi.Row.TMDB_NOW_PLAYING, new int[]{ResUtil.dp2px(132), ResUtil.dp2px(176)});
        addPosterSection(R.string.discover_popular_selection, DiscoverApi.Row.TMDB_POPULAR_MOVIE, new int[]{ResUtil.dp2px(132), ResUtil.dp2px(176)});
        addPosterSection(R.string.discover_top_rated, DiscoverApi.Row.TMDB_TOP_MOVIE, new int[]{ResUtil.dp2px(132), ResUtil.dp2px(176)});
        mAdapter.add(R.string.discover_filter_results);
        resultStartPosition = mAdapter.size();
        mAdapter.add("discover_filter_progress");
        resultRowCount = 1;
        updateFilterPanel();
    }

    private void addPosterSection(int title, DiscoverApi.Row row, int[] size) {
        sectionPositions.put(title, mAdapter.size());
        mAdapter.add(title);
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(new VodPresenter(this, Style.rect(), size));
        posterRows.put(row, adapter);
        mAdapter.add(new ListRow(adapter));
    }

    private void addLandscapeSection(int title, DiscoverApi.Row row) {
        sectionPositions.put(title, mAdapter.size());
        mAdapter.add(title);
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(new DiscoverLandscapePresenter(this));
        landscapeRows.put(row, adapter);
        mAdapter.add(new ListRow(adapter));
    }

    private void addRankSection(int title, DiscoverApi.Row row) {
        sectionPositions.put(title, mAdapter.size());
        mAdapter.add(title);
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(new DiscoverRankPresenter(this));
        rankRows.put(row, adapter);
        mAdapter.add(new ListRow(adapter));
    }

    private void loadFeatured() {
        featurePending = FEATURE_REQUESTS;
        featureFinished = false;
        DiscoverApi.Row[] rows = {
                DiscoverApi.Row.DOUBAN_HOT_MOVIE, DiscoverApi.Row.DOUBAN_HOT_TV, DiscoverApi.Row.DOUBAN_NEW_MOVIE,
                DiscoverApi.Row.TMDB_DAY, DiscoverApi.Row.TMDB_WEEK, DiscoverApi.Row.TMDB_NOW_PLAYING,
                DiscoverApi.Row.TMDB_POPULAR_MOVIE, DiscoverApi.Row.TMDB_POPULAR_TV,
                DiscoverApi.Row.TMDB_TOP_MOVIE, DiscoverApi.Row.TMDB_TOP_TV
        };
        for (DiscoverApi.Row row : rows) DiscoverApi.fetch(row, BuildConfig.TMDB_API_KEY, requestTag, new DiscoverApi.Listener() {
            @Override
            public void onSuccess(DiscoverApi.Row value, List<Vod> items) {
                if (isInactive()) return;
                content.put(value, items);
                updatePosterRow(value, items);
                updateHero();
                completeFeatured();
            }

            @Override
            public void onError(DiscoverApi.Row value, Exception e) {
                if (isInactive()) return;
                content.put(value, List.of());
                updateHero();
                completeFeatured();
            }
        });
    }

    private void updatePosterRow(DiscoverApi.Row row, List<Vod> items) {
        ArrayObjectAdapter posterAdapter = posterRows.get(row);
        if (posterAdapter != null) {
            posterAdapter.clear();
            posterAdapter.addAll(0, items);
        }
        ArrayObjectAdapter landscapeAdapter = landscapeRows.get(row);
        if (landscapeAdapter != null) {
            landscapeAdapter.clear();
            landscapeAdapter.addAll(0, items);
        }
        ArrayObjectAdapter rankAdapter = rankRows.get(row);
        if (rankAdapter != null) {
            rankAdapter.clear();
            List<DiscoverRankItem> ranks = new ArrayList<>();
            for (int i = 0; i < Math.min(RANK_LIMIT, items.size()); i++) ranks.add(new DiscoverRankItem(i + 1, items.get(i)));
            rankAdapter.addAll(0, ranks);
        }
        if (posterAdapter == null && landscapeAdapter == null && rankAdapter == null) return;
        Integer title = switch (row) {
            case TMDB_DAY -> R.string.discover_today_trending;
            case TMDB_WEEK -> R.string.discover_week_trending;
            case DOUBAN_HOT_MOVIE -> R.string.discover_douban_hot_movie;
            case DOUBAN_HOT_TV -> R.string.discover_douban_hot_tv;
            case DOUBAN_NEW_MOVIE -> R.string.discover_douban_new_movie;
            case TMDB_NOW_PLAYING -> R.string.discover_now_playing;
            case TMDB_POPULAR_MOVIE -> R.string.discover_popular_selection;
            case TMDB_TOP_MOVIE -> R.string.discover_top_rated;
            default -> null;
        };
        if (title != null) notifySection(title);
        if (row == DiscoverApi.Row.TMDB_WEEK) notifySection(R.string.discover_top_ten);
    }

    private void notifySection(int title) {
        Integer header = sectionPositions.get(title);
        if (header != null) mAdapter.notifyArrayItemRangeChanged(header, 2);
    }

    private void updateHero() {
        List<Vod> values = assembleHero(content);
        for (Vod item : values) {
            if (!item.getId().startsWith("douban:") || !item.getContent().isEmpty()) continue;
            if (heroDetailRequests.add(item.getId())) loadHeroDoubanDetail(item);
        }
        hero.replace(values);
        mAdapter.notifyArrayItemRangeChanged(0, 1);
    }

    static List<Vod> assembleHero(Map<DiscoverApi.Row, List<Vod>> content) {
        DiscoverApi.Row[] preferred = {
                DiscoverApi.Row.TMDB_DAY, DiscoverApi.Row.TMDB_WEEK, DiscoverApi.Row.TMDB_NOW_PLAYING,
                DiscoverApi.Row.TMDB_POPULAR_MOVIE, DiscoverApi.Row.TMDB_POPULAR_TV
        };
        DiscoverApi.Row[] fallback = {
                DiscoverApi.Row.TMDB_TOP_MOVIE, DiscoverApi.Row.TMDB_TOP_TV,
                DiscoverApi.Row.DOUBAN_HOT_MOVIE, DiscoverApi.Row.DOUBAN_HOT_TV, DiscoverApi.Row.DOUBAN_NEW_MOVIE
        };
        LinkedHashMap<String, Vod> result = new LinkedHashMap<>();
        for (DiscoverApi.Row row : preferred) {
            addHeroCandidates(result, content.get(row));
            if (result.size() >= 5) break;
        }
        for (DiscoverApi.Row row : fallback) {
            if (result.size() >= 5) break;
            addHeroCandidates(result, content.get(row));
        }
        return new ArrayList<>(result.values()).subList(0, Math.min(5, result.size()));
    }

    private static void addHeroCandidates(Map<String, Vod> result, List<Vod> items) {
        if (items == null) return;
        for (Vod item : items) {
            if (result.size() >= 5) return;
            putHero(result, item);
        }
    }

    private static boolean putHero(Map<String, Vod> result, Vod item) {
        if (item == null || item.getName().isEmpty() || item.getPic().isEmpty()) return false;
        if (!item.getId().startsWith("douban:") && item.getBackdrop().equals(item.getPic())) return false;
        String title = item.getName().toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]", "");
        if (title.isEmpty() || result.containsKey(title)) return false;
        result.put(title, item);
        return true;
    }

    private void loadHeroDoubanDetail(Vod item) {
        DiscoverApi.fetchDoubanDetail(item, requestTag, new DiscoverApi.DoubanDetailListener() {
            @Override
            public void onSuccess(DoubanDetail detail) {
                if (isInactive()) return;
                applyDoubanDetail(item, detail);
                mAdapter.notifyArrayItemRangeChanged(0, 1);
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    private void applyDoubanDetail(Vod item, DoubanDetail detail) {
        item.setName(detail.getTitle());
        item.setYear(detail.getYear());
        item.setTypeName(detail.getMediaType());
        item.setRemarks(detail.getRating());
        item.setArea(detail.getRegion());
        item.setDirector(detail.getDirectors());
        item.setActor(detail.getActors());
        item.setContent(detail.getComment());
    }

    private void completeFeatured() {
        featurePending--;
        featureFinished = featurePending <= 0;
        showContentIfReady();
    }

    private void loadGenres(String type) {
        DiscoverApi.fetchGenres(type, BuildConfig.TMDB_API_KEY, requestTag, new DiscoverApi.FacetListener() {
            @Override
            public void onSuccess(List<DiscoverFacet> items) {
                if (isInactive() || !mediaType.equals(type)) return;
                genres = items;
                updateFilterPanel();
            }

            @Override
            public void onError(Exception e) {
                if (isInactive() || !mediaType.equals(type)) return;
                genres = List.of();
                updateFilterPanel();
            }
        });
    }

    private void updateFilterPanel() {
        filterPanel.setRow(FILTER_MEDIA, mediaOptions());
        filterPanel.setRow(FILTER_GENRE, genreOptions());
        filterPanel.setRow(FILTER_REGION, regionOptions());
        filterPanel.setRow(FILTER_YEAR, yearOptions());
        filterPanel.setRow(FILTER_SORT, sortOptions());
        int position = findObjectPosition(filterPanel);
        if (position >= 0) mAdapter.notifyArrayItemRangeChanged(position, 1);
    }

    @Override
    public void onFilterGroupClick(int row) {
        filterPanel.setExpandedRow(filterPanel.getExpandedRow() == row ? -1 : row);
        int position = findObjectPosition(filterPanel);
        if (position >= 0) mAdapter.notifyArrayItemRangeChanged(position, 1);
    }

    private int findObjectPosition(Object object) {
        for (int i = 0; i < mAdapter.size(); i++) if (mAdapter.get(i) == object) return i;
        return -1;
    }

    private void refreshResults(boolean force) {
        DiscoverQuery query = currentQuery(1);
        if (!force && query.equals(lastQuery)) return;
        lastQuery = query;
        queryGeneration = requestState.reset();
        page = 1;
        totalPages = 1;
        queryFinished = false;
        if (scroller != null) {
            scroller.reset();
            scroller.setEnable(2);
        }
        replaceResultRows(List.of(), true);
        loadQuery(query, queryGeneration);
    }

    private void loadQuery(DiscoverQuery query, int generation) {
        DiscoverApi.fetch(query, BuildConfig.TMDB_API_KEY, requestTag, new DiscoverApi.QueryListener() {
            @Override
            public void onSuccess(List<Vod> items, int responsePage, int responseTotalPages) {
                if (isInactive() || !requestState.accepts(generation) || !query.equals(currentQuery(query.getPage()))) return;
                List<Vod> added = requestState.addAllAndGetAdded(generation, items);
                page = responsePage;
                totalPages = responseTotalPages;
                queryFinished = true;
                if (query.getPage() == 1) replaceResultRows(requestState.getItems(), false);
                else appendResultRows(added);
                if (scroller != null) {
                    if (query.getPage() > 1) scroller.endLoading(newResult(items));
                    scroller.setEnable(responseTotalPages);
                }
                showContentIfReady();
            }

            @Override
            public void onError(Exception e) {
                if (isInactive() || !requestState.accepts(generation)) return;
                queryFinished = true;
                if (scroller != null) {
                    if (query.getPage() > 1) scroller.endLoading(newResult(List.of()));
                    scroller.setEnable(totalPages);
                }
                showContentIfReady();
            }
        });
    }

    private void replaceResultRows(List<Vod> values, boolean loading) {
        int previousCount = resultRowCount;
        if (resultRowCount > 0) mAdapter.removeItems(resultStartPosition, resultRowCount);
        resultAdapters.clear();
        List<Object> rows = new ArrayList<>();
        if (loading) rows.add("discover_filter_progress");
        else {
            VodPresenter presenter = new VodPresenter(this, Style.rect());
            for (List<Vod> part : Lists.partition(values, Product.getColumn(Style.rect()))) {
                ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
                adapter.addAll(0, part);
                resultAdapters.add(adapter);
                rows.add(new ListRow(adapter));
            }
        }
        if (!rows.isEmpty()) mAdapter.addAll(resultStartPosition, rows);
        resultRowCount = rows.size();
        int delta = resultRowCount - previousCount;
        if (delta != 0) sectionPositions.replaceAll((title, position) -> position >= resultStartPosition ? position + delta : position);
    }

    private void appendResultRows(List<Vod> values) {
        if (values.isEmpty()) return;
        int column = Product.getColumn(Style.rect());
        int offset = 0;
        if (!resultAdapters.isEmpty()) {
            ArrayObjectAdapter last = resultAdapters.get(resultAdapters.size() - 1);
            int count = Math.min(column - last.size(), values.size());
            if (count > 0) {
                last.addAll(last.size(), values.subList(0, count));
                offset = count;
            }
        }
        if (offset >= values.size()) return;
        VodPresenter presenter = new VodPresenter(this, Style.rect());
        List<Object> rows = new ArrayList<>();
        for (List<Vod> part : Lists.partition(values.subList(offset, values.size()), column)) {
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
            adapter.addAll(0, part);
            resultAdapters.add(adapter);
            rows.add(new ListRow(adapter));
        }
        int addedCount = rows.size();
        mAdapter.addAll(resultStartPosition + resultRowCount, rows);
        resultRowCount += addedCount;
        sectionPositions.replaceAll((title, position) -> position >= resultStartPosition ? position + addedCount : position);
    }

    private boolean interceptFocusBoundary(KeyEvent event) {
        if (!KeyUtil.isActionDown(event) || mAdapter == null) return false;
        boolean up = KeyUtil.isUpKey(event);
        boolean down = KeyUtil.isDownKey(event);
        if (!up && !down) return false;
        int filterPosition = findObjectPosition(filterPanel);
        int currentPosition = getFocusedAdapterPosition();
        if (filterPosition < 0 || currentPosition == RecyclerView.NO_POSITION) return false;
        View focused = getCurrentFocus();
        if (down && isNearestNavigable(currentPosition, filterPosition, 1) && focusSearchSkips(focused, View.FOCUS_DOWN, filterPosition)) {
            return requestAdapterFocus(filterPosition, R.id.summary);
        }
        if (up && isNearestNavigable(currentPosition, filterPosition, -1) && focusSearchSkips(focused, View.FOCUS_UP, filterPosition)) {
            int target = filterPanel.getExpandedRow() >= 0 ? R.id.options : R.id.summary;
            return requestAdapterFocus(filterPosition, target);
        }
        if (currentPosition != filterPosition || focused == null) return false;
        if (up && focused.getId() == R.id.summary) {
            return requestAdapterFocus(findNavigablePosition(filterPosition, -1), View.NO_ID);
        }
        boolean atBottom = focused.getId() == R.id.options || filterPanel.getExpandedRow() < 0 && focused.getId() == R.id.summary;
        if (down && atBottom) {
            return requestAdapterFocus(findNavigablePosition(filterPosition, 1), View.NO_ID);
        }
        return false;
    }

    private boolean isNearestNavigable(int origin, int target, int step) {
        return findNavigablePosition(origin, step) == target;
    }

    private boolean focusSearchSkips(View focused, int direction, int targetPosition) {
        View next = focused == null ? null : focused.focusSearch(direction);
        if (next == null) return true;
        RecyclerView.ViewHolder holder = mBinding.recycler.findContainingViewHolder(next);
        return holder == null || holder.getBindingAdapterPosition() != targetPosition;
    }

    private int getFocusedAdapterPosition() {
        View focused = getCurrentFocus();
        if (focused == null) return RecyclerView.NO_POSITION;
        RecyclerView.ViewHolder holder = mBinding.recycler.findContainingViewHolder(focused);
        if (holder == null) return RecyclerView.NO_POSITION;
        return holder.getBindingAdapterPosition();
    }

    private int findNavigablePosition(int origin, int step) {
        for (int position = origin + step; position >= 0 && position < mAdapter.size(); position += step) {
            Object item = mAdapter.get(position);
            if (item instanceof DiscoverHero value && !value.isEmpty()) return position;
            if (item instanceof DiscoverFilterPanel) return position;
            if (item instanceof ListRow row && row.getAdapter() != null && row.getAdapter().size() > 0) return position;
        }
        return RecyclerView.NO_POSITION;
    }

    private boolean requestAdapterFocus(int position, int targetId) {
        if (position < 0 || position >= mAdapter.size()) return false;
        mBinding.recycler.setSelectedPosition(position, holder -> requestHolderFocus(holder.itemView, targetId));
        return true;
    }

    private void requestHolderFocus(View root, int targetId) {
        View target = targetId == View.NO_ID ? findFocusable(root) : root.findViewById(targetId);
        if (canRequestFocus(target) && target.requestFocus()) return;
        mBinding.recycler.post(() -> {
            View fallback = targetId == View.NO_ID ? findFocusable(root) : root.findViewById(targetId);
            if (canRequestFocus(fallback)) fallback.requestFocus();
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

    private void showContentIfReady() {
        if (featureFinished && queryFinished) mBinding.progressLayout.showContent(true, mAdapter.size());
        else mBinding.progressLayout.showContent();
    }

    private com.fongmi.android.tv.bean.Result newResult(List<Vod> items) {
        com.fongmi.android.tv.bean.Result result = new com.fongmi.android.tv.bean.Result();
        result.setList(items);
        return result;
    }

    private DiscoverQuery currentQuery(int targetPage) {
        return new DiscoverQuery(mediaType, genreId, region, dateStart, dateEnd, sort, targetPage);
    }

    private List<DiscoverFilterOption> mediaOptions() {
        return List.of(option(DiscoverMediaKey.MOVIE, getString(R.string.discover_media_movie), mediaType),
                option(DiscoverMediaKey.TV, getString(R.string.discover_media_tv), mediaType));
    }

    private List<DiscoverFilterOption> genreOptions() {
        List<DiscoverFilterOption> items = new ArrayList<>();
        items.add(option("", getString(R.string.discover_all), genreId));
        for (DiscoverFacet item : genres) items.add(option(item.getId(), item.getName(), genreId));
        return items;
    }

    private List<DiscoverFilterOption> regionOptions() {
        return List.of(option("", getString(R.string.discover_all), region), option("CN", getString(R.string.discover_region_cn), region),
                option("HK", getString(R.string.discover_region_hk), region), option("TW", getString(R.string.discover_region_tw), region),
                option("US", getString(R.string.discover_region_us), region), option("JP", getString(R.string.discover_region_jp), region),
                option("KR", getString(R.string.discover_region_kr), region), option("GB", getString(R.string.discover_region_gb), region),
                option("FR", getString(R.string.discover_region_fr), region));
    }

    private List<DiscoverFilterOption> yearOptions() {
        int current = LocalDate.now().getYear();
        List<DiscoverFilterOption> items = new ArrayList<>();
        items.add(yearOption(getString(R.string.discover_all), "", ""));
        for (int value = current; value >= current - 2; value--) items.add(yearOption(String.valueOf(value), value + "-01-01", value + "-12-31"));
        items.add(yearOption(getString(R.string.discover_year_recent, current - 7, current - 3), (current - 7) + "-01-01", (current - 3) + "-12-31"));
        int decade = current / 10 * 10;
        for (int start = decade; start >= 2000; start -= 10) items.add(yearOption(getString(R.string.discover_year_decade, start), start + "-01-01", (start + 9) + "-12-31"));
        items.add(yearOption(getString(R.string.discover_year_before_2000), "", "1999-12-31"));
        return items;
    }

    private List<DiscoverFilterOption> sortOptions() {
        return List.of(option(DiscoverQuery.SORT_POPULAR, getString(R.string.discover_sort_popular), sort),
                option(DiscoverQuery.SORT_RATING, getString(R.string.discover_sort_rating), sort),
                option(DiscoverQuery.SORT_LATEST, getString(R.string.discover_sort_latest), sort));
    }

    private DiscoverFilterOption option(String value, String label, String selected) {
        return new DiscoverFilterOption(value, label, value.equals(selected));
    }

    private DiscoverFilterOption yearOption(String label, String start, String end) {
        return new DiscoverFilterOption(label, label, start, end, start.equals(dateStart) && end.equals(dateEnd));
    }

    @Override
    public void onFilterClick(int row, DiscoverFilterOption option) {
        boolean changed;
        switch (row) {
            case FILTER_MEDIA -> {
                changed = !mediaType.equals(option.getValue());
                if (changed) {
                    mediaType = option.getValue();
                    genreId = "";
                    genres = List.of();
                    loadGenres(mediaType);
                }
            }
            case FILTER_GENRE -> { changed = !genreId.equals(option.getValue()); genreId = option.getValue(); }
            case FILTER_REGION -> { changed = !region.equals(option.getValue()); region = option.getValue(); }
            case FILTER_YEAR -> { changed = !dateStart.equals(option.getStartDate()) || !dateEnd.equals(option.getEndDate()); dateStart = option.getStartDate(); dateEnd = option.getEndDate(); }
            case FILTER_SORT -> { changed = !sort.equals(option.getValue()); sort = option.getValue(); }
            default -> changed = false;
        }
        if (!changed) return;
        updateFilterPanel();
        refreshResults(false);
    }

    @Override
    public void onItemClick(Vod item) {
        openItem(item, null);
    }

    @Override
    public void onItemClick(Vod item, View poster) {
        openItem(item, poster);
    }

    private void openItem(Vod item, View poster) {
        DiscoverMediaKey key = DiscoverMediaKey.parse(item.getId());
        if (key != null) {
            openTmdb(key, item, poster);
            return;
        }
        if (!item.getId().startsWith("douban:")) return;
        Notify.show(R.string.discover_loading_match);
        DiscoverApi.fetchDoubanDetail(item, requestTag, new DiscoverApi.DoubanDetailListener() {
            @Override
            public void onSuccess(DoubanDetail detail) {
                if (isInactive()) return;
                applyDoubanDetail(item, detail);
                DiscoverApi.matchDoubanToTmdb(detail, BuildConfig.TMDB_API_KEY, requestTag, new DiscoverApi.MatchListener() {
                    @Override
                    public void onMatch(DiscoverMediaKey key) {
                        if (!isInactive()) openTmdb(key, item, poster);
                    }

                    @Override
                    public void onNoMatch() {
                        showDiscoverDialog(detail);
                    }

                    @Override
                    public void onError(Exception e) {
                        showDiscoverDialog(detail);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                showDiscoverDialog(fallbackDetail(item));
            }
        });
    }

    private void showDiscoverDialog(DoubanDetail detail) {
        if (isInactive() || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                || getSupportFragmentManager().isStateSaved()) return;
        DiscoverDialog.create(detail).show(this);
    }

    private DoubanDetail fallbackDetail(Vod item) {
        String subject = item.getId().substring("douban:".length());
        return new DoubanDetail(subject, item.getTypeName(), item.getName(), item.getPic(), item.getRemarks(),
                item.getYear(), item.getTypeName(), item.getArea(), "", item.getDirector(), item.getActor(), item.getContent());
    }

    private void openTmdb(DiscoverMediaKey key, Vod item, View poster) {
        DiscoverDetailActivity.start(this, key, item.getName(), item.getPic(), item.getBackdrop(),
                item.getContent(), item.getYear(), item.getRemarks(), poster);
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }

    @Override
    public boolean onLoadMore(String ignored) {
        if (isInactive() || !queryFinished || page >= totalPages) return false;
        queryFinished = false;
        loadQuery(currentQuery(page + 1), queryGeneration);
        return true;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        DiscoverApi.cancel(requestTag);
        if (scroller != null) mBinding.recycler.removeOnScrollListener(scroller);
        super.onDestroy();
    }

    private boolean isInactive() {
        return destroyed || isFinishing() || isDestroyed();
    }
}
