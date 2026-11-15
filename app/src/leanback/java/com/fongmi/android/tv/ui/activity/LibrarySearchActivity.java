package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLibraryApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityLibrarySearchBinding;
import com.fongmi.android.tv.model.DiscoverSourceSearch;
import com.fongmi.android.tv.setting.PosterSourcePrioritySetting;
import com.fongmi.android.tv.source.LibrarySearchIndex;
import com.fongmi.android.tv.source.MediaIdentity;
import com.fongmi.android.tv.source.MediaMatcher;
import com.fongmi.android.tv.ui.adapter.LibrarySearchAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.LibraryPalette;
import com.fongmi.android.tv.ui.custom.LibraryViews;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TMDB owns the work list; matching runs off the UI thread and artwork is recycled. */
public final class LibrarySearchActivity extends BaseActivity {
    private final Object tag = new Object();
    private final DiscoverSourceSearch sourceSearch = new DiscoverSourceSearch();
    private LibrarySearchIndex index = new LibrarySearchIndex();
    private LibrarySearchIndex.Snapshot snapshot = new LibrarySearchIndex.Snapshot(List.of(), List.of(), 0);
    private final List<List<Vod>> pendingWorks = new ArrayList<>(), pendingSources = new ArrayList<>();
    private ExecutorService matcher;
    private final Runnable matchPending = this::matchPending;
    private final Runnable focusFirst = this::requestInitialFocus;
    private ActivityLibrarySearchBinding binding;
    private LibrarySearchAdapter adapter;
    private GridLayoutManager grid;
    private String keyword, type = "";
    private long person;
    private int page, totalPages = 1, generation, focusRequest;
    private boolean tmdbLoading, sourceLoading, tmdbError, sourceError, matching, matchScheduled, renderPending, initialFocus;

    public static void start(Activity activity, String query) {
        activity.startActivity(new Intent(activity, LibrarySearchActivity.class).putExtra("keyword", query));
    }
    public static void credits(Activity activity, long id, String name) {
        activity.startActivity(new Intent(activity, LibrarySearchActivity.class).putExtra("person", id).putExtra("name", name));
    }

