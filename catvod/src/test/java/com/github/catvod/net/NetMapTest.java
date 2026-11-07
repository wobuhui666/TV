package com.github.catvod.net;

import com.github.catvod.utils.CallTimeout;
import org.junit.Test;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

public class NetMapTest {

    @Test
    public void preservesOrderAndCallingScopeWithBoundedParallelism() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        try (Net net = new Net(new OkHttpClient(), null); CallTimeout scope = new CallTimeout(0, false)) {
            Runnable restore = scope.enter();
            try {
                assertEquals(List.of(10, 21, 32, 43), net.map(List.of(1, 2, 3, 4), (item, index) -> {
                    assertSame(scope, CallTimeout.current());
                    assertThrows(IllegalStateException.class, CallTimeout::requireDialogsAllowed);
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try { Thread.sleep((5 - item) * 10L); return item * 10 + index; }
                    finally { active.decrementAndGet(); }
                }, 2));
                assertTrue(maximum.get() <= 2);
                assertEquals(List.of(), net.map(List.of(), (item, index) -> { fail(); return null; }));
            } finally { restore.run(); }
        }
    }

    @Test
    public void mapperFailureInterruptsSiblingWorkersAndStopsQueuedItems() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger queued = new AtomicInteger();
        try (Net net = new Net(new OkHttpClient(), null)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> net.map(List.of(0, 1, 2), (item, index) -> {
                if (item == 0) {
                    assertTrue(sleeping.await(2, TimeUnit.SECONDS));
                    throw new IllegalArgumentException("fixture mapper failed");
                }
                if (item == 1) {
                    sleeping.countDown();
                    try { Thread.sleep(30000); }
                    catch (InterruptedException error) { interrupted.countDown(); throw error; }
                }
                queued.incrementAndGet();
                return item;
            }, 2));
            assertEquals("fixture mapper failed", failure.getMessage());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(0, queued.get());
        }
    }

    @Test
    public void closingCallScopeOrNetworkOwnerCancelsMapWorkers() throws Exception {
        for (boolean closeOwner : new boolean[]{false, true}) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            try (Net net = new Net(new OkHttpClient(), null); CallTimeout scope = new CallTimeout(0)) {
                CompletableFuture<List<Integer>> result = CompletableFuture.supplyAsync(() -> {
                    Runnable restore = scope.enter();
                    try {
                        return net.map(List.of(1), (item, index) -> {
                            entered.countDown();
                            try { Thread.sleep(30000); }
                            catch (InterruptedException error) { interrupted.countDown(); throw error; }
                            return item;
                        });
                    } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                    finally { restore.run(); }
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                if (closeOwner) net.close(); else scope.close();
                assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
                assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            }
        }
    }
}
