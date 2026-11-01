package com.fongmi.android.tv.player.skip;

import android.app.Instrumentation;
import android.net.Uri;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackState;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Exercises production controller/Exo seeking with local ranges and generated PCM, without provider traffic. */
@RunWith(AndroidJUnit4.class)
public final class CommunitySkipPlaybackAndroidTest {

    private static final long DURATION = 30_000;
    private static final Map<String, Object> SETTINGS = Map.ofEntries(
            Map.entry("player_engine", PlayerSetting.ENGINE_EXO), Map.entry("tunnel", false),
            Map.entry("preload", false), Map.entry("preload_next", false), Map.entry("incognito", true),
            Map.entry("audio_effect_preset", 0), Map.entry("audio_pass_through", false),
            Map.entry("audio_prefer", false), Map.entry("video_prefer", false), Map.entry("av3a", false),
            Map.entry("subtitle_libass", false), Map.entry("ai_subtitle_enabled", false),
            Map.entry("community_skip_enabled", true));

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicReference<String> error = new AtomicReference<>();
    private final List<Long> seeks = new ArrayList<>();
    private Map<String, ?> saved;
    private PlayerManager manager;
    private VodPlaybackController controller;
    private CommunitySkipRuntime runtime;
    private History history;
    private File fixture;
    private int nextRequests;
    private boolean passed;

    @Before public void setup() throws Exception {
        String applicationId = instrumentation.getTargetContext().getPackageName();
        assertTrue("Use a disposable validation application", applicationId.equals("com.fongmi.android.tv.preview")
                || applicationId.equals("com.fongmi.android.tv.sourceprobe"));
        saved = new HashMap<>(Prefers.getPrefers().getAll());
        fixture = new File(instrumentation.getTargetContext().getCacheDir(), "community-skip-pcm.wav");
        try (FileOutputStream output = new FileOutputStream(fixture)) { output.write(wav()); }
        main(() -> {
            SETTINGS.forEach(Prefers::put);
            manager = new PlayerManager(new Callback());
            assertTrue(manager.getPlayer() instanceof ExoPlayer);
            Result result = new Result();
            result.setUrl(Uri.fromFile(fixture).toString());
            result.setFormat("audio/wav");
            manager.start(PlaySpec.from(result, "community-skip-local-fixture", MediaMetadata.EMPTY), 20_000, 0);
            manager.pause();
            manager.getPlayer().setVolume(0);
            history = new History();
            history.setKey("community-skip-local-fixture");
            history.setVodFlag("fixture");
            history.setVodRemarks("S01E01");
            history.setEpisodeUrl(fixture.toString());
            VodPlaybackState state = new VodPlaybackState();
            Flag flag = new Flag("fixture");
            flag.getEpisodes().add(Episode.create("S01E01", fixture.toString()));
            flag.getEpisodes().add(Episode.create("S01E02", fixture.toString() + "-next"));
            flag.toggle(true, flag.getEpisodes().get(0));
            state.setFlags(List.of(flag));
            state.setHistory(history);
            VodPlaybackHost host = (VodPlaybackHost) Proxy.newProxyInstance(VodPlaybackHost.class.getClassLoader(),
                    new Class<?>[]{VodPlaybackHost.class}, (proxy, method, args) -> {
                        if (method.getName().equals("canSkipCommunitySegment")) return manager.isPlaying() && manager.canSeek();
                        if (method.getName().equals("skipCommunitySegment")) {
                            long target = (long) args[0];
                            seeks.add(target);
                            manager.seekTo(target);
                        } else if (method.getName().equals("requestPlayer")) nextRequests++;
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return manager.getPosition();
                        if (method.getReturnType() == String.class) return "";
                        if (method.getReturnType() == List.class) return List.of();
                        return null;
                    });
            controller = new VodPlaybackController(host, state);
            runtime = (CommunitySkipRuntime) field(controller, "communitySkip");
            installRanges();
        });
        await(() -> manager.getPlaybackState() == Player.STATE_READY && manager.canSeek(), "PCM WAV ready");
        main(() -> assertEquals(DURATION, manager.getDuration(), 5));
    }

    @Test public void shouldSeekOnlyTheIntroAndCreditsAndKeepBothStoryScenes() {
        play();
        main(() -> {
            tick();
            assertTrue("The cold open must remain before the intro", manager.getPosition() < 5_000);
            assertTrue(seeks.isEmpty());
            manager.pause();
        });
        seekPaused(5_200);
        play();
        main(() -> { tick(); manager.pause(); });
        await(() -> manager.getPlaybackState() == Player.STATE_READY, "Intro seek ready");
        main(() -> {
            assertEquals(List.of(9_000L), seeks);
            assertEquals(9_000, manager.getPosition(), 50);
        });
        seekPaused(23_200);
        play();
        main(() -> { tick(); manager.pause(); });
        await(() -> manager.getPlaybackState() == Player.STATE_READY, "Credits seek ready");
        main(() -> {
            assertEquals(List.of(9_000L, 27_000L), seeks);
            assertEquals(27_000, manager.getPosition(), 50);
            assertTrue("The post-credit scene must still be playable", manager.getPosition() < manager.getDuration());
            assertEquals("Credits with a later scene must not request the next episode", 0, nextRequests);
            assertEquals("Community ranges must not overwrite manual offsets", C.TIME_UNSET, history.getOpening());
            assertEquals(C.TIME_UNSET, history.getEnding());
        });
        passed = true;
    }

