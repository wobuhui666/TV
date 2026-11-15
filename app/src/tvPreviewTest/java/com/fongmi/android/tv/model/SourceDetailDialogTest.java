package com.fongmi.android.tv.model;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Parcel;
import android.os.SystemClock;

import androidx.fragment.app.Fragment;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.activity.HomePreviewFixtures;
import com.fongmi.android.tv.ui.activity.TvHomePreviewActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.fragment.TypeFragment;
import com.github.catvod.Init;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderRuntime;
import com.github.catvod.spider.LegacyDetailPreviewSpider;
import com.github.catvod.utils.Util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Exercises actual list-card clicks through TypeFragment, SiteApi and the legacy Java source. */
@RunWith(AndroidJUnit4.class)
public final class SourceDetailDialogTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final LegacyDetailPreviewSpider spider = new LegacyDetailPreviewSpider();
    private final AtomicReference<Intent> playbackIntent = new AtomicReference<>();
    private HomePreviewFixtures.Session fixture;
    private TvHomePreviewActivity home;
    private VodActivity page;
    private TypeFragment type;
    private Site site;
    private Map<String, Spider> spiders;
    private String cacheKey;
    private Instrumentation.ActivityMonitor playbackMonitor;
    private VideoViewModel videoModel;

    @SuppressWarnings("unchecked")
    @Before public void open() {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        Init.setActivity(App::activity);
        main(() -> fixture = HomePreviewFixtures.openCompact(instrumentation.getTargetContext()));
        home = (TvHomePreviewActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), TvHomePreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("home source categories load", () -> home.hasWindowFocus()
                && ((Result) HomePreviewFixtures.field(home, "mResult")).getTypes().size() == 2);
        Result categories = (Result) HomePreviewFixtures.field(home, "mResult");
        main(() -> {
            site = VodConfig.get().getHome();
            setField(site, "type", 3);
            setField(site, "style", Style.list());
            site.setApi("csp_LegacyDetailPreviewSpider");
            Object loader = HomePreviewFixtures.field(BaseLoader.get(), "jarLoader");
            spiders = (Map<String, Spider>) HomePreviewFixtures.field(loader, "spiders");
            cacheKey = Util.md5(site.getJar()) + site.getKey();
            assertNull(spiders.put(cacheKey, spider));
            spider.cachedActivity = home;
        });
        playbackMonitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() == null || !VideoActivity.class.getName().equals(intent.getComponent().getClassName())) return null;
                playbackIntent.set(new Intent(intent));
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(playbackMonitor);
        page = (VodActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), VodActivity.class).putExtra("key", site.getKey())
                .putExtra("result", categories).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("legacy list loads", () -> page.hasWindowFocus() && device.hasObject(By.text("Legacy proxy settings")));
        main(() -> type = findType(page.getSupportFragmentManager().getFragments()));
        assertNotNull(type);
    }

    @After public void close() {
        main(() -> {
            spider.handler.removeCallbacksAndMessages(null);
            if (spider.shown != null && spider.shown.isShowing()) spider.shown.dismiss();
            if (videoModel != null) videoModel.init();
            if (page != null && !page.isDestroyed()) page.finish();
        });
        if (page != null) await("library closes", page::isDestroyed);
        if (home != null && !home.isDestroyed()) main(home::finish);
        if (home != null) await("home closes", home::isDestroyed);
        if (spiders != null) spiders.remove(cacheKey, spider);
        SpiderRuntime.close(spider);
        spider.cachedActivity = null;
        if (playbackMonitor != null) instrumentation.removeMonitor(playbackMonitor);
        if (fixture != null) main(fixture::close);
        Init.setActivity(App::activity);
        assertNull(Init.currentActivityOwner());
    }

    @Test public void shouldOpenDetailConfigurationOnTheLibraryAndSuppressRepeatedClicks() {
        // A touch focuses the row; the TV center key then activates its normal click listener.
        device.findObject(By.text("Legacy proxy settings")).click();
        device.pressDPadCenter();
        await("legacy detail opens its native window", () -> spider.shown != null && spider.shown.isShowing()
                && device.hasObject(By.text("Legacy proxy engine")));
        main(() -> {
            assertEquals(page.getWindow().getAttributes().token, spider.shown.getWindow().getAttributes().token);
            for (int i = 0; i < 3; i++) type.onItemClick(settings());
        });
        assertEquals(1, spider.details.get());
        assertNull("Configuration details cannot navigate to the player", playbackIntent.get());
        NativeUiEvidence.capture("source-detail-owned-window");
        main(spider.shown::dismiss);
        await("library regains focus", page::hasWindowFocus);
        main(() -> type.onItemClick(settings()));
        await("the user can reopen the configuration", () -> spider.details.get() == 2 && spider.shown.isShowing());
    }

    @Test public void shouldCancelDelayedDetailWindowsAfterLeavingTheLibrary() {
        spider.delay = 1000;
        main(() -> type.onItemClick(settings()));
        await("detail request returns without player navigation", () -> spider.details.get() == 1);
        main(page::finish);
        await("home resumes", home::hasWindowFocus);
        SystemClock.sleep(1300);
        assertTrue(spider.shown == null || !spider.shown.isShowing());
        assertFalse(device.hasObject(By.text("Legacy proxy engine")));
        assertNull(playbackIntent.get());
        NativeUiEvidence.capture("source-detail-home-after-return");
    }

    @Test public void shouldNavigatePlayableListDetailsWithoutFetchingThemTwice() {
        main(() -> type.onItemClick(Vod.objectFrom(
                "{\"vod_id\":\"legacy-movie\",\"vod_name\":\"A video in the same list\"}")));
        await("normal list videos still navigate", () -> playbackIntent.get() != null);
        assertEquals(1, spider.details.get());
        assertEquals("legacy-movie", playbackIntent.get().getStringExtra("id"));
        Result detail = playbackIntent.get().getParcelableExtra("sourceDetail");
        assertNotNull(detail);
        Parcel parcel = Parcel.obtain();
        Result transported;
        try {
            parcel.writeParcelable(detail, 0);
            parcel.setDataPosition(0);
            transported = parcel.readParcelable(Result.class.getClassLoader());
        } finally {
            parcel.recycle();
        }
        assertEquals(1, transported.getVod().getFlags().size());
        AtomicInteger delivered = new AtomicInteger();
        main(() -> {
            videoModel = new VideoViewModel();
            videoModel.getResult().observe(page, value -> delivered.incrementAndGet());
            videoModel.detailContent(site.getKey(), "legacy-movie", transported);
        });
        await("the player consumes the transported detail", () -> delivered.get() == 1);
        assertEquals("Navigation must reuse the source response", 1, spider.details.get());
        main(() -> videoModel.detailContent(site.getKey(), "legacy-movie"));
        await("explicit refresh fetches a new detail", () -> delivered.get() == 2);
        assertEquals(2, spider.details.get());
    }

    private Vod settings() {
        Vod item = Vod.objectFrom("{\"vod_id\":\"legacy-settings\",\"vod_name\":\"Legacy proxy settings\"}");
        assertFalse("This is the legacy detail protocol, without an action field", item.isAction());
        return item;
    }

    private TypeFragment findType(Iterable<Fragment> fragments) {
        for (Fragment fragment : fragments) {
            if (fragment instanceof TypeFragment child && child.getUserVisibleHint()) return child;
            TypeFragment child = findType(fragment.getChildFragmentManager().getFragments());
            if (child != null) return child;
        }
        return null;
    }

    private void main(Runnable runnable) {
        instrumentation.runOnMainSync(runnable);
    }

    private void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(40);
        }
        fail(message);
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
