package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Touch scrolling must survive callbacks which still see the TV toolbar as focused. */
@RunWith(AndroidJUnit4.class)
public final class HomeTouchScrollTest {
    @Rule public final TestName testName = new TestName();
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
        await("the real hero is measured before input starts", () -> {
            RecyclerView.ViewHolder first = home.pageGrid().findViewHolderForAdapterPosition(0);
            return first != null && first.itemView.getHeight() > 100 && first.itemView.getBottom() > 0;
        });
        main(home::queueNavRestore);
        await("navigation starts focused", () -> home.findViewById(R.id.nav).hasFocus());
        settle();
        main(() -> dumpState("ready"));
    }

    @After public void close() {
        if (home != null && !home.isDestroyed()) {
            main(() -> dumpState("before-close"));
            NativeUiEvidence.capture("home-touch-" + Integer.toHexString(testName.getMethodName().hashCode()));
        }
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
        Viewport[] before = new Viewport[1];
        AlertDialog[] dialog = new AlertDialog[1];
        main(() -> {
            before[0] = viewport();
            home.replaceHistory(fixture.histories);
            home.onRefresh();
            dumpState("late-history-and-source-refresh");
            dialog[0] = new MaterialAlertDialogBuilder(home).setMessage("Window focus fixture")
                    .setPositiveButton(android.R.string.ok, null).show();
        });
        await("dialog receives window focus", () -> !home.hasWindowFocus());
        main(dialog[0]::dismiss);
        await("home receives window focus again", home::hasWindowFocus);
        main(() -> dumpState("window-focus-returned"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP);
        main(() -> home.replaceHistory(fixture.histories));
        settle();
        main(() -> assertViewport(before[0]));
    }

    @Test public void shouldCancelQueuedContentFocusWhenTouchStarts() {
        Viewport[] beforeTouch = new Viewport[1];
        int[] queuedPosition = new int[1];
        main(() -> {
            Rect bounds = contentBounds();
            beforeTouch[0] = viewport();
            queuedPosition[0] = home.sourcePosition();
            assertTrue(queuedPosition[0] > home.pageGrid().getSelectedPosition());
            home.focusRow(queuedPosition[0]);
            // The padding gutter accepts the parent's stream without focusing a card on DOWN.
            dispatchTouch(MotionEvent.ACTION_DOWN, bounds.right - 4, bounds.centerY());
            dispatchTouch(MotionEvent.ACTION_CANCEL, bounds.right - 4, bounds.centerY());
            dumpState("content-touch-cancels-queued-focus");
        });
        settle();
        main(() -> {
            assertNotEquals("The queued source row cannot steal a new touch stream",
                    queuedPosition[0], home.pageGrid().getSelectedPosition());
            assertRowOffset(beforeTouch[0]);
            dumpState("queued-focus-stays-cancelled");
        });
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
        main(() -> dumpState("before-first-dpad-down"));
        device.pressDPadDown();
        settle();
        await("the first D-pad key restores a home focus target", () -> !home.isFinishing()
                && (home.findViewById(R.id.nav).hasFocus() || home.pageGrid().hasFocus()));
        boolean[] navigationRestored = new boolean[1];
        main(() -> {
            dumpState("after-first-dpad-down");
            navigationRestored[0] = home.findViewById(R.id.nav).hasFocus() && !home.pageGrid().hasFocus();
            if (navigationRestored[0]) {
                assertFalse("Leaving touch mode keeps the home page open", home.isFinishing());
                assertFalse("The first D-pad key leaves touch mode", home.pageGrid().isInTouchMode());
            }
        });
        if (navigationRestored[0]) {
            device.pressDPadDown();
            settle();
            main(() -> dumpState("after-second-dpad-down"));
        }
        await("D-pad resumes navigation from the toolbar", () -> home.pageGrid().hasFocus());
        device.pressBack();
        await("Back restores top navigation", () -> home.findViewById(R.id.nav).hasFocus()
                && !isBelowTop());
        swipeContent();
        main(() -> home.onNavClick(String.valueOf(R.string.home_tab_home)));
        await("explicit Home selects the first content row", () -> home.pageGrid().hasFocus()
                && home.pageGrid().getSelectedPosition() == 0);
        device.pressBack();
        await("Back restores navigation again", () -> home.findViewById(R.id.nav).hasFocus());
        swipeContent();
        device.pressBack();
        await("Back from a touch scrolled list returns to top before exiting", () -> !home.isFinishing()
                && !isBelowTop() && home.findViewById(R.id.nav).hasFocus());
    }

    private void swipeContent() {
        Rect[] bounds = new Rect[1];
        main(() -> bounds[0] = contentBounds());
        int start = bounds[0].top + bounds[0].height() * 3 / 4;
        int end = bounds[0].top + bounds[0].height() / 3;
        main(() -> dumpState("before-swipe " + bounds[0]));
        assertTrue(device.swipe(bounds[0].centerX(), start, bounds[0].centerX(), end, 32));
        await("touch scroll moves below the top", () -> isBelowTop()
                && home.pageGrid().getScrollState() == RecyclerView.SCROLL_STATE_IDLE);
        settle();
        main(() -> dumpState("after-swipe"));
    }

    private Rect contentBounds() {
        Rect bounds = new Rect();
        assertTrue(home.pageGrid().getGlobalVisibleRect(bounds));
        Rect toolbar = new Rect();
        if (home.toolbar().getGlobalVisibleRect(toolbar)) bounds.top = Math.max(bounds.top, toolbar.bottom);
        return bounds;
    }

    private boolean isBelowTop() {
        Viewport first = viewport();
        return home.pageAdapter().indexOf(first.row) > 0 || first.top < home.pageGrid().getPaddingTop() - 2;
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
        dumpState("verify-preserved-viewport");
        assertTrue("Late callbacks keep the list below the top", isBelowTop());
        assertRowOffset(before);
    }

    private void assertRowOffset(Viewport before) {
        int position = home.pageAdapter().indexOf(before.row);
        RecyclerView.ViewHolder holder = home.pageGrid().findViewHolderForAdapterPosition(position);
        assertNotNull("The same row remains visible", holder);
        assertEquals("The visible row keeps its scroll offset", before.top, holder.itemView.getTop(), 2);
    }

    private void dispatchTouch(int action, int x, int y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        int[] origin = new int[2];
        home.getWindow().getDecorView().getLocationOnScreen(origin);
        event.offsetLocation(-origin[0], -origin[1]);
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
        main(() -> dumpState("timeout: " + description));
        fail(description);
    }

    private void dumpState(String step) {
        RecyclerView grid = home.pageGrid();
        StringBuilder state = new StringBuilder(step).append(" windowFocus=").append(home.hasWindowFocus())
                .append(" navFocus=").append(home.findViewById(R.id.nav).hasFocus())
                .append(" contentFocus=").append(grid.hasFocus())
                .append(" touchMode=").append(grid.isInTouchMode())
                .append(" touchBrowsing=").append(HomePreviewFixtures.field(home, "mTouchBrowsing"))
                .append(" selected=").append(home.pageGrid().getSelectedPosition())
                .append(" focusScrollStrategy=").append(home.pageGrid().getFocusScrollStrategy())
                .append(" scrollState=").append(grid.getScrollState())
                .append(" rows=").append(home.pageAdapter().size()).append(" visible=[");
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            state.append(grid.getChildAdapterPosition(child)).append(':').append(child.getTop()).append("..").append(child.getBottom())
                    .append(child.hasFocus() ? "*" : "").append(',');
        }
        Log.i("HomeTouchScrollTest", state.append(']').toString());
    }

    private static final class Viewport {
        final Object row;
        final int top;
        Viewport(Object row, int top) { this.row = row; this.top = top; }
    }
}
