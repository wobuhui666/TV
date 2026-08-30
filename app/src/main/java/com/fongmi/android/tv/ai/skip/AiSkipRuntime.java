package com.fongmi.android.tv.ai.skip;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.MpvLogCollector;
import com.fongmi.android.tv.utils.Task;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class AiSkipRuntime {
    private static final String AI_SOURCE = "ai";
    private static final String AI_SOURCE_PREFIX = AI_SOURCE + ":";
    private static final int SAMPLE_RATE = 8_000;
    private static final long POLL_LIMIT_MS = TimeUnit.MINUTES.toMillis(3);
    private static final Executor BOUNDARY_EXECUTOR = MoreExecutors.newSequentialExecutor(Task.executor());
    private static volatile AiSkipRuntime instance;

    private final Object lock = new Object();
    private CaptureSession activeSession;

    private AiSkipRuntime() {
    }

    public static AiSkipRuntime get() {
        if (instance == null) synchronized (AiSkipRuntime.class) {
            if (instance == null) instance = new AiSkipRuntime();
        }
        return instance;
    }

    public long startSession(@Nullable String mediaKey, @Nullable History history, @Nullable String episode,
                             long startPositionMs, @Nullable Runnable appliedCallback) {
        stopSession();
        if (history == null || mediaKey == null || mediaKey.isEmpty()) return startPositionMs;
        BoundarySnapshot previousBoundaries = BoundarySnapshot.from(history);
        boolean stale = hasStaleAiBoundaries(history, mediaKey);
        long effectivePositionMs = prepareStartPosition(mediaKey, history, startPositionMs);
        if (stale) saveAsync(history, previousBoundaries, BoundarySnapshot.from(history));
        if (!AiSkipSettings.isConfigured()) return effectivePositionMs;
        CaptureSession session = new CaptureSession(mediaKey, history, episode, appliedCallback);
        synchronized (lock) {
            activeSession = session;
        }
        Task.execute(() -> loadCachedResult(session));
        return effectivePositionMs;
    }

    public void stopSession() {
        synchronized (lock) {
            if (activeSession != null) {
                activeSession.appliedCallback = null;
                activeSession.cancelProbe();
            }
            activeSession = null;
        }
    }

    public void onMediaResolved(@Nullable PlaySpec spec, int playbackEngine) {
        if (spec == null || !AiSkipSettings.isConfigured()) return;
        MediaItem item;
        try {
            item = MediaItemFactory.from(spec);
        } catch (Exception e) {
            MpvLogCollector.logError("AiSkip", "无法创建音频探针媒体项: " + e.getClass().getSimpleName());
            return;
        }
        CaptureSession session;
        Runnable callback;
        synchronized (lock) {
            session = activeSession;
            if (session == null || !session.matchesHistory()) return;
            session.pendingMediaItem = item;
            session.playbackEngine = playbackEngine;
            session.mediaResolved = true;
            callback = session.resultApplied ? session.appliedCallback : null;
        }
        if (callback != null) App.post(callback);
        maybeStartProbe(session);
    }

    private void maybeStartProbe(CaptureSession session) {
        MediaItem item;
        AiSkipAudioProbe probe;
        int engine;
        synchronized (lock) {
            if (activeSession != session || !session.matchesHistory() || !session.cachedLookupComplete
                    || session.exactCachedResultFound || session.probeStarted || session.pendingMediaItem == null) return;
            session.probeStarted = true;
            item = session.pendingMediaItem;
            engine = session.playbackEngine;
            probe = new AiSkipAudioProbe(new AiSkipAudioProbe.Callback() {
                @Override
                public void onComplete(long durationMs, List<AiSkipAudioProbe.CapturedSample> samples) {
                    onProbeComplete(session, durationMs, samples);
                }

                @Override
                public void onFailure(String reason) {
                    synchronized (lock) {
                        if (activeSession == session) session.probe = null;
                    }
                }
            });
            session.probe = probe;
        }
        MpvLogCollector.log("AiSkip", "开始分析，播放内核="
                + (engine == PlayerSetting.ENGINE_MPV ? "MPV" : "EXO"));
        probe.start(item);
    }

    private void onProbeComplete(CaptureSession session, long durationMs,
                                 List<AiSkipAudioProbe.CapturedSample> samples) {
        synchronized (lock) {
            if (activeSession != session || !session.matchesHistory()) return;
            session.probe = null;
            session.durationMs = durationMs;
        }
        for (AiSkipAudioProbe.CapturedSample sample : samples) {
            scheduleUpload(session, sample.side(), sample.startMs(), sample.pcm());
        }
        synchronized (lock) {
            session.captureFinalized = true;
        }
        maybeSubmitJob(session);
    }

    private void scheduleUpload(CaptureSession session, String side, long startMs, short[] pcm) {
        long sampleDurationMs = samplesToMs(pcm.length);
        byte[] wav = WavEncoder.encode(pcm, pcm.length, SAMPLE_RATE);
        synchronized (lock) {
            session.pendingUploads++;
        }
        Task.execute(() -> {
            try {
                String objectKey = new AiSkipApi().upload(wav);
                synchronized (lock) {
                    session.uploadedSamples.add(new AiSkipApi.Sample(side, startMs, sampleDurationMs, objectKey));
                }
            } catch (Exception error) {
                MpvLogCollector.logError("AiSkip", "样本上传失败: " + error.getMessage());
            } finally {
                synchronized (lock) {
                    session.pendingUploads--;
                }
                maybeSubmitJob(session);
            }
        });
    }

    private void maybeSubmitJob(CaptureSession session) {
        boolean ready;
        synchronized (lock) {
            ready = isReadyToSubmit(session.captureFinalized, session.jobSubmissionStarted, session.pendingUploads,
                    session.durationMs, session.uploadedSamples);
            if (ready) session.jobSubmissionStarted = true;
        }
        if (ready) submitJob(session);
    }

    private void submitJob(CaptureSession session) {
        JobSnapshot snapshot;
        synchronized (lock) {
            long duration = Math.max(session.durationMs, 1);
            List<AiSkipApi.Sample> samples = new ArrayList<>(session.uploadedSamples);
            samples.sort(Comparator
                    .comparingInt((AiSkipApi.Sample sample) -> "opening".equals(sample.side()) ? 0 : 1)
                    .thenComparingLong(AiSkipApi.Sample::startMs));
            snapshot = new JobSnapshot(session.mediaKey, session.seriesKey, session.episode, duration, samples);
        }
        try {
            AiSkipResult created = new AiSkipApi().create(snapshot.mediaKey(), snapshot.seriesKey(), snapshot.episode(), snapshot.durationMs(), snapshot.samples());
            if (created != null && created.getJobId().isEmpty()) return;
            poll(session, created == null ? "" : created.getJobId());
        } catch (Exception error) {
            MpvLogCollector.logError("AiSkip", "任务创建失败: " + error.getMessage());
        }
    }

    private void loadCachedResult(CaptureSession session) {
        try {
            AiSkipApi api = new AiSkipApi();
            AiSkipResult cached = api.find(session.mediaKey);
            boolean exact = cached != null && cached.isCompleted();
            if (!exact) {
                AiSkipResult series = api.findSeries(session.seriesKey);
                if (series != null && series.isCompleted() && series.hasUsableBoundary()) cached = series;
            }
            if (cached != null && cached.isCompleted() && (exact || cached.hasUsableBoundary())) {
                synchronized (lock) {
                    session.exactCachedResultFound = exact;
                }
                apply(session, cached, exact);
            }
        } catch (Exception error) {
            MpvLogCollector.logError("AiSkip", "缓存查询失败: " + error.getMessage());
        } finally {
            synchronized (lock) {
                session.cachedLookupComplete = true;
            }
            maybeStartProbe(session);
        }
    }

    private void poll(CaptureSession session, String jobId) {
        if (jobId == null || jobId.isEmpty()) return;
        synchronized (lock) {
            if (activeSession == session && session.matchesHistory()) activeSession.activeJobId = jobId;
        }
        poll(session, jobId, System.currentTimeMillis() + POLL_LIMIT_MS, 5_000);
    }

    private void poll(CaptureSession session, String jobId, long deadline, long delayMs) {
        if (!isActive(session) || System.currentTimeMillis() >= deadline) return;
        try {
            AiSkipResult result = new AiSkipApi().getJob(jobId);
            if (result == null) return;
            if (result.isCompleted()) {
                apply(session, result, true);
                return;
            }
            if (!result.isPending()) return;
        } catch (Exception error) {
            MpvLogCollector.logError("AiSkip", "任务轮询失败: " + error.getMessage());
            return;
        }
        long nextDelayMs = Math.min(30_000, delayMs * 2);
        Task.schedule(() -> Task.execute(() -> poll(session, jobId, deadline, nextDelayMs)), delayMs, TimeUnit.MILLISECONDS);
    }

    private boolean isActive(CaptureSession session) {
        synchronized (lock) {
            return activeSession == session && session.matchesHistory();
        }
    }

    private void apply(CaptureSession session, AiSkipResult result, boolean feedbackEnabled) {
        App.post(() -> applyOnMainThread(session, result, feedbackEnabled));
    }

    private void applyOnMainThread(CaptureSession session, AiSkipResult result, boolean feedbackEnabled) {
        Runnable callback;
        History history;
        BoundarySnapshot previousBoundaries;
        BoundarySnapshot updatedBoundaries;
        synchronized (lock) {
            CaptureSession current = activeSession;
            if (current != session || !current.mediaKey.equals(session.mediaKey) || !current.matchesHistory()) return;
            history = current.history;
            previousBoundaries = BoundarySnapshot.from(current.history);
            if (previousBoundaries == null) return;
            if (feedbackEnabled) current.activeJobId = result.getJobId();
            current.resultApplied = true;
            clearStaleAiBoundaries(current.history, current.mediaKey);
            long durationMs = current.history.getDuration() > 0
                    ? current.history.getDuration() : result.getDurationMs();
            if (!isManual(current.history.getOpening(), current.history.getOpeningSource())
                    && isPlausibleBoundary(result.getOpeningMs(), durationMs)
                    && result.getOpeningConfidence() >= 0.8f) {
                current.history.setOpening(result.getOpeningMs());
                current.history.setOpeningSource(aiSource(current.mediaKey));
            }
            if (!isManual(current.history.getEnding(), current.history.getEndingSource())
                    && isPlausibleBoundary(result.getEndingMs(), durationMs)
                    && result.getEndingConfidence() >= 0.8f) {
                current.history.setEnding(result.getEndingMs());
                current.history.setEndingSource(aiSource(current.mediaKey));
            }
            callback = current.mediaResolved ? current.appliedCallback : null;
            updatedBoundaries = BoundarySnapshot.from(current.history);
        }
        saveAsync(history, previousBoundaries, updatedBoundaries);
        if (callback != null) callback.run();
    }

    private void saveAsync(@Nullable History history, @Nullable BoundarySnapshot previous, @Nullable BoundarySnapshot updated) {
        if (history == null || previous == null || updated == null || previous.equals(updated) || !previous.matchesEpisode(updated)) return;
        try {
            BOUNDARY_EXECUTOR.execute(() -> {
                if (Setting.isIncognito() || !updated.matches(history)) return;
                try {
                    updated.saveIfUnchanged(previous);
                } catch (Exception ignored) {
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    static boolean isReadyToSubmit(boolean captureFinalized, boolean submissionStarted, int pendingUploads,
                                   long durationMs, List<AiSkipApi.Sample> samples) {
        if (!captureFinalized || submissionStarted || pendingUploads > 0 || durationMs <= 0) return false;
        boolean hasOpening = samples.stream().anyMatch(sample -> "opening".equals(sample.side()));
        boolean hasEnding = samples.stream().anyMatch(sample -> "ending".equals(sample.side()));
        return hasOpening && hasEnding;
    }

    static boolean isManual(long value, String source) {
        return "manual".equalsIgnoreCase(source) || ("unknown".equalsIgnoreCase(source) && value > 0);
    }

    static boolean isPlausibleBoundary(long boundaryMs, long durationMs) {
        return boundaryMs > 0 && durationMs > 0 && boundaryMs < durationMs
                && boundaryMs <= Constant.getOpEdLimit(durationMs);
    }

    public static boolean shouldSkipOpening(long positionMs, long openingMs) {
        return positionMs >= 0 && openingMs > 0 && positionMs < openingMs;
    }

    public static boolean hasEnteredEnding(long positionMs, long durationMs, long endingMs) {
        return positionMs >= 0 && durationMs > 0 && endingMs > 0 && endingMs < durationMs
                && positionMs >= durationMs - endingMs;
    }

    static String aiSource(String mediaKey) {
        return AI_SOURCE_PREFIX + (mediaKey == null ? "" : mediaKey);
    }

    static boolean isAiSourceFor(String source, String mediaKey) {
        return source != null && mediaKey != null && !mediaKey.isEmpty() && source.equals(aiSource(mediaKey));
    }

    static boolean isStaleAiSource(String source, String mediaKey) {
        if (source == null) return false;
        boolean aiSource = AI_SOURCE.equalsIgnoreCase(source) || source.regionMatches(true, 0, AI_SOURCE_PREFIX, 0, AI_SOURCE_PREFIX.length());
        return aiSource && !isAiSourceFor(source, mediaKey);
    }

    static boolean clearStaleAiBoundaries(History history, String mediaKey) {
        if (history == null || mediaKey == null || mediaKey.isEmpty()) return false;
        boolean changed = false;
        if (isStaleAiSource(history.getOpeningSource(), mediaKey)) {
            history.setOpening(C.TIME_UNSET);
            history.setOpeningSource("unknown");
            changed = true;
        }
        if (isStaleAiSource(history.getEndingSource(), mediaKey)) {
            history.setEnding(C.TIME_UNSET);
            history.setEndingSource("unknown");
            changed = true;
        }
        return changed;
    }

    static boolean hasStaleAiBoundaries(History history, String mediaKey) {
        return history != null && (isStaleAiSource(history.getOpeningSource(), mediaKey)
                || isStaleAiSource(history.getEndingSource(), mediaKey));
    }

    static long prepareStartPosition(String mediaKey, History history, long requestedPositionMs) {
        if (!clearStaleAiBoundaries(history, mediaKey)) return requestedPositionMs;
        long position = Math.max(history.getOpening(), history.getPosition());
        return position < 0 ? C.TIME_UNSET : position;
    }

    public void feedback(History target) {
        String jobId;
        synchronized (lock) {
            CaptureSession session = activeSession;
            if (session == null || target == null || target != session.history || !session.matchesHistory()
                    || session.activeJobId == null || session.activeJobId.isEmpty()) return;
            jobId = session.activeJobId;
        }
        long openingMs = Math.max(0, target.getOpening());
        long endingMs = Math.max(0, target.getEnding());
        Task.execute(() -> {
            try { new AiSkipApi().feedback(jobId, openingMs, endingMs); } catch (Exception ignored) { }
        });
    }

    private static long samplesToMs(int samples) {
        return samples * 1000L / SAMPLE_RATE;
    }

    private static final class CaptureSession {
        private final History history;
        private final String mediaKey;
        private final String seriesKey;
        private final String episode;
        private final EpisodeSnapshot episodeSnapshot;
        private final List<AiSkipApi.Sample> uploadedSamples = new ArrayList<>();
        private long durationMs;
        private boolean captureFinalized;
        private boolean jobSubmissionStarted;
        private int pendingUploads;
        private boolean cachedLookupComplete;
        private boolean exactCachedResultFound;
        private boolean probeStarted;
        private boolean mediaResolved;
        private boolean resultApplied;
        private int playbackEngine;
        private Runnable appliedCallback;
        private String activeJobId;
        private MediaItem pendingMediaItem;
        private AiSkipAudioProbe probe;

        private CaptureSession(String mediaKey, History history, @Nullable String episode,
                               @Nullable Runnable appliedCallback) {
            this.history = history;
            this.mediaKey = mediaKey;
            this.seriesKey = com.github.catvod.utils.Util.md5(history.getKey());
            this.episode = episode == null ? history.getVodRemarks() : episode;
            this.episodeSnapshot = EpisodeSnapshot.from(history);
            this.durationMs = history.getDuration();
            this.appliedCallback = appliedCallback;
        }

        private boolean matchesHistory() {
            return episodeSnapshot != null && episodeSnapshot.matches(history);
        }

        private void cancelProbe() {
            if (probe != null) probe.cancel();
            probe = null;
        }
    }

    private record JobSnapshot(String mediaKey, String seriesKey, String episode, long durationMs,
                               List<AiSkipApi.Sample> samples) {
    }

    record EpisodeSnapshot(int cid, String key, String vodRemarks, String episodeUrl) {

        @Nullable
        static EpisodeSnapshot from(@Nullable History history) {
            if (history == null || history.getKey() == null || history.getKey().isEmpty()) return null;
            return new EpisodeSnapshot(history.getCid(), history.getKey(), history.getVodRemarks(), history.getEpisodeUrl());
        }

        boolean matches(@Nullable History history) {
            return history != null
                    && cid == history.getCid()
                    && Objects.equals(key, history.getKey())
                    && Objects.equals(vodRemarks, history.getVodRemarks())
                    && Objects.equals(episodeUrl, history.getEpisodeUrl());
        }
    }

    record BoundarySnapshot(int cid, String key, String vodRemarks, String episodeUrl,
                            long opening, String openingSource, long ending, String endingSource) {

        @Nullable
        static BoundarySnapshot from(@Nullable History history) {
            if (history == null || history.getKey() == null || history.getKey().isEmpty()) return null;
            return new BoundarySnapshot(history.getCid(), history.getKey(), history.getVodRemarks(), history.getEpisodeUrl(),
                    history.getOpening(), history.getOpeningSource(), history.getEnding(), history.getEndingSource());
        }

        boolean matches(History history) {
            return history != null
                    && cid == history.getCid()
                    && Objects.equals(key, history.getKey())
                    && Objects.equals(vodRemarks, history.getVodRemarks())
                    && Objects.equals(episodeUrl, history.getEpisodeUrl())
                    && opening == history.getOpening()
                    && Objects.equals(openingSource, history.getOpeningSource())
                    && ending == history.getEnding()
                    && Objects.equals(endingSource, history.getEndingSource());
        }

        boolean matchesEpisode(BoundarySnapshot other) {
            return other != null
                    && cid == other.cid
                    && Objects.equals(key, other.key)
                    && Objects.equals(vodRemarks, other.vodRemarks)
                    && Objects.equals(episodeUrl, other.episodeUrl);
        }

        void saveIfUnchanged(BoundarySnapshot previous) {
            AppDatabase.get().getHistoryDao().updateBoundariesIfUnchanged(cid, key, vodRemarks, episodeUrl,
                    previous.opening, previous.openingSource, previous.ending, previous.endingSource,
                    opening, openingSource, ending, endingSource);
        }
    }

}
