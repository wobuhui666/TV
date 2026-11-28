package com.fongmi.android.tv.server;

import com.fongmi.android.tv.server.process.Log;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;

import static org.junit.Assert.*;

public class LogSessionTest {
    private final AtomicLong clock = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final List<Thread> readers = new ArrayList<>();
    private final List<FakeProcess> processes = new ArrayList<>();
    private final LogSession logs = new LogSession(clock::get, () -> {
        FakeProcess process = new FakeProcess();
        synchronized (processes) { processes.add(process); }
        return process;
    }, tasks::add, scheduler);

    @After public void cleanup() throws Exception {
        logs.stop();
        scheduler.shutdownNow();
        for (Thread thread : readers) thread.join(3000);
        for (Thread thread : readers) assertFalse("Leaked log reader", thread.isAlive());
    }

    @Test public void shouldReuseActiveSessionAndInvalidateCodeAfterStop() {
        logs.start();
        String code = logs.getCode();
        logs.start();
        assertEquals(code, logs.getCode());
        assertEquals(1, tasks.size());
        assertThrows(SecurityException.class, () -> logs.read("wrong", null));
        assertThrows(SecurityException.class, () -> logs.stop("wrong"));
        logs.stop(code);
        assertThrows(IllegalStateException.class, () -> logs.read(code, null));
        logs.start();
        assertNotEquals(code, logs.getCode());
        assertThrows(SecurityException.class, () -> logs.read(code, null));
    }

    @Test public void shouldExpireEvenWithoutBrowserPollingAndSkipQueuedOldReader() {
        logs.start();
        clock.set(LogSession.DURATION);
        assertFalse(logs.isRunning());
        logs.start();
        String current = logs.getCode();
        tasks.remove().run();
        assertTrue(processes.isEmpty());
        assertEquals(current, logs.getCode());
    }

    @Test public void shouldDestroyProcessAndRejectReadsAfterDeadline() throws Exception {
        FakeProcess process = startReader();
        String code = logs.getCode();
        process.write("ready\n");
        awaitSequence(1);
        clock.set(LogSession.DURATION);
        assertThrows(IllegalStateException.class, () -> logs.read(code, null));
        assertTrue(process.destroyed);
    }

    @Test public void shouldReadIncrementallyAndRejectInvalidCursors() throws Exception {
        FakeProcess process = startReader();
        process.write("first\r\nsecond\n");
        awaitSequence(2);
        String code = logs.getCode();
        LogSession.Snapshot first = logs.read(code, null);
        assertEquals(List.of("first", "second"), first.lines().stream().map(LogSession.Line::text).toList());
        assertEquals(2, first.next());
        assertFalse(first.backlog());
        assertFalse(first.gap());
        assertTrue(logs.read(code, "2").lines().isEmpty());
        for (String cursor : List.of("-1", "3", "abc", "9223372036854775808")) {
            assertThrows(IllegalArgumentException.class, () -> logs.read(code, cursor));
        }
        process.write("third\n");
        awaitSequence(3);
        assertEquals("third", logs.read(code, "2").lines().getFirst().text());
    }

    @Test public void shouldBoundRetentionAndReportOverwrittenLines() throws Exception {
        FakeProcess process = startReader();
        process.write("x\n".repeat(LogSession.MAX_LINES + 5));
        awaitSequence(LogSession.MAX_LINES + 5);
        LogSession.Snapshot snapshot = logs.read(logs.getCode(), "0");
        assertTrue(snapshot.gap());
        assertEquals(6, snapshot.lines().getFirst().seq());
        assertEquals(LogSession.MAX_BATCH_LINES, snapshot.lines().size());
        assertTrue(snapshot.backlog());
    }

