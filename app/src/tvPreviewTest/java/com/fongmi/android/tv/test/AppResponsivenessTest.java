package com.fongmi.android.tv.test;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.Window;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.ViewModelProvider;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Explicit live-config probe. Counts/timings only; never exports configuration or source bodies. */
@RunWith(AndroidJUnit4.class)
public final class AppResponsivenessTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final JSONObject report = new JSONObject();
    private final JSONArray stages = new JSONArray();
    private final List<Long> frames = Collections.synchronizedList(new ArrayList<>());
    private final HandlerThread metricsThread = new HandlerThread("responsiveness-metrics");
    private final List<Activity> activities = new ArrayList<>();
    private final List<Activity> observed = new ArrayList<>();
    private Activity current;

    @Test(timeout = 360_000)
    public void realConfigurationAndRepeatedNavigationProduceMeasuredEvidence() throws Exception {
        assertEquals("Use the disposable emulator preview", "com.fongmi.android.tv.preview", target.getPackageName());
        String url = InstrumentationRegistry.getArguments().getString("performance_config_url", "");
        assertEquals("Explicitly select the requested live configuration", "https://9280.kstore.vip/aiwex.json", url);
        File output = target.getExternalFilesDir("responsiveness-verification");
        assertNotNull(output);
        assertTrue(output.isDirectory() || output.mkdirs());
        metricsThread.start();
        // A plugin ignoring interruption must not prevent the next bounded source probe
        // from starting. There are at most four source probes in this disposable process.
        ExecutorService worker = Executors.newFixedThreadPool(4);
        Window.OnFrameMetricsAvailableListener listener = (window, metrics, dropped) -> {
            long duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            if (duration > 0 && frames.size() < 20_000) frames.add(duration);
        };
        try {
            report.put("schema", "tv.responsiveness.v1");
            report.put("nativeArmPlaybackVerified", false);
            report.put("stages", stages);
            CountDownLatch loaded = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean();
            long started = SystemClock.elapsedRealtime();
            worker.submit(() -> VodConfig.load(Config.find(url, 0), new Callback() {
                @Override public void success() { success.set(true); loaded.countDown(); }
                @Override public void error(String message) { loaded.countDown(); }
            }));
            assertTrue("Live configuration timed out", loaded.await(90, TimeUnit.SECONDS));
            assertTrue("Live configuration did not load", success.get());
            report.put("configLoadMs", SystemClock.elapsedRealtime() - started);
            report.put("siteCount", VodConfig.get().getSites().size());
            assertTrue("The real configuration must contain sites", VodConfig.get().getSites().size() > 0);

            // Real plugin execution is reported separately from successful config parsing and UI work.
            boolean homeContent = probeSource(worker, "home", VodConfig.get().getHome(), "");
            int attempted = 0;
            boolean searchContent = false;
            for (Site site : VodConfig.get().getSites()) {
                if (!site.isSearchable()) continue;
                if (attempted++ >= 3) break;
                if (probeSource(worker, "search", site, "庆余年")) { searchContent = true; break; }
            }
            report.put("sourceHomeContentVerified", homeContent);
            report.put("sourceSearchContentVerified", searchContent);
            report.put("sourceSearchProbeCount", Math.min(attempted, 3));
            report.put("sourceAvailability", homeContent || searchContent ? "content-returned" : "no-content-from-probed-sources");
            report.put("homeExperience", BrowseExperienceSettings.isPosterHomeEnabled() ? "poster" : "source");
            // Keep an unrelated automatic update dialog from taking navigation focus.
            Prefers.put("ota_last_check", System.currentTimeMillis());

            ConfigReload reload = new ConfigReload();
            instrumentation.runOnMainSync(() -> EventBus.getDefault().register(reload));
            HomeActivity home;
            long homeStarted = SystemClock.elapsedRealtime();
            try {
                home = (HomeActivity) launch(HomeActivity.class);
                observe(home, listener);
                assertTrue("Home configuration reload timed out", reload.complete.await(45, TimeUnit.SECONDS));
                assertEquals("Home must retain the requested real configuration", url, VodConfig.getUrl());
                assertTrue("Home must retain real configured sites", !VodConfig.get().getSites().isEmpty());
                report.put("homeConfigReloadMs", SystemClock.elapsedRealtime() - homeStarted);
            } finally {
                instrumentation.runOnMainSync(() -> EventBus.getDefault().unregister(reload));
            }
            awaitWindow(home);
            // ConfigEvent starts another real home-content request. Wait for its bounded
            // result delivery before sending remote events through restored focus.
            boolean homeResultDelivered = await(() -> new ViewModelProvider(home).get(SiteViewModel.class)
                    .getResult().getValue() != null, 35_000);
            report.put("homeResultDelivered", homeResultDelivered);
            sample("home-start");
            for (int i = 0; i < 24; i++) {
                key(i % 2 == 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
            }
            capture(output, "home.png");
            for (int round = 0; round < 3; round++) {
                SearchActivity search = (SearchActivity) launch(SearchActivity.class);
                observe(search, listener);
                awaitWindow(search);
                for (int i = 0; i < 16; i++) key(i % 2 == 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                sample("search-" + round);
                if (round == 0) capture(output, "search.png");
                assertTrue("Search activity did not finish", close(search, listener));
                current = home;
                awaitWindow(home);
            }

            // Exercise the ordinary multi-source results workspace as well as editable search.
            CollectActivity results = (CollectActivity) launch(CollectActivity.class, "庆余年");
            observe(results, listener);
            awaitWindow(results);
            boolean visibleResults = await(() -> (int) field(field(results, "results"), "resultCount") > 0, 32_000);
            report.put("ordinarySearchHasResults", visibleResults);
            instrumentation.runOnMainSync(() -> {
                try {
                    report.put("ordinarySearchResultCount", (int) field(field(results, "results"), "resultCount"));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            for (int i = 0; i < 16; i++) key(i % 2 == 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
            sample("ordinary-search-results");
            capture(output, "search-results.png");
            assertTrue("Search results activity did not finish", close(results, listener));
            current = home;
            awaitWindow(home);
            sample("home-return");
            assertFalse("Home must remain alive after search round trips", home.isFinishing() || home.isDestroyed());
            report.put("passed", true);
        } catch (Throwable failure) {
            report.put("passed", false);
            report.put("failureType", failure.getClass().getSimpleName());
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        } finally {
            worker.shutdownNow();
            for (Activity activity : new ArrayList<>(activities)) close(activity, listener);
            report.put("activityCleanupComplete", activities.isEmpty());
            if (!activities.isEmpty()) report.put("passed", false);
            metricsThread.quitSafely();
            metricsThread.join(2000);
            List<Long> durations;
            synchronized (frames) { durations = new ArrayList<>(frames); }
            Collections.sort(durations);
            report.put("frameCount", durations.size());
            if (!durations.isEmpty()) {
                report.put("frameP50Ms", percentile(durations, 0.50));
                report.put("frameP95Ms", percentile(durations, 0.95));
                report.put("framesOver32Ms", durations.stream().filter(value -> value > 32_000_000L).count());
            }
            try (FileOutputStream stream = new FileOutputStream(new File(output, "report.json"))) {
                stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private boolean probeSource(ExecutorService worker, String phase, Site site, String keyword) throws Exception {
        JSONObject stage = new JSONObject().put("phase", phase).put("siteIndex", VodConfig.get().getSites().indexOf(site));
        stages.put(stage);
        long started = SystemClock.elapsedRealtime();
        Future<Result> pending = worker.submit(() -> phase.equals("home") ? SiteApi.homeContent(site)
                : SiteApi.searchContent(site, keyword, false, "1"));
        try {
            Result result = pending.get(25, TimeUnit.SECONDS);
            int count = result.getList().size();
            stage.put("itemCount", count).put("categoryCount", result.getTypes().size());
            boolean content = count > 0 || phase.equals("home") && !result.getTypes().isEmpty();
            stage.put("status", content ? "results" : "empty");
            return content;
        } catch (Exception failure) {
            pending.cancel(true);
            stage.put("status", "failed").put("failureType", failure.getClass().getSimpleName());
            return false;
        } finally {
            stage.put("elapsedMs", SystemClock.elapsedRealtime() - started);
        }
    }

    private Activity launch(java.lang.Class<? extends Activity> type) {
        return launch(type, null);
    }

    private Activity launch(java.lang.Class<? extends Activity> type, String keyword) {
        Intent intent = new Intent(target, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (keyword != null) intent.putExtra("keyword", keyword);
        current = instrumentation.startActivitySync(intent);
        activities.add(current);
        return current;
    }

    private boolean close(Activity activity, Window.OnFrameMetricsAvailableListener listener) {
        instrumentation.runOnMainSync(() -> {
            if (observed.remove(activity)) activity.getWindow().removeOnFrameMetricsAvailableListener(listener);
            if (!activity.isDestroyed()) activity.finish();
        });
        boolean destroyed = await(activity::isDestroyed, 10_000);
        if (destroyed) activities.remove(activity);
        return destroyed;
    }

    private boolean await(BooleanSupplier condition, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        AtomicBoolean complete = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> complete.set(condition.getAsBoolean()));
            if (complete.get()) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    public static final class ConfigReload {
        final CountDownLatch complete = new CountDownLatch(1);

        @Subscribe(threadMode = ThreadMode.MAIN)
        public void onConfigEvent(ConfigEvent event) {
            if (event.isVod()) complete.countDown();
        }
    }

    private void observe(Activity activity, Window.OnFrameMetricsAvailableListener listener) {
        instrumentation.runOnMainSync(() -> activity.getWindow().addOnFrameMetricsAvailableListener(listener, new Handler(metricsThread.getLooper())));
        observed.add(activity);
    }

    private void awaitWindow(Activity activity) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        AtomicBoolean focused = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> focused.set(activity.hasWindowFocus()));
            if (focused.get()) return;
            SystemClock.sleep(100);
        }
        assertTrue("Activity window did not regain focus", focused.get());
    }

    private void key(int code) {
        instrumentation.sendKeyDownUpSync(code);
        SystemClock.sleep(100);
    }

    private void sample(String name) throws Exception {
        Debug.MemoryInfo memory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memory);
        stages.put(new JSONObject().put("phase", name).put("totalPssKb", memory.getTotalPss())
                .put("javaHeapKb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024));
    }

    private void capture(File output, String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream stream = new FileOutputStream(new File(output, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
    }

    private double percentile(List<Long> values, double fraction) {
        return values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * fraction) - 1)) / 1_000_000.0;
    }
}
