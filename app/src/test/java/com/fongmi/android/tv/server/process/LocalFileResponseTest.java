package com.fongmi.android.tv.server.process;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LocalFileResponseTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final OkHttpClient client = new OkHttpClient();
    private NanoHTTPD server;

    @Before public void startServer() throws Exception {
        Local local = new Local();
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                return local.doResponse(session, session.getUri(), Map.of());
            }
        };
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
    }

    @After public void stopServer() {
        if (server != null) server.stop();
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();
    }

    @Test public void shouldReturn404UntilTheRequestedFileExists() throws Exception {
        File file = new File(temporary.getRoot(), "source.js");
        try (Response missing = client.newCall(request(file).build()).execute()) {
            assertEquals(404, missing.code());
            assertEquals("", missing.body().string());
        }
        Files.writeString(file.toPath(), "export default {}", StandardCharsets.UTF_8);
        try (Response found = client.newCall(request(file).build()).execute()) {
            assertEquals(200, found.code());
            assertEquals("export default {}", found.body().string());
        }
    }

    @Test public void shouldKeepRangeAndConditionalRequestsForExistingFiles() throws Exception {
        File file = temporary.newFile("video.txt");
        Files.writeString(file.toPath(), "0123456789", StandardCharsets.UTF_8);
        String etag;
        try (Response partial = client.newCall(request(file).header("Range", "bytes=2-4").build()).execute()) {
            assertEquals(206, partial.code());
            assertEquals("bytes 2-4/10", partial.header("Content-Range"));
            assertEquals("234", partial.body().string());
            etag = partial.header("ETag");
            assertNotNull(etag);
        }
        try (Response unchanged = client.newCall(request(file).header("If-None-Match", etag).build()).execute()) {
            assertEquals(304, unchanged.code());
        }
        try (Response invalid = client.newCall(request(file).header("Range", "bytes=20-30").build()).execute()) {
            assertEquals(416, invalid.code());
        }
    }

    private Request.Builder request(File file) {
        return new Request.Builder().url("http://127.0.0.1:" + server.getListeningPort() + "/file" + file.getAbsolutePath());
    }
}
