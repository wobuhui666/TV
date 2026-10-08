package com.fongmi.android.tv.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.VerticalGridView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.WatchHistoryActivity;
import com.fongmi.android.tv.ui.custom.JetStreamPageProgressLayout;
import com.fongmi.android.tv.ui.dialog.HistoryActionsDialog;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.utils.HistoryTaskQueue;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.android.material.button.MaterialButton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

/** Native card menus and real Room transactions, restricted to disposable validation applications. */
@RunWith(AndroidJUnit4.class)
public final class HistoryActionsIntegrationTest {
    private static final String[] PREFERENCES = {"browse_history_actions", "browse_poster_home"};
    private static final String[] VOD_FIELDS = {"home", "wall", "parse", "doh", "rules", "sites", "ads", "flags", "parses"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<Activity> launched = new ArrayList<>();
    private final List<Activity> previousActivities = new ArrayList<>();
    private final List<String> fixtureKeys = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final Map<String, Object> preferences = new HashMap<>();
    private final Map<String, Object> vodState = new HashMap<>();
    private final String sourceKey = "history-actions-" + UUID.randomUUID();
    private final int cid = -1_000_000_000 - (UUID.randomUUID().hashCode() & 0x0ffffffe);
    private final int otherCid = cid - 1;
    private Config configuration;
    private Config otherConfiguration;
    private Config previousConfiguration;
    private Site fixtureSite;
    private OkHttpClient previousClient;
    private OkHttpClient previousTmdbClient;
    private OkHttpClient offlineClient;
    private boolean prepared;

    @Before public void setup() {
        String packageName = instrumentation.getTargetContext().getPackageName();
        assertTrue("Use an isolated validation application", packageName.equals("com.fongmi.android.tv.preview")
                || packageName.equals("com.fongmi.android.tv.sourceprobe"));
        assertEquals("History menus are a TV feature", "leanback", BuildConfig.FLAVOR_mode);
        main(() -> {
            assertTrue(AppDatabase.get().getHistoryDao().findAll(cid).isEmpty());
            assertTrue(AppDatabase.get().getHistoryDao().findAll(otherCid).isEmpty());
            previousActivities.addAll(validationActivities());
            Map<String, ?> existing = Prefers.getPrefers().getAll();
            for (String key : PREFERENCES) if (existing.containsKey(key)) preferences.put(key, existing.get(key));
            previousConfiguration = VodConfig.get().getConfig();
            for (String name : VOD_FIELDS) vodState.put(name, field(VodConfig.get(), name));
            configuration = config(cid);
            otherConfiguration = config(otherCid);
            fixtureSite = Site.objectFrom("{\"key\":\"" + sourceKey + "\",\"name\":\"History test source\",\"searchable\":0}");
            useConfiguration(configuration);
            previousClient = OkHttp.client();
            previousTmdbClient = (OkHttpClient) field(TmdbNetwork.class, "client");
            offlineClient = new OkHttpClient.Builder().addInterceptor(chain -> {
                throw new IOException("Intentional offline history fixture");
            }).build();
            setField(OkHttp.get(), "client", offlineClient);
            setField(TmdbNetwork.class, "client", offlineClient);
            BrowseExperienceSettings.putHistoryActionsEnabled(true);
            BrowseExperienceSettings.putPosterHomeEnabled(false);
            prepared = true;
        });
    }

    @After public void cleanup() {
        if (!prepared) return;
        for (CountDownLatch gate : gates) gate.countDown();
        List<Activity> closing = new ArrayList<>(launched);
        List<CountDownLatch> drained = new ArrayList<>();
        main(() -> {
            for (Activity activity : validationActivities()) {
                if (!previousActivities.contains(activity) && !closing.contains(activity)) closing.add(activity);
            }
            for (Activity activity : closing) {
                if (activity.isDestroyed()) continue;
                if (activity instanceof HomeActivity || activity instanceof WatchHistoryActivity) {
                    CountDownLatch done = new CountDownLatch(1);
                    queue(activity).write(done::countDown);
                    drained.add(done);
                }
            }
            for (int i = closing.size() - 1; i >= 0; i--) if (!closing.get(i).isDestroyed()) closing.get(i).finish();
        });
        try {
            for (CountDownLatch done : drained) assertLatch(done, "accepted history work finishes before fixture cleanup");
            await(() -> closing.stream().allMatch(Activity::isDestroyed), "test activities finish");
        } finally {
            main(() -> {
                for (String key : fixtureKeys) {
                    AppDatabase.get().getHistoryDao().delete(cid, key);
                    AppDatabase.get().getHistoryDao().delete(otherCid, key);
                    AppDatabase.get().getTrackDao().delete(key);
                }
                offlineClient.dispatcher().cancelAll();
                offlineClient.connectionPool().evictAll();
                setField(OkHttp.get(), "client", previousClient);
                setField(TmdbNetwork.class, "client", previousTmdbClient);
                for (String name : VOD_FIELDS) setField(VodConfig.get(), name, vodState.get(name));
                VodConfig.get().config(previousConfiguration);
                SharedPreferences.Editor editor = Prefers.getPrefers().edit();
                for (String key : PREFERENCES) {
                    Object saved = preferences.get(key);
                    if (saved instanceof Boolean flag) editor.putBoolean(key, flag);
                    else editor.remove(key);
                }
                assertTrue(editor.commit());
            });
        }
    }

    @Test(timeout = 45000)
    public void cancelAfterRefreshRestoresTheCapturedCardWithoutDeletingAnything() {
        History first = seed("First history title", cid);
        History second = seed("Second history title", cid);
        History foreign = seed("Other configuration", otherCid);
        WatchHistoryActivity activity = watch(2);
        AlertDialog dialog = openLong(activity, first.getKey());
        main(() -> {
            assertTrue(dialog.findViewById(R.id.history_actions_continue) instanceof MaterialButton);
            assertEquals(first.getVodName(), text(dialog, R.id.history_actions_title));
            assertTrue(text(dialog, R.id.history_actions_episode).contains("Episode 3"));
            assertTrue(text(dialog, R.id.history_actions_progress).contains("01:05"));
            assertTrue(text(dialog, R.id.history_actions_source).contains("History test source"));
            first.setCreateTime(second.getCreateTime() + 1000);
            AppDatabase.get().getHistoryDao().update(first);
            invoke(activity, "load");
        });
        await(() -> ((History) items(activity).get(0)).getKey().equals(first.getKey()), "refresh reorders the card behind the menu");
        press(KeyEvent.KEYCODE_BACK);
        await(() -> !dialog.isShowing() && activity.hasWindowFocus() && first.getKey().equals(selectedKey(activity))
                && activity.findViewById(R.id.recycler).hasFocus(), "cancel returns to the captured card at its new position");
        assertStored(first, second, foreign);
    }

    @Test(timeout = 45000)
    public void findOtherSourcesUsesTheTitleWithoutResettingHistoryOrTracks() {
        History first = seed("Find this exact title", cid);
        History second = seed("Keep this history", cid);
        WatchHistoryActivity activity = watch(2);
        focus(activity, first.getKey());
        press(KeyEvent.KEYCODE_MENU);
        AlertDialog dialog = menu(activity);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> dialog.findViewById(R.id.history_actions_search).hasFocus(), "source search action");
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        CollectActivity results = awaitActivity(CollectActivity.class);
        assertEquals(first.getVodName(), value(() -> results.getIntent().getStringExtra("keyword")));
        assertStored(first, second);
    }

