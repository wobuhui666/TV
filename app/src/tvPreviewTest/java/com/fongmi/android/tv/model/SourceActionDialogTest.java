package com.fongmi.android.tv.model;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.activity.HomePreviewFixtures;
import com.fongmi.android.tv.ui.activity.LegacyActionPreviewBridge;
import com.fongmi.android.tv.ui.activity.TvHomePreviewActivity;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.dialog.SourceActionUi;
import com.fongmi.android.tv.ui.dialog.SpiderDialog;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Init;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Real Home/Vod Activities, Java source callbacks and native dialog windows; all content is offline. */
@RunWith(AndroidJUnit4.class)
public final class SourceActionDialogTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final List<ListenableFuture<?>> calls = new ArrayList<>();
    private HomePreviewFixtures.Session fixture;
    private TvHomePreviewActivity home;
    private VodActivity page;
    private SiteViewModel model;
    private final AtomicInteger results = new AtomicInteger();

    @Before public void open() {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview", instrumentation.getTargetContext().getPackageName());
        Init.setActivity(App::activity);
        main(() -> fixture = HomePreviewFixtures.openCompact(instrumentation.getTargetContext()));
        home = (TvHomePreviewActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), TvHomePreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("home source categories load", () -> home.hasWindowFocus()
                && ((Result) HomePreviewFixtures.field(home, "mResult")).getTypes().size() == 2);
        Result result = (Result) HomePreviewFixtures.field(home, "mResult");
        page = (VodActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), VodActivity.class)
                .putExtra("key", VodConfig.get().getHome().getKey()).putExtra("result", result).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("configuration page resumes", () -> page.hasWindowFocus() && !home.hasWindowFocus());
        main(() -> {
            LegacyActionPreviewBridge.setCachedActivity(home);
            model = new SiteViewModel();
            model.getAction().observe(page, value -> results.incrementAndGet());
        });
    }

    @After public void close() {
        calls.forEach(call -> call.cancel(true));
        if (model != null) main(model::cancelAction);
        if (page != null && !page.isDestroyed()) main(page::finish);
        if (page != null) await("configuration page closes", page::isDestroyed);
        if (home != null && !home.isDestroyed()) main(home::finish);
        if (home != null) await("home closes", home::isDestroyed);
        if (fixture != null) main(fixture::close);
        LegacyActionPreviewBridge.setCachedActivity(null);
        Init.setActivity(App::activity);
        assertNull("Source UI scope is restored after main-thread callbacks", Init.currentActivityOwner());
    }

    @Test public void shouldBindLegacyWindowsToConfigurationPageAndIgnoreRepeatedActions() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        for (int i = 0; i < 3; i++) legacy(actions, shown, 250, "Source configuration");
        await("one configuration dialog opens", () -> shown.get() != null && shown.get().isShowing());
        assertEquals("Repeated clicks cannot queue source actions", 1, actions.get());
        main(() -> {
            assertEquals(page.getWindow().getAttributes().token, shown.get().getWindow().getAttributes().token);
        });
        legacy(actions, shown, 0, "Source configuration");
        assertEquals(1, actions.get());
        NativeUiEvidence.capture("source-config-owned-window");
        main(shown.get()::dismiss);
        await("page focus returns", page::hasWindowFocus);
        legacy(actions, shown, 0, "Source configuration reopened");
        await("the user can reopen after closing", () -> actions.get() == 2 && shown.get().isShowing());
    }

    @Test public void shouldDiscardDelayedLegacyUiAfterReturningHome() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        legacy(actions, shown, 1000, "Late source configuration");
        await("source returns while its UI remains queued", () -> results.get() == 1);
        main(page::finish);
        await("home resumes", home::hasWindowFocus);
        SystemClock.sleep(1300);
        assertTrue("A delayed legacy window is discarded before it is drawn on home", shown.get() == null || !shown.get().isShowing());
        assertFalse(device.hasObject(By.text("Late source configuration")));
        NativeUiEvidence.capture("source-config-home-after-return");
    }

    @Test public void shouldKeepOneLegacyWindowWhenItsConstructorQueriesWindowServiceTwice() {
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        AtomicInteger confirmations = new AtomicInteger();
        main(() -> model.executeAction(page, page, () -> true, () -> {
            SourceActionUi.bind(LegacyActionPreviewBridge.class);
            LegacyActionPreviewBridge.run(() -> {
                shown.set(LegacyActionPreviewBridge.createDoubleLookupDialog(
                        LegacyActionPreviewBridge.getConfigActivity(), "Source repeated window lookup", "Done",
                        (dialog, which) -> confirmations.incrementAndGet()));
                shown.get().show();
            }, 0);
            return Result.empty();
        }));
        await("The dialog survives both captures and becomes visible", () -> shown.get() != null
                && shown.get().isShowing() && device.hasObject(By.text("Source repeated window lookup")));
        main(() -> {
            assertTrue("Repeated capture cannot dismiss the same window", shown.get().isShowing());
            assertEquals(page.getWindow().getAttributes().token, shown.get().getWindow().getAttributes().token);
        });
        NativeUiEvidence.capture("source-config-repeated-window-lookup");
        main(() -> shown.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await("Confirm closes the source window and restores its page", () -> !shown.get().isShowing() && page.hasWindowFocus());
        assertEquals(1, confirmations.get());
    }

    @Test public void shouldCloseVisibleLegacyWindowsWhenTheOwnerPauses() throws Exception {
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        legacy(new AtomicInteger(), shown, 0, "Visible source configuration");
        await("source window is visible", () -> shown.get() != null && shown.get().isShowing());
        main(page::finish);
        await("home regains focus without a source window", () -> home.hasWindowFocus() && !shown.get().isShowing());
        assertFalse(device.hasObject(By.text("Visible source configuration")));
    }

    @Test public void shouldAllowAnUnrelatedLegacyActionToFinishItsCaller() {
        main(() -> model.executeAction(page, page, () -> true, () -> {
            SourceActionUi.bind(LegacyActionPreviewBridge.class);
            LegacyActionPreviewBridge.run(() -> LegacyActionPreviewBridge.finishActivity(page), 0);
            return Result.empty();
        }));
        await("Ordinary source actions retain their normal finish behavior", () -> page.isFinishing() && home.hasWindowFocus());
    }

    @Test public void shouldPreserveSequentialLegacyDialogsFromOneAction() throws Exception {
        AtomicReference<AlertDialog> first = new AtomicReference<>();
        AtomicReference<AlertDialog> second = new AtomicReference<>();
        main(() -> model.executeAction(page, page, () -> true, () -> {
            SourceActionUi.bind(LegacyActionPreviewBridge.class);
            LegacyActionPreviewBridge.execute(() -> LegacyActionPreviewBridge.run(() -> {
                first.set(LegacyActionPreviewBridge.createDialog(LegacyActionPreviewBridge.getConfigActivity(),
                        "First source step", "Next", (dialog, which) ->
                                LegacyActionPreviewBridge.post(() -> {
                                    second.set(LegacyActionPreviewBridge.createDialog(LegacyActionPreviewBridge.getConfigActivity(),
                                            "Second source step", "Done", null));
                                    second.get().show();
                                })));
                first.get().show();
            }, 0));
            return Result.empty();
        }));
        await("first step opens", () -> first.get() != null && first.get().isShowing());
        main(() -> first.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await("second step opens", () -> second.get() != null && second.get().isShowing());
        main(() -> { assertFalse(first.get().isShowing());
            assertEquals(page.getWindow().getAttributes().token, second.get().getWindow().getAttributes().token); });
        NativeUiEvidence.capture("source-config-sequential-window");
    }

    @Test public void shouldAllowAnotherLegacyFormAfterTheConfigurationOwnerCloses() throws Exception {
        AtomicReference<AlertDialog> configured = new AtomicReference<>();
        legacy(new AtomicInteger(), configured, 0, "Source configuration");
        await("configuration opens", () -> configured.get() != null && configured.get().isShowing());
        main(page::finish);
        await("configuration closes before home resumes", () -> home.hasWindowFocus() && !configured.get().isShowing());
        AtomicReference<AlertDialog> login = new AtomicReference<>();
        main(() -> {
            login.set(LegacyActionPreviewBridge.createLoginDialog(home, "Other source login"));
            login.get().show();
        });
        await("a different form from the same source package can open", () -> login.get().isShowing()
                && device.hasObject(By.text("Other source login")));
        main(() -> {
            assertEquals(home.getWindow().getAttributes().token, login.get().getWindow().getAttributes().token);
            login.get().dismiss();
        });
    }

    @Test public void shouldAllowTheSameLegacyFormAfterTheLateCallbackBudgetExpires() throws Exception {
        AtomicReference<AlertDialog> configured = new AtomicReference<>();
        legacy(new AtomicInteger(), configured, 0, "Source configuration");
        await("configuration opens", () -> configured.get() != null && configured.get().isShowing());
        main(page::finish);
        await("configuration closes before home resumes", () -> home.hasWindowFocus() && !configured.get().isShowing());
        SystemClock.sleep(Constant.TIMEOUT_VOD + 500L);
        main(() -> {
            AlertDialog next = LegacyActionPreviewBridge.createDialog(home, "A later source form", "Done", null);
            next.show();
            assertTrue("Retired configuration requests cannot permanently block another call", next.isShowing());
            assertEquals(home.getWindow().getAttributes().token, next.getWindow().getAttributes().token);
            next.dismiss();
        });
    }

    @Test public void shouldKeepPublicDialogOnItsPageAndRejectASecondWindow() throws Exception {
        main(() -> model.executeAction(page, page, () -> true, () -> {
            SpiderDialog.show("{\"title\":\"Public source configuration\"}").get();
            return Result.empty();
        }));
        await("public source dialog opens on the configuration page", () -> device.hasObject(By.text("Public source configuration")));
        CompletableFuture<String>[] duplicate = new CompletableFuture[1];
        main(() -> duplicate[0] = SpiderDialog.show("{\"title\":\"Duplicate source configuration\"}"));
        assertNull("The second request is cancelled without another window", duplicate[0].get(5, TimeUnit.SECONDS));
        assertFalse(device.hasObject(By.text("Duplicate source configuration")));
        NativeUiEvidence.capture("source-config-public-window");
        main(page::finish);
        await("public source window closes on page departure", home::hasWindowFocus);
        assertFalse(device.hasObject(By.text("Public source configuration")));
    }

    @Test public void shouldRetainTheOriginWhenAWorkerOutlivesItsPage() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<Activity> captured = new AtomicReference<>();
        ListenableFuture<?>[] call = new ListenableFuture[1];
        main(() -> call[0] = Task.submit(() -> {
            captured.set(Init.activity());
            started.countDown();
            assertTrue(proceed.await(5, TimeUnit.SECONDS));
            return SpiderDialog.show("{\"title\":\"Retired page configuration\"}").get();
        }, 10000));
        calls.add(call[0]);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertSame("Capture before the worker runs", page, captured.get());
        main(page::finish);
        await("home resumes before delayed UI is requested", home::hasWindowFocus);
        proceed.countDown();
        assertThrows(ExecutionException.class, () -> call[0].get(5, TimeUnit.SECONDS));
        assertFalse(device.hasObject(By.text("Retired page configuration")));
    }

    private void legacy(AtomicInteger actions, AtomicReference<AlertDialog> shown, int delay, String title) {
        main(() -> model.executeAction(page, page, () -> true, () -> {
            actions.incrementAndGet();
            SourceActionUi.bind(LegacyActionPreviewBridge.class);
            assertSame("The legacy source deliberately caches the wrong Activity", home, LegacyActionPreviewBridge.getActivity());
            LegacyActionPreviewBridge.run(() -> {
                shown.set(LegacyActionPreviewBridge.createDialog(LegacyActionPreviewBridge.getConfigActivity(), title, "Done", null));
                shown.get().show();
            }, delay);
            return Result.empty();
        }));
    }

    private void main(Runnable runnable) { instrumentation.runOnMainSync(runnable); instrumentation.waitForIdleSync(); }

    private void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] satisfied = new boolean[1];
            instrumentation.runOnMainSync(() -> satisfied[0] = condition.getAsBoolean());
            if (satisfied[0]) return;
            SystemClock.sleep(40);
        }
        fail(message);
    }
}
