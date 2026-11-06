package com.fongmi.android.tv.service;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.service.media.MediaBrowserService;
import android.view.ContextThemeWrapper;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.FlagSet;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.VideoSize;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.activity.PlaybackActivity;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercises production ownership, deferred observers and native end handling on Android's main thread. */
@RunWith(AndroidJUnit4.class)
public class PlaybackOwnershipAndroidTest {

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<Harness> harnesses = new ArrayList<>();
    private int savedEngine;
    private int savedBackground;

    @Before public void setup() {
        assertTrue(context.getPackageName().endsWith(".preview") || context.getPackageName().endsWith(".sourceprobe"));
        savedEngine = PlayerSetting.getEngine();
        savedBackground = PlayerSetting.getBackground();
        PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
        PlayerSetting.putBackground(0);
    }

    @After public void cleanup() {
        try {
            main(() -> {
                for (Harness harness : harnesses) harness.close();
            });
        } finally {
            PlayerSetting.putEngine(savedEngine);
            PlayerSetting.putBackground(savedBackground);
        }
    }

    @Test public void shouldTransferBindingBeforeNotifyingTheOldOwner() {
        main(() -> {
            PlaybackService service = new PlaybackService();
            Runnable next = () -> {};
            Runnable[] previous = new Runnable[1];
            previous[0] = () -> {
                assertFalse(service.isBindingOwner(previous[0]));
                assertTrue(service.isBindingOwner(next));
            };
            service.replaceBinding(previous[0]);
            service.replaceBinding(next);
        });
    }

    @Test public void shouldIgnoreStaleReleaseAndRepeatedClaim() {
        main(() -> {
            PlaybackService service = new PlaybackService();
            int[] notifications = {0};
            Runnable previous = () -> notifications[0]++;
            Runnable next = () -> {};
            service.replaceBinding(previous);
            service.replaceBinding(previous);
            assertEquals(0, notifications[0]);
            service.replaceBinding(next);
            service.releaseBinding(previous);
            assertEquals(1, notifications[0]);
            assertTrue(service.isBindingOwner(next));
            service.releaseBinding(next);
            assertFalse(service.isBindingOwner(next));
        });
    }

    @Test public void shouldRestoreBrowserTrackingAcrossUnbindAndRebind() {
        main(() -> {
            PlaybackService service = new PlaybackService();
            Intent browser = new Intent(MediaBrowserService.SERVICE_INTERFACE);
            service.onRebind(browser);
            assertTrue(service.hasMediaClient());
            assertTrue(service.onUnbind(browser));
            assertFalse(service.hasMediaClient());
            service.onRebind(browser);
            assertTrue(service.hasMediaClient());
        });
    }

    @Test public void shouldKeepLocalBindingSeparateFromBrowserTracking() {
        main(() -> {
            PlaybackService service = new PlaybackService();
            Intent local = new Intent(PlaybackService.LOCAL_BIND_ACTION);
            assertNotNull(service.onBind(local));
            service.onRebind(local);
            assertFalse(service.hasMediaClient());
            assertFalse(service.onUnbind(local));
            service.onRebind(new Intent(MediaBrowserService.SERVICE_INTERFACE));
            service.onUnbind(local);
            assertTrue(service.hasMediaClient());
        });
    }

    @Test public void shouldReadTheCurrentNativeStateAndHandleEachEndOnce() {
        main(() -> {
            Harness harness = createHarness();
            FixturePlayer oldPlayer = new FixturePlayer();
            oldPlayer.state(Player.STATE_ENDED);
            harness.activity.onEvents(oldPlayer, stateEvents());
            assertEquals(0, harness.activity.ends);
            harness.raw.state(Player.STATE_ENDED);
            harness.activity.onEvents(oldPlayer, stateEvents());
            harness.activity.onEvents(oldPlayer, stateEvents());
            assertEquals(1, harness.activity.ends);
            harness.raw.state(Player.STATE_READY);
            harness.activity.onEvents(harness.raw, stateEvents());
            harness.raw.state(Player.STATE_ENDED);
            harness.activity.onEvents(harness.raw, stateEvents());
            assertEquals(2, harness.activity.ends);
            oldPlayer.release();
        });
    }

    @Test public void shouldSuppressEndTransitionsWhileBrowserResolutionIsPending() {
        main(() -> {
            Harness harness = createHarness();
            MediaResolutionRequestGate gate = field(harness.service, PlaybackService.class, "mediaResolutionGate");
            gate.begin("next", null);
            harness.raw.state(Player.STATE_ENDED);
            harness.activity.onEvents(harness.raw, stateEvents());
            assertEquals(0, harness.activity.ends);
            gate.invalidate();
            harness.activity.onEvents(harness.raw, stateEvents());
            assertEquals(1, harness.activity.ends);
        });
    }

