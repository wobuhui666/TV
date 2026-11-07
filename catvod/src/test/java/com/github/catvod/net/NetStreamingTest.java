package com.github.catvod.net;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

public class NetStreamingTest {

    private static Object header(Map<?, ?> headers, String name) {
        return headers.entrySet().stream().filter(entry -> name.equalsIgnoreCase(entry.getKey().toString()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    @Test
    public void returnsHeadersBeforeDelayedBodyAndPreservesRangeAndEof() throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(206).body("abc")
                    .addHeader("Content-Type", "video/mp2t").addHeader("Content-Range", "bytes 3-5/6")
                    .bodyDelay(3, TimeUnit.SECONDS).build());
            CompletableFuture<Net.StreamResult> opening = net.requestStreamAsync(server.url("/live").toString(),
                    NetOptions.from("{\"timeout\":5000,\"callTimeout\":0,\"headers\":{\"Range\":\"bytes=3-5\"}}"));
            try (Net.StreamResult stream = opening.get(2, TimeUnit.SECONDS)) {
                assertEquals(206, stream.code);
                assertEquals("video/mp2t", stream.contentType);
                assertEquals("bytes 3-5/6", header(stream.headers, "Content-Range"));
                assertEquals("bytes=3-5", server.takeRequest().getHeaders().get("Range"));
                ByteBuffer bytes = ByteBuffer.allocate(3);
                assertEquals(3, stream.readAsync(bytes).get(5, TimeUnit.SECONDS).intValue());
                assertArrayEquals(new byte[]{'a', 'b', 'c'}, bytes.array());
                assertEquals(-1, stream.readAsync(ByteBuffer.allocate(1)).get(2, TimeUnit.SECONDS).intValue());
            }
        }
    }

    @Test
    public void closeAndOwnerShutdownInterruptPendingReads() throws Exception {
        for (boolean closeOwner : new boolean[]{false, true}) {
            try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
                server.start();
                server.enqueue(new MockResponse.Builder().body("x").bodyDelay(5, TimeUnit.SECONDS).build());
                Net.StreamResult stream = net.openStream(server.url("/live").toString(), "{\"timeout\":10000,\"callTimeout\":0}");
                CompletableFuture<Integer> reading = stream.readAsync(ByteBuffer.allocate(1));
                assertThrows(TimeoutException.class, () -> reading.get(100, TimeUnit.MILLISECONDS));
                if (closeOwner) net.close(); else stream.close();
                assertThrows(ExecutionException.class, () -> reading.get(2, TimeUnit.SECONDS));
                stream.close();
                assertThrows(java.io.IOException.class, stream::read);
            }
        }
    }

    @Test
    public void proxyStreamCarriesActualInputStreamAndHttpErrorBody() throws Exception {
        try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(403).body("denied").addHeader("Content-Type", "text/plain").build());
            Object[] response = net.stream(server.url("/denied").toString(), "{}");
            assertEquals(403, response[0]);
            assertEquals("text/plain", response[1]);
            assertTrue(response[2] instanceof Net.StreamResult);
            assertEquals("text/plain", header((Map<?, ?>) response[3], "Content-Type"));
            try (Net.StreamResult stream = (Net.StreamResult) response[2]) {
                assertEquals("denied", new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
}
