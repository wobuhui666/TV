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
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.RexSection;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomePreviewFixtures;
import com.fongmi.android.tv.ui.activity.LibraryNavigation;
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
import java.util.concurrent.atomic.AtomicReference;
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
            ExperienceSettings.putMode(ExperienceMode.LIBRARY);
            fixture = HomePreviewFixtures.openCompact(instrumentation.getTargetContext());
        });
        home = (TvHomePreviewActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), TvHomePreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("compact home and source categories load", () -> home.hasWindowFocus()
                && ((Result) HomePreviewFixtures.field(home, "mResult")).getTypes().size() == 2);
        // Android touch mode survives the previous Activity; each fixture starts with remote navigation.
        device.pressDPadUp();
        AtomicBoolean touchMode = new AtomicBoolean();
        main(() -> touchMode.set(home.pageGrid().isInTouchMode()
                || (boolean) HomePreviewFixtures.field(home, "mTouchBrowsing")));
        if (touchMode.get()) device.pressDPadUp();
        await("the new home starts in Android and application D-pad mode", () ->
                !home.pageGrid().isInTouchMode()
                        && !(boolean) HomePreviewFixtures.field(home, "mTouchBrowsing"));
    }

    @After public void close() {
        if (home != null) main(home::finish);
        if (home != null) await("home closes before restoring fixture state", home::isDestroyed);
        if (fixture != null) main(fixture::close);
        if (savedPreferences != null) main(() -> {
            Object mode = savedPreferences.get("tv_experience_mode_v1");
            if (mode instanceof String value) Prefers.put("tv_experience_mode_v1", value);
            else Prefers.remove("tv_experience_mode_v1");
            Map<String, ?> restored = Prefers.getPrefers().getAll();
            for (String key : List.of("browse_home_layout_v1", "browse_poster_home")) {
                assertEquals("Restore exact preference presence for " + key, savedPreferences.containsKey(key), restored.containsKey(key));
                assertEquals("Restore exact preference value for " + key, savedPreferences.get(key), restored.get(key));
            }
        });
    }

    @Test public void shouldMergeRecommendationsIntoHomeAndKeepTheNativeSourceLibrary() {
        main(() -> {
            assertTrue("The native source directory remains available in library mode", ExperienceSettings.usesLibrary());
            @SuppressWarnings("unchecked") List<JetStreamHomeNavView.NavItem> nav = (List<JetStreamHomeNavView.NavItem>) HomePreviewFixtures.field(home.findViewById(R.id.nav), "items");
            assertTrue(nav.stream().anyMatch(item -> item.getKey().equals(String.valueOf(R.string.home_vod))));
            assertFalse("Curated recommendations are part of Home", nav.stream().anyMatch(item -> item.getKey().equals(String.valueOf(R.string.home_discover))));
        });
        await("Home shows a bounded selection including domestic films and series", () -> visibleShelfKeys().equals(
                List.of("cn_movie", "cn_tv", "trending_day")));
        main(this::assertCuratedShelfLimits);
        NativeUiEvidence.capture("curated-home-domestic");
        text("片库").click();
        await("library routes through its existing activity", () -> routeIs("VodActivity"));
        assertEquals("preview-source-0", home.lastNavigation.getStringExtra("key"));
        Result source = home.lastNavigation.getParcelableExtra("result");
        assertNotNull(source);
        assertEquals("The source's own categories remain available", 2, source.getTypes().size());
    }

    @Test public void shouldKeepTheSameCuratedHomeInSimpleMode() {
        main(home::finish);
        await("Full-library home closes before switching mode", home::isDestroyed);
        main(() -> ExperienceSettings.putMode(ExperienceMode.SIMPLE));
        home = (TvHomePreviewActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), TvHomePreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("Simple home loads its domestic selection", () -> home.hasWindowFocus()
                && visibleShelfKeys().equals(List.of("cn_movie", "cn_tv", "trending_day")));
        main(() -> {
            @SuppressWarnings("unchecked") List<JetStreamHomeNavView.NavItem> nav = (List<JetStreamHomeNavView.NavItem>) HomePreviewFixtures.field(home.findViewById(R.id.nav), "items");
            assertFalse(nav.stream().anyMatch(item -> item.getKey().equals(String.valueOf(R.string.home_discover))));
            assertCuratedShelfLimits();
        });
        text("片库").click();
        await("Simple mode retains the native source library", () -> routeIs("VodActivity"));
        assertEquals("preview-source-0", home.lastNavigation.getStringExtra("key"));
        assertEquals(2, ((Result) home.lastNavigation.getParcelableExtra("result")).getTypes().size());
    }

    @Test public void shouldKeepCategoryOrderAndExposeFilterShortcut() throws Exception {
        main(() -> home.focusRow(home.categoriesPosition()));
        await("category row is visible", () -> rowView(home.categoriesPosition()) != null);
        text(home.getString(R.string.home_wall_movies)).click();
        await("movie shelves replace the compact selection", () -> visibleShelfKeys().equals(
                List.of("cn_movie", "douban_movie", "now_playing")));
        main(this::assertCuratedShelfLimits);
        device.pressDPadDown();
        AtomicBoolean touchBrowsing = new AtomicBoolean();
        main(() -> touchBrowsing.set((boolean) HomePreviewFixtures.field(home, "mTouchBrowsing")));
        // Android can consume the first D-pad key while restoring focus from the category tap.
        if (touchBrowsing.get()) device.pressDPadDown();
        await("D-pad navigation resumes before requesting the category row", () ->
                !(boolean) HomePreviewFixtures.field(home, "mTouchBrowsing") && !home.pageGrid().isInTouchMode());
        main(() -> home.focusRow(home.categoriesPosition()));
        try {
            await("the refreshed category row exposes its filter control", () -> {
                View row = rowView(home.categoriesPosition());
                View filter = row == null ? null : row.findViewById(R.id.filter);
                android.graphics.Rect bounds = new android.graphics.Rect();
                return filter != null && filter.getGlobalVisibleRect(bounds) && bounds.height() == filter.getHeight();
            });
        } catch (AssertionError error) {
            AtomicReference<String> diagnostic = new AtomicReference<>();
            main(() -> {
                View row = rowView(home.categoriesPosition());
                View filter = row == null ? null : row.findViewById(R.id.filter);
                android.graphics.Rect bounds = new android.graphics.Rect();
                boolean visible = filter != null && filter.getGlobalVisibleRect(bounds);
                diagnostic.set("category=" + home.categoriesPosition() + "; selected=" + home.pageGrid().getSelectedPosition()
                        + "; generation=" + home.focusGeneration() + "; focus=" + home.getCurrentFocus()
                        + "; row=" + row + "; filter=" + filter + "; shown=" + (filter != null && filter.isShown())
                        + "; visibility=" + (filter == null ? -1 : filter.getVisibility())
                        + "; height=" + (filter == null ? -1 : filter.getHeight()) + "; visible=" + visible + "; bounds=" + bounds);
            });
            NativeUiEvidence.capture("compact-home-filter-failure");
            throw new AssertionError(error.getMessage() + "; " + diagnostic.get(), error);
        }
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

    @Test public void shouldKeepSeriesAndTopSelectionsBoundedAfterCategoryChanges() {
        int[] labels = {R.string.home_wall_tv, R.string.home_wall_top, R.string.home_wall_all};
        List<List<String>> expected = List.of(List.of("cn_tv", "douban_tv", "popular_tv"),
                List.of("cn_top_movie", "cn_top_tv"), List.of("cn_movie", "cn_tv", "trending_day"));
        for (int i = 0; i < labels.length; i++) {
            main(() -> home.focusRow(home.categoriesPosition()));
            text(home.getString(labels[i])).click();
            List<String> keys = expected.get(i);
            await("Only the selected category's previews remain", () -> visibleShelfKeys().equals(keys));
            main(this::assertCuratedShelfLimits);
        }
    }

    @Test public void shouldKeepNoArtworkActionBeyondThePreviewLimit() {
        main(() -> {
            Object source = HomePreviewFixtures.field(home.controller(), "source");
            @SuppressWarnings("unchecked") List<Vod> items = (List<Vod>) HomePreviewFixtures.field(source, "items");
            @SuppressWarnings("unchecked") List<Vod> actions = (List<Vod>) HomePreviewFixtures.field(source, "actions");
            assertEquals(20, items.size());
            assertEquals(2, actions.size());
            assertTrue(actions.get(0).getPic().isEmpty());
            assertTrue(actions.get(0).getId().isEmpty());
            home.focusRow(home.sourcePosition());
        });
        text("配置 CK").click();
        await("source action uses the production SiteApi action path", () -> fixture.actionCalls.get() == 1);
        await("content child focus hides the toolbar even when the selected row did not change", () -> home.toolbar().getVisibility() == View.GONE);
        main(() -> {
            View source = rowView(home.sourcePosition());
            assertNotNull(source);
            assertTrue("The source header remains inside the viewport", source.getTop() >= 0);
        });
        NativeUiEvidence.capture("compact-source-actions");
    }

    @Test public void shouldOpenSourceAccountFolderWithoutTmdbInterception() {
        main(() -> home.focusRow(home.sourcePosition()));
        text("账号设置").click();
        await("The source folder opens its native category activity", () -> routeIs("VodActivity"));
        assertEquals("preview-source-0", home.lastNavigation.getStringExtra("key"));
        Result folder = home.lastNavigation.getParcelableExtra("result");
        assertNotNull(folder);
        assertEquals(1, folder.getTypes().size());
        assertEquals("source-settings", folder.getTypes().get(0).getTypeId());
        assertTrue(folder.getTypes().get(0).isFolder());
        assertEquals("Opening a folder does not execute an unrelated login action", 0, fixture.actionCalls.get());
    }

    @Test public void shouldKeepAccountFoldersOutOfIndexSourceSearch() throws Exception {
        java.lang.reflect.Field index = Site.class.getDeclaredField("indexs");
        index.setAccessible(true);
        index.set(VodConfig.get().getHome(), 1);
        main(() -> home.focusRow(home.sourcePosition()));
        text("账号设置").click();
        await("An index source's account folder still opens its native directory", () -> routeIs("VodActivity"));
        Result folder = home.lastNavigation.getParcelableExtra("result");
        assertNotNull(folder);
        assertEquals("source-settings", folder.getTypes().get(0).getTypeId());
        main(() -> {
            Vod item = ((Result) HomePreviewFixtures.field(home, "mResult")).getList().stream()
                    .filter(Vod::isFolder).findFirst().orElseThrow();
            assertFalse("Folder long presses do not search its settings label", home.onLongClick(item));
        });
    }

    @Test public void shouldPreserveSourceFolderIdentityInSharedNavigation() {
        Vod item = Vod.objectFrom("{\"vod_id\":\"catalog:season?cursor=2\",\"vod_name\":\"片源目录\",\"vod_tag\":\"folder\"}");
        item.setSite(VodConfig.get().getHome());
        main(() -> LibraryNavigation.open(home, item, null));
        await("Shared navigation preserves a source folder", () -> routeIs("VodActivity"));
        assertEquals(item.getSiteKey(), home.lastNavigation.getStringExtra("key"));
        Result folder = home.lastNavigation.getParcelableExtra("result");
        assertNotNull(folder);
        assertEquals(item.getId(), folder.getTypes().get(0).getTypeId());
        assertTrue(folder.getTypes().get(0).isFolder());
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
        await("retained card clicks accept the new current source object", () -> routeIs("LibraryDetailActivity"));
        Vod selected = home.lastNavigation.getParcelableExtra("work");
        assertNotNull(selected);
        assertEquals("preview-source-0", selected.getSiteKey());
        assertEquals("source-preview-0", selected.getId());
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

    private void assertCuratedShelfLimits() {
        @SuppressWarnings("unchecked") List<Object> shelves = (List<Object>) HomePreviewFixtures.field(home.controller(), "shelves");
        int visible = 0;
        for (int i = 0; i < home.pageAdapter().size(); i++) {
            Object item = home.pageAdapter().get(i);
            if (!shelves.contains(item)) continue;
            visible++;
            @SuppressWarnings("unchecked") List<Vod> posters = (List<Vod>) HomePreviewFixtures.field(item, "items");
            assertTrue("Every home shelf is a limited preview", posters.size() <= 12);
            assertFalse("The fixture supplies content for every selected shelf", posters.isEmpty());
        }
        assertTrue("Home does not expand into the entire recommendation catalog", visible <= 3);
        Object loader = HomePreviewFixtures.field(home.controller(), "rexLoader");
        @SuppressWarnings("unchecked") List<RexSection> requested = (List<RexSection>) HomePreviewFixtures.field(loader, "sections");
        assertEquals("Only the selected home shelves are requested", visibleShelfKeys(),
                requested.stream().map(RexSection::getKey).toList());
        Object spotlight = HomePreviewFixtures.field(home.controller(), "rexSpotlightRow");
        assertEquals("Specialty recommendations do not add another home rail", -1, home.pageAdapter().indexOf(spotlight));
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
