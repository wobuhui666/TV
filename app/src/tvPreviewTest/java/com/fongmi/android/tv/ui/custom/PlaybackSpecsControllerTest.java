package com.fongmi.android.tv.ui.custom;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Production controller and Exo callbacks, using a generated local PCM file without network I/O. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackSpecsControllerTest {
    private static final Map<String, Object> SETTINGS = Map.ofEntries(
            Map.entry("player_engine", PlayerSetting.ENGINE_EXO), Map.entry("tunnel", false),
            Map.entry("render", PlayerSetting.RENDER_SURFACE), Map.entry("preload", false),
            Map.entry("preload_next", false), Map.entry("audio_effect_preset", 0),
            Map.entry("audio_pass_through", false), Map.entry("audio_prefer", false),
            Map.entry("video_prefer", false), Map.entry("av3a", false),
            Map.entry("subtitle_libass", false), Map.entry("ai_subtitle_enabled", false),
            Map.entry("community_skip_enabled", false));
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicReference<String> error = new AtomicReference<>();
    private Map<String, ?> saved;
    private File fixture;
    private CorePlaybackActivity activity;
    private PlayerManager manager;
    private JetStreamPlaybackSpecsView capsule;
    private PlaybackSpecsController controller;

    @Before public void setup() throws Exception {
        assertEquals("Use the isolated TV preview", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        saved = new HashMap<>(Prefers.getPrefers().getAll());
        main(() -> SETTINGS.forEach(Prefers::put));
        fixture = File.createTempFile("playback-specs-", ".wav", instrumentation.getTargetContext().getCacheDir());
        writeWav(fixture);
        activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> {
            FrameLayout host = new FrameLayout(activity);
            ((ViewGroup) activity.view.getParent()).removeView(activity.view);
            host.addView(activity.view, new FrameLayout.LayoutParams(-1, -1));
            capsule = new JetStreamPlaybackSpecsView(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, dp(46));
            params.setMargins(dp(14), dp(14), dp(14), 0);
            host.addView(capsule, params);
            activity.setContentView(host);
            manager = new PlayerManager(new Callback());
            assertTrue("Exercise the production Exo engine", manager.getPlayer() instanceof ExoPlayer);
            controller = new PlaybackSpecsController(capsule, activity.view, () -> manager, () -> null, () -> true);
            controller.setStarted(true);
            manager.bindPlayerView(activity.view);
            manager.getPlayer().setVolume(0f);
            manager.getPlayer().addListener(new Player.Listener() {
                @Override public void onIsPlayingChanged(boolean playing) { controller.onMetadataChanged(); }
                @Override public void onTracksChanged(Tracks tracks) { controller.onMetadataChanged(); }
                @Override public void onPlayerError(PlaybackException failure) { error.set(failure.toString()); }
            });
        });
    }

    @After public void cleanup() {
        try {
            main(() -> {
                try {
                    if (controller != null) controller.setStarted(false);
                    if (activity != null) activity.view.setPlayer(null);
                    if (manager != null) manager.release();
                } finally {
                    if (activity != null) activity.finish();
                }
            });
        } finally {
            try {
                if (saved != null) main(() -> SETTINGS.keySet().forEach(key -> {
                    if (saved.containsKey(key)) Prefers.put(key, saved.get(key));
                    else Prefers.remove(key);
                }));
            } finally {
                if (fixture != null) assertTrue("Delete generated media", !fixture.exists() || fixture.delete());
            }
        }
    }

    @Test public void shouldPresentOncePerMediaAcrossPauseResumeSeekAndLateMetadata() {
        start("capsule-first-media");
        await(capsule::isActive, "first playback presentation");
        assertFalse(read(controller::isPending));
        await(() -> !capsule.isActive(), "natural presentation expiry");
        main(() -> manager.getPlayer().pause());
        await(() -> !manager.isPlaying(), "paused playback");
        main(() -> {
            manager.seekTo(1500);
            manager.getPlayer().play();
        });
        await(manager::isPlaying, "playback after seeking and resuming");
        main(() -> {
            for (int event = 0; event < 8; event++) controller.onMetadataChanged();
        });
        assertRemainsHidden(1800);
        assertFalse("Only a new media request may arm another presentation", read(controller::isPending));
        start("capsule-second-media");
        await(capsule::isActive, "new media presentation");
        assertFalse(read(controller::isPending));
    }

    @Test public void shouldCancelQueuedUpdatesWhenTheHostStopsAndRequireANewRequest() {
        start("capsule-lifecycle-media");
        await(capsule::isActive, "playing presentation");
        main(() -> {
            controller.onMetadataChanged();
            controller.setStarted(false);
            assertFalse(capsule.isActive());
            assertFalse(controller.isPending());
            controller.onMetadataChanged();
        });
        assertRemainsHidden(400);
        main(() -> {
            controller.setStarted(true);
            controller.onMetadataChanged();
        });
        assertRemainsHidden(400);
        start("capsule-after-lifecycle-media");
        await(capsule::isActive, "new request after host restart");
    }

    private void start(String key) {
        main(() -> {
            controller.begin();
            assertTrue(controller.isPending());
            assertFalse(capsule.isActive());
            manager.start(PlaySpec.from(key, Uri.fromFile(fixture).toString(), Map.of(), MediaMetadata.EMPTY), 12_000, 0);
        });
        await(manager::isPlaying, "local PCM playback");
    }

    private void assertRemainsHidden(long millis) {
        long deadline = SystemClock.elapsedRealtime() + millis;
        do {
            assertNull(error.get());
            assertFalse("Existing playback must not replay the capsule", read(capsule::isActive));
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
    }

    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 12_000;
        do {
            assertNull(error.get());
            if (read(condition::getAsBoolean)) return;
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Timed out: " + message);
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void main(Runnable task) { read(() -> { task.run(); return null; }); }
    private <T> T read(Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(task.get()); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        if (failure.get() != null) throw new AssertionError("Controller main-thread operation failed", failure.get());
        return result.get();
    }

    private static void writeWav(File file) throws Exception {
        int rate = 16_000, samples = rate * 25, bytes = samples * 2;
        ByteBuffer out = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        out.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        for (int i = 0; i < samples; i++) out.putShort((short) (1000 * Math.sin(2 * Math.PI * 220 * i / rate)));
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(out.array()); }
    }

    private final class Callback implements PlayerManager.Callback {
        @Override public void onPrepare() {}
        @Override public void onTracksChanged() {}
        @Override public void onDecodeChanged() {}
        @Override public void onMediaOptionsChanged() {}
        @Override public void onError(String message) { error.set(message); }
        @Override public void onPlayerRebuild(Player player) { error.set("Unexpected player replacement"); }
        @Override public void onDanmakuSourceChanged(Uri uri) {}
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) {}
        @Override public void onDanmakuEnabledChanged(boolean enabled) {}
        @Override public void onDanmakuSent(String text) {}
    }
}
