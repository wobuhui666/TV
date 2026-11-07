package com.github.catvod.net;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import okio.Buffer;

import static org.junit.Assert.*;

public class NetDownloadTest {

    @Test
    public void enforcesMaxAndExpectedSizeBeforeReplacingExistingFile() throws Exception {
        File directory = Files.createTempDirectory("net-download").toFile();
        File target = new File(directory, "result.bin");
        Files.writeString(target.toPath(), "previous");
        try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
            server.start();
            for (String options : new String[]{"{\"maxBytes\":3}", "{\"size\":10}"}) {
                server.enqueue(new MockResponse.Builder().body("payload").build());
                assertThrows(java.io.IOException.class, () -> net.download(server.url("/file").toString(), options, target));
                assertEquals("previous", Files.readString(target.toPath()));
            }
            server.enqueue(new MockResponse.Builder().body("payload").build());
            net.download(server.url("/file").toString(), "{\"maxBytes\":7,\"size\":7,\"threads\":1}", target);
            assertEquals("payload", Files.readString(target.toPath()));
        }
    }

    @Test
    public void malformedGeneratedRangeCannotReplaceExistingTarget() throws Exception {
        File target = new File(Files.createTempDirectory("net-invalid-range").toFile(), "result.bin");
        Files.writeString(target.toPath(), "previous");
        try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(206).body("partial").build());
            assertThrows(java.io.IOException.class, () -> net.download(server.url("/file").toString(), "{\"threads\":2}", target));
            assertEquals("previous", Files.readString(target.toPath()));
        }
    }

    @Test
    public void startsBoundedRangeTransfersWhileInitialBodyIsStillPending() throws Exception {
        byte[] payload = new byte[4 * 1024 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        CountDownLatch secondRange = new CountDownLatch(1);
        File target = new File(Files.createTempDirectory("net-range-download").toFile(), "result.bin");
        try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
            server.start();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    String range = request.getHeaders().get("Range");
                    assertNotNull(range);
                    String[] boundaries = range.substring(6).split("-");
                    int start = Integer.parseInt(boundaries[0]);
                    int end = Integer.parseInt(boundaries[1]);
                    if (start > 0) { assertEquals("\"fixture-v1\"", request.getHeaders().get("If-Range")); secondRange.countDown(); }
                    return new MockResponse.Builder().code(206)
                            .body(new Buffer().write(Arrays.copyOfRange(payload, start, end + 1)))
                            .addHeader("Content-Range", "bytes " + start + "-" + end + "/" + payload.length)
                            .addHeader("ETag", "\"fixture-v1\"").addHeader("Accept-Ranges", "bytes")
                            .bodyDelay(2, TimeUnit.SECONDS).build();
                }
            });
            CompletableFuture<String> transfer = net.downloadAsync(server.url("/file").toString(),
                    "{\"size\":4194304,\"threads\":2,\"timeout\":10000}", target);
            assertTrue(secondRange.await(1, TimeUnit.SECONDS));
            assertEquals(target.getAbsolutePath(), transfer.get(5, TimeUnit.SECONDS));
            assertArrayEquals(payload, Files.readAllBytes(target.toPath()));
            assertEquals(2, server.getRequestCount());
        }
    }
}
