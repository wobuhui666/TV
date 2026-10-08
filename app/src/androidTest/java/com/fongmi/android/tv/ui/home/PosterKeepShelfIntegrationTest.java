package com.fongmi.android.tv.ui.home;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.PosterKeepShelfState;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.activity.DiscoverDetailActivity;
import com.fongmi.android.tv.ui.activity.KeepActivity;
import com.fongmi.android.tv.ui.activity.MyActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Native shelf, real Room records and deliberately delayed reads; never opens a network source. */
@RunWith(AndroidJUnit4.class)
public final class PosterKeepShelfIntegrationTest {

    private static final int FIRST_CID = -910_701;
    private static final int SECOND_CID = -910_702;
    private static final String[] PREFERENCES = {"browse_poster_home", "browse_poster_keep_shelf"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Map<String, Object> preferences = new HashMap<>();
    private final List<Keep> inserted = new ArrayList<>();
    private final List<CountDownLatch> releaseOnCleanup = new ArrayList<>();
    private final List<Instrumentation.ActivityMonitor> monitors = new ArrayList<>();
    private final AtomicInteger focusFallbacks = new AtomicInteger();
    private Config savedConfig;
    private MyActivity activity;
    private PosterKeepShelfController controller;
    private ArrayObjectAdapter page;
    private VerticalGridView rows;
    private Button navigation;
    private boolean prepared;

    @Before
    public void prepare() {
        String packageName = instrumentation.getTargetContext().getPackageName();
        assertTrue("Use an isolated validation application", packageName.equals("com.fongmi.android.tv.preview")
                || packageName.equals("com.fongmi.android.tv.sourceprobe"));
        assertEquals("This shelf belongs to the native TV interface", "leanback", BuildConfig.FLAVOR_mode);
        main(() -> {
            savedConfig = VodConfig.get().getConfig();
            Map<String, ?> previous = Prefers.getPrefers().getAll();
            for (String key : PREFERENCES) if (previous.containsKey(key)) preferences.put(key, previous.get(key));
            prepared = true;
            setConfig(FIRST_CID);
            BrowseExperienceSettings.putPosterHomeEnabled(true);
            BrowseExperienceSettings.putPosterKeepShelfEnabled(true);
        });
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MyActivity.class.getName(), null, false);
        try {
            main(() -> instrumentation.getTargetContext().startActivity(new Intent(instrumentation.getTargetContext(), MyActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
            activity = (MyActivity) instrumentation.waitForMonitorWithTimeout(monitor, 8_000);
            assertNotNull("Native host launched", activity);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        await(activity::hasWindowFocus, "native host window");
    }

    @After
    public void cleanup() {
        if (!prepared) return;
        for (CountDownLatch release : releaseOnCleanup) release.countDown();
        for (Instrumentation.ActivityMonitor monitor : monitors) instrumentation.removeMonitor(monitor);
        try {
            main(() -> {
                if (controller != null) controller.close();
                if (activity != null && !activity.isDestroyed()) activity.finish();
            });
            if (activity != null) await(activity::isDestroyed, "host destroyed before restoring configuration");
        } finally {
            // Only records inserted by this test are removed; pre-existing collections are untouched.
            for (Keep item : inserted) {
                if (item.getType() == Keep.TYPE_LIVE) Keep.delete(item.getKey());
                else item.delete();
            }
            main(() -> {
                VodConfig.get().config(savedConfig);
                SharedPreferences.Editor editor = Prefers.getPrefers().edit();
                for (String key : PREFERENCES) {
                    Object old = preferences.get(key);
                    if (old instanceof Boolean value) editor.putBoolean(key, value);
                    else if (old instanceof String value) editor.putString(key, value);
                    else if (old instanceof Integer value) editor.putInt(key, value);
                    else editor.remove(key);
                }
                assertTrue(editor.commit());
            });
        }
    }

    @Test(timeout = 30_000)
    public void actualRoomRecordsRespectConfigAndDiscoverScopeAndRemoval() {
        Keep first = insert(keep(Keep.TYPE_VOD, FIRST_CID, 30));
        Keep second = insert(keep(Keep.TYPE_VOD, SECOND_CID, 20));
        Keep global = insert(keep(Keep.TYPE_DISCOVER, SECOND_CID, 40));
        mount(cid -> AppDatabase.get().getKeepDao().getPosterShelf(cid, PosterKeepShelfState.MAX_POSTERS + 1));
        await(() -> state().getItems().contains(first) && state().getItems().contains(global), "local and global records loaded");
        main(() -> {
            assertFalse(state().getItems().contains(second));
            assertEquals("The history-row sentinel distance remains unchanged", 2,
                    page.indexOf(R.string.home_recommend) - page.indexOf(R.string.home_history));
            assertTrue(controller.isRow(page.get(page.indexOf(R.string.home_recommend) + 1)));
            setConfig(SECOND_CID);
            controller.refresh();
            assertTrue("Previous config cards disappear synchronously", state().getItems().isEmpty());
        });
        await(() -> state().getItems().contains(second) && state().getItems().contains(global), "second config and global records");
        assertFalse(value(() -> state().getItems().contains(first)));

        second.delete();
        main(controller::refresh);
        await(() -> !state().getItems().contains(second) && state().getItems().contains(global), "cancelled favorite removed by the next read");
        assertNotNull("Switching and reading did not delete the first configuration's record", Keep.find(FIRST_CID, first.getKey()));
    }

    @Test(timeout = 30_000)
    public void roomQueryLimitsEligibleRowsBeforeTheyReachTheUi() {
        long base = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365);
        List<Keep> eligible = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            Keep current = keep(Keep.TYPE_VOD, FIRST_CID, i);
            current.setCreateTime(base + i);
            eligible.add(insert(current));
            Keep foreign = keep(Keep.TYPE_VOD, SECOND_CID, i);
            foreign.setCreateTime(base + 100 + i);
            insert(foreign);
        }
        Keep global = keep(Keep.TYPE_DISCOVER, SECOND_CID, 1);
        global.setCreateTime(base + 50);
        insert(global);
        Keep live = keep(Keep.TYPE_LIVE, FIRST_CID, 2);
        live.setCreateTime(base + 200);
        insert(live);

        List<Keep> rows = AppDatabase.get().getKeepDao().getPosterShelf(FIRST_CID, PosterKeepShelfState.MAX_POSTERS + 1);

        assertEquals("Room returns at most the displayed posters plus the overflow sentinel", 21, rows.size());
        assertEquals(global, rows.get(0));
        assertEquals(eligible.get(21), rows.get(1));
        assertEquals(eligible.get(2), rows.get(20));
        assertTrue(rows.stream().allMatch(item -> item.getType() == Keep.TYPE_DISCOVER
                || item.getType() == Keep.TYPE_VOD && item.getCid() == FIRST_CID));
        assertFalse(rows.contains(live));
    }

    @Test(timeout = 30_000)
    public void failedReadKeepsCardsAndRetryDoesNotStealNavigationFocus() {
        Keep item = keep(Keep.TYPE_VOD, FIRST_CID, 1);
        AtomicBoolean fail = new AtomicBoolean();
        AtomicBoolean backgroundRead = new AtomicBoolean();
        mount(cid -> {
            backgroundRead.set(Looper.myLooper() != Looper.getMainLooper());
            if (fail.get()) throw new IllegalStateException("Intentional collection read failure");
            return List.of(item);
        });
        await(() -> state().getItems().size() == 1, "initial local card");
        focusCard(0);
        main(() -> {
            navigation.requestFocus();
            fail.set(true);
            controller.refresh();
        });
        await(() -> state().hasFailed() && visible(R.id.keepFailure), "recoverable read error");
        main(() -> {
            assertEquals(List.of(item), state().getItems());
            assertTrue(navigation.hasFocus());
            fail.set(false);
            assertTrue(activity.findViewById(R.id.keepRetry).performClick());
        });
        await(() -> !state().hasFailed(), "retry recovered");
        assertTrue(value(navigation::hasFocus));
        assertTrue("The query ran off the UI thread", backgroundRead.get());
        assertEquals(0, focusFallbacks.get());
    }

    @Test(timeout = 30_000)
    public void deletingTheLastCardCollapsesTheRowAndRecoversFocus() {
        AtomicReference<List<Keep>> saved = new AtomicReference<>(List.of(keep(Keep.TYPE_VOD, FIRST_CID, 1)));
        mount(cid -> saved.get());
        await(() -> state().getItems().size() == 1, "initial collection");
        focusCard(0);
        main(() -> {
            saved.set(List.of());
            controller.refresh();
        });
        await(() -> !state().isVisible() && navigation.hasFocus(), "empty row hands focus back to navigation");
        assertEquals(1, focusFallbacks.get());
        assertFalse(value(() -> visible(R.id.keepPosters)));
        assertFalse(value(controller::isFocusable));
    }

    @Test(timeout = 30_000)
    public void cancelledBlockedReadCannotReintroduceAnotherConfiguration() throws Exception {
        Keep first = keep(Keep.TYPE_VOD, FIRST_CID, 1);
        Keep second = keep(Keep.TYPE_VOD, SECOND_CID, 2);
        Keep global = keep(Keep.TYPE_DISCOVER, FIRST_CID, 3);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        releaseOnCleanup.add(release);
        AtomicInteger reads = new AtomicInteger();
        mount(cid -> {
            if (reads.incrementAndGet() == 1) {
                entered.countDown();
                awaitIgnoringInterrupt(release);
                return List.of(first, global);
            }
            return List.of(second, global);
        });
        assertTrue("First DB read is running", entered.await(5, TimeUnit.SECONDS));
        main(() -> {
            setConfig(SECOND_CID);
            controller.refresh();
            controller.refresh();
            assertTrue(state().getItems().isEmpty());
        });
        release.countDown();
        await(() -> state().getItems().equals(List.of(global, second)), "only the newest request is applied");
        assertEquals("Superseded queued reads were removed", 2, reads.get());
        assertFalse(value(() -> state().getItems().contains(first)));
    }

    @Test(timeout = 30_000)
    public void disablingAndPausingRejectLateReadsWithoutDeletingFavorites() throws Exception {
        Keep item = insert(keep(Keep.TYPE_VOD, FIRST_CID, 1));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        releaseOnCleanup.add(release);
        AtomicInteger reads = new AtomicInteger();
        mount(cid -> {
            Keep saved = Keep.find(cid, item.getKey());
            if (reads.incrementAndGet() == 2) {
                entered.countDown();
                awaitIgnoringInterrupt(release);
                finished.countDown();
            }
            return saved == null ? List.of() : List.of(saved);
        });
        await(() -> state().getItems().size() == 1, "stored favorite");
        main(controller::refresh);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        main(() -> {
            BrowseExperienceSettings.putPosterKeepShelfEnabled(false);
            controller.refresh();
            assertFalse(state().isVisible());
            controller.pause();
            controller.refresh();
        });
        release.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        assertFalse(value(() -> state().isVisible()));
        assertNotNull("Turning off a shelf never deletes its saved record", Keep.find(FIRST_CID, item.getKey()));
        assertEquals("Disabled/background refreshes did not start another read", 2, reads.get());
        main(() -> {
            BrowseExperienceSettings.putPosterKeepShelfEnabled(true);
            controller.resume();
        });
        await(() -> state().getItems().size() == 1, "favorite returns after re-enabling");
    }

    @Test(timeout = 30_000)
    public void nativeCardActionsRouteToTheirExistingScreensAndOverflowHasViewAll() {
        List<Keep> saved = new ArrayList<>();
        for (int i = 0; i < 21; i++) saved.add(keep(Keep.TYPE_VOD, FIRST_CID, i));
        saved.add(keep(Keep.TYPE_DISCOVER, SECOND_CID, 30));
        mount(cid -> saved);
        await(() -> state().getItems().size() == 20 && state().hasMore(), "bounded shelf with overflow");
        Instrumentation.ActivityMonitor discovery = block(DiscoverDetailActivity.class);
        Instrumentation.ActivityMonitor video = block(VideoActivity.class);
        Instrumentation.ActivityMonitor all = block(KeepActivity.class);
        focusCard(0);
        NativeUiEvidence.capture("poster-keep-shelf");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> discovery.getHits() == 1, "discovery favorite opens native discovery detail");
        focusCard(1);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> video.getHits() == 1, "current-source favorite opens the existing video screen");
        main(() -> {
            View more = activity.findViewById(R.id.keepMore);
            assertTrue(more.isShown());
            assertTrue(more.requestFocus());
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> all.getHits() == 1, "view all opens the existing collection screen");
    }

    private void mount(IntFunction<List<Keep>> readKeeps) {
        main(() -> {
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            navigation = new Button(activity);
            navigation.setText("Navigation");
            navigation.setFocusableInTouchMode(true);
            root.addView(navigation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64));
            rows = new VerticalGridView(activity);
            root.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            CustomSelector selector = new CustomSelector();
            selector.addPresenter(Integer.class, new MarkerPresenter(false));
            selector.addPresenter(String.class, new MarkerPresenter(true));
            controller = new PosterKeepShelfController(activity, readKeeps);
            controller.register(selector);
            page = new ArrayObjectAdapter(selector);
            page.add(R.string.home_history);
            page.add("Continue watching");
            page.add(R.string.home_recommend);
            controller.attach(page);
            rows.setAdapter(new ItemBridgeAdapter(page));
            rows.setItemAnimator(null);
            controller.setFocusFallback(() -> {
                focusFallbacks.incrementAndGet();
                navigation.requestFocus();
            });
            activity.setContentView(root);
            navigation.requestFocus();
            controller.resume();
        });
    }

