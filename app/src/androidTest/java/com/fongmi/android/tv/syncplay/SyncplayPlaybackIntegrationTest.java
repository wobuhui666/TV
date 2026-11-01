package com.fongmi.android.tv.syncplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Explicit sourceprobe-only test against an official Syncplay server forwarded over adb reverse. */
@RunWith(AndroidJUnit4.class)
public final class SyncplayPlaybackIntegrationTest {
    private static final String PACKAGE = "com.fongmi.android.tv.sourceprobe";
    private static final String TITLE = "Syncplay private fixture";
    private static final String TV_USER = "TVFixture";
    private static final Map<String, Object> SETTINGS = Map.ofEntries(
            Map.entry("player_engine", PlayerSetting.ENGINE_EXO), Map.entry("tunnel", false),
            Map.entry("preload", false), Map.entry("preload_next", false),
            Map.entry("audio_effect_preset", 0), Map.entry("audio_pass_through", false),
            Map.entry("ai_subtitle_enabled", false), Map.entry("community_skip_enabled", false));

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final AtomicReference<String> playbackError = new AtomicReference<>();
    private final AtomicInteger mainThreadNetwork = new AtomicInteger();
    private final JSONArray checks = new JSONArray();
    private final JSONObject report = new JSONObject();
    private final Object owner = new Object();
    private Map<String, ?> saved;
    private PlayerManager manager;
    private Peer peer;
    private StrictMode.ThreadPolicy previousPolicy;
    private String stage = "arguments";

