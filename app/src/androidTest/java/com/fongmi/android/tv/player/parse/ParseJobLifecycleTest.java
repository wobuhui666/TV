package com.fongmi.android.tv.player.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;

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
        try {
            Field field = ParseJob.class.getDeclaredField("webViews");
            field.setAccessible(true);
            return (List<?>) field.get(job);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class Fixture extends NanoHTTPD {
        final CountDownLatch loaded = new CountDownLatch(1);
        final AtomicInteger pings = new AtomicInteger();

        Fixture() { super("127.0.0.1", 0); }

        String url(String path) { return "http://127.0.0.1:" + getListeningPort() + path; }

        @Override public Response serve(IHTTPSession session) {
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
