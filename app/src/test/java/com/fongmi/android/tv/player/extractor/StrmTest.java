package com.fongmi.android.tv.player.extractor;

import static org.junit.Assert.assertEquals;

import com.github.catvod.net.OkHttp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

public class StrmTest {

    private final Strm extractor = new Strm();
    private MockWebServer server;
    private OkHttpClient client;
    private Field transport;
    private Object previous;

    @Before
    public void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build();
        transport = OkHttp.class.getDeclaredField("client");
        transport.setAccessible(true);
        previous = transport.get(OkHttp.get());
        transport.set(OkHttp.get(), client);
    }

    @After
    public void closeServer() throws Exception {
        if (transport != null) transport.set(OkHttp.get(), previous);
        if (client != null) {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
        if (server != null) server.close();
    }

    @Test
    public void shouldResolvePointerWithoutContentDisposition() throws Exception {
        String target = "https://media.example/movie.mkv?token=fixture";
        server.enqueue(new MockResponse().setBody(target));

        assertEquals(target, extractor.fetch(server.url("/movie.strm").toString()));
    }

    @Test
    public void shouldReadFirstLineAfterBomWhitespaceAndCrLf() throws Exception {
        String target = "https://media.example/movie.mkv";
        server.enqueue(new MockResponse().setBody("\uFEFF  " + target + " \r\nignored second line"));

        assertEquals(target, extractor.fetch(server.url("/movie.strm").toString()));
    }

    @Test
    public void shouldKeepOriginalUrlForInvalidContent() throws Exception {
        for (String body : new String[]{"", "  \r\nhttps://media.example/second", "<html>unavailable</html>", "https://media.example/bad target"}) {
            server.enqueue(new MockResponse().setBody(body).addHeader("Content-Disposition", "attachment; filename=movie.strm"));
            String original = server.url("/movie.strm").toString();
            assertEquals(original, extractor.fetch(original));
        }
    }

    @Test
    public void shouldKeepOriginalUrlForRedirectAndErrorResponses() throws Exception {
        for (int status : new int[]{302, 404, 500}) {
            server.enqueue(new MockResponse().setResponseCode(status).addHeader("Location", "/target")
                    .setBody("https://media.example/should-not-be-used"));
            String original = server.url("/movie.strm").toString();
            assertEquals(original, extractor.fetch(original));
        }
        assertEquals("Redirects must not make an extra request", 3, server.getRequestCount());
    }

    @Test
    public void shouldRejectBodiesAtOrAboveTheProbeLimit() throws Exception {
        for (int bytes : new int[]{64 * 1024, 128 * 1024}) {
            server.enqueue(new MockResponse().setBody("https://media.example/movie\n" + "x".repeat(bytes - 28)));
            String original = server.url("/movie.strm").toString();
            assertEquals(original, extractor.fetch(original));
        }
    }

    @Test
    public void shouldPreserveOtherAbsoluteSchemes() {
        String original = "https://source.example/movie.strm";
        assertEquals("rtsp://media.example/movie", Strm.parseTarget("rtsp://media.example/movie", original));
        assertEquals("proxy://source?id=1", Strm.parseTarget("proxy://source?id=1", original));
        assertEquals(original, Strm.parseTarget("../relative/movie.mkv", original));
    }
}
