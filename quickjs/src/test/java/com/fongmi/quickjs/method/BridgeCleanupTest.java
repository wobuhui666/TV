package com.fongmi.quickjs.method;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class BridgeCleanupTest {
    @Test public void closesEveryResourceAndPreservesLaterFailures() {
        BridgeCleanup cleanup = new BridgeCleanup();
        AtomicInteger released = new AtomicInteger();
        RuntimeException first = new IllegalStateException("stream close");
        Error second = new AssertionError("socket close");
        cleanup.run(() -> { released.incrementAndGet(); throw first; });
        cleanup.run(() -> { released.incrementAndGet(); throw second; });
        cleanup.run(released::incrementAndGet);
        assertEquals(3, released.get());
        assertSame(first, assertThrows(RuntimeException.class, cleanup::finish));
        assertArrayEquals(new Throwable[]{second}, first.getSuppressed());
    }

    @Test public void preservesErrorWhenItIsTheFirstFailure() {
        BridgeCleanup cleanup = new BridgeCleanup();
        Error first = new AssertionError("callback release");
        cleanup.run(() -> { throw first; });
        cleanup.run(() -> { throw first; });
        assertSame(first, assertThrows(Error.class, cleanup::finish));
        assertEquals(0, first.getSuppressed().length);
    }
}
