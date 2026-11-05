package com.fongmi.android.tv.ui.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverHero;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterHomeWallCategoriesBinding;
import com.fongmi.android.tv.databinding.AdapterHomeWallFooterBinding;
import com.fongmi.android.tv.databinding.AdapterHomeWallShelfBinding;
import com.fongmi.android.tv.databinding.AdapterHomeSourceBinding;
import com.fongmi.android.tv.ui.activity.DiscoverActivity;
import com.fongmi.android.tv.ui.activity.DiscoverDetailActivity;
import com.fongmi.android.tv.ui.activity.SettingActivity;
import com.fongmi.android.tv.bean.RexSection;
import com.fongmi.android.tv.ui.activity.RexSectionActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.presenter.RexPosterPresenter;
import com.fongmi.android.tv.ui.presenter.RexSectionCardPresenter;
import com.fongmi.android.tv.ui.rex.RexFeedLoader;
import com.fongmi.android.tv.ui.rex.RexFeedResult;
import com.fongmi.android.tv.ui.rex.RexHomeCatalog;
import com.fongmi.android.tv.ui.rex.RexSections;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.ListRow;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.JetStreamHomeNavView;
import com.fongmi.android.tv.ui.custom.TouchFocus;
import com.fongmi.android.tv.ui.presenter.DiscoverHeroPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Optional native discovery surface; the activity continues to own navigation and history. */
public final class PosterHomeController implements VodPresenter.OnClickListener {

    public interface Listener {
        void onHeroChanged(boolean visible);
    }

    private static final long DEADLINE_MS = 24_000;
    private final Activity activity;
    private final Listener listener;
    private final boolean compact;
    private final PosterKeepShelfController keepShelf;
    private final Object requestTag = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<DiscoverApi.Row, List<Vod>> content = new EnumMap<>(DiscoverApi.Row.class);
    private final List<Shelf> shelves = new ArrayList<>();
    private final Hero hero = new Hero();
    private final Categories categories = new Categories();
    private final Footer footer = new Footer();
    private final Source source = new Source();
    private final Map<String, RexFeedResult> compactFeeds = new LinkedHashMap<>();
    private VodPresenter.OnClickListener sourceListener;
    private Runnable openLibrary = () -> {};
    private final RexFeedLoader rexLoader;
    private ArrayObjectAdapter rexSpotlightAdapter;
    private ListRow rexSpotlightRow;
    private final List<Shelf> rexShelves = new ArrayList<>();
    private boolean shelfAny;
    private ArrayObjectAdapter page;
    private int category;
    private int generation;
    private int pending;
    private boolean closed;
    private Runnable deadline;

    public PosterHomeController(Activity activity, Listener listener) {
        this(activity, listener, false);
    }

    public PosterHomeController(Activity activity, Listener listener, boolean compact) {
        this.activity = activity;
        this.listener = listener;
        this.compact = compact;
        rexLoader = new RexFeedLoader(compact);
        keepShelf = new PosterKeepShelfController(activity);
        if (compact) {
            for (RexSection section : RexSections.home()) {
                if (section.isShelf()) continue;
                Shelf shelf = new Shelf(section.getLabelRes(), null);
                shelf.key = section.getKey();
                shelf.section = section;
                shelves.add(shelf);
            }
            return;
        }
        shelves.add(new Shelf(R.string.home_wall_trending, DiscoverApi.Row.TMDB_DAY));
        shelves.add(new Shelf(R.string.home_wall_movie_shelf, DiscoverApi.Row.DOUBAN_HOT_MOVIE));
        shelves.add(new Shelf(R.string.home_wall_tv_shelf, DiscoverApi.Row.DOUBAN_HOT_TV));
        shelves.add(new Shelf(R.string.home_wall_playing_shelf, DiscoverApi.Row.TMDB_NOW_PLAYING));
        shelves.add(new Shelf(R.string.home_wall_series_shelf, DiscoverApi.Row.TMDB_POPULAR_TV));
        shelves.add(new Shelf(R.string.home_wall_top_movies, DiscoverApi.Row.TMDB_TOP_MOVIE));
        shelves.add(new Shelf(R.string.home_wall_top_series, DiscoverApi.Row.TMDB_TOP_TV));
    }

