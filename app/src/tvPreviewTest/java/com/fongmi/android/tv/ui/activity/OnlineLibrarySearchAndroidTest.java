package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.EditText;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.source.LibrarySearchIndex;
import com.fongmi.android.tv.ui.adapter.LibrarySearchAdapter;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Opt-in real configuration and production search. No test HTTP clients or response fixtures. */
@RunWith(AndroidJUnit4.class)
public final class OnlineLibrarySearchAndroidTest {
    private static final String REQUESTED_CONFIG = "https://9280.kstore.vip/aiwex.json";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Activity> opened = new ArrayList<>();
    private final JSONObject report = new JSONObject();
    private File output;

    @Test public void requestedOnlineConfigurationAndSubmittedSearchesProduceMeasuredEvidence() throws Exception {
        assertEquals("Only the isolated preview", "com.fongmi.android.tv.preview", target.getPackageName());
        assertEquals(REQUESTED_CONFIG, InstrumentationRegistry.getArguments().getString("performance_config_url", ""));
        output = target.getExternalFilesDir("online-library-search");
        assertTrue(output != null && (output.isDirectory() || output.mkdirs()));
        report.put("schema", "tv.online-library-search.v1");
        report.put("configUrl", REQUESTED_CONFIG);
        report.put("deviceAbis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
        report.put("testTransportReplacement", false);
        report.put("testResponseFixtures", false);
        report.put("nativeArmHardwareOrPlaybackVerified", false);
        report.put("tmdbKeyConfigured", !BuildConfig.TMDB_API_KEY.isBlank());
        JSONArray queries = new JSONArray();
        report.put("queries", queries);
        ExecutorService configWorker = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> configThread = new AtomicReference<>();
        AtomicReference<String> configStage = new AtomicReference<>("queued");
        AtomicReference<Throwable> configFailure = new AtomicReference<>();
        AtomicBoolean callbackStarted = new AtomicBoolean();
        AtomicBoolean callbackError = new AtomicBoolean();
        Map<String, Long> configTimes = Collections.synchronizedMap(new HashMap<>());
        Future<?> configFuture = null;
        try {
            runMain(() -> { ExperienceSettings.putMode(ExperienceMode.LIBRARY); Prefers.put("incognito", true); return null; }, 10000);
            CountDownLatch loaded = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean();
            long began = SystemClock.uptimeMillis();
            try (Probe probe = new Probe("config-load", report, "configMainResponse")) {
                configFuture = configWorker.submit(() -> {
                    configThread.set(Thread.currentThread());
                    configTimes.put("workerStartedAfterMs", SystemClock.uptimeMillis() - began);
                    try {
                        configStage.set("Config.find");
                        long findBegan = SystemClock.uptimeMillis();
                        Config selected = Config.find(REQUESTED_CONFIG, 0);
                        configTimes.put("configFindElapsedMs", SystemClock.uptimeMillis() - findBegan);
                        configStage.set("VodConfig.load dispatch");
                        long dispatchBegan = SystemClock.uptimeMillis();
                        VodConfig.load(selected, new Callback() {
                            @Override public void start() { callbackStarted.set(true); configTimes.put("callbackStartAfterMs", SystemClock.uptimeMillis() - began); }
                            @Override public void success() { success.set(true); configTimes.put("callbackSuccessAfterMs", SystemClock.uptimeMillis() - began); loaded.countDown(); }
                            @Override public void error(String message) { callbackError.set(true); configTimes.put("callbackErrorAfterMs", SystemClock.uptimeMillis() - began); loaded.countDown(); }
                        });
                        configTimes.put("configDispatchElapsedMs", SystemClock.uptimeMillis() - dispatchBegan);
                        configStage.set("dispatch returned; awaiting callback");
                    } catch (Throwable failure) {
                        configFailure.set(failure);
                        configStage.set(configStage.get() + " failed");
                        configTimes.put("workerFailureAfterMs", SystemClock.uptimeMillis() - began);
                        loaded.countDown();
                        if (failure instanceof Error error) throw error;
                        if (failure instanceof RuntimeException error) throw error;
                        throw new RuntimeException(failure);
                    } finally {
                        configTimes.put("workerFinishedAfterMs", SystemClock.uptimeMillis() - began);
                    }
                });
                boolean signalledAt90Seconds = loaded.await(90, TimeUnit.SECONDS);
                report.put("configObservationAt90Seconds", configObservation(began, configStage, configFuture,
                        configThread, configFailure, configTimes, callbackStarted, callbackError, success));
                report.put("configCompletionSignalAt90Seconds", signalledAt90Seconds);
                if (!signalledAt90Seconds) {
                    // Extend only this diagnostic observation. Production request budgets stay intact.
                    save();
                    long remaining = began + 180_000 - SystemClock.uptimeMillis();
                    if (remaining > 0) loaded.await(remaining, TimeUnit.MILLISECONDS);
                }
                report.put("configDiagnosticWindowMs", 180_000);
                report.put("configFinalObservation", configObservation(began, configStage, configFuture,
                        configThread, configFailure, configTimes, callbackStarted, callbackError, success));
                report.put("configCallbackArrived", success.get() || callbackError.get());
                report.put("configCompletionSignalArrived", loaded.getCount() == 0);
                report.put("configLoadSucceeded", success.get());
                report.put("configLoadElapsedMs", SystemClock.uptimeMillis() - began);
                probe.freeze();
                report.put("configMainResponse", probe.json());
            }
            assertTrue("Real configuration must load before live search", success.get());
            runMain(() -> {
                assertEquals(REQUESTED_CONFIG, VodConfig.getUrl());
                report.put("configuredSites", VodConfig.get().getSites().size());
                report.put("searchableSites", VodConfig.get().getSites().stream().filter(Site::isSearchable).count());
                report.put("tmdbEndpointMode", Setting.getTmdbConnectionMode());
                return null;
            }, 5000);
            for (int order = 0; order < 2; order++) {
                String keyword = order == 0 ? "黑帮领地" : "庆余年";
                JSONObject query = new JSONObject().put("keyword", keyword).put("order", order)
                        .put("applicationWarmth", order == 0 ? "first-search-after-config-load" : "second-search-same-process");
                queries.put(query);
                try { search(keyword, order, query); }
                catch (Exception failure) { query.put("driverFailureType", failure.getClass().getSimpleName()); }
                save();
            }
            report.put("cachedPluginClassesAfterRealSearch", cachedPluginClasses());
        } catch (Throwable failure) {
            report.put("driverFailureType", failure.getClass().getSimpleName());
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        } finally {
            configWorker.shutdownNow();
            try {
                runMain(() -> { for (int i = opened.size() - 1; i >= 0; i--) if (!opened.get(i).isDestroyed()) opened.get(i).finish(); return null; }, 5000);
                report.put("cleanupFinishRequested", true);
            } catch (Exception failure) { report.put("cleanupFailureType", failure.getClass().getSimpleName()); }
            save();
        }
    }

    private void search(String keyword, int order, JSONObject query) throws Exception {
        SearchActivity input;
        long inputBegan = SystemClock.uptimeMillis();
        try (Probe probe = new Probe("input-start", query, "inputMainResponse")) {
            input = launchInput(keyword);
            awaitFocus(input, 30000);
            probe.freeze();
            query.put("inputLaunchElapsedMs", SystemClock.uptimeMillis() - inputBegan).put("inputMainResponse", probe.json());
        }
        LibrarySearchActivity results = null;
        boolean settled = false;
        long submitted = SystemClock.uptimeMillis();
        JSONArray progress = new JSONArray();
        try (Probe probe = new Probe("submit-and-live-results", query, "resultsMainResponse")) {
            runMain(() -> {
                EditText editable = input.findViewById(R.id.keyword);
                editable.getText().replace(0, editable.length(), keyword);
                editable.setSelection(editable.length());
                input.onSearch();
                return null;
            }, 10000);
            results = awaitResults(30000);
            query.put("resultsActivityElapsedMs", SystemClock.uptimeMillis() - submitted);
            final LibrarySearchActivity page = results;
            long deadline = submitted + 45000;
            while (SystemClock.uptimeMillis() < deadline) {
                JSONObject state = runMain(() -> searchState(page), 3000);
                state.put("elapsedMs", SystemClock.uptimeMillis() - submitted);
                progress.put(state);
                if (!state.getBoolean("sourceLoading") && !state.getBoolean("tmdbLoading")
                        && !state.getBoolean("matching") && !state.getBoolean("matchScheduled") && !state.getBoolean("renderPending")) {
                    settled = true; break;
                }
                SystemClock.sleep(750);
            }
            query.put("settledWithin45Seconds", settled);
            query.put("observationElapsedMs", SystemClock.uptimeMillis() - submitted);
            query.put("progress", progress);
            query.put("finalState", runMain(() -> searchState(page), 3000));
            SystemClock.sleep(500);
            probe.freeze();
            query.put("resultsMainResponse", probe.json());
        } finally {
            query.put("progress", progress);
        }
        save();
        capture("online-search-" + order + "-results.png");
        long backBegan = SystemClock.uptimeMillis();
        final LibrarySearchActivity page = results;
        try (Probe probe = new Probe("back-to-input", query, "backMainResponse")) {
            runMain(() -> { page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)); return null; }, 5000);
            boolean returned = awaitReturn(page, input, 15000);
            probe.freeze();
            query.put("backReturnedToOriginalInput", returned).put("backElapsedMs", SystemClock.uptimeMillis() - backBegan)
                    .put("backMainResponse", probe.json());
        }
        runMain(() -> { if (!input.isDestroyed()) input.finish(); if (!page.isDestroyed()) page.finish(); return null; }, 5000);
        SystemClock.sleep(500);
    }

    private SearchActivity launchInput(String keyword) throws Exception {
        List<Activity> before = runMain(this::active, 5000);
        target.startActivity(new Intent(target, SearchActivity.class).putExtra("keyword", keyword).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long deadline = SystemClock.uptimeMillis() + 30000;
        while (SystemClock.uptimeMillis() < deadline) {
            SearchActivity found = runMain(() -> {
                for (Activity activity : active()) if (activity instanceof SearchActivity search && !before.contains(activity)) return search;
                return null;
            }, 3000);
            if (found != null) { opened.add(found); return found; }
            SystemClock.sleep(100);
        }
        throw new MainDeadline();
    }

    private LibrarySearchActivity awaitResults(long timeout) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            LibrarySearchActivity found = runMain(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (activity instanceof LibrarySearchActivity search) return search;
                return null;
            }, 3000);
            if (found != null) { opened.add(found); return found; }
            SystemClock.sleep(100);
        }
        throw new MainDeadline();
    }

    private List<Activity> active() {
        List<Activity> result = new ArrayList<>();
        for (Stage stage : Stage.values()) if (stage != Stage.DESTROYED)
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))
                if (target.getPackageName().equals(activity.getPackageName()) && !result.contains(activity)) result.add(activity);
        return result;
    }

    private void awaitFocus(Activity activity, long timeout) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (runMain(activity::hasWindowFocus, 3000)) return;
            SystemClock.sleep(100);
        }
        throw new MainDeadline();
    }

    private boolean awaitReturn(Activity page, Activity input, long timeout) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (runMain(() -> page.isDestroyed() && input.hasWindowFocus(), 3000)) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private JSONObject searchState(LibrarySearchActivity activity) throws Exception {
        JSONObject state = new JSONObject();
        for (String flag : new String[]{"sourceLoading", "tmdbLoading", "matching", "matchScheduled", "renderPending", "sourceError", "tmdbError"}) state.put(flag, field(activity, flag));
        LibrarySearchIndex.Snapshot snapshot = (LibrarySearchIndex.Snapshot) field(activity, "snapshot");
        state.put("works", snapshot.works().size()).put("attachedResources", snapshot.resourceCount()).put("sourceSupplements", snapshot.supplements().size());
        state.put("sourceCallbacksReturned", field(field(activity, "sourceSearch"), "returned"));
        RecyclerView grid = activity.findViewById(R.id.results);
        state.put("adapterRows", grid.getAdapter().getItemCount()).put("attachedCards", grid.getChildCount());
        state.put("createdCards", ((LibrarySearchAdapter) grid.getAdapter()).getCreatedCards());
        Map<String, Integer> counts = new HashMap<>();
        for (LibrarySearchIndex.Work work : snapshot.works()) for (Vod item : work.resources()) counts.merge(item.getSiteKey(), 1, Integer::sum);
        for (Vod item : snapshot.supplements()) counts.merge(item.getSiteKey(), 1, Integer::sum);
        JSONArray sources = new JSONArray();
        List<Site> sites = VodConfig.get().getSites();
        for (int index = 0; index < sites.size(); index++) if (counts.containsKey(sites.get(index).getKey()))
            sources.put(new JSONObject().put("siteIndex", index).put("resourceItems", counts.get(sites.get(index).getKey())));
        state.put("sourcesWithVisibleResourceItems", sources);
        return state;
    }

    private JSONObject cachedPluginClasses() throws Exception {
        Object loader = field(BaseLoader.get(), "jarLoader");
        Map<?, ?> spiders = (Map<?, ?>) field(loader, "spiders");
        Map<?, ?> dex = (Map<?, ?>) field(loader, "loaders");
        JSONArray classes = new JSONArray();
        List<Site> sites = VodConfig.get().getSites();
        for (int index = 0; index < sites.size(); index++) {
            Site site = sites.get(index);
            Object instance = spiders.get(Util.md5(site.getJar()) + site.getKey());
            if (instance instanceof Spider spider) classes.put(new JSONObject().put("siteIndex", index)
                    .put("loadedClass", spider.getClass().getName()).put("fallbackSpiderNull", spider instanceof SpiderNull));
        }
        return new JSONObject().put("cachedInstances", spiders.size()).put("loadedJarClasses", dex.size()).put("instances", classes);
    }

    private <T> T runMain(Callable<T> action, long timeout) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable call = () -> { try { result.set(action.call()); } catch (Throwable error) { failure.set(error); } finally { ready.countDown(); } };
        main.post(call);
        if (!ready.await(timeout, TimeUnit.MILLISECONDS)) { main.removeCallbacks(call); throw new MainDeadline(); }
        if (failure.get() instanceof Exception error) throw error;
        if (failure.get() instanceof Error error) throw error;
        return result.get();
    }

    private static Object field(Object owner, String name) throws Exception {
        Field member = owner.getClass().getDeclaredField(name); member.setAccessible(true); return member.get(owner);
    }

    /** Error messages can contain transport credentials; retain types and code locations only. */
    private JSONObject configObservation(long began, AtomicReference<String> stage, Future<?> future,
            AtomicReference<Thread> thread, AtomicReference<Throwable> failure, Map<String, Long> times,
            AtomicBoolean started, AtomicBoolean errored, AtomicBoolean succeeded) throws Exception {
        JSONObject value = new JSONObject().put("elapsedMs", SystemClock.uptimeMillis() - began)
                .put("workerStage", stage.get()).put("callbackStartCalled", started.get())
                .put("callbackErrorCalled", errored.get()).put("callbackSuccessCalled", succeeded.get())
                .put("workerFuture", futureState(future)).put("workerFailure", failureState(failure.get()))
                .put("innerTask", configInnerTaskState()).put("relatedThreadStacks", configThreadStates());
        synchronized (times) { value.put("stageTimingsMs", new JSONObject(new HashMap<>(times))); }
        Thread worker = thread.get();
        if (worker != null) value.put("workerThread", threadState(worker));
        return value;
    }

    private JSONObject failureState(Throwable failure) throws Exception {
        JSONObject value = new JSONObject();
        if (failure == null) return value;
        JSONArray causes = new JSONArray();
        for (int count = 0; failure != null && count < 4; count++, failure = failure.getCause()) {
            JSONObject cause = new JSONObject().put("type", failure.getClass().getName());
            JSONArray stack = new JSONArray();
            StackTraceElement[] frames = failure.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 20); i++) stack.put(frames[i].toString());
            causes.put(cause.put("stack", stack));
        }
        return value.put("causes", causes);
    }

    private JSONObject futureState(Future<?> future) throws Exception {
        JSONObject state = new JSONObject().put("present", future != null);
        if (future == null) return state;
        state.put("done", future.isDone()).put("cancelled", future.isCancelled());
        if (future.isDone() && !future.isCancelled()) {
            try { future.get(0, TimeUnit.MILLISECONDS); state.put("completedNormally", true); }
            catch (ExecutionException failure) { state.put("completedNormally", false).put("failure", failureState(failure.getCause())); }
            catch (Exception failure) { state.put("inspectionFailure", failureState(failure)); }
        }
        return state;
    }

    private JSONObject configInnerTaskState() throws Exception {
        JSONObject value = new JSONObject();
        try {
            Field futureField = VodConfig.class.getSuperclass().getDeclaredField("future");
            futureField.setAccessible(true);
            Object future = futureField.get(VodConfig.get());
            value.put("future", futureState(future instanceof Future<?> task ? task : null));
        } catch (Throwable failure) { value.put("inspectionFailure", failureState(failure)); }
        return value;
    }

    private JSONObject threadState(Thread thread) throws Exception {
        JSONArray frames = new JSONArray();
        StackTraceElement[] stack = thread.getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 24); i++) frames.put(stack[i].toString());
        return new JSONObject().put("name", thread.getName()).put("state", thread.getState().name()).put("stack", frames);
    }

    private JSONArray configThreadStates() throws Exception {
        JSONArray threads = new JSONArray();
        for (Map.Entry<Thread, StackTraceElement[]> item : Thread.getAllStackTraces().entrySet()) {
            boolean related = false;
            for (StackTraceElement frame : item.getValue()) {
                if (frame.getClassName().startsWith("com.fongmi.android.tv.api.config.")
                        || frame.getClassName().equals("com.fongmi.android.tv.bean.Config")) { related = true; break; }
            }
            if (related) { threads.put(threadState(item.getKey())); if (threads.length() == 8) break; }
        }
        return threads;
    }
    private void save() throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(output, "report.json"))) { stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
    }
    private void capture(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertTrue("Capture the real app outside measurement", bitmap != null);
        try (FileOutputStream stream = new FileOutputStream(new File(output, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        finally { bitmap.recycle(); }
    }
    private static final class MainDeadline extends Exception { }

    private final class Probe implements AutoCloseable {
        private final String phase;
        private final JSONObject owner;
        private final String fieldName;
        private final Object token = new Object();
        private final ConcurrentLinkedQueue<Long> pending = new ConcurrentLinkedQueue<>();
        private final List<Long> delays = Collections.synchronizedList(new ArrayList<>());
        private final List<String> stacks = Collections.synchronizedList(new ArrayList<>());
        private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        private final AtomicBoolean measuring = new AtomicBoolean(true);
        private final long started = SystemClock.uptimeMillis();
        private long maximum, elapsed;
        Probe(String phase, JSONObject owner, String fieldName) {
            this.phase = phase; this.owner = owner; this.fieldName = fieldName;
            watcher.scheduleAtFixedRate(() -> {
                if (!measuring.get()) return;
                long now = SystemClock.uptimeMillis(); Long oldest = pending.peek();
                if (oldest != null && now - oldest > 250 && stacks.size() < 8) {
                    StringBuilder stack = new StringBuilder(); for (StackTraceElement line : Looper.getMainLooper().getThread().getStackTrace()) stack.append(line).append('\n'); stacks.add(stack.toString());
                }
                pending.add(now);
                main.postAtTime(() -> { if (measuring.get()) delays.add(SystemClock.uptimeMillis() - now); pending.remove(now); }, token, now);
            }, 0, 50, TimeUnit.MILLISECONDS);
        }
        void freeze() {
            if (!measuring.getAndSet(false)) return;
            Long oldest = pending.peek();
            synchronized (delays) { maximum = delays.stream().mapToLong(Long::longValue).max().orElse(0); }
            if (oldest != null) maximum = Math.max(maximum, SystemClock.uptimeMillis() - oldest);
            elapsed = SystemClock.uptimeMillis() - started;
            watcher.shutdownNow(); main.removeCallbacksAndMessages(token);
        }
        JSONObject json() throws Exception {
            freeze();
            return new JSONObject().put("phase", phase).put("elapsedMs", elapsed).put("maxMainHandlerDelayMs", maximum)
                    .put("handlerSamples", delays.size()).put("reference250MsExceeded", maximum > 250)
                    .put("mainStacksOver250Ms", new JSONArray(stacks));
        }
        @Override public void close() throws Exception { freeze(); owner.put(fieldName, json()); }
    }
}