    @Test(timeout = 60000)
    public void realHeldConfirmAndMenuKeysDoNotActivateContinueWhenReleased() {
        History first = seed("Held remote key fixture", cid);
        WatchHistoryActivity history = watch(1);
        assertHeldKeys(history, first);
        main(history::finish);
        await(history::isDestroyed, "complete-history host closes");
        HomeActivity home = home(1);
        assertHeldKeys(home, first);
        assertStored(first);
    }

    @Test(timeout = 45000)
    public void deletingOneRecordLeavesOtherRecordsAndKeepsAnEmptyPageReachable() {
        History first = seed("Remove only this", cid);
        History second = seed("Remove this next", cid);
        History foreign = seed("Never remove another configuration", otherCid);
        WatchHistoryActivity activity = watch(2);
        AlertDialog dialog = openLong(activity, first.getKey());
        main(() -> {
            first.setCreateTime(second.getCreateTime() + 1000);
            AppDatabase.get().getHistoryDao().update(first);
            invoke(activity, "load");
        });
        await(() -> ((History) items(activity).get(0)).getKey().equals(first.getKey()), "refresh moves the captured deletion target");
        delete(dialog);
        await(() -> AppDatabase.get().getHistoryDao().find(cid, first.getKey()) == null
                && items(activity).size() == 1 && second.getKey().equals(selectedKey(activity))
                && activity.findViewById(R.id.recycler).hasFocus(), "one record removed and the remaining card focused");
        assertTrue(value(() -> AppDatabase.get().getTrackDao().find(first.getKey()).isEmpty()));
        assertStored(second, foreign);
        delete(openLong(activity, second.getKey()));
        await(() -> items(activity).size() == 0 && activity.findViewById(R.id.history_actions_back).hasFocus(),
                "last-card deletion focuses the native Back action");
        await(() -> AppDatabase.get().getHistoryDao().find(cid, second.getKey()) == null, "last record transaction finishes");
        assertStored(foreign);
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        await(activity::isDestroyed, "Back leaves the empty page");
    }

