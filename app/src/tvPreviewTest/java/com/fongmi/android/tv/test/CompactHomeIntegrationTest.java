package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomePreviewFixtures;
import com.fongmi.android.tv.ui.activity.TvHomePreviewActivity;
import com.fongmi.android.tv.ui.custom.JetStreamHomeNavView;
import com.fongmi.android.tv.ui.home.PosterHomeCatalog;

import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Real home presenters and SiteApi calls, with offline recommendation and source fixtures. */
@RunWith(AndroidJUnit4.class)
public final class CompactHomeIntegrationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private HomePreviewFixtures.Session fixture;
    private TvHomePreviewActivity home;
    private Map<String, ?> savedPreferences;

    @Before public void open() {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview", instrumentation.getTargetContext().getPackageName());
        main(() -> {
            savedPreferences = Prefers.getPrefers().getAll();
            fixture = HomePreviewFixtures.openCompact(instrumentation.getTargetContext());
        });
        home = (TvHomePreviewActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), TvHomePreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("compact home and source categories load", () -> home.hasWindowFocus()
                && ((Result) HomePreviewFixtures.field(home, "mResult")).getTypes().size() == 2);
    }

    @After public void close() {
        if (home != null) main(home::finish);
        if (home != null) await("home closes before restoring fixture state", home::isDestroyed);
        if (fixture != null) main(fixture::close);
        if (savedPreferences != null) main(() -> {
            Map<String, ?> restored = Prefers.getPrefers().getAll();
            for (String key : List.of("browse_home_layout_v1", "browse_poster_home")) {
                assertEquals("Restore exact preference presence for " + key, savedPreferences.containsKey(key), restored.containsKey(key));
                assertEquals("Restore exact preference value for " + key, savedPreferences.get(key), restored.get(key));
            }
        });
    }

    @Test public void shouldUseCompactDefaultAndKeepLibraryInNavigation() {
        main(() -> {
            assertFalse("The legacy flag cannot override the new default", Prefers.getBoolean("browse_poster_home", true));
            assertFalse(Prefers.getPrefers().contains("browse_home_layout_v1"));
            assertEquals(BrowseExperienceSettings.HOME_COMPACT, BrowseExperienceSettings.getHomeLayout());
            @SuppressWarnings("unchecked") List<JetStreamHomeNavView.NavItem> nav = (List<JetStreamHomeNavView.NavItem>) HomePreviewFixtures.field(home.findViewById(R.id.nav), "items");
            assertTrue(nav.stream().anyMatch(item -> item.getKey().equals(String.valueOf(R.string.home_vod))));
            assertFalse(nav.stream().anyMatch(item -> item.getKey().equals(String.valueOf(R.string.home_discover))));
            assertTrue(home.categoriesPosition() > home.historyPosition());
            assertEquals(List.of("trending_day", "douban_movie", "douban_tv"), visibleShelfKeys());
        });
        NativeUiEvidence.capture("compact-home");
        text("片库").click();
        await("library routes through its existing activity", () -> routeIs("VodActivity"));
        assertEquals("preview-source-0", home.lastNavigation.getStringExtra("key"));
        device.pressDPadRight();
        device.pressDPadCenter();
        await("remote navigation skips the removed recommendation tab", () -> routeIs("SearchActivity"));
    }

    @Test public void shouldKeepCategoryOrderAndExposeFilterShortcut() throws Exception {
        main(() -> home.focusRow(home.categoriesPosition()));
        await("category row is visible", () -> rowView(home.categoriesPosition()) != null);
        text(home.getString(R.string.home_wall_movies)).click();
        await("movie shelves replace the compact selection", () -> visibleShelfKeys().equals(
                List.of("douban_movie", "now_playing", "popular_movie", "top_movie")));
        text(home.getString(R.string.home_compact_filter)).click();
        await("filter shortcut emits a pure-filter page intent", () -> routeIs("DiscoverActivity"));
        assertTrue(home.lastNavigation.getBooleanExtra("filters_only", false));
        assertEquals(PosterHomeCatalog.MOVIES, home.lastNavigation.getIntExtra("home_category", -1));

        main(() -> applyEmptySnapshot());
        main(() -> {
            assertTrue("A final empty snapshot removes previous recommendation shelves", visibleShelfKeys().isEmpty());
            assertFalse("Old hero data cannot survive an empty snapshot", home.controller().isFocusable(home.pageAdapter().get(0)));
        });
        NativeUiEvidence.capture("compact-home-empty");
    }

    @Test public void shouldKeepNoArtworkActionBeyondThePreviewLimit() {
        main(() -> {
            Object source = HomePreviewFixtures.field(home.controller(), "source");
            @SuppressWarnings("unchecked") List<Vod> items = (List<Vod>) HomePreviewFixtures.field(source, "items");
            @SuppressWarnings("unchecked") List<Vod> actions = (List<Vod>) HomePreviewFixtures.field(source, "actions");
            assertEquals(20, items.size());
            assertEquals(1, actions.size());
            assertTrue(actions.get(0).getPic().isEmpty());
            assertTrue(actions.get(0).getId().isEmpty());
            home.focusRow(home.sourcePosition());
        });
        text("配置 CK").click();
        await("source action uses the production SiteApi action path", () -> fixture.actionCalls.get() == 1);
        NativeUiEvidence.capture("compact-source-actions");
    }

    @Test public void shouldRebindSourceCardsAfterAnIdenticalRefresh() {
        main(() -> home.focusRow(home.sourcePosition()));
        await("source preview cards are laid out", () -> firstSourceCard() != null);
        Vod previous = ((Result) HomePreviewFixtures.field(home, "mResult")).getList().get(0);
        main(home::onRefresh);
        await("new response instances replace the preview", () -> !((Result) HomePreviewFixtures.field(home, "mResult")).getList().isEmpty()
                && ((Result) HomePreviewFixtures.field(home, "mResult")).getList().get(0) != previous);
        main(() -> home.focusRow(home.sourcePosition()));
        await("refreshed card is visible", () -> firstSourceCard() != null);
        main(() -> assertTrue(firstSourceCard().performClick()));
        await("retained card clicks accept the new current source object", () -> routeIs("VideoActivity"));
    }

    @Test public void shouldKeepLegacyLayoutChoicesIndependentOfOtherSettings() {
        main(() -> {
            String base = Setting.getBridgeBase();
            String key = Setting.getBridgeSk();
            boolean sources = BrowseExperienceSettings.isDetailSourcesEnabled();
            BrowseExperienceSettings.putHomeLayout(BrowseExperienceSettings.HOME_SOURCE);
            assertFalse(BrowseExperienceSettings.isPosterHomeEnabled());
            assertFalse(BrowseExperienceSettings.isCompactHomeEnabled());
            BrowseExperienceSettings.putHomeLayout(BrowseExperienceSettings.HOME_POSTERS);
            assertTrue(BrowseExperienceSettings.isPosterHomeEnabled());
            assertFalse(BrowseExperienceSettings.isCompactHomeEnabled());
            BrowseExperienceSettings.putHomeLayout(BrowseExperienceSettings.HOME_COMPACT);
            assertTrue(BrowseExperienceSettings.isCompactHomeEnabled());
            assertEquals(base, Setting.getBridgeBase());
            assertEquals(key, Setting.getBridgeSk());
            assertEquals(sources, BrowseExperienceSettings.isDetailSourcesEnabled());
        });
    }

    private List<String> visibleShelfKeys() {
        @SuppressWarnings("unchecked") List<Object> shelves = (List<Object>) HomePreviewFixtures.field(home.controller(), "shelves");
        return java.util.stream.IntStream.range(0, home.pageAdapter().size()).mapToObj(home.pageAdapter()::get)
                .filter(shelves::contains).map(shelf -> (String) HomePreviewFixtures.field(shelf, "key")).toList();
    }

    private View rowView(int position) {
        RecyclerView.ViewHolder holder = home.pageGrid().findViewHolderForAdapterPosition(position);
        return holder == null ? null : holder.itemView;
    }

    private View firstSourceCard() {
        View row = rowView(home.sourcePosition());
        if (row == null) return null;
        HorizontalGridView grid = row.findViewById(R.id.posters);
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(0);
        return holder == null ? null : holder.itemView;
    }

    private boolean routeIs(String destination) {
        return home.lastNavigation != null && home.lastNavigation.getComponent() != null
                && home.lastNavigation.getComponent().getClassName().equals("com.fongmi.android.tv.ui.activity." + destination);
    }

    private void applyEmptySnapshot() {
        try {
            Method method = home.controller().getClass().getDeclaredMethod("applyRexResults", List.class);
            method.setAccessible(true);
            method.invoke(home.controller(), List.of());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private UiObject2 text(String value) {
        UiObject2 result = device.wait(Until.findObject(By.text(value)), 5000);
        assertNotNull("Visible control: " + value, result);
        return result;
    }

    private void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    private void await(String description, BooleanSupplier ready) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        AtomicBoolean complete = new AtomicBoolean();
        do {
            main(() -> complete.set(ready.getAsBoolean()));
            if (complete.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(description);
    }
}
