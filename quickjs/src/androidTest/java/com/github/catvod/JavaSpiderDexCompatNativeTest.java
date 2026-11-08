package com.github.catvod;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Dialog;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

import static org.junit.Assert.*;

/** Executes independently compiled old/new JAR bytecode against the real Android host. */
@RunWith(AndroidJUnit4.class)
public final class JavaSpiderDexCompatNativeTest {

    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Context tests = InstrumentationRegistry.getInstrumentation().getContext();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService hostCallbacks = Executors.newSingleThreadExecutor();
    private final List<Spider> loaded = new ArrayList<>();
    private File directory;
    private int previousPort;

    @Before
    public void initializeHost() {
        Init.set(target);
        previousPort = Proxy.getPort();
        Proxy.set(19091);
        directory = new File(target.getCacheDir(), "spider-java-dex-" + System.nanoTime());
        assertTrue(directory.mkdirs());
    }

    @After
    public void releaseHost() throws Exception {
        for (Spider spider : loaded) spider.close();
        worker.shutdownNow();
        hostCallbacks.shutdownNow();
        Init.setDialog(null);
        Init.setToast(null);
        Init.setActivity(null);
        Proxy.set(previousPort);
        delete(directory);
    }

    @Test(timeout = 15000)
    public void legacyJarLinksClientProxyInitAndPublicDialogFieldToHostClasses() throws Exception {
        Spider fixture = load("legacy", "com.github.catvod.fixtures.LegacyAbiFixture");
        Init.setDialog((options, callback) -> CompletableFuture.completedFuture("{\"button\":\"legacy-host\"}"));
        JSONObject result = new JSONObject(worker.submit(() -> invoke(fixture, "probe")).get(5, TimeUnit.SECONDS).toString());
        assertTrue(result.getBoolean("client"));
        assertEquals(target.getPackageName(), result.getString("context"));
        assertTrue(result.getBoolean("activityEmpty"));
        assertEquals("legacy-fixture", result.getString("toast"));
        assertEquals("legacy-host", result.getJSONObject("answer").getString("button"));
        assertProxy(result.getString("proxy"), fixture.siteKey, null);
        assertProxy(result.getString("proxyParams"), fixture.siteKey, "legacy & 中文");
        Runtime.toast("new-name-to-old-jar");
        assertEquals("new-name-to-old-jar", fixture.getClass().getField("toastValue").get(fixture));

        CompletableFuture<String> response = new CompletableFuture<>();
        Init.setDialog((options, callback) -> response);
        Object pending = invoke(fixture, "pendingDialog");
        assertSame(response, pending);
        assertFalse(response.isDone());
        invoke(fixture, "closeOwner");
        assertTrue("Old bytecode close() must cancel its actual host-owned dialog", response.isCancelled());
    }

    @Test(timeout = 15000)
    public void upstreamJarLinksBothDialogOverloadsRuntimeCallbacksAndCloseDialog() throws Exception {
        Spider fixture = load("modern", "com.github.catvod.fixtures.ModernAbiFixture");
        Runtime.setDialog((options, callback) -> {
            if (callback == null) return CompletableFuture.completedFuture("{\"button\":\"plain-host\"}");
            CompletableFuture<String> response = new CompletableFuture<>();
            hostCallbacks.execute(() -> {
                try {
                    String update = callback.apply("{\"action\":\"click\"}").get(5, TimeUnit.SECONDS);
                    response.complete(new JSONObject().put("button", "callback-host")
                            .put("update", new JSONObject(update)).toString());
                } catch (Throwable error) {
                    response.completeExceptionally(error);
                }
            });
            return response;
        });
        JSONObject result = new JSONObject(worker.submit(() -> invoke(fixture, "probe")).get(7, TimeUnit.SECONDS).toString());
        assertEquals(target.getPackageName(), result.getString("context"));
        assertSame(Runtime.context(), Init.context());
        assertTrue(result.getBoolean("activityEmpty"));
        assertEquals("modern-fixture", result.getString("toast"));
        assertEquals("plain-host", result.getJSONObject("plain").getString("button"));
        assertEquals("callback-host", result.getJSONObject("callback").getString("button"));
        assertEquals("click", result.getJSONObject("callback").getJSONObject("update").getString("received"));
        assertEquals(1, result.getInt("callbackCount"));
        assertEquals("fixture-host", result.getJSONObject("runtimeReply").getString("runtime"));
        assertEquals("fixture-host", new JSONObject(Init.dialog("{}", null).get(2, TimeUnit.SECONDS)).getString("runtime"));
        Init.toast("old-name-to-new-jar");
        assertEquals("old-name-to-new-jar", fixture.getClass().getField("toastValue").get(fixture));
        assertProxy(result.getString("proxy"), fixture.siteKey, null);
        assertProxy(result.getString("proxyParams"), fixture.siteKey, "modern & 中文");

        CompletableFuture<String> pending = new CompletableFuture<>();
        CountDownLatch opened = new CountDownLatch(1);
        Init.setDialog((options, callback) -> { opened.countDown(); return pending; });
        Future<Object> waiting = worker.submit(() -> invoke(fixture, "pendingDialog"));
        assertTrue(opened.await(3, TimeUnit.SECONDS));
        invoke(fixture, "closeOwnDialog");
        invoke(fixture, "closeOwnDialog");
        // The host may return its future after the close; Dialog.open cancels it on publication.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!pending.isCancelled() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("New bytecode closeDialog() must reach the actual owner", pending.isCancelled());
        assertThrows(ExecutionException.class, () -> waiting.get(3, TimeUnit.SECONDS));
        invoke(fixture, "closeOwner");
    }

