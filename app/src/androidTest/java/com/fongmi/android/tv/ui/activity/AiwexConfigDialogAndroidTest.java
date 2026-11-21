package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.test.SourceDialogTestActivity;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Init;
import com.github.catvod.utils.CallTimeout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Opt-in real configuration probe; never submits account fields or exports source identifiers. */
@RunWith(AndroidJUnit4.class)
public final class AiwexConfigDialogAndroidTest {
    private static final String PACKAGE = "com.fongmi.android.tv.sourceprobe.wexdialog";
    private static final String CONFIG_URL = "https://9280.kstore.vip/aiwex.json";
    private static final Set<String> CONFIGURATION_ENTRIES = Set.of("网页版配置中心", "网盘线路排序");
    private static final Set<String> SAFE_CONTROLS = Set.of("确定", "确认", "取消", "返回", "关闭", "保存", "设置", "配置",
            "网页配置", "网页版配置中心", "配置中心", "综合设置", "OK", "Cancel", "Close", "Back");
    private static final Set<String> SAFE_ITEMS = Set.of("网页版配置中心", "配置中心", "网页配置中心", "网盘线路排序",
            "多线程代理模式", "Go多线程管理", "综合设置", "夸克网盘", "阿里网盘", "UC网盘", "百度网盘",
            "确定", "确认", "取消", "返回", "关闭", "保存", "设置", "配置", "设置成功后刷新配置中心");
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final JSONObject report = new JSONObject();
    private final JSONArray stages = new JSONArray();
    private SourceDialogTestActivity activity;
    private VodActivity categoryActivity;
    private SiteViewModel model;
    private Future<?> pending;
    private Set<View> baseline;