    @Test(timeout = 45000)
    public void switchingConfigurationInvalidatesTheOpenMenu() {
        History first = seed("Original configuration", cid);
        History foreign = seed("New configuration", otherCid);
        WatchHistoryActivity activity = watch(1);
        AlertDialog dialog = openLong(activity, first.getKey());
        main(() -> {
            useConfiguration(otherConfiguration);
            dialog.findViewById(R.id.history_actions_delete).performClick();
        });
        await(() -> !dialog.isShowing(), "stale menu closes");
        assertStored(first, foreign);
        main(() -> invoke(activity, "load"));
        await(() -> items(activity).size() == 1 && ((History) items(activity).get(0)).getCid() == otherCid,
                "new configuration history replaces the old cards");
        assertStored(first, foreign);
    }

    @Test(timeout = 45000)
    public void acceptedDeleteKeepsItsConfigurationAndSurvivesClosingTheActivity() {
        History first = seed("Accepted deletion", cid);
        History second = seed("Same configuration survivor", cid);
        History foreign = seed("Different configuration survivor", otherCid);
        WatchHistoryActivity activity = watch(2);
        CountDownLatch release = blockQueue(activity);
        delete(openLong(activity, first.getKey()));
        CountDownLatch done = new CountDownLatch(1);
        main(() -> {
            queue(activity).write(done::countDown);
            activity.finish();
            useConfiguration(otherConfiguration);
        });
        await(activity::isDestroyed, "activity closes before the queued write");
        release.countDown();
        assertLatch(done, "accepted deletion is not cancelled by onDestroy");
        assertNull(value(() -> AppDatabase.get().getHistoryDao().find(cid, first.getKey())));
        assertTrue(value(() -> AppDatabase.get().getTrackDao().find(first.getKey()).isEmpty()));
        assertStored(second, foreign);
    }

    @Test(timeout = 45000)
    public void movedRecordKeepsItsSharedTracksWhenTheQueuedDeleteIsStale() {
        History first = seed("Record moved to another configuration", cid);
        WatchHistoryActivity activity = watch(1);
        CountDownLatch release = blockQueue(activity);
        delete(openLong(activity, first.getKey()));
        CountDownLatch done = new CountDownLatch(1);
        main(() -> {
            first.setCid(otherCid);
            AppDatabase.get().getHistoryDao().update(first);
            queue(activity).write(done::countDown);
            activity.finish();
            useConfiguration(otherConfiguration);
        });
        await(activity::isDestroyed, "stale deletion host closes");
        release.countDown();
        assertLatch(done, "stale deletion finishes without affecting the new owner");
        assertStored(first);
    }

