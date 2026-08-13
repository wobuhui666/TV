package com.fongmi.android.tv.ai.subtitle;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AiSubtitleRuntime implements SherpaSubtitleController.Listener {
    private static final String TAG = "AiSubtitle";
    private static final String STATUS_TAG = "ai-subtitle-starting-status";
    // A real AudioTrack presentation clock is available, so do not intentionally lead speech.
    private static final long CUE_EARLY_MS = 0L;
    private static final long CUE_END_GRACE_MS = 500L;
    private static final long MAX_PRESENTATION_ERROR_MS = 120L;
    private static final long MAX_CUE_DURATION_MS = 6_500L;
    private static final long MIN_CUE_VISIBLE_MS = 900L;
    private static final int MIN_CUE_DURATION_PERCENT = 75;
    private static final long LOOKAHEAD_MS = AiAudioTrackBufferSizeProvider.LOOKAHEAD_MS;
    private static final long MIN_PRIMED_LOOKAHEAD_MS = 3_500L;
    private static final long LOOKAHEAD_STABLE_MS = 600L;
    private static final long AUDIO_CLOCK_MAX_AGE_MS = 500L;
    private static final long TICK_MS = 20L;
    private static volatile AiSubtitleRuntime instance;

    private record ScheduledCue(long id, long startElapsedMs, long endElapsedMs,
                                long segmentStartUs, String text) {
    }

    static record CueWindow(long startElapsedMs, long endElapsedMs) {
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AsrModelManager models;
    private final SherpaSubtitleController controller;
    private final OpenAiSubtitleTranslator translator;
    private final AtomicLong sessionGeneration = new AtomicLong();
    private final AtomicLong pipelineGeneration = new AtomicLong();
    private final AtomicLong cueIds = new AtomicLong();
    private final AtomicBoolean firstPcm = new AtomicBoolean();
    private final Object timelineLock = new Object();
    private final ArrayList<ScheduledCue> pendingCues = new ArrayList<>();
    private volatile WeakReference<PlayerView> playerView = new WeakReference<>(null);
    private volatile WeakReference<TextView> statusView = new WeakReference<>(null);
    private volatile Player player;
    private volatile boolean sessionActive;
    private volatile boolean engineReady;
    private volatile boolean pipelinePrimed;
    private volatile boolean serviceReady;
    private volatile boolean timelineAligned;
    private volatile String status = "未启动";
    private volatile String selectedAudioTrackKey;
    private volatile long latestPipelineId;
    private volatile long capturedTimelineUs;
    private volatile long latestCapturedUs;
    private volatile long timelineAnchorUs = C.TIME_UNSET;
    private volatile long mediaAnchorMs = C.TIME_UNSET;
    private volatile long firstPcmElapsedMs = C.TIME_UNSET;
    private volatile long observedLookaheadMs;
    private volatile long calibratedLookaheadMs;
    private volatile long latestAudioOutputId;
    private volatile long audioOutputWrittenUs;
    private volatile long audioOutputPositionUs;
    private volatile long audioClockSampleElapsedMs = C.TIME_UNSET;
    private volatile boolean audioClockPlaying;
    private long lookaheadStableSinceMs = C.TIME_UNSET;
    private ScheduledCue currentCue;

    private final Runnable cueTicker = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive) return;
            tickCues();
            main.postDelayed(this, TICK_MS);
        }
    };

    private AiSubtitleRuntime() {
        models = new AsrModelManager(App.get());
        translator = new OpenAiSubtitleTranslator();
        controller = new SherpaSubtitleController(models, this);
    }

    public static AiSubtitleRuntime get() {
        if (instance == null) {
            synchronized (AiSubtitleRuntime.class) {
                if (instance == null) instance = new AiSubtitleRuntime();
            }
        }
        return instance;
    }

    public AsrModelManager models() {
        return models;
    }

    public String status() {
        return status;
    }

    /** Returns a generation-bound sink so a released player's late audio cannot enter a new session. */
    public PcmTapAudioProcessor.Sink createPcmSink() {
        long pipelineId = pipelineGeneration.incrementAndGet();
        latestPipelineId = pipelineId;
        clearAudioClock();
        return (mono, sampleRate) -> offerPcm(pipelineId, mono, sampleRate);
    }

    /** Binds Media3's AudioTrack-backed presentation clock to the same PCM pipeline generation. */
    public AiAudioOutputProvider.ClockSink createAudioClockSink() {
        long pipelineId = latestPipelineId;
        return new AiAudioOutputProvider.ClockSink() {
            @Override
            public void onSample(long outputId, long writtenUs, long positionUs,
                                 long sampledElapsedMs, boolean playing) {
                updateAudioClock(pipelineId, outputId, writtenUs, positionUs,
                        sampledElapsedMs, playing);
            }

            @Override
            public void onReleased(long outputId) {
                releaseAudioClock(pipelineId, outputId);
            }
        };
    }

    public void attachPlayerView(PlayerView view) {
        playerView = new WeakReference<>(view);
        TextView overlay = findOrCreateStatusView(view);
        statusView = new WeakReference<>(overlay);
        if (sessionActive && AiSubtitleSettings.isEnabled() && !serviceReady) showStartingStatus();
        else overlay.setVisibility(View.GONE);
        if (!AiSubtitleSettings.isEnabled()) view.getSubtitleView().setCues(null);
    }

    public void detachPlayerView(PlayerView view) {
        PlayerView current = playerView.get();
        if (current == view) {
            current.getSubtitleView().setCues(null);
            TextView overlay = statusView.get();
            if (overlay != null) overlay.setVisibility(View.GONE);
            playerView = new WeakReference<>(null);
            statusView = new WeakReference<>(null);
        }
    }

    public void startSession(Player sessionPlayer) {
        player = sessionPlayer;
        sessionActive = true;
        selectedAudioTrackKey = null;
        restartSessionState("播放会话启动");
    }

    public void stopSession() {
        sessionActive = false;
        sessionGeneration.incrementAndGet();
        controller.stop();
        translator.reset();
        player = null;
        selectedAudioTrackKey = null;
        resetTimeline();
        clearScheduledCues();
        hideStartingStatus();
        status = "未启动";
    }

    public void onSettingsChanged() {
        if (!sessionActive) {
            clearScheduledCues();
            return;
        }
        restartSessionState("字幕配置变更");
    }

    public void prepareForAudioPipelineRebuild() {
        long token = sessionGeneration.incrementAndGet();
        controller.stop();
        translator.reset();
        resetTimeline();
        clearScheduledCues();
        engineReady = false;
        pipelinePrimed = false;
        serviceReady = false;
        timelineAligned = false;
        firstPcm.set(false);
        main.removeCallbacks(cueTicker);
        if (AiSubtitleSettings.isEnabled()) {
            status = "字幕翻译服务正在启动中";
            showStartingStatus();
            Log.i(TAG, "pipelineRebuildPrepared generation=" + token
                    + " lang=" + AiSubtitleSettings.getLanguage().code());
        } else {
            status = "实时字幕已关闭";
            hideStartingStatus();
            Log.i(TAG, "sessionStopped generation=" + token + " reason=字幕手动关闭");
        }
    }

    /** Called for every real Media3 track update, not only the first manifest track discovery. */
    public void onTracksChanged(Tracks tracks) {
        if (!sessionActive || !AiSubtitleSettings.isEnabled() || tracks == null || tracks.isEmpty()) return;
        String key = selectedAudioKey(tracks);
        if (key == null) return;
        String previous = selectedAudioTrackKey;
        selectedAudioTrackKey = key;
        if (previous != null && !previous.equals(key)) restartSessionState("音轨切换");
    }

    public void onPlaybackPositionDiscontinuity() {
        if (!sessionActive || !AiSubtitleSettings.isEnabled() || timelineAnchorUs == C.TIME_UNSET) return;
        restartSessionState("播放时间线跳变");
    }

    private void restartSessionState(String reason) {
        long token = sessionGeneration.incrementAndGet();
        controller.stop();
        translator.reset();
        resetTimeline();
        clearScheduledCues();
        engineReady = false;
        pipelinePrimed = false;
        serviceReady = false;
        timelineAligned = false;
        firstPcm.set(false);
        if (AiSubtitleSettings.isEnabled()) {
            status = "字幕翻译服务正在启动中";
            showStartingStatus();
            controller.start(AiSubtitleSettings.getLanguage());
            startTicker();
            Log.i(TAG, "sessionReload generation=" + token + " reason=" + reason
                    + " lang=" + AiSubtitleSettings.getLanguage().code());
        } else {
            main.removeCallbacks(cueTicker);
            hideStartingStatus();
            status = "实时字幕已关闭";
        }
    }

    private void offerPcm(long pipelineId, float[] mono, int sampleRate) {
        if (pipelineId != latestPipelineId || !sessionActive || !AiSubtitleSettings.isEnabled()
                || mono == null || mono.length == 0 || sampleRate <= 0) return;
        long startUs;
        long endUs;
        synchronized (timelineLock) {
            startUs = capturedTimelineUs;
            long durationUs = Math.max(1L, mono.length * 1_000_000L / sampleRate);
            endUs = startUs + durationUs;
            capturedTimelineUs = endUs;
            latestCapturedUs = endUs;
        }
        long token = sessionGeneration.get();
        if (firstPcm.compareAndSet(false, true)) {
            firstPcmElapsedMs = android.os.SystemClock.elapsedRealtime();
            Log.i(TAG, "pcmTap active sampleRate=" + sampleRate + " samples=" + mono.length
                    + " pipeline=" + pipelineId);
            main.post(() -> alignTimeline(token, startUs));
        }
        controller.offer(mono, sampleRate, startUs, endUs);
    }

    private void alignTimeline(long token, long firstChunkStartUs) {
        if (token != sessionGeneration.get() || !sessionActive || !AiSubtitleSettings.isEnabled()) return;
        Player current = player;
        if (current == null) return;
        long position = readPlaybackPosition(current);
        timelineAnchorUs = firstChunkStartUs;
        mediaAnchorMs = position;
        timelineAligned = false;
        Log.i(TAG, "timelineAnchor generation=" + token + " mediaMs=" + position
                + " tapUs=" + firstChunkStartUs + " engineReady=" + engineReady);
        updatePrimingState();
    }

    public SherpaSubtitleController.Metrics metrics() {
        return controller.metrics();
    }

    @Override
    public void onRecognized(SherpaSubtitleController.RecognizedSegment segment) {
        SherpaSubtitleController.Metrics metrics = controller.metrics();
        Log.i(TAG, "recognized lang=" + AiSubtitleSettings.getLanguage().code()
                + " decodeMs=" + segment.decodeMs() + " audioMs=" + segment.audioMs()
                + " startUs=" + segment.startUs() + " endUs=" + segment.endUs()
                + " droppedPcm=" + metrics.droppedChunks
                + " droppedSpeech=" + metrics.droppedSpeechSegments
                + " maxOfferUs=" + metrics.maxOfferMicros);
        if (BuildConfig.DEBUG) Log.i(TAG, "recognizedText=" + segment.text());
        long token = sessionGeneration.get();
        if (!sessionActive || !pipelinePrimed || !AiSubtitleSettings.isEnabled()) {
            Log.i(TAG, "subtitle dropped before lookahead primed");
            return;
        }

        // Atomic bilingual delivery: never publish source text first.  Translation (including the
        // OFF provider's immediate source passthrough) must finish before a single cue is queued.
        long translationStartedMs = android.os.SystemClock.elapsedRealtime();
        translator.translate(AiSubtitleSettings.getLanguage(), segment.text(), new OpenAiSubtitleTranslator.ResultCallback() {
            @Override
            public void onSuccess(String source, String translated) {
                if (token != sessionGeneration.get()) return;
                long callbackMs = android.os.SystemClock.elapsedRealtime() - translationStartedMs;
                controller.recordTranslationCallbackMs(callbackMs);
                if (isDiagnosticResult(translated)) {
                    Log.w(TAG, "translation diagnostic result ignored");
                    return;
                }
                String cue = composeCue(AiSubtitleSettings.getLanguage(),
                        AiSubtitleSettings.getSubtitleMode(), source, translated);
                Log.i(TAG, "translation completed callbackMs=" + callbackMs
                        + " audioMs=" + segment.audioMs());
                enqueueCue(segment, cue, token);
            }

            @Override
            public void onFailure(String source, String message) {
                if (token != sessionGeneration.get()) return;
                // A bilingual/translated cue cannot be emitted atomically without a translation.
                Log.w(TAG, "translation skipped and cue dropped: " + message);
            }
        });
    }

    static String composeCue(AiLanguage language, AiSubtitleSettings.SubtitleMode mode,
                             String source, String translated) {
        String original = source == null ? "" : source.trim();
        String result = translated == null ? "" : translated.trim();
        if (result.isEmpty()) return original;
        if (language == AiLanguage.MANDARIN
                || mode == AiSubtitleSettings.SubtitleMode.TRANSLATED_ONLY
                || original.equals(result)) return result;
        return result + "\n" + original;
    }

    static boolean isDiagnosticResult(String translated) {
        if (translated == null) return false;
        String normalized = translated.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return normalized.equals("mtranserver测试通过") || normalized.equals("测试通过")
                || normalized.equals("translationtestok");
    }

    /**
     * Maps recognizer sample time to the monotonic clock used by the subtitle scheduler. The PCM
     * tap runs ahead of AudioTrack presentation, so elapsed time is derived from the live
     * written-position headroom reported by Media3's AudioOutput clock.
     */
    static CueWindow planCueWindow(long segmentStartMs, long segmentEndMs, long tapNowMs,
                                   long elapsedNowMs, long lookaheadMs) {
        long startAgeMs = Math.max(0L, tapNowMs - segmentStartMs);
        long endAgeMs = Math.max(0L, tapNowMs - segmentEndMs);
        long rawStartDelayMs = (lookaheadMs - startAgeMs) - CUE_EARLY_MS;
        // Once the spoken start is audibly more than a frame or two in the past, dropping the cue
        // is preferable to showing a correctly translated but visibly late subtitle.
        if (rawStartDelayMs < -MAX_PRESENTATION_ERROR_MS) return null;
        long startDelayMs = Math.max(0L, Math.min(rawStartDelayMs, lookaheadMs));
        long endDelayMs = (lookaheadMs - endAgeMs) + CUE_END_GRACE_MS;
        if ((rawStartDelayMs <= 0L && endDelayMs < 700L) || endDelayMs <= startDelayMs) return null;
        long endElapsedMs = elapsedNowMs + Math.min(endDelayMs, startDelayMs + MAX_CUE_DURATION_MS);
        long startElapsedMs = elapsedNowMs + startDelayMs;
        long visibleMs = endElapsedMs - startElapsedMs;
        long desiredMs = desiredCueDurationMs(segmentStartMs, segmentEndMs);
        long minimumMs = Math.min(desiredMs,
                Math.max(MIN_CUE_VISIBLE_MS, desiredMs * MIN_CUE_DURATION_PERCENT / 100L));
        if (visibleMs < minimumMs) return null;
        return new CueWindow(startElapsedMs, endElapsedMs);
    }

    static long desiredCueDurationMs(long segmentStartMs, long segmentEndMs) {
        long audioMs = Math.max(0L, segmentEndMs - segmentStartMs);
        return Math.min(MAX_CUE_DURATION_MS, audioMs + CUE_EARLY_MS + CUE_END_GRACE_MS);
    }

    @Override
    public void onStatus(String value) {
        status = value;
        Log.i(TAG, "status=" + value);
        if ("实时字幕引擎已就绪".equals(value)) {
            engineReady = true;
            main.post(this::updatePrimingState);
        }
    }

    private void enqueueCue(SherpaSubtitleController.RecognizedSegment segment, String text, long token) {
        if (text == null || text.isBlank()) return;
        main.post(() -> {
            if (token != sessionGeneration.get() || !pipelinePrimed || !sessionActive) return;
            long segmentStartMs = Math.max(0L, (segment.startUs() - timelineAnchorUs) / 1_000L);
            long segmentEndMs = Math.max(segmentStartMs, (segment.endUs() - timelineAnchorUs) / 1_000L);
            long tapNowMs = Math.max(0L, (latestCapturedUs - timelineAnchorUs) / 1_000L);
            long elapsedNowMs = android.os.SystemClock.elapsedRealtime();
            long effectiveLookaheadMs = effectiveLookaheadMs(elapsedNowMs);
            if (effectiveLookaheadMs == C.TIME_UNSET) {
                Log.i(TAG, "subtitle dropped audioClockUnavailable startMs=" + segmentStartMs
                        + " endMs=" + segmentEndMs + " tapMs=" + tapNowMs);
                return;
            }
            CueWindow window = planCueWindow(segmentStartMs, segmentEndMs, tapNowMs,
                    elapsedNowMs, effectiveLookaheadMs);
            if (window == null) {
                Log.i(TAG, "subtitle dropped lateOrShort startMs=" + segmentStartMs
                        + " endMs=" + segmentEndMs + " tapMs=" + tapNowMs
                        + " desiredDurationMs=" + desiredCueDurationMs(segmentStartMs, segmentEndMs)
                        + " ageEndMs=" + Math.max(0L, tapNowMs - segmentEndMs)
                        + " effectiveLookaheadMs=" + effectiveLookaheadMs);
                return;
            }
            pendingCues.add(new ScheduledCue(cueIds.incrementAndGet(), window.startElapsedMs,
                    window.endElapsedMs, segment.startUs(), text.trim()));
            pendingCues.sort(Comparator.comparingLong(ScheduledCue::startElapsedMs));
            serviceReady = true;
            status = "实时字幕已就绪";
            hideStartingStatus();
            Log.i(TAG, "subtitle queued scheduledInMs=" + (window.startElapsedMs - elapsedNowMs)
                    + " durationMs=" + (window.endElapsedMs - window.startElapsedMs)
                    + " desiredDurationMs=" + desiredCueDurationMs(segmentStartMs, segmentEndMs)
                    + " tapMs=" + tapNowMs + " ageEndMs=" + Math.max(0L, tapNowMs - segmentEndMs)
                    + " effectiveLookaheadMs=" + effectiveLookaheadMs
                    + " audioClockAgeMs=" + audioClockAgeMs(elapsedNowMs)
                    + " bilingual=" + text.contains("\n"));
            tickCues();
        });
    }

    private void tickCues() {
        if (!sessionActive) return;
        updatePrimingState();
        long elapsedNowMs = android.os.SystemClock.elapsedRealtime();
        if (currentCue != null && elapsedNowMs >= currentCue.endElapsedMs) {
            currentCue = null;
            setExternalCue(null);
        }
        ScheduledCue ready = null;
        for (int i = pendingCues.size() - 1; i >= 0; i--) {
            ScheduledCue cue = pendingCues.get(i);
            if (elapsedNowMs >= cue.endElapsedMs) {
                pendingCues.remove(i);
                Log.i(TAG, "scheduled subtitle expired id=" + cue.id);
            }
        }
        for (int i = 0; i < pendingCues.size(); i++) {
            ScheduledCue cue = pendingCues.get(i);
            if (cue.startElapsedMs <= elapsedNowMs) ready = cue;
            else break;
        }
        if (ready != null) {
            long readyId = ready.id;
            pendingCues.removeIf(cue -> cue.startElapsedMs <= elapsedNowMs);
            currentCue = ready;
            setExternalCue(ready.text);
            long presentationErrorMs = presentationErrorMs(ready, elapsedNowMs);
            Log.i(TAG, "subtitle shown id=" + readyId + " scheduleErrorMs="
                    + (elapsedNowMs - ready.startElapsedMs)
                    + " presentationErrorMs=" + presentationErrorMs
                    + " audioClockAgeMs=" + audioClockAgeMs(elapsedNowMs)
                    + " bilingual=" + ready.text.contains("\n"));
        }
    }

    private void setExternalCue(String text) {
        PlayerView view = playerView.get();
        if (view == null) return;
        if (text == null || text.isBlank()) {
            view.getSubtitleView().setCues(null);
            return;
        }
        Cue cue = new Cue.Builder()
                .setText(text)
                .setLine(0.90f, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_END)
                .build();
        view.getSubtitleView().setCues(List.of(cue));
    }

    private void resetTimeline() {
        synchronized (timelineLock) {
            capturedTimelineUs = 0L;
            latestCapturedUs = 0L;
        }
        timelineAnchorUs = C.TIME_UNSET;
        mediaAnchorMs = C.TIME_UNSET;
        firstPcmElapsedMs = C.TIME_UNSET;
        observedLookaheadMs = 0L;
        calibratedLookaheadMs = 0L;
        lookaheadStableSinceMs = C.TIME_UNSET;
        clearAudioClock();
        pipelinePrimed = false;
        serviceReady = false;
        timelineAligned = false;
    }

    private long readPlaybackPosition(Player current) {
        return Math.max(0L, current.getCurrentPosition());
    }

    private void clearScheduledCues() {
        long token = sessionGeneration.get();
        main.post(() -> {
            if (token != sessionGeneration.get()) return;
            pendingCues.clear();
            currentCue = null;
            setExternalCue(null);
        });
    }

    private void startTicker() {
        main.removeCallbacks(cueTicker);
        main.post(cueTicker);
    }

    private void updatePrimingState() {
        if (!sessionActive || !AiSubtitleSettings.isEnabled()) {
            hideStartingStatus();
            return;
        }
        if (!serviceReady) showStartingStatus();
        if (!engineReady || timelineAnchorUs == C.TIME_UNSET || firstPcmElapsedMs == C.TIME_UNSET) return;
        long now = android.os.SystemClock.elapsedRealtime();
        long capturedMs = Math.max(0L, (latestCapturedUs - timelineAnchorUs) / 1_000L);
        long audioHeadroomMs = readAudioHeadroomMs(now);
        if (audioHeadroomMs == C.TIME_UNSET) {
            lookaheadStableSinceMs = C.TIME_UNSET;
            return;
        }
        observedLookaheadMs = audioHeadroomMs;
        controller.updateAudioLookaheadMs(audioHeadroomMs);
        if (serviceReady || pipelinePrimed) return;
        if (capturedMs < LOOKAHEAD_MS || observedLookaheadMs < MIN_PRIMED_LOOKAHEAD_MS) {
            lookaheadStableSinceMs = C.TIME_UNSET;
            return;
        }
        if (lookaheadStableSinceMs == C.TIME_UNSET) {
            lookaheadStableSinceMs = now;
            return;
        }
        if (!pipelinePrimed && now - lookaheadStableSinceMs >= LOOKAHEAD_STABLE_MS) {
            calibratedLookaheadMs = observedLookaheadMs;
            pipelinePrimed = true;
            timelineAligned = true;
            status = "实时字幕已对齐，等待首条同步字幕";
            Log.i(TAG, "subtitle pipeline primed generation=" + sessionGeneration.get()
                    + " capturedMs=" + capturedMs + " observedLookaheadMs=" + observedLookaheadMs
                    + " calibratedLookaheadMs=" + calibratedLookaheadMs);
        }
    }

    /** Uses Media3's AudioTrack-backed playout head; buffer capacity is only an upper bound. */
    private long effectiveLookaheadMs(long elapsedNowMs) {
        long measuredMs = readAudioHeadroomMs(elapsedNowMs);
        if (measuredMs == C.TIME_UNSET) return C.TIME_UNSET;
        observedLookaheadMs = measuredMs;
        controller.updateAudioLookaheadMs(measuredMs);
        return measuredMs;
    }

    private void updateAudioClock(long pipelineId, long outputId, long writtenUs, long positionUs,
                                  long sampledElapsedMs, boolean playing) {
        if (pipelineId != latestPipelineId) return;
        if (outputId < latestAudioOutputId) return;
        boolean created = outputId > latestAudioOutputId;
        latestAudioOutputId = outputId;
        audioOutputWrittenUs = Math.max(0L, writtenUs);
        audioOutputPositionUs = Math.max(0L, positionUs);
        audioClockSampleElapsedMs = sampledElapsedMs;
        audioClockPlaying = playing;
        if (created) {
            Log.i(TAG, "audio presentation clock active pipeline=" + pipelineId
                    + " output=" + outputId);
        }
    }

    private void releaseAudioClock(long pipelineId, long outputId) {
        if (pipelineId != latestPipelineId || outputId != latestAudioOutputId) return;
        audioClockSampleElapsedMs = C.TIME_UNSET;
        audioClockPlaying = false;
        Log.i(TAG, "audio presentation clock released pipeline=" + pipelineId
                + " output=" + outputId);
    }

    private long readAudioHeadroomMs(long elapsedNowMs) {
        long sampledAt = audioClockSampleElapsedMs;
        if (sampledAt == C.TIME_UNSET) return C.TIME_UNSET;
        long ageMs = elapsedNowMs - sampledAt;
        if (ageMs < 0L || ageMs > AUDIO_CLOCK_MAX_AGE_MS) return C.TIME_UNSET;
        long writtenUs = audioOutputWrittenUs;
        long positionUs = audioOutputPositionUs;
        if (audioClockPlaying && ageMs > 0L) positionUs += ageMs * 1_000L;
        positionUs = Math.min(writtenUs, Math.max(0L, positionUs));
        return Math.max(0L, writtenUs - positionUs) / 1_000L;
    }

    private long presentationErrorMs(ScheduledCue cue, long elapsedNowMs) {
        long headroomMs = readAudioHeadroomMs(elapsedNowMs);
        // A release/create callback can race this diagnostic read by a few microseconds. The cue
        // itself was already planned from a valid clock, so retain a bounded scheduler error in
        // the log rather than emitting C.TIME_UNSET's sentinel as a misleading huge number.
        if (headroomMs == C.TIME_UNSET) return elapsedNowMs - cue.startElapsedMs;
        long presentedTapUs = latestCapturedUs - headroomMs * 1_000L;
        return (presentedTapUs - cue.segmentStartUs) / 1_000L;
    }

    private long audioClockAgeMs(long elapsedNowMs) {
        long sampledAt = audioClockSampleElapsedMs;
        return sampledAt == C.TIME_UNSET ? C.TIME_UNSET : Math.max(0L, elapsedNowMs - sampledAt);
    }

    private void clearAudioClock() {
        latestAudioOutputId = 0L;
        audioOutputWrittenUs = 0L;
        audioOutputPositionUs = 0L;
        audioClockSampleElapsedMs = C.TIME_UNSET;
        audioClockPlaying = false;
    }

    private void showStartingStatus() {
        runOnMain(() -> {
            TextView view = statusView.get();
            if (view == null) return;
            if (view.getVisibility() != View.VISIBLE) {
                view.setText(R.string.ai_subtitle_starting);
                view.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideStartingStatus() {
        runOnMain(() -> {
            TextView view = statusView.get();
            if (view != null) view.setVisibility(View.GONE);
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run();
        else main.post(action);
    }

    private static TextView findOrCreateStatusView(PlayerView playerView) {
        View existing = playerView.findViewWithTag(STATUS_TAG);
        if (existing instanceof TextView textView) return textView;
        TextView textView = new TextView(playerView.getContext());
        textView.setTag(STATUS_TAG);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        textView.setGravity(Gravity.CENTER);
        textView.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        int horizontal = dp(playerView, 18);
        int vertical = dp(playerView, 8);
        textView.setPadding(horizontal, vertical, horizontal, vertical);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(playerView, 112);
        playerView.addView(textView, params);
        textView.setVisibility(View.GONE);
        return textView;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static String selectedAudioKey(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSelected(i)) continue;
                Format format = group.getTrackFormat(i);
                return group.getMediaTrackGroup().id + "|" + safe(format.id) + "|"
                        + safe(format.sampleMimeType) + "|" + safe(format.language) + "|"
                        + format.channelCount + "|" + format.sampleRate;
            }
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
