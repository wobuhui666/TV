package com.fongmi.android.tv.server;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.process.RemoteKey;
import com.fongmi.android.tv.test.RemoteKeyPreviewActivity;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class RemoteKeyAndroidTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final RemoteKey remote = new RemoteKey();
    private RemoteKeyPreviewActivity activity;

    @Before
    public void launch() {
        Context context = instrumentation.getTargetContext();
        instrumentation.setInTouchMode(false);
        Intent intent = new Intent().setComponent(new ComponentName(context.getPackageName(), RemoteKeyPreviewActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (RemoteKeyPreviewActivity) instrumentation.startActivitySync(intent);
        await(() -> activity.hasWindowFocus() && App.activity() == activity, "fixture window focus");
        main(() -> assertTrue(activity.first.requestFocus()));
        await(() -> activity.first.hasFocus(), "first button focus");
    }

    @After
    public void close() {
        if (activity != null) main(() -> {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            activity.finish();
        });
        instrumentation.waitForIdleSync();
    }

    @Test
    public void shouldMoveFocusAndActivateOnlyTheFocusedWindow() throws Exception {
        accepted("down");
        await(() -> activity.second.hasFocus(), "down key moved focus");
        accepted("ok");
        assertEquals(1, (int) read(() -> activity.clicks));
        accepted("up");
        await(() -> activity.first.hasFocus(), "up key moved focus");
        main(() -> activity.consumeDirections = true);
        accepted("down");
        assertTrue("A handled D-pad callback must not move focus again", read(() -> activity.first.hasFocus()));
        assertEquals(1, (int) read(() -> activity.consumedDirectionDowns));
        main(() -> activity.consumeDirections = false);
        accepted("down");
        await(() -> activity.second.hasFocus(), "unhandled D-pad uses native focus search");
        accepted("down");
        assertTrue("No next neighbor must keep the current focus", read(() -> activity.second.hasFocus()));
        main(() -> activity.first.setFocusableInTouchMode(true));
        instrumentation.setInTouchMode(true);
        main(() -> assertTrue(activity.first.requestFocus()));
        await(() -> activity.first.hasFocus() && activity.first.isInTouchMode(), "touch-mode focused control");
        accepted("down");
        await(() -> activity.second.hasFocus() && !activity.second.isInTouchMode(), "D-pad exits touch mode before navigation");
    }

    @Test
    public void shouldDispatchNumericAndMenuTapsInsideTheApp() throws Exception {
        accepted("7");
        accepted("menu");
        assertEquals(1, (int) read(() -> activity.numericTaps));
        assertEquals(7, (int) read(() -> activity.lastNumeric));
        assertEquals(1, (int) read(() -> activity.menuTaps));
    }

    @Test
    public void shouldUseTheActivityBackDispatcher() throws Exception {
        accepted("back");
        assertEquals(1, (int) read(() -> activity.backs));
        assertFalse(read(() -> activity.isFinishing()));
    }

    @Test
    public void shouldDirectKeysToTheNestedFocusedDialog() throws Exception {
        RemoteKeyPreviewActivity.Sheet sheet = read(() -> activity.showNestedDialog());
        await(() -> sheet.getDialog() != null && sheet.getDialog().getWindow().getDecorView().hasWindowFocus(), "nested dialog focus");
        main(() -> assertTrue(sheet.first.requestFocus()));
        await(() -> sheet.first.hasFocus(), "dialog first focus");
        accepted("down");
        await(() -> sheet.second.hasFocus(), "dialog down key");
        main(() -> activity.consumeDirections = true);
        accepted("up");
        assertTrue("A dialog-handled D-pad must not invoke the fallback", read(() -> sheet.second.hasFocus()));
        assertEquals(1, (int) read(() -> activity.consumedDirectionDowns));
        main(() -> activity.consumeDirections = false);
        accepted("up");
        await(() -> sheet.first.hasFocus(), "dialog native up key");
        accepted("down");
        await(() -> sheet.second.hasFocus(), "dialog native down key before click");
        accepted("ok");
        assertEquals(1, (int) read(() -> activity.dialogClicks));
        assertEquals(0, (int) read(() -> activity.clicks));
        accepted("back");
        await(() -> !sheet.isAdded() && activity.hasWindowFocus(), "back dismissed nested dialog");
        assertEquals(0, (int) read(() -> activity.backs));
    }

    @Test
    public void shouldRejectAWindowThatCannotReceiveFocus() throws Exception {
        main(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE));
        await(() -> !activity.hasWindowFocus(), "fixture relinquished window focus");
        assertEquals(409, response(Method.POST, Map.of("key", "ok")).getStatus().getRequestStatus());
        assertEquals(0, (int) read(() -> activity.clicks));
    }

    @Test
    public void shouldRejectWhenNoActivityIsResumed() throws Exception {
        main(activity::finish);
        await(() -> App.activity() == null, "activity released");
        assertEquals(409, response(Method.POST, Map.of("key", "ok")).getStatus().getRequestStatus());
    }

    @Test
    public void shouldRejectUnsupportedKeysActionsAndMethods() throws Exception {
        assertTrue(remote.isRequest(null, "/remote/key"));
        assertFalse(remote.isRequest(null, "/remote/key-extra"));
        assertEquals(405, response(Method.GET, Map.of("key", "ok")).getStatus().getRequestStatus());
        assertEquals(400, response(Method.POST, Map.of("key", "home")).getStatus().getRequestStatus());
        assertEquals(400, response(Method.POST, Map.of("key", "ok", "action", "down")).getStatus().getRequestStatus());
        assertEquals(400, response(Method.POST, Map.of()).getStatus().getRequestStatus());
        Map<String, String> tap = Map.of("key", "ok");
        assertEquals(403, response(Method.POST, tap, Map.of("host", "localhost:9978", "origin", "http://example.invalid:9978"))
                .getStatus().getRequestStatus());
        assertEquals(403, response(Method.POST, tap, Map.of("host", "localhost:9978", "origin", "https://localhost:9978"))
                .getStatus().getRequestStatus());
        assertEquals(403, response(Method.POST, tap, Map.of("host", "localhost:9978", "origin", "null"))
                .getStatus().getRequestStatus());
        assertEquals(0, (int) read(() -> activity.clicks));
        assertEquals(200, response(Method.POST, tap, Map.of("host", "localhost:9978", "origin", "http://LOCALHOST:9978"))
                .getStatus().getRequestStatus());
        assertEquals(1, (int) read(() -> activity.clicks));
    }

    @Test
    public void shouldDiscardAQueuedTapAfterTheBoundedWait() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        App.post(() -> {
            blocked.countDown();
            try {
                release.await(6, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            assertEquals(503, response(Method.POST, Map.of("key", "ok")).getStatus().getRequestStatus());
        } finally {
            release.countDown();
        }
        instrumentation.waitForIdleSync();
        assertEquals("A cancelled network request must not click later", 0, (int) read(() -> activity.clicks));
    }

    private void accepted(String key) throws Exception {
        Response result = response(Method.POST, Map.of("key", key, "action", "press"));
        assertEquals(200, result.getStatus().getRequestStatus());
        String body = new String(result.getData().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(JsonParser.parseString(body).getAsJsonObject().get("accepted").getAsBoolean());
    }

    private Response response(Method method, Map<String, String> params) {
        return response(method, params, Map.of());
    }

    private Response response(Method method, Map<String, String> params, Map<String, String> headers) {
        IHTTPSession session = (IHTTPSession) Proxy.newProxyInstance(IHTTPSession.class.getClassLoader(),
                new Class<?>[]{IHTTPSession.class}, (proxy, called, arguments) -> switch (called.getName()) {
                    case "getMethod" -> method;
                    case "getParms" -> params;
                    case "getHeaders" -> headers;
                    default -> null;
                });
        return remote.doResponse(session, "/remote/key", Map.of());
    }

    private void main(Runnable action) { instrumentation.runOnMainSync(action); }

    private <T> T read(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        main(() -> result.set(action.get()));
        return result.get();
    }

    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 12_000;
        while (SystemClock.uptimeMillis() < end) {
            if (read(condition::getAsBoolean)) return;
            SystemClock.sleep(40);
        }
        assertTrue(message, read(condition::getAsBoolean));
    }
}
