package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.test.MpvRegressionActivity;
import com.fongmi.android.tv.ui.activity.FileActivity;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import is.xyz.mpv.MPVLib;
import is.xyz.mpv.MPVNode;

/** Real libmpv clocks, TextureView pixels, and the production subtitle folder action. */
@RunWith(AndroidJUnit4.class)
public final class MpvPlaybackSemanticsAndroidTest {
    @Rule public final TestName testName = new TestName();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final JSONObject report = new JSONObject();
    private Map<String, ?> saved;
    private Map<String, Object> settings;
    private MpvRegressionActivity activity;
    private PlayerManager manager;
    private FileActivity picker;
    private TextureView texture;
    private PlaySpec spec;
    private boolean passed;
    private String stage = "setup";
    private long began;
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

    @Before public void setUp() throws Exception {
        // This guard comes before preference changes, native access, or activities.
        assertEquals("Disposable MPV issue119 package only", "com.fongmi.android.tv.mpv119", target.getPackageName());
        began = SystemClock.elapsedRealtime();
        File media = new File(target.getFilesDir(), "mpv-semantics-fixture.mp4");
        String address = InstrumentationRegistry.getArguments().getString("mpv_semantics_url", Uri.fromFile(media).toString());
        assertTrue("Use private local media or an adb-reversed loopback fixture",
                address.equals(Uri.fromFile(media).toString()) && media.isFile()
                        || address.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}/[A-Za-z0-9_./-]+"));
        boolean gpuNext = Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("mpv_gpu_next", "false"));
        boolean vulkan = Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("mpv_vulkan", "false"));
        settings = Map.ofEntries(Map.entry("player_engine", PlayerSetting.ENGINE_MPV),
                Map.entry("mpv_gpu_next", gpuNext), Map.entry("mpv_vulkan", vulkan), Map.entry("mpv_hdr", 2),
                Map.entry("mpv_anime4k", 0), Map.entry("preload", false), Map.entry("preload_next", false),
                Map.entry("tunnel", false), Map.entry("audio_effect_preset", 0), Map.entry("audio_pass_through", false),
                Map.entry("ai_subtitle_enabled", false), Map.entry("community_skip_enabled", false));
        saved = new HashMap<>(Prefers.getPrefers().getAll());
        settings.forEach(Prefers::put);
        assertTrue("Native MPV must be available", MpvPlayerEngine.isAvailable());
        report.put("test", testName.getMethodName()).put("configuredGpuNext", gpuNext).put("configuredVulkan", vulkan)
                .put("mediaKind", address.endsWith(".m3u8") ? "hls" : address.startsWith("http") ? "http-mp4" : "local-mp4")
                .put("externalNetwork", false);
        MPVLib.addObserver(observer);
        activity = (MpvRegressionActivity) instrumentation.startActivitySync(new Intent(target, MpvRegressionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> {
            manager = new PlayerManager(new Callback());
            activity.setManager(manager);
            assertTrue("Expected actual MPV player", manager.getPlayer() instanceof MpvPlayer);
            activity.getView().setPlayer(manager.getPlayer());
            manager.bindPlayerView(activity.getView());
            manager.getPlayer().addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) { errors.incrementAndGet(); }
            });
            assertTrue(activity.getView().getVideoSurfaceView() instanceof TextureView);
            texture = (TextureView) activity.getView().getVideoSurfaceView();
            spec = PlaySpec.from("mpv-issue119-fixture", address, Map.of("User-Agent", "TV-issue119-local-fixture"),
                    PlayerManager.buildMetadata("MPV regression fixture", "", null));
            manager.start(spec, 30000, 8000);
            manager.pause();
            manager.getPlayer().setVolume(0);
            manager.getPlayer().setPlaybackParameters(new PlaybackParameters(1.25f));
        });
        stage = "initial-ready";
        await(() -> ready() && near(8000, 900) && paused(), 30000);
        assertTrue("Use a fixture longer than one minute", value(manager::getDuration) >= 60000);
        captureVideo("initial");
        report.put("initialVo", property("current-vo")).put("initialGpuContext", property("current-gpu-context"));
    }

    @Test(timeout = 150000) public void replayFromPlayingAndPausedRestartsAtBeginning() throws Exception {
        JSONArray rounds = new JSONArray();
        report.put("rounds", rounds);
        for (boolean pauseFirst : new boolean[]{false, true}) {
            stage = pauseFirst ? "replay-paused" : "replay-playing";
            main(() -> { manager.seekTo(15000); manager.pause(); });
            await(() -> ready() && near(15000, 750) && paused(), 15000);
            if (!pauseFirst) {
                main(manager::play);
                await(() -> !paused() && nativePosition() > 15400, 10000);
            }
            int loads = starts.get();
            main(() -> manager.replay(C.TIME_UNSET));
            await(() -> ready() && !paused() && nativePosition() < 1800 && value(manager::getPosition) < 1800, 12000);
            assertEquals("Replay uses a seek in the same file", loads, starts.get());
            assertSame("Replay must keep the native player", value(manager::getPlayer), value(activity.getView()::getPlayer));
            rounds.put(snapshot().put("wasPaused", pauseFirst));
            captureVideo(stage);
        }
        passed = true;
    }

    @Test(timeout = 180000) public void repeatedRefreshPreservesCurrentPlaybackProgress() throws Exception {
        JSONArray rounds = new JSONArray();
        report.put("rounds", rounds);
        for (int round = 0; round < 3; round++) {
            stage = "refresh-" + round;
            long desired = 12000 + round * 7000;
            main(() -> { manager.seekTo(desired); manager.pause(); });
            await(() -> ready() && near(desired, 750) && paused(), 15000);
            long retained = value(manager::getPosition);
            int loads = starts.get();
            Player player = value(manager::getPlayer);
            main(() -> {
                manager.stop();
                manager.start(spec, 30000, retained);
                manager.pause();
            });
            await(() -> starts.get() > loads && ready() && near(retained, 900) && paused(), 30000);
            assertSame("MPV refresh retains its native instance", player, value(manager::getPlayer));
            JSONObject record = snapshot().put("savedPositionMs", retained).put("round", round);
            rounds.put(record);
            captureVideo(stage);
            main(manager::play);
            await(() -> nativePosition() > retained + 600 && value(manager::getPosition) > retained + 600, 10000);
        }
        passed = true;
    }

    @Test(timeout = 180000) public void subtitleFolderCancelAndSelectionRestoreTextureVideo() throws Exception {
        JSONArray returns = new JSONArray();
        report.put("returns", returns);
        for (boolean playingBefore : new boolean[]{true, false}) {
            stage = playingBefore ? "cancel-from-playing" : "cancel-from-paused";
            main(() -> { if (playingBefore) manager.play(); else manager.pause(); });
            await(() -> playingBefore != paused(), 10000);
            openSubtitlePicker();
            await(() -> !value(texture::isAvailable) && paused(), 10000);
            long held = value(manager::getPosition);
            main(() -> picker.finish());
            picker = null;
            await(() -> value(texture::isAvailable) && ready() && paused(), 15000);
            assertEquals("Cancel preserves the folder action's pause", held, (long) value(manager::getPosition), 900);
            assertEquals(1.25, number("speed"), .005);
            captureVideo(stage);
            returns.put(snapshot().put("playingBeforeOpen", playingBefore).put("cancelled", true));
            main(this::dismissTracks);
        }
        stage = "select-local-subtitle";
        File subtitle = new File(target.getFilesDir(), "mpv-issue119-local.srt");
        try (FileOutputStream out = new FileOutputStream(subtitle)) {
            out.write("1\n00:00:00,000 --> 00:01:30,000\nMPV LOCAL SUBTITLE FIXTURE\n".getBytes(StandardCharsets.UTF_8));
        }
        openSubtitlePicker();
        await(() -> !value(texture::isAvailable) && paused(), 10000);
        long held = value(manager::getPosition);
        main(() -> picker.onItemClick(subtitle));
        picker = null;
        // The production local selection callback intentionally resumes playback.
        await(() -> value(texture::isAvailable) && ready() && !paused() && !"no".equals(property("sid"))
                && !property("sid").isEmpty(), 15000);
        assertTrue("Local subtitle selected in actual libmpv", hasSelectedExternalSubtitle());
        assertTrue("Local selection must not restart the media", nativePosition() >= held - 900);
        assertEquals(1.25, number("speed"), .005);
        captureVideo(stage);
        returns.put(snapshot().put("cancelled", false).put("externalSubtitleSelected", true));
        await(() -> nativePosition() > held + 650, 10000);
        passed = true;
    }

    private void openSubtitlePicker() {
        main(activity::openSubtitleTracks);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(FileActivity.class.getName(), null, false);
        try {
            main(() -> {
                TrackDialog dialog = tracks();
                assertNotNull("Production track selection dialog", dialog.getDialog());
                View folder = dialog.getDialog().findViewById(R.id.choose);
                assertNotNull("Subtitle folder action", folder);
                assertEquals(View.VISIBLE, folder.getVisibility());
                assertTrue(folder.performClick());
            });
            picker = (FileActivity) instrumentation.waitForMonitorWithTimeout(monitor, 10000);
            assertNotNull("The actual TV local file picker must open", picker);
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private TrackDialog tracks() {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments())
            if (fragment instanceof TrackDialog dialog) return dialog;
        throw new AssertionError("No TrackDialog");
    }

    private void dismissTracks() {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments())
            if (fragment instanceof DialogFragment dialog) dialog.dismissNow();
    }

    private boolean hasSelectedExternalSubtitle() {
        int count = (int) number("track-list/count");
        for (int i = 0; i < count; i++) {
            if ("sub".equals(property("track-list/" + i + "/type"))
                    && Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("track-list/" + i + "/external"))
                    && Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("track-list/" + i + "/selected"))) return true;
        }
        return false;
    }

    private boolean ready() {
        return Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("vo-configured"))
                && value(() -> manager.getPlayer().getPlaybackState() == Player.STATE_READY);
    }
    private boolean paused() {
        return Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("pause"))
                && !value(() -> manager.getPlayer().getPlayWhenReady());
    }
    private boolean near(long position, long tolerance) {
        return Math.abs(nativePosition() - position) <= tolerance && Math.abs(value(manager::getPosition) - position) <= tolerance;
    }
    private double nativePosition() { return number("time-pos") * 1000; }
    private double number(String name) {
        Double value = MPVLib.INSTANCE.getPropertyDouble(name);
        return value == null ? Double.NaN : value;
    }
    private String property(String name) {
        String value = MPVLib.INSTANCE.getPropertyString(name);
        return value == null ? "" : value;
    }
    private JSONObject snapshot() throws Exception {
        return new JSONObject().put("nativePositionMs", finite(nativePosition())).put("reportedPositionMs", value(manager::getPosition))
                .put("paused", paused()).put("vo", property("current-vo")).put("speed", finite(number("speed")))
                .put("nativeStartEvents", starts.get());
    }

    private static Object finite(double value) { return Double.isFinite(value) ? value : JSONObject.NULL; }

    private void captureVideo(String label) throws Exception {
        long until = SystemClock.elapsedRealtime() + 8000;
        do {
            assertEquals("Native player error during " + stage, 0, errors.get());
            Bitmap bitmap = value(() -> texture.getBitmap(160, 90));
            if (bitmap != null) {
                try {
                    int[] pixels = new int[160 * 90];
                    bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90);
                    int nonblack = 0, minimum = 255, maximum = 0;
                    for (int pixel : pixels) {
                        int bright = Math.max((pixel >> 16) & 255, Math.max((pixel >> 8) & 255, pixel & 255));
                        if ((pixel >>> 24) != 0 && bright > 8) nonblack++;
                        minimum = Math.min(minimum, bright);
                        maximum = Math.max(maximum, bright);
                    }
                    if (nonblack > 100 && maximum - minimum > 16) {
                        JSONArray frames = report.optJSONArray("frames");
                        if (frames == null) { frames = new JSONArray(); report.put("frames", frames); }
                        frames.put(new JSONObject().put("stage", label).put("nonblackPixels", nonblack)
                                .put("brightnessRange", maximum - minimum));
                        File image = new File(target.getFilesDir(), "mpv119-" + testName.getMethodName() + "-" + label + ".png");
                        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                        return;
                    }
                } finally { bitmap.recycle(); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < until);
        fail("Real TextureView pixels remained blank or uniform during " + label);
    }

    private void await(BooleanSupplier condition, long timeout) {
        long until = SystemClock.elapsedRealtime() + timeout;
        do {
            assertEquals("Native player error during " + stage, 0, errors.get());
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(80);
        } while (SystemClock.elapsedRealtime() < until);
        fail("Timeout during " + stage + "; native position=" + nativePosition() + ", pause=" + property("pause")
                + ", vo=" + property("current-vo") + ", reported position=" + value(manager::getPosition));
    }

    @After public void tearDown() throws Exception {
        if (saved == null) return;
        try {
            report.put("lastStage", stage).put("elapsedMs", SystemClock.elapsedRealtime() - began).put("passed", passed);
            if (manager != null) report.put("finalState", snapshot());
        } finally {
            try {
                main(() -> {
                    if (picker != null) picker.finish();
                    MPVLib.removeObserver(observer);
                    if (manager != null) {
                        manager.bindPlayerView(null);
                        if (activity != null) activity.getView().setPlayer(null);
                        manager.release();
                    }
                    if (activity != null) activity.finish();
                });
                long until = SystemClock.elapsedRealtime() + 15000;
                while (!MpvPlayerEngine.isAvailable() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
                assertTrue("Native player release completed", MpvPlayerEngine.isAvailable());
            } finally {
                SharedPreferences.Editor edit = Prefers.getPrefers().edit();
                for (String key : settings.keySet()) {
                    Object value = saved.get(key);
                    if (value == null) edit.remove(key);
                    else if (value instanceof Boolean flag) edit.putBoolean(key, flag);
                    else if (value instanceof Integer number) edit.putInt(key, number);
                    else if (value instanceof Long number) edit.putLong(key, number);
                    else if (value instanceof Float number) edit.putFloat(key, number);
                    else if (value instanceof String text) edit.putString(key, text);
                    else throw new AssertionError("Unexpected preference type");
                }
                assertTrue(edit.commit());
                for (String key : settings.keySet()) assertEquals(saved.get(key), Prefers.getPrefers().getAll().get(key));
                report.put("preferencesRestored", true);
                try (FileOutputStream out = new FileOutputStream(new File(target.getFilesDir(),
                        "mpv119-" + testName.getMethodName() + ".json"))) {
                    out.write((report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private void main(Runnable action) { value(() -> { action.run(); return null; }); }
    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(action.get()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("Main thread action failed", failure.get());
        return result.get();
    }

    private final class Callback implements PlayerManager.Callback {
        @Override public void onPrepare() { }
        @Override public void onTracksChanged() { }
        @Override public void onDecodeChanged() { }
        @Override public void onMediaOptionsChanged() { }
        @Override public void onError(String message) { errors.incrementAndGet(); }
        @Override public void onPlayerRebuild(Player player) { errors.incrementAndGet(); }
        @Override public void onDanmakuSourceChanged(Uri uri) { }
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) { }
        @Override public void onDanmakuEnabledChanged(boolean enabled) { }
        @Override public void onDanmakuSent(String text) { }
    }
}
