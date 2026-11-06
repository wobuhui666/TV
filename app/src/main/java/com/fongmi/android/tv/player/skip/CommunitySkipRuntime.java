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
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One runtime per playback controller; all session state is confined to the UI thread. */
public final class CommunitySkipRuntime {

    // Metadata lookups must not occupy the source/parsing executor or create a second player.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), runnable -> new Thread(runnable, "community-skip"));

    private Session active;

    public void start(Vod media, History history, Episode episode) {
        stop();
        if (!Setting.isCommunitySkipEnabled() || media == null || history == null || episode == null) return;
        if (episode.isGeneratedLabel() && !EpisodeIdentityResolver.isMovie(media.getId(), media.getTypeName())) return;
        Session session = new Session(history);
        active = session;
        String id = media.getId();
        String title = media.getName();
        String year = media.getYear();
        String type = media.getTypeName();
        String episodeName = episode.getName();
        boolean reliableEpisodeLabel = !episode.isGeneratedLabel();
        submit(session, () -> {
            EpisodeIdentity identity;
            try {
                identity = session.resolver.resolve(id, title, year, type, episodeName, reliableEpisodeLabel);
            } catch (IOException ignored) {
                return;
            }
            App.post(() -> {
                if (!isActive(session)) return;
                session.identity = identity;
                fetchWhenReady(session);
            });
        });
    }

    public void stop() {
        Session previous = active;
        active = null;
        if (previous == null) return;
        previous.resolver.cancel();
        previous.api.cancel();
        if (previous.task != null) previous.task.cancel(true);
        WORKER.purge();
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
        fetchWhenReady(session);
        return playing && session.policy != null ? session.policy.target(positionMs, durationMs, history) : C.TIME_UNSET;
    }

    private boolean isActive(Session session) {
        return active == session && session.snapshot.matches(session.history) && Setting.isCommunitySkipEnabled();
    }

    private void fetchWhenReady(Session session) {
        if (session.identity == null || session.durationMs <= 0 || session.fetchStarted) return;
        session.fetchStarted = true;
        long duration = session.durationMs;
        submit(session, () -> {
            List<SkipSegment> segments = session.api.fetch(session.identity, duration);
            App.post(() -> {
                if (isActive(session)) session.policy = new SegmentSkipPolicy(segments, duration);
            });
        });
    }

    private void submit(Session session, Runnable task) {
        try {
            session.task = WORKER.submit(() -> {
                try {
                    task.run();
                } catch (Exception ignored) {
                    // Missing metadata, an unavailable service or a cancelled lookup leaves playback alone.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A burst of cancelled navigation must never delay playback to retry this optional lookup.
        }
    }

    private static final class Session {
        final History history;
        final PlaybackEpisodeSnapshot snapshot;
        final EpisodeIdentityResolver resolver = new EpisodeIdentityResolver(BuildConfig.TMDB_API_KEY);
        final CommunitySkipApi api = new CommunitySkipApi();
        EpisodeIdentity identity;
        Future<?> task;
        long durationMs;
        boolean fetchStarted;
        SegmentSkipPolicy policy;

        Session(History history) {
            this.history = history;
            this.snapshot = PlaybackEpisodeSnapshot.from(history);
        }
    }
}
