package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
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
    private ViewGroup detachedTextureParent;
    private ViewGroup.LayoutParams detachedTextureLayout;
    private int detachedTextureIndex;
    private SurfaceTexture previousTextureSurface;
    private JSONObject surfaceRecreation;
    private PlaySpec spec;
    private boolean passed;
    private boolean expectedEngineRebuild;
    private boolean nativeObserverRegistered;
    private boolean observingPositions;
    private long expectedPosition;
    private long minimumObservedPosition;
    private int belowTargetEvents;
    private JSONArray positionEvents;
    private String stage = "setup";
    private long began;
    private final Player.Listener playerObserver = new Player.Listener() {
        @Override public void onPlayerError(PlaybackException error) { errors.incrementAndGet(); }
        @Override public void onEvents(Player player, Player.Events events) {
            // A newly created engine has no media until start(); its empty timeline is valid.
            if (!observingPositions || player.getCurrentMediaItem() == null) return;
            long position = player.getCurrentPosition();
            minimumObservedPosition = Math.min(minimumObservedPosition, position);
            if (position < expectedPosition - 1000) belowTargetEvents++;
            try {
                if (positionEvents.length() < 300) positionEvents.put(new JSONObject()
                        .put("positionMs", position).put("state", player.getPlaybackState())
                        .put("playWhenReady", player.getPlayWhenReady()));
            } catch (Exception failure) { errors.incrementAndGet(); }
        }
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
        nativeObserverRegistered = true;
        stage = "test-host-ready";
        activity = (MpvRegressionActivity) instrumentation.startActivitySync(new Intent(target, MpvRegressionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long hostDeadline = SystemClock.elapsedRealtime() + 15000;
        while (!value(() -> activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                && activity.hasWindowFocus()
                && activity.getView().getVideoSurfaceView() instanceof TextureView view
                && view.isAttachedToWindow() && view.isAvailable())) {
            assertTrue("The exact test host and texture must be foreground before binding MPV",
                    SystemClock.elapsedRealtime() < hostDeadline);
            SystemClock.sleep(80);
        }
        report.put("hostResumedBeforePlayer", true).put("hostFocusedBeforePlayer", true)
                .put("textureAttachedBeforePlayer", true);
        main(() -> {
            manager = new PlayerManager(new Callback());
            activity.setManager(manager);
            assertTrue("Expected actual MPV player", manager.getPlayer() instanceof MpvPlayer);
            activity.getView().setPlayer(manager.getPlayer());
            manager.bindPlayerView(activity.getView());
            manager.getPlayer().addListener(playerObserver);
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
            refreshRound(rounds, round, 12000 + round * 7000);
        }
        passed = true;
    }

    @Test(timeout = 120000) public void replayAfterEndReloadsSameMediaAtBeginning() throws Exception {
        long duration = value(manager::getDuration);
        stage = "seek-near-real-end";
        main(() -> { manager.pause(); manager.seekTo(duration - 600); });
        await(() -> ready() && near(duration - 600, 350) && paused(), 15000);
        Player player = value(manager::getPlayer);
        Uri media = value(() -> manager.getPlayer().getCurrentMediaItem().localConfiguration.uri);
        main(manager::play);
        stage = "real-eof";
        await(() -> value(() -> manager.getPlayer().getPlaybackState() == Player.STATE_ENDED), 15000);
        report.put("reachedActualEnd", true).put("durationMs", duration).put("endState", snapshot());

        int loads = starts.get();
        stage = "replay-after-eof";
        main(() -> manager.replay(C.TIME_UNSET));
        await(() -> starts.get() > loads && ready() && !paused()
                && nativePosition() < 1800 && value(manager::getPosition) < 1800, 15000);
        assertEquals("EOF replay opens this same file once", loads + 1, starts.get());
        assertSame("EOF replay keeps the MPV native generation", player, value(manager::getPlayer));
        assertEquals("EOF replay keeps the same media", media,
                value(() -> manager.getPlayer().getCurrentMediaItem().localConfiguration.uri));
        assertEquals("EOF replay keeps the selected speed", 1.25, number("speed"), .005);
        captureVideo(stage);
        report.put("afterReplay", snapshot()).put("newNativeStartEvents", starts.get() - loads);
        passed = true;
    }

    @Test(timeout = 240000) public void exoToMpvSwitchThenRepeatedRefreshPreservesProgress() throws Exception {
        stage = "before-exo-switch";
        main(() -> { manager.seekTo(18000); manager.pause(); });
        await(() -> ready() && near(18000, 750) && paused(), 15000);
        main(() -> {
            // Stop receiving native callbacks before destroying this MPV generation.
            MPVLib.removeObserver(observer);
            nativeObserverRegistered = false;
            switchEngine(PlayerSetting.ENGINE_EXO);
        });
        stage = "real-exo-ready";
        await(() -> value(() -> manager.getEngine() == PlayerSetting.ENGINE_EXO
                && manager.getPlayer().getPlaybackState() == Player.STATE_READY
                && !manager.getPlayer().getPlayWhenReady() && Math.abs(manager.getPosition() - 18000) <= 900), 30000);
        // Availability reads Java lifecycle state and never touches the destroyed native handle.
        await(MpvPlayerEngine::isAvailable, 15000);
        captureVideo(stage);
        report.put("realExoReady", true).put("exoPositionMs", value(manager::getPosition));

        stage = "exo-to-mpv";
        main(() -> {
            MPVLib.addObserver(observer);
            nativeObserverRegistered = true;
            observePositions(18000);
            switchEngine(PlayerSetting.ENGINE_MPV);
            assertTrue("Switch must create the actual MPV player", manager.getPlayer() instanceof MpvPlayer);
        });
        await(() -> ready() && near(18000, 900) && paused(), 30000);
        report.put("switchPositionEvents", value(this::finishPositionObservation));
        report.put("mpvAfterExo", snapshot());
        captureVideo(stage);

        JSONArray rounds = new JSONArray();
        report.put("rounds", rounds);
        for (int round = 0; round < 3; round++) {
            stage = "refresh-after-exo-" + round;
            refreshRound(rounds, round, 20000 + round * 7000);
        }
        passed = true;
    }

    @Test(timeout = 120000) public void seekWhileLoadingOverridesPreviousResumeTarget() throws Exception {
        stage = "seek-while-loading";
        int loads = starts.get();
        Player player = value(manager::getPlayer);
        main(() -> {
            manager.stop();
            manager.start(spec, 30000, 12000);
            manager.pause();
            assertFalse("Seek must run before FILE_LOADED, in this same main-thread turn", fileLoaded());
            manager.seekTo(26000);
            observePositions(26000);
            assertEquals("Expose the user's pending seek immediately", 26000, manager.getPosition());
        });
        await(() -> starts.get() > loads && ready() && near(26000, 900) && paused(), 30000);
        assertSame("Loading seek must keep this MPV generation", player, value(manager::getPlayer));
        report.put("seekIssuedBeforeFileLoaded", true).put("oldResumeTargetMs", 12000).put("userSeekMs", 26000)
                .put("positionEvents", value(this::finishPositionObservation)).put("afterSeek", snapshot());
        captureVideo(stage);
        main(manager::play);
        await(() -> nativePosition() > 26600 && value(manager::getPosition) > 26600, 10000);
        passed = true;
    }

    private void refreshRound(JSONArray rounds, int round, long desired) throws Exception {
        main(() -> { manager.seekTo(desired); manager.pause(); });
        await(() -> ready() && near(desired, 750) && paused(), 15000);
        long retained = value(manager::getPosition);
        int loads = starts.get();
        Player player = value(manager::getPlayer);
        main(() -> {
            observePositions(retained);
            manager.stop();
            manager.start(spec, 30000, retained);
            manager.pause();
        });
        await(() -> starts.get() > loads && ready() && near(retained, 900) && paused(), 30000);
        assertSame("MPV refresh retains its native instance", player, value(manager::getPlayer));
        rounds.put(snapshot().put("savedPositionMs", retained).put("round", round)
                .put("positionEvents", value(this::finishPositionObservation)));
        captureVideo(stage);
        main(manager::play);
        await(() -> nativePosition() > retained + 600 && value(manager::getPosition) > retained + 600, 10000);
    }

    /** Invoked on the main thread; allow only these two requested engine replacements. */
    private void switchEngine(int engine) {
        manager.getPlayer().removeListener(playerObserver);
        expectedEngineRebuild = true;
        try { manager.setEngine(engine); }
        finally { expectedEngineRebuild = false; }
    }

    private boolean fileLoaded() {
        try {
            java.lang.reflect.Field field = MpvPlayer.class.getDeclaredField("fileLoaded");
            field.setAccessible(true);
            return field.getBoolean(manager.getPlayer());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private void observePositions(long expected) {
        expectedPosition = expected;
        minimumObservedPosition = Long.MAX_VALUE;
        belowTargetEvents = 0;
        positionEvents = new JSONArray();
        observingPositions = true;
    }

    private JSONObject finishPositionObservation() {
        observingPositions = false;
        try {
            JSONObject result = new JSONObject().put("expectedPositionMs", expectedPosition).put("minimumPositionMs", minimumObservedPosition)
                    .put("belowTargetEvents", belowTargetEvents).put("samples", positionEvents);
            report.put("lastPositionObservation", result);
            assertTrue("Real Player.onEvents must cover the reload transition", positionEvents.length() > 0);
            assertEquals("Loading events must not publish zero or an older position", 0, belowTargetEvents);
            return result;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test(timeout = 180000) public void subtitleFolderCancelAndSelectionRestoreTextureVideo() throws Exception {
        JSONArray returns = new JSONArray();
        report.put("returns", returns);
        for (boolean playingBefore : new boolean[]{true, false}) {
            stage = playingBefore ? "cancel-from-playing" : "cancel-from-paused";
            main(() -> { if (playingBefore) manager.play(); else manager.pause(); });
            await(() -> playingBefore != paused(), 10000);
            openSubtitlePicker();
            recreateSurfaceWhilePickerIsForeground();
            await(() -> !value(texture::isAvailable) && paused(), 10000);
            long held = value(manager::getPosition);
            main(() -> picker.finish());
            picker = null;
            await(() -> value(() -> activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)), 10000);
            main(this::restoreDetachedTexture);
            await(() -> value(texture::isAvailable) && ready() && paused(), 15000);
            verifyNewTextureSurface();
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
        recreateSurfaceWhilePickerIsForeground();
        await(() -> !value(texture::isAvailable) && paused(), 10000);
        long held = value(manager::getPosition);
        main(() -> picker.onItemClick(subtitle));
        picker = null;
        await(() -> value(() -> activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)), 10000);
        main(this::restoreDetachedTexture);
        // The production local selection callback intentionally resumes playback.
        await(() -> value(texture::isAvailable) && ready() && !paused() && !"no".equals(property("sid"))
                && !property("sid").isEmpty(), 15000);
        verifyNewTextureSurface();
        assertTrue("Local subtitle selected in actual libmpv", hasSelectedExternalSubtitle());
        assertTrue("Local selection must not restart the media", nativePosition() >= held - 900);
        assertEquals(1.25, number("speed"), .005);
        captureVideo(stage);
        returns.put(snapshot().put("cancelled", false).put("externalSubtitleSelected", true));
        await(() -> nativePosition() > held + 650, 10000);
        passed = true;
    }

    /** This device keeps stopped textures; detach the real view without fabricating player events. */
    private void recreateSurfaceWhilePickerIsForeground() {
        await(() -> value(() -> picker != null && picker.hasWindowFocus()) && paused(), 10000);
        main(() -> {
            assertNull("The previous texture must already be restored", detachedTextureParent);
            assertTrue("Keep the original TextureView parent", texture.getParent() instanceof ViewGroup);
            detachedTextureParent = (ViewGroup) texture.getParent();
            detachedTextureIndex = detachedTextureParent.indexOfChild(texture);
            detachedTextureLayout = texture.getLayoutParams();
            previousTextureSurface = texture.getSurfaceTexture();
            try {
                report.put("explicitSurfaceRecreation", true);
                surfaceRecreation = new JSONObject().put("stage", stage)
                        .put("naturalSurfaceReleasedBeforeRemoval", !texture.isAvailable())
                        .put("originalChildIndex", detachedTextureIndex)
                        .put("originalLayoutWidth", detachedTextureLayout.width)
                        .put("originalLayoutHeight", detachedTextureLayout.height);
                JSONArray recreations = report.optJSONArray("surfaceRecreations");
                if (recreations == null) { recreations = new JSONArray(); report.put("surfaceRecreations", recreations); }
                recreations.put(surfaceRecreation);
                detachedTextureParent.removeView(texture);
                assertFalse("Removing the view must detach it from the actual window", texture.isAttachedToWindow());
                assertFalse("Android must release the original SurfaceTexture", texture.isAvailable());
                if (previousTextureSurface != null && Build.VERSION.SDK_INT >= 26) assertTrue("The old native SurfaceTexture must be released",
                        previousTextureSurface.isReleased());
                surfaceRecreation.put("viewDetachedFromWindow", true).put("oldSurfaceReleased", true);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    /** Also used from cleanup after failures; preserve the same view, index, and layout parameters. */
    private void restoreDetachedTexture() {
        if (detachedTextureParent == null) return;
        if (texture.getParent() == null) {
            int index = Math.min(detachedTextureIndex, detachedTextureParent.getChildCount());
            detachedTextureParent.addView(texture, index, detachedTextureLayout);
            try { surfaceRecreation.put("restoredChildIndex", index).put("sameViewRestored", true); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
        assertSame("Restore the original texture parent", detachedTextureParent, texture.getParent());
        assertSame("Restore the original layout parameters", detachedTextureLayout, texture.getLayoutParams());
        detachedTextureParent = null;
        detachedTextureLayout = null;
    }

    private void verifyNewTextureSurface() {
        main(() -> {
            assertNotNull("Android must create a real new SurfaceTexture", texture.getSurfaceTexture());
            if (previousTextureSurface != null) assertNotSame("Use a newly created SurfaceTexture after detach",
                    previousTextureSurface, texture.getSurfaceTexture());
            try { surfaceRecreation.put("newSurfaceCreated", true); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
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
        boolean mpv = value(() -> manager.getPlayer() instanceof MpvPlayer);
        return new JSONObject().put("nativePositionMs", mpv ? finite(nativePosition()) : JSONObject.NULL)
                .put("reportedPositionMs", value(manager::getPosition)).put("engine", mpv ? "mpv" : "exo")
                .put("paused", !value(() -> manager.getPlayer().getPlayWhenReady()))
                .put("vo", mpv ? property("current-vo") : JSONObject.NULL)
                .put("speed", value(() -> manager.getPlayer().getPlaybackParameters().speed))
                .put("cachePaused", mpv ? Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("paused-for-cache")) : JSONObject.NULL)
                .put("seeking", mpv ? Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("seeking")) : JSONObject.NULL)
                .put("eofReached", mpv ? Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("eof-reached")) : JSONObject.NULL)
                .put("voConfigured", mpv ? Boolean.TRUE.equals(MPVLib.INSTANCE.getPropertyBoolean("vo-configured")) : JSONObject.NULL)
                .put("vid", mpv ? property("vid") : JSONObject.NULL)
                .put("demuxerReaderPts", mpv ? finite(number("demuxer-cache-state/reader-pts")) : JSONObject.NULL)
                .put("nativeStartEvents", starts.get());
    }

    private static Object finite(double value) { return Double.isFinite(value) ? value : JSONObject.NULL; }

    private void captureVideo(String label) throws Exception {
        main(() -> {
            assertTrue("Capture from the resumed test host",
                    activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED));
            assertTrue("Capture from the attached test texture", texture.isAttachedToWindow());
        });
        long until = SystemClock.elapsedRealtime() + 8000;
        do {
            assertEquals("Native player error during " + stage, 0, errors.get());
            Bitmap bitmap = value(() -> texture.getBitmap(160, 90));
            if (bitmap != null) {
                try {
                    int[] pixels = new int[160 * 90];
                    bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90);
                    int nonblack = 0, colored = 0, minimum = 255, maximum = 0;
                    for (int pixel : pixels) {
                        int red = (pixel >> 16) & 255, green = (pixel >> 8) & 255, blue = pixel & 255;
                        int bright = Math.max(red, Math.max(green, blue));
                        int dark = Math.min(red, Math.min(green, blue));
                        if ((pixel >>> 24) != 0 && bright > 8) nonblack++;
                        if ((pixel >>> 24) != 0 && bright > 60 && bright - dark > 40) colored++;
                        minimum = Math.min(minimum, bright);
                        maximum = Math.max(maximum, bright);
                    }
                    JSONArray attempts = report.optJSONArray("frameCaptureAttempts");
                    if (attempts == null) { attempts = new JSONArray(); report.put("frameCaptureAttempts", attempts); }
                    attempts.put(new JSONObject().put("stage", label).put("nonblackPixels", nonblack)
                            .put("coloredPixels", colored).put("brightnessRange", maximum - minimum));
                    // testsrc2 contains large colored areas; white subtitles on black cannot pass.
                    if (nonblack > 100 && colored > 1000 && maximum - minimum > 16) {
                        JSONArray frames = report.optJSONArray("frames");
                        if (frames == null) { frames = new JSONArray(); report.put("frames", frames); }
                        frames.put(new JSONObject().put("stage", label).put("nonblackPixels", nonblack)
                                .put("coloredPixels", colored).put("brightnessRange", maximum - minimum));
                        File image = new File(target.getFilesDir(), "mpv119-" + testName.getMethodName() + "-" + label + ".png");
                        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                        return;
                    }
                    if (SystemClock.elapsedRealtime() >= until - 100) {
                        File image = new File(target.getFilesDir(), "mpv119-" + testName.getMethodName() + "-" + label + "-failed.png");
                        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                        report.put("failedVideoFrame", image.getName());
                    }
                } finally { bitmap.recycle(); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < until);
        fail("Real TextureView did not show the colored testsrc2 video during " + label);
    }

    private void await(BooleanSupplier condition, long timeout) {
        long until = SystemClock.elapsedRealtime() + timeout;
        do {
            assertEquals("Native player error during " + stage, 0, errors.get());
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(80);
        } while (SystemClock.elapsedRealtime() < until);
        boolean mpv = value(() -> manager.getPlayer() instanceof MpvPlayer);
        fail("Timeout during " + stage + (mpv ? "; native position=" + nativePosition() + ", pause=" + property("pause")
                + ", vo=" + property("current-vo") : "; engine=exo") + ", reported position=" + value(manager::getPosition));
    }

    @After public void tearDown() throws Exception {
        if (saved == null) return;
        try {
            report.put("lastStage", stage).put("elapsedMs", SystemClock.elapsedRealtime() - began).put("passed", passed);
            if (observingPositions) main(() -> {
                observingPositions = false;
                try { report.put("unfinishedPositionEvents", positionEvents); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            if (manager != null) report.put("finalState", snapshot());
        } finally {
            try {
                main(() -> {
                    observingPositions = false;
                    try {
                        if (picker != null) picker.finish();
                        restoreDetachedTexture();
                    } finally {
                        try {
                            if (nativeObserverRegistered) MPVLib.removeObserver(observer);
                            if (manager != null) {
                                try {
                                    manager.getPlayer().removeListener(playerObserver);
                                    manager.bindPlayerView(null);
                                    if (activity != null) activity.getView().setPlayer(null);
                                } finally { manager.release(); }
                            }
                        } finally { if (activity != null) activity.finish(); }
                    }
                });
                long until = SystemClock.elapsedRealtime() + 15000;
                while (!MpvPlayerEngine.isAvailable() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
                assertTrue("Native player release completed", MpvPlayerEngine.isAvailable());
                if (activity != null) {
                    long hostDeadline = SystemClock.elapsedRealtime() + 15000;
                    while (value(() -> activity.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED)
                            && SystemClock.elapsedRealtime() < hostDeadline) SystemClock.sleep(80);
                    assertEquals("The previous host must be destroyed before the next test",
                            Lifecycle.State.DESTROYED, value(() -> activity.getLifecycle().getCurrentState()));
                    report.put("hostDestroyedAfterRelease", true);
                }
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
        @Override public void onPlayerRebuild(Player player) {
            if (!expectedEngineRebuild) errors.incrementAndGet();
            activity.getView().setPlayer(player);
            manager.bindPlayerView(activity.getView());
            player.addListener(playerObserver);
        }
        @Override public void onDanmakuSourceChanged(Uri uri) { }
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) { }
        @Override public void onDanmakuEnabledChanged(boolean enabled) { }
        @Override public void onDanmakuSent(String text) { }
    }
}
