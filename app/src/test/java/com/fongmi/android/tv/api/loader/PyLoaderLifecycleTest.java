package com.fongmi.android.tv.api.loader;

import static org.junit.Assert.*;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.CallTimeout;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutionException;

/** Real host ownership/cache behavior with failing source objects; no Python VM is started. */
public class PyLoaderLifecycleTest {

    @After
    public void restoreThreadInterrupt() {
        Thread.interrupted();
    }

    @Test
    public void shouldReleasePartiallyInitializedSpiderAndKeepTheOriginalFailure() throws Exception {
        FakeSpider source = new FakeSpider();
        IllegalStateException initialization = new IllegalStateException("init fixture");
        IllegalArgumentException cleanup = new IllegalArgumentException("destroy fixture");
        source.initAction = () -> { throw initialization; };
        source.destroyFailure = cleanup;
        FakeLoader loader = new FakeLoader(source);

        Spider result = loader.getSpider("source", "fixture.py", "");

        assertTrue(result instanceof SpiderNull);
        assertEquals(1, source.destroyCalls);
        assertArrayEquals(new Throwable[]{cleanup}, initialization.getSuppressed());
        assertDialogClosed(source);
        assertSame("Ordinary failed source caching keeps its existing behavior", result, loader.getSpider("source", "fixture.py", ""));
        assertEquals(1, loader.loads);
    }

    @Test
    public void shouldDestroyEverySourceWhenOneDestroyFails() throws Exception {
        FakeSpider first = new FakeSpider();
        first.destroyFailure = new LinkageError("destroy fixture");
        FakeSpider second = new FakeSpider();
        FakeLoader loader = new FakeLoader(first, second);
        assertSame(first, loader.getSpider("first", "first.py", ""));
        assertSame(second, loader.getSpider("second", "second.py", ""));
        loader.setRecent("first");

        loader.clear();

        assertEquals(1, first.destroyCalls);
        assertEquals(1, second.destroyCalls);
        assertDialogClosed(first);
        assertDialogClosed(second);
        assertNull(loader.proxy(Map.of()));
        loader.clear();
        assertEquals("Cleared sources must not be destroyed twice", 1, first.destroyCalls);
        assertEquals(1, second.destroyCalls);
    }

    @Test
    public void shouldCleanInterruptedInitializationWithoutCachingItsFailure() throws Exception {
        FakeSpider interrupted = new FakeSpider();
        interrupted.initAction = () -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cancel fixture");
        };
        FakeSpider retry = new FakeSpider();
        FakeLoader loader = new FakeLoader(interrupted, retry);

        assertTrue(loader.getSpider("source", "fixture.py", "") instanceof SpiderNull);

        assertTrue("Cleanup must preserve the caller cancellation", Thread.interrupted());
        assertEquals(1, interrupted.destroyCalls);
        assertDialogClosed(interrupted);
        assertSame(retry, loader.getSpider("source", "fixture.py", ""));
        assertEquals(2, loader.loads);
        loader.clear();
    }

    @Test
    public void shouldRetryWhenInitializationRequiresAForbiddenDialog() throws Exception {
        FakeSpider denied = new FakeSpider();
        denied.initAction = CallTimeout::requireDialogsAllowed;
        FakeSpider retry = new FakeSpider();
        FakeLoader loader = new FakeLoader(denied, retry);
        try (CallTimeout budget = new CallTimeout(1000, false)) {
            Runnable restore = budget.enter();
            try {
                assertTrue(loader.getSpider("source", "fixture.py", "") instanceof SpiderNull);
                assertTrue(CallTimeout.currentDeniedDialog());
            } finally {
                restore.run();
            }
        }

        assertEquals(1, denied.destroyCalls);
        assertDialogClosed(denied);
        assertSame(retry, loader.getSpider("source", "fixture.py", ""));
        assertEquals(2, loader.loads);
        loader.clear();
    }

    private static void assertDialogClosed(Spider source) throws Exception {
        try {
            source.dialog.open("{}", null).get();
            fail("Host resources should close even if plugin destroy throws");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
            assertEquals("Dialog owner is closed", expected.getCause().getMessage());
        }
    }

    private static final class FakeLoader extends PyLoader {
        private final Queue<Spider> sources;
        private int loads;

        FakeLoader(Spider... sources) {
            this.sources = new ArrayDeque<>(Arrays.asList(sources));
        }

        @Override
        Spider loadSpider(String api) {
            loads++;
            return sources.remove();
        }
    }

    private static final class FakeSpider extends Spider {
        private Runnable initAction = () -> {};
        private Throwable destroyFailure;
        private int destroyCalls;

        @Override
        public void init(Context context, String extend) {
            initAction.run();
        }

        @Override
        public void destroy() {
            destroyCalls++;
            if (destroyFailure instanceof RuntimeException error) throw error;
            if (destroyFailure instanceof Error error) throw error;
        }
    }
}
