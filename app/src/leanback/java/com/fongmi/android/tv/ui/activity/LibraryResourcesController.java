package com.fongmi.android.tv.ui.activity;

import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.model.DiscoverSourceSearch;
import com.fongmi.android.tv.setting.PosterSourcePrioritySetting;
import com.fongmi.android.tv.source.EpisodeTarget;
import com.fongmi.android.tv.source.LibraryIdentity;
import com.fongmi.android.tv.source.LibrarySourceMatcher;
import com.fongmi.android.tv.source.MediaMatcher;
import com.fongmi.android.tv.source.SmartSourceSelector;
import com.fongmi.android.tv.source.SourceReliabilityStore;
import com.fongmi.android.tv.utils.Task;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/** Bounded resource search followed by one cancellable, explicit media selection. */
final class LibraryResourcesController {
    interface Listener {
        void onResources(List<Vod> resources, boolean running, boolean partialError);
        void onSelected(Vod source, Flag flag, Episode episode);
        void onSelectionFailed(Vod source);
        default void onSourceDetail(Vod source) { }
        default void onPrepared(Vod source, Flag flag, Episode episode) { }
    }
    private final Listener listener;
    private final DiscoverSourceSearch search = new DiscoverSourceSearch();
    private final Map<String, Vod> sources = new LinkedHashMap<>();
    private final Map<String, Vod> hydrated = new LinkedHashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private String title = "", year = "", previousSite = "", previousId = "", originalKey = "";
    private List<String> aliases = List.of();
    private boolean movie, running, error, closed, selecting;
    private boolean preparing;
    private record Prepared(Vod source, Flag flag, Episode episode, EpisodeTarget target) { }
    private record Resolved(Vod source, SmartSourceSelector.FlagSelection selection) { }
    private Prepared prepared;
    private EpisodeTarget lastPreparedTarget;
    private long preparedAttemptAt;
    private String lastPreparedContext = "";
    private Integer season;
    private EpisodeTarget target;
    private History history;
    private int generation;
    private Future<?> pending;
    private Future<?> browsing;
    private long selectionDeadline;
    private final Runnable selectionTimeout = this::selectionExpired;

    LibraryResourcesController(Listener listener) { this.listener = listener; }

    void metadata(String title, List<String> aliases, String year, boolean movie, Integer season, History history, Vod original) {
        cancelSelection(); prepared = null;
        this.title = title; this.aliases = List.copyOf(aliases); this.year = year; this.movie = movie; this.season = season;
        this.history = history == null ? null : history.copy();
        previousSite = history == null ? "" : history.getSiteKey();
        previousId = history == null ? "" : history.getVodId();
        originalKey = original == null ? "" : LibraryIdentity.sourceKey(original);
        sources.entrySet().removeIf(entry -> !entry.getKey().equals(originalKey)
                && !LibrarySourceMatcher.match(entry.getValue(), title, aliases, year, movie, season).matches());
    }

    void seed(List<Vod> items) {
        if (items != null) for (Vod item : items) if (item != null && !item.getSiteKey().isEmpty()) {
            sources.put(LibraryIdentity.sourceKey(item), item);
            if (!item.getFlags().isEmpty()) hydrated.put(LibraryIdentity.sourceKey(item), item);
        }
    }

    void browse(Vod source) {
        if (source == null || closed) return;
        if (browsing != null) browsing.cancel(true);
        ListenableFuture<Vod> request = Task.submit(() -> hydrate(source), Constant.TIMEOUT_VOD, false);
        browsing = request;
        Futures.addCallback(request, Task.callback(value -> App.post(() -> {
            if (closed || browsing != request) return;
            browsing = null;
            hydrated.put(LibraryIdentity.sourceKey(source), value);
            sources.put(LibraryIdentity.sourceKey(source), value);
            listener.onSourceDetail(value);
            render();
        }), problem -> App.post(() -> {
            if (closed || browsing != request) return;
            browsing = null;
        })), MoreExecutors.directExecutor());
    }

    List<Vod> snapshot() { return List.copyOf(sources.values()); }
    boolean isRunning() { return running; }
    boolean isSelecting() { return selecting && !preparing; }
    boolean isPreparing() { return selecting && preparing; }

    void prepare(EpisodeTarget target) {
        if (closed || isSelecting() || running) return;
        if (isPreparing()) {
            if (sameTarget(this.target, target)) return;
            cancelSelection();
        }
        if (prepared != null && sameTarget(prepared.target(), target)) {
            listener.onPrepared(prepared.source(), prepared.flag(), prepared.episode());
            return;
        }
        String context = title + ":" + season + ":" + sources.size();
        if (context.equals(lastPreparedContext) && sameTarget(lastPreparedTarget, target) && SystemClock.elapsedRealtime() - preparedAttemptAt < 15_000) return;
        cancelSelection();
        lastPreparedContext = context;
        lastPreparedTarget = target; preparedAttemptAt = SystemClock.elapsedRealtime();
        this.target = target;
        preparing = selecting = true;
        attempted.clear();
        beginDeadline(false);
        attemptNext();
    }

