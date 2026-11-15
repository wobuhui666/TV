package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.ui.activity.HomePreviewFixtures;
import com.fongmi.android.tv.ui.activity.TvHomePreviewActivity;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Touch scrolling must survive callbacks which still see the TV toolbar as focused. */
@RunWith(AndroidJUnit4.class)
public final class HomeTouchScrollTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private HomePreviewFixtures.Session fixture;
    private TvHomePreviewActivity home;
    private Object savedMode;

    @Before public void open() {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        main(() -> {
            savedMode = Prefers.getPrefers().getAll().get("tv_experience_mode_v1");
            ExperienceSettings.putMode(ExperienceMode.LIBRARY);
            fixture = HomePreviewFixtures.openCompact(instrumentation.getTargetContext());
        });
        home = (TvHomePreviewActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), TvHomePreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("home fixture finishes loading", () -> home.hasWindowFocus()
                && ((Result) HomePreviewFixtures.field(home, "mResult")).getTypes().size() == 2);
        main(home::queueNavRestore);
        await("navigation starts focused", () -> home.findViewById(R.id.nav).hasFocus());
    }

    @After public void close() {
        if (home != null) main(home::finish);
        if (home != null) await("home closes", home::isDestroyed);
        if (fixture != null) main(fixture::close);
        main(() -> {
            if (savedMode == null) Prefers.remove("tv_experience_mode_v1");
            else Prefers.put("tv_experience_mode_v1", (String) savedMode);
        });
    }

    @Test public void shouldKeepScrolledViewportAfterHistoryRefreshAndWindowFocusReturns() {
        swipeContent();
        main(() -> assertTrue("A drag can retain the TV navigation focus", home.findViewById(R.id.nav).hasFocus()));
        Viewport[] before = new Viewport[1];
        AlertDialog[] dialog = new AlertDialog[1];
        main(() -> {
            before[0] = viewport();
            home.replaceHistory(fixture.histories);
            home.onRefresh();
            dialog[0] = new MaterialAlertDialogBuilder(home).setMessage("Window focus fixture")
                    .setPositiveButton(android.R.string.ok, null).show();
        });
        await("dialog receives window focus", () -> !home.hasWindowFocus());
        main(dialog[0]::dismiss);
        await("home receives window focus again", home::hasWindowFocus);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP);
        main(() -> home.replaceHistory(fixture.histories));
        settle();
        main(() -> assertViewport(before[0]));
    }

    @Test public void shouldCancelQueuedContentFocusWhenTouchStarts() {
        main(() -> {
            Rect bounds = contentBounds();
            home.focusRow(home.sourcePosition());
            dispatchTouch(MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY());
            dispatchTouch(MotionEvent.ACTION_CANCEL, bounds.centerX(), bounds.centerY());
        });
        settle();
        main(() -> assertTrue("Queued row focus cannot steal a new touch stream",
                home.findViewById(R.id.nav).hasFocus()));
        swipeContent();
        Viewport[] before = new Viewport[1];
        main(() -> {
            before[0] = viewport();
            home.replaceHistory(fixture.histories);
        });
        settle();
        main(() -> assertViewport(before[0]));
    }

    @Test public void shouldRestoreRemoteAndExplicitHomeNavigationAfterTouchScrolling() {
        swipeContent();
        device.pressDPadDown();
        await("D-pad resumes navigation from the toolbar", () -> home.pageGrid().hasFocus());
        device.pressBack();
        await("Back restores top navigation", () -> home.findViewById(R.id.nav).hasFocus()
                && !home.pageGrid().canScrollVertically(-1));
        swipeContent();
        main(() -> home.onNavClick(String.valueOf(R.string.home_tab_home)));
        await("explicit Home selects the first content row", () -> home.pageGrid().hasFocus()
                && home.pageGrid().getSelectedPosition() == 0);
        device.pressBack();
        await("Back restores navigation again", () -> home.findViewById(R.id.nav).hasFocus());
        swipeContent();
        device.pressBack();
        await("Back from a touch scrolled list returns to top before exiting", () -> !home.isFinishing()
                && !home.pageGrid().canScrollVertically(-1) && home.findViewById(R.id.nav).hasFocus());
    }

    private void swipeContent() {
        Rect[] bounds = new Rect[1];
        main(() -> bounds[0] = contentBounds());
        int start = bounds[0].top + bounds[0].height() * 3 / 4;
        int end = bounds[0].top + bounds[0].height() / 3;
        assertTrue(device.swipe(bounds[0].centerX(), start, bounds[0].centerX(), end, 32));
        await("touch scroll moves below the top", () -> home.pageGrid().canScrollVertically(-1)
                && home.pageGrid().getScrollState() == RecyclerView.SCROLL_STATE_IDLE);
        settle();
    }

    private Rect contentBounds() {
        Rect bounds = new Rect();
        assertTrue(home.pageGrid().getGlobalVisibleRect(bounds));
        bounds.top = Math.max(bounds.top, home.toolbar().getBottom());
        return bounds;
    }

    private Viewport viewport() {
        RecyclerView grid = home.pageGrid();
        View first = null;
        for (int index = 0; index < grid.getChildCount(); index++) {
            View child = grid.getChildAt(index);
            if (child.getBottom() > 0 && child.getTop() < grid.getHeight()
                    && (first == null || child.getTop() < first.getTop())) first = child;
        }
        assertNotNull("A visible home row", first);
        int position = grid.getChildAdapterPosition(first);
        assertTrue(position >= 0);
        return new Viewport(home.pageAdapter().get(position), first.getTop());
    }

    private void assertViewport(Viewport before) {
        assertTrue("Late callbacks keep the list below the top", home.pageGrid().canScrollVertically(-1));
        int position = home.pageAdapter().indexOf(before.row);
        RecyclerView.ViewHolder holder = home.pageGrid().findViewHolderForAdapterPosition(position);
        assertNotNull("The same row remains visible", holder);
        assertEquals("The visible row keeps its scroll offset", before.top, holder.itemView.getTop(), 2);
    }

    private void dispatchTouch(int action, int x, int y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { home.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private void settle() {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(200);
        instrumentation.waitForIdleSync();
    }

    private void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    private void await(String description, BooleanSupplier ready) {
        long deadline = SystemClock.uptimeMillis() + 8000;
        AtomicBoolean complete = new AtomicBoolean();
        do {
            main(() -> complete.set(ready.getAsBoolean()));
            if (complete.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(description);
    }

    private static final class Viewport {
        final Object row;
        final int top;
        Viewport(Object row, int top) { this.row = row; this.top = top; }
    }
}