    private Spider load(String kind, String expectedClass) throws Exception {
        JSONObject manifest;
        try (InputStream input = tests.getAssets().open("spider-java-compat/manifest.json")) {
            manifest = new JSONObject(new String(read(input), StandardCharsets.UTF_8));
        }
        assertEquals("independent compile-only ABI declarations; no host API jar", manifest.getString("apiMode"));
        JSONObject evidence = manifest.getJSONObject("fixtures").getJSONObject(kind);
        assertEquals(expectedClass, evidence.getString("className"));
        byte[] bytes;
        try (InputStream input = tests.getAssets().open("spider-java-compat/" + evidence.getString("file"))) {
            bytes = read(input);
        }
        assertEquals(evidence.getLong("bytes"), bytes.length);
        assertEquals(evidence.getString("sha256"), digest(bytes));
        File jar = new File(directory, kind + ".jar");
        try (FileOutputStream output = new FileOutputStream(jar)) { output.write(bytes); }
        assertTrue(jar.setReadOnly());
        try (ZipFile archive = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            assertTrue(entries.hasMoreElements());
            ZipEntry dex = entries.nextElement();
            assertEquals("classes.dex", dex.getName());
            assertFalse("Compile stubs must not appear in the JAR", entries.hasMoreElements());
            byte[] data;
            try (InputStream input = archive.getInputStream(dex)) { data = read(input); }
            assertEquals("dex\n", new String(data, 0, 4, StandardCharsets.US_ASCII));
            assertEquals("Only the fixture class may be defined; host types must resolve at runtime",
                    1, ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(96));
        }
        DexClassLoader loader = new DexClassLoader(jar.getAbsolutePath(), directory.getAbsolutePath(), null, Spider.class.getClassLoader());
        assertSame(Spider.class, loader.loadClass("com.github.catvod.crawler.Spider"));
        assertSame(Init.class, loader.loadClass("com.github.catvod.Init"));
        assertSame(Runtime.class, loader.loadClass("com.github.catvod.Runtime"));
        assertSame(Dialog.class, loader.loadClass("com.github.catvod.utils.Dialog"));
        assertSame(Dialog.Callback.class, loader.loadClass("com.github.catvod.utils.Dialog$Callback"));
        Class<?> type = loader.loadClass(expectedClass);
        assertSame("The fixture bytecode must come from its DEX loader", loader, type.getClassLoader());
        assertSame(Spider.class, type.getSuperclass());
        Spider fixture = (Spider) type.getDeclaredConstructor().newInstance();
        fixture.siteKey = "dex-" + kind;
        loaded.add(fixture);
        return fixture;
    }

    private static Object invoke(Object fixture, String method) throws Exception {
        try {
            return fixture.getClass().getMethod(method).invoke(fixture);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }

    private static void assertProxy(String url, String siteKey, String query) {
        Uri parsed = Uri.parse(url);
        assertEquals("127.0.0.1", parsed.getHost());
        assertEquals(19091, parsed.getPort());
        assertEquals("/proxy", parsed.getPath());
        assertEquals("jar", parsed.getQueryParameter("do"));
        assertEquals(siteKey, parsed.getQueryParameter("siteKey"));
        assertEquals(query, parsed.getQueryParameter("q"));
    }

    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        for (int count; (count = input.read(chunk)) != -1; ) output.write(chunk, 0, count);
        return output.toByteArray();
    }

    private static String digest(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        return result.toString();
    }

    private static void delete(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }
}
