package com.github.catvod.net;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class NetCacheTest {

    private static CacheOptions options(long ttl, boolean stale, boolean persist) {
        return new CacheOptions(ttl, stale, persist);
    }

    @Test
    public void invalidationDoesNotRemoveNewOrInFlightValues() throws Exception {
        try (NetCache cache = new NetCache()) {
            cache.cached("key", CacheOptions.from("{\"ttl\":1000}"), () -> CompletableFuture.completedFuture("1")).get();
            assertFalse(cache.invalidate("key", "0"));
            assertEquals("1", cache.peek("key"));
            assertTrue(cache.invalidate("key", "1"));
            CompletableFuture<String> loading = new CompletableFuture<>();
            CompletableFuture<String> waiting = cache.cached("key", CacheOptions.from("{\"ttl\":1000}"), () -> loading);
            assertFalse(cache.invalidate("key", null));
            loading.complete("2");
            assertEquals("2", waiting.get());
            assertFalse(cache.invalidate("key", "1"));
            assertEquals("2", cache.peek("key"));
        }
    }

    @Test
    public void combinesConcurrentLoadsAndWaiterCancellationDoesNotCancelOtherWaiters() throws Exception {
        NetCache cache = new NetCache();
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> loading = new CompletableFuture<>();
        CompletableFuture<String> first = cache.cached("key", options(1000, false, false), () -> {
            calls.incrementAndGet();
            return loading;
        });
        CompletableFuture<String> second = cache.cached("key", options(1000, false, false), () -> {
            fail("Duplicate loader");
            return null;
        });
        first.cancel(true);
        loading.complete("{\"value\":1}");
        assertEquals("{\"value\":1}", second.get());
        assertEquals(1, calls.get());
        assertFalse(loading.isCancelled());
        cache.close();
    }

    @Test
    public void expiredStaleValueReturnsWhileOneRefreshRuns() throws Exception {
        AtomicLong clock = new AtomicLong();
        NetCache cache = new NetCache(clock::get);
        cache.cached("key", options(10, false, false), () -> CompletableFuture.completedFuture("1")).get();
        clock.set(TimeUnit.MILLISECONDS.toNanos(10));
        CompletableFuture<String> refresh = new CompletableFuture<>();
        assertEquals("1", cache.cached("key", options(10, true, false), () -> refresh).get());
        CompletableFuture<String> waiting = cache.cached("key", options(10, false, false), () -> {
            fail("Must join active refresh");
            return null;
        });
        assertFalse(waiting.isDone());
        refresh.complete("2");
        assertEquals("2", waiting.get());
        assertEquals("2", cache.peek("key"));
        cache.close();
    }

    @Test
    public void clearCancelsFlightAndLateCompletionCannotRepopulateCache() throws Exception {
        NetCache cache = new NetCache();
        CompletableFuture<String> loading = new CompletableFuture<>();
        CompletableFuture<String> result = cache.cached("key", options(1000, false, false), () -> loading);
        cache.clear("key");
        assertTrue(loading.isCancelled());
        assertThrows(ExecutionException.class, result::get);
        loading.complete("1");
        assertNull(cache.peek("key"));
        assertEquals("2", cache.cached("key", options(1000, false, false), () -> CompletableFuture.completedFuture("2")).get());
        cache.close();
    }

    @Test
    public void zeroTtlDoesNotRetainValueAndInvalidJsonIsRejected() throws Exception {
        NetCache cache = new NetCache();
        assertEquals("false", cache.cached("key", options(0, false, false), () -> CompletableFuture.completedFuture("false")).get());
        assertNull(cache.peek("key"));
        assertThrows(ExecutionException.class, () -> cache.cached("key", options(1000, false, false), () -> CompletableFuture.completedFuture("<html>login</html>")).get());
        assertNull(cache.peek("key"));
        cache.close();
    }

    @Test
    public void persistenceRestoresAgeAndCloseKeepsDiskData() throws Exception {
        Map<String, String> disk = new HashMap<>();
        AtomicLong monotonic = new AtomicLong();
        AtomicLong wall = new AtomicLong(1000);
        NetCache first = persistent(monotonic, wall, disk);
        first.cached("key", options(100, false, true), () -> CompletableFuture.completedFuture("{\"saved\":true}")).get();
        first.close();
        assertTrue(disk.containsKey("key"));
        wall.addAndGet(50);
        NetCache next = persistent(monotonic, wall, disk);
        assertEquals("{\"saved\":true}", next.value("key", options(100, false, true)));
        monotonic.set(TimeUnit.MILLISECONDS.toNanos(50));
        assertNull(next.value("key", options(100, false, true)));
        assertEquals("{\"saved\":true}", next.peek("key"));
        next.clear(null);
        assertTrue(disk.isEmpty());
        next.close();
    }

    @Test
    public void ownerlessCacheRejectsPersistenceAndCloseCancelsAllKeys() {
        NetCache cache = new NetCache();
        assertThrows(ExecutionException.class, () -> cache.cached("key", options(1, false, true), () -> CompletableFuture.completedFuture("1")).get());
        CompletableFuture<String> one = new CompletableFuture<>();
        CompletableFuture<String> two = new CompletableFuture<>();
        cache.cached("one", options(1, false, false), () -> one);
        cache.cached("two", options(1, false, false), () -> two);
        cache.close();
        assertTrue(one.isCancelled());
        assertTrue(two.isCancelled());
        assertThrows(IllegalStateException.class, () -> cache.peek("one"));
    }

    @Test
    public void cacheOptionsRequireExactNonnegativeDuration() {
        assertEquals(0, CacheOptions.from("{\"ttl\":0}").ttl);
        assertTrue(CacheOptions.from("{\"ttl\":1}").stale);
        assertFalse(CacheOptions.from("{\"ttl\":1}").persist);
        for (String json : new String[]{"{}", "{\"ttl\":-1}", "{\"ttl\":1.5}", "{\"ttl\":\"1\"}", "{\"ttl\":9223372036854775808}", "{\"ttl\":1,\"stale\":\"false\"}"}) {
            assertThrows(json, IllegalArgumentException.class, () -> CacheOptions.from(json));
        }
    }

    private NetCache persistent(AtomicLong monotonic, AtomicLong wall, Map<String, String> disk) {
        return new NetCache(monotonic::get, wall::get, disk::get, disk::put,
                key -> { if (key == null) disk.clear(); else disk.remove(key); });
    }
}
