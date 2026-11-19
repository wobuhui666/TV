package com.fongmi.android.tv.ui.activity;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.test.SeekInputActivity;
import com.github.catvod.Init;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.CallTimeout;
import com.github.catvod.utils.Util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Source-card details can open dialogs; automatic preparation must remain noninteractive. */
@RunWith(AndroidJUnit4.class)
public final class LibrarySourceDialogAndroidTest {
    private static final String SITE_KEY = "library-dialog-fixture";
    private static final String TITLE = "Source configuration fixture";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final DetailListener listener = new DetailListener();
    private final FixtureSpider spider = new FixtureSpider();
    private SeekInputActivity activity;
    private LibraryResourcesController resources;
    private Vod source;
    private Object originalSites, originalRecent, jarLoader;
    private Map<String, Spider> spiders;
    private Spider originalSpider;
    private String cacheKey;
    private boolean cacheInjected, sitesInjected, recentCaptured;

    @Before public void setup() throws Exception {
        String target = instrumentation.getTargetContext().getPackageName();
        assertTrue("Only an isolated source probe or preview", target.equals("com.fongmi.android.tv.sourceprobe")
                || target.equals("com.fongmi.android.tv.preview"));
        activity = (SeekInputActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), SeekInputActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        Init.setActivity(() -> activity);
        await("The host is resumed before a source is opened", () -> onMain(() ->
                activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                        && activity.hasWindowFocus()));
        originalSites = field(VodConfig.get(), "sites").get(VodConfig.get());
        jarLoader = field(BaseLoader.get(), "jarLoader").get(BaseLoader.get());
        originalRecent = field(jarLoader, "recent").get(jarLoader);
        recentCaptured = true;
        // Inject only this synthetic source; the production loader and detail path are exercised.
        @SuppressWarnings("unchecked") Map<String, Spider> cache =
                (Map<String, Spider>) field(jarLoader, "spiders").get(jarLoader);
        spiders = cache;
        cacheKey = Util.md5("") + SITE_KEY;
        originalSpider = spiders.put(cacheKey, spider);
        cacheInjected = true;
        Site site = Site.get(SITE_KEY, "Fixture source");
        site.setApi("csp_LibraryDialogFixture");
        field(site, "type").set(site, 3);
        field(VodConfig.get(), "sites").set(VodConfig.get(), List.of(site));
        sitesInjected = true;
        source = new Vod();
        source.setId("settings"); source.setName("Source settings"); source.setSite(site);
        main(() -> resources = new LibraryResourcesController(listener));
    }

    @After public void cleanup() throws Exception {
        if (resources != null) main(resources::close);
        spider.dialog.close();
        if (spider.calls.get() > 0) spider.finished.await(5, TimeUnit.SECONDS);
        if (cacheInjected) {
            if (originalSpider == null) spiders.remove(cacheKey, spider);
            else spiders.put(cacheKey, originalSpider);
        }
        if (recentCaptured) field(jarLoader, "recent").set(jarLoader, originalRecent);
        if (sitesInjected) field(VodConfig.get(), "sites").set(VodConfig.get(), originalSites);
        Init.setActivity(App::activity);
        if (activity != null) main(activity::finish);
        instrumentation.waitForIdleSync();
    }

    @Test public void openingSourceDetailShowsItsConfigurationAndDeliversTheResult() throws Exception {
        main(() -> resources.browse(source));
        text(TITLE);
        capture("explicit-source-detail");
        text("Apply source settings").click();
        assertTrue("Source detail completes after the user confirms", listener.detail.await(8, TimeUnit.SECONDS));
        assertEquals(1, spider.calls.get());
        assertEquals(1, spider.callbacks.get());
        assertEquals("apply", spider.selected.get());
        assertEquals("settings", listener.value.get().getId());
        assertEquals(SITE_KEY, listener.value.get().getSiteKey());
        assertFalse("The returned detail retains its playable flags", listener.value.get().getFlags().isEmpty());
        absent(TITLE);
    }