    private static boolean sameTarget(EpisodeTarget first, EpisodeTarget second) {
        return first == null ? second == null : first.sameSelection(second);
    }

    void start() {
        if (preparing) cancelSelection();
        prepared = null;
        preparedAttemptAt = 0;
        search.stop();
        running = true; error = false;
        List<String> names = new ArrayList<>();
        if (!movie && season != null && season > 1) names.add(title + " 第" + season + "季");
        names.add(title);
        if (!aliases.isEmpty()) names.add(aliases.get(0));
        render();
        search.start(PosterSourcePrioritySetting.orderSites(VodConfig.get().getSites()), names, PosterSourcePrioritySetting.getOrderedKeys(), new DiscoverSourceSearch.Listener() {
            @Override public void onResult(Result result, int returned, int total) {
                if (closed) return;
                boolean changed = result == null && !error;
                if (result == null) error = true;
                else for (Vod source : result.getList()) {
                    if (LibrarySourceMatcher.match(source, title, aliases, year, movie, season).matches()
                            && sources.putIfAbsent(LibraryIdentity.sourceKey(source), source) == null) changed = true;
                }
                if (changed) render();
                if (selecting && pending == null) attemptNext();
            }
            @Override public void onComplete(boolean deadline) {
                if (closed) return;
                running = false; error |= deadline;
                render();
                if (selecting && pending == null) attemptNext();
            }
        });
    }

    void choose(EpisodeTarget target, Vod manual) {
        cancelSelection();
        this.target = target;
        if (prepared != null && sameTarget(prepared.target(), target)
                && (manual == null || LibraryIdentity.sourceKey(manual).equals(LibraryIdentity.sourceKey(prepared.source())))) {
            attempted.clear(); attempted.add(LibraryIdentity.sourceKey(prepared.source()));
            listener.onSelected(prepared.source(), prepared.flag(), prepared.episode());
            return;
        }
        attempted.clear();
        selecting = true;
        beginDeadline(manual != null);
        if (manual != null) attempt(manual, true);
        else attemptNext();
    }

    void retryAfterPlayback(Vod failed, EpisodeTarget current) {
        cancelSelection();
        selecting = true;
        beginDeadline(false);
        if (current != null) target = current;
        if (failed != null) attempted.add(LibraryIdentity.sourceKey(failed));
        attemptNext();
    }

    private void attemptNext() {
        if (closed || !selecting || pending != null) return;
        if (SystemClock.elapsedRealtime() >= selectionDeadline) { selectionFailed(null); return; }
        List<Vod> candidates = new ArrayList<>(sources.values());
        List<String> priorities = PosterSourcePrioritySetting.getOrderedKeys();
        Map<String, Integer> configuredOrder = new LinkedHashMap<>();
        for (int i = 0; i < VodConfig.get().getSites().size(); i++) configuredOrder.putIfAbsent(VodConfig.get().getSites().get(i).getKey(), i);
        SourceReliabilityStore reliability = new SourceReliabilityStore();
        candidates.removeIf(source -> attempted.contains(LibraryIdentity.sourceKey(source))
                || !LibrarySourceMatcher.match(source, title, aliases, year, movie, season).matches()
                && !LibraryIdentity.sourceKey(source).equals(originalKey)
                && !(source.getSiteKey().equals(previousSite) && source.getId().equals(previousId)));
        // Minimal search summaries often omit type/year. The hydrated detail must confirm them before playback.
        Vod best = candidates.stream().min(Comparator.comparingInt((Vod source) -> source.getSiteKey().equals(previousSite) && source.getId().equals(previousId) ? 0 : 1)
                .thenComparingInt(source -> priorities.indexOf(source.getSiteKey()) < 0 ? Integer.MAX_VALUE : priorities.indexOf(source.getSiteKey()))
                .thenComparing(Comparator.comparingInt((Vod source) -> reliability.score(source)).reversed())
                .thenComparingInt(source -> configuredOrder.getOrDefault(source.getSiteKey(), Integer.MAX_VALUE))
                .thenComparing(LibraryIdentity::sourceKey)).orElse(null);
        if (best != null) attempt(best, false);
        else if (!running) selectionFailed(null);
    }