    private void focusCard(int position) {
        main(() -> {
            rows.setSelectedPosition(page.indexOf(R.string.home_recommend) + 1);
            rows.requestFocus();
        });
        await(() -> visible(R.id.keepPosters), "poster grid attached");
        main(() -> {
            HorizontalGridView posters = activity.findViewById(R.id.keepPosters);
            posters.setSelectedPosition(position);
            posters.requestFocus();
        });
        await(() -> ((HorizontalGridView) activity.findViewById(R.id.keepPosters)).findViewHolderForAdapterPosition(position) != null,
                "selected native card laid out");
        main(() -> {
            HorizontalGridView posters = activity.findViewById(R.id.keepPosters);
            RecyclerView.ViewHolder holder = posters.findViewHolderForAdapterPosition(position);
            assertNotNull(holder);
            assertTrue(holder.itemView.requestFocus());
        });
        await(() -> activity.findViewById(R.id.keepPosters).hasFocus(), "card focus");
    }

    private Instrumentation.ActivityMonitor block(Class<? extends Activity> target) {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(target.getName(),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        monitors.add(monitor);
        return monitor;
    }

    private PosterKeepShelfState state() {
        try {
            Field field = PosterKeepShelfController.class.getDeclaredField("state");
            field.setAccessible(true);
            return (PosterKeepShelfState) field.get(controller);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private boolean visible(int id) {
        View view = activity.findViewById(id);
        return view != null && view.isShown();
    }

    private void setConfig(int cid) {
        Config config = new Config().url("https://keep-shelf-fixture.invalid/" + UUID.randomUUID());
        config.setId(cid);
        VodConfig.get().config(config);
    }

    private Keep insert(Keep keep) {
        keep.save();
        inserted.add(keep);
        return keep;
    }

    private Keep keep(int type, int cid, int order) {
        Keep keep = new Keep();
        String unique = UUID.randomUUID().toString();
        keep.setKey(type == Keep.TYPE_DISCOVER ? "tmdb:movie:" + (9_000_000_000L + Math.abs((long) unique.hashCode()))
                : "keep-fixture-" + unique + AppDatabase.SYMBOL + "fixture-id");
        keep.setType(type);
        keep.setCid(cid);
        keep.setCreateTime(System.currentTimeMillis() + 10_000 + order * 1_000L);
        keep.setVodName("Saved poster " + order);
        keep.setVodPic("");
        keep.setSiteName("Local fixture");
        return keep;
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        long end = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < end) {
            try { if (latch.await(Math.max(1, end - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS)) return; }
            catch (InterruptedException ignored) { }
        }
        throw new IllegalStateException("Timed out waiting for delayed-read fixture release");
    }

    private void await(BooleanSupplier condition, String description) {
        long end = SystemClock.uptimeMillis() + 8_000;
        while (SystemClock.uptimeMillis() < end) {
            if (value(condition::getAsBoolean)) return;
            SystemClock.sleep(40);
        }
        fail("Timed out: " + description);
    }

    private <T> T value(Supplier<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        main(() -> result.set(supplier.get()));
        return result.get();
    }

    private void main(Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { task.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class MarkerPresenter extends Presenter {
        private final boolean focusable;

        MarkerPresenter(boolean focusable) { this.focusable = focusable; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
            TextView view = new TextView(parent.getContext());
            view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, focusable ? 48 : 0));
            view.setFocusable(focusable);
            view.setFocusableInTouchMode(focusable);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, Object item) {
            ((TextView) holder.view).setText(focusable ? item.toString() : "");
        }

        @Override
        public void onUnbindViewHolder(@NonNull ViewHolder holder) {
        }
    }
}