    @Test public void closingThePageCancelsItsPendingSourceConfiguration() throws Exception {
        main(() -> resources.browse(source));
        text(TITLE);
        main(resources::close);
        assertTrue("The canceled source worker settles", spider.finished.await(8, TimeUnit.SECONDS));
        absent(TITLE);
        instrumentation.waitForIdleSync();
        assertNull("A closed page receives no late detail", listener.value.get());
        assertEquals(0, spider.callbacks.get());
    }

    @Test public void automaticPreparationCannotOpenSourceConfiguration() throws Exception {
        main(() -> {
            resources.metadata(source.getName(), List.of(), "", true, null, null, source);
            resources.seed(List.of(source));
            resources.prepare(null);
        });
        assertTrue("Automatic preparation attempted the real detail path", spider.finished.await(8, TimeUnit.SECONDS));
        await("Automatic preparation ends after dialog denial", () -> onMain(() -> !resources.isPreparing()));
        assertEquals(1, spider.calls.get());
        assertTrue("The request was denied by the background dialog policy", spider.denied);
        absent(TITLE);
        assertNull(listener.value.get());
        assertEquals(0, listener.prepared.get());
        assertEquals(0, listener.selected.get());
    }

    private static final class FixtureSpider extends Spider {
        final AtomicInteger calls = new AtomicInteger(), callbacks = new AtomicInteger();
        final AtomicReference<String> selected = new AtomicReference<>();
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean denied;

        @Override public String detailContent(List<String> ids) throws Exception {
            calls.incrementAndGet();
            try {
                JSONObject result = dialog.show(new JSONObject().put("title", TITLE)
                        .put("buttons", new JSONArray().put(new JSONObject()
                                .put("id", "apply").put("label", "Apply source settings"))), event -> {
                    assertNotSame("Source callbacks stay off the main thread", Looper.getMainLooper(), Looper.myLooper());
                    callbacks.incrementAndGet();
                    return new JSONObject().put("close", true);
                });
                if (result == null) return "{}";
                selected.set(result.getString("button"));
                return new JSONObject().put("list", new JSONArray().put(new JSONObject()
                        .put("vod_id", ids.get(0)).put("vod_name", "Source settings")
                        .put("vod_play_from", "Fixture")
                        .put("vod_play_url", "Movie$https://example.invalid/fixture.mp4"))).toString();
            } catch (Exception error) {
                denied = CallTimeout.currentDeniedDialog();
                throw error;
            } finally {
                finished.countDown();
            }
        }
    }

    private static final class DetailListener implements LibraryResourcesController.Listener {
        final AtomicReference<Vod> value = new AtomicReference<>();
        final CountDownLatch detail = new CountDownLatch(1);
        final AtomicInteger prepared = new AtomicInteger(), selected = new AtomicInteger();
        @Override public void onResources(List<Vod> resources, boolean running, boolean partialError) { }
        @Override public void onSelected(Vod source, Flag flag, Episode episode) { selected.incrementAndGet(); }
        @Override public void onSelectionFailed(Vod source) { }
        @Override public void onPrepared(Vod source, Flag flag, Episode episode) { prepared.incrementAndGet(); }
        @Override public void onSourceDetail(Vod source) { value.set(source); detail.countDown(); }
    }

    private void main(Runnable action) { instrumentation.runOnMainSync(action); }

    private boolean onMain(BooleanSupplier condition) {
        boolean[] result = {false};
        main(() -> result[0] = condition.getAsBoolean());
        return result[0];
    }

    private void await(String description, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 8_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(description);
    }

    private void refresh() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.getUiAutomation().clearCache();
    }

    private UiObject2 text(String value) {
        AtomicReference<UiObject2> found = new AtomicReference<>();
        await("Missing source dialog control: " + value, () -> {
            refresh(); found.set(device.findObject(By.text(value))); return found.get() != null;
        });
        return found.get();
    }

    private void absent(String value) {
        await("Source dialog remained visible: " + value, () -> {
            refresh(); return !device.hasObject(By.text(value));
        });
    }

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        File directory = new File(activity.getExternalFilesDir(null), "library-source-dialog-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
    }

    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
