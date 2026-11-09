package com.fongmi.android.tv.player.exo;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

/** Runs the exact production adapter and Media3 decoder against synthetic H5E media. */
@RunWith(AndroidJUnit4.class)
public final class H5ePlaybackTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test(timeout = 15000)
    public void realHttpSourceRestoresOriginalBytesAndTransformedRange() throws Exception {
        byte[] clear = asset("clear.ts");
        byte[] encrypted = asset("cdrmldcctv-synthetic.ts");
        assertFalse(Arrays.equals(clear, encrypted));
        try (FixtureServer server = new FixtureServer(Map.of("/cdrmldcctv-synthetic.ts", encrypted))) {
            DataSource source = factory().createDataSource();
            try {
                assertEquals(clear.length, source.open(new DataSpec(Uri.parse(server.url("/cdrmldcctv-synthetic.ts")))));
                assertArrayEquals(clear, read(source));
            } finally { source.close(); }
            try {
                source.open(new DataSpec.Builder().setUri(server.url("/cdrmldcctv-synthetic.ts"))
                        .setPosition(31).setLength(1001).build());
                assertArrayEquals(Arrays.copyOfRange(clear, 31, 1032), read(source));
            } finally { source.close(); }
            assertEquals(2, server.count("/cdrmldcctv-synthetic.ts"));
        }
    }

    @Test(timeout = 35000)
    public void exoRendersSyntheticH5eVideoAndThenOrdinaryHls() throws Exception {
        Map<String, byte[]> routes = Map.of(
                "/encrypted.m3u8", asset("encrypted.m3u8"),
                "/clear.m3u8", asset("clear.m3u8"),
                "/cdrmldcctv-synthetic.ts", asset("cdrmldcctv-synthetic.ts"),
                "/clear.ts", asset("clear.ts"));
        Intent intent = new Intent(instrumentation.getContext(), H5ePlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        H5ePlaybackActivity activity = (H5ePlaybackActivity) instrumentation.startActivitySync(intent);
        AtomicReference<ExoPlayer> player = new AtomicReference<>();
        AtomicReference<PlaybackException> error = new AtomicReference<>();
        AtomicBoolean ready = new AtomicBoolean();
        AtomicInteger firstFrames = new AtomicInteger();
        try (FixtureServer server = new FixtureServer(routes)) {
            instrumentation.runOnMainSync(() -> {
                ExoPlayer exo = new ExoPlayer.Builder(activity)
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(factory())).build();
                player.set(exo);
                exo.setVolume(0);
                exo.setVideoSurfaceView(activity.video);
                exo.addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException failure) { error.set(failure); }
                    @Override public void onRenderedFirstFrame() { firstFrames.incrementAndGet(); }
                    @Override public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) ready.set(true);
                    }
                });
            });
            for (String playlist : new String[]{"/encrypted.m3u8", "/clear.m3u8"}) {
                ready.set(false);
                int previousFrames = firstFrames.get();
                instrumentation.runOnMainSync(() -> {
                    ExoPlayer exo = player.get();
                    exo.setMediaItem(new MediaItem.Builder().setUri(server.url(playlist))
                            .setMimeType(MimeTypes.APPLICATION_M3U8).build());
                    exo.prepare();
                    exo.play();
                });
                long deadline = SystemClock.elapsedRealtime() + 12000;
                while (error.get() == null && SystemClock.elapsedRealtime() < deadline) {
                    AtomicBoolean decoded = new AtomicBoolean();
                    instrumentation.runOnMainSync(() -> {
                        ExoPlayer exo = player.get();
                        decoded.set(ready.get() && firstFrames.get() > previousFrames
                                && exo.getCurrentPosition() >= 1500
                                && exo.getVideoSize().width == 320 && exo.getVideoSize().height == 180
                                && exo.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO)
                                && exo.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO));
                    });
                    if (decoded.get()) break;
                    SystemClock.sleep(30);
                }
                assertNull("Actual Exo decode error for " + playlist, error.get());
                assertTrue("No decoded frame for " + playlist, firstFrames.get() > previousFrames);
                instrumentation.runOnMainSync(() -> {
                    assertTrue("Playback did not advance", player.get().getCurrentPosition() >= 1500);
                    assertEquals(320, player.get().getVideoSize().width);
                    assertEquals(180, player.get().getVideoSize().height);
                    assertTrue(player.get().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO));
                });
            }
            assertTrue(server.count("/cdrmldcctv-synthetic.ts") > 0);
            assertTrue(server.count("/clear.ts") > 0);
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (player.get() != null) player.get().release();
                activity.finish();
            });
        }
    }

    private DataSource.Factory factory() {
        return new H5eDataSource.Factory(new OkHttpDataSource.Factory(new OkHttpClient()));
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream input = instrumentation.getContext().getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static byte[] read(DataSource input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[137];
        for (int count; (count = input.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT;)
            output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static final class FixtureServer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        private final Map<String, byte[]> routes;
        private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        private final Thread worker;

        FixtureServer(Map<String, byte[]> routes) throws IOException {
            this.routes = routes;
            worker = new Thread(this::serve, "H5E synthetic HTTP");
            worker.setDaemon(true);
            worker.start();
        }
        String url(String path) { return "http://127.0.0.1:" + server.getLocalPort() + path; }
        int count(String path) { return requests.getOrDefault(path, new AtomicInteger()).get(); }
        private void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String request = reader.readLine();
                    if (request == null) continue;
                    String path = request.split(" ")[1].split("\\?", 2)[0];
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {}
                    byte[] body = routes.get(path);
                    requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
                    String status = body == null ? "404 Not Found" : "200 OK";
                    if (body == null) body = new byte[0];
                    String type = path.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t";
                    socket.getOutputStream().write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type
                            + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                } catch (IOException error) { if (server.isClosed()) return; }
            }
        }
        @Override public void close() throws Exception { server.close(); worker.join(3000); }
    }
}