    @Test(timeout = 45000)
    public void homeMenuRemovesOnlyItsCardAndTheDisabledOptionRestoresLegacyLongPress() {
        History first = seed("Last home history", cid);
        History foreign = seed("Other home configuration", otherCid);
        HomeActivity home = home(1);
        delete(openLong(home, first.getKey()));
        await(() -> items(home).size() == 0 && home.findViewById(R.id.nav).hasFocus(),
                "empty home history returns focus to navigation");
        await(() -> AppDatabase.get().getHistoryDao().find(cid, first.getKey()) == null, "home deletion commits");
        assertStored(foreign);

        History second = seed("Legacy first", cid);
        History third = seed("Legacy second", cid);
        main(() -> {
            BrowseExperienceSettings.putHistoryActionsEnabled(false);
            invoke(home, "getHistory");
        });
        await(() -> items(home).size() == 2, "legacy home cards");
        focus(home, second.getKey());
        main(() -> assertTrue(home.getCurrentFocus().performLongClick()));
        await(() -> presenter(home).isDelete(), "disabled option retains legacy delete mode");
        assertNull(value(() -> field(home, "mHistoryActions")));
        assertStored(second, third, foreign);
        main(() -> assertTrue(home.getCurrentFocus().performLongClick()));
        await(() -> items(home).size() == 0, "legacy second long-press retains its existing clear behavior");
        assertStored(foreign);
    }

    @Test(timeout = 45000)
    public void disabledHistoryPageStillTogglesItsOriginalDeleteMode() {
        History first = seed("Original page interaction", cid);
        History second = seed("Original page survivor", cid);
        main(() -> BrowseExperienceSettings.putHistoryActionsEnabled(false));
        WatchHistoryActivity activity = watch(2);
        focus(activity, first.getKey());
        main(() -> assertTrue(activity.getCurrentFocus().performLongClick()));
        await(() -> presenter(activity).isDelete(), "first old long-press enables delete mode");
        assertNull(value(() -> field(activity, "historyActions")));
        main(() -> assertTrue(activity.getCurrentFocus().performLongClick()));
        await(() -> !presenter(activity).isDelete(), "second old long-press exits delete mode");
        assertStored(first, second);
    }

    private History seed(String title, int configurationId) {
        return value(() -> {
            History history = new History();
            history.setCid(configurationId);
            history.setKey(sourceKey + AppDatabase.SYMBOL + fixtureKeys.size());
            history.setVodName(title);
            history.setVodPic("");
            history.setVodRemarks("Episode 3");
            history.setVodFlag("Fixture line");
            history.setEpisodeUrl("https://history.invalid/episode");
            history.setPosition(65_000);
            history.setDuration(600_000);
            history.setCreateTime(System.currentTimeMillis() + fixtureKeys.size() * 100L);
            AppDatabase.get().getHistoryDao().insertOrUpdate(history);
            AppDatabase.get().getTrackDao().insert(new Track(1, "Original audio", "fixture").key(history.getKey()));
            fixtureKeys.add(history.getKey());
            return history;
        });
    }

    private void assertStored(History... expected) {
        main(() -> {
            for (History history : expected) {
                History stored = AppDatabase.get().getHistoryDao().find(history.getCid(), history.getKey());
                assertNotNull("History survives: " + history.getVodName(), stored);
                assertEquals(history.toString(), stored.toString());
                List<Track> tracks = AppDatabase.get().getTrackDao().find(history.getKey());
                assertEquals("Tracks survive: " + history.getVodName(), 1, tracks.size());
                assertEquals("Original audio", tracks.get(0).getName());
            }
        });
    }

    private Config config(int id) {
        Config result = Config.create(0);
        result.setId(id);
        result.setUrl("");
        result.setName("History menu fixture");
        return result;
    }

    private void useConfiguration(Config config) {
        VodConfig.get().config(config);
        setField(VodConfig.get(), "sites", new ArrayList<>(List.of(fixtureSite)));
        setField(VodConfig.get(), "home", fixtureSite);
    }

