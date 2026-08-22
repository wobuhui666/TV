package com.fongmi.android.tv.ai.skip;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.ai.subtitle.PcmTapAudioProcessor;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class AiSkipRuntime {
    private static final int SAMPLE_RATE = 8_000;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE * 30;
    private static final int MAX_CHUNKS_PER_SIDE = 3;
    private static final int WINDOW_SAMPLES = CHUNK_SAMPLES * MAX_CHUNKS_PER_SIDE;
    private static final long POLL_LIMIT_MS = TimeUnit.MINUTES.toMillis(3);
    private static volatile AiSkipRuntime instance;

    private final Object lock = new Object();
    private short[] opening = new short[WINDOW_SAMPLES];
    private int openingLength;
    private short[] ending = new short[WINDOW_SAMPLES];
    private int endingLength;
    private double resampleCursor;
    private int inputRate;
    private int inputSamples;
    private History history;
    private String mediaKey;
    private String seriesKey;
    private String episode;
    private long durationMs;
    private boolean captureOpening;
    private boolean captureFinalized;
    private boolean jobSubmissionStarted;
    private boolean stopped;
    private int nextOpeningChunk;
    private int pendingUploads;
    private final List<AiSkipApi.Sample> uploadedSamples = new ArrayList<>();
    private Runnable appliedCallback;
    private String activeJobId;

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

    public void startSession(@Nullable String mediaKey, @Nullable History history, @Nullable String episode,
                             long startPositionMs, @Nullable Runnable appliedCallback) {
        stopSession();
        if (!AiSkipSettings.isConfigured() || history == null || mediaKey == null || mediaKey.isEmpty()) return;
        synchronized (lock) {
            resetCapture();
            this.history = history;
            this.mediaKey = mediaKey;
            this.seriesKey = com.github.catvod.utils.Util.md5(history.getKey());
            this.episode = episode == null ? history.getVodRemarks() : episode;
            this.durationMs = history.getDuration();
            this.captureOpening = startPositionMs <= TimeUnit.SECONDS.toMillis(2);
            this.appliedCallback = appliedCallback;
            this.stopped = false;
        }
        String sessionMediaKey = mediaKey;
        Task.execute(() -> loadCachedResult(sessionMediaKey));
    }

    public void stopSession() {
        synchronized (lock) {
            stopped = true;
            history = null;
            mediaKey = null;
            appliedCallback = null;
            resetCapture();
        }
    }

    public void onTimeChanged(long positionMs, long durationMs) {
        synchronized (lock) {
            if (stopped || history == null) return;
            if (durationMs > 0) this.durationMs = durationMs;
            if (positionMs >= 0 && this.durationMs > 0 && positionMs >= this.durationMs - 2_000 && endingLength > SAMPLE_RATE * 5) {
                if (!captureFinalized && captureOpening && openingLength > SAMPLE_RATE * 5) finalizeCapture();
            }
        }
    }

    private void onPcm(float[] mono, int sampleRate) {
        if (mono == null || mono.length == 0 || sampleRate <= 0) return;
        synchronized (lock) {
            if (stopped || history == null) return;
            if (inputRate != sampleRate) {
                inputRate = sampleRate;
                resampleCursor = 0;
                inputSamples = 0;
            }
            double step = sampleRate / (double) SAMPLE_RATE;
            for (float value : mono) {
                if (inputSamples >= resampleCursor) {
                    short pcm = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 32767f)));
                    if (captureOpening && openingLength < WINDOW_SAMPLES) opening[openingLength++] = pcm;
                    ending[endingLength % WINDOW_SAMPLES] = pcm;
                    endingLength++;
                    resampleCursor += step;
                }
                inputSamples++;
            }
            while (nextOpeningChunk < MAX_CHUNKS_PER_SIDE
                    && openingLength >= (nextOpeningChunk + 1) * CHUNK_SAMPLES) {
                scheduleOpeningChunk(nextOpeningChunk, CHUNK_SAMPLES);
                nextOpeningChunk++;
            }
        }
    }

    private void scheduleOpeningChunk(int index, int length) {
        int offset = index * CHUNK_SAMPLES;
        short[] chunk = Arrays.copyOfRange(opening, offset, offset + length);
        scheduleUpload("opening", samplesToMs(offset), chunk);
    }

    private void finalizeCapture() {
        captureFinalized = true;
        int openingOffset = nextOpeningChunk * CHUNK_SAMPLES;
        if (openingOffset < openingLength) {
            scheduleOpeningChunk(nextOpeningChunk, openingLength - openingOffset);
            nextOpeningChunk++;
        }

        int length = Math.min(endingLength, WINDOW_SAMPLES);
        int start = Math.max(0, endingLength - length);
        long tailStartMs = Math.max(0, durationMs - samplesToMs(length));
        for (int offset = 0; offset < length; offset += CHUNK_SAMPLES) {
            int chunkLength = Math.min(CHUNK_SAMPLES, length - offset);
            short[] chunk = new short[chunkLength];
            for (int i = 0; i < chunkLength; i++) chunk[i] = ending[(start + offset + i) % WINDOW_SAMPLES];
            scheduleUpload("ending", tailStartMs + samplesToMs(offset), chunk);
        }
        maybeSubmitJob(mediaKey);
    }

    private void scheduleUpload(String side, long startMs, short[] pcm) {
        String media = mediaKey;
        if (media == null) return;
        long sampleDurationMs = samplesToMs(pcm.length);
        byte[] wav = WavEncoder.encode(pcm, pcm.length, SAMPLE_RATE);
        pendingUploads++;
        Task.execute(() -> {
            try {
                String objectKey = new AiSkipApi().upload(wav);
                synchronized (lock) {
                    if (!media.equals(mediaKey)) return;
                    uploadedSamples.add(new AiSkipApi.Sample(side, startMs, sampleDurationMs, objectKey));
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (lock) {
                    if (!media.equals(mediaKey)) return;
                    pendingUploads--;
                }
                maybeSubmitJob(media);
            }
        });
    }

    private void maybeSubmitJob(String media) {
        boolean ready;
        synchronized (lock) {
            if (media == null || !media.equals(mediaKey) || !captureFinalized || jobSubmissionStarted
                    || pendingUploads > 0 || durationMs <= 0) return;
            boolean hasOpening = uploadedSamples.stream().anyMatch(sample -> "opening".equals(sample.side()));
            boolean hasEnding = uploadedSamples.stream().anyMatch(sample -> "ending".equals(sample.side()));
            ready = hasOpening && hasEnding;
            if (ready) jobSubmissionStarted = true;
        }
        if (ready) submitJob(media);
    }

    private void submitJob(String media) {
        JobSnapshot snapshot;
        synchronized (lock) {
            if (history == null || !media.equals(mediaKey)) return;
            long duration = Math.max(durationMs, 1);
            List<AiSkipApi.Sample> samples = new ArrayList<>(uploadedSamples);
            samples.sort(Comparator
                    .comparingInt((AiSkipApi.Sample sample) -> "opening".equals(sample.side()) ? 0 : 1)
                    .thenComparingLong(AiSkipApi.Sample::startMs));
            snapshot = new JobSnapshot(media, seriesKey, episode, duration, samples);
        }
        try {
            AiSkipResult created = new AiSkipApi().create(snapshot.mediaKey(), snapshot.seriesKey(), snapshot.episode(), snapshot.durationMs(), snapshot.samples());
            if (created != null && created.getJobId().isEmpty()) return;
            poll(media, created == null ? "" : created.getJobId());
        } catch (Exception ignored) {
        }
    }

    private void loadCachedResult(String media) {
        try {
            AiSkipResult cached = new AiSkipApi().find(media);
            if (cached != null && cached.isCompleted()) apply(media, cached);
        } catch (Exception ignored) {
        }
    }

    private void poll(String media, String jobId) {
        if (jobId == null || jobId.isEmpty()) return;
        synchronized (lock) { if (media.equals(mediaKey)) activeJobId = jobId; }
        long deadline = System.currentTimeMillis() + POLL_LIMIT_MS;
        long delay = 5_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                AiSkipResult result = new AiSkipApi().getJob(jobId);
                if (result == null) return;
                if (result.isCompleted()) { apply(media, result); return; }
                if (!result.isPending()) return;
            } catch (Exception ignored) {
                return;
            }
            try { Thread.sleep(delay); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            delay = Math.min(30_000, delay * 2);
        }
    }

    private void apply(String media, AiSkipResult result) {
        synchronized (lock) {
            if (history == null || !media.equals(mediaKey)) return;
            activeJobId = result.getJobId();
            if (!isManual(history.getOpening(), history.getOpeningSource()) && result.getOpeningMs() > 0 && result.getOpeningConfidence() >= 0.8f) {
                history.setOpening(result.getOpeningMs());
                history.setOpeningSource("ai");
            }
            if (!isManual(history.getEnding(), history.getEndingSource()) && result.getEndingMs() > 0 && result.getEndingConfidence() >= 0.8f) {
                history.setEnding(result.getEndingMs());
                history.setEndingSource("ai");
            }
            history.save();
            if (appliedCallback != null) App.post(appliedCallback);
        }
    }

    static boolean isManual(long value, String source) {
        return "manual".equalsIgnoreCase(source) || ("unknown".equalsIgnoreCase(source) && value > 0);
    }

    public void feedback(History target) {
        String jobId;
        synchronized (lock) {
            if (target == null || target != history || activeJobId == null || activeJobId.isEmpty()) return;
            jobId = activeJobId;
        }
        long openingMs = Math.max(0, target.getOpening());
        long endingMs = Math.max(0, target.getEnding());
        Task.execute(() -> {
            try { new AiSkipApi().feedback(jobId, openingMs, endingMs); } catch (Exception ignored) { }
        });
    }

    private void resetCapture() {
        opening = new short[WINDOW_SAMPLES];
        ending = new short[WINDOW_SAMPLES];
        openingLength = 0;
        endingLength = 0;
        resampleCursor = 0;
        inputSamples = 0;
        inputRate = 0;
        durationMs = 0;
        captureOpening = false;
        captureFinalized = false;
        jobSubmissionStarted = false;
        nextOpeningChunk = 0;
        pendingUploads = 0;
        uploadedSamples.clear();
        activeJobId = null;
    }

    private static long samplesToMs(int samples) {
        return samples * 1000L / SAMPLE_RATE;
    }

    private record JobSnapshot(String mediaKey, String seriesKey, String episode, long durationMs,
                               List<AiSkipApi.Sample> samples) {
    }

}
