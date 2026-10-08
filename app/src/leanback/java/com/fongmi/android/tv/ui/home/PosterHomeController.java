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
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterHomeWallCategoriesBinding;
import com.fongmi.android.tv.databinding.AdapterHomeWallFooterBinding;
import com.fongmi.android.tv.databinding.AdapterHomeWallShelfBinding;
import com.fongmi.android.tv.ui.activity.DiscoverActivity;
import com.fongmi.android.tv.ui.activity.DiscoverDetailActivity;
import com.fongmi.android.tv.ui.activity.SettingActivity;
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

/** Optional native discovery surface; the activity continues to own navigation and history. */
public final class PosterHomeController implements VodPresenter.OnClickListener {

    public interface Listener {
        void onHeroChanged(boolean visible);
    }

    private static final long DEADLINE_MS = 24_000;
    private final Activity activity;
    private final Listener listener;
    private final PosterKeepShelfController keepShelf;
    private final Object requestTag = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<DiscoverApi.Row, List<Vod>> content = new EnumMap<>(DiscoverApi.Row.class);
    private final List<Shelf> shelves = new ArrayList<>();
    private final Hero hero = new Hero();
    private final Categories categories = new Categories();
    private final Footer footer = new Footer();
    private ArrayObjectAdapter page;
    private int category;
    private int generation;
    private int pending;
    private boolean closed;
    private Runnable deadline;

    public PosterHomeController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        keepShelf = new PosterKeepShelfController(activity);
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
        keepShelf.register(selector);
    }

    public void attach(ArrayObjectAdapter adapter) {
        page = adapter;
        page.add(0, hero);
        page.add(1, categories);
        keepShelf.attach(page);
        page.addAll(page.size(), shelves);
        page.add(footer);
        render();
        refresh();
    }

    public boolean isHero(Object row) {
        return row == hero;
    }

    public boolean isFocusable(Object row) {
        if (row == hero) return !hero.value.isEmpty();
        if (row instanceof Shelf shelf) return !shelf.items.isEmpty();
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
        if (closed || page == null || pending > 0) return;
        int request = ++generation;
        pending = shelves.size();
        footer.loading = true;
        notifyRow(footer);
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
        hero.value.replace(PosterHomeCatalog.hero(content, category));
        notifyRow(hero);
        boolean any = false;
        for (Shelf shelf : shelves) {
            shelf.items = PosterHomeCatalog.visible(shelf.row, category)
                    ? PosterHomeCatalog.filter(content.get(shelf.row), category) : List.of();
            any |= !shelf.items.isEmpty();
            notifyRow(shelf);
        }
        footer.empty = !any;
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
        final int title;
        final DiscoverApi.Row row;
        List<Vod> items = List.of();

        Shelf(int title, DiscoverApi.Row row) {
            this.title = title;
            this.row = row;
        }
    }

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
            shelfHolder.adapter.setItems(shelf.items, new BaseDiffCallback<Vod>());
            collapse(holder.view, shelf.items.isEmpty(), ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        @Override
        public void onUnbindViewHolder(@NonNull ViewHolder holder) {
            ((ShelfHolder) holder).adapter.clear();
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
            binding.more.setOnClickListener(view -> DiscoverActivity.start(activity));
            binding.settings.setOnClickListener(view -> SettingActivity.start(activity));
            return new ViewHolder(binding.getRoot());
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, Object row) {
            AdapterHomeWallFooterBinding binding = AdapterHomeWallFooterBinding.bind(holder.view);
            binding.status.setText(footer.loading ? R.string.home_wall_loading : footer.empty ? R.string.home_wall_empty : R.string.home_wall_more);
            binding.hint.setText(footer.empty ? R.string.home_wall_empty_hint : R.string.home_wall_ready);
            binding.retry.setText(footer.loading ? R.string.home_wall_loading : R.string.home_wall_retry);
        }

        @Override
        public void onUnbindViewHolder(@NonNull ViewHolder holder) {
        }
    }
}
