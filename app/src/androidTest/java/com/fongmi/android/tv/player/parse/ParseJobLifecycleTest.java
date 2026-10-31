package com.fongmi.android.tv.player.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.fongmi.android.tv.ui.custom.CustomWebView;
import com.fongmi.android.tv.utils.WebViewUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import fi.iki.elonen.NanoHTTPD;

/** Exercises real WebViews and loopback requests while source switching drains the UI queue. */
@RunWith(AndroidJUnit4.class)
public final class ParseJobLifecycleTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger callbacks = new AtomicInteger();
    private final ParseCallback callback = new ParseCallback() {
        @Override public void onParseSuccess(Map<String, String> headers, String url, String from) { callbacks.incrementAndGet(); }
        @Override public void onParseError() { callbacks.incrementAndGet(); }
    };
    private Fixture fixture;
    private CorePlaybackActivity activity;
    private ParseJob job;
    private CustomWebView parent;

    @Before public void setup() throws Exception {
        String packageName = instrumentation.getTargetContext().getPackageName();
        assertTrue("Run only in a disposable validation application", packageName.equals("com.fongmi.android.tv.preview")
                || packageName.equals("com.fongmi.android.tv.sourceprobe"));
        fixture = new Fixture();
        fixture.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            assertTrue("Regression requires a working Android WebView", WebViewUtil.support());
            // Warm Chromium before timing checks; every tested request is confined to loopback.
            parent = CustomWebView.create(activity).start("", "", Map.of(), "about:blank", "", callback, true);
            activity.setContentView(parent);
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> assertTrue("Nested callbacks use the real attached View queue", parent.isAttachedToWindow()));
    }

    @After public void cleanup() {
        instrumentation.runOnMainSync(() -> {
            if (job != null) job.stop();
            if (parent != null) {
                if (activity != null) activity.setContentView(activity.view);
                parent.stop(false);
                parent.destroy();
            }
            if (activity != null) activity.finish();
        });
        if (fixture != null) fixture.stop();
    }

    @Test public void shouldDiscardQueuedWebCreationWhenParseStops() throws Exception {
        instrumentation.runOnMainSync(() -> {
            job = ParseJob.create(callback);
            invoke(job, "startWeb", new Class<?>[]{String.class, String.class, Map.class, String.class, String.class},
                    "", "", Map.of(), fixture.url("/nested.html"), "");
            job.stop();
        });
        instrumentation.waitForIdleSync();
        assertFalse("A stopped parse must not load its queued page", fixture.loaded.await(1500, TimeUnit.MILLISECONDS));
        instrumentation.runOnMainSync(() -> assertTrue("No WebView may survive source switching", views(job).isEmpty()));
        assertEquals(0, callbacks.get());
    }

    @Test public void shouldDiscardQueuedChildWhenParentStops() throws Exception {
        instrumentation.runOnMainSync(() -> {
            addNestedPage();
            parent.stop(false);
        });
        instrumentation.waitForIdleSync();
        assertFalse("A stopped sniffer must not start a queued nested page", fixture.loaded.await(1500, TimeUnit.MILLISECONDS));
        assertEquals(0, callbacks.get());
    }

    @Test public void shouldStopRunningNestedPageWithItsParent() throws Exception {
        instrumentation.runOnMainSync(this::addNestedPage);
        assertTrue("The nested page must really load before testing its lifetime", fixture.loaded.await(8, TimeUnit.SECONDS));
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (fixture.pings.get() < 2 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40);
        assertTrue("Nested page JavaScript must issue real requests", fixture.pings.get() >= 2);
        instrumentation.runOnMainSync(() -> parent.stop(false));
        // Allow a request already intercepted by Chromium to finish before checking quiescence.
        SystemClock.sleep(250);
        int stoppedCount = fixture.pings.get();
        SystemClock.sleep(600);
        assertEquals("Stopping the parent must stop nested JavaScript and network work", stoppedCount, fixture.pings.get());
        assertEquals(0, callbacks.get());
    }

    @Test public void shouldNotReopenQueuedChallengeDialogAfterStop() {
        instrumentation.runOnMainSync(() -> {
            assertTrue("Queue through the real attached View handler", parent.isAttachedToWindow());
            parent.post(() -> invoke(parent, "showDialog", new Class<?>[0]));
            // Positive control: an active challenge can still display its real dialog.
            invoke(parent, "showDialog", new Class<?>[0]);
            Object shown = field(parent, "dialog");
            assertNotNull("Active challenge dialog remains available", shown);
            assertTrue(((AlertDialog) field(shown, "dialog")).isShowing());
            parent.stop(false);
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> assertNull("A queued challenge must not reopen a stopped parser", field(parent, "dialog")));
        assertEquals(0, callbacks.get());
    }

    @Test public void shouldDiscardDelayedJavascriptContinuationAfterStop() throws Exception {
        awaitJavascriptReady();
        instrumentation.runOnMainSync(() -> invoke(parent, "evaluate", new Class<?>[]{List.class, int.class},
                List.of("window.location.href='" + fixture.url("/script-active.html") + "'"), 0));
        assertTrue("Active parser JavaScript must really navigate before testing cancellation", fixture.activeScript.await(8, TimeUnit.SECONDS));
        instrumentation.runOnMainSync(() -> parent.stop(false));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            // Replay the continuation normally invoked by the previous evaluateJavascript
            // ValueCallback after it returns to the main thread, preserving its next index.
            invoke(parent, "evaluate", new Class<?>[]{List.class, int.class}, List.of("already completed",
                    "window.location.href='" + fixture.url("/script-forbidden.html") + "'"), 1);
        });
        assertFalse("A late script continuation must not navigate after stop", fixture.forbiddenScript.await(1500, TimeUnit.MILLISECONDS));
        assertEquals(0, callbacks.get());
    }

    private void awaitJavascriptReady() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            CountDownLatch evaluated = new CountDownLatch(1);
            AtomicReference<String> state = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> parent.evaluateJavascript("document.readyState", value -> {
                state.set(value);
                evaluated.countDown();
            }));
            if (evaluated.await(500, TimeUnit.MILLISECONDS) && "\"complete\"".equals(state.get())) return;
            SystemClock.sleep(40);
        }
        throw new AssertionError("WebView must finish its initial blank document before the script control");
    }

    private void addNestedPage() {
        invoke(parent, "onParseAdd", new Class<?>[]{Map.class, String.class}, Map.of(), fixture.url("/nested.html"));
    }

    private static void invoke(Object owner, String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(owner, arguments);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static List<?> views(ParseJob job) {
        return (List<?>) field(job, "webViews");
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class Fixture extends NanoHTTPD {
        final CountDownLatch loaded = new CountDownLatch(1);
        final CountDownLatch activeScript = new CountDownLatch(1);
        final CountDownLatch forbiddenScript = new CountDownLatch(1);
        final AtomicInteger pings = new AtomicInteger();

        Fixture() { super("127.0.0.1", 0); }

        String url(String path) { return "http://127.0.0.1:" + getListeningPort() + path; }

        @Override public Response serve(IHTTPSession session) {
            if ("/script-active.html".equals(session.getUri())) activeScript.countDown();
            if ("/script-forbidden.html".equals(session.getUri())) forbiddenScript.countDown();
            if (session.getUri().startsWith("/script-")) {
                return newFixedLengthResponse(Response.Status.OK, "text/html", "<html>Script navigation fixture</html>");
            }
            if ("/nested.html".equals(session.getUri())) {
                loaded.countDown();
                return newFixedLengthResponse(Response.Status.OK, "text/html",
                        "<html><script>setInterval(function(){fetch('/ping?t='+Date.now())},70)</script></html>");
            }
            if ("/ping".equals(session.getUri())) pings.incrementAndGet();
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
        }
    }
}