    private void attempt(Vod candidate, boolean manual) {
        attempted.add(LibraryIdentity.sourceKey(candidate));
        int ticket = ++generation;
        Vod cached = hydrated.get(LibraryIdentity.sourceKey(candidate));
        String requestTitle = title, requestYear = year;
        List<String> requestAliases = aliases;
        boolean requestMovie = movie;
        Integer requestSeason = season;
        EpisodeTarget requestTarget = target;
        History requestHistory = history;
        boolean previous = requestHistory != null && candidate.getSiteKey().equals(previousSite) && candidate.getId().equals(previousId);
        boolean trusted = manual || candidate.getSiteKey().equals(previousSite) && candidate.getId().equals(previousId)
                || LibraryIdentity.sourceKey(candidate).equals(originalKey);
        long timeout = manual ? Constant.TIMEOUT_VOD : Math.max(1, Math.min(10_000, selectionDeadline - SystemClock.elapsedRealtime()));
        // Freeze the selection context before leaving the main thread. Cancellation/generation
        // checks below reject results from an older season or a superseded remote action.
        ListenableFuture<Resolved> request = Task.submit(() -> {
            Vod selected = cached == null ? hydrate(candidate) : cached;
            boolean valid = trusted || LibrarySourceMatcher.match(selected, requestTitle, requestAliases, requestYear, requestMovie, requestSeason).automatic();
            return new Resolved(selected, valid ? find(selected, requestMovie, requestTarget, previous, requestHistory, requestSeason) : null);
        }, timeout, manual);
        pending = request;
        Futures.addCallback(request, Task.callback(resolved -> App.post(() -> {
            if (closed || !selecting || ticket != generation) return;
            pending = null;
            Vod selected = resolved.source();
            hydrated.put(LibraryIdentity.sourceKey(candidate), selected);
            SmartSourceSelector.FlagSelection selection = resolved.selection();
            if (selection == null) {
                if (manual) selectionFailed(selected);
                else attemptNext();
            } else {
                boolean preview = preparing;
                finishSelection();
                prepared = new Prepared(selected, selection.flag(), selection.episode(), requestTarget);
                if (preview) listener.onPrepared(selected, selection.flag(), selection.episode());
                else listener.onSelected(selected, selection.flag(), selection.episode());
            }
        }), problem -> App.post(() -> {
            if (closed || ticket != generation) return;
            pending = null;
            if (manual) selectionFailed(candidate);
            else attemptNext();
        })), MoreExecutors.directExecutor());
    }

    private static Vod hydrate(Vod candidate) throws Exception {
        Result response = SiteApi.detailContent(candidate.getSiteKey(), candidate.getId());
        if (response.getList().isEmpty()) throw new IllegalArgumentException("Empty source detail");
        Vod item = response.getVod();
        item.setSite(VodConfig.get().getSite(candidate.getSiteKey()));
        item.setId(candidate.getId());
        item.checkName(candidate.getName());
        item.checkPic(candidate.getPic());
        return item;
    }

    private static SmartSourceSelector.FlagSelection find(Vod source, boolean movie, EpisodeTarget target,
                                                         boolean previous, History history, Integer season) {
        String flagName = previous ? history.getVodFlag() : "";
        if (movie || target == null) {
            return SmartSourceSelector.selectMovie(source, previous ? history.getEpisodeUrl() : "", flagName);
        }
        Integer sourceSeason = SmartSourceSelector.implicitEpisodeSeason(source);
        List<Flag> flags = new ArrayList<>(source.getFlags());
        flags.sort(Comparator.comparingInt(flag -> flag.getFlag().equals(flagName) ? 0 : 1));
        if (previous && java.util.Objects.equals(history.getMediaSeason(), season)
                && java.util.Objects.equals(history.getMediaEpisode(), target.getNumber())) {
            SmartSourceSelector.FlagSelection saved = SmartSourceSelector.selectExplicitEpisode(source, history.getEpisodeUrl(), flagName);
            if (saved != null && target.matches(saved.episode(), sourceSeason)) return saved;
        }
        for (Flag flag : flags) {
            Episode episode = SmartSourceSelector.findEpisode(flag, target, sourceSeason);
            if (episode != null) return new SmartSourceSelector.FlagSelection(flag, episode, 0, flag.getEpisodes().size());
        }
        return null;
    }

    private void render() { listener.onResources(snapshot(), running, error); }

    private void beginDeadline(boolean manual) {
        App.removeCallbacks(selectionTimeout);
        selectionDeadline = manual ? Long.MAX_VALUE : SystemClock.elapsedRealtime() + Constant.TIMEOUT_VOD;
        if (!manual) App.post(selectionTimeout, Constant.TIMEOUT_VOD);
    }

    private void selectionExpired() {
        if (!selecting || closed) return;
        boolean preview = preparing;
        cancelSelection();
        if (!preview) listener.onSelectionFailed(null);
    }

    private void selectionFailed(Vod source) {
        boolean preview = preparing;
        finishSelection();
        if (!preview) listener.onSelectionFailed(source);
    }

    private void finishSelection() { selecting = preparing = false; App.removeCallbacks(selectionTimeout); }

    void cancelSelection() {
        finishSelection();
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null;
    }

    void close() {
        closed = true; cancelSelection(); search.stop();
        if (browsing != null) browsing.cancel(true);
        browsing = null;
    }
}