    @Test public void shouldHonorPauseManualResetStopAndChangedEpisode() {
        seekPaused(5_200);
        main(() -> {
            tick();
            assertTrue("A paused player must not seek or consume the intro", seeks.isEmpty());
            assertEquals(5_200, manager.getPosition(), 50);
        });
        play();
        main(() -> { tick(); manager.pause(); });
        await(() -> manager.getPlaybackState() == Player.STATE_READY, "Resume seek ready");
        main(() -> assertEquals(List.of(9_000L), seeks));
        main(() -> {
            installRanges();
            seeks.clear();
            controller.setOpening(0);
        });
        seekPaused(5_200);
        play();
        main(() -> {
            tick();
            manager.pause();
            assertTrue("Explicit manual reset disables the community intro", seeks.isEmpty());
            history.setOpening(C.TIME_UNSET);
            history.setOpeningSource("unknown");
            installRanges();
            controller.stopCommunitySkip();
        });
        play();
        main(() -> {
            tick();
            manager.pause();
            assertTrue("A stopped session must not seek", seeks.isEmpty());
            installRanges();
            history.setEpisodeUrl("changed-episode");
        });
        play();
        main(() -> {
            tick();
            manager.pause();
            assertTrue("A result for the previous episode must not seek", seeks.isEmpty());
            assertNull(field(runtime, "active"));
        });
        passed = true;
    }

    @After public void cleanup() throws Exception {
        try {
            main(() -> {
                if (controller != null) controller.stopCommunitySkip();
                if (manager != null) manager.release();
                if (saved != null) for (String key : SETTINGS.keySet()) {
                    if (saved.containsKey(key)) Prefers.put(key, saved.get(key));
                    else Prefers.remove(key);
                }
            });
        } finally {
            if (fixture != null) fixture.delete();
            if (saved != null) for (String key : SETTINGS.keySet()) {
                Map<String, ?> current = Prefers.getPrefers().getAll();
                assertEquals(saved.containsKey(key), current.containsKey(key));
                assertEquals(saved.get(key), current.get(key));
            }
            File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "community-skip-verification");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            JSONObject report = new JSONObject().put("scope", "PRODUCTION_VOD_CONTROLLER_EXO_PCM_WAV")
                    .put("passed", passed).put("seekCount", seeks.size()).put("nextEpisodeRequests", nextRequests)
                    .put("providerRequests", 0).put("mpvExecuted", false).put("videoDecoded", false);
            try (FileOutputStream output = new FileOutputStream(new File(directory,
                    "community-skip-" + (seeks.size() > 1 ? "ranges" : "guards") + ".json"))) {
                output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void installRanges() {
        try {
            Class<?> sessionClass = Class.forName(CommunitySkipRuntime.class.getName() + "$Session");
            Constructor<?> constructor = sessionClass.getDeclaredConstructor(History.class);
            constructor.setAccessible(true);
            Object session = constructor.newInstance(history);
            setField(session, "fetchStarted", true);
            setField(session, "policy", new SegmentSkipPolicy(List.of(
                    new SkipSegment(SkipSegment.Type.INTRO, 5_000, 9_000, "fixture"),
                    new SkipSegment(SkipSegment.Type.CREDITS, 23_000, 27_000, "fixture")), DURATION));
            runtime.stop();
            setField(runtime, "active", session);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not install local ranges in the production session", failure);
        }
    }

    private void tick() { controller.onTimeChanged(System.currentTimeMillis(), manager.getPosition(), manager.getDuration()); }

    private void play() {
        main(manager::play);
        await(manager::isPlaying, "Exo playing");
    }

    private void seekPaused(long position) {
        main(() -> { manager.pause(); manager.seekTo(position); controller.onSeek(); });
        await(() -> manager.getPlaybackState() == Player.STATE_READY && Math.abs(manager.getPosition() - position) < 50, "Paused seek ready");
    }

    private void await(BooleanSupplier condition, String description) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNull(error.get());
            if (read(condition::getAsBoolean)) return;
            SystemClock.sleep(40);
        }
        fail(description + "; state=" + read(manager::getPlaybackState));
    }

    private void main(Runnable task) { read(() -> { task.run(); return null; }); }

    private <T> T read(Supplier<T> task) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(task.get()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Community skip main-thread check failed", failure.get());
        return value.get();
    }

    private static Object field(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void setField(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static byte[] wav() {
        int rate = 16_000, samples = rate * (int) (DURATION / 1000), bytes = samples * 2;
        ByteBuffer output = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        output.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
        output.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        output.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        output.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        for (int i = 0; i < samples; i++) output.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / rate) * 1000));
        return output.array();
    }

    private final class Callback implements PlayerManager.Callback {
        @Override public void onPrepare() {}
        @Override public void onTracksChanged() {}
        @Override public void onDecodeChanged() {}
        @Override public void onMediaOptionsChanged() {}
        @Override public void onError(String message) { error.set(message); }
        @Override public void onPlayerRebuild(Player player) { error.set("Unexpected decoder replacement"); }
        @Override public void onDanmakuSourceChanged(Uri uri) {}
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) {}
        @Override public void onDanmakuEnabledChanged(boolean enabled) {}
        @Override public void onDanmakuSent(String text) {}
    }
}
