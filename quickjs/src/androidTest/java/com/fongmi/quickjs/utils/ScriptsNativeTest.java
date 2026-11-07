package com.fongmi.quickjs.utils;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.github.catvod.utils.Js;
import com.whl.quickjs.android.QuickJSLoader;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScriptsNativeTest {

    @BeforeClass
    public static void loadNative() {
        QuickJSLoader.init();
        assertEquals(0, NativeBudget.activeCount());
    }

    @Test
    public void serializesSynchronousAndPromiseResults() throws Exception {
        try (Js js = new Js()) {
            assertEquals("{\"value\":3}", js.eval("({value: 3})"));
            assertEquals("\"plain text\"", js.eval("'plain text'"));
            assertEquals("null", js.eval("null"));
            assertEquals("[1,true]", js.eval("Promise.resolve([1,true])"));
            assertEquals("7", js.eval("new Promise(resolve => setTimeout(() => resolve(7), 10))"));
            assertEquals("1", js.eval("globalThis.format = null; 1"));
            assertThrows(Exception.class, () -> js.eval("undefined"));
            assertThrows(Exception.class, () -> js.eval("Promise.reject(new Error('failed'))"));
            assertThrows(Exception.class, () -> js.eval("({nested: Promise.resolve(1)})"));
        }
        assertEquals(0, NativeBudget.activeCount());
    }

    @Test
    public void injectsGlobalsForDouyuMd5SignatureAndIsolatesCalls() throws Exception {
        try (Js js = new Js()) {
            String source = "var CryptoJS = {MD5: md5X}; CryptoJS.MD5(rid + did + tt);";
            assertEquals("\"e80b5017098950fc58aad83c8c14978e\"", js.eval(source, "{\"globals\":{\"rid\":\"ab\",\"did\":\"cd\",\"tt\":\"ef\"}}"));
            assertEquals("\"undefined\"", js.eval("typeof rid"));
            assertEquals("18", js.eval("JSON.bad + eval", "{\"globals\":{\"JSON\":{\"bad\":1},\"eval\":17}}"));
            assertThrows(Exception.class, () -> js.eval("md5X(123)"));
        }
        assertEquals(0, NativeBudget.activeCount());
    }

    @Test(timeout = 10000)
    public void nativeExecutionBudgetInterruptsAnInfiniteLoopAndReleasesTheContext() throws Exception {
        try (Js js = new Js()) {
            long started = System.nanoTime();
            assertThrows(TimeoutException.class, () -> js.eval("for (;;) {}", "{\"timeout\":100}"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000);
            assertEquals(0, NativeBudget.activeCount());
            assertEquals("2", js.eval("1 + 1"));
        }
    }

    @Test(timeout = 10000)
    public void cancellationAndOwnerCloseReleaseNativeBudgetsAndPendingTimers() throws Exception {
        Js js = new Js();
        try {
            CompletableFuture<String> loop = js.evalAsync("for (;;) {}", "{\"timeout\":5000}");
            await(() -> NativeBudget.activeCount() == 1);
            loop.cancel(true);
            await(() -> NativeBudget.activeCount() == 0);
            CompletableFuture<String> timer = js.evalAsync("new Promise(resolve => setTimeout(() => resolve(1), 4000))");
            await(() -> NativeBudget.activeCount() == 1);
            js.close();
            assertTrue(timer.isCancelled());
            await(() -> NativeBudget.activeCount() == 0);
            await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && thread.getName().equals("Spider script evaluation")));
        } finally {
            js.close();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("Script resources were not released", condition.getAsBoolean());
    }
}
