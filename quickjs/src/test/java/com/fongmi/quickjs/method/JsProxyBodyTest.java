package com.fongmi.quickjs.method;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class JsProxyBodyTest {
    @Test public void pullsOnlyRequestedBytesAndClosesOnceAtEof() throws Exception {
        AtomicInteger pulls = new AtomicInteger(), closes = new AtomicInteger();
        JsProxyBody body = new JsProxyBody(new JsProxyBody.Source() {
            public CompletableFuture<byte[]> pull(int size) {
                assertEquals(2, size);
                int count = pulls.incrementAndGet();
                return CompletableFuture.completedFuture(count == 1 ? new byte[]{0, (byte)255} : null);
            }
            public void close() { closes.incrementAndGet(); }
        });
        assertEquals(0, pulls.get());
        byte[] bytes = new byte[2];
        assertEquals(2, body.read(bytes));
        assertArrayEquals(new byte[]{0, (byte)255}, bytes);
        assertEquals(1, pulls.get());
        assertEquals(-1, body.read(bytes));
        body.close();
        assertEquals(1, closes.get());
    }

    @Test public void closeCancelsBlockedPullWithoutTakingReadLock() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1), ended = new CountDownLatch(1);
        CompletableFuture<byte[]> pending = new CompletableFuture<>() {
            @Override public byte[] get() throws InterruptedException, ExecutionException {
                // JsProxyBody publishes its pending future before calling get().
                waiting.countDown();
                return super.get();
            }
        };
        JsProxyBody body = new JsProxyBody(new JsProxyBody.Source() {
            public CompletableFuture<byte[]> pull(int size) { return pending; }
            public void close() {}
        });
        Thread reader = new Thread(() -> {
            try { body.read(); fail("Expected cancellation"); }
            catch (IOException expected) {}
            finally { ended.countDown(); }
        });
        reader.start();
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        body.close();
        assertTrue(pending.isCancelled());
        assertTrue(ended.await(2, TimeUnit.SECONDS));
    }

    @Test public void rejectsOversizedChunkAndClosesFailedGenerator() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        JsProxyBody body = new JsProxyBody(new JsProxyBody.Source() {
            public CompletableFuture<byte[]> pull(int size) { return CompletableFuture.completedFuture(new byte[size + 1]); }
            public void close() { closes.incrementAndGet(); }
        });
        assertThrows(IOException.class, body::read);
        assertEquals(1, closes.get());
    }
}