    @Override protected ViewBinding getBinding() { return binding = ActivityLibrarySearchBinding.inflate(getLayoutInflater()); }
    @Override protected boolean customWall() { return false; }
    @Override protected void onThemeChanged() {
        // The shared native components refresh in place, preserving results and focus.
    }
    @Override protected void initView(Bundle saved) {
        LibraryViews.bindAppearance(binding.getRoot(), () -> {
            binding.getRoot().setBackgroundColor(LibraryPalette.surface());
            binding.title.setTextColor(LibraryPalette.primary());
            binding.status.setTextColor(LibraryPalette.secondary());
        });
        for (TextView text : new TextView[]{binding.title, binding.status, binding.filter, binding.filterMovies, binding.filterSeries, binding.sources, binding.more}) LibraryViews.applyTypeface(text);
        for (TextView control : new TextView[]{binding.filter, binding.filterMovies, binding.filterSeries, binding.sources, binding.more}) {
            LibraryViews.chip(control);
            LibraryViews.bindFocusMotion(control);
        }
        adapter = new LibrarySearchAdapter(cardWidth(), item -> LibraryNavigation.open(this, item, null));
        grid = new GridLayoutManager(this, columns());
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter.fullSpan(position) ? columns() : 1; }
        });
        grid.getSpanSizeLookup().setSpanIndexCacheEnabled(true);
        binding.results.setLayoutManager(grid);
        binding.results.setItemAnimator(null);
        binding.results.setAdapter(adapter);
        binding.results.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (position >= 0 && !adapter.fullSpan(position) && grid.getSpanSizeLookup().getSpanIndex(position, columns()) < columns() - 1) out.right = LibraryViews.dp(16);
            }
        });
        keyword = getIntent().getStringExtra("keyword");
        if (keyword == null) keyword = "";
        person = getIntent().getLongExtra("person", 0);
        if (saved != null) type = saved.getString("type", "");
        binding.title.setText(person > 0 ? getString(R.string.library_credit_works, getIntent().getStringExtra("name")) : getString(R.string.library_search_title, keyword));
        binding.filters.setVisibility(person > 0 ? View.GONE : View.VISIBLE);
        reset(true);
    }
    @Override protected void initEvent() {
        binding.more.setOnClickListener(view -> load(page + 1));
        binding.filter.setOnClickListener(view -> selectType(""));
        binding.filterMovies.setOnClickListener(view -> selectType("movie"));
        binding.filterSeries.setOnClickListener(view -> selectType("tv"));
        binding.sources.setOnClickListener(view -> focusResult(adapter.firstSourcePosition()));
        binding.results.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recycler, int dx, int dy) {
                if (dy > 0 && !tmdbLoading && page < totalPages && grid.findFirstVisibleItemPosition() < snapshot.works().size()
                        && grid.findLastVisibleItemPosition() >= snapshot.works().size() - columns()) load(page + 1);
            }
        });
    }

    private void selectType(String selected) {
        if (type.equals(selected)) return;
        type = selected;
        reset(false);
    }

    private void reset(boolean focusResults) {
        generation++;
        focusRequest++;
        binding.results.removeCallbacks(matchPending);
        binding.results.removeCallbacks(focusFirst);
        if (matcher != null) matcher.shutdownNow();
        matcher = Executors.newSingleThreadExecutor(runnable -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "library-match"));
        sourceSearch.stop(); TmdbLibraryApi.cancel(tag);
        // A running old session owns its index until cancellation completes; reset never waits for it.
        index = new LibrarySearchIndex();
        snapshot = new LibrarySearchIndex.Snapshot(List.of(), List.of(), 0);
        pendingWorks.clear(); pendingSources.clear(); adapter.submitList(List.of());
        page = 0; totalPages = 1;
        tmdbLoading = sourceLoading = matching = matchScheduled = renderPending = false; initialFocus = focusResults;
        sourceError = tmdbError = false;
        binding.filter.setSelected(type.isEmpty());
        binding.filterMovies.setSelected("movie".equals(type));
        binding.filterSeries.setSelected("tv".equals(type));
        binding.results.scrollToPosition(0);
        load(1);
        if (person > 0) return;
        sourceLoading = true;
        int ticket = generation;
        sourceSearch.start(PosterSourcePrioritySetting.orderSites(VodConfig.get().getSites()), List.of(keyword), PosterSourcePrioritySetting.getOrderedKeys(), new DiscoverSourceSearch.Listener() {
            @Override public void onResult(Result result, int returned, int total) {
                if (isInactive() || ticket != generation) return;
                if (result == null) sourceError = true;
                else pendingSources.add(result.getList());
                scheduleMatch();
            }
            @Override public void onComplete(boolean deadline) {
                if (isInactive() || ticket != generation) return;
                sourceLoading = false; sourceError |= deadline; scheduleMatch();
            }
        });
        updateStatus();
    }

    private void load(int targetPage) {
        if (tmdbLoading || isInactive()) return;
        tmdbLoading = true;
        updateStatus();
        int ticket = generation;
        TmdbLibraryApi.Listener<TmdbLibraryApi.Page> listener = new TmdbLibraryApi.Listener<>() {
            @Override public void onSuccess(TmdbLibraryApi.Page result) {
                if (isInactive() || ticket != generation) return;
                tmdbLoading = false; tmdbError = false; page = Math.max(targetPage, result.page()); totalPages = result.totalPages();
                pendingWorks.add(result.items()); scheduleMatch();
            }
            @Override public void onError(Exception error) {
                if (isInactive() || ticket != generation) return;
                tmdbLoading = false; tmdbError = true; scheduleMatch();
            }
        };
        if (person > 0) TmdbLibraryApi.credits(person, BuildConfig.TMDB_API_KEY, tag, listener);
        else TmdbLibraryApi.search(keyword, type, targetPage, BuildConfig.TMDB_API_KEY, tag, listener);
    }

    private void scheduleMatch() {
        renderPending = true;
        if (!matching && !matchScheduled) { matchScheduled = true; binding.results.postDelayed(matchPending, 48); }
        updateStatus();
    }

    private void matchPending() {
        matchScheduled = false;
        if (matching || isInactive()) return;
        matching = true;
        int ticket = generation;
        String selectedType = type;
        boolean pending = sourceLoading;
        LibrarySearchIndex current = index;
        List<List<Vod>> works = new ArrayList<>(pendingWorks), sources = new ArrayList<>(pendingSources);
        pendingWorks.clear(); pendingSources.clear();
        matcher.execute(() -> {
            try {
                for (List<Vod> items : works) current.addWorks(items);
                for (List<Vod> items : sources) current.addSources(items.stream().filter(item -> {
                    if (item == null) return false;
                    MediaIdentity.Kind kind = MediaMatcher.kind(item.getTypeName());
                    return selectedType.isEmpty() || kind == MediaIdentity.Kind.UNKNOWN
                            || kind == ("movie".equals(selectedType) ? MediaIdentity.Kind.MOVIE : MediaIdentity.Kind.SERIES);
                }).toList());
                if (Thread.currentThread().isInterrupted()) return;
                LibrarySearchIndex.Snapshot ready;
                try { ready = current.snapshot(); } catch (java.util.concurrent.CancellationException cancelled) { return; }
                List<LibrarySearchAdapter.Row> rows = adapter.rows(ready);
                binding.results.post(() -> {
                    if (isInactive() || ticket != generation) return;
                    snapshot = ready; matching = false;
                    boolean newer = !pendingWorks.isEmpty() || !pendingSources.isEmpty() || pending != sourceLoading;
                    renderPending = true;
                    adapter.submitRows(rows, binding.results, () -> {
                        if (isInactive() || ticket != generation) return;
                        renderPending = matching || matchScheduled || !pendingWorks.isEmpty() || !pendingSources.isEmpty() || pending != sourceLoading;
                        updateStatus();
                        requestInitialFocus();
                    });
                    updateStatus();
                    if (newer) scheduleMatch();
                });
            } catch (RuntimeException error) {
                if (Thread.currentThread().isInterrupted()) return;
                binding.results.post(() -> {
                    if (isInactive() || ticket != generation) return;
                    matching = renderPending = false; sourceError = true; updateStatus();
                    if (!pendingWorks.isEmpty() || !pendingSources.isEmpty()) scheduleMatch();
                });
            }
        });
    }

    private void requestInitialFocus() {
        if (!initialFocus || isInactive() || !hasWindowFocus() || adapter.getItemCount() == 0) return;
        int position = 0;
        while (position < adapter.getItemCount() && adapter.fullSpan(position)) position++;
        if (position == adapter.getItemCount()) return;
        RecyclerView.ViewHolder first = binding.results.findViewHolderForAdapterPosition(position);
        if (first != null && first.itemView.requestFocus()) initialFocus = false;
        else { binding.results.removeCallbacks(focusFirst); binding.results.postDelayed(focusFirst, 32); }
    }

    private View selectedFilter() {
        return "movie".equals(type) ? binding.filterMovies : "tv".equals(type) ? binding.filterSeries : binding.filter;
    }

    private boolean isToolbarControl(View view) {
        return view == binding.filter || view == binding.filterMovies || view == binding.filterSeries || view == binding.sources;
    }

    /** The toolbar and the grid are separate focus groups; async arrivals never undo a remote action. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && adapter != null) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                initialFocus = false;
                focusRequest++;
                binding.results.removeCallbacks(focusFirst);
            }
            View focused = getCurrentFocus();
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && isToolbarControl(focused) && adapter.getItemCount() > 0) {
                int position = Math.max(adapter.firstCardPosition(), grid.findFirstCompletelyVisibleItemPosition());
                focusResult(position);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP && focused != null && person == 0) {
                View card = binding.results.findContainingItemView(focused);
                int position = card == null ? RecyclerView.NO_POSITION : binding.results.getChildAdapterPosition(card);
                int first = adapter.firstCardPosition();
                if (position >= 0 && first >= 0 && grid.getSpanSizeLookup().getSpanGroupIndex(position, columns())
                        == grid.getSpanSizeLookup().getSpanGroupIndex(first, columns())) {
                    selectedFilter().requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void focusResult(int position) {
        initialFocus = false;
        if (position < 0) return;
        while (position < adapter.getItemCount() && adapter.fullSpan(position)) position++;
        String key = adapter.keyAt(position);
        if (key != null) focusResult(key, generation, ++focusRequest, 0);
    }

    private void focusResult(String key, int ticket, int request, int attempt) {
        if (ticket != generation || request != focusRequest || isInactive()) return;
        int position = adapter.positionOf(key);
        if (position < 0) return;
        RecyclerView.ViewHolder holder = binding.results.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView.requestFocus()) return;
        if (attempt == 0) grid.scrollToPositionWithOffset(position, LibraryViews.dp(8));
        if (attempt < 12) {
            binding.results.postOnAnimation(() -> focusResult(key, ticket, request, attempt + 1));
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && adapter != null) requestInitialFocus();
    }

    private int availableWidth() { return (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density) - 96; }
    private int columns() { return Math.max(2, Math.min(5, (availableWidth() + 16) / 160)); }
    private int cardWidth() { return Math.max(100, (availableWidth() - 16 * (columns() - 1)) / columns()); }

    private void updateStatus() {
        binding.more.setVisibility(page > 0 && page < totalPages ? View.VISIBLE : View.GONE);
        binding.more.setEnabled(!tmdbLoading);
        boolean hasSources = adapter.firstSourcePosition() >= 0;
        if (!hasSources && binding.sources.hasFocus()) selectedFilter().requestFocus();
        binding.sources.setVisibility(hasSources ? View.VISIBLE : View.GONE);
        int status = tmdbError ? R.string.library_tmdb_error : tmdbLoading || sourceLoading ? R.string.library_search_pending
                : snapshot.works().isEmpty() && snapshot.supplements().isEmpty() && !renderPending ? R.string.library_search_empty : 0;
        String text = status == 0 ? getString(R.string.library_search_result_count, snapshot.works().size() + snapshot.supplements().size()) : getString(status);
        if (!text.contentEquals(binding.status.getText())) binding.status.setText(text);
    }
    private boolean isInactive() { return isFinishing() || isDestroyed(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("type", type); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        generation++; binding.results.removeCallbacks(matchPending); binding.results.removeCallbacks(focusFirst);
        if (matcher != null) matcher.shutdownNow();
        sourceSearch.stop(); TmdbLibraryApi.cancel(tag); super.onDestroy();
    }
}
