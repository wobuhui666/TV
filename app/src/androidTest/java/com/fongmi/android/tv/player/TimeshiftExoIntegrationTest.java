package com.fongmi.android.tv.player;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Timeshift;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.utils.Prefers;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

import static org.junit.Assert.*;

/** Uses production PlayerManager/Exo with generated PCM WAV; no native media extensions or fixtures. */
@RunWith(AndroidJUnit4.class)
public class TimeshiftExoIntegrationTest {

    private static final Map<String, Object> SETTINGS = Map.ofEntries(
            Map.entry("player_engine", PlayerSetting.ENGINE_EXO), Map.entry("tunnel", false),
            Map.entry("preload", false), Map.entry("preload_next", false),
            Map.entry("audio_effect_preset", 0), Map.entry("audio_pass_through", false),
            Map.entry("audio_prefer", false), Map.entry("video_prefer", false), Map.entry("av3a", false),
            Map.entry("subtitle_libass", false), Map.entry("ai_subtitle_enabled", false), Map.entry("community_skip_enabled", false));

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicReference<String> error = new AtomicReference<>();
    private PlayerManager manager;
    private MediaServer server;
    private final AtomicInteger injectedDecodeErrors = new AtomicInteger();
    private final AtomicInteger fatalDecodeErrors = new AtomicInteger();
    private final AtomicInteger rebuilds = new AtomicInteger();
    private boolean injectDecodeOnPrepare;
    private boolean allowRebuild;

