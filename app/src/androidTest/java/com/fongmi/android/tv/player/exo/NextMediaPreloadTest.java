package com.fongmi.android.tv.player.exo;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.subtitle.AdvancedSubtitleController;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.fongmi.android.tv.test.CoreFixtureServer;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Map;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NextMediaPreloadTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicReference<PlaybackException> error = new AtomicReference<>();
    private NextMediaPreload next;
    private PreloadCoordinator coordinator;
    private AdvancedSubtitleController subtitles;
    private CorePlaybackActivity activity;

    @Test public void reusesPreparedSourceAcrossSameEpisodeSeekWithIsolatedHeadersAndCancelsOnStopOrDisable() {
        String packageName = instrumentation.getTargetContext().getPackageName();
        assertTrue("Disposable validation package only", packageName.equals("com.fongmi.android.tv.preview")
                || packageName.equals("com.fongmi.android.tv.sourceprobe"));
        CoreFixtureServer.ensureStarted();
        activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String base = CoreFixtureServer.baseUrl();
        MediaItem first = item(base, "base.mp4"), second = item(base, "styled.mkv");
        boolean originalNextEpisode = PreloadSetting.isNextEpisode();
        try {
            main(() -> {
                PreloadSetting.putNextEpisode(true);
                subtitles = new AdvancedSubtitleController();
                coordinator = new PreloadCoordinator();
                next = new NextMediaPreload(1, new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException e) { error.set(e); }
                }, subtitles, coordinator);
                activity.view.setPlayer(next.player);
                subtitles.activate(first);
                next.player.setMediaItem(first); next.player.prepare(); next.player.play();
            });
            await(() -> next.player.getPlaybackState() == Player.STATE_READY && !next.player.isLoading(), "current buffer ready");
            main(() -> next.player.pause());
            awaitBudget();
            main(() -> {
                PreloadBudget.Lease current = coordinator.acquire(PreloadBudget.Owner.CURRENT, () -> {});
                assertNotNull("Current item can reserve the background connection", current);
                next.preload(second, 0);
                assertNull("Next item must wait while current-item pre-cache owns the connection", next.take(second));
                coordinator.release(current);
                next.preload(second, 0);
                assertTrue("The real manager starts an unfinished speculative source", (boolean) field(next, "added"));
                assertFalse("Completion callbacks cannot run until this UI operation returns", (boolean) field(next, "completed"));
                AbortableCallFactory unfinished = (AbortableCallFactory) field(next, "calls");
                assertNotNull(unfinished);
                next.player.seekTo(1000);
                assertTrue("A real foreground seek aborts the old preload transport", (boolean) field(unfinished, "aborted"));
                assertFalse("In-flight work yields to the foreground seek budget", (boolean) field(next, "added"));
                assertNull("An unfinished source is removed instead of consuming seek-time resources", preparedSource(second));
            });
            awaitBudget();
            main(() -> next.preload(second, 0));
            await(() -> (boolean) field(next, "completed"), "next item's real requested range completed");
            AtomicReference<MediaSource> ready = new AtomicReference<>();
            main(() -> {
                ready.set(preparedSource(second));
                assertNotNull("The completed preload exposes its prepared MediaSource", ready.get());
                next.player.seekTo(5000);
            });
            await(() -> next.player.getPlaybackState() == Player.STATE_READY
                    && next.player.getCurrentPosition() >= 5000 && !next.player.isLoading(), "same-episode seek finishes");
            main(() -> {
                assertTrue("The seek must retain completion instead of rebuilding the next item", (boolean) field(next, "completed"));
                assertSame("Same-episode seek retains the exact prepared source and its buffered samples", ready.get(), preparedSource(second));
            });
            AtomicReference<MediaSource> source = new AtomicReference<>();
            main(() -> {
                next.player.stop();
                source.set(next.take(second));
                assertNotNull("Prepared next item was discarded during stop", source.get());
                assertSame("Stop/take transfers the same source retained across the seek", ready.get(), source.get());
                subtitles.activate(second);
                next.player.setMediaSource(source.get()); next.player.prepare(); next.player.play();
                next.clear();
            });
            await(() -> next.player.getPlaybackState() == Player.STATE_READY && next.player.getCurrentPosition() > 0, "preloaded episode handoff");
            await(() -> !next.player.isLoading(), "handoff foreground buffer finishes loading");
            main(() -> next.player.pause());
            awaitBudget();
            main(() -> next.preload(first, 0));
            await(() -> (boolean) field(next, "completed"), "another completed next source before standalone stop");
            main(() -> next.player.stop());
            await(() -> field(next, "pending") == null, "standalone stop releases the unused completed buffer");
            main(() -> assertNull("The manager must forget the completed source when it is not handed off", preparedSource(first)));
            main(() -> {
                next.preload(first, 0);
                PreloadSetting.putNextEpisode(false);
            });
            await(() -> field(next, "pending") == null, "disabling clears even an unstarted speculative item");
            main(() -> assertNull("Disabling must release speculative media", next.take(first)));
            assertNull(error.get());
        } finally {
            main(() -> {
                activity.view.setPlayer(null);
                if (coordinator != null) coordinator.detach();
                if (next != null) { next.release(); next.player.release(); }
                if (subtitles != null) subtitles.release();
                activity.finish(); PreloadSetting.putNextEpisode(originalNextEpisode);
            });
        }
    }

    @AfterClass public static void stopFixtureServer() { CoreFixtureServer.stop(); }

    private MediaItem item(String base, String file) {
        return MediaItemFactory.from(PlaySpec.from("next-test", base + file, Map.of("X-Core-Media", file), MediaMetadata.EMPTY));
    }
    private MediaSource preparedSource(MediaItem item) {
        return ((DefaultPreloadManager) field(next, "manager")).getMediaSource(item);
    }
    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void awaitBudget() {
        await(() -> {
            PreloadBudget.Lease probe = coordinator.acquire(PreloadBudget.Owner.CURRENT, () -> {});
            if (probe == null) return false;
            coordinator.release(probe);
            return true;
        }, "background budget ready (playback reserve, seek cooldown, memory and storage)");
    }
    private void main(Runnable runnable) { instrumentation.runOnMainSync(runnable); }
    private void await(BooleanSupplier condition, String message) {
        long deadline=SystemClock.elapsedRealtime()+25000;
        while(SystemClock.elapsedRealtime()<deadline) {
            if(error.get()!=null) throw new AssertionError(message,error.get());
            AtomicReference<Boolean> ready=new AtomicReference<>(false);main(()->ready.set(condition.getAsBoolean()));
            if(ready.get()) return; SystemClock.sleep(100);
        }
        fail(message);
    }
}
