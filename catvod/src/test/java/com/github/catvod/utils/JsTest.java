package com.github.catvod.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class JsTest {

    @Test
    public void returnsJsonAndUnwrapsEvaluatorErrors() throws Exception {
        Js js = new Js((source, options) -> CompletableFuture.completedFuture("{\"value\":3}"));
        assertEquals("{\"value\":3}", js.eval("fixture"));
        Exception failure = new Exception("signature failed");
        Js failed = new Js((source, options) -> CompletableFuture.failedFuture(failure));
        assertSame(failure, assertThrows(Exception.class, () -> failed.eval("fixture")));
    }

    @Test
    public void closeCancelsOwnedEvaluationsAndRejectsNewWork() {
        List<CompletableFuture<String>> values = new ArrayList<>();
        Js js = new Js((source, options) -> {
            CompletableFuture<String> result = new CompletableFuture<>();
            values.add(result);
            return result;
        });
        js.evalAsync("first");
        js.evalAsync("second");
        values.get(0).complete("null");
        js.close();
        js.close();
        assertFalse(values.get(0).isCancelled());
        assertTrue(values.get(1).isCancelled());
        assertThrows(IllegalStateException.class, () -> js.evalAsync("late"));
    }

    @Test
    public void cancellingRequestDeadlineCancelsEvaluation() {
        CompletableFuture<String> result = new CompletableFuture<>();
        try (CallTimeout scope = new CallTimeout(0)) {
            Runnable restore = scope.enter();
            try { new Js((source, options) -> result).evalAsync("fixture"); }
            finally { restore.run(); }
        }
        assertTrue(result.isCancelled());
    }

    @Test(timeout = 5000)
    public void interruptedSyncCallerCancelsWorkAndKeepsInterruptFlag() throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Js js = new Js((source, options) -> { started.countDown(); return result; });
        Thread caller = new Thread(() -> {
            try { js.eval("fixture"); }
            catch (Throwable error) { failure.set(error); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            caller.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertTrue(failure.get() instanceof InterruptedException);
            assertTrue(interrupted.get());
            assertTrue(result.isCancelled());
        } finally {
            js.close();
            caller.interrupt();
            caller.join(2000);
        }
    }
}
