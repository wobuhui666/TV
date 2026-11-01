package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import is.xyz.mpv.MPVLib;
import is.xyz.mpv.MPVNode;

/** Local fixture only. Injects real incompatible native options, never fabricated logs or state. */
@RunWith(AndroidJUnit4.class)
public final class MpvVideoOutputRecoveryIntegrationTest {
    private static final Map<String, Object> SETTINGS = Map.ofEntries(
            Map.entry("player_engine", PlayerSetting.ENGINE_MPV), Map.entry("mpv_gpu_next", true),
            Map.entry("mpv_vulkan", true), Map.entry("mpv_hdr", 2), Map.entry("mpv_anime4k", 0),
            Map.entry("preload", false), Map.entry("preload_next", false), Map.entry("tunnel", false),
            Map.entry("audio_effect_preset", 0), Map.entry("audio_pass_through", false),
            Map.entry("ai_subtitle_enabled", false), Map.entry("community_skip_enabled", false));
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "TV-VO-Recovery-Fixture", "X-Recovery-Fixture", "local-only");
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final AtomicInteger contextErrors = new AtomicInteger();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicInteger errorCode = new AtomicInteger();
    private final JSONArray recoveries = new JSONArray();
    private final JSONObject report = new JSONObject();
    private CorePlaybackActivity activity;
    private PlayerManager manager;
    private Map<String, ?> saved;
    private Uri mediaUri;
    private String stage = "setup";

    private final MPVLib.LogObserver logObserver = (prefix, level, text) -> {
        if (text != null && (text.contains("Failed initializing any suitable GPU context")
                || text.contains("Error opening/initializing vo window")
                || text.contains("Error opening/initializing the VO window"))) contextErrors.incrementAndGet();
    };
    private final MPVLib.EventObserver observer = new MPVLib.EventObserver() {
        @Override public void eventProperty(String property) { }
        @Override public void eventProperty(String property, long value) { }
        @Override public void eventProperty(String property, boolean value) { }
        @Override public void eventProperty(String property, String value) { }
        @Override public void eventProperty(String property, double value) { }
        @Override public void eventProperty(String property, MPVNode value) { }
        @Override public void event(int id, MPVNode value) {
            if (id == MPVLib.MpvEvent.MPV_EVENT_START_FILE) starts.incrementAndGet();
        }
    };

    @Test(timeout = 180000) public void realNativeContextFailuresRecoverAndExhaustWithoutLosingPlaybackState() throws Exception {
        // This guard precedes preferences, files, surfaces, and native-player access.
        assertEquals("Disposable sourceprobe only", "com.fongmi.android.tv.sourceprobe", target.getPackageName());
        assertTrue("PixelCopy coverage requires Android 7 or later", Build.VERSION.SDK_INT >= 24);
        File directory = target.getFilesDir().getCanonicalFile();
        String mediaName = InstrumentationRegistry.getArguments().getString("mpv_recovery_media_file", "mpv-recovery-fixture.mp4");
        assertTrue("Use a private fixture filename only", mediaName.matches("[A-Za-z0-9._-]{1,120}\\.mp4"));
        File media = new File(directory, mediaName).getCanonicalFile();
        assertTrue("Expected private local MP4 fixture", media.getParentFile().equals(directory)
                && media.isFile() && media.length() > 0 && media.length() <= 16 * 1024 * 1024);
        assertTrue("Native MPV must be available", MpvPlayerEngine.isAvailable());
        mediaUri = Uri.fromFile(media);
        report.put("scope", "SOURCEPROBE_LOCAL_NATIVE_VO_RECOVERY").put("externalNetwork", false)
                .put("recoveries", recoveries).put("passed", false);
        long started = SystemClock.elapsedRealtime();
        try {
            saved = new HashMap<>(Prefers.getPrefers().getAll());
            SETTINGS.forEach(Prefers::put);
            MPVLib.addLogObserver(logObserver); MPVLib.addObserver(observer);
            activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(target, CorePlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> {
                manager = new PlayerManager(new PlayerManager.Callback() {
                    @Override public void onPrepare() { }
                    @Override public void onTracksChanged() { }
                    @Override public void onDecodeChanged() { }
                    @Override public void onMediaOptionsChanged() { }
                    @Override public void onError(String message) { }
                    @Override public void onPlayerRebuild(Player player) { errorCode.set(-1); }
                    @Override public void onDanmakuSourceChanged(Uri uri) { }
                    @Override public void onDanmakuConfigChanged(DanmakuConfig config) { }
                    @Override public void onDanmakuEnabledChanged(boolean enabled) { }
                    @Override public void onDanmakuSent(String text) { }
                });
                assertTrue("PlayerManager must use actual MPV", manager.getPlayer() instanceof MpvPlayer);
                activity.view.setPlayer(manager.getPlayer()); manager.bindPlayerView(activity.view);
                manager.getPlayer().addListener(new Player.Listener() {
                    @Override public void onRenderedFirstFrame() { frames.incrementAndGet(); }
                    @Override public void onPlayerError(PlaybackException error) { errorCode.set(error.errorCode); }
                });
                manager.start(PlaySpec.from("vo-recovery-fixture", mediaUri.toString(), HEADERS,
                        PlayerManager.buildMetadata("VO recovery fixture", "", null)), 20000, 8000);
                manager.getPlayer().pause(); manager.getPlayer().setVolume(.17f);
                manager.getPlayer().setPlaybackParameters(new PlaybackParameters(1.25f));
            });
            stage = "initial-vulkan-or-natural-fallback";
            await(() -> ready() && frames.get() > 0 && Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("pause"))
                    && Math.abs(number("time-pos") * 1000 - 8000) <= 750, 25000, true);
            assertFalse(value(() -> manager.getPlayer().getPlayWhenReady()));
            assertEquals(1.25, number("speed"), .005);
            assertEquals(17, number("volume"), .6);
            assertEquals(mediaUri, value(() -> manager.getPlayer().getCurrentMediaItem().localConfiguration.uri));
            assertTrue("Native player must still use the fixture", sameNativeMedia());
            assertEquals(HEADERS.get("User-Agent"), property("user-agent"));
            assertTrue(property("http-header-fields").contains("local-only"));
            report.put("initialNativeVo", property("current-vo")).put("initialNativeContext", property("current-gpu-context"))
                    .put("naturalFallback", "android".equals(property("current-gpu-context")))
                    .put("initialContextErrorCount", contextErrors.get()).put("initialStatePreserved", true);
            assertTrue("Expected gpu-next or a genuine natural fallback", "gpu-next".equals(property("current-vo"))
                    || "gpu".equals(property("current-vo")) && contextErrors.get() > 0);
            captureFrame(report);

            // At most two recoveries: Vulkan/gpu-next -> GL/gpu-next -> GL/gpu.
            for (int round = 0; round < 2 && !"gpu".equals(property("current-vo")); round++) {
                stage = "recover-" + round;
                main(() -> {
                    manager.getPlayer().pause(); manager.getPlayer().seekTo(8000);
                    manager.getPlayer().setPlaybackParameters(new PlaybackParameters(1.25f));
                    manager.getPlayer().setVolume(.17f);
                });
                await(() -> ready() && Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("pause"))
                        && Math.abs(number("time-pos") * 1000 - 8000) <= 750, 12000, true);
                long beforePosition = value(() -> manager.getPlayer().getCurrentPosition());
                String beforeId = value(() -> manager.getPlayer().getCurrentMediaItem().mediaId);
                String oldVo = property("current-vo");
                boolean wasGl = "android".equals(property("current-gpu-context"));
                int oldErrors = contextErrors.get(), oldFrames = frames.get();
                int result = injectNativeMismatch();
                String expectedVo = wasGl ? "gpu" : "gpu-next";
                await(() -> contextErrors.get() > oldErrors && ready()
                        && "android".equals(property("current-gpu-context"))
                        && expectedVo.equals(property("current-vo")) && frames.get() > oldFrames, 20000, true);
                assertFalse("Recovery must preserve paused state", value(() -> manager.getPlayer().getPlayWhenReady()));
                assertEquals(Boolean.TRUE, MPVLib.INSTANCE.getPropertyBoolean("pause"));
                assertEquals(beforePosition, (long) value(() -> manager.getPlayer().getCurrentPosition()), 750);
                assertEquals(beforePosition / 1000.0, number("time-pos"), .75);
                assertEquals(1.25, number("speed"), .005);
                assertEquals(17, number("volume"), .6);
                assertEquals(1.25f, value(() -> manager.getPlayer().getPlaybackParameters().speed), .005f);
                assertEquals(.17f, value(() -> manager.getPlayer().getVolume()), .006f);
                assertEquals(beforeId, value(() -> manager.getPlayer().getCurrentMediaItem().mediaId));
                assertEquals(mediaUri, value(() -> manager.getPlayer().getCurrentMediaItem().localConfiguration.uri));
                assertEquals(mediaUri.toString(), value(() -> manager.getUrl()));
                assertTrue("Recovery must reload the same native fixture", sameNativeMedia());
                assertEquals(HEADERS.get("User-Agent"), property("user-agent"));
                assertTrue("Native extra request header must survive recovery", property("http-header-fields").contains("local-only"));
                JSONObject recovery = new JSONObject().put("fromVo", oldVo).put("toVo", property("current-vo"))
                        .put("nativeContext", property("current-gpu-context")).put("injectionReturnCode", result)
                        .put("realContextErrors", contextErrors.get() - oldErrors).put("statePreserved", true)
                        .put("sameMediaAndHeaders", true).put("newRenderedFrameEvents", frames.get() - oldFrames);
                long pausePosition = value(() -> manager.getPlayer().getCurrentPosition());
                SystemClock.sleep(350);
                assertEquals(pausePosition, (long) value(() -> manager.getPlayer().getCurrentPosition()), 150);
                captureFrame(recovery);
                main(() -> manager.getPlayer().play());
                await(() -> value(() -> manager.getPlayer().getCurrentPosition()) > pausePosition + 650, 8000, true);
                captureFrame(recovery);
                recovery.put("actualPlaybackClockAdvanced", true); recoveries.put(recovery);
            }
            assertEquals("Recovery must settle on bounded GL/gpu fallback", "gpu", property("current-vo"));
            assertEquals("android", property("current-gpu-context"));
            stage = "bounded-exhaustion";
            main(() -> manager.getPlayer().pause());
            int beforeFatalErrors = contextErrors.get();
            injectNativeMismatch();
            await(() -> contextErrors.get() > beforeFatalErrors
                    && errorCode.get() == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED, 15000, false);
            int loadsAfterFatal = starts.get();
            SystemClock.sleep(1500);
            assertEquals("Exhausted recovery must not keep loading or switch engines", loadsAfterFatal, starts.get());
            assertNotNull("Late native events must not clear the renderer failure", value(() -> manager.getPlayer().getPlayerError()));
            assertEquals(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
                    (int) value(() -> manager.getPlayer().getPlayerError().errorCode));
            assertNotEquals("Late events must not restore READY", Player.STATE_READY,
                    (int) value(() -> manager.getPlayer().getPlaybackState()));
            assertTrue(value(() -> manager.getPlayer() instanceof MpvPlayer));
            assertTrue("Keep requested renderer preferences", Prefers.getBoolean("mpv_gpu_next", false)
                    && Prefers.getBoolean("mpv_vulkan", false));
            report.put("boundedExhaustion", true).put("terminalErrorCode", errorCode.get())
                    .put("nativeStartFileEvents", starts.get()).put("checksPassed", true);
        } finally {
            if (!report.optBoolean("checksPassed")) captureFailureDiagnostics();
            try {
                main(() -> {
                    MPVLib.removeLogObserver(logObserver); MPVLib.removeObserver(observer);
                    if (manager != null) {
                        manager.bindPlayerView(null);
                        if (activity != null) activity.view.setPlayer(null);
                        manager.release();
                    }
                    if (activity != null) activity.finish();
                });
                long end = SystemClock.elapsedRealtime() + 15000;
                while (!MpvPlayerEngine.isAvailable() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
                report.put("nativePlayerReleased", MpvPlayerEngine.isAvailable());
                assertTrue("Native player release timed out", MpvPlayerEngine.isAvailable());
            } finally {
                try { restorePreferences(); }
                finally {
                    report.put("lastStage", stage).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                            .put("passed", report.optBoolean("checksPassed") && report.optBoolean("nativePlayerReleased")
                                    && report.optBoolean("preferencesRestored"));
                    File output = new File(target.getFilesDir(), "mpv-video-output-recovery-result.json");
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        stream.write((report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                    output.setReadable(false, false); output.setWritable(false, false);
                    output.setReadable(true, true); output.setWritable(true, true);
                }
            }
        }
    }

    /** Read-only, allowlisted state captured before release; diagnostic failures never skip cleanup. */
    private void captureFailureDiagnostics() {
        try {
            JSONObject snapshot = new JSONObject();
            report.put("failureDiagnostics", snapshot);
            snapshot.put("stage", stage).put("contextErrorEvents", contextErrors.get())
                    .put("startFileEvents", starts.get()).put("renderedFrameEvents", frames.get())
                    .put("observedPlayerErrorCode", errorCode.get());
            if (manager != null) {
                for (String name : new String[]{"current-vo", "gpu-api", "gpu-context", "current-gpu-context"}) {
                    diagnostic(snapshot, "native_" + name, () -> {
                        String text = MPVLib.INSTANCE.getPropertyString(name);
                        return text != null && text.matches("[A-Za-z0-9_.+-]{0,64}") ? text : JSONObject.NULL;
                    });
                }
                for (String name : new String[]{"vo-configured", "pause", "seeking", "idle-active"})
                    diagnostic(snapshot, "native_" + name, () -> MPVLib.INSTANCE.getPropertyBoolean(name));
                for (String name : new String[]{"time-pos", "speed", "volume"})
                    diagnostic(snapshot, "native_" + name, () -> MPVLib.INSTANCE.getPropertyDouble(name));
                diagnostic(snapshot, "media3", () -> value(() -> {
                    JSONObject playerState = new JSONObject();
                    Player player = manager.getPlayer();
                    if (player == null) return playerState;
                    diagnostic(playerState, "state", player::getPlaybackState);
                    diagnostic(playerState, "errorCode", () -> player.getPlayerError() == null ? 0 : player.getPlayerError().errorCode);
                    diagnostic(playerState, "positionMs", player::getCurrentPosition);
                    diagnostic(playerState, "durationMs", player::getDuration);
                    diagnostic(playerState, "playWhenReady", player::getPlayWhenReady);
                    diagnostic(playerState, "loading", player::isLoading);
                    diagnostic(playerState, "speed", () -> player.getPlaybackParameters().speed);
                    diagnostic(playerState, "volume", player::getVolume);
                    diagnostic(playerState, "videoWidth", () -> player.getVideoSize().width);
                    diagnostic(playerState, "videoHeight", () -> player.getVideoSize().height);
                    for (String name : new String[]{"pendingStartPositionMs", "pendingSeekAfterLoadMs", "outputReloadPending",
                            "awaitingOutputStart", "fileLoaded", "loading"}) {
                        try {
                            java.lang.reflect.Field field = player.getClass().getDeclaredField(name);
                            field.setAccessible(true);
                            Object fieldValue = field.get(player);
                            if (fieldValue instanceof Number || fieldValue instanceof Boolean) playerState.put(name, fieldValue);
                        } catch (Throwable ignored) { }
                    }
                    try {
                        java.lang.reflect.Field pending = player.getClass().getDeclaredField("pendingUrl");
                        pending.setAccessible(true);
                        Object address = pending.get(player);
                        playerState.put("hasPendingUrl", address instanceof String && !((String) address).isEmpty());
                    } catch (Throwable ignored) { }
                    return playerState;
                }));
            }
            // Retain the snapshot even if native release later stalls; no private strings are included.
            File output = new File(target.getFilesDir(), "mpv-video-output-recovery-result.json");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                stream.write((report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            output.setReadable(false, false); output.setWritable(false, false);
            output.setReadable(true, true); output.setWritable(true, true);
        } catch (Throwable ignored) { }
    }

    private static void diagnostic(JSONObject object, String key, Supplier<?> read) {
        try {
            Object value = read.get();
            if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                    || value instanceof Float floatValue && !Float.isFinite(floatValue)) value = null;
            object.put(key, value == null ? JSONObject.NULL : value);
        } catch (Throwable ignored) {
            try { object.put(key, JSONObject.NULL); } catch (Throwable ignoredAgain) { }
        }
    }

    private int injectNativeMismatch() {
        // These are real libmpv updates. No calls to logMessage(), recovery methods, or fake events.
        if ("android".equals(property("current-gpu-context")))
            return MPVLib.INSTANCE.setOptionString("gpu-api", "vulkan");
        assertEquals("Initial injected path must actually use Vulkan", "vulkan", property("gpu-api"));
        return MPVLib.INSTANCE.setOptionString("gpu-context", "android");
    }

    private boolean ready() {
        return Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("vo-configured"))
                && value(() -> manager.getPlayer().getPlaybackState() == Player.STATE_READY
                && manager.getPlayer().getVideoSize().width > 0 && manager.getPlayer().getVideoSize().height > 0);
    }

    private String property(String name) {
        String result = MPVLib.INSTANCE.getPropertyString(name);
        return result == null ? "" : result;
    }

    private boolean sameNativeMedia() {
        String path = property("path");
        return mediaUri.toString().equals(path) || mediaUri.getPath().equals(path);
    }

    private double number(String name) {
        Double result = MPVLib.INSTANCE.getPropertyDouble(name);
        return result == null ? Double.NaN : result;
    }

    private void captureFrame(JSONObject record) throws Exception {
        View surface = value(() -> activity.view.getVideoSurfaceView());
        assertTrue("No real video surface", surface instanceof SurfaceView || surface instanceof TextureView);
        JSONArray samples = record.optJSONArray("frameCaptureSamples");
        if (samples == null) { samples = new JSONArray(); record.put("frameCaptureSamples", samples); }
        long started = SystemClock.elapsedRealtime(), deadline = started + 8000;
        Bitmap bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
        try {
            do {
                assertEquals("Player failed while waiting for a real frame", 0, errorCode.get());
                assertSame("Video surface changed during capture", surface, value(() -> activity.view.getVideoSurfaceView()));
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT);
                int copyCode;
                if (surface instanceof SurfaceView view) {
                    CountDownLatch done = new CountDownLatch(1); AtomicInteger code = new AtomicInteger(-1);
                    main(() -> PixelCopy.request(view, bitmap, result -> { code.set(result); done.countDown(); }, new Handler(Looper.getMainLooper())));
                    assertTrue("Surface copy timeout", done.await(Math.max(1, Math.min(5000, deadline - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS));
                    copyCode = code.get();
                    assertTrue("PixelCopy failed permanently: " + copyCode, copyCode == PixelCopy.SUCCESS
                            || copyCode == PixelCopy.ERROR_SOURCE_NO_DATA || copyCode == PixelCopy.ERROR_TIMEOUT);
                } else copyCode = value(() -> ((TextureView) surface).getBitmap(bitmap)) == null
                        ? PixelCopy.ERROR_SOURCE_NO_DATA : PixelCopy.SUCCESS;
                int[] pixels = new int[160 * 90]; bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90);
                int nonblack = 0, minimum = 255, maximum = 0;
                for (int pixel : pixels) {
                    int bright = Math.max((pixel >> 16) & 255, Math.max((pixel >> 8) & 255, pixel & 255));
                    if (bright > 8 && (pixel >>> 24) > 0) nonblack++;
                    minimum = Math.min(minimum, bright); maximum = Math.max(maximum, bright);
                }
                samples.put(new JSONObject().put("stage", stage).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("pixelCopyResult", copyCode).put("nonblackPixels", nonblack)
                        .put("minimumBrightness", minimum).put("maximumBrightness", maximum)
                        .put("surfaceWidth", value(surface::getWidth)).put("surfaceHeight", value(surface::getHeight)));
                // FILE_LOADED/VO dimensions can precede Surface buffer submission, especially while paused.
                // Require the same real pixel evidence; never resume playback or relax the pixel thresholds.
                if (copyCode == PixelCopy.SUCCESS && nonblack > 100 && maximum - minimum > 16) {
                    record.put("pixelCopyHasVideo", true).put("nonblackPixels", nonblack);
                    return;
                }
                if (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(120);
            } while (SystemClock.elapsedRealtime() < deadline);
            File image = new File(target.getFilesDir(), "mpv-recovery-frame-" + stage + ".png");
            try (FileOutputStream output = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
            record.put("failedFrameFile", image.getName());
            fail("Video output stayed blank or uniform for 8 seconds; inspect frameCaptureSamples");
        } finally { bitmap.recycle(); }
    }

    private void await(BooleanSupplier condition, long timeout, boolean rejectError) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            if (rejectError) assertEquals("Unexpected player failure during " + stage, 0, errorCode.get());
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(80);
        }
        fail("Timeout during " + stage);
    }

    private void restorePreferences() throws Exception {
        if (saved == null) return;
        SharedPreferences.Editor edit = Prefers.getPrefers().edit();
        for (String key : SETTINGS.keySet()) {
            Object value = saved.get(key);
            if (value == null) edit.remove(key);
            else if (value instanceof Boolean flag) edit.putBoolean(key, flag);
            else if (value instanceof Integer number) edit.putInt(key, number);
            else if (value instanceof Long number) edit.putLong(key, number);
            else if (value instanceof Float number) edit.putFloat(key, number);
            else if (value instanceof String text) edit.putString(key, text);
            else throw new AssertionError("Unexpected fixture preference type");
        }
        assertTrue("Preferences restoration failed", edit.commit());
        Map<String, ?> current = Prefers.getPrefers().getAll();
        for (String key : SETTINGS.keySet()) assertEquals(saved.get(key), current.get(key));
        report.put("preferencesRestored", true);
    }

    private void main(Runnable action) { value(() -> { action.run(); return null; }); }
    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(action.get()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("Main-thread fixture operation failed", failure.get());
        return result.get();
    }
}
