package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.PosterSourcePrioritySetting;
import com.fongmi.android.tv.ui.activity.MyActivity;
import com.fongmi.android.tv.ui.dialog.PosterSourcePriorityDialog;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Real native dialog and input; fake sites and one preference are restored exactly after use. */
@RunWith(AndroidJUnit4.class)
public final class PosterSourcePriorityIntegrationTest {
    private static final String PREFERENCE = "browse_poster_source_priority_v1";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger changes = new AtomicInteger();
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private MyActivity activity;
    private Object savedSites;
    private Object savedConfig;
    private Object savedPreference;
    private boolean prepared;

    @Before public void prepareIsolatedConfig() {
        String packageName = instrumentation.getTargetContext().getPackageName();
        assertTrue("Use an isolated validation application", packageName.equals("com.fongmi.android.tv.preview")
                || packageName.equals("com.fongmi.android.tv.sourceprobe"));
        assertEquals("This verifies the native TV dialog", "leanback", BuildConfig.FLAVOR_mode);
        main(() -> {
            savedSites = field(VodConfig.class, VodConfig.get(), "sites");
            savedConfig = field(VodConfig.class.getSuperclass(), VodConfig.get(), "config");
            savedPreference = Prefers.getPrefers().getAll().get(PREFERENCE);
            prepared = true;
            // Config.create(type, url) inserts into Room; this plain object never enters a database.
            VodConfig.get().config(new Config().url("https://priority-ui-fixture.invalid/" + UUID.randomUUID()));
            setField(VodConfig.class, VodConfig.get(), "sites", new ArrayList<>(List.of(
                    site("a", true), site("b", true), site("c", true), site("d", true), site("disabled", false))));
            PosterSourcePrioritySetting.putOrderedKeys(List.of(key("b")));
        });
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MyActivity.class.getName(), null, false);
        try {
            main(() -> instrumentation.getTargetContext().startActivity(new Intent(instrumentation.getTargetContext(), MyActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
            activity = (MyActivity) instrumentation.waitForMonitorWithTimeout(monitor, 8000);
            assertNotNull("Native fixture host was launched", activity);
        } finally { instrumentation.removeMonitor(monitor); }
        await(activity::hasWindowFocus, "native host window");
    }

    @After public void restoreExactState() {
        if (!prepared) return;
        try {
            main(() -> {
                for (AlertDialog dialog : dialogs) if (dialog.isShowing()) dialog.dismiss();
                if (activity != null && !activity.isDestroyed()) activity.finish();
            });
            if (activity != null) await(activity::isDestroyed, "host destroyed before restoring its config");
        } finally {
            main(() -> {
                setField(VodConfig.class, VodConfig.get(), "sites", savedSites);
                setField(VodConfig.class.getSuperclass(), VodConfig.get(), "config", savedConfig);
                SharedPreferences.Editor editor = Prefers.getPrefers().edit();
                if (savedPreference instanceof String value) editor.putString(PREFERENCE, value);
                else if (savedPreference instanceof Boolean value) editor.putBoolean(PREFERENCE, value);
                else if (savedPreference instanceof Integer value) editor.putInt(PREFERENCE, value);
                else if (savedPreference instanceof Long value) editor.putLong(PREFERENCE, value);
                else if (savedPreference instanceof Float value) editor.putFloat(PREFERENCE, value);
                else if (savedPreference instanceof Set<?> values) {
                    Set<String> copy = new HashSet<>();
                    for (Object value : values) copy.add((String) value);
                    editor.putStringSet(PREFERENCE, copy);
                } else editor.remove(PREFERENCE);
                assertTrue("Restore the exact raw priority preference", editor.commit());
                assertSame(savedSites, field(VodConfig.class, VodConfig.get(), "sites"));
                assertSame(savedConfig, field(VodConfig.class.getSuperclass(), VodConfig.get(), "config"));
                assertEquals(savedPreference, Prefers.getPrefers().getAll().get(PREFERENCE));
            });
        }
    }

    @Test(timeout = 60_000) public void nativeEditorKeepsDraftsUntilSaveAndRestoresDefaultOrder() {
        Editor cancelled = openEditor();
        addTwoSources(cancelled);
        View moveDown = rowAction(cancelled.dialog, 0, "b", R.id.priorityMoveDown);
        tap(moveDown);
        assertRow(cancelled.dialog, 1, "b");
        assertEquals("Editing is still a draft", List.of(key("b")), value(PosterSourcePrioritySetting::getOrderedKeys));
        click(cancelled.dialog.findViewById(R.id.cancel));
        await(() -> !cancelled.dialog.isShowing() && activity.hasWindowFocus(), "cancel returns to host");
        assertEquals(List.of(key("b")), value(PosterSourcePrioritySetting::getOrderedKeys));
        assertEquals("Cancel does not notify or start a new source search", 0, changes.get());

        Editor saved = openEditor();
        addTwoSources(saved);
        press(KeyEvent.KEYCODE_DPAD_UP); // Leave touch mode before testing the remote's confirm key.
        View down = rowAction(saved.dialog, 0, "b", R.id.priorityMoveDown);
        main(() -> assertTrue(down.requestFocus()));
        await(down::isFocused, "move-down button owns remote focus");
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertRow(saved.dialog, 1, "b");
        View up = rowAction(saved.dialog, 1, "b", R.id.priorityMoveUp);
        click(up);
        assertRow(saved.dialog, 0, "b");
        await(() -> focusedRank(saved.dialog) == 0 && recycler(saved.dialog).findFocus().getId() == R.id.priorityMoveUp,
                "move-up keeps focus on the same action at the top edge");
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertRow(saved.dialog, 0, "b");
        assertEquals("An edge confirm must not remove an item", 3, value(() -> recycler(saved.dialog).getAdapter().getItemCount()).intValue());
        assertEquals("Neither move wrote preferences", List.of(key("b")), value(PosterSourcePrioritySetting::getOrderedKeys));
        click(saved.dialog.findViewById(R.id.save));
        await(() -> changes.get() == 1 && !saved.dialog.isShowing(), "save applies and reports the ordered list once");
        assertEquals(List.of(key("b"), key("a"), key("c")), value(PosterSourcePrioritySetting::getOrderedKeys));
        assertEquals("Unselected searchable sources remain as fallback", List.of(key("b"), key("a"), key("c"), key("d")),
                value(() -> PosterSourcePrioritySetting.orderSites(VodConfig.get().getSites()).stream().map(Site::getKey).toList()));

        Editor resetCancelled = openEditor();
        click(resetCancelled.dialog.findViewById(R.id.reset));
        await(() -> resetCancelled.dialog.findViewById(R.id.empty).isShown(), "restore-default is an empty draft");
        click(resetCancelled.dialog.findViewById(R.id.cancel));
        await(() -> !resetCancelled.dialog.isShowing(), "default draft cancelled");
        assertEquals(List.of(key("b"), key("a"), key("c")), value(PosterSourcePrioritySetting::getOrderedKeys));
        assertEquals(1, changes.get());

        Editor resetSaved = openEditor();
        click(resetSaved.dialog.findViewById(R.id.reset));
        click(resetSaved.dialog.findViewById(R.id.save));
        await(() -> changes.get() == 2 && !resetSaved.dialog.isShowing(), "save restores the original source order");
        assertTrue(value(PosterSourcePrioritySetting::getOrderedKeys).isEmpty());
        assertEquals(List.of(key("a"), key("b"), key("c"), key("d")),
                value(() -> PosterSourcePrioritySetting.orderSites(VodConfig.get().getSites()).stream().map(Site::getKey).toList()));
    }

    @Test(timeout = 45_000) public void removingPriorityKeepsTheSourceAndStaleEditorsCannotWriteAnotherConfig() {
        Config firstConfig = value(() -> VodConfig.get().getConfig());
        List<Site> originalSites = value(() -> VodConfig.get().getSites());
        main(() -> PosterSourcePrioritySetting.putOrderedKeys(List.of(key("b"), key("a"), key("c"))));
        Editor removal = openEditor();
        click(rowAction(removal.dialog, 0, "b", R.id.priorityRemove));
        await(() -> recycler(removal.dialog).getAdapter().getItemCount() == 2, "remove changes the draft list");
        assertEquals("Removal still needs Save", List.of(key("b"), key("a"), key("c")), value(PosterSourcePrioritySetting::getOrderedKeys));
        click(removal.dialog.findViewById(R.id.save));
        await(() -> !removal.dialog.isShowing() && changes.get() == 1, "removed priority saved once");
        assertEquals(List.of(key("a"), key("c")), value(PosterSourcePrioritySetting::getOrderedKeys));
        main(() -> {
            assertSame("Priority editing must not replace the source configuration", originalSites, VodConfig.get().getSites());
            assertEquals(List.of(key("a"), key("b"), key("c"), key("d"), key("disabled")),
                    VodConfig.get().getSites().stream().map(Site::getKey).toList());
            assertTrue("The removed source remains searchable", originalSites.get(1).isSearchable());
            assertFalse("Disabled sources stay disabled", originalSites.get(4).isSearchable());
            assertEquals("The removed priority becomes fallback", List.of(key("a"), key("c"), key("b"), key("d")),
                    PosterSourcePrioritySetting.orderSites(originalSites).stream().map(Site::getKey).toList());
        });

        Config secondConfig = new Config().url("https://priority-ui-fixture.invalid/" + UUID.randomUUID());
        main(() -> {
            // Deliberately reuse the source keys: availability filtering cannot conceal a cross-write.
            VodConfig.get().config(secondConfig);
            PosterSourcePrioritySetting.putOrderedKeys(List.of(key("d"), key("b")));
            VodConfig.get().config(firstConfig);
        });
        Object beforeRejectedSave = value(() -> Prefers.getPrefers().getAll().get(PREFERENCE));
        Editor stale = openEditor();
        click(rowAction(stale.dialog, 0, "a", R.id.priorityMoveDown));
        assertRow(stale.dialog, 1, "a");
        main(() -> VodConfig.get().config(secondConfig));
        click(stale.dialog.findViewById(R.id.save));
        await(() -> !stale.dialog.isShowing() && activity.hasWindowFocus(), "stale editor closes after a configuration switch");
        assertEquals("Rejected Save never restarts source search", 1, changes.get());
        assertEquals(List.of(key("d"), key("b")), value(PosterSourcePrioritySetting::getOrderedKeys));
        assertEquals("Neither configuration is rewritten", beforeRejectedSave, value(() -> Prefers.getPrefers().getAll().get(PREFERENCE)));
        main(() -> VodConfig.get().config(firstConfig));
        assertEquals(List.of(key("a"), key("c")), value(PosterSourcePrioritySetting::getOrderedKeys));
    }

    @Test(timeout = 45_000) public void longPriorityListClipsPartialRowsAndKeepsTheFooterClickable() {
        List<Site> manySites = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            String suffix = "source-" + index;
            Site site = site(suffix, true);
            site.setName("示例播放源 " + index);
            manySites.add(site);
            expected.add(key(suffix));
        }
        manySites.add(site("fallback", true));
        main(() -> {
            setField(VodConfig.class, VodConfig.get(), "sites", manySites);
            PosterSourcePrioritySetting.putOrderedKeys(expected);
            // Keep every screenshot pixel synthetic, including the dialog's transparent corners.
            TextView background = new TextView(activity);
            background.setBackgroundColor(Color.BLACK);
            activity.setContentView(background);
        });
        Editor editor = openEditor();
        RecyclerView list = value(() -> recycler(editor.dialog));
        View save = value(() -> editor.dialog.findViewById(R.id.save));
        View cancel = value(() -> editor.dialog.findViewById(R.id.cancel));
        await(() -> list.getAdapter().getItemCount() == 20 && list.findViewHolderForAdapterPosition(0) != null
                && list.canScrollVertically(1), "twenty priority rows exceed the viewport");
        Rect saveBefore = value(() -> screenBounds(save));
        Rect cancelBefore = value(() -> screenBounds(cancel));
        main(() -> {
            View first = list.findViewHolderForAdapterPosition(0).itemView;
            list.scrollBy(0, first.getHeight() / 2 + list.getPaddingTop());
        });
        await(() -> !list.isLayoutRequested() && !list.isComputingLayout()
                && list.getScrollState() == RecyclerView.SCROLL_STATE_IDLE, "partial-row scroll settles");
        main(() -> {
            Rect viewport = screenBounds(list);
            viewport.left += list.getPaddingLeft();
            viewport.top += list.getPaddingTop();
            viewport.right -= list.getPaddingRight();
            viewport.bottom -= list.getPaddingBottom();
            int partialRows = 0;
            for (int index = 0; index < list.getChildCount(); index++) {
                View row = list.getChildAt(index);
                Rect full = screenBounds(row);
                if (!Rect.intersects(full, viewport)) continue;
                if (full.top < viewport.top || full.bottom > viewport.bottom) partialRows++;
                assertTrue("Scrolled rows cannot draw into the controls or footer", viewport.contains(visibleScreenBounds(row)));
            }
            assertTrue("The fixture must expose actual partly clipped rows", partialRows > 0);
            for (int id : new int[]{R.id.add, R.id.reset}) {
                assertTrue("Header controls stay above the list", screenBounds(editor.dialog.findViewById(id)).bottom <= viewport.top);
            }
            assertTrue("Save remains below the list", screenBounds(save).top >= viewport.bottom);
            assertTrue("Cancel remains below the list", screenBounds(cancel).top >= viewport.bottom);
            assertEquals("Scrolling never moves Save", saveBefore, screenBounds(save));
            assertEquals("Scrolling never moves Cancel", cancelBefore, screenBounds(cancel));
            assertEquals("Save is fully visible", saveBefore, visibleScreenBounds(save));
            assertEquals("Cancel is fully visible", cancelBefore, visibleScreenBounds(cancel));
        });
        captureFixtureDialog(editor.dialog);

        main(() -> ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(19, 0));
        await(() -> {
            RecyclerView.ViewHolder last = list.findViewHolderForAdapterPosition(19);
            return last != null && visible(last.itemView.findViewById(R.id.priorityMoveUp));
        }, "last priority remains reachable by scrolling");
        click(value(() -> list.findViewHolderForAdapterPosition(19).itemView.findViewById(R.id.priorityMoveUp)));
        await(() -> focusedRank(editor.dialog) == 18, "last source moves up without losing its remote focus");
        main(() -> {
            assertEquals(saveBefore, screenBounds(save));
            assertEquals(cancelBefore, screenBounds(cancel));
            assertTrue(visible(save) && visible(cancel));
        });
        Collections.swap(expected, 18, 19);
        tap(save);
        await(() -> !editor.dialog.isShowing() && changes.get() == 1, "a footer tap saves after scrolling the entire list");
        assertEquals(expected, value(PosterSourcePrioritySetting::getOrderedKeys));
        assertSame(manySites, value(() -> VodConfig.get().getSites()));
        assertEquals("The fallback source remains configured", 21, value(() -> VodConfig.get().getSites().size()).intValue());
    }

    private Editor openEditor() {
        Editor editor = value(() -> {
            try {
                // Only obtain the window handle. Every mutation below uses actual visible controls.
                Constructor<PosterSourcePriorityDialog> constructor = PosterSourcePriorityDialog.class
                        .getDeclaredConstructor(FragmentActivity.class, Runnable.class);
                constructor.setAccessible(true);
                Object owner = constructor.newInstance(activity, (Runnable) changes::incrementAndGet);
                AlertDialog dialog = (AlertDialog) field(PosterSourcePriorityDialog.class, owner, "dialog");
                dialogs.add(dialog);
                Method show = PosterSourcePriorityDialog.class.getDeclaredMethod("show");
                show.setAccessible(true);
                show.invoke(owner);
                return new Editor(owner, dialog);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        await(() -> editor.dialog.isShowing() && editor.dialog.getWindow().getDecorView().hasWindowFocus()
                && visible(editor.dialog.findViewById(R.id.save)), "visible editor with reachable footer");
        return editor;
    }

    private void addTwoSources(Editor editor) {
        main(() -> assertTrue(editor.dialog.findViewById(R.id.save).requestFocus()));
        tap(editor.dialog.findViewById(R.id.add)); // First tap works while another button owns focus.
        await(() -> picker(editor) != null && picker(editor).isShowing()
                && picker(editor).getWindow().getDecorView().hasWindowFocus(), "first tap opens the native multi-choice list");
        AlertDialog picker = value(() -> picker(editor));
        ListView choices = value(picker::getListView);
        assertEquals("Only unselected searchable sources can be added", List.of(name("a"), name("c"), name("d")), value(() -> {
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < choices.getAdapter().getCount(); i++) labels.add(choices.getAdapter().getItem(i).toString());
            return labels;
        }));
        for (int index = 0; index < 2; index++) {
            int position = index;
            await(() -> choices.getChildAt(position - choices.getFirstVisiblePosition()) != null,
                    "multi-choice source row is laid out");
            tap(value(() -> choices.getChildAt(position - choices.getFirstVisiblePosition())));
            await(() -> choices.isItemChecked(position), "source checkbox toggled by real touch");
        }
        click(picker.getButton(AlertDialog.BUTTON_POSITIVE));
        await(() -> !picker.isShowing() && editor.dialog.getWindow().getDecorView().hasWindowFocus()
                && recycler(editor.dialog).getAdapter().getItemCount() == 3 && focusedRank(editor.dialog) == 1,
                "selected sources appended with focus on the first added source");
    }

    private AlertDialog picker(Editor editor) {
        return (AlertDialog) field(PosterSourcePriorityDialog.class, editor.owner, "picker");
    }

    private View rowAction(AlertDialog dialog, int position, String source, int action) {
        main(() -> ((LinearLayoutManager) recycler(dialog).getLayoutManager()).scrollToPositionWithOffset(position, 0));
        await(() -> {
            RecyclerView.ViewHolder holder = recycler(dialog).findViewHolderForAdapterPosition(position);
            return holder != null && visible(holder.itemView.findViewById(action))
                    && name(source).contentEquals(((TextView) holder.itemView.findViewById(R.id.name)).getText());
        }, "visible source action at rank " + (position + 1));
        return value(() -> recycler(dialog).findViewHolderForAdapterPosition(position).itemView.findViewById(action));
    }

    private void assertRow(AlertDialog dialog, int position, String source) {
        rowAction(dialog, position, source, R.id.priorityMoveUp);
    }

    private RecyclerView recycler(AlertDialog dialog) { return dialog.findViewById(R.id.recycler); }

    private int focusedRank(AlertDialog dialog) {
        RecyclerView recycler = recycler(dialog);
        View focus = recycler.findFocus();
        RecyclerView.ViewHolder holder = focus == null ? null : recycler.findContainingViewHolder(focus);
        return holder == null ? RecyclerView.NO_POSITION : holder.getBindingAdapterPosition();
    }

    private void click(View view) {
        main(() -> {
            assertTrue("Only click a visible, enabled control", visible(view) && view.isEnabled());
            assertTrue("The real control handles its click", view.performClick());
        });
    }

    private static boolean visible(View view) {
        return view != null && view.isShown() && view.getLocalVisibleRect(new Rect());
    }

    private static Rect screenBounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private static Rect visibleScreenBounds(View view) {
        Rect rect = new Rect();
        assertTrue("The control is visibly laid out", view != null && view.isShown() && view.getLocalVisibleRect(rect));
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        rect.offset(location[0], location[1]);
        return rect;
    }

    private void tap(View view) {
        Rect bounds = value(() -> visibleScreenBounds(view));
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { instrumentation.sendPointerSync(down); }
        finally { down.recycle(); }
        SystemClock.sleep(40);
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { instrumentation.sendPointerSync(up); }
        finally { up.recycle(); }
    }

    /** Optional review evidence; capture/storage failure must not replace the functional assertions. */
    private void captureFixtureDialog(AlertDialog dialog) {
        Bitmap screen = null;
        Bitmap cropped = null;
        File output = null;
        try {
            File directory = instrumentation.getTargetContext().getExternalFilesDir(null);
            if (directory == null) return;
            output = new File(directory, "priority-editor.png");
            if (output.exists() && !output.delete()) return;
            SystemClock.sleep(250); // Let the window's entry animation finish; never wait for global idle.
            screen = instrumentation.getUiAutomation().takeScreenshot();
            if (screen == null) return;
            Rect bounds = value(() -> screenBounds(dialog.getWindow().getDecorView()));
            if (!bounds.intersect(0, 0, screen.getWidth(), screen.getHeight())) return;
            cropped = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height());
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Screenshot encoding failed");
            }
            Log.i("PriorityEditorFixture", "Saved priority-editor.png");
        } catch (IOException | RuntimeException error) {
            if (output != null && output.exists()) output.delete();
            Log.w("PriorityEditorFixture", "Optional screenshot unavailable: " + error.getClass().getSimpleName());
        } finally {
            if (cropped != null && cropped != screen) cropped.recycle();
            if (screen != null) screen.recycle();
        }
    }

    private void press(int code) { instrumentation.sendKeyDownUpSync(code); }

    private void await(BooleanSupplier predicate, String reason) {
        long until = SystemClock.elapsedRealtime() + 7000;
        while (SystemClock.elapsedRealtime() < until) {
            if (value(predicate::getAsBoolean)) return;
            SystemClock.sleep(40);
        }
        fail("Timed out waiting for " + reason);
    }

    private void main(Runnable action) { value(() -> { action.run(); return null; }); }

    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.get()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Main-thread UI operation failed", failure.get());
        return result.get();
    }

    private static Site site(String suffix, boolean searchable) {
        Site site = new Site();
        site.setKey(key(suffix));
        site.setName(name(suffix));
        site.setSearchable(searchable);
        return site;
    }

    private static String key(String suffix) { return "priority-ui-fixture-" + suffix; }
    private static String name(String suffix) { return "Priority fixture " + suffix; }

    private static Object field(Class<?> type, Object owner, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setField(Class<?> type, Object owner, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private record Editor(Object owner, AlertDialog dialog) {}
}