    @Test public void shouldRejectOldOwnerPauseReleaseAndEndedCallbacks() {
        main(() -> {
            Harness harness = createHarness();
            PlaybackService.NavigationCallback navigation = new PlaybackService.NavigationCallback() {};
            Runnable next = () -> {};
            harness.service.replaceBinding(next);
            harness.service.setNavigationCallback(navigation, "next");
            invoke(harness.activity, "pausePlayback");
            invoke(harness.activity, "releaseService", new Class<?>[]{boolean.class}, false);
            harness.raw.state(Player.STATE_ENDED);
            harness.activity.onEvents(harness.raw, stateEvents());
            assertEquals(0, harness.raw.pauses);
            assertEquals(0, harness.activity.ends);
            assertTrue(harness.service.isBindingOwner(next));
            assertSame(navigation, field(harness.service, PlaybackService.class, "navigationCallback"));
        });
    }

    @Test public void shouldDeliverStoppedResultsOnReturnAndDiscardTheirOldGeneration() throws Exception {
        Harness harness = harnessWithController();
        main(() -> {
            MutableLiveData<Integer> result = new MutableLiveData<>();
            List<Integer> delivered = new ArrayList<>();
            harness.activity.watch(result, delivered);
            harness.activity.request();
            result.setValue(42);
            assertTrue(delivered.isEmpty());
            harness.activity.started(true);
            harness.activity.dispatchPending();
            assertEquals(List.of(42), delivered);
            harness.activity.started(false);
            result.setValue(43);
            harness.activity.request();
            harness.activity.started(true);
            harness.activity.dispatchPending();
            assertEquals(List.of(42), delivered);
        });
    }

    @Test public void shouldDeliverCreatedResultsImmediatelyWhenBackgroundPlaybackIsEnabled() throws Exception {
        PlayerSetting.putBackground(1);
        Harness harness = harnessWithController();
        main(() -> {
            MutableLiveData<Integer> result = new MutableLiveData<>();
            List<Integer> delivered = new ArrayList<>();
            harness.activity.watch(result, delivered);
            harness.activity.request();
            assertEquals(Lifecycle.State.CREATED, harness.activity.getLifecycle().getCurrentState());
            result.setValue(42);
            assertEquals(List.of(42), delivered);
        });
    }

    @Test public void shouldRestoreKnownSizeWhenTheControllerConnects() throws Exception {
        Harness harness = harnessWithController();
        main(() -> {
            harness.activity.started(true);
            harness.raw.size = new VideoSize(720, 576, 16f / 15);
            harness.raw.refresh();
            invoke(harness.activity, "syncPlaybackState");
            assertEquals(720, harness.manager.getVideoWidth());
            assertEquals(576, harness.manager.getVideoHeight());
            assertEquals(harness.raw.size, harness.activity.lastSize);
            assertSame(harness.raw, harness.activity.view.getPlayer());
        });
    }

    @Test public void shouldResumeOnlyTheSameAutomaticallyPausedMedia() throws Exception {
        Harness harness = harnessWithController();
        main(() -> {
            harness.activity.started(true);
            invoke(harness.activity, "pausePlayback");
            assertEquals(1, harness.raw.pauses);
            invoke(harness.activity, "resumePlayback");
            assertEquals(1, harness.raw.plays);
            harness.raw.setPlayWhenReady(false);
            invoke(harness.activity, "pausePlayback");
            invoke(harness.activity, "resumePlayback");
            assertEquals("A manual pause must stay paused", 1, harness.raw.plays);
            harness.raw.setPlayWhenReady(true);
            invoke(harness.activity, "pausePlayback");
            harness.raw.item = new MediaItem.Builder().setUri("https://example.com/replacement").build();
            harness.raw.refresh();
            invoke(harness.activity, "resumePlayback");
            assertEquals("A saved resume must not play a replacement media item", 2, harness.raw.plays);
        });
    }

    private Harness harnessWithController() throws Exception {
        AtomicReference<Harness> reference = new AtomicReference<>();
        main(() -> {
            Harness harness = createHarness();
            harness.session = new MediaSession.Builder(context, harness.raw).build();
            harness.controllerFuture = new MediaController.Builder(context, harness.session.getToken()).buildAsync();
            reference.set(harness);
        });
        Harness harness = reference.get();
        MediaController controller = harness.controllerFuture.get(10, TimeUnit.SECONDS);
        main(() -> set(harness.activity, PlaybackActivity.class, "mController", controller));
        return harness;
    }

    private Harness createHarness() {
        Harness harness = new Harness();
        harnesses.add(harness);
        return harness;
    }

    private Player.Events stateEvents() {
        return new Player.Events(new FlagSet.Builder().add(Player.EVENT_PLAYBACK_STATE_CHANGED).build());
    }

