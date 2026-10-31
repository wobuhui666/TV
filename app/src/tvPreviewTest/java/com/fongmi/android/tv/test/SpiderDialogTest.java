package com.fongmi.android.tv.test;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.view.inspector.WindowInspector;
import androidx.test.filters.SdkSuppress;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.activity.SpiderDialogPreviewActivity;
import com.fongmi.android.tv.ui.dialog.SpiderDialog;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.crawler.Spider;
import com.fongmi.quickjs.utils.QuickLog;
import com.github.catvod.Init;
import com.github.catvod.utils.Dialog;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import fi.iki.elonen.NanoHTTPD;

/** Real source dialog lifecycle and QuickJS/JNI checks. All data and endpoints are local fixtures. */
@RunWith(AndroidJUnit4.class)
public final class SpiderDialogTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final List<Dialog> owners = new ArrayList<>();
    private final List<ListenableFuture<?>> calls = new ArrayList<>();
    private SpiderDialogPreviewActivity activity;

    @Before public void launch() {
        Intent intent = new Intent(instrumentation.getTargetContext(), SpiderDialogPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (SpiderDialogPreviewActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        Init.setActivity(() -> activity);
    }

    @After public void close() {
        for (ListenableFuture<?> call : calls) call.cancel(true);
        for (Dialog owner : owners) owner.close();
        Init.setActivity(App::activity);
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
    }

    private Dialog owner() {
        Dialog dialog = new Dialog(); owners.add(dialog); return dialog;
    }

    private JSONObject form(String title) throws Exception {
        return new JSONObject().put("title", title).put("message", "Sign in to the fixture source")
                .put("qrcode", "https://example.invalid/source-dialog-fixture")
                .put("inputs", new JSONArray()
                        .put(new JSONObject().put("name", "code").put("label", "Code").put("required", true).put("minLength", 4)))
                .put("buttons", new JSONArray()
                        .put(new JSONObject().put("id", "verify").put("label", "Verify").put("close", false))
                        .put(new JSONObject().put("id", "done").put("label", "Done")));
    }

    @Test public void javaFormKeepsCallbacksOnCallerAndPausesInputTimeout() throws Exception {
        Dialog owner = owner();
        JSONObject options = form("Java fixture login");
        AtomicInteger callbacks = new AtomicInteger();
        ListenableFuture<JSONObject> call = Task.submit(() -> {
            Thread caller = Thread.currentThread();
            return owner.show(options, event -> {
                assertSame(caller, Thread.currentThread());
                assertNotSame(Looper.getMainLooper(), Looper.myLooper());
                callbacks.incrementAndGet();
                if (event.getString("button").equals("verify")) {
                    return new JSONObject().put("message", "Code accepted")
                            .put("values", new JSONObject().put("code", "5678"))
                            .put("inputs", new JSONArray().put(new JSONObject().put("name", "code").put("enabled", false)));
                }
                return new JSONObject().put("close", true);
            });
        }, 500);
        calls.add(call);
        text("Java fixture login");
        capture("java-login-form");
        SystemClock.sleep(750);
        assertFalse("Human input must not consume the source timeout", call.isDone());
        text("Verify").click();
        instrumentation.waitForIdleSync();
        assertEquals("Required field stops callback", 0, callbacks.get());
        field("Code").setText("1234");
        text("Verify").click();
        text("Code accepted");
        assertEquals(1, callbacks.get());
        assertFalse(field("Code").isEnabled());
        capture("java-login-validated");
        text("Done").click();
        JSONObject result = call.get(5, TimeUnit.SECONDS);
        assertEquals("done", result.getString("button"));
        assertEquals("5678", result.getJSONObject("values").getString("code"));
        assertEquals(2, callbacks.get());
    }

    @Test public void cancellationAndMissingActivityNeverLeavePendingWindows() throws Exception {
        Dialog owner = owner();
        ListenableFuture<JSONObject> call = Task.submit(() -> owner.show(new JSONObject().put("title", "Cancel fixture")), 500);
        calls.add(call);
        text("Cancel fixture");
        call.cancel(true);
        absent("Cancel fixture");
        ListenableFuture<JSONObject> background = Task.submit(() -> owner.show(new JSONObject().put("title", "Background fixture")), 500, false);
        calls.add(background);
        assertThrows(ExecutionException.class, () -> background.get(5, TimeUnit.SECONDS));
        assertFalse(device.hasObject(By.text("Background fixture")));
        Init.setActivity(() -> null);
        assertThrows(ExecutionException.class, () -> SpiderDialog.show("{}").get(5, TimeUnit.SECONDS));
        instrumentation.runOnMainSync(() -> assertThrows(IllegalStateException.class, () -> owner.show(new JSONObject())));
    }

    @Test public void remoteNavigationSupportsTextareaAndNativeChoiceLists() throws Exception {
        JSONObject options = new JSONObject().put("title", "Choices fixture").put("inputs", new JSONArray()
                .put(new JSONObject().put("name", "note").put("label", "Note").put("type", "textarea").put("value", "First line\nSecond line"))
                .put(new JSONObject().put("name", "account").put("label", "Account").put("type", "select")
                        .put("options", new JSONArray().put(choice("one", "Account one")).put(choice("two", "Account two"))))
                .put(new JSONObject().put("name", "roles").put("label", "Roles").put("type", "multiselect")
                        .put("options", new JSONArray().put(choice("viewer", "Viewer")).put(choice("editor", "Editor")))))
                .put("buttons", new JSONArray().put(new JSONObject().put("id", "finish").put("label", "Finish")));
        Dialog owner = owner();
        ListenableFuture<JSONObject> call = Task.submit(() -> owner.show(options), 1000);
        calls.add(call);
        text("Choices fixture");
        field("Note").setText("First line\nSecond line");
        device.pressDPadDown();
        focused("Account");
        device.pressDPadCenter();
        text("Account two").click();
        field("Roles").click();
        text("Editor").click();
        text(activity.getString(android.R.string.ok)).click();
        capture("source-dialog-choices");
        text("Finish").click();
        JSONObject values = call.get(5, TimeUnit.SECONDS).getJSONObject("values");
        assertEquals("two", values.getString("account"));
        assertEquals("editor", values.getJSONArray("roles").getString(0));
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void remoteNavigationScrollsLongFormsBeforeLeavingTheInputs() throws Exception {
        JSONArray fields = new JSONArray();
        for (int i = 1; i <= 12; i++) fields.put(new JSONObject().put("name", "field" + i).put("label", "Field " + i));
        JSONObject options = new JSONObject().put("title", "Long source form").put("inputs", fields)
                .put("buttons", new JSONArray().put(new JSONObject().put("id", "finish").put("label", "Finish long form")));
        Dialog owner = owner();
        ListenableFuture<JSONObject> call = Task.submit(() -> owner.show(options), 1000);
        calls.add(call);
        text("Long source form");
        final View[] first = new View[1];
        final ScrollView[] scroll = new ScrollView[1];
        instrumentation.runOnMainSync(() -> {
            first[0] = findDescription(currentDialogRoot(), "Field 1");
            assertNotNull(first[0]);
            for (android.view.ViewParent parent = first[0].getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof ScrollView container) { scroll[0] = container; break; }
            }
            assertNotNull(scroll[0]);
            assertTrue(first[0].requestFocus());
        });
        for (int i = 2; i <= 12; i++) {
            device.pressDPadDown();
            focused("Field " + i);
        }
        instrumentation.runOnMainSync(() -> {
            assertTrue("Long forms must scroll while remote focus stays in the inputs", scroll[0].getScrollY() > 0);
            View focused = currentDialogRoot().findFocus();
            assertTrue(focused instanceof EditText);
            assertEquals("Field 12", focused.getContentDescription().toString());
            Rect fieldBounds = new Rect();
            assertTrue("The final input must be visible after scrolling", focused.getGlobalVisibleRect(fieldBounds));
        });
        capture("long-form-scroll");
        text("Finish long form").click();
        assertEquals("finish", call.get(5, TimeUnit.SECONDS).getString("button"));
    }

    private View currentDialogRoot() {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (findDescription(root, "Field 1") != null) return root;
        }
        throw new AssertionError("Long form window is not attached");
    }

    private View findDescription(View view, String description) {
        if (description.equals(String.valueOf(view.getContentDescription()))) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void quickJsDialogsSurviveAsyncNetworkingAndStayWithTheirCaller() throws Exception {
        boolean logging = QuickLog.isEnabled();
        QuickLog.putEnabled(true);
        // This preview contains real x86 QuickJS, but deliberately no Python native runtime.
        com.fongmi.android.tv.api.loader.PyLoader unusedPython = new com.fongmi.android.tv.api.loader.PyLoader();
        unusedPython.clear();
        Fixture server = new Fixture();
        Spider spider = null;
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            String base = "http://127.0.0.1:" + server.getListeningPort();
            spider = new Loader().spider(base + "/dialog.js", null);
            spider.siteKey = "dialog-fixture-" + server.getListeningPort();
            Spider source = spider;
            ListenableFuture<?> init = Task.submit(() -> { source.init(activity, new JSONObject().put("base", base).toString()); return null; }, 10000);
            calls.add(init); init.get(15, TimeUnit.SECONDS);
            ListenableFuture<String> call = Task.submit(() -> source.action("login"), 700);
            calls.add(call);
            text("JS first form");
            SystemClock.sleep(850);
            assertFalse(call.isDone());
            text("Next").click();
            text("JS second form");
            field("JS code").setText("2468");
            text("Check").click();
            text("Verified after HTTP");
            capture("quickjs-sequential-login");
            text("Complete").click();
            JSONObject result = new JSONObject(call.get(5, TimeUnit.SECONDS));
            assertEquals("next", result.getJSONObject("first").getString("button"));
            assertEquals("2468", result.getJSONObject("second").getJSONObject("values").getString("code"));
            ListenableFuture<String> cancelled = Task.submit(() -> source.action("cancel"), 700);
            calls.add(cancelled);
            text("JS cancel form");
            cancelled.cancel(true);
            absent("JS cancel form");
            ListenableFuture<String> background = Task.submit(() -> source.action("cancel"), 700, false);
            calls.add(background);
            assertThrows(ExecutionException.class, () -> background.get(5, TimeUnit.SECONDS));
            assertFalse(device.hasObject(By.text("JS cancel form")));
            ListenableFuture<String> again = Task.submit(() -> source.action("again"), 700);
            calls.add(again);
            text("JS still usable");
            text("Close").click();
            assertEquals("ok", new JSONObject(again.get(5, TimeUnit.SECONDS)).getString("button"));
            String sentinel = "FORM_PRIVATE_SENTINEL_8642";
            ListenableFuture<String> secret = Task.submit(() -> source.action("secret"), 700);
            calls.add(secret);
            text("Private fixture");
            field("Password").setText(sentinel);
            text("Submit private").click();
            assertEquals(sentinel, new JSONObject(secret.get(5, TimeUnit.SECONDS)).getJSONObject("values").getString("password"));
            assertFalse("Dialog values leaked into exported JS logs", String.join("\n", QuickLog.getLogs()).contains(sentinel));
            ListenableFuture<String> secretFailure = Task.submit(() -> source.action("secret-failure"), 700);
            calls.add(secretFailure);
            text("Private fixture");
            field("Password").setText(sentinel);
            text("Submit private").click();
            ExecutionException failed = assertThrows(ExecutionException.class, () -> secretFailure.get(5, TimeUnit.SECONDS));
            java.io.StringWriter trace = new java.io.StringWriter();
            failed.printStackTrace(new java.io.PrintWriter(trace));
            assertFalse("Dialog exception leaked through caller", trace.toString().contains(sentinel));
            assertFalse("Dialog exception leaked into exported JS logs", String.join("\n", QuickLog.getLogs()).contains(sentinel));
            com.fongmi.android.tv.api.loader.JsLoader cached = new com.fongmi.android.tv.api.loader.JsLoader();
            String ext = new JSONObject().put("base", base).put("prompt", true).toString();
            try {
                ListenableFuture<com.github.catvod.crawler.Spider> coldBackground = Task.submit(() -> cached.getSpider("cold-dialog-fixture", base + "/init-dialog.js", ext, ""), 2000, false);
                calls.add(coldBackground);
                assertTrue(coldBackground.get(5, TimeUnit.SECONDS) instanceof com.github.catvod.crawler.SpiderNull);
                assertFalse(device.hasObject(By.text("Initialization login")));
                Init.setActivity(() -> null);
                ListenableFuture<com.github.catvod.crawler.Spider> noActivity = Task.submit(() -> cached.getSpider("cold-dialog-fixture", base + "/init-dialog.js", ext, ""), 2000);
                calls.add(noActivity);
                assertTrue(noActivity.get(5, TimeUnit.SECONDS) instanceof com.github.catvod.crawler.SpiderNull);
                Init.setActivity(() -> activity);
                ListenableFuture<com.github.catvod.crawler.Spider> cancelledInit = Task.submit(() -> cached.getSpider("cold-dialog-fixture", base + "/init-dialog.js", ext, ""), 2000);
                calls.add(cancelledInit);
                text("Initialization login");
                cancelledInit.cancel(true);
                absent("Initialization login");
                ListenableFuture<com.github.catvod.crawler.Spider> coldManual = Task.submit(() -> cached.getSpider("cold-dialog-fixture", base + "/init-dialog.js", ext, ""), 2000);
                calls.add(coldManual);
                text("Initialization login");
                text("Continue initialization").click();
                assertTrue("Background denial must not cache a failed spider", coldManual.get(5, TimeUnit.SECONDS) instanceof Spider);
            } finally { cached.clear(); }
        } finally {
            if (spider != null) spider.destroy();
            server.stop();
            QuickLog.putEnabled(logging);
        }
    }

    private static JSONObject choice(String value, String label) throws Exception {
        return new JSONObject().put("value", value).put("label", label);
    }

    private void refresh() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.getUiAutomation().clearCache();
    }

    private UiObject2 text(String value) {
        long until = SystemClock.uptimeMillis() + 8000;
        do {
            refresh();
            UiObject2 found = device.findObject(By.text(value));
            if (found != null) return found;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Missing dialog control: " + value);
    }

    private UiObject2 field(String label) {
        refresh();
        UiObject2 found = device.findObject(By.desc(label));
        assertNotNull("Missing dialog field " + label, found);
        return found;
    }

    private void focused(String label) {
        long until = SystemClock.uptimeMillis() + 4000;
        do {
            refresh();
            UiObject2 focused = device.findObject(By.desc(label).focused(true));
            if (focused != null) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < until);
        fail("D-pad focus did not reach " + label);
    }

    private void absent(String value) {
        long until = SystemClock.uptimeMillis() + 5000;
        do {
            refresh();
            if (!device.hasObject(By.text(value))) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < until);
        fail("Dialog remained after cancellation: " + value);
    }

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(250);
        File directory = new File(activity.getExternalFilesDir(null), "spider-dialog-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        bitmap.recycle();
        device.dumpWindowHierarchy(new File(directory, name + ".xml"));
    }

    private static final class Fixture extends NanoHTTPD {
        Fixture() { super("127.0.0.1", 0); }
        @Override public Response serve(IHTTPSession session) {
            return newFixedLengthResponse(Response.Status.OK, session.getUri().endsWith(".js") ? "application/javascript" : "application/json",
                    session.getUri().endsWith(".js") ? SCRIPT : "{\"ok\":true}");
        }
    }

    private static final String SCRIPT = """
            export default function() {
              let base;
              return {
                async init(ext) {
                  base = ext.base;
                  if (ext.prompt) await dialog({title:'Initialization login',buttons:[{id:'ok',label:'Continue initialization'}]});
                }, destroy() {},
                async action(kind) {
                  if (kind === 'secret' || kind === 'secret-failure') {
                    const result = await dialog({title:'Private fixture',inputs:[{name:'password',label:'Password',type:'password'}],buttons:[{id:'ok',label:'Submit private'}]});
                    if (kind === 'secret-failure') throw new Error(result.values.password);
                    return result;
                  }
                  if (kind === 'cancel') return await dialog({title:'JS cancel form'});
                  if (kind === 'again') return await dialog({title:'JS still usable',buttons:[{id:'ok',label:'Close'}]});
                  await net.json(base + '/probe');
                  const first = await dialog({title:'JS first form',buttons:[{id:'next',label:'Next'}]});
                  await new Promise(resolve => setTimeout(resolve, 25));
                  const second = await dialog({title:'JS second form',inputs:[{name:'code',label:'JS code',required:true}],
                    buttons:[{id:'check',label:'Check',close:false},{id:'complete',label:'Complete'}],
                    async onAction(event) {
                      if (event.button === 'check') {
                        await net.json(base + '/verify');
                        return {message:'Verified after HTTP',close:false};
                      }
                      return {close:true};
                    }});
                  return {first,second};
                }
              };
            }
            """;
}