    @Test public void shouldApplyReplayAndLiveSourcesToRealExoAndKeepPausedPlaybackOnResolverFailure() throws Exception {
        Map<String, ?> saved = new HashMap<>(Prefers.getPrefers().getAll());
        boolean passed = false;
        boolean restored = false;
        String stage = "setup";
        try {
            main(() -> SETTINGS.forEach(Prefers::put));
            server = new MediaServer();
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            main(() -> {
                manager = new PlayerManager(new Callback());
                assertTrue("The production manager must create Exo", manager.getPlayer() instanceof ExoPlayer);
                manager.getPlayer().addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException failure) { error.set("Exo error " + failure.errorCode); }
                });
                Result result = new Result();
                result.setUrl(server.base() + "/initial.wav");
                result.setFormat("audio/wav");
                result.setHeader(Map.of("X-Timeshift-Fixture", "initial"));
                result.setTimeshift(new Timeshift(server.base() + "/resolve?time={time}", System.currentTimeMillis() - 120_000, 60_000));
                manager.start(PlaySpec.from(result, "timeshift-exo-fixture", MediaMetadata.EMPTY), 20_000);
                manager.getPlayer().setVolume(0);
            });

            stage = "initial-live-ready";
            awaitReady("/initial.wav");
            assertTrue(server.mediaRequests.contains("initial"));
            main(() -> {
                assertTrue(manager.getTimeshift().isActive());
                assertFalse(manager.getTimeshift().isReplaying());
                assertEquals(60_000, manager.getDuration());
                assertEquals(12_000, manager.getPlayer().getDuration(), 5);
                manager.pause();
                manager.setSpeed(1.25f);
            });

            stage = "replay-ready";
            seek(20_000).get(8, TimeUnit.SECONDS);
            awaitReady("/replay.wav");
            assertTrue(server.mediaRequests.contains("replay"));
            assertTrue(server.times.get(0) > 0);
            main(() -> {
                assertTrue(manager.getTimeshift().isReplaying());
                assertPaused();
                assertEquals("replay", manager.getHeaders().get("X-Timeshift-Fixture"));
            });

            stage = "resolver-failure-keeps-replay";
            MediaItem previous = read(manager::getCurrentMediaItem);
            Player previousPlayer = read(manager::getPlayer);
            String previousUrl = read(manager::getUrl);
            server.failResolution = true;
            seek(10_000).get(8, TimeUnit.SECONDS);
            main(() -> {
                assertSame(previousPlayer, manager.getPlayer());
                assertEquals(previous, manager.getCurrentMediaItem());
                assertEquals(previousUrl, manager.getUrl());
                assertEquals(Player.STATE_READY, manager.getPlaybackState());
                assertTrue(manager.getTimeshift().isReplaying());
                assertPaused();
            });

            stage = "return-live-ready";
            server.failResolution = false;
            seek(C.TIME_UNSET).get(8, TimeUnit.SECONDS);
            awaitReady("/live.wav");
            assertEquals(Long.valueOf(0), server.times.get(server.times.size() - 1));
            assertTrue(server.mediaRequests.contains("live"));
            main(() -> {
                assertFalse(manager.getTimeshift().isReplaying());
                assertPaused();
                assertEquals("live", manager.getHeaders().get("X-Timeshift-Fixture"));
            });
            assertEquals("Media credentials must not be forwarded to the resolver", 0, server.unexpectedHeaders.get());
            assertNull(error.get());
            passed = true;
        } finally {
            try {
                main(() -> { if (manager != null) manager.release(); });
            } finally {
                if (server != null) server.stop();
                restorePreferences(saved);
                restored = true;
                writeReport("timeshift-exo.json", passed, restored, stage);
            }
        }
    }

    @Test public void shouldKeepTheDecodeRetryBudgetAcrossSuccessfulTimeshiftResolution() throws Exception {
        Map<String, ?> saved = new HashMap<>(Prefers.getPrefers().getAll());
        boolean passed = false;
        try {
            main(() -> SETTINGS.forEach(Prefers::put));
            server = new MediaServer();
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            main(() -> {
                allowRebuild = true;
                manager = new PlayerManager(new Callback());
                Result result = new Result();
                result.setUrl(server.base() + "/initial.wav");
                result.setFormat("audio/wav");
                result.setHeader(Map.of("X-Timeshift-Fixture", "initial"));
                result.setTimeshift(new Timeshift(server.base() + "/resolve?time={time}", System.currentTimeMillis() - 120_000, 60_000));
                manager.start(PlaySpec.from(result, "timeshift-decode-budget-fixture", MediaMetadata.EMPTY), 20_000);
                manager.pause();
                manager.getPlayer().setVolume(0);
            });
            awaitReady("/initial.wav");
            main(() -> {
                // The next error is injected at the actual replacement's onPrepare callback,
                // after HTTP resolution and applyTimeshift, before that decoder can reach READY.
                injectDecodeOnPrepare = true;
                injectDecodeFailure();
            });
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (injectedDecodeErrors.get() < 2 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            main(() -> {
                assertEquals(2, injectedDecodeErrors.get());
                assertEquals("Second decode error must exhaust the same stream's budget", 1, fatalDecodeErrors.get());
                assertEquals("Only the first error may rebuild the decoder", 1, rebuilds.get());
            });
            assertEquals("A failed replacement must not resolve endlessly", 1, server.times.size());
            assertNull(error.get());
            passed = true;
        } finally {
            try {
                main(() -> { if (manager != null) manager.release(); });
            } finally {
                if (server != null) server.stop();
                restorePreferences(saved);
                writeReport("timeshift-exo-decode-retry.json", passed, true, "decode-retry-budget");
            }
        }
    }

    private void injectDecodeFailure() {
        try {
            Method method = PlayerManager.class.getDeclaredMethod("handleDecodeError", PlaybackException.class);
            method.setAccessible(true);
            injectedDecodeErrors.incrementAndGet();
            method.invoke(manager, new PlaybackException("Injected decoder failure", null, PlaybackException.ERROR_CODE_DECODING_FAILED));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not drive the production decode failure path", failure);
        }
    }

    private void restorePreferences(Map<String, ?> saved) {
        main(() -> {
            for (String key : SETTINGS.keySet()) {
                if (saved.containsKey(key)) Prefers.put(key, saved.get(key));
                else Prefers.remove(key);
            }
        });
        Map<String, ?> current = Prefers.getPrefers().getAll();
        for (String key : SETTINGS.keySet()) {
            assertEquals("Restore presence of " + key, saved.containsKey(key), current.containsKey(key));
            assertEquals("Restore " + key, saved.get(key), current.get(key));
        }
    }

    private void assertPaused() {
        assertFalse("Applying a provider result must retain pause", manager.getPlayer().getPlayWhenReady());
        assertEquals(1.25f, manager.getSpeed(), .001f);
        assertEquals(PlayerSetting.ENGINE_EXO, manager.getEngine());
    }

    private ListenableFuture<?> seek(long position) {
        return read(() -> manager.getTimeshift().seekTo(position));
    }

    private void awaitReady(String path) {
        await(() -> {
            MediaItem item = manager.getCurrentMediaItem();
            return manager.getPlaybackState() == Player.STATE_READY && item != null && item.localConfiguration != null
                    && path.equals(item.localConfiguration.uri.getPath()) && manager.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO);
        }, "Exo READY with PCM audio at " + path);
    }

    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < end) {
            assertNull(error.get());
            if (read(condition::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail(message + "; state=" + read(manager::getPlaybackState));
    }

    private void main(Runnable task) {
        read(() -> { task.run(); return null; });
    }

    private <T> T read(Supplier<T> task) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(task.get()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Timeshift Exo main-thread check failed", failure.get());
        return value.get();
    }

    private void writeReport(String filename, boolean passed, boolean restored, String stage) throws Exception {
        Context context = instrumentation.getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "upstream-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject report = new JSONObject().put("scope", "PRODUCTION_PLAYER_MANAGER_EXO_PCM_WAV")
                .put("passed", passed && restored).put("preferencesRestored", restored).put("lastStage", stage)
                .put("videoDecoded", false).put("mpvExecuted", false)
                .put("mediaRequests", server == null ? 0 : server.mediaRequests.size())
                .put("resolverRequests", server == null ? 0 : server.times.size())
                .put("injectedDecodeErrors", injectedDecodeErrors.get()).put("fatalDecodeErrors", fatalDecodeErrors.get()).put("decoderRebuilds", rebuilds.get());
        if (error.get() != null) report.put("playbackError", error.get());
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private final class Callback implements PlayerManager.Callback {
        @Override public void onPrepare() {
            if (injectDecodeOnPrepare) {
                injectDecodeOnPrepare = false;
                injectDecodeFailure();
            }
        }
        @Override public void onTracksChanged() {}
        @Override public void onDecodeChanged() {}
        @Override public void onMediaOptionsChanged() {}
        @Override public void onError(String message) {
            if (injectedDecodeErrors.get() == 2) fatalDecodeErrors.incrementAndGet();
            else error.set(message);
        }
        @Override public void onPlayerRebuild(Player player) {
            rebuilds.incrementAndGet();
            if (!allowRebuild) error.set("Unexpected engine replacement");
        }
        @Override public void onDanmakuSourceChanged(Uri uri) {}
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) {}
        @Override public void onDanmakuEnabledChanged(boolean enabled) {}
        @Override public void onDanmakuSent(String text) {}
    }

    private static final class MediaServer extends NanoHTTPD {
        private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");
        final List<Long> times = new CopyOnWriteArrayList<>();
        final List<String> mediaRequests = new CopyOnWriteArrayList<>();
        final AtomicInteger unexpectedHeaders = new AtomicInteger();
        private final byte[] wav = wav();
        volatile boolean failResolution;

        MediaServer() { super("127.0.0.1", 0); }
        String base() { return "http://127.0.0.1:" + getListeningPort(); }

        @Override protected boolean useGzipWhenAccepted(Response response) { return false; }

        @Override public Response serve(IHTTPSession session) {
            String path = session.getUri();
            String header = session.getHeaders().get("x-timeshift-fixture");
            if (path.equals("/resolve")) {
                long time = Long.parseLong(session.getParms().getOrDefault("time", "0"));
                times.add(time);
                if (header != null) unexpectedHeaders.incrementAndGet();
                if (failResolution) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "unavailable");
                String stream = time == 0 ? "live" : "replay";
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"parse\":0,\"format\":\"audio/wav\",\"url\":\""
                        + base() + "/" + stream + ".wav\",\"header\":{\"X-Timeshift-Fixture\":\"" + stream + "\"}}");
            }
            String stream = path.equals("/initial.wav") ? "initial" : path.equals("/replay.wav") ? "replay" : path.equals("/live.wav") ? "live" : "";
            if (stream.isEmpty()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "unknown fixture");
            if (!stream.equals(header)) {
                unexpectedHeaders.incrementAndGet();
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "wrong media header");
            }
            mediaRequests.add(stream);
            int start = 0, end = wav.length - 1;
            String range = session.getHeaders().get("range");
            if (range != null) {
                Matcher match = RANGE.matcher(range);
                if (!match.matches()) return unsatisfiable();
                try {
                    start = Math.toIntExact(Long.parseLong(match.group(1)));
                    if (!match.group(2).isEmpty()) end = (int) Math.min(end, Long.parseLong(match.group(2)));
                } catch (ArithmeticException | NumberFormatException invalid) { return unsatisfiable(); }
                if (start < 0 || start > end || start >= wav.length) return unsatisfiable();
            }
            int length = end - start + 1;
            ByteArrayInputStream input = session.getMethod() == Method.HEAD ? new ByteArrayInputStream(new byte[0]) : new ByteArrayInputStream(wav, start, length);
            Response response = newFixedLengthResponse(range == null ? Response.Status.OK : Response.Status.PARTIAL_CONTENT, "audio/wav", input, length);
            response.addHeader("Accept-Ranges", "bytes");
            if (range != null) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + wav.length);
            return response;
        }

        private Response unsatisfiable() {
            Response response = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "invalid range");
            response.addHeader("Content-Range", "bytes */" + wav.length);
            return response;
        }

        private static byte[] wav() {
            int rate = 16_000, samples = rate * 12, bytes = samples * 2;
            ByteBuffer out = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
            out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
            out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
            out.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
            out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
            for (int i = 0; i < samples; i++) out.putShort((short) (1000 * Math.sin(2 * Math.PI * 220 * i / rate)));
            return out.array();
        }
    }
}
