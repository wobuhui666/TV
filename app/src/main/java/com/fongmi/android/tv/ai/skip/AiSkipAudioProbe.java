package com.fongmi.android.tv.ai.skip;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ai.subtitle.PcmTapAudioProcessor;
import com.fongmi.android.tv.player.exo.MediaSourceFactory;
import com.fongmi.android.tv.utils.MpvLogCollector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class AiSkipAudioProbe implements Player.Listener {

    interface Callback {
        void onComplete(long durationMs, List<CapturedSample> samples);

        void onFailure(String reason);
    }

    record CapturedSample(String side, long startMs, short[] pcm) {
    }

    private static final int SAMPLE_RATE = 8_000;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE * 30;
    private static final int WINDOW_SAMPLES = CHUNK_SAMPLES * 3;
    private static final int MIN_CAPTURE_SAMPLES = SAMPLE_RATE * 5;
    private static final float PLAYBACK_SPEED = 8f;
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(75);

    private final Object lock = new Object();
    private final Callback callback;
    private final Runnable timeout = () -> fail("timeout");
    private final short[] opening = new short[WINDOW_SAMPLES];
    private final short[] ending = new short[WINDOW_SAMPLES];

    private ExoPlayer player;
    private Phase phase = Phase.OPENING;
    private int openingLength;
    private int endingLength;
    private int inputRate;
    private int inputSamples;
    private int captureTargetSamples = WINDOW_SAMPLES;
    private double resampleCursor;
    private long durationMs = C.TIME_UNSET;
    private long endingStartMs;
    private boolean transitionPosted;
    private volatile boolean released;

    AiSkipAudioProbe(Callback callback) {
        this.callback = callback;
    }

    void start(MediaItem item) {
        App.post(() -> startOnMainThread(item));
    }

    void cancel() {
        App.post(this::release);
    }

    private void startOnMainThread(MediaItem item) {
        if (released) return;
        try {
            DefaultTrackSelector selector = new DefaultTrackSelector(App.get());
            selector.setParameters(selector.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));
            player = new ExoPlayer.Builder(App.get())
                    .setTrackSelector(selector)
                    .setRenderersFactory(buildRenderersFactory())
                    .setMediaSourceFactory(new MediaSourceFactory())
                    .setReleaseTimeoutMs(3_000L)
                    .build();
            player.addListener(this);
            player.setAudioAttributes(AudioAttributes.DEFAULT, false);
            player.setHandleAudioBecomingNoisy(false);
            player.setVolume(0f);
            player.setPlaybackParameters(new PlaybackParameters(PLAYBACK_SPEED));
            player.setMediaItem(item);
            player.prepare();
            player.play();
            App.post(timeout, TIMEOUT_MS);
            MpvLogCollector.log("AiSkip", "启动静音音频探针，速度=" + PLAYBACK_SPEED + "x");
        } catch (Exception e) {
            fail("start_" + errorName(e));
        }
    }

    private DefaultRenderersFactory buildRenderersFactory() {
        return new DefaultRenderersFactory(App.get()) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput,
                                               boolean enableAudioOutputPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(true)
                        .setAudioProcessors(new AudioProcessor[]{new PcmTapAudioProcessor(AiSkipAudioProbe.this::onPcm)})
                        .setAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(null)
                                .setMaxPlaybackSpeed(PLAYBACK_SPEED)
                                .build())
                        .build();
            }

            @Override
            protected void buildAudioRenderers(Context context, int extensionRendererMode,
                                               MediaCodecSelector mediaCodecSelector,
                                               boolean enableDecoderFallback, AudioSink audioSink,
                                               Handler eventHandler,
                                               AudioRendererEventListener eventListener,
                                               ArrayList<Renderer> out) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, true,
                        audioSink, eventHandler, eventListener, out);
            }
        }.setEnableDecoderFallback(true);
    }

    private void onPcm(float[] mono, int sampleRate) {
        if (mono == null || mono.length == 0 || sampleRate <= 0) return;
        boolean advance = false;
        boolean complete = false;
        synchronized (lock) {
            if (released || (phase != Phase.OPENING && phase != Phase.ENDING)) return;
            if (inputRate != sampleRate) resetResampler(sampleRate);
            short[] target = phase == Phase.OPENING ? opening : ending;
            int length = phase == Phase.OPENING ? openingLength : endingLength;
            double step = sampleRate / (double) SAMPLE_RATE;
            for (float value : mono) {
                if (inputSamples >= resampleCursor && length < captureTargetSamples) {
                    target[length++] = toPcm(value);
                    resampleCursor += step;
                }
                inputSamples++;
                if (length >= captureTargetSamples) break;
            }
            if (phase == Phase.OPENING) openingLength = length;
            else endingLength = length;
            if (length >= captureTargetSamples && !transitionPosted) {
                transitionPosted = true;
                advance = phase == Phase.OPENING;
                complete = phase == Phase.ENDING;
                phase = advance ? Phase.SEEKING_ENDING : Phase.FINISHED;
            }
        }
        if (advance) App.post(this::seekToEnding);
        else if (complete) App.post(this::complete);
    }

    private void seekToEnding() {
        if (released || player == null) return;
        durationMs = player.getDuration();
        if (durationMs <= 0) {
            fail("duration_unavailable");
            return;
        }
        if (!player.isCurrentMediaItemSeekable()) {
            fail("not_seekable");
            return;
        }
        captureTargetSamples = captureWindowSamples(durationMs);
        if (captureTargetSamples < MIN_CAPTURE_SAMPLES) {
            fail("duration_too_short");
            return;
        }
        endingStartMs = Math.max(0, durationMs - samplesToMs(captureTargetSamples));
        synchronized (lock) {
            resetResampler(0);
            transitionPosted = false;
        }
        player.seekTo(endingStartMs);
        player.play();
    }

    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                        @NonNull Player.PositionInfo newPosition, int reason) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return;
        synchronized (lock) {
            if (released || phase != Phase.SEEKING_ENDING) return;
            phase = Phase.ENDING;
            resetResampler(0);
        }
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_READY) updateCaptureWindow();
        if (playbackState != Player.STATE_ENDED) return;
        boolean shouldComplete;
        synchronized (lock) {
            if (released || phase == Phase.FINISHED) return;
            shouldComplete = !released && phase == Phase.ENDING && endingLength >= MIN_CAPTURE_SAMPLES;
            if (shouldComplete) phase = Phase.FINISHED;
        }
        if (shouldComplete) complete();
        else fail("ended_before_capture");
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        fail("player_" + error.errorCode);
    }

    private void complete() {
        List<CapturedSample> samples;
        long duration;
        synchronized (lock) {
            if (released || openingLength < MIN_CAPTURE_SAMPLES || endingLength < MIN_CAPTURE_SAMPLES) return;
            samples = new ArrayList<>();
            addChunks(samples, "opening", 0, opening, openingLength);
            addChunks(samples, "ending", endingStartMs, ending, endingLength);
            duration = durationMs;
        }
        MpvLogCollector.log("AiSkip", "音频探针完成，片长=" + duration + "ms，样本=" + samples.size());
        release();
        callback.onComplete(duration, samples);
    }

    private void fail(String reason) {
        if (released) return;
        MpvLogCollector.logError("AiSkip", "音频探针失败: " + reason);
        release();
        callback.onFailure(reason);
    }

    private void release() {
        if (released) return;
        released = true;
        App.removeCallbacks(timeout);
        if (player != null) {
            player.removeListener(this);
            player.release();
            player = null;
        }
    }

    static void addChunks(List<CapturedSample> output, String side, long startMs,
                          short[] pcm, int length) {
        int safeLength = Math.min(pcm.length, Math.max(0, length));
        for (int offset = 0; offset < safeLength; offset += CHUNK_SAMPLES) {
            int chunkLength = Math.min(CHUNK_SAMPLES, safeLength - offset);
            if (chunkLength < MIN_CAPTURE_SAMPLES) break;
            output.add(new CapturedSample(side, startMs + samplesToMs(offset),
                    Arrays.copyOfRange(pcm, offset, offset + chunkLength)));
        }
    }

    private void updateCaptureWindow() {
        if (released || player == null) return;
        long knownDurationMs = player.getDuration();
        if (knownDurationMs <= 0) return;
        boolean advance = false;
        boolean tooShort = false;
        synchronized (lock) {
            durationMs = knownDurationMs;
            if (phase != Phase.OPENING) return;
            captureTargetSamples = captureWindowSamples(durationMs);
            if (captureTargetSamples < MIN_CAPTURE_SAMPLES) {
                tooShort = true;
                phase = Phase.FINISHED;
            } else if (openingLength >= captureTargetSamples && !transitionPosted) {
                transitionPosted = true;
                phase = Phase.SEEKING_ENDING;
                advance = true;
            }
        }
        if (tooShort) fail("duration_too_short");
        else if (advance) App.post(this::seekToEnding);
    }

    static int captureWindowSamples(long durationMs) {
        if (durationMs <= 0) return WINDOW_SAMPLES;
        return (int) Math.min(WINDOW_SAMPLES, durationMs * SAMPLE_RATE / 2_000L);
    }

    private void resetResampler(int sampleRate) {
        inputRate = sampleRate;
        inputSamples = 0;
        resampleCursor = 0;
    }

    private static short toPcm(float value) {
        return (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(value * 32767f)));
    }

    private static long samplesToMs(int samples) {
        return samples * 1000L / SAMPLE_RATE;
    }

    private static String errorName(Exception error) {
        String name = error.getClass().getSimpleName();
        return name.isEmpty() ? "unknown" : name;
    }

    private enum Phase {
        OPENING,
        SEEKING_ENDING,
        ENDING,
        FINISHED
    }
}
