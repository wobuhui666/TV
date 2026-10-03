package com.fongmi.android.tv.api.loader;

import com.github.catvod.spider.FailingNativePlugin;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SourcePluginRuntimeTest {

    @Test(timeout = 3000)
    public void childFailureAfterInitializationDoesNotReachHostHandler() throws Exception {
        AtomicReference<Throwable> delegated = new AtomicReference<>();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        SourcePluginRuntime runtime = runtime(delegated, reported);
        Thread child = runtime.call(() -> {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                FailingNativePlugin.load();
            });
            thread.start();
            return thread;
        });
        start.countDown();
        child.join();
        assertTrue(reported.get() instanceof UnsatisfiedLinkError);
        assertNull(delegated.get());
        assertEquals("still usable", runtime.call(() -> "still usable"));
    }

    @Test(timeout = 3000)
    public void hostLinkageErrorAndOtherWorkerErrorsKeepOriginalHandler() throws Exception {
        AtomicReference<Throwable> delegated = new AtomicReference<>();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        SourcePluginRuntime runtime = runtime(delegated, reported);
        for (Error error : new Error[]{new UnsatisfiedLinkError("host library"), new OutOfMemoryError("host")}) {
            Thread child = runtime.call(() -> {
                Thread thread = new Thread(() -> { throw error; });
                thread.start();
                return thread;
            });
            child.join();
            assertSame(error, delegated.get());
            assertNull(reported.get());
        }
        RuntimeException failure = new RuntimeException("plugin bug");
        Thread child = runtime.call(() -> {
            Thread thread = new Thread(() -> { throw failure; });
            thread.start();
            return thread;
        });
        child.join();
        assertSame(failure, delegated.get());
    }

    @Test(timeout = 3000)
    public void initializationIsSynchronousAndCanReenterWhileHoldingJarLock() {
        SourcePluginRuntime runtime = new SourcePluginRuntime(error -> fail());
        Object lock = new Object();
        assertEquals("initialized", runtime.call(() -> {
            synchronized (lock) {
                return runtime.call(() -> {
                    synchronized (lock) {
                        return "initialized";
                    }
                });
            }
        }));
    }

    @Test(timeout = 3000)
    public void synchronousPluginErrorIsReturnedToExistingCallerProtection() {
        SourcePluginRuntime runtime = new SourcePluginRuntime(error -> fail());
        try {
            runtime.call(() -> { FailingNativePlugin.load(); return null; });
            fail();
        } catch (UnsatisfiedLinkError expected) {
            assertEquals("bad ELF magic: 3c3f786d", expected.getMessage());
        }
    }

    @Test(timeout = 3000)
    public void interruptedCallerCancelsWorkerAndKeepsInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        SourcePluginRuntime runtime = new SourcePluginRuntime(error -> fail());
        Thread caller = new Thread(() -> {
            try {
                runtime.call(() -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException expected) {
                        canceled.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (IllegalStateException expected) {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join();
        assertEquals(Boolean.TRUE, interrupted.get());
        assertTrue(canceled.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void pluginCallerDoesNotHideHostOriginAndSystemLoadFramesAreRecognized() {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError();
        error.setStackTrace(new StackTraceElement[]{
                frame("com.fongmi.android.tv.Host"), frame("com.github.catvod.spider.GoProxy")});
        assertFalse(SourcePluginRuntime.isPluginLinkageError(error));
        error.setStackTrace(new StackTraceElement[]{
                frame("java.lang.Runtime"), frame("java.lang.System"), frame("com.github.catvod.spider.GoProxy")});
        assertTrue(SourcePluginRuntime.isPluginLinkageError(error));
        error.setStackTrace(new StackTraceElement[0]);
        assertFalse(SourcePluginRuntime.isPluginLinkageError(error));
    }

    @Test(timeout = 3000)
    public void cancellationSurvivesPluginClearingItsInterrupt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Boolean> canceled = new AtomicReference<>();
        SourcePluginRuntime runtime = new SourcePluginRuntime(error -> fail());
        Thread caller = new Thread(() -> {
            try {
                runtime.call(() -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException ignored) {
                        // Third-party init does not have to preserve the interrupt flag.
                    }
                    canceled.set(runtime.isCanceled());
                    finished.countDown();
                    return null;
                });
            } catch (IllegalStateException expected) {
                // The caller has stopped waiting; the plugin may still be unwinding.
            }
        });
        caller.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join();
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, canceled.get());
    }

    private static StackTraceElement frame(String name) {
        return new StackTraceElement(name, "load", "SourceFile", 2);
    }

    private static SourcePluginRuntime runtime(AtomicReference<Throwable> delegated, AtomicReference<Throwable> reported) {
        ThreadGroup parent = new ThreadGroup("host-test") {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                delegated.set(error);
            }
        };
        return new SourcePluginRuntime(parent, reported::set);
    }
}