    @Test public void officialRoomControlsRealExoAndLeavesLocalPlaybackIndependent() throws Exception {
        // Reject user installations before opening files, changing preferences, or touching a player.
        assertEquals("This test is restricted to the disposable sourceprobe app", PACKAGE, target.getPackageName());
        assertTrue("StrictMode listener coverage requires Android 9 or later", Build.VERSION.SDK_INT >= 28);
        Bundle args = InstrumentationRegistry.getArguments();
        assertEquals("Explicit loopback plaintext opt-in is required", "true", args.getString("syncplay_allow_plaintext"));
        int port = Integer.parseInt(required(args, "syncplay_port"));
        String room = required(args, "syncplay_room");
        String password = args.getString("syncplay_password", "");
        File files = target.getFilesDir().getCanonicalFile();
        File media = new File(files, required(args, "syncplay_media_file")).getCanonicalFile();
        assertTrue("Fixture must be a private MP4 inside sourceprobe files",
                media.getPath().startsWith(files.getPath() + File.separator)
                        && media.getName().endsWith(".mp4") && media.isFile()
                        && media.length() > 0 && media.length() <= 16 * 1024 * 1024);
        SyncplayConfig config = new SyncplayConfig("127.0.0.1", port, TV_USER, room, password, false);
        SyncplayConfig peerConfig = new SyncplayConfig("127.0.0.1", port, "PeerFixture", room, password, false);
        report.put("scope", "SOURCEPROBE_REAL_EXO_OFFICIAL_LOOPBACK_SERVER")
                .put("mediaSha256", sha256(media)).put("tls", false)
                .put("remoteServer", "127.0.0.1").put("checks", checks).put("passed", false);
        long started = SystemClock.elapsedRealtime();
        boolean complete = false;
        boolean closed = false;
        boolean restored = false;
        try {
            saved = new HashMap<>(Prefers.getPrefers().getAll());
            SETTINGS.forEach(Prefers::put);
            stage = "prepare-real-player";
            main(() -> {
                assertEquals(SyncplaySession.Phase.OFFLINE, SyncplaySession.get().status().phase);
                manager = new PlayerManager(new PlayerManager.Callback() {
                    @Override public void onPrepare() { }
                    @Override public void onTracksChanged() { }
                    @Override public void onDecodeChanged() { }
                    @Override public void onMediaOptionsChanged() { }
                    @Override public void onError(String message) { playbackError.set("manager_error"); }
                    @Override public void onPlayerRebuild(Player player) { playbackError.set("unexpected_player_rebuild"); }
                    @Override public void onDanmakuSourceChanged(Uri uri) { }
                    @Override public void onDanmakuConfigChanged(DanmakuConfig config) { }
                    @Override public void onDanmakuEnabledChanged(boolean enabled) { }
                    @Override public void onDanmakuSent(String text) { }
                });
                assertTrue("The real manager must create ExoPlayer", manager.getPlayer() instanceof ExoPlayer);
                manager.getPlayer().addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException error) {
                        playbackError.set("exo_" + error.errorCode);
                    }
                });
                manager.start(PlaySpec.from("syncplay-private-fixture", Uri.fromFile(media).toString(),
                        Collections.emptyMap(), PlayerManager.buildMetadata(TITLE, "", null)), 15_000, 0);
                manager.pause();
                manager.getPlayer().setVolume(0);
            });
            awaitMain(() -> player().getPlaybackState() == Player.STATE_READY
                    && player().isCurrentMediaItemSeekable() && hasSelectedAudio(player().getCurrentTracks()), 10_000);
            long duration = value(() -> player().getDuration());
            assertTrue("Use a 25–60 second H264/AAC fixture", duration >= 25_000 && duration <= 60_500);
            checked("real_exo_ready_with_selected_audio");
            report.put("durationMs", duration);

            stage = "join-official-room";
            main(() -> {
                player().setPlaybackParameters(new PlaybackParameters(1.25f));
                previousPolicy = StrictMode.getThreadPolicy();
                StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectNetwork()
                        .penaltyListener(Runnable::run, violation -> mainThreadNetwork.incrementAndGet()).build());
                SyncplaySession.get().attach(owner, () -> manager);
                SyncplaySession.get().join(config);
            });
            awaitMain(() -> SyncplaySession.get().status().phase == SyncplaySession.Phase.JOINED
                    && SyncplaySession.get().status().gate == SyncplaySynchronizer.Gate.READY, 8_000);
            assertEquals(1f, value(() -> player().getPlaybackParameters().speed), .001f);
            assertFalse(value(() -> SyncplaySession.get().status().encrypted));
            peer = new Peer();
            peer.client.setFile(TITLE, duration / 1000.0);
            peer.client.connect(peerConfig);
            await(() -> peer.username != null && peer.members.size() == 2
                    && peer.members.stream().allMatch(member -> TITLE.equals(member.filename)), 8_000);
            awaitMain(() -> SyncplaySession.get().status().members == 2
                    && SyncplaySession.get().status().gate == SyncplaySynchronizer.Gate.READY, 8_000);
            checked("production_session_and_peer_join_same_media");

            stage = "remote-play";
            peer.client.localChange(5, false, true);
            awaitMain(() -> player().isPlaying() && player().getCurrentPosition() >= 4_800
                    && player().getCurrentPosition() < 7_500, 8_000);
            long running = value(() -> player().getCurrentPosition());
            awaitMain(() -> player().getCurrentPosition() >= running + 500, 3_000);
            report.put("playingClockAdvanceMs", value(() -> player().getCurrentPosition()) - running);
            checked("remote_play_advances_actual_exo_clock");

            stage = "remote-pause";
            peer.client.localChange(8, true, false);
            awaitMain(() -> !player().getPlayWhenReady() && !player().isPlaying()
                    && Math.abs(player().getCurrentPosition() - 8_000) < 100, 8_000);
            long paused = value(() -> player().getCurrentPosition());
            SystemClock.sleep(700);
            long pauseDrift = Math.abs(value(() -> player().getCurrentPosition()) - paused);
            assertTrue("A paused real player must stop its clock", pauseDrift <= 50);
            report.put("pausedClockDriftMs", pauseDrift);
            checked("remote_pause_stops_actual_exo_clock");

            stage = "remote-seek-and-nearby-user-seek";
            peer.client.localChange(12, true, true);
            awaitMain(() -> !player().getPlayWhenReady()
                    && Math.abs(player().getCurrentPosition() - 12_000) <= 50, 8_000);
            long observedSeekAt = SystemClock.elapsedRealtime();
            int before = peer.states.size();
            // This is intentionally only 120 ms from the remote target and within its 2.5 s
            // echo window. A new user SEEK must still be sent through the real session.
            main(() -> player().seekTo(12_120));
            long userSeekDelay = SystemClock.elapsedRealtime() - observedSeekAt;
            assertTrue("User seek must run inside the remote echo window", userSeekDelay < 2_000);
            report.put("userSeekAfterRemoteObservedMs", userSeekDelay);
            await(() -> peer.hasUserSeekSince(before, 12.120), 8_000);
            assertTrue("The local user seek must remain applied", Math.abs(value(() -> player().getCurrentPosition()) - 12_120) <= 50);
            checked("remote_seek_reaches_real_exo");
            checked("nearby_user_seek_is_broadcast_inside_echo_window");

            stage = "leave-restores-speed-and-local-control";
            main(() -> SyncplaySession.get().leave());
            assertEquals(SyncplaySession.Phase.OFFLINE, value(() -> SyncplaySession.get().status().phase));
            assertEquals(1.25f, value(() -> player().getPlaybackParameters().speed), .001f);
            await(() -> peer.members.size() == 1, 8_000);
            main(() -> { manager.pause(); player().seekTo(4_000); });
            peer.client.localChange(20, false, true);
            SystemClock.sleep(1_200);
            assertFalse("A peer must not resume the player after leave", value(() -> player().getPlayWhenReady()));
            assertTrue("A peer must not seek the player after leave", Math.abs(value(() -> player().getCurrentPosition()) - 4_000) <= 50);
            main(() -> manager.play());
            awaitMain(() -> player().isPlaying() && player().getCurrentPosition() >= 4_500, 3_000);
            assertEquals(1.25f, value(() -> player().getPlaybackParameters().speed), .001f);
            checked("leave_restores_previous_speed");
            checked("leave_ignores_peer_and_keeps_local_play_seek");
            assertEquals("Syncplay connect/control/close must not perform main-thread network I/O", 0, mainThreadNetwork.get());
            checked("main_thread_strictmode_network_clean");
            complete = true;
        } finally {
            try {
                try {
                    SyncplaySession.Status status = value(() -> SyncplaySession.get().status());
                    report.put("sessionBeforeCleanup", new JSONObject().put("phase", status.phase.name())
                            .put("gate", status.gate.name()).put("reason", status.reason).put("members", status.members));
                } catch (Throwable ignored) { /* Diagnostics must not prevent cleanup. */ }
                if (peer != null) peer.client.close();
                main(() -> {
                    try {
                        try { SyncplaySession.get().detach(owner); }
                        finally { if (manager != null) manager.release(); }
                    } finally {
                        if (previousPolicy != null) StrictMode.setThreadPolicy(previousPolicy);
                    }
                });
                assertEquals("Cleanup must also avoid main-thread network I/O", 0, mainThreadNetwork.get());
                closed = true;
            } finally {
                try {
                    restoreSettings();
                    restored = true;
                    report.put("preferencesRestored", true);
                } finally {
                    report.put("passed", complete && closed && restored && mainThreadNetwork.get() == 0)
                            .put("playerAndSessionClosed", closed)
                            .put("lastStage", stage).put("mainThreadNetworkViolations", mainThreadNetwork.get())
                            .put("elapsedMs", SystemClock.elapsedRealtime() - started);
                    if (playbackError.get() != null) report.put("playbackErrorCode", playbackError.get());
                    File destination = new File(files, "source-validation/syncplay-playback-result.json");
                    assertTrue("Could not create private result directory", destination.getParentFile().isDirectory()
                            || destination.getParentFile().mkdirs());
                    try (FileOutputStream output = new FileOutputStream(destination)) {
                        output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
    }

    private final class Peer implements SyncplayClient.Listener {
        final SyncplayClient client = new SyncplayClient(this);
        final List<SyncplayProtocol.RemoteState> states = new CopyOnWriteArrayList<>();
        volatile List<SyncplayProtocol.Member> members = Collections.emptyList();
        volatile String username, error;
        @Override public void onTransport(boolean encrypted) { if (encrypted) error = "unexpected_tls"; }
        @Override public void onHello(String username, String room) { this.username = username; }
        @Override public void onState(SyncplayProtocol.RemoteState state) { states.add(state); }
        @Override public void onMembers(List<SyncplayProtocol.Member> members, boolean complete) { this.members = new ArrayList<>(members); }
        @Override public void onError(String message) { error = "peer_server_error"; }
        @Override public void onDisconnected(String reason) { error = "peer_disconnected"; }
        boolean hasUserSeekSince(int index, double targetSeconds) {
            for (int i = index; i < states.size(); i++) {
                SyncplayProtocol.RemoteState state = states.get(i);
                if (TV_USER.equals(state.setBy) && state.seek && state.paused
                        && Math.abs(state.position - targetSeconds) < .04) return true;
            }
            return false;
        }
    }

    private Player player() { return manager.getPlayer(); }
    private static boolean hasSelectedAudio(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) return true;
        }
        return false;
    }
    private void checked(String name) { checks.put(name); }
    private void awaitMain(BooleanSupplier condition, long timeout) { await(() -> value(condition::getAsBoolean), timeout); }
    private void await(BooleanSupplier condition, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        do {
            assertTrue("Playback failed at " + stage + ": " + playbackError.get(), playbackError.get() == null);
            assertTrue("Peer failed at " + stage, peer == null || peer.error == null);
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Timed out at " + stage);
    }
    private void main(Runnable action) { value(() -> { action.run(); return null; }); }
    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.get()); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() != null) throw new AssertionError("Main-thread operation failed at " + stage, error.get());
        return result.get();
    }
    private static String required(Bundle args, String key) {
        String value = args.getString(key);
        assertNotNull("Missing explicit instrumentation argument: " + key, value);
        assertFalse("Empty instrumentation argument: " + key, value.trim().isEmpty());
        return value;
    }
    private void restoreSettings() {
        if (saved == null) return;
        SharedPreferences.Editor editor = Prefers.getPrefers().edit();
        for (String key : SETTINGS.keySet()) {
            Object old = saved.get(key);
            if (old == null) editor.remove(key);
            else if (old instanceof Boolean value) editor.putBoolean(key, value);
            else if (old instanceof Integer value) editor.putInt(key, value);
            else if (old instanceof Long value) editor.putLong(key, value);
            else if (old instanceof Float value) editor.putFloat(key, value);
            else if (old instanceof String value) editor.putString(key, value);
            else if (old instanceof Set<?> values) {
                Set<String> strings = new HashSet<>();
                for (Object value : values) strings.add((String) value);
                editor.putStringSet(key, strings);
            }
        }
        assertTrue("Could not restore sourceprobe preferences", editor.commit());
        for (String key : SETTINGS.keySet()) assertEquals("Setting was not restored: " + key,
                saved.get(key), Prefers.getPrefers().getAll().get(key));
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[8192];
            int size;
            while ((size = input.read(bytes)) != -1) digest.update(bytes, 0, size);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