    @Test(timeout = 240_000)
    public void explicitRealConfigurationCardShouldShowItsDialog() throws Exception {
        assertEquals("Only the dedicated configuration-dialog package may run this live test", PACKAGE,
                instrumentation.getTargetContext().getPackageName());
        assertEquals("The real configuration requires an explicit test argument", CONFIG_URL,
                InstrumentationRegistry.getArguments().getString("source_config_url", ""));
        String entry = InstrumentationRegistry.getArguments().getString("source_configuration_entry", "网页版配置中心");
        assertTrue("Only the two approved configuration entries may be selected", CONFIGURATION_ENTRIES.contains(entry));
        assertTrue("WindowInspector requires Android 10", Build.VERSION.SDK_INT >= 29);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("aiwex-config-dialog");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        ExecutorService loader = Executors.newSingleThreadExecutor();
        report.put("schema", "tv.aiwex-config-dialog.v1").put("sdk", Build.VERSION.SDK_INT)
                .put("stages", stages).put("configSuccess", false).put("dialogShown", false)
                .put("formValuesSubmitted", false).put("passed", false).put("selectedConfigurationEntry", entry);
        try {
            Init.setActivity(App::activity);
            activity = (SourceDialogTestActivity) instrumentation.startActivitySync(new Intent(
                    instrumentation.getTargetContext(), SourceDialogTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            awaitResumed(activity);
            Server.get().start();
            report.put("localServerRunning", Server.get().isRunning());
            baseline = roots();
            report.put("baselineWindowCount", baseline.size());

            CountDownLatch loaded = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean();
            long started = SystemClock.elapsedRealtime();
            Future<?> config = loader.submit(() -> VodConfig.load(Config.find(CONFIG_URL, 0), new Callback() {
                @Override public void success() { success.set(true); loaded.countDown(); }
                @Override public void error(String ignored) { loaded.countDown(); }
            }));
            boolean completed = loaded.await(90, TimeUnit.SECONDS);
            report.put("configLoadMs", SystemClock.elapsedRealtime() - started)
                    .put("configCallbackCompleted", completed).put("configSuccess", success.get());
            if (config.isDone()) config.get();
            assertTrue("Real configuration callback timed out", completed);
            assertTrue("Real configuration failed to load", success.get());
            Site site = VodConfig.get().getSites().stream().filter(value -> value.getKey().equals("Wexconfig"))
                    .findFirst().orElse(null);
            assertNotNull("The real configuration source is missing", site);
            report.put("siteCount", VodConfig.get().getSites().size());

            AtomicReference<Result> result = new AtomicReference<>();
            boolean shown = call("home", () -> SiteApi.homeContent(site), result);
            Result home = result.get();
            if (!shown && home != null && home.getList().isEmpty() && !home.getTypes().isEmpty()) {
                String category = home.getTypes().get(0).getTypeId();
                shown = call("first-category", () -> SiteApi.categoryContent(site.getKey(), category, "1", false,
                        new HashMap<>()), result);
            }
            if (!shown && result.get() != null) {
                Vod card = configurationCard(result.get().getList(), entry);
                report.put("configurationCardFound", card != null);
                if (card != null) {
                    card.setSite(site);
                    openCategories(site, home);
                    if (card.isAction()) shown = call("configuration-action",
                            () -> SiteApi.action(site.getKey(), card.getAction()), new AtomicReference<>());
                    else shown = browse(card);
                }
            }
            report.put("dialogShown", shown);
            if (shown) {
                boolean dismissed = dismissWithBack();
                report.put("dismissedWithBack", dismissed);
                assertTrue("The source configuration dialog must close with Back", dismissed);
            }
            assertTrue("The real configuration card must be reached", report.optBoolean("configurationCardFound"));
            assertTrue("No real configuration dialog appeared; inspect the safe report", shown);
            report.put("passed", true);
        } catch (Throwable failure) {
            report.put("failureTypes", failureTypes(failure));
            throw new AssertionError("Real configuration dialog probe failed; inspect report.json");
        } finally {
            if (pending != null) pending.cancel(true);
            if (model != null) main(model::cancelAction);
            loader.shutdownNow();
            BaseLoader.get().clear();
            Server.get().stop();
            Init.setActivity(App::activity);
            if (categoryActivity != null) main(() -> {
                if (!categoryActivity.isFinishing() && !categoryActivity.isDestroyed()) categoryActivity.finish();
            });
            if (activity != null) main(activity::finish);
            try (FileOutputStream output = new FileOutputStream(new File(directory, "report.json"))) {
                output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void openCategories(Site site, Result home) throws Exception {
        assertNotNull("The real source homepage must be available to open its categories", home);
        assertFalse("The real source must supply categories", home.getTypes().isEmpty());
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(VodActivity.class.getName(), null, false);
        try {
            main(() -> VodActivity.start(activity, site.getKey(), home));
            Activity started = instrumentation.waitForMonitorWithTimeout(monitor, 8_000);
            assertTrue("The real category activity did not open", started instanceof VodActivity);
            categoryActivity = (VodActivity) started;
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        awaitResumed(categoryActivity);
        baseline = roots();
        report.put("categoryHostResumed", true).put("categoryBaselineWindowCount", baseline.size())
                .put("activitySupplier", "App.activity");
    }

    private boolean call(String phase, Callable<Result> action, AtomicReference<Result> result) throws Exception {
        JSONObject stage = new JSONObject().put("phase", phase).put("dialogsAllowed", true);
        stages.put(stage);
        result.set(null);
        AtomicBoolean denied = new AtomicBoolean(), used = new AtomicBoolean();
        pending = Task.submit(() -> {
            try { Result value = action.call(); result.set(value); return value; }
            finally { denied.set(CallTimeout.currentDeniedDialog()); used.set(CallTimeout.currentHasDialog()); }
        }, 30_000, true);
        boolean shown = observe(stage, () -> pending.isDone(), 40_000);
        if (!shown) {
            if (!pending.isDone()) pending.cancel(true);
            try { pending.get(2, TimeUnit.SECONDS); }
            catch (Throwable failure) { stage.put("failureTypes", failureTypes(failure)); }
        }
        Result value = result.get();
        if (value != null) describe(stage, value);
        stage.put("sharedDialogStateObserved", pending.isDone());
        if (pending.isDone()) stage.put("sharedDialogDenied", denied.get()).put("sharedDialogUsed", used.get());
        return shown;
    }

    private boolean browse(Vod card) throws Exception {
        JSONObject stage = new JSONObject().put("phase", "configuration-card-detail");
        stages.put(stage);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<Vod> returned = new AtomicReference<>();
        main(() -> {
            model = new SiteViewModel();
            model.getAction().observe(categoryActivity, value -> {
                if (value != null) {
                    returned.set(value.getVod());
                    finished.set(true);
                }
            });
            model.openListItem(categoryActivity, categoryActivity, card.getSiteKey(), card.getId(),
                    () -> !categoryActivity.isFinishing() && !categoryActivity.isDestroyed(), value -> {
                        returned.set(value.getVod());
                        finished.set(true);
                    });
        });
        boolean shown = observe(stage, () -> finished.get() || categoryActivity.isFinishing(), 35_000);
        main(() -> {
            Result value = model.getAction().getValue();
            if (returned.get() == null && value != null) returned.set(value.getVod());
        });
        stage.put("detailReturned", returned.get() != null);
        main(() -> {
            try {
                stage.put("categoryActivityFinishing", categoryActivity != null && categoryActivity.isFinishing())
                        .put("anchorActivityFinishing", activity.isFinishing())
                        .put("currentActivityIsAnchor", App.activity() == activity)
                        .put("currentActivityIsCategory", App.activity() == categoryActivity);
            } catch (Exception ignored) { }
        });
        if (returned.get() != null) {
            String name = returned.get().getName();
            stage.put("detailName", SAFE_ITEMS.contains(name.trim()) ? name : "[omitted]")
                    .put("detailFlagCount", returned.get().getFlags().size());
        }
        return shown;
    }

    private boolean observe(JSONObject stage, java.util.function.BooleanSupplier done, long timeout) throws Exception {
        long started = SystemClock.elapsedRealtime(), completedAt = 0, visibleAt = 0;
        Set<View> visible = Collections.newSetFromMap(new IdentityHashMap<>());
        do {
            Set<View> dialogs = dialogRoots();
            Set<View> focused = Collections.newSetFromMap(new IdentityHashMap<>());
            main(() -> dialogs.forEach(root -> { if (root.hasWindowFocus()) focused.add(root); }));
            visible.retainAll(focused);
            long now = SystemClock.elapsedRealtime();
            if (focused.isEmpty()) visibleAt = 0;
            else if (visible.isEmpty()) {
                visible.addAll(focused);
                visibleAt = now;
            }
            if (!visible.isEmpty() && now - visibleAt >= 500) {
                stage.put("dialogShown", true).put("additionalWindowCount", dialogs.size())
                        .put("windowFocused", true).put("visibleForMs", now - visibleAt);
                JSONArray labels = new JSONArray();
                main(() -> visible.forEach(root -> safeControls(root, labels)));
                stage.put("safeDialogControls", labels).put("elapsedMs", SystemClock.elapsedRealtime() - started);
                return true;
            }
            if (done.getAsBoolean()) {
                if (completedAt == 0) completedAt = SystemClock.elapsedRealtime();
                if (SystemClock.elapsedRealtime() - completedAt >= 4_000) break;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() - started < timeout);
        stage.put("dialogShown", false).put("requestCompleted", done.getAsBoolean())
                .put("elapsedMs", SystemClock.elapsedRealtime() - started);
        return false;
    }

    private boolean dismissWithBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        long deadline = SystemClock.elapsedRealtime() + 3_000;
        do {
            if (dialogRoots().isEmpty()) return true;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private Set<View> roots() {
        Set<View> result = Collections.newSetFromMap(new IdentityHashMap<>());
        main(() -> result.addAll(WindowInspector.getGlobalWindowViews()));
        return result;
    }

    private Set<View> dialogRoots() {
        Set<View> result = Collections.newSetFromMap(new IdentityHashMap<>());
        main(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) {
                if (baseline.contains(root) || !root.isAttachedToWindow() || !root.isShown()) continue;
                if (root.getLayoutParams() instanceof WindowManager.LayoutParams parameters
                        && parameters.type > WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                        && parameters.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) result.add(root);
            }
        });
        return result;
    }

    private static void safeControls(View view, JSONArray labels) {
        if (view instanceof EditText || !view.isShown()) return;
        if (view instanceof TextView text) {
            String label = text.getText().toString().trim();
            if (SAFE_CONTROLS.contains(label) && (view instanceof Button || titleView(view))) labels.put(label);
        }
        if (view instanceof ViewGroup group)
            for (int index = 0; index < group.getChildCount(); index++) safeControls(group.getChildAt(index), labels);
    }

    private static boolean titleView(View view) {
        try { return view.getResources().getResourceEntryName(view.getId()).equals("alertTitle"); }
        catch (RuntimeException ignored) { return false; }
    }

    private static void describe(JSONObject stage, Result result) throws Exception {
        JSONArray items = new JSONArray();
        for (Vod value : result.getList()) {
            String name = value.getName();
            boolean safe = SAFE_ITEMS.contains(name.trim());
            items.put(new JSONObject().put("name", safe ? name : "[omitted]")
                    .put("isAction", value.isAction()).put("isFolder", value.isFolder()));
        }
        stage.put("listCount", result.getList().size()).put("typeCount", result.getTypes().size()).put("items", items);
    }

    private static Vod configurationCard(List<Vod> items, String entry) {
        for (Vod value : items) if (value.getName().equals(entry)) return value;
        return null;
    }

    private static JSONArray failureTypes(Throwable failure) {
        JSONArray result = new JSONArray();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause())
            result.put(current.getClass().getSimpleName());
        return result;
    }

    private void awaitResumed(FragmentActivity host) {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            main(() -> ready.set(host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                    && host.hasWindowFocus() && App.activity() == host));
            if (ready.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The configuration dialog host did not resume");
    }

    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
}
