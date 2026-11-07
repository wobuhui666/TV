package com.fongmi.android.tv.test;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.api.loader.JarLoader;
import com.github.catvod.bean.Result;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.Net;
import com.github.catvod.utils.Local;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

/** Uses real generated classes.dex assets and the production JarLoader, not a JVM loader substitute. */
@RunWith(AndroidJUnit4.class)
public final class SpiderDexCompatibilityTest {

    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final JSONObject report = new JSONObject();
    private String stage = "start";

    @Test
    public void legacyHelpersStayPrivateAndModernApiBindsBeforeInit() throws Exception {
        assertTrue("Use the isolated test application", target.getPackageName().equals("com.fongmi.android.tv.sourceprobe")
                || target.getPackageName().equals("com.fongmi.android.tv.preview"));
        JarLoader jars = new JarLoader();
        Fixture server = new Fixture();
        String prefix = "dex-api-" + UUID.randomUUID();
        Spider legacy = null, modern = null;
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            JSONObject manifest;
            try (InputStream input = assets("manifest.json")) {
                manifest = new JSONObject(new String(read(input), StandardCharsets.UTF_8));
            }
            File directory = new File(target.getCacheDir(), prefix);
            assertTrue(directory.mkdirs());
            File oldJar = copy("legacy", directory, manifest);
            File newJar = copy("modern", directory, manifest);
            String oldUrl = "file://" + oldJar.getAbsolutePath();
            String newUrl = "file://" + newJar.getAbsolutePath();
            legacy = step("legacy-load", () -> jars.getSpider(prefix + "-legacy", "csp_LegacyFixture", "", oldUrl));
            assertFalse("Legacy fixture failed to load", legacy instanceof SpiderNull);
            Spider old = legacy;
            JSONObject oldResult = new JSONObject(step("legacy-home", () -> old.homeContent(true)));
            assertEquals("legacy-result", oldResult.getString("result"));
            assertEquals("legacy-vod", oldResult.getString("vod"));
            assertTrue(oldResult.getBoolean("client"));
            assertTrue(oldResult.getBoolean("hostSpider"));
            assertTrue(oldResult.getBoolean("hostNet"));
            ClassLoader oldLoader = jars.dex(oldUrl);
            assertSame(Spider.class, oldLoader.loadClass("com.github.catvod.crawler.Spider"));
            assertSame(Net.class, oldLoader.loadClass("com.github.catvod.net.Net"));
            assertNotSame(Result.class, oldLoader.loadClass("com.github.catvod.bean.Result"));
            String endpoint = "http://127.0.0.1:" + server.getListeningPort() + "/json";
            modern = step("modern-load", () -> jars.getSpider(prefix + "-modern", "csp_ModernFixture", endpoint, newUrl));
            assertFalse("Modern fixture failed to load", modern instanceof SpiderNull);
            Spider current = modern;
            JSONObject result = new JSONObject(step("modern-home", () -> current.homeContent(true)));
            assertTrue(result.getBoolean("bound"));
            assertTrue(result.getBoolean("http"));
            assertTrue(result.getBoolean("local"));
            assertTrue(result.getBoolean("hostResult"));
            assertEquals("modern", result.getJSONArray("list").getJSONObject(0).getString("vod_id"));
            assertSame(Result.class, jars.dex(newUrl).loadClass("com.github.catvod.bean.Result"));
            assertEquals("{\"bound\":true}", new Local(prefix + "-modern").get("fixture"));
            // Legacy destroy deliberately throws. Both owners still have to close.
            step("loader-clear", () -> { jars.clear(); return true; });
            assertTrue(new JSONObject(legacy.net.req(endpoint, "{}")).has("error"));
            assertTrue(new JSONObject(modern.net.req(endpoint, "{}")).has("error"));
            assertNotNull(new Local(prefix + "-modern").get("fixture"));
            report.put("status", "PASS").put("legacyHelpersPrivate", true)
                    .put("runtimeTypesFromHost", true).put("modernBoundBeforeInit", true)
                    .put("cleanupSurvivesPluginDestroyFailure", true);
        } catch (Throwable failure) {
            report.put("status", "FAIL").put("stage", stage).put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            Future<?> cleanup = worker.submit(jars::clear);
            try { cleanup.get(10, TimeUnit.SECONDS); } catch (Exception ignored) { cleanup.cancel(true); }
            worker.shutdownNow();
            if (legacy != null && legacy.local != null) legacy.local.clear();
            if (modern != null && modern.local != null) modern.local.clear();
            server.stop();
            write();
        }
    }

    private File copy(String name, File directory, JSONObject manifest) throws Exception {
        File file = new File(directory, name + ".jar");
        byte[] bytes;
        try (InputStream input = assets(name + ".jar")) { bytes = read(input); }
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sha = new StringBuilder();
        for (byte value : digest) sha.append(String.format(Locale.ROOT, "%02x", value & 255));
        assertEquals("DEX fixture hash mismatch", manifest.getJSONObject("fixtures").getJSONObject(name).getString("sha256"), sha.toString());
        return file;
    }

    private InputStream assets(String name) throws Exception {
        return InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("spider-api/" + name);
    }

    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private <T> T step(String name, Callable<T> callable) throws Exception {
        stage = name;
        report.put("stage", stage);
        write();
        Future<T> future = worker.submit(callable);
        try { return future.get(25, TimeUnit.SECONDS); }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    private void write() throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(target.getFilesDir(), "spider-dex-result.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Fixture extends NanoHTTPD {
        Fixture() { super("127.0.0.1", 0); }
        @Override public Response serve(IHTTPSession session) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
        }
    }
}
