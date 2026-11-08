package com.github.catvod;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Dialog;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class JavaSpiderCompatNativeTest {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Before
    public void setHostContext() {
        Init.set(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @After
    public void clearHost() {
        Init.setDialog(null);
        Init.setToast(null);
        Init.setActivity(null);
        worker.shutdownNow();
    }

    @Test
    public void newRuntimeNameReadsAndUpdatesTheLegacyHostState() throws Exception {
        Context context = Init.context();
        assertSame(context, Runtime.context());
        Runtime.set(context.getApplicationContext());
        assertSame(context.getApplicationContext(), Init.context());
        AtomicReference<String> message = new AtomicReference<>();
        Runtime.setToast(message::set);
        Init.toast("legacy");
        assertEquals("legacy", message.get());
        Init.setToast(message::set);
        Runtime.toast("modern");
        assertEquals("modern", message.get());
        assertNull(Runtime.activity());
        assertNotNull(Spider.class.getMethod("client").invoke(null));
        assertNotNull(Spider.class.getMethod("getProxyUrl"));
        assertEquals(Dialog.class, Spider.class.getField("dialog").getType());
    }

    @Test
    public void newDialogOverloadsUseTheExistingOwnerAndWorkerCallback() throws Exception {
        Spider spider = new Spider() {};
        CompletableFuture<String> response = new CompletableFuture<>();
        CountDownLatch opened = new CountDownLatch(1);
        AtomicReference<java.util.function.Function<String, CompletableFuture<String>>> action = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        Runtime.setDialog((options, callback) -> {
            action.set(callback);
            opened.countDown();
            return response;
        });
        try {
            Future<JSONObject> result = worker.submit(() -> spider.dialog(new JSONObject("{\"title\":\"test\"}"), event -> {
                callbackThread.set(Thread.currentThread());
                return new JSONObject().put("received", event.getString("action"));
            }));
            assertTrue(opened.await(3, TimeUnit.SECONDS));
            assertEquals("click", new JSONObject(action.get().apply("{\"action\":\"click\"}").get(3, TimeUnit.SECONDS)).getString("received"));
            assertNotSame(Thread.currentThread(), callbackThread.get());
            response.complete("{\"button\":\"ok\"}");
            assertEquals("ok", result.get(3, TimeUnit.SECONDS).getString("button"));
            Init.setDialog((options, callback) -> CompletableFuture.completedFuture("{\"button\":\"next\"}"));
            assertEquals("next", worker.submit(() -> spider.dialog(new JSONObject())).get(3, TimeUnit.SECONDS).getString("button"));
            // The legacy field still uses the same owner after modern calls.
            assertEquals("next", new JSONObject(spider.dialog.open("{}", null).get(3, TimeUnit.SECONDS)).getString("button"));
        } finally {
            spider.close();
        }
    }

    @Test
    public void closeDialogCancelsTheLegacyOwnerWithoutClosingAnotherSpider() throws Exception {
        Spider first = new Spider() {};
        Spider second = new Spider() {};
        Init.setDialog((options, callback) -> new CompletableFuture<>());
        try {
            CompletableFuture<String> a = first.dialog.open("{}", null);
            CompletableFuture<String> b = second.dialog.open("{}", null);
            first.closeDialog();
            first.closeDialog();
            assertTrue(a.isCancelled());
            assertFalse(b.isDone());
            assertThrows(Exception.class, () -> worker.submit(() -> first.dialog(new JSONObject())).get(3, TimeUnit.SECONDS));
            second.close();
            assertTrue(b.isCancelled());
        } finally {
            first.close();
            second.close();
        }
    }
}
