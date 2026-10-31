package com.fongmi.android.tv.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.CallTimeout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Actual guarded sources in a disposable ARM package; exports counts and failure types only. */
@RunWith(AndroidJUnit4.class)
public final class GuardedConfigAndroidTest {

    private static final String PACKAGE = "com.fongmi.android.tv.sourceprobe";
    private static final String CONFIG_URL = "https://9280.kstore.vip/aiwex.json";
    private static final long PROBE_TIMEOUT_MS = 30_000;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final JSONObject report = new JSONObject();
    private final JSONArray stages = new JSONArray();

    @Test(timeout = 300_000)
    public void shouldInitializeRealGuardedSourcesAndReturnContent() throws Exception {
        assertEquals("Only the disposable sourceprobe package may run this test", PACKAGE, target.getPackageName());
        assertEquals("Select the requested real configuration explicitly", CONFIG_URL,
                InstrumentationRegistry.getArguments().getString("performance_config_url", ""));
        File directory = target.getExternalFilesDir("guarded-source-verification");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        // At most four source calls can run, including a plugin which ignores interruption.
        ExecutorService workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "guarded-source-probe");
            thread.setDaemon(true);
            return thread;
        });
        boolean initialized = false;
        boolean contentReturned = false;
        report.put("schema", "tv.guarded-source-verification.v1");
        report.put("stages", stages);
        report.put("nativeArmPlaybackVerified", false);
        report.put("passed", false);
        try {
            recordRuntime();
            CountDownLatch loaded = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean();
            long started = SystemClock.elapsedRealtime();
            workers.submit(() -> VodConfig.load(Config.find(CONFIG_URL, 0), new Callback() {
                @Override public void success() { success.set(true); loaded.countDown(); }
                @Override public void error(String message) { loaded.countDown(); }
            }));
            boolean finished = loaded.await(90, TimeUnit.SECONDS);
            report.put("configLoadMs", SystemClock.elapsedRealtime() - started);
            report.put("configStatus", !finished ? "timeout" : success.get() ? "loaded" : "load-failed");
            assertTrue("Real configuration timed out", finished);
            assertTrue("Real configuration did not load", success.get());
            report.put("siteCount", VodConfig.get().getSites().size());
            assertTrue("Real configuration must contain sources", !VodConfig.get().getSites().isEmpty());

            JSONObject home = probe(workers, "home", VodConfig.get().getHome());
            initialized |= home.optBoolean("pluginInitialized");
            contentReturned |= home.optBoolean("contentReturned");
            int attempts = 0;
            for (Site site : VodConfig.get().getSites()) {
                if (!site.isSearchable()) continue;
                if (attempts == 3) break;
                attempts++;
                JSONObject search = probe(workers, "search", site);
                initialized |= search.optBoolean("pluginInitialized");
                contentReturned |= search.optBoolean("contentReturned");
            }
            report.put("searchProbeCount", attempts);
            report.put("pluginInitializationVerified", initialized);
            report.put("sourceContentVerified", contentReturned);
            report.put("sourceAvailability", !initialized ? "source-not-loaded"
                    : contentReturned ? "content-returned" : "initialized-without-content");
            try (FileInputStream maps = new FileInputStream("/proc/self/maps")) {
                String entries = new String(maps.readNBytes(4 * 1024 * 1024), StandardCharsets.UTF_8);
                report.put("nativeBridgeMapped", entries.contains("libndk_translation.so") || entries.contains("libhoudini.so"));
            } catch (Exception failure) {
                report.put("nativeBridgeMapsFailureTypes", exceptionTypes(failure));
            }
            assertTrue("No real plugin initialized; inspect the sanitized guarded report", initialized);
            assertTrue("Plugins initialized but no source returned content; source availability remains unverified", contentReturned);
            report.put("passed", true);
        } catch (Throwable failure) {
            report.put("failureTypes", exceptionTypes(failure));
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new AssertionError("Guarded source verification failed");
        } finally {
            workers.shutdownNow();
            OkHttp.client().dispatcher().cancelAll();
            OkHttp.client().connectionPool().evictAll();
            report.put("workerCleanupComplete", workers.awaitTermination(3, TimeUnit.SECONDS));
            // This package is disposable and has no UI, playback, or user configuration to retain.
            BaseLoader.get().clear();
            Server.get().stop();
            report.put("pluginInitializationVerified", initialized);
            report.put("sourceContentVerified", contentReturned);
            try (FileOutputStream stream = new FileOutputStream(new File(directory, "report.json"))) {
                stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private JSONObject probe(ExecutorService workers, String phase, Site site) throws Exception {
        JSONObject stage = new JSONObject().put("phase", phase)
                .put("siteIndex", VodConfig.get().getSites().indexOf(site));
        stages.put(stage);
        long started = SystemClock.elapsedRealtime();
        AtomicBoolean pluginInitialized = new AtomicBoolean();
        AtomicReference<String> spiderClass = new AtomicReference<>();
        Future<JSONObject> pending = workers.submit(() -> {
            JSONObject outcome = new JSONObject().put("pluginInitialized", false).put("contentReturned", false);
            Thread thread = Thread.currentThread();
            try (CallTimeout deadline = new CallTimeout(PROBE_TIMEOUT_MS, false)) {
                deadline.start(thread::interrupt);
                Runnable restore = deadline.enter();
                try {
                    Spider spider = site.spider();
                    spiderClass.set(spider.getClass().getName());
                    if (spider instanceof SpiderNull) {
                        outcome.put("status", "source-not-loaded");
                        recordGuardFailure(site, outcome);
                        return outcome;
                    }
                    pluginInitialized.set(true);
                    outcome.put("pluginInitialized", true);
                    Result result = phase.equals("home") ? SiteApi.homeContent(site)
                            : SiteApi.searchContent(site, "庆余年", false, "1");
                    int items = result.getList().size();
                    int categories = result.getTypes().size();
                    boolean content = items > 0 || phase.equals("home") && categories > 0;
                    outcome.put("itemCount", items).put("categoryCount", categories).put("contentReturned", content);
                    outcome.put("status", content ? "results" : "initialized-without-content");
                } catch (Throwable failure) {
                    outcome.put("status", "failed").put("failureTypes", exceptionTypes(failure));
                } finally {
                    outcome.put("dialogRequired", CallTimeout.currentDeniedDialog());
                    restore.run();
                }
            }
            return outcome;
        });
        try {
            JSONObject outcome = pending.get(PROBE_TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS);
            for (java.util.Iterator<String> keys = outcome.keys(); keys.hasNext();) {
                String key = keys.next();
                stage.put(key, outcome.get(key));
            }
        } catch (Exception failure) {
            pending.cancel(true);
            stage.put("status", "failed").put("failureTypes", exceptionTypes(failure));
        } finally {
            stage.put("pluginInitialized", pluginInitialized.get());
            if (spiderClass.get() != null) stage.put("spiderClass", spiderClass.get());
            stage.put("elapsedMs", SystemClock.elapsedRealtime() - started);
        }
        return stage;
    }

    private void recordGuardFailure(Site site, JSONObject outcome) throws Exception {
        try {
            ClassLoader loader = BaseLoader.get().dex(site.getJar());
            if (loader == null) {
                outcome.put("guardStatus", "jar-loader-unavailable");
                return;
            }
            // Re-expose only the failure type swallowed by the host's compatibility fallback.
            Class.forName("com.github.catvod.spider.DexNative", true, loader);
            outcome.put("guardStatus", "native-guard-class-initialized");
        } catch (Throwable failure) {
            outcome.put("guardStatus", "failed").put("guardFailureTypes", exceptionTypes(failure));
        }
    }

    private void recordRuntime() throws Exception {
        report.put("deviceAbis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
        report.put("devicePrimaryAbi", safeArchitecture(Build.CPU_ABI));
        report.put("process64Bit", Process.is64Bit());
        report.put("processArchitecture", safeArchitecture(System.getProperty("os.arch", "unknown")));
        String packageState = shell("dumpsys package " + PACKAGE);
        report.put("primaryCpuAbi", packageAbi(packageState, "primaryCpuAbi"));
        report.put("secondaryCpuAbi", packageAbi(packageState, "secondaryCpuAbi"));
        String bridge = shell("getprop ro.dalvik.vm.native.bridge").trim();
        report.put("nativeBridge", bridge.matches("[A-Za-z0-9._-]{1,80}") ? bridge : "unknown");
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            // Package metadata is parsed locally; full dumpsys output never enters the report.
            return new String(input.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    private static String packageAbi(String packageState, String field) {
        Matcher value = Pattern.compile("\\b" + field + "=([A-Za-z0-9_-]+)").matcher(packageState);
        return value.find() ? safeArchitecture(value.group(1)) : "unknown";
    }

    private static String safeArchitecture(String value) {
        return value.matches("[A-Za-z0-9_-]{1,40}") ? value : "unknown";
    }

    private static JSONArray exceptionTypes(Throwable failure) {
        Set<String> names = new LinkedHashSet<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.size() < 12 && seen.add(current); current = current.getCause()) {
            names.add(current.getClass().getName());
        }
        return new JSONArray(names);
    }
}
