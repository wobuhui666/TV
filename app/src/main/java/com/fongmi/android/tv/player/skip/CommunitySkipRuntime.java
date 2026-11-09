package com.fongmi.android.tv.player.skip;

import androidx.media3.common.C;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One runtime per playback controller; all session state is confined to the UI thread. */
public final class CommunitySkipRuntime {

    // Metadata lookups must not occupy the source/parsing executor or create a second player.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), runnable -> new Thread(runnable, "community-skip"));
    private static final long ADMISSION_RETRY_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long IDENTITY_RETRY_NS = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_REJECTIONS = 3;
    private static final int MAX_IDENTITY_ATTEMPTS = 2;
    private static final int MAX_DURATION_LOOKUPS = 2;
    private static final long MAX_DURATION_DRIFT_MS = 2_000;

    private final ExecutorService worker;
    private final Supplier<EpisodeIdentityResolver> resolverFactory;
    private final Supplier<CommunitySkipApi> apiFactory;
    private Session active;

    public CommunitySkipRuntime() {
        this(WORKER, () -> new EpisodeIdentityResolver(BuildConfig.TMDB_API_KEY), CommunitySkipApi::new);
    }

    CommunitySkipRuntime(ExecutorService worker, Supplier<EpisodeIdentityResolver> resolverFactory,
                         Supplier<CommunitySkipApi> apiFactory) {
        this.worker = worker;
        this.resolverFactory = resolverFactory;
        this.apiFactory = apiFactory;
    }

    /** Replay creates a new consumption policy; normal preparation reuses the same episode lookup. */
    public void start(Vod media, History history, Episode episode) {
        stop();
        prepare(media, history, episode);
    }

    /** Start identity work while the source resolves its URL, without holding up playback. */
    public void prepare(Vod media, History history, Episode episode) {
        if (!Setting.isCommunitySkipEnabled() || media == null || history == null || episode == null
                || episode.isGeneratedLabel() && !EpisodeIdentityResolver.isMovie(media.getId(), media.getTypeName())) {
            stop();
            return;
        }
        RequestMetadata request = new RequestMetadata(media.getId(), media.getName(), media.getYear(),
                media.getTypeName(), episode.getName(), !episode.isGeneratedLabel());
        if (active != null && isActive(active) && active.history == history && request.equals(active.request)) {
            resolveWhenReady(active);
            return;
        }
        stop();
        Session session = new Session(history, request, resolverFactory.get(), apiFactory.get());
        active = session;
        resolveWhenReady(session);
    }

    private void resolveWhenReady(Session session) {
        if (!isActive(session) || session.identityDone || session.identityStarted
                || System.nanoTime() < session.identityRetryNs || session.identityRejections >= MAX_REJECTIONS) return;
        RequestMetadata request = session.request;
        session.identityStarted = true;
        boolean accepted = submit(session, () -> {
            EpisodeIdentity identity;
            try {
                identity = session.resolver.resolve(request.id(), request.title(), request.year(), request.type(),
                        request.episode(), request.reliableEpisode());
            } catch (IOException | RuntimeException ignored) {
                App.post(() -> {
                    if (!isActive(session)) return;
                    session.identityStarted = false;
                    session.identityDone = ++session.identityFailures >= MAX_IDENTITY_ATTEMPTS;
                    session.identityRetryNs = System.nanoTime() + IDENTITY_RETRY_NS;
                });
                return;
            }
            App.post(() -> {
                if (!isActive(session)) return;
                session.identityStarted = false;
                session.identityDone = true;
                session.identity = identity;
                fetchWhenReady(session);
            });
        });
        if (!accepted) {
            session.identityStarted = false;
            session.identityRejections++;
            session.identityRetryNs = System.nanoTime() + ADMISSION_RETRY_NS;
        }
    }

    public void stop() {
        Session previous = active;
        active = null;
        if (previous == null) return;
        previous.resolver.cancel();
        previous.api.cancel();
        if (previous.task != null) previous.task.cancel(true);
        if (worker instanceof ThreadPoolExecutor executor) executor.purge();
    }

