package com.fongmi.android.tv.player.exo;

import android.net.TestUri;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.*;

public class CctvH5eDataSourceTest {

    private static final Uri SEGMENT = TestUri.of("https://example.invalid/live/cdrmldcctv1/segment.ts?token=secret");
    private static final byte[] ORDINARY = "ordinary transport data".getBytes(StandardCharsets.UTF_8);

    @Test
    public void shouldMatchOnlyDecodedCctvTransportStreamPaths() {
        assertTrue(CctvH5eDataSource.handles(SEGMENT));
        assertTrue(CctvH5eDataSource.handles(TestUri.of("https://example.invalid/CDRMLDCCTV1/SEGMENT.TS?token=x")));
        assertTrue(CctvH5eDataSource.handles(TestUri.of("https://example.invalid/%63drmldcctv1/segment%2ets")));
        for (String url : new String[]{
                "https://cdrmldcctv.invalid/live/segment.ts",
                "https://example.invalid/live/segment.ts?source=cdrmldcctv",
                "https://example.invalid/cdrmldcctv1/master.m3u8",
                "https://example.invalid/cdrmldcctv1/segment.ts/extra",
                "https://example.invalid/cdrmldcctv1/segment.tsx",
                "https://example.invalid/cdrmldcctv1/key.bin",
                "data:video/mp2t,cdrmldcctv.ts"}) {
            assertFalse(url, CctvH5eDataSource.handles(TestUri.of(url)));
        }
    }

    @Test
    public void shouldDelegateOrdinaryRequestAndStreamingReadsUnchanged() throws Exception {
        RecordingSource upstream = new RecordingSource(ORDINARY);
        CctvH5eDataSource source = new CctvH5eDataSource(upstream);
        DataSpec spec = new DataSpec.Builder().setUri(TestUri.of("https://example.invalid/video.ts"))
                .setPosition(4).setLength(8).setKey("cache-key").setUriPositionOffset(17)
                .setHttpRequestHeaders(Collections.singletonMap("Cookie", "item-cookie"))
                .setFlags(DataSpec.FLAG_ALLOW_GZIP).build();

        assertEquals(8, source.open(spec));
        assertSame(spec, upstream.openedSpec);
        assertEquals(0, upstream.readCalls);
        assertArrayEquals(Arrays.copyOfRange(ORDINARY, 4, 12), readAll(source));
        source.close();
        assertEquals(1, upstream.closeCalls);
    }

    @Test
    public void shouldTransformBeforeExposingBytesAndPreserveResponseMetadata() throws Exception {
        byte[] encrypted = CctvH5eTransformTest.encryptedFixture();
        byte[] plaintext = CctvH5eTransformTest.plaintextFixture();
        RecordingSource upstream = new RecordingSource(encrypted);
        upstream.finalUri = TestUri.of("https://cdn.invalid/cdrmldcctv1/segment.ts");
        CctvH5eDataSource source = new CctvH5eDataSource(upstream);

        assertEquals(plaintext.length, source.open(new DataSpec.Builder().setUri(SEGMENT).build()));
        assertTrue(upstream.readCalls > 0);
        assertSame(upstream.finalUri, source.getUri());
        assertSame(upstream.headers, source.getResponseHeaders());
        assertArrayEquals(plaintext, readAll(source));
        assertEquals(0, source.read(new byte[1], 0, 0));
        source.close();
        assertNull(source.getUri());
        assertTrue(source.getResponseHeaders().isEmpty());
    }

    @Test
    public void shouldApplyRetryRangeToTransformedBytesAfterFullFetch() throws Exception {
        byte[] plaintext = CctvH5eTransformTest.plaintextFixture();
        RecordingSource upstream = new RecordingSource(CctvH5eTransformTest.encryptedFixture());
        CctvH5eDataSource source = new CctvH5eDataSource(upstream);
        DataSpec spec = new DataSpec.Builder().setUri(SEGMENT).setPosition(31).setLength(211)
                .setKey("segment-key").setUriPositionOffset(23).setFlags(DataSpec.FLAG_ALLOW_GZIP)
                .setHttpRequestHeaders(Collections.singletonMap("Authorization", "item-token"))
                .setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(new byte[]{1, 2, 3}).build();

        assertEquals(211, source.open(spec));
        assertEquals(0, upstream.openedSpec.position);
        assertEquals(C.LENGTH_UNSET, upstream.openedSpec.length);
        assertSame(spec.uri, upstream.openedSpec.uri);
        assertEquals(spec.httpRequestHeaders, upstream.openedSpec.httpRequestHeaders);
        assertEquals(spec.httpMethod, upstream.openedSpec.httpMethod);
        assertArrayEquals(spec.httpBody, upstream.openedSpec.httpBody);
        assertEquals(spec.key, upstream.openedSpec.key);
        assertEquals(spec.flags, upstream.openedSpec.flags);
        assertEquals(spec.uriPositionOffset, upstream.openedSpec.uriPositionOffset);
        assertArrayEquals(Arrays.copyOfRange(plaintext, 31, 242), readAll(source));
        source.close();
    }

