package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.playback.live.LiveLineSelection;
import com.fongmi.android.tv.playback.live.LivePlayRequest;
import com.fongmi.android.tv.playback.live.LivePlaybackController;
import com.fongmi.android.tv.playback.live.LivePlaybackHost;
import com.fongmi.android.tv.playback.live.LivePlaybackState;
import com.fongmi.android.tv.ui.activity.TvPreviewActivity;
import com.fongmi.android.tv.ui.dialog.LiveLineDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.ZoneId;
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
    private final AtomicInteger playbackRefreshes = new AtomicInteger();
    private Channel playbackChannel;
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
        await("Current line is focused and highlighted", () -> focusedPosition() == 2 && row(2).isActivated());
        recordState("exact-choice-ready");

        Rect choice = value(() -> {
            Rect bounds = new Rect();
            assertTrue(row(3).getGlobalVisibleRect(bounds));
            return bounds;
        });
        device.click(choice.centerX(), choice.centerY());

        await("Selection closes the actual dialog", () -> dialog() == null && closures.get() == 1);
        recordState("exact-choice-closed");
        assertEquals(1, selections.get());
        assertEquals(3, selectedIndex.get());
        main(() -> assertEquals(3, playbackChannel.getIndex()));
        assertEquals(1, playbackRefreshes.get());
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
        recordState("cancel-ready");

        device.pressBack();

        await("Back cancels the list", () -> dialog() == null && closures.get() == 1);
        recordState("cancel-closed");
        assertEquals(0, selections.get());
        assertEquals(-1, selectedIndex.get());
        assertPlayingLineUnchanged(1);
    }

    @Test public void shouldFocusALateLineAndAcceptDpadSelectionAfterTouch() {
        main(() -> assertTrue(show(labels(60), 40)));
        await("Late current line is scrolled into view", () -> focusedPosition() == 40 && row(40).isActivated());
        recordState("late-line-ready");
        NativeUiEvidence.capture("live-lines-current");
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
        FocusState touched = recordState("after-header-touch");
        NativeUiEvidence.capture("live-lines-touched");
        assertTrue("An internal header touch keeps the list open", touched.dialogPresent);
        assertTrue("Real touch enters Android touch mode", touched.inTouchMode);
        assertTrue("Touch retains the current line's highlight", touched.line40Activated);
        assertFalse("Touch does not mark the next line active", touched.line41Activated);
        assertEquals(0, selections.get());
        assertPlayingLineUnchanged(40);
        device.pressDPadDown();
        await("First D-pad leaves touch mode with the same list focused", () -> dialog() != null
                && dialog().getView() != null && !dialog().getView().isInTouchMode()
                && (focusedPosition() == 40 || focusedPosition() == 41));
        FocusState firstDown = recordState("after-first-down");
        NativeUiEvidence.capture("live-lines-first-down");
        assertTrue("The first remote key keeps the list open", firstDown.dialogPresent);
        assertFalse("The first remote key leaves touch mode", firstDown.inTouchMode);
        assertTrue("Remote focus retains the active playing line", firstDown.line40Activated);
        assertFalse("Remote focus does not activate another line", firstDown.line41Activated);
        assertEquals(0, selections.get());
        assertPlayingLineUnchanged(40);
        if (firstDown.focusedPosition == 40) {
            // Android may consume the first navigation key when leaving touch mode.
            // Verify the restored position before sending the next real key event.
            assertEquals("First D-pad restores the current line", 40, firstDown.focusedPosition);
            device.pressDPadDown();
            await("Second D-pad advances from restored line 40 to 41", () -> focusedPosition() == 41);
            recordState("after-second-down");
            NativeUiEvidence.capture("live-lines-second-down");
        } else {
            assertEquals("A platform that advances immediately reaches line 41", 41, firstDown.focusedPosition);
        }
        main(() -> {
            assertEquals("The exact next choice is focused before confirming", 41, focusedPosition());
            assertTrue("The playing line remains highlighted until confirmation", row(40).isActivated());
            assertFalse("The focused choice is not the playing line yet", row(41).isActivated());
        });
        assertEquals(0, selections.get());
        assertPlayingLineUnchanged(40);
        device.pressDPadCenter();

        await("D-pad selects the requested late line", () -> dialog() == null && selections.get() == 1);
        recordState("late-line-confirmed");
        assertEquals(41, selectedIndex.get());
        assertEquals(1, closures.get());
        main(() -> assertEquals("Confirmation selects the exact channel line", 41, playbackChannel.getIndex()));
        assertEquals("Confirmation refreshes playback once", 1, playbackRefreshes.get());
    }

    private boolean show(List<String> labels, int current) {
        Channel channel = Channel.create("Offline preview channel");
        List<String> urls = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) urls.add("fixture://line/" + index + "$" + labels.get(index));
        channel.setUrls(urls);
        channel.setIndex(current);
        LivePlaybackState state = new LivePlaybackState();
        state.setChannel(channel);
        LivePlaybackController controller = new LivePlaybackController(new OfflineHost(), state) {
            @Override public void refresh() { playbackRefreshes.incrementAndGet(); }
        };
        LiveLineSelection snapshot = controller.getLineSelection();
        boolean shown = LiveLineDialog.show(activity, snapshot.labels(number -> "Line " + number), snapshot.getSelectedIndex(), index -> {
            controller.selectLine(snapshot, index);
            selectedIndex.set(index);
            selections.incrementAndGet();
        }, closures::incrementAndGet);
        if (shown) playbackChannel = channel;
        return shown;
    }

    private void assertPlayingLineUnchanged(int expected) {
        main(() -> assertEquals("Focus and touch do not select a playback line", expected, playbackChannel.getIndex()));
        assertEquals("Focus and touch do not refresh playback", 0, playbackRefreshes.get());
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

    private FocusState recordState(String step) {
        FocusState state = value(() -> {
            LiveLineDialog current = dialog();
            View root = current == null ? null : current.getView();
            boolean present = current != null && current.getDialog() != null && current.getDialog().isShowing() && root != null;
            View modeOwner = root == null ? activity.getWindow().getDecorView() : root;
            FocusState snapshot = new FocusState(present, modeOwner.isInTouchMode(), focusedPosition(), isSelected(40), isSelected(41), isActivated(40), isActivated(41));
            Log.i("LiveLineDialogTest", "step=" + step + " dialogPresent=" + snapshot.dialogPresent
                    + " focusedPosition=" + snapshot.focusedPosition + " inTouchMode=" + snapshot.inTouchMode
                    + " line40Selected=" + snapshot.line40Selected + " line41Selected=" + snapshot.line41Selected
                    + " line40Activated=" + snapshot.line40Activated + " line41Activated=" + snapshot.line41Activated
                    + " selectionCallbacks=" + selections.get() + " selectedIndex=" + selectedIndex.get()
                    + " playingIndex=" + (playbackChannel == null ? -1 : playbackChannel.getIndex())
                    + " playbackRefreshes=" + playbackRefreshes.get() + " closures=" + closures.get());
            return snapshot;
        });
        return state;
    }

    private boolean isSelected(int position) {
        if (dialog() == null || dialog().getView() == null) return false;
        RecyclerView.ViewHolder holder = recycler().findViewHolderForAdapterPosition(position);
        return holder != null && holder.itemView.isSelected();
    }

    private boolean isActivated(int position) {
        if (dialog() == null || dialog().getView() == null) return false;
        RecyclerView.ViewHolder holder = recycler().findViewHolderForAdapterPosition(position);
        return holder != null && holder.itemView.isActivated();
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
        recordState("timeout: " + message);
        NativeUiEvidence.capture("live-lines-timeout");
        fail(message);
    }

    private static final class FocusState {
        final boolean dialogPresent;
        final boolean inTouchMode;
        final int focusedPosition;
        final boolean line40Selected;
        final boolean line41Selected;
        final boolean line40Activated;
        final boolean line41Activated;

        FocusState(boolean present, boolean touchMode, int position, boolean selected40, boolean selected41, boolean active40, boolean active41) {
            dialogPresent = present;
            inTouchMode = touchMode;
            focusedPosition = position;
            line40Selected = selected40;
            line41Selected = selected41;
            line40Activated = active40;
            line41Activated = active41;
        }
    }

    private static final class OfflineHost implements LivePlaybackHost {
        @Override public int getGroupCount() { return 0; }
        @Override public int getGroupPosition() { return 0; }
        @Override public Group getGroup(int position) { return null; }
        @Override public boolean isPlayerLive() { return true; }
        @Override public ZoneId getZoneId() { return ZoneId.of("UTC"); }
        @Override public void requestUrl(LivePlayRequest request) { }
        @Override public void requestCatchupUrl(LivePlayRequest request) { }
        @Override public void stopPlaybackForRefresh() { }
        @Override public void startPlayback(Result result, long position, Channel channel) { }
        @Override public void resetPlaybackForError(String message) { }
        @Override public void renderGroupSelection(Group group) { }
        @Override public void renderGroupChannels(Group group) { }
        @Override public void renderChannelSelection(Channel channel) { }
        @Override public void renderLineSelection(Channel channel, boolean show) { }
        @Override public void renderEpgSelection(Channel channel, EpgData data) { }
        @Override public void showCatchupReady(Channel channel, EpgData data) { }
        @Override public void showProgress() { }
    }
}
