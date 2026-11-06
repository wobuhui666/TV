package com.fongmi.android.tv.test;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.ui.activity.TvFolderPreviewActivity;
import com.fongmi.android.tv.ui.fragment.FolderFragment;
import com.fongmi.android.tv.ui.fragment.TypeFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Exercises actual folder back stacks, filter views and SiteApi category requests. */
@RunWith(AndroidJUnit4.class)
public final class FolderFilterNavigationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private TvFolderPreviewActivity activity;
    private TvFolderPreviewActivity.Session fixture;

    @Before
    public void open() {
        main(() -> fixture = TvFolderPreviewActivity.openFixture(instrumentation.getTargetContext()));
        activity = (TvFolderPreviewActivity) instrumentation.startActivitySync(intent());
        await("root category loads", () -> TvFolderPreviewActivity.ROOT_ID.equals(activity.currentCategoryId())
                && fixture.requested(TvFolderPreviewActivity.ROOT_ID, null) && categoryEntryVisible(TvFolderPreviewActivity.ROOT_ID));
    }

    @After
    public void close() {
        if (activity != null) main(activity::finish);
        if (activity != null) await("fixture activity closes", activity::isDestroyed);
        if (fixture != null) main(fixture::close);
    }

    @Test
    public void filterRequestedFromCommitKeepsNestedCategoryAndBackStack() throws Exception {
        openNestedFolders();
        main(() -> {
            FragmentManager manager = activity.folder().getChildFragmentManager();
            manager.beginTransaction().runOnCommit(() -> {
                activity.showFilters(true);
                assertEquals("Showing filters preserves the current navigation", 2, manager.getBackStackEntryCount());
            }).commit();
            manager.executePendingTransactions();
        });
        awaitFilters("folder-level-2", 2);
        selectFilter("folder-level-2");
        assertFalse("Filtering a folder leaves the root request untouched", fixture.requested(TvFolderPreviewActivity.ROOT_ID, "A"));
        main(() -> activity.folder().goBack());
        await("Back restores the previous folder", () -> "folder-level-1".equals(activity.currentCategoryId())
                && activity.folder().getChildFragmentManager().getBackStackEntryCount() == 1);
        capture("folder-filter-from-commit");
    }

    @Test
    public void savedStateDefersFilterAndRefreshUntilResume() throws Exception {
        openNestedFolders();
        device.pressHome();
        await("activity saves its child navigation state", () -> activity.folder().getChildFragmentManager().isStateSaved()
                && !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        main(() -> {
            activity.showFilters(true);
            activity.folder().onRefresh();
            assertEquals("Saved navigation cannot be popped", 2, activity.folder().getChildFragmentManager().getBackStackEntryCount());
            assertEquals("folder-level-2", activity.currentCategoryId());
        });
        instrumentation.getTargetContext().startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        await("activity resumes", () -> activity.getLifecycle().getCurrentState() == Lifecycle.State.RESUMED);
        awaitFilters("folder-level-2", 2);
        selectFilter("folder-level-2");
        capture("folder-filter-after-resume");
    }

    @Test
    public void viewRecreationRetainsNestedNavigationAndQueuedFilter() throws Exception {
        openNestedFolders();
        FolderFragment folder = activity.folder();
        main(() -> activity.getSupportFragmentManager().beginTransaction().detach(folder).commitNow());
        main(() -> assertNull(folder.getView()));
        main(() -> activity.getSupportFragmentManager().beginTransaction().attach(folder).commitNow());
        await("nested category survives parent view recreation", () -> "folder-level-2".equals(activity.currentCategoryId())
                && folder.getChildFragmentManager().getBackStackEntryCount() == 2);
        main(() -> {
            assertEquals("folder-level-2", activity.currentCategoryId());
            assertEquals(2, folder.getChildFragmentManager().getBackStackEntryCount());
            activity.getSupportFragmentManager().beginTransaction().detach(folder).commitNow();
            folder.getType().setFilter(true);
            folder.toggleFilter(true);
            folder.onRefresh();
            assertNull(folder.getView());
            activity.getSupportFragmentManager().beginTransaction().attach(folder).commitNow();
        });
        awaitFilters("folder-level-2", 2);
        selectFilter("folder-level-2");
        capture("folder-filter-after-view-recreation");
    }

    @Test
    public void cancelledQueuedFilterKeepsTheCurrentFolder() throws Exception {
        openNestedFolders();
        main(() -> {
            FragmentManager manager = activity.folder().getChildFragmentManager();
            manager.beginTransaction().runOnCommit(() -> {
                activity.showFilters(true);
                activity.showFilters(false);
            }).commit();
            manager.executePendingTransactions();
        });
        main(() -> {
            assertEquals(2, activity.folder().getChildFragmentManager().getBackStackEntryCount());
            assertEquals("folder-level-2", activity.currentCategoryId());
            assertTrue("The current folder retains its rendered results", categoryEntryVisible("folder-level-2"));
            assertNull(filterOption());
        });
        capture("cancelled-filter-keeps-folder");
    }

    @Test
    public void inheritedFiltersKeepIndependentSelectionsThroughBackAndViewRecreation() throws Exception {
        main(() -> activity.showFilters(true));
        awaitFilters(TvFolderPreviewActivity.ROOT_ID, 0);
        selectFilter(TvFolderPreviewActivity.ROOT_ID);
        TypeFragment root = activity.currentCategory();
        main(() -> activity.folder().openFolder("folder-level-1", extend(root)));
        await("first folder inherits the selected root filter", () -> "folder-level-1".equals(activity.currentCategoryId())
                && fixture.requested("folder-level-1", "A") && categoryEntryVisible("folder-level-1"));
        TypeFragment first = activity.currentCategory();
        main(() -> {
            assertNotSame("Each folder owns its filter values", extend(root), extend(first));
            activity.showFilters(true);
        });
        awaitFilters("folder-level-1", 1);
        await("inherited selection is shown", () -> filterOption().isSelected());
        clickFilter();
        await("clearing the child filter refreshes only that category", () -> requestedWithoutFilter("folder-level-1"));
        main(() -> {
            assertEquals("A", extend(root).get("region"));
            assertFalse(extend(first).containsKey("region"));
            activity.folder().openFolder("folder-level-2", extend(first));
        });
        await("second folder starts with its parent's current filter values", () -> "folder-level-2".equals(activity.currentCategoryId())
                && requestedWithoutFilter("folder-level-2") && categoryEntryVisible("folder-level-2"));
        main(() -> activity.showFilters(true));
        awaitFilters("folder-level-2", 2);
        selectFilter("folder-level-2");
        main(() -> {
            assertFalse("Filtering a descendant cannot alter its hidden parent", extend(first).containsKey("region"));
            FolderFragment folder = activity.folder();
            activity.getSupportFragmentManager().beginTransaction().detach(folder).commitNow();
            activity.getSupportFragmentManager().beginTransaction().attach(folder).commitNow();
        });
        awaitFilters("folder-level-2", 2);
        await("recreated filter controls retain this folder's selection", () -> filterOption().isSelected());
        main(() -> activity.folder().goBack());
        await("Back restores the parent's filter snapshot", () -> activity.currentCategory() == first
                && !extend(first).containsKey("region") && activity.folder().getChildFragmentManager().getBackStackEntryCount() == 1);
        main(() -> activity.showFilters(true));
        awaitFilters("folder-level-1", 1);
        await("parent filter selection stays cleared", () -> !filterOption().isSelected());
        main(() -> activity.folder().goBack());
        await("Back restores the root's original selection", () -> activity.currentCategory() == root
                && "A".equals(extend(root).get("region")) && !activity.folder().canBack());
        main(() -> activity.showFilters(true));
        awaitFilters(TvFolderPreviewActivity.ROOT_ID, 0);
        await("root filter remains selected", () -> filterOption().isSelected());
        capture("folder-filter-snapshots");
    }

    @Test
    public void explicitlyEmptyChildFiltersDoNotInheritRootControls() {
        String categoryId = "folder-explicitly-unfiltered";
        Map<String, List<Filter>> cache = filterCache();
        boolean cached = cache.containsKey(categoryId);
        List<Filter> previous = cache.get(categoryId);
        try {
            main(() -> {
                Class type = new Class();
                type.setTypeId(categoryId);
                type.setFilters(List.of());
                Result result = new Result();
                result.setTypes(List.of(type));
                new Cache().put(result);
                assertTrue(Cache.contains(categoryId));
                activity.folder().openFolder(categoryId, new HashMap<>());
            });
            await("unfiltered child category loads", () -> categoryId.equals(activity.currentCategoryId())
                    && fixture.requested(categoryId, null) && categoryEntryVisible(categoryId));
            main(() -> activity.showFilters(true));
            main(() -> {
                assertEquals(categoryId, activity.currentCategoryId());
                assertEquals(1, activity.folder().getChildFragmentManager().getBackStackEntryCount());
                assertNull("An explicit empty filter set suppresses root fallback", filterOption());
            });
        } finally {
            main(() -> {
                if (cached) cache.put(categoryId, previous);
                else cache.remove(categoryId);
            });
        }
    }

    private void openNestedFolders() {
        main(() -> activity.folder().openFolder("folder-level-1", new HashMap<>()));
        await("first folder loads", () -> "folder-level-1".equals(activity.currentCategoryId())
                && fixture.requested("folder-level-1", null) && categoryEntryVisible("folder-level-1"));
        main(() -> activity.folder().openFolder("folder-level-2", new HashMap<>()));
        await("second folder loads", () -> "folder-level-2".equals(activity.currentCategoryId())
                && fixture.requested("folder-level-2", null) && categoryEntryVisible("folder-level-2"));
    }

    private void awaitFilters(String categoryId, int depth) {
        await("filter controls appear in " + categoryId, () -> categoryId.equals(activity.currentCategoryId())
                && activity.folder().getChildFragmentManager().getBackStackEntryCount() == depth && filterOption() != null);
    }

    private void selectFilter(String categoryId) {
        clickFilter();
        await("filter applies to " + categoryId, () -> fixture.requested(categoryId, "A"));
    }

    private void clickFilter() {
        main(() -> {
            TextView option = filterOption();
            assertNotNull(option);
            assertTrue("The actual filter control accepts a click", option.performClick());
        });
    }

    private boolean requestedWithoutFilter(String categoryId) {
        return fixture.requests.stream().anyMatch(request -> categoryId.equals(request.get("category").getAsString())
                && !request.getAsJsonObject("filters").has("region"));
    }

    @SuppressWarnings("unchecked")
    private HashMap<String, String> extend(TypeFragment category) {
        return (HashMap<String, String>) category.requireArguments().getSerializable("extend");
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Filter>> filterCache() {
        try {
            java.lang.Class<?> loader = java.lang.Class.forName(Cache.class.getName() + "$Loader");
            Field instance = loader.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            Field values = Cache.class.getDeclaredField("cache");
            values.setAccessible(true);
            return (Map<String, List<Filter>>) values.get(instance.get(null));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private TextView filterOption() {
        TypeFragment category = activity.currentCategory();
        return category == null ? null : findText(category.getView(), TvFolderPreviewActivity.FILTER_OPTION);
    }

    private boolean categoryEntryVisible(String id) {
        TypeFragment category = activity.currentCategory();
        return category != null && findText(category.getView(), "Entry in " + id) != null;
    }

    private TextView findText(View view, String text) {
        if (view == null || !view.isShown()) return null;
        if (view instanceof TextView label && view.getWidth() > 0 && view.getHeight() > 0
                && text.contentEquals(label.getText())) return label;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Intent intent() {
        return new Intent(instrumentation.getTargetContext(), TvFolderPreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        File directory = new File(activity.getExternalFilesDir(null), "folder-filter-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
        device.dumpWindowHierarchy(new File(directory, name + ".xml"));
    }
}