    private void main(Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { task.run(); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Playback ownership regression failed", failure.get());
    }

    @SuppressWarnings("unchecked") private static <T> T field(Object target, Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void set(Object target, Class<?> type, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invoke(Object target, String name) { invoke(target, name, new Class<?>[0]); }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = PlaybackActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private final class Harness {
        final PlaybackService service = new PlaybackService();
        final FixturePlayer raw = new FixturePlayer();
        final FixtureActivity activity = new FixtureActivity(context);
        final PlayerManager manager;
        MediaSession session;
        ListenableFuture<MediaController> controllerFuture;

        Harness() {
            PlayerManager.Callback callback = (PlayerManager.Callback) Proxy.newProxyInstance(PlayerManager.Callback.class.getClassLoader(),
                    new Class<?>[]{PlayerManager.Callback.class}, (proxy, method, args) -> null);
            manager = new PlayerManager(callback);
            set(manager, PlayerManager.class, "player", raw);
            set(service, PlaybackService.class, "player", manager);
            set(service, PlaybackService.class, "active", true);
            set(activity, PlaybackActivity.class, "mService", service);
            Runnable binding = field(activity, PlaybackActivity.class, "bindingCallback");
            service.replaceBinding(binding);
        }

        void close() {
            activity.view.setPlayer(null);
            activity.seek.setPlayer(null);
            if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
            if (session != null) session.release();
            manager.release();
            raw.release();
        }
    }

    private static final class FixtureActivity extends PlaybackActivity {
        final PlayerView view;
        final PlayerSeekView seek;
        private FixtureLifecycle lifecycle;
        int ends;
        VideoSize lastSize;

        FixtureActivity(Context context) {
            Context themed = new ContextThemeWrapper(context, R.style.Theme_App);
            view = new PlayerView(themed);
            seek = new PlayerSeekView(themed);
            started(false);
        }

        @Override public Lifecycle getLifecycle() {
            if (lifecycle == null) lifecycle = new FixtureLifecycle();
            return lifecycle;
        }

        void started(boolean started) { ((FixtureLifecycle) getLifecycle()).state = started ? Lifecycle.State.STARTED : Lifecycle.State.CREATED; }
        void request() { beginPlaybackRequest(); }
        void watch(MutableLiveData<Integer> data, List<Integer> delivered) { observeForever(data, delivered::add); }
        void dispatchPending() {
            List<?> observers = field(this, PlaybackActivity.class, "playbackObservers");
            for (Object observer : observers) {
                try {
                    Method dispatch = observer.getClass().getDeclaredMethod("dispatch");
                    dispatch.setAccessible(true);
                    dispatch.invoke(observer);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            }
        }
        @Override protected ViewBinding getBinding() { return () -> view; }
        @Override protected PlayerView getPlayerView() { return view; }
        @Override protected PlayerSeekView getSeekView() { return seek; }
        @Override protected PlaybackService.NavigationCallback getNavigationCallback() { return null; }
        @Override protected String getPlaybackKey() { return null; }
        @Override protected void onPlaybackEnded() { ends++; }
        @Override protected void onSizeChanged(VideoSize size) { lastSize = size; }
        @Override public void onIsPlayingChanged(boolean playing) { /* The unattached fixture has no Window. */ }
    }

    private static final class FixtureLifecycle extends Lifecycle {
        State state = State.INITIALIZED;
        @Override public State getCurrentState() { return state; }
        @Override public void addObserver(LifecycleObserver observer) {}
        @Override public void removeObserver(LifecycleObserver observer) {}
    }

    private static final class FixturePlayer extends SimpleBasePlayer {
        MediaItem item = new MediaItem.Builder().setUri("https://example.com/current").build();
        VideoSize size = VideoSize.UNKNOWN;
        int playbackState = Player.STATE_READY;
        boolean playWhenReady = true;
        int plays;
        int pauses;

        FixturePlayer() { super(Looper.getMainLooper()); }
        void state(int state) { playbackState = state; refresh(); }
        void refresh() { invalidateState(); }

        @Override protected State getState() {
            return new State.Builder().setPlaylist(List.of(new MediaItemData.Builder("current").setMediaItem(item).setDurationUs(120_000_000).build()))
                    .setCurrentMediaItemIndex(0).setPlaybackState(playbackState).setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .setVideoSize(size).setContentPositionMs(30_000).setAvailableCommands(new Player.Commands.Builder().addAll(
                            COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_TRACKS,
                            COMMAND_GET_METADATA, COMMAND_PLAY_PAUSE, COMMAND_RELEASE).build()).build();
        }

        @Override protected ListenableFuture<?> handleSetPlayWhenReady(boolean ready) {
            playWhenReady = ready;
            if (ready) plays++; else pauses++;
            return Futures.immediateVoidFuture();
        }

        @Override protected ListenableFuture<?> handleRelease() { return Futures.immediateVoidFuture(); }
    }
}