    @Test
    public void shouldResolveUnknownLengthAndPassThroughOrdinarySegmentBytes() throws Exception {
        RecordingSource upstream = new RecordingSource(ORDINARY);
        upstream.announcedLength = C.LENGTH_UNSET;
        CctvH5eDataSource source = new CctvH5eDataSource(upstream);

        assertEquals(ORDINARY.length, source.open(new DataSpec.Builder().setUri(SEGMENT).build()));
        assertArrayEquals(ORDINARY, readAll(source));
        source.close();
    }

    @Test
    public void shouldUseActualBytesWhenResourceEndsBeforeItsAnnouncedLength() throws Exception {
        RecordingSource upstream = new RecordingSource(ORDINARY);
        upstream.announcedLength = ORDINARY.length + 100;
        CctvH5eDataSource source = new CctvH5eDataSource(upstream);

        assertEquals(ORDINARY.length, source.open(new DataSpec.Builder().setUri(SEGMENT).build()));
        assertArrayEquals(ORDINARY, readAll(source));
        source.close();
    }

    @Test
    public void shouldAllowPositionAtEndAndRequestsExtendingPastEnd() throws Exception {
        CctvH5eDataSource source = new CctvH5eDataSource(new RecordingSource(ORDINARY));
        assertEquals(0, source.open(new DataSpec.Builder().setUri(SEGMENT).setPosition(ORDINARY.length).build()));
        assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1));
        source.close();

        assertEquals(100, source.open(new DataSpec.Builder().setUri(SEGMENT)
                .setPosition(ORDINARY.length - 3).setLength(100).build()));
        assertArrayEquals(Arrays.copyOfRange(ORDINARY, ORDINARY.length - 3, ORDINARY.length), readAll(source));
        source.close();
    }

    @Test
    public void shouldRejectPositionsPastEndWithoutTruncatingLongOffsets() throws Exception {
        for (long position : new long[]{ORDINARY.length + 1, (long) Integer.MAX_VALUE + 1}) {
            RecordingSource upstream = new RecordingSource(ORDINARY);
            CctvH5eDataSource source = new CctvH5eDataSource(upstream);
            IOException failure = assertThrows(IOException.class,
                    () -> source.open(new DataSpec.Builder().setUri(SEGMENT).setPosition(position).build()));
            assertTrue(DataSourceException.isCausedByPositionOutOfRange(failure));
            assertEquals(1, upstream.closeCalls);
            source.close();
            assertEquals(1, upstream.closeCalls);
        }
    }

    @Test
    public void shouldRejectAnnouncedOversizeBeforeReadingAndReleaseUpstream() throws Exception {
        RecordingSource upstream = new RecordingSource(ORDINARY);
        upstream.announcedLength = CctvH5eDataSource.MAX_SEGMENT_BYTES + 1L;
        CctvH5eDataSource source = new CctvH5eDataSource(upstream);

        assertThrows(IOException.class, () -> source.open(new DataSpec.Builder().setUri(SEGMENT).build()));
        assertEquals(0, upstream.readCalls);
        assertEquals(1, upstream.closeCalls);
    }

    @Test
    public void shouldEnforceSizeLimitForUnknownLengthResources() throws Exception {
        RepeatingSource upstream = new RepeatingSource(CctvH5eDataSource.MAX_SEGMENT_BYTES + 1);
        CctvH5eDataSource source = new CctvH5eDataSource(upstream);

        assertThrows(IOException.class, () -> source.open(new DataSpec.Builder().setUri(SEGMENT).build()));
        assertEquals(1, upstream.closeCalls);
    }

    @Test
    public void shouldAcceptSegmentExactlyAtSizeLimit() throws Exception {
        RepeatingSource upstream = new RepeatingSource(CctvH5eDataSource.MAX_SEGMENT_BYTES);
        CctvH5eDataSource source = new CctvH5eDataSource(upstream);

        assertEquals(CctvH5eDataSource.MAX_SEGMENT_BYTES,
                source.open(new DataSpec.Builder().setUri(SEGMENT).build()));
        byte[] buffer = new byte[32 * 1024];
        int total = 0;
        int count;
        while ((count = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) total += count;
        assertEquals(CctvH5eDataSource.MAX_SEGMENT_BYTES, total);
        source.close();
    }

    @Test
    public void shouldPreserveOpenAndReadErrorsWhileClosingFailedSegmentLoads() throws Exception {
        IOException failure = new IOException("upstream failure");
        for (boolean failOpen : new boolean[]{true, false}) {
            RecordingSource upstream = new RecordingSource(ORDINARY) {
                @Override public long open(DataSpec spec) throws IOException {
                    if (failOpen) throw failure;
                    return super.open(spec);
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    throw failure;
                }
            };
            CctvH5eDataSource source = new CctvH5eDataSource(upstream);
            assertSame(failure, assertThrows(IOException.class,
                    () -> source.open(new DataSpec.Builder().setUri(SEGMENT).build())));
            assertEquals(1, upstream.closeCalls);
            source.close();
        }
    }

    @Test
    public void shouldKeepPrimaryReadErrorWhenCleanupAlsoFails() throws Exception {
        IOException failure = new IOException("read failed");
        IOException closeFailure = new IOException("close failed");
        RecordingSource upstream = new RecordingSource(ORDINARY) {
            @Override public int read(byte[] buffer, int offset, int length) throws IOException { throw failure; }
            @Override public void close() throws IOException { super.close(); throw closeFailure; }
        };
        CctvH5eDataSource source = new CctvH5eDataSource(upstream);

        assertSame(failure, assertThrows(IOException.class,
                () -> source.open(new DataSpec.Builder().setUri(SEGMENT).build())));
        assertArrayEquals(new Throwable[]{closeFailure}, failure.getSuppressed());
    }

    @Test
    public void shouldCancelBlockedOpenAndReadWithoutWaitingForStateLock() throws Exception {
        for (boolean blockOpen : new boolean[]{true, false}) {
            BlockingSource upstream = new BlockingSource(blockOpen);
            CctvH5eDataSource source = new CctvH5eDataSource(upstream);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<IOException> load = executor.submit(() -> {
                    try {
                        source.open(new DataSpec.Builder().setUri(SEGMENT).build());
                        throw new AssertionError("Canceled open succeeded");
                    } catch (IOException error) {
                        return error;
                    }
                });
                assertTrue(upstream.entered.await(2, TimeUnit.SECONDS));
                source.close();
                assertNotNull(load.get(2, TimeUnit.SECONDS));
                assertTrue(upstream.closed.await(2, TimeUnit.SECONDS));
                assertNull(source.getUri());
            } finally {
                source.close();
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void shouldCancelRealHttpOpenBeforeResponseHeadersArrive() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
        CctvH5eDataSource source = new CctvH5eDataSource(new OkHttpDataSource.Factory(client).createDataSource());
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            Uri uri = TestUri.of(server.url("/cdrmldcctv1/segment.ts").toString());
            Future<Boolean> load = executor.submit(() -> {
                try {
                    source.open(new DataSpec.Builder().setUri(uri).build());
                    return false;
                } catch (IOException canceled) {
                    return true;
                }
            });
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            source.close();
            assertTrue("Close must cancel the HTTP call without waiting for its timeout", load.get(2, TimeUnit.SECONDS));
        } finally {
            source.close();
            executor.shutdownNow();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
    }

    private static byte[] readAll(DataSource source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[103];
        int count;
        while ((count = source.read(buffer, 3, 97)) != C.RESULT_END_OF_INPUT) output.write(buffer, 3, count);
        return output.toByteArray();
    }

    private static class RecordingSource implements DataSource {

        private final ByteArrayDataSource delegate;
        final Map<String, List<String>> headers = Collections.singletonMap("X-Test", Collections.singletonList("response"));
        DataSpec openedSpec;
        Uri finalUri;
        long announcedLength = Long.MIN_VALUE;
        int readCalls;
        int closeCalls;

        RecordingSource(byte[] data) {
            delegate = new ByteArrayDataSource(data);
        }

        @Override public void addTransferListener(TransferListener listener) { delegate.addTransferListener(listener); }
        @Override public long open(DataSpec spec) throws IOException {
            openedSpec = spec;
            long length = delegate.open(spec);
            return announcedLength == Long.MIN_VALUE ? length : announcedLength;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            readCalls++;
            return delegate.read(buffer, offset, Math.min(length, 113));
        }
        @Override public Uri getUri() { return finalUri == null ? delegate.getUri() : finalUri; }
        @Override public Map<String, List<String>> getResponseHeaders() { return headers; }
        @Override public void close() throws IOException { closeCalls++; delegate.close(); }
    }

    private static final class RepeatingSource extends RecordingSource {

        private final int size;
        private int position;

        RepeatingSource(int size) {
            super(ORDINARY);
            this.size = size;
        }

        @Override public long open(DataSpec spec) { return C.LENGTH_UNSET; }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (position == size) return C.RESULT_END_OF_INPUT;
            int count = Math.min(length, size - position);
            Arrays.fill(buffer, offset, offset + count, (byte) 0);
            position += count;
            return count;
        }
    }

    private static final class BlockingSource extends RecordingSource {

        private final boolean blockOpen;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        BlockingSource(boolean blockOpen) {
            super(ORDINARY);
            this.blockOpen = blockOpen;
        }

        @Override public long open(DataSpec spec) throws IOException {
            if (blockOpen) block();
            return super.open(spec);
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            block();
            return C.RESULT_END_OF_INPUT;
        }
        @Override public void close() throws IOException { super.close(); closed.countDown(); }

        private void block() throws InterruptedIOException {
            entered.countDown();
            boolean interrupted = false;
            try {
                if (blockOpen) {
                    // Pending HTTP opens need interruption: closing a response does nothing
                    // before any response exists.
                    new CountDownLatch(1).await();
                } else {
                    // A read may require closing its upstream stream even after interruption.
                    while (closed.getCount() != 0) {
                        try {
                            closed.await();
                        } catch (InterruptedException error) {
                            interrupted = true;
                        }
                    }
                }
            } catch (InterruptedException error) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
            throw new InterruptedIOException("canceled");
        }
    }
}
