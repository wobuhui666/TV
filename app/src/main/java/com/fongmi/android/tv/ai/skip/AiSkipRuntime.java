package com.fongmi.android.tv.ai.skip;

import androidx.annotation.Nullable;
import androidx.media3.common.C;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.ai.subtitle.PcmTapAudioProcessor;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class AiSkipRuntime {
    private static final String AI_SOURCE = "ai";
    private static final String AI_SOURCE_PREFIX = AI_SOURCE + ":";
    private static final int SAMPLE_RATE = 8_000;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE * 30;
    private static final int MAX_CHUNKS_PER_SIDE = 3;
    private static final int WINDOW_SAMPLES = CHUNK_SAMPLES * MAX_CHUNKS_PER_SIDE;
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

    public PcmTapAudioProcessor.Sink createPcmSink() {
        return this::onPcm;
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
        CaptureSession session = new CaptureSession(mediaKey, history, episode, effectivePositionMs, appliedCallback);
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
                activeSession.releaseAudio();
            }
            activeSession = null;
        }
    }

    public void onTimeChanged(long positionMs, long durationMs) {
        synchronized (lock) {
            CaptureSession session = activeSession;
            if (session == null) return;
            if (durationMs > 0) session.durationMs = durationMs;
            if (positionMs >= 0 && session.durationMs > 0 && positionMs >= session.durationMs - 2_000 && session.endingLength > SAMPLE_RATE * 5) {
                if (!session.captureFinalized && session.captureOpening && session.openingLength > SAMPLE_RATE * 5) finalizeCapture(session);
            }
        }
    }

    private void onPcm(float[] mono, int sampleRate) {
        if (mono == null || mono.length == 0 || sampleRate <= 0) return;
        synchronized (lock) {
            CaptureSession session = activeSession;
            if (session == null || session.captureFinalized) return;
            if (session.inputRate != sampleRate) {
                session.inputRate = sampleRate;
                session.resampleCursor = 0;
                session.inputSamples = 0;
            }
            double step = sampleRate / (double) SAMPLE_RATE;
            for (float value : mono) {
                if (session.inputSamples >= session.resampleCursor) {
                    short pcm = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 32767f)));
                    if (session.captureOpening && session.openingLength < WINDOW_SAMPLES) session.opening[session.openingLength++] = pcm;
                    session.ending[session.endingLength % WINDOW_SAMPLES] = pcm;
                    session.endingLength++;
                    session.resampleCursor += step;
                }
                session.inputSamples++;
            }
            while (session.nextOpeningChunk < MAX_CHUNKS_PER_SIDE
                    && session.openingLength >= (session.nextOpeningChunk + 1) * CHUNK_SAMPLES) {
                scheduleOpeningChunk(session, session.nextOpeningChunk, CHUNK_SAMPLES);
                session.nextOpeningChunk++;
            }
        }
    }

    private void scheduleOpeningChunk(CaptureSession session, int index, int length) {
        int offset = index * CHUNK_SAMPLES;
        short[] chunk = Arrays.copyOfRange(session.opening, offset, offset + length);
        scheduleUpload(session, "opening", samplesToMs(offset), chunk);
    }

    private void finalizeCapture(CaptureSession session) {
        session.captureFinalized = true;
        int openingOffset = session.nextOpeningChunk * CHUNK_SAMPLES;
        if (openingOffset < session.openingLength) {
            scheduleOpeningChunk(session, session.nextOpeningChunk, session.openingLength - openingOffset);
            session.nextOpeningChunk++;
        }

        int length = Math.min(session.endingLength, WINDOW_SAMPLES);
        int start = Math.max(0, session.endingLength - length);
        long tailStartMs = Math.max(0, session.durationMs - samplesToMs(length));
        for (int offset = 0; offset < length; offset += CHUNK_SAMPLES) {
            int chunkLength = Math.min(CHUNK_SAMPLES, length - offset);
            short[] chunk = new short[chunkLength];
            for (int i = 0; i < chunkLength; i++) chunk[i] = session.ending[(start + offset + i) % WINDOW_SAMPLES];
            scheduleUpload(session, "ending", tailStartMs + samplesToMs(offset), chunk);
        }
        session.releaseAudio();
        maybeSubmitJob(session);
    }

    private void scheduleUpload(CaptureSession session, String side, long startMs, short[] pcm) {
        long sampleDurationMs = samplesToMs(pcm.length);
        byte[] wav = WavEncoder.encode(pcm, pcm.length, SAMPLE_RATE);
        session.pendingUploads++;
        Task.execute(() -> {
            try {
                String objectKey = new AiSkipApi().upload(wav);
                synchronized (lock) {
                    session.uploadedSamples.add(new AiSkipApi.Sample(side, startMs, sampleDurationMs, objectKey));
                }
            } catch (Exception ignored) {
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
        } catch (Exception ignored) {
        }
    }

    private void loadCachedResult(CaptureSession session) {
        try {
            AiSkipResult cached = new AiSkipApi().find(session.mediaKey);
            if (cached != null && cached.isCompleted()) apply(session, cached);
        } catch (Exception ignored) {
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
                apply(session, result);
                return;
            }
            if (!result.isPending()) return;
        } catch (Exception ignored) {
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

    private void apply(CaptureSession session, AiSkipResult result) {
        App.post(() -> applyOnMainThread(session, result));
    }

    private void applyOnMainThread(CaptureSession session, AiSkipResult result) {
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
            current.activeJobId = result.getJobId();
            clearStaleAiBoundaries(current.history, current.mediaKey);
            if (!isManual(current.history.getOpening(), current.history.getOpeningSource()) && result.getOpeningMs() > 0 && result.getOpeningConfidence() >= 0.8f) {
                current.history.setOpening(result.getOpeningMs());
                current.history.setOpeningSource(aiSource(current.mediaKey));
            }
            if (!isManual(current.history.getEnding(), current.history.getEndingSource()) && result.getEndingMs() > 0 && result.getEndingConfidence() >= 0.8f) {
                current.history.setEnding(result.getEndingMs());
                current.history.setEndingSource(aiSource(current.mediaKey));
            }
            callback = current.appliedCallback;
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
        private short[] opening = new short[WINDOW_SAMPLES];
        private short[] ending = new short[WINDOW_SAMPLES];
        private final History history;
        private final String mediaKey;
        private final String seriesKey;
        private final String episode;
        private final EpisodeSnapshot episodeSnapshot;
        private final List<AiSkipApi.Sample> uploadedSamples = new ArrayList<>();
        private long durationMs;
        private boolean captureOpening;
        private boolean captureFinalized;
        private boolean jobSubmissionStarted;
        private int openingLength;
        private int endingLength;
        private int nextOpeningChunk;
        private int pendingUploads;
        private int inputRate;
        private int inputSamples;
        private double resampleCursor;
        private Runnable appliedCallback;
        private String activeJobId;

        private CaptureSession(String mediaKey, History history, @Nullable String episode, long startPositionMs,
                               @Nullable Runnable appliedCallback) {
            this.history = history;
            this.mediaKey = mediaKey;
            this.seriesKey = com.github.catvod.utils.Util.md5(history.getKey());
            this.episode = episode == null ? history.getVodRemarks() : episode;
            this.episodeSnapshot = EpisodeSnapshot.from(history);
            this.durationMs = history.getDuration();
            this.captureOpening = startPositionMs <= TimeUnit.SECONDS.toMillis(2);
            this.appliedCallback = appliedCallback;
        }

        private boolean matchesHistory() {
            return episodeSnapshot != null && episodeSnapshot.matches(history);
        }

        private void releaseAudio() {
            opening = new short[0];
            ending = new short[0];
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
