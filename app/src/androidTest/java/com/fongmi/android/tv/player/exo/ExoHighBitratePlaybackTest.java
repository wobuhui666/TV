package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.ui.danmaku.Danmaku;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.media3.ui.danmaku.DanmakuView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.AdvancedSubtitleSetting;
import com.fongmi.android.tv.test.CoreFixtureServer;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Real local-file/HTTP playback comparisons. Performance results are measurements, not device claims. */
@RunWith(AndroidJUnit4.class)
public final class ExoHighBitratePlaybackTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private CorePlaybackActivity activity;
    private final JSONArray cases = new JSONArray();
    private File fixtureRoot;

    @Test(timeout = 1_500_000)
    public void measureSameMediaAcrossPipelineSurfaceCodecAndDanmakuModes() throws Exception {
        fixtureRoot = new File(instrumentation.getTargetContext().getFilesDir(), "core-fixtures");
        JSONObject manifest = new JSONObject(Files.readString(new File(fixtureRoot, "manifest.json").toPath()));
        assertEquals(4, manifest.getJSONArray("files").length());
        CoreFixtureServer.ensureStarted();
        activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Map<String, ?> saved = Prefers.getPrefers().getAll();
        JSONObject report = new JSONObject();
        report.put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL).put("hardware", Build.HARDWARE)
                .put("fixtureManifest", manifest).put("cases", cases)
                .put("scope", "API emulator; platform codecs and SwiftShader; no MT5873 hardware or audible output")
                .put("reference", "DefaultRenderersFactory and default source using the same pinned Media3; not another installed app");
        try {
            main(() -> {
                Prefers.put("player_engine", 0); Prefers.put("tunnel", false);
                Prefers.put("audio_pass_through", false); Prefers.put("audio_prefer", false);
                Prefers.put("video_prefer", false); Prefers.put("preload", false); Prefers.put("preload_next", false);
                Prefers.put("ai_subtitle_enabled", false); Prefers.put("audio_effect_preset", 0);
                activity.view.setDanmakuEnabled(false);
            });
            for (String file : new String[]{"baseline1080p.mp4", "high1080p.mp4", "high2160p.mp4"}) {
                for (int repeat = 0; repeat < 2; repeat++) {
                    // Reverse pair order to avoid consistently attributing warm-up to the reference.
                    String[] pair = repeat == 0 ? new String[]{"reference", "production"} : new String[]{"production", "reference"};
                    for (String mode : pair) runCase(file, mode, false, false, "none", repeat);
                }
            }
            runCase("high1080p.mp4", "production", true, false, "none", 0);
            runCase("high1080p.mp4", "async_reference", false, false, "none", 0);
            for (int repeat = 0; repeat < 2; repeat++) {
                String[] pair = repeat == 0 ? new String[]{"patched", "legacy"} : new String[]{"legacy", "patched"};
                for (String overlay : pair) runCase("high1080p.mp4", "production", false, false, overlay, repeat);
            }
            runCase("high1080p-ass.mkv", "production", false, false, "none", 0);
            runCase("high1080p-ass.mkv", "production", false, true, "none", 0);
            int referenceControls = 0;
            for (int i = 0; i < cases.length(); i++) {
                JSONObject result = cases.getJSONObject(i);
                if (result.getString("file").equals("baseline1080p.mp4") && result.getString("pipeline").equals("reference")) {
                    assertTrue("1080p control must decode real frames: " + result, result.getInt("submittedFrames") > 20);
                    assertTrue("1080p control clock must advance: " + result, result.getLong("positionAdvanceMs") > 500);
                    referenceControls++;
                }
            }
            assertEquals(2, referenceControls);
            assertTrue("Native ASS case must actually select a text track",
                    cases.getJSONObject(cases.length() - 1).getBoolean("selectedText"));
            report.put("controlsPassed", true);
        } finally {
            main(() -> {
                activity.view.setPlayer(null);
                activity.finish();
                var editor = Prefers.getPrefers().edit().clear();
                for (var entry : saved.entrySet()) {
                    Object value = entry.getValue(); String key = entry.getKey();
                    if (value instanceof Boolean v) editor.putBoolean(key, v);
                    else if (value instanceof Integer v) editor.putInt(key, v);
                    else if (value instanceof Long v) editor.putLong(key, v);
                    else if (value instanceof Float v) editor.putFloat(key, v);
                    else if (value instanceof String v) editor.putString(key, v);
                }
                editor.apply();
            });
            CoreFixtureServer.stop();
            File out = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "playback-stutter-evidence");
            assertTrue(out.isDirectory() || out.mkdirs());
            Files.writeString(new File(out, "playback-summary.json").toPath(), report.toString(2), StandardCharsets.UTF_8);
        }
    }

    private void runCase(String file, String mode, boolean texture, boolean nativeAss, String danmaku, int repeat) throws Exception {
        Metrics metrics = new Metrics();
        ExoPlayer[] player = new ExoPlayer[1];
        ExoPlayerEngine[] engine = new ExoPlayerEngine[1];
        View[] overlay = new View[1];
        long beforeGc = runtimeStat("art.gc.gc-count"), beforeGcTime = runtimeStat("art.gc.gc-time");
        long started = SystemClock.elapsedRealtime();
        boolean ready = false;
        try {
            main(() -> {
                if (nativeAss) assertTrue("Pinned native libass must be available for this comparison",
                        com.fongmi.android.tv.player.subtitle.AssTextRenderer.available());
                AdvancedSubtitleSetting.ass(nativeAss);
                activity.view.setRender(texture ? 1 : 0);
                if (mode.equals("production")) {
                    engine[0] = new ExoPlayerEngine(1, metrics);
                    player[0] = (ExoPlayer) engine[0].getPlayer();
                    activity.view.setPlayer(player[0]);
                    engine[0].bindPlayerView(activity.view);
                } else {
                    DefaultRenderersFactory renderers = new DefaultRenderersFactory(activity);
                    if (mode.equals("async_reference")) renderers.forceEnableMediaCodecAsynchronousQueueing();
                    var upstream = new DefaultDataSource.Factory(activity, new OkHttpDataSource.Factory(
                            new okhttp3.OkHttpClient()).setDefaultRequestProperties(Map.of("X-Core-Media", file)));
                    player[0] = new ExoPlayer.Builder(activity).setRenderersFactory(renderers)
                            .setMediaSourceFactory(new DefaultMediaSourceFactory(upstream)).build();
                    player[0].addListener(metrics);
                    activity.view.setPlayer(player[0]);
                }
                player[0].setVolume(1f); // Exercise normal AudioTrack writes; emulator host still uses -noaudio.
                player[0].addAnalyticsListener(metrics);
                player[0].setVideoFrameMetadataListener((pts, release, format, codecFormat) -> metrics.frame());
                if (engine[0] != null) engine[0].start(PlaySpec.from("stutter-" + file,
                        CoreFixtureServer.baseUrl() + file, Map.of("X-Core-Media", file), MediaMetadata.EMPTY), 0);
                else { player[0].setMediaItem(MediaItem.fromUri(CoreFixtureServer.baseUrl() + file)); player[0].prepare(); player[0].play(); }
                if (nativeAss) player[0].setTrackSelectionParameters(player[0].getTrackSelectionParameters()
                        .buildUpon().setPreferredTextLanguage("en").build());
            });
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            while (SystemClock.elapsedRealtime() < deadline && metrics.error == null) {
                boolean[] state = new boolean[1];
                main(() -> state[0] = player[0].getPlaybackState() == Player.STATE_READY && metrics.frames.get() > 0);
                if (state[0]) { ready = true; break; }
                SystemClock.sleep(100);
            }
            long preparationMs = SystemClock.elapsedRealtime() - started;
            long overlayPreparationMs = 0;
            if (!danmaku.equals("none") && ready) {
                long overlayStarted = SystemClock.elapsedRealtime();
                main(() -> {
                    try { overlay[0] = addDanmaku(danmaku); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                awaitDanmaku(overlay[0]);
                overlayPreparationMs = SystemClock.elapsedRealtime() - overlayStarted;
                // Pool setup is not part of the steady playback window. Restart media afterward.
                main(() -> player[0].seekTo(0));
                long restartDeadline = SystemClock.elapsedRealtime() + 10_000;
                boolean[] restarted = new boolean[1];
                while (SystemClock.elapsedRealtime() < restartDeadline && metrics.error == null) {
                    main(() -> restarted[0] = player[0].getPlaybackState() == Player.STATE_READY
                            && player[0].getCurrentPosition() >= 500 && player[0].getCurrentPosition() < 5_000);
                    if (restarted[0]) break;
                    SystemClock.sleep(100);
                }
                assertTrue("Comparison playback must restart after pool preparation", restarted[0]);
            }
            long[] position = new long[2], buffers = new long[]{Long.MAX_VALUE, 0};
            main(() -> position[0] = player[0].getCurrentPosition());
            int[] initialCounters = new int[3];
            main(() -> {
                metrics.resetWindow();
                DecoderCounters counters = player[0].getVideoDecoderCounters();
                if (counters != null) {
                    counters.ensureUpdated();
                    initialCounters[0] = counters.renderedOutputBufferCount;
                    initialCounters[1] = counters.droppedBufferCount;
                    initialCounters[2] = counters.skippedOutputBufferCount;
                }
            });
            beforeGc = runtimeStat("art.gc.gc-count"); beforeGcTime = runtimeStat("art.gc.gc-time");
            UiFrames ui = new UiFrames();
            main(ui::start);
            long window = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - window < 10_000 && metrics.error == null) {
                main(() -> {
                    buffers[0] = Math.min(buffers[0], player[0].getTotalBufferedDuration());
                    buffers[1] = player[0].getCurrentPosition();
                });
                SystemClock.sleep(250);
            }
            main(() -> { ui.stop(); position[1] = player[0].getCurrentPosition(); });
            JSONObject result = new JSONObject().put("file", file).put("pipeline", mode).put("surface", texture ? "texture" : "surface")
                    .put("nativeAss", nativeAss).put("danmaku", danmaku).put("repeat", repeat).put("ready", ready)
                    .put("status", metrics.error != null ? "decode_error" : ready ? "measured" : "not_ready")
                    .put("overlayPreparationMs", overlayPreparationMs)
                    .put("preparationMs", preparationMs).put("wallWindowMs", SystemClock.elapsedRealtime() - window)
                    .put("positionAdvanceMs", position[1] - position[0])
                    .put("minMediaBufferedMs", buffers[0] == Long.MAX_VALUE ? JSONObject.NULL : buffers[0])
                    .put("submittedFrames", metrics.frames.get()).put("maxFrameSubmitGapMs", metrics.maxGapNs.get() / 1_000_000d)
                    .put("audioUnderruns", metrics.underruns).put("maxAudioFeedGapMs", metrics.maxAudioFeedGapMs)
                    .put("bufferingEvents", metrics.buffering).put("droppedFrameEvents", metrics.dropped)
                    .put("videoDecoder", metrics.videoDecoder).put("audioDecoder", metrics.audioDecoder)
                    .put("videoWidth", metrics.width).put("videoHeight", metrics.height).put("videoFps", metrics.fps)
                    .put("gcCountDelta", runtimeStat("art.gc.gc-count") - beforeGc)
                    .put("gcTimeDelta", runtimeStat("art.gc.gc-time") - beforeGcTime)
                    .put("javaUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                    .put("uiFrames", ui.frames).put("uiOver50ms", ui.over50).put("uiMaxGapMs", ui.maxGapNs / 1_000_000d)
                    .put("error", metrics.error == null ? JSONObject.NULL : metrics.error.getErrorCodeName());
            main(() -> {
                try {
                    DecoderCounters counters = player[0].getVideoDecoderCounters();
                    if (counters != null) {
                        counters.ensureUpdated();
                        result.put("decoderRenderedBuffers", counters.renderedOutputBufferCount - initialCounters[0])
                                .put("decoderDroppedBuffers", counters.droppedBufferCount - initialCounters[1])
                                .put("decoderSkippedBuffers", counters.skippedOutputBufferCount - initialCounters[2]);
                    }
                    result.put("selectedText", player[0].getCurrentTracks().isTypeSelected(C.TRACK_TYPE_TEXT));
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            cases.put(result);
            android.util.Log.i("HighBitrateMetrics", result.toString());
        } finally {
            main(() -> {
                if (overlay[0] != null) {
                    try { overlay[0].getClass().getMethod("release").invoke(overlay[0]); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                    ((ViewGroup) overlay[0].getParent()).removeView(overlay[0]);
                }
                activity.view.setPlayer(null);
                if (engine[0] != null) engine[0].release();
                else if (player[0] != null) player[0].release();
            });
            SystemClock.sleep(500);
        }
    }

    private View addDanmaku(String mode) throws Exception {
        Class<?> type = mode.equals("legacy") ? Class.forName("androidx.media3.ui.danmaku.LegacyDanmakuView") : DanmakuView.class;
        View view = (View) type.getConstructor(android.content.Context.class).newInstance(activity);
        ViewGroup root = (ViewGroup) activity.view.getParent();
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        DanmakuConfig config = new DanmakuConfig.Builder().setTextSizeSp(18).setMaxOnScreen(150)
                .setAllowScrollOverlap(true).build();
        type.getMethod("setConfig", DanmakuConfig.class).invoke(view, config);
        Method add = type.getMethod("addItems", List.class);
        for (int chunk = 0; chunk < 80; chunk++) {
            List<Danmaku> items = new ArrayList<>(1_200);
            for (int i = 0; i < 1_200; i++) items.add(new Danmaku("Synthetic comment " + (i % 200), chunk * 90_000L + i * 100L));
            add.invoke(view, items);
        }
        return view;
    }

    private void awaitDanmaku(View view) throws Exception {
        var field = view.getClass().getDeclaredField("renderPool");
        field.setAccessible(true);
        Object pool = field.get(view);
        Method size = pool.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        boolean[] layout = new boolean[1];
        while (SystemClock.elapsedRealtime() < deadline) {
            main(() -> layout[0] = view.getWidth() > 0 && view.getHeight() > 0);
            if ((int) size.invoke(pool) == 96_000 && layout[0]) break;
            SystemClock.sleep(100);
        }
        assertEquals("Both legacy and patched pools must contain the same history", 96_000, size.invoke(pool));
        assertTrue("Danmaku overlay must be laid out before measurement", layout[0]);
        main(() -> {
            try { view.getClass().getMethod("start", long.class).invoke(view, 79 * 90_000L + 60_000L); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    private static long runtimeStat(String name) {
        try { return Long.parseLong(Debug.getRuntimeStat(name)); }
        catch (RuntimeException error) { return -1; }
    }

    private void main(Runnable job) { instrumentation.runOnMainSync(job); }

    private static final class Metrics implements Player.Listener, AnalyticsListener {
        volatile PlaybackException error;
        String videoDecoder = "unknown", audioDecoder = "unknown";
        int width, height, dropped, buffering, underruns;
        float fps;
        long maxAudioFeedGapMs;
        final AtomicInteger frames = new AtomicInteger();
        final AtomicLong lastFrameNs = new AtomicLong(), maxGapNs = new AtomicLong();
        void frame() {
            long now = System.nanoTime(), last = lastFrameNs.getAndSet(now);
            if (last > 0) maxGapNs.accumulateAndGet(now - last, Math::max);
            frames.incrementAndGet();
        }
        void resetWindow() { frames.set(0); lastFrameNs.set(0); maxGapNs.set(0); dropped = buffering = underruns = 0; maxAudioFeedGapMs = 0; }
        @Override public void onPlayerError(PlaybackException failure) { error = failure; }
        @Override public void onPlaybackStateChanged(int state) { if (state == Player.STATE_BUFFERING) buffering++; }
        @Override public void onVideoDecoderInitialized(EventTime time, String name, long when, long duration) { videoDecoder = name; }
        @Override public void onAudioDecoderInitialized(EventTime time, String name, long when, long duration) { audioDecoder = name; }
        @Override public void onVideoInputFormatChanged(EventTime time, Format format, DecoderReuseEvaluation reuse) {
            width = format.width; height = format.height; fps = format.frameRate;
        }
        @Override public void onDroppedVideoFrames(EventTime time, int count, long elapsed) { dropped += count; }
        @Override public void onAudioUnderrun(EventTime time, int bytes, long duration, long elapsed) { underruns++; maxAudioFeedGapMs = Math.max(maxAudioFeedGapMs, elapsed); }
    }

    private static final class UiFrames implements Choreographer.FrameCallback {
        boolean running; int frames, over50; long previous, maxGapNs;
        void start() { running = true; Choreographer.getInstance().postFrameCallback(this); }
        void stop() { running = false; Choreographer.getInstance().removeFrameCallback(this); }
        @Override public void doFrame(long now) {
            if (!running) return;
            if (previous > 0) { long delta = now - previous; maxGapNs = Math.max(maxGapNs, delta); if (delta > 50_000_000) over50++; }
            previous = now; frames++; Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