    public long target(long positionMs, long durationMs, History history, boolean playing) {
        Session session = active;
        if (session == null) return C.TIME_UNSET;
        if (!isActive(session) || session.history != history) {
            stop();
            return C.TIME_UNSET;
        }
        if (durationMs <= 0 || durationMs > TimeUnit.DAYS.toMillis(1)) return C.TIME_UNSET;
        session.durationMs = durationMs;
        resolveWhenReady(session);
        if (session.fetchStarted && Math.abs(session.fetchDurationMs - durationMs) > MAX_DURATION_DRIFT_MS) {
            session.api.cancel();
            if (session.task != null) session.task.cancel(true);
            session.fetchGeneration++;
            if (session.policy != null) session.policy.updateSegments(List.of(), durationMs);
            session.fetchStarted = false;
            session.api = apiFactory.get();
        }
        fetchWhenReady(session);
        return playing && session.policy != null ? session.policy.target(positionMs, durationMs, history) : C.TIME_UNSET;
    }

    private boolean isActive(Session session) {
        return active == session && session.snapshot.matches(session.history) && Setting.isCommunitySkipEnabled();
    }

    private void fetchWhenReady(Session session) {
        if (!isActive(session) || session.identity == null || session.durationMs <= 0 || session.fetchStarted
                || session.fetchLookups >= MAX_DURATION_LOOKUPS || session.fetchRejections >= MAX_REJECTIONS
                || System.nanoTime() < session.fetchRetryNs) return;
        session.fetchStarted = true;
        session.fetchDurationMs = session.durationMs;
        long duration = session.durationMs;
        CommunitySkipApi api = session.api;
        int generation = ++session.fetchGeneration;
        boolean accepted = submit(session, () -> {
            List<SkipSegment> segments = api.fetch(session.identity, duration,
                    partial -> publishSegments(session, api, generation, duration, partial));
            publishSegments(session, api, generation, duration, segments);
        });
        if (accepted) session.fetchLookups++;
        else {
            session.fetchStarted = false;
            session.fetchRejections++;
            session.fetchRetryNs = System.nanoTime() + ADMISSION_RETRY_NS;
        }
    }

    private void publishSegments(Session session, CommunitySkipApi api, int generation,
                                 long duration, List<SkipSegment> segments) {
        App.post(() -> {
            if (!isActive(session) || session.api != api || session.fetchGeneration != generation
                    || Math.abs(session.durationMs - duration) > MAX_DURATION_DRIFT_MS) return;
            if (session.policy == null) session.policy = new SegmentSkipPolicy(segments, duration);
            else session.policy.updateSegments(segments, duration);
        });
    }

    private boolean submit(Session session, Runnable task) {
        try {
            session.task = worker.submit(() -> {
                try {
                    task.run();
                } catch (Exception ignored) {
                    // Missing metadata, an unavailable service or a cancelled lookup leaves playback alone.
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            // Retry from a later progress tick, never by waiting on playback's UI thread.
            return false;
        }
    }

    private record RequestMetadata(String id, String title, String year, String type,
                                   String episode, boolean reliableEpisode) { }

    private static final class Session {
        final History history;
        final PlaybackEpisodeSnapshot snapshot;
        final RequestMetadata request;
        final EpisodeIdentityResolver resolver;
        CommunitySkipApi api;
        EpisodeIdentity identity;
        Future<?> task;
        long durationMs;
        boolean identityStarted;
        boolean identityDone;
        int identityFailures;
        int identityRejections;
        long identityRetryNs;
        boolean fetchStarted;
        long fetchDurationMs;
        int fetchGeneration;
        int fetchLookups;
        int fetchRejections;
        long fetchRetryNs;
        SegmentSkipPolicy policy;

        Session(History history) {
            this(history, null, new EpisodeIdentityResolver(BuildConfig.TMDB_API_KEY), new CommunitySkipApi());
            identityDone = true;
        }

        Session(History history, RequestMetadata request, EpisodeIdentityResolver resolver, CommunitySkipApi api) {
            this.history = history;
            this.snapshot = PlaybackEpisodeSnapshot.from(history);
            this.request = request;
            this.resolver = resolver;
            this.api = api;
        }
    }
}
