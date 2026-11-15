package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.TvPreviewActivity;
import com.fongmi.android.tv.ui.dialog.LiveLineDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Real production line panel in an offline preview host; no live source is needed. */
@RunWith(AndroidJUnit4.class)
public final class LiveLineDialogTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final AtomicInteger selections = new AtomicInteger();
    private final AtomicInteger selectedIndex = new AtomicInteger(-1);
    private final AtomicInteger closures = new AtomicInteger();
    private TvPreviewActivity activity;

    @Before public void launchOfflineHost() {
        assertEquals("Run in the isolated preview package", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        activity = (TvPreviewActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), TvPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> activity.showPage("home"));
    }

    @After public void closeHost() {
        if (activity == null) return;
        main(() -> {
            LiveLineDialog.dismiss(activity);
            activity.finish();
        });
    }

    @Test public void shouldHighlightTheCurrentLineAndClickTheExactChoice() {
        main(() -> assertTrue(show(labels(4), 2)));
        await("Current line is focused and highlighted", () -> focusedPosition() == 2 && row(2).isSelected());

        Rect choice = value(() -> {
            Rect bounds = new Rect();
            assertTrue(row(3).getGlobalVisibleRect(bounds));
            return bounds;
        });
        device.click(choice.centerX(), choice.centerY());

        await("Selection closes the actual dialog", () -> dialog() == null && closures.get() == 1);
        assertEquals(1, selections.get());
        assertEquals(3, selectedIndex.get());
    }

    @Test public void shouldCancelWithoutSelectionAndRejectDuplicateDisplays() {
        AtomicBoolean firstShown = new AtomicBoolean();
        AtomicBoolean duplicateShown = new AtomicBoolean();
        main(() -> {
            firstShown.set(show(labels(3), 1));
            duplicateShown.set(show(labels(3), 0));
            assertEquals(1, activity.getSupportFragmentManager().getFragments().stream()
                    .filter(fragment -> fragment instanceof LiveLineDialog).count());
        });
        assertTrue(firstShown.get());
        assertFalse(duplicateShown.get());
        await("Dialog is ready for Back", () -> focusedPosition() == 1);

        device.pressBack();

        await("Back cancels the list", () -> dialog() == null && closures.get() == 1);
        assertEquals(0, selections.get());
        assertEquals(-1, selectedIndex.get());
    }

    @Test public void shouldFocusALateLineAndAcceptDpadSelectionAfterTouch() {
        main(() -> assertTrue(show(labels(60), 40)));
        await("Late current line is scrolled into view", () -> focusedPosition() == 40 && row(40).isSelected());
        main(() -> {
            assertEquals(60, recycler().getAdapter().getItemCount());
            assertTrue("Long lists retain only visible rows", recycler().getChildCount() < 60);
        });
        Rect header = value(() -> {
            Rect bounds = new Rect();
            assertTrue(dialog().getView().findViewById(R.id.title).getGlobalVisibleRect(bounds));
            return bounds;
        });
        // Touch a non-selecting part of the real dialog to enter Android touch mode.
        device.click(header.centerX(), header.centerY());
        instrumentation.waitForIdleSync();
        assertEquals(0, selections.get());
        device.pressDPadDown();
        await("D-pad advances after touch", () -> focusedPosition() == 41);
        device.pressDPadCenter();

        await("D-pad selects the requested late line", () -> dialog() == null && selections.get() == 1);
        assertEquals(41, selectedIndex.get());
    }

    private boolean show(List<String> labels, int current) {
        return LiveLineDialog.show(activity, labels, current, index -> {
            selectedIndex.set(index);
            selections.incrementAndGet();
        }, closures::incrementAndGet);
    }

    private LiveLineDialog dialog() {
        return (LiveLineDialog) activity.getSupportFragmentManager().findFragmentByTag("live-lines");
    }

    private RecyclerView recycler() {
        return dialog().getView().findViewById(R.id.recycler);
    }

    private View row(int position) {
        RecyclerView.ViewHolder holder = recycler().findViewHolderForAdapterPosition(position);
        assertNotNull("Visible line " + position, holder);
        return holder.itemView;
    }

    private int focusedPosition() {
        LiveLineDialog dialog = dialog();
        if (dialog == null || dialog.getDialog() == null || dialog.getView() == null) return -1;
        View focused = dialog.getDialog().getCurrentFocus();
        if (focused == null) return -1;
        RecyclerView.ViewHolder holder = recycler().findContainingViewHolder(focused);
        return holder == null ? -1 : holder.getBindingAdapterPosition();
    }

    private static List<String> labels(int count) {
        List<String> labels = new ArrayList<>();
        for (int index = 1; index <= count; index++) labels.add("线路 " + index);
        return labels;
    }

    private void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    private <T> T value(Supplier<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(supplier.get()));
        return result.get();
    }

    private void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (value(condition::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail(message);
    }
}