    @Test public void shouldBoundUnicodeLinesJsonBatchesAndRetainedBytes() throws Exception {
        FakeProcess process = startReader();
        String line = "😀\"\\".repeat(8000);
        process.write((line + "\n").repeat(250));
        awaitSequence(250);
        LogSession.Snapshot snapshot = logs.read(logs.getCode(), "0");
        assertTrue("Byte retention should evict old entries", snapshot.gap());
        int bytes = 512;
        for (LogSession.Line entry : snapshot.lines()) {
            assertTrue(entry.text().endsWith(" [truncated]"));
            assertTrue(entry.text().getBytes(StandardCharsets.UTF_8).length <= LogSession.MAX_LINE_BYTES);
            String json = new com.google.gson.JsonPrimitive(entry.text()).toString();
            bytes += json.getBytes(StandardCharsets.UTF_8).length + 64;
            assertFalse(entry.text().contains("\uFFFD"));
        }
        assertTrue(bytes <= LogSession.MAX_BATCH_BYTES);
        assertTrue(snapshot.backlog());
    }

    @Test public void shouldCleanUpFailedStartAndAllowRetry() {
        LogSession failing = new LogSession(clock::get, () -> { throw new java.io.IOException("unavailable"); }, Runnable::run, scheduler);
        failing.start();
        assertFalse(failing.isRunning());
        assertEquals("unavailable", failing.getError());
        failing.stop();
        assertEquals("", failing.getError());
    }

    @Test public void shouldServeOnlyValidatedSessionRequestsOverHttp() throws Exception {
        Log handler = new Log(logs);
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                return handler.doResponse(session, session.getUri(), Map.of());
            }
        };
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getListeningPort();
            assertEquals(415, request(base, "POST", Map.of()).status);
            assertEquals(403, request(base, "POST", Map.of("Content-Type", "application/json", "Origin", "https://other.invalid")).status);
            assertFalse(logs.isRunning());
            Reply opened = request(base, "POST", Map.of("Content-Type", "application/json", "Origin", base));
            assertEquals(200, opened.status);
            assertEquals("no-store", opened.cache);
            String code = JsonParser.parseString(opened.body).getAsJsonObject().get("code").getAsString();
            assertEquals(403, request(base, "GET", Map.of()).status);
            assertEquals(403, request(base, "GET", Map.of("X-Log-Code", code, "Origin", "https://other.invalid")).status);
            assertEquals(200, request(base, "GET", Map.of("X-Log-Code", code)).status);
            assertEquals(400, request(base + "?after=-1", "GET", Map.of("X-Log-Code", code)).status);
            assertEquals(405, request(base, "PUT", Map.of()).status);
            assertEquals(403, request(base, "DELETE", Map.of("X-Log-Code", "wrong")).status);
            assertTrue(logs.isRunning());
            assertEquals(200, request(base, "DELETE", Map.of("X-Log-Code", code)).status);
            assertEquals(410, request(base, "GET", Map.of("X-Log-Code", code)).status);
        } finally { server.stop(); }
    }

    private FakeProcess startReader() throws Exception {
        logs.start();
        Thread thread = new Thread(tasks.remove(), "test-log-reader");
        readers.add(thread);
        thread.start();
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (processes) { if (!processes.isEmpty()) return processes.getLast(); }
            Thread.sleep(5);
        }
        throw new AssertionError("Reader did not start");
    }

    private void awaitSequence(long sequence) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            try { logs.read(logs.getCode(), Long.toString(sequence)); return; }
            catch (IllegalArgumentException ignored) { Thread.sleep(5); }
        }
        throw new AssertionError("Reader did not reach " + sequence);
    }

    private static Reply request(String base, String method, Map<String, String> headers) throws Exception {
        // HttpURLConnection silently strips Origin on the host JDK.
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1).build()) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(3))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            headers.forEach(builder::header);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body(), response.headers().firstValue("Cache-Control").orElse(null));
        }
    }

    private record Reply(int status, String body, String cache) {}

    private static final class FakeProcess extends Process {
        private final PipedInputStream input = new PipedInputStream(65536);
        private final PipedOutputStream writer;
        volatile boolean destroyed;
        FakeProcess() throws Exception { writer = new PipedOutputStream(input); }
        void write(String value) throws Exception { writer.write(value.getBytes(StandardCharsets.UTF_8)); writer.flush(); }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() {
            destroyed = true;
            try { writer.close(); } catch (Exception ignored) {}
        }
    }
}