    private WatchHistoryActivity watch(int count) {
        WatchHistoryActivity activity = launch(WatchHistoryActivity.class);
        await(() -> activity.hasWindowFocus() && items(activity).size() == count, "complete history loads");
        return activity;
    }

    private HomeActivity home(int count) {
        HomeActivity activity = launch(HomeActivity.class);
        await(() -> activity.hasWindowFocus()
                && !((JetStreamPageProgressLayout) activity.findViewById(R.id.progressLayout)).isProgress(), "home config load finishes");
        main(() -> {
            ((SiteViewModel) field(activity, "mViewModel")).getResult().removeObservers(activity);
            invoke(activity, "cancelHistoryLoad");
            useConfiguration(configuration);
            setField(activity, "mInitialFocusPending", false);
            ArrayObjectAdapter rows = (ArrayObjectAdapter) field(activity, "mAdapter");
            rows.clear();
            items(activity).clear();
            rows.add(R.string.home_history);
            rows.add(R.string.home_recommend);
            invoke(activity, "getHistory");
        });
        await(() -> items(activity).size() == count, "home fixture history loads");
        return activity;
    }

    private void focus(Activity activity, String key) {
        int position = value(() -> {
            for (int i = 0; i < items(activity).size(); i++) if (((History) items(activity).get(i)).getKey().equals(key)) return i;
            throw new AssertionError("Missing fixture card: " + key);
        });
        main(() -> {
            if (activity instanceof HomeActivity) invoke(activity, "requestHistoryFocus", position);
            else {
                VerticalGridView recycler = activity.findViewById(R.id.recycler);
                recycler.setSelectedPosition(position);
                recycler.requestFocus();
            }
        });
        await(() -> activity.hasWindowFocus() && key.equals(selectedKey(activity))
                && activity.getCurrentFocus() != null && activity.getCurrentFocus().isLongClickable(), "history card focus");
    }

    private AlertDialog openLong(Activity activity, String key) {
        focus(activity, key);
        main(() -> assertTrue(activity.getCurrentFocus().performLongClick()));
        return menu(activity);
    }

    private AlertDialog menu(Activity activity) {
        AtomicReference<AlertDialog> result = new AtomicReference<>();
        await(() -> {
            HistoryActionsDialog menu = (HistoryActionsDialog) field(activity,
                    activity instanceof HomeActivity ? "mHistoryActions" : "historyActions");
            if (menu == null || !menu.isShowing()) return false;
            AlertDialog dialog = (AlertDialog) field(menu, "dialog");
            result.set(dialog);
            return dialog.findViewById(R.id.history_actions_continue).hasFocus();
        }, "native menu defaults to Continue watching");
        return result.get();
    }