    public void register(CustomSelector selector) {
        selector.addPresenter(Hero.class, new HeroPresenter());
        selector.addPresenter(Categories.class, new CategoriesPresenter());
        selector.addPresenter(Shelf.class, new ShelfPresenter());
        selector.addPresenter(Footer.class, new FooterPresenter());
        if (compact) selector.addPresenter(Source.class, new SourcePresenter());
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ITEM, 48), RexSectionCardPresenter.class);
        keepShelf.register(selector);
    }

    public void attach(ArrayObjectAdapter adapter) {
        attachRows(adapter);
        render();
        refresh();
    }

    /** Bind an already-loaded Rex feed snapshot without scheduling source requests. */
    public void attachSnapshot(ArrayObjectAdapter adapter, List<RexFeedResult> results) {
        attachRows(adapter);
        applyRexResults(results);
        render();
    }

    private void attachRows(ArrayObjectAdapter adapter) {
        page = adapter;
        page.add(0, hero);
        page.add(1, categories);
        keepShelf.attach(page, categories);
        page.addAll(page.size(), shelves);
        rexSpotlightAdapter = new ArrayObjectAdapter(new RexSectionCardPresenter(new RexSectionCardPresenter.Listener() {
            @Override
            public void onSectionClick(RexSection section, View card) {
                RexSectionActivity.start(activity, section);
            }

            @Override
            public boolean onSectionLongClick(RexSection section) {
                RexSectionActivity.start(activity, section);
                return true;
            }
        }, PosterHomeController.this));
        rexSpotlightRow = new ListRow(rexSpotlightAdapter);
        page.add(rexSpotlightRow);
        if (compact) page.add(source);
        page.add(footer);
        updateHistoryOrder();
    }

    /** Keep the history sentinels contiguous while categories follow any visible history. */
    public void updateHistoryOrder() {
        if (page == null) return;
        int history = page.indexOf(R.string.home_history);
        int recommendation = page.indexOf(R.string.home_recommend);
        boolean hasHistory = history >= 0 && recommendation == history + 2;
        int anchor = hasHistory ? recommendation : page.indexOf(hero);
        int index = page.indexOf(categories);
        if (anchor >= 0 && index >= 0) {
            int destination = anchor + 1 - (index < anchor ? 1 : 0);
            if (index != destination) page.move(index, destination);
        }
        keepShelf.updateOrder();
    }

    public boolean isHero(Object row) {
        return row == hero;
    }

    public boolean isFocusable(Object row) {
        if (row == hero) return !hero.value.isEmpty();
        if (row instanceof Shelf shelf) return !shelf.items.isEmpty();
        if (row == rexSpotlightRow) return rexSpotlightAdapter != null && rexSpotlightAdapter.size() > 0;
        if (row == source) return compact;
        if (keepShelf.isRow(row)) return keepShelf.isFocusable();
        return row == categories || row == footer;
    }

    public void setKeepShelfFocusFallback(Runnable fallback) {
        keepShelf.setFocusFallback(fallback);
    }

    public void resumeKeepShelf() {
        keepShelf.resume();
    }

    public void pauseKeepShelf() {
        keepShelf.pause();
    }

    public void refreshKeepShelf() {
        keepShelf.refresh();
    }

    public void refresh() {
        refreshKeepShelf();
        if (compact) {
            if (closed || page == null) return;
            footer.loading = true;
            notifyRow(footer);
            refreshRex();
            return;
        }
        if (closed || page == null || pending > 0) return;
        int request = ++generation;
        pending = shelves.size();
        footer.loading = true;
        notifyRow(footer);
        refreshRex();
        deadline = () -> {
            if (!isCurrent(request)) return;
            generation++;
            DiscoverApi.cancel(requestTag);
            pending = 0;
            footer.loading = false;
            render();
        };
        handler.postDelayed(deadline, DEADLINE_MS);
        for (Shelf shelf : shelves) DiscoverApi.fetch(shelf.row, BuildConfig.TMDB_API_KEY, requestTag, new DiscoverApi.Listener() {
            @Override
            public void onSuccess(DiscoverApi.Row row, List<Vod> items) {
                if (!isCurrent(request)) return;
                // Keep an already useful shelf if a retry returns an empty transient response.
                if (!items.isEmpty() || !content.containsKey(row)) content.put(row, items);
                complete();
            }

            @Override
            public void onError(DiscoverApi.Row row, Exception error) {
                if (isCurrent(request)) complete();
            }
        });
    }

    private boolean isCurrent(int request) {
        return !closed && generation == request && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void complete() {
        pending = Math.max(0, pending - 1);
        footer.loading = pending > 0;
        if (pending == 0 && deadline != null) handler.removeCallbacks(deadline);
        render();
    }

    private void selectCategory(int value) {
        if (category == value) return;
        category = value;
        notifyRow(categories);
        render();
    }

    private void render() {
        if (compact) {
            renderCompact();
            return;
        }
        List<Vod> heroItems = PosterHomeCatalog.hero(content, category);
        if (!RexFeedResult.sameItems(hero.value.getItems(), heroItems)) {
            hero.value.replace(heroItems);
            notifyRow(hero);
        }
        boolean any = false;
        for (Shelf shelf : shelves) {
            List<Vod> items = PosterHomeCatalog.visible(shelf.row, category)
                    ? PosterHomeCatalog.filter(content.get(shelf.row), category) : List.of();
            if (!RexFeedResult.sameItems(shelf.items, items)) {
                shelf.items = items;
                notifyRow(shelf);
            }
            any |= !shelf.items.isEmpty();
        }
        shelfAny = any;
        footer.empty = !any && !rexAny();
        notifyRow(footer);
        listener.onHeroChanged(!hero.value.isEmpty());
    }

    private void notifyRow(Object row) {
        if (page == null) return;
        int index = page.indexOf(row);
        if (index >= 0) page.notifyArrayItemRangeChanged(index, 1);
    }

    public void close() {
        keepShelf.close();
        rexLoader.close();
        closed = true;
        generation++;
        pending = 0;
        if (deadline != null) handler.removeCallbacks(deadline);
        DiscoverApi.cancel(requestTag);
    }

    @Override
    public void onItemClick(Vod item) {
        onItemClick(item, null);
    }

    @Override
    public void onItemClick(Vod item, View poster) {
        DiscoverDetailActivity.start(activity, item, poster);
    }

    @Override
    public boolean onLongClick(Vod item) {
        onItemClick(item);
        return true;
    }

    private void refreshRex() {
        rexLoader.load(RexSections.home(), results -> {
            if (closed || rexSpotlightAdapter == null) return;
            applyRexResults(results);
        });
    }

    private void applyRexResults(List<RexFeedResult> results) {
        List<RexFeedResult> feeds = results == null ? List.of() : results;
        if (compact) {
            compactFeeds.clear();
            for (RexFeedResult feed : feeds) compactFeeds.put(feed.getSection().getKey(), feed);
            footer.loading = rexLoader.isLoading();
            renderCompact();
            return;
        }
        rexSpotlightAdapter.setItems(RexHomeCatalog.spotlights(feeds), new BaseDiffCallback<RexFeedResult>());
        applyRexShelves(feeds);
        footer.empty = !shelfAny && !rexAny();
        notifyRow(footer);
    }

    /** shelf() 标记的栏目（「动漫」等新板块）以经典书架样式排在两行卡片之后、footer 之前。 */
    private void applyRexShelves(List<RexFeedResult> results) {
        if (page == null) return;
        if (page.indexOf(footer) < 0) return;
        List<Shelf> next = new ArrayList<>();
        List<Shelf> changed = new ArrayList<>();
        for (RexFeedResult result : results) {
            if (result.getSection().isShelf() && !result.getItems().isEmpty()) {
                Shelf shelf = null;
                for (Shelf previous : rexShelves) if (result.getSection().getKey().equals(previous.key)) shelf = previous;
                if (shelf == null) {
                    shelf = new Shelf(result.getSection().getLabelRes(), null);
                    shelf.key = result.getSection().getKey();
                }
                if (!RexFeedResult.sameItems(shelf.items, result.getItems())) {
                    shelf.items = result.getItems();
                    changed.add(shelf);
                }
                next.add(shelf);
            }
        }
        for (Shelf previous : rexShelves) if (!next.contains(previous)) page.remove(previous);
        int existing = 0;
        for (Shelf shelf : next) if (page.indexOf(shelf) >= 0) existing++;
        int start = page.indexOf(footer) - existing;
        for (int i = 0; i < next.size(); i++) {
            Shelf shelf = next.get(i);
            int index = page.indexOf(shelf);
            if (index < 0) page.add(start + i, shelf);
            else {
                if (index != start + i) page.move(index, start + i);
                if (changed.contains(shelf)) notifyRow(shelf);
            }
        }
        rexShelves.clear();
        rexShelves.addAll(next);
    }

    private boolean rexAny() {
        return (rexSpotlightAdapter != null && rexSpotlightAdapter.size() > 0)
                || !rexShelves.isEmpty();
    }

    private void renderCompact() {
        List<RexFeedResult> feeds = new ArrayList<>(compactFeeds.values());
        List<Vod> heroItems = PosterHomeCatalog.compactHero(feeds, category);
        if (!RexFeedResult.sameItems(hero.value.getItems(), heroItems)) {
            hero.value.replace(heroItems);
            notifyRow(hero);
        }
        List<String> keys = PosterHomeCatalog.compactKeys(category);
        for (Shelf shelf : shelves) {
            RexFeedResult feed = compactFeeds.get(shelf.key);
            List<Vod> items = keys.contains(shelf.key) && feed != null
                    ? PosterHomeCatalog.compactItems(feed.getItems(), category) : List.of();
            boolean changed = !RexFeedResult.sameItems(shelf.items, items);
            shelf.items = items;
            if (page == null) continue;
            int index = page.indexOf(shelf);
            if (items.isEmpty()) {
                if (index >= 0) page.remove(shelf);
            } else {
                if (index < 0) page.add(compactShelfEnd(), shelf);
                else if (changed) notifyRow(shelf);
            }
        }
        // A category is an ordered set of shelves, independent of response arrival order.
        int anchor = page == null ? -1 : compactShelfEnd();
        if (anchor >= 0) {
            int count = 0;
            for (Shelf shelf : shelves) if (page.indexOf(shelf) >= 0) count++;
            int position = anchor - count;
            for (String key : keys) for (Shelf shelf : shelves) {
                if (shelf.key.equals(key) && page.indexOf(shelf) >= 0) {
                    if (page.indexOf(shelf) != position) page.move(page.indexOf(shelf), position);
                    position++;
                }
            }
        }
        List<RexFeedResult> specialty = new ArrayList<>();
        if (category == PosterHomeCatalog.ALL || category == PosterHomeCatalog.TV) {
            for (RexSection section : RexSections.home()) {
                RexFeedResult feed = compactFeeds.get(section.getKey());
                if (!section.isShelf() || feed == null) continue;
                List<Vod> items = PosterHomeCatalog.compactItems(feed.getItems(), category);
                if (!items.isEmpty()) specialty.add(new RexFeedResult(section, items));
            }
        }
        if (rexSpotlightAdapter != null) {
            rexSpotlightAdapter.setItems(RexHomeCatalog.spotlights(specialty), new BaseDiffCallback<RexFeedResult>());
            if (page != null) {
                if (specialty.isEmpty()) page.remove(rexSpotlightRow);
                else if (page.indexOf(rexSpotlightRow) < 0) page.add(page.indexOf(source), rexSpotlightRow);
            }
        }
        footer.empty = heroItems.isEmpty() && shelves.stream().allMatch(shelf -> shelf.items.isEmpty()) && specialty.isEmpty();
        notifyRow(footer);
        listener.onHeroChanged(!hero.value.isEmpty());
    }

    private int compactShelfEnd() {
        int index = page.indexOf(rexSpotlightRow);
        return index >= 0 ? index : page.indexOf(source);
    }

    public void setSourceListener(VodPresenter.OnClickListener listener, Runnable openLibrary) {
        sourceListener = listener;
        this.openLibrary = openLibrary;
    }

    public void updateSource(String key, String name, Result result, boolean loading) {
        if (!compact) return;
        source.key = key;
        source.name = name;
        source.loading = loading;
        source.actions = PosterHomeCatalog.sourceActions(result.getList());
        source.items = PosterHomeCatalog.sourceVideos(result.getList());
        notifyRow(source);
    }

    private static void collapse(View view, boolean empty, int height) {
        view.setVisibility(empty ? View.GONE : View.VISIBLE);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.height != (empty ? 0 : height)) {
            params.height = empty ? 0 : height;
            view.setLayoutParams(params);
        }
    }

    private static final class Hero {
        final DiscoverHero value = new DiscoverHero();
    }

    private static final class Categories {
    }

    private static final class Footer {
        boolean loading;
        boolean empty = true;
    }

    private static final class Shelf {
        String key;
        RexSection section;
        final int title;
        final DiscoverApi.Row row;
        List<Vod> items = List.of();

        Shelf(int title, DiscoverApi.Row row) {
            this.title = title;
            this.row = row;
        }
    }

    private static final class Source {
        String key = "";
        String name = "";
        boolean loading;
        List<Vod> items = List.of();
        List<Vod> actions = List.of();
    }

    // Equal source responses may contain new object instances. Rebind the click closures as well
    // as visible fields so HomeActivity's current-request identity guard accepts retained cards.
    private static final BaseDiffCallback<Vod> SOURCE_DIFF = new BaseDiffCallback<>() {
        @Override public boolean areContentsTheSame(Vod oldItem, @NonNull Vod newItem) {
            return oldItem == newItem && super.areContentsTheSame(oldItem, newItem);
        }
    };

    private final class HeroPresenter extends Presenter {
        private final DiscoverHeroPresenter delegate = new DiscoverHeroPresenter(PosterHomeController.this);

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
            ViewHolder holder = delegate.onCreateViewHolder(parent);
            TouchFocus.bind(holder.view);
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, Object row) {
            delegate.onBindViewHolder(holder, hero.value);
            collapse(holder.view, hero.value.isEmpty(), ResUtil.dp2px(392));
        }

        @Override
        public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
            delegate.onViewAttachedToWindow(holder);
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
            delegate.onViewDetachedFromWindow(holder);
        }

        @Override
        public void onUnbindViewHolder(@NonNull ViewHolder holder) {
            delegate.onUnbindViewHolder(holder);
        }
    }

    private final class CategoriesPresenter extends Presenter {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
            AdapterHomeWallCategoriesBinding binding = AdapterHomeWallCategoriesBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            binding.categories.setTouchHandlingEnabled(true);
            List<JetStreamHomeNavView.NavItem> items = new ArrayList<>();
            int[] labels = {R.string.home_wall_all, R.string.home_wall_movies, R.string.home_wall_tv, R.string.home_wall_top};
            for (int i = 0; i < labels.length; i++) items.add(new JetStreamHomeNavView.NavItem(String.valueOf(i), activity.getString(labels[i]), 0));
            binding.categories.setItems(items);
            binding.filter.setVisibility(compact ? View.VISIBLE : View.GONE);
            if (compact) {
                TouchFocus.bind(binding.filter);
                binding.filter.setOnClickListener(view -> DiscoverActivity.startFilters(activity, category));
            }
            binding.categories.setListener(new JetStreamHomeNavView.Listener() {
                @Override
                public void onNavClick(String key) {
                    selectCategory(Integer.parseInt(key));
                }

                @Override
                public void onNavLongClick(String key) {
                    selectCategory(Integer.parseInt(key));
                }
            });
            return new ViewHolder(binding.getRoot());
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, Object row) {
            AdapterHomeWallCategoriesBinding.bind(holder.view).categories.setSelectedKey(String.valueOf(category));
        }

        @Override
        public void onUnbindViewHolder(@NonNull ViewHolder holder) {
        }
    }

    private final class ShelfPresenter extends Presenter {
        @NonNull
        @Override
        @SuppressLint("RestrictedApi")
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
            AdapterHomeWallShelfBinding binding = AdapterHomeWallShelfBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            VodPresenter presenter = new VodPresenter(PosterHomeController.this, Style.rect(), new int[]{ResUtil.dp2px(132), ResUtil.dp2px(176)}) {
                @NonNull
                @Override
                public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
                    ViewHolder holder = super.onCreateViewHolder(parent);
                    TouchFocus.bind(holder.view);
                    return holder;
                }
            };
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
            // Empty adapters cannot supply a wrap_content cross-axis measurement to Leanback.
            // Reserve 176 poster + 8 gap + 26 title + 20 metadata; the XML adds 12dp padding per side.
            binding.posters.setRowHeight(ResUtil.dp2px(230));
            binding.posters.setAdapter(new ItemBridgeAdapter(adapter));
            binding.posters.setHorizontalSpacing(ResUtil.dp2px(20));
            binding.posters.setItemAnimator(null);
            binding.posters.setFocusScrollStrategy(HorizontalGridView.FOCUS_SCROLL_ITEM);
            return new ShelfHolder(binding, adapter);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, Object row) {
            ShelfHolder shelfHolder = (ShelfHolder) holder;
            Shelf shelf = (Shelf) row;
            shelfHolder.binding.title.setText(shelf.title);
            shelfHolder.binding.more.setVisibility(compact ? View.VISIBLE : View.GONE);
            shelfHolder.binding.more.setOnClickListener(compact
                    ? view -> RexSectionActivity.start(activity, shelf.section) : null);
            if (compact) TouchFocus.bind(shelfHolder.binding.more);
            shelfHolder.adapter.setItems(shelf.items, new BaseDiffCallback<Vod>());
            collapse(holder.view, shelf.items.isEmpty(), ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        @Override
        public void onUnbindViewHolder(@NonNull ViewHolder holder) {
            ((ShelfHolder) holder).adapter.clear();
        }
    }

    private final class SourcePresenter extends Presenter {
        private final VodPresenter.OnClickListener listener = new VodPresenter.OnClickListener() {
            @Override public void onItemClick(Vod item) { onItemClick(item, null); }
            @Override public void onItemClick(Vod item, View poster) {
                if (sourceListener != null && source.items.contains(item)) sourceListener.onItemClick(item, poster);
            }
            @Override public boolean onLongClick(Vod item) {
                return sourceListener != null && source.items.contains(item) && sourceListener.onLongClick(item);
            }
        };

        @NonNull
        @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
            AdapterHomeSourceBinding binding = AdapterHomeSourceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(new VodPresenter(listener, Style.rect(), new int[]{ResUtil.dp2px(132), ResUtil.dp2px(176)}) {
                @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
                    ViewHolder holder = super.onCreateViewHolder(parent);
                    TouchFocus.bind(holder.view);
                    return holder;
                }
            });
            binding.posters.setAdapter(new ItemBridgeAdapter(adapter));
            binding.posters.setRowHeight(ResUtil.dp2px(230));
            binding.posters.setHorizontalSpacing(ResUtil.dp2px(20));
            binding.posters.setItemAnimator(null);
            binding.posters.setFocusScrollStrategy(HorizontalGridView.FOCUS_SCROLL_ITEM);
            TouchFocus.bind(binding.library);
            binding.library.setOnClickListener(view -> openLibrary.run());
            return new SourceHolder(binding, adapter);
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object row) {
            SourceHolder holder = (SourceHolder) viewHolder;
            AdapterHomeSourceBinding binding = holder.binding;
            boolean removedFocus = binding.actions.hasFocus() && source.actions.isEmpty()
                    || binding.posters.hasFocus() && source.items.isEmpty();
            boolean changedSource = !holder.key.equals(source.key);
            holder.key = source.key;
            binding.title.setText(source.name.isEmpty() ? activity.getString(R.string.home_compact_source)
                    : activity.getString(R.string.home_source_recommendations, source.name));
            binding.status.setVisibility(source.loading || source.items.isEmpty() ? View.VISIBLE : View.GONE);
            binding.status.setText(source.key.isEmpty() ? R.string.home_compact_no_source
                    : source.loading ? R.string.home_compact_source_loading : R.string.home_compact_source_empty);
            binding.library.setText(source.key.isEmpty() ? R.string.home_choose_source : R.string.home_tab_library);
            binding.actions.setVisibility(source.actions.isEmpty() ? View.GONE : View.VISIBLE);
            int focusedAction = binding.actions.getFocusedPosition();
            binding.actions.setItems(source.actions.stream().map(item -> item.getName().isEmpty()
                    ? activity.getString(R.string.home_compact_source_action) : item.getName()).toList());
            if (!changedSource && focusedAction >= 0) binding.actions.setFocusedPosition(focusedAction);
            List<Vod> actions = List.copyOf(source.actions);
            String boundKey = source.key;
            binding.actions.setOnChipClickListener((com.fongmi.android.tv.ui.custom.JetStreamChipRow.OnChipClickListener) position -> {
                if (boundKey.equals(source.key) && sourceListener != null && position >= 0 && position < actions.size()) {
                    sourceListener.onItemClick(actions.get(position));
                }
            });
            holder.adapter.setItems(source.items, SOURCE_DIFF);
            binding.posters.setVisibility(source.items.isEmpty() ? View.GONE : View.VISIBLE);
            if (changedSource) binding.posters.setSelectedPosition(0);
            if (removedFocus) binding.library.requestFocus();
        }

        @Override public void onUnbindViewHolder(@NonNull ViewHolder viewHolder) {
            SourceHolder holder = (SourceHolder) viewHolder;
            holder.binding.actions.clearListeners();
            holder.adapter.clear();
        }
    }

    private static final class SourceHolder extends Presenter.ViewHolder {
        final AdapterHomeSourceBinding binding;
        final ArrayObjectAdapter adapter;
        String key = "";

        SourceHolder(AdapterHomeSourceBinding binding, ArrayObjectAdapter adapter) {
            super(binding.getRoot());
            this.binding = binding;
            this.adapter = adapter;
        }
    }

    private static final class ShelfHolder extends Presenter.ViewHolder {
        final AdapterHomeWallShelfBinding binding;
        final ArrayObjectAdapter adapter;

        ShelfHolder(AdapterHomeWallShelfBinding binding, ArrayObjectAdapter adapter) {
            super(binding.getRoot());
            this.binding = binding;
            this.adapter = adapter;
        }
    }

    private final class FooterPresenter extends Presenter {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
            AdapterHomeWallFooterBinding binding = AdapterHomeWallFooterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            TouchFocus.bind(binding.retry, binding.more, binding.settings);
            binding.retry.setOnClickListener(view -> refresh());
            binding.more.setOnClickListener(view -> {
                if (compact) DiscoverActivity.startFilters(activity, category);
                else DiscoverActivity.start(activity);
            });
            binding.settings.setOnClickListener(view -> SettingActivity.start(activity));
            return new ViewHolder(binding.getRoot());
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, Object row) {
            AdapterHomeWallFooterBinding binding = AdapterHomeWallFooterBinding.bind(holder.view);
            binding.status.setText(footer.loading ? R.string.home_wall_loading : footer.empty ? R.string.home_wall_empty : R.string.home_wall_more);
            binding.hint.setText(footer.empty ? R.string.home_wall_empty_hint : R.string.home_wall_ready);
            binding.retry.setText(footer.loading ? R.string.home_wall_loading : R.string.home_wall_retry);
            if (compact) {
                binding.more.setText(R.string.home_compact_filter);
                binding.hint.setText(footer.empty ? R.string.home_compact_empty_hint : R.string.home_compact_ready);
            }
        }

        @Override
        public void onUnbindViewHolder(@NonNull ViewHolder holder) {
        }
    }
}