    private void delete(AlertDialog dialog) {
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> dialog.findViewById(R.id.history_actions_delete).hasFocus(), "single-record delete action");
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> !dialog.isShowing(), "record menu closes after selection");
    }

    private void assertHeldKeys(Activity activity, History item) {
        for (int keyCode : new int[]{KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU}) {
            focus(activity, item.getKey());
            long downTime = SystemClock.uptimeMillis();
            AlertDialog dialog;
            sendHeldKey(downTime, KeyEvent.ACTION_DOWN, keyCode, 0);
            try {
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 120L);
                sendHeldKey(downTime, KeyEvent.ACTION_DOWN, keyCode, 1);
                dialog = menu(activity);
                press(KeyEvent.KEYCODE_DPAD_DOWN);
                press(KeyEvent.KEYCODE_DPAD_UP);
                sendHeldKey(downTime, KeyEvent.ACTION_DOWN, keyCode, 2);
            } finally {
                sendHeldKey(downTime, KeyEvent.ACTION_UP, keyCode, 0);
            }
            await(() -> dialog.isShowing() && dialog.findViewById(R.id.history_actions_continue).hasFocus(),
                    "release and repeated keys leave Continue unactivated");
            assertStored(item);
            press(KeyEvent.KEYCODE_DPAD_DOWN);
            press(KeyEvent.KEYCODE_DPAD_DOWN);
            press(KeyEvent.KEYCODE_DPAD_DOWN);
            await(() -> dialog.findViewById(R.id.history_actions_cancel).hasFocus(), "Cancel is reachable after a held key");
            press(keyCode == KeyEvent.KEYCODE_MENU ? KeyEvent.KEYCODE_DPAD_CENTER : keyCode);
            await(() -> !dialog.isShowing() && activity.hasWindowFocus(), "a fresh ordinary confirmation is not swallowed");
        }
    }

    private void sendHeldKey(long downTime, int action, int keyCode, int repeat) {
        int source = keyCode == KeyEvent.KEYCODE_ENTER ? InputDevice.SOURCE_KEYBOARD : InputDevice.SOURCE_DPAD;
        int flags = KeyEvent.FLAG_FROM_SYSTEM | (repeat > 0 ? KeyEvent.FLAG_LONG_PRESS : 0);
        instrumentation.sendKeySync(new KeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode,
                repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, source));
    }

    private CountDownLatch blockQueue(Activity activity) {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gates.add(release);
        main(() -> queue(activity).write(() -> {
            entered.countDown();
            try { release.await(30, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }));
        assertLatch(entered, "history worker enters the queued gate");
        return release;
    }

    private static HistoryTaskQueue queue(Activity activity) {
        return (HistoryTaskQueue) field(activity, activity instanceof HomeActivity ? "mHistoryTasks" : "tasks");
    }

    private static ArrayObjectAdapter items(Activity activity) {
        return (ArrayObjectAdapter) field(activity, activity instanceof HomeActivity ? "mHistoryAdapter" : "items");
    }

    private static HistoryPresenter presenter(Activity activity) {
        return (HistoryPresenter) field(activity, activity instanceof HomeActivity ? "mPresenter" : "presenter");
    }

    private String selectedKey(Activity activity) {
        View focus = activity.getCurrentFocus();
        if (focus != null && focus.getContentDescription() != null) {
            for (int i = 0; i < items(activity).size(); i++) {
                History item = (History) items(activity).get(i);
                if (focus.getContentDescription().toString().startsWith(item.getVodName() + ", ")) return item.getKey();
            }
        }
        return null;
    }

    private static String text(AlertDialog dialog, int id) {
        return ((TextView) dialog.findViewById(id)).getText().toString();
    }

    private <T extends Activity> T launch(Class<T> type) {
        T activity = type.cast(instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        launched.add(activity);
        return activity;
    }

    private List<Activity> validationActivities() {
        List<Activity> result = new ArrayList<>();
        for (Stage stage : new Stage[]{Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED}) {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)) {
                if (activity.getPackageName().equals(instrumentation.getTargetContext().getPackageName())) result.add(activity);
            }
        }
        return result;
    }

    private <T extends Activity> T awaitActivity(Class<T> type) {
        AtomicReference<T> result = new AtomicReference<>();
        await(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if (type.isInstance(activity) && activity.hasWindowFocus()) {
                    result.set(type.cast(activity));
                    return true;
                }
            }
            return false;
        }, type.getSimpleName() + " opens");
        launched.add(result.get());
        return result.get();
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner instanceof Class<?> ? null : owner, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invoke(Object owner, String name, int... arguments) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name, arguments.length == 0 ? new Class<?>[0] : new Class<?>[]{int.class});
            method.setAccessible(true);
            if (arguments.length == 0) method.invoke(owner);
            else method.invoke(owner, arguments[0]);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void press(int keyCode) {
        instrumentation.sendKeyDownUpSync(keyCode);
    }

    private static void assertLatch(CountDownLatch latch, String reason) {
        try { assertTrue(reason, latch.await(8, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private void await(BooleanSupplier predicate, String reason) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (value(predicate::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for " + reason);
    }

    private void main(Runnable action) {
        value(() -> { action.run(); return null; });
    }

    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.get()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("UI operation failed", failure.get());
        return result.get();
    }
}
