package com.fongmi.android.tv.ui.custom;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.test.SeekInputActivity;
import com.fongmi.android.tv.ui.activity.CastActivity;
import com.fongmi.android.tv.ui.activity.LiveActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

/** Real Android KeyEvent identities, Handler commits and production replacement callbacks. */
@RunWith(AndroidJUnit4.class)
public final class SeekKeyOwnershipAndroidTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private SeekInputActivity activity;
    private CustomKeyDownVod vod;
    private CustomKeyDownLive live;
    private boolean accelerate;

    @Before public void setup() {
        String pkg = instrumentation.getTargetContext().getPackageName();
        assertTrue("Disposable validation app only", pkg.equals("com.fongmi.android.tv.preview")
                || pkg.equals("com.fongmi.android.tv.sourceprobe"));
        activity = (SeekInputActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), SeekInputActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> {
            accelerate = Setting.isSeekAccelerate();
            Setting.putSeekAccelerate(true);
            vod = CustomKeyDownVod.create(activity);
            vod.setFull(true);
            live = CustomKeyDownLive.create(activity);
        });
    }

    @After public void cleanup() {
        main(() -> {
            if (vod != null) vod.reset();
            if (live != null) live.reset();
            Setting.putSeekAccelerate(accelerate);
            if (activity != null) activity.finish();
        });
    }

    @Test public void shouldRejectForeignRepeatsAndCanceledReleaseWithoutChangingLongPressSteps() {
        main(() -> {
            vod.onKeyDown(key(100, KeyEvent.ACTION_DOWN, 22, 0, 7, 0));
            vod.onKeyDown(key(100, KeyEvent.ACTION_DOWN, 22, 25, 8, 0));
            vod.onKeyDown(key(99, KeyEvent.ACTION_UP, 22, 0, 7, 0));
            assertEquals(10_000, activity.preview);
        });
        waitCommit();
        main(() -> {
            assertEquals(0, activity.commits);
            vod.onKeyDown(key(100, KeyEvent.ACTION_UP, 22, 0, 7, KeyEvent.FLAG_CANCELED));
            assertEquals(1, activity.cancellations);
            vod.onKeyDown(key(200, KeyEvent.ACTION_DOWN, 22, 0, 7, 0));
            vod.onKeyDown(key(200, KeyEvent.ACTION_DOWN, 22, 5, 7, 0));
            vod.onKeyDown(key(200, KeyEvent.ACTION_DOWN, 22, 25, 7, 0));
            assertEquals(60_000, activity.preview);
            vod.onKeyDown(key(200, KeyEvent.ACTION_UP, 22, 0, 7, 0));
        });
        waitCommit();
        main(() -> { assertEquals(1, activity.commits); assertEquals(60_000, activity.committed); });
    }

    @Test public void shouldIgnoreOldHeldKeysAndPendingCommitAfterResetAndFullscreenExit() {
        main(() -> {
            vod.onKeyDown(key(100, KeyEvent.ACTION_DOWN, 22, 0, 7, 0));
            vod.onKeyDown(key(100, KeyEvent.ACTION_UP, 22, 0, 7, 0));
            vod.reset();
            vod.onKeyDown(key(100, KeyEvent.ACTION_DOWN, 22, 25, 7, 0));
            vod.onKeyDown(key(100, KeyEvent.ACTION_UP, 22, 0, 7, 0));
        });
        waitCommit();
        main(() -> {
            assertEquals(0, activity.commits);
            vod.onKeyDown(key(200, KeyEvent.ACTION_DOWN, 22, 0, 7, 0));
            vod.onKeyDown(key(200, KeyEvent.ACTION_UP, 22, 0, 7, 0));
            vod.setFull(false);
        });
        waitCommit();
        main(() -> assertEquals(0, activity.commits));
    }

    @Test public void shouldKeepLiveLineSwitchingAndDropOldSeekReleases() {
        main(() -> {
            live.onKeyDown(key(100, KeyEvent.ACTION_DOWN, 22, 0, 7, 0));
            live.reset();
            live.onKeyDown(key(100, KeyEvent.ACTION_DOWN, 22, 3, 7, 0));
            live.onKeyDown(key(100, KeyEvent.ACTION_UP, 22, 0, 7, 0));
            activity.seekable = false;
            live.onKeyDown(key(200, KeyEvent.ACTION_DOWN, 21, 0, 7, 0));
            live.onKeyDown(key(200, KeyEvent.ACTION_UP, 21, 0, 7, KeyEvent.FLAG_CANCELED));
            assertEquals(0, activity.leftLines);
            live.onKeyDown(key(300, KeyEvent.ACTION_DOWN, 21, 0, 7, 0));
            live.onKeyDown(key(300, KeyEvent.ACTION_UP, 21, 0, 7, 0));
            assertEquals(1, activity.leftLines);
            activity.seekable = true;
            live.onKeyDown(key(400, KeyEvent.ACTION_DOWN, 22, 0, 7, 0));
            live.onKeyDown(key(400, KeyEvent.ACTION_DOWN, 22, 5, 8, 0));
            live.onKeyDown(key(400, KeyEvent.ACTION_UP, 22, 0, 8, 0));
            assertEquals(10_000, activity.preview);
            live.onKeyDown(key(400, KeyEvent.ACTION_UP, 22, 0, 7, 0));
        });
        waitCommit();
        main(() -> { assertEquals(1, activity.commits); assertEquals(10_000, activity.committed); });
    }

    @Test public void shouldCancelPendingKeysFromRealVodLiveAndCastReplacementCallbacks() {
        main(() -> {
            VideoActivity video = new VideoActivity();
            putKeyDown(video, vod);
            vod.onKeyDown(key(100, KeyEvent.ACTION_DOWN, 22, 0, 7, 0));
            vod.onKeyDown(key(100, KeyEvent.ACTION_UP, 22, 0, 7, 0));
            video.onMediaItemTransition(MediaItem.EMPTY, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED);
            CastActivity cast = new CastActivity();
            putKeyDown(cast, vod);
            vod.onKeyDown(key(200, KeyEvent.ACTION_DOWN, 22, 0, 7, 0));
            vod.onKeyDown(key(200, KeyEvent.ACTION_UP, 22, 0, 7, 0));
            cast.onMediaItemTransition(MediaItem.EMPTY, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED);
            LiveActivity channel = new LiveActivity();
            putKeyDown(channel, live);
            live.onKeyDown(key(300, KeyEvent.ACTION_DOWN, 22, 0, 7, 0));
            live.onKeyDown(key(300, KeyEvent.ACTION_UP, 22, 0, 7, 0));
            channel.onMediaItemTransition(MediaItem.EMPTY, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED);
        });
        waitCommit();
        main(() -> assertEquals(0, activity.commits));
    }

    @Test public void shouldCombineSuccessiveTouchSwipesAsBefore() {
        main(() -> {
            swipe(100);
            swipe(200);
            assertEquals(20_000, activity.preview);
        });
        waitCommit();
        main(() -> { assertEquals(1, activity.commits); assertEquals(20_000, activity.committed); });
    }

    private void swipe(long down) {
        MotionEvent start = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 100, 100, 0);
        MotionEvent end = MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, 600, 100, 0);
        try { vod.onTouchEvent(start); vod.onTouchEvent(end); }
        finally { start.recycle(); end.recycle(); }
    }

    private static KeyEvent key(long down, int action, int code, int repeat, int device, int flags) {
        return new KeyEvent(down, down + repeat + 1, action, code, repeat, 0, device, 0, flags, InputDevice.SOURCE_DPAD);
    }

    private static void putKeyDown(Object host, Object input) {
        try {
            Field field = host.getClass().getDeclaredField("mKeyDown");
            field.setAccessible(true);
            field.set(host, input);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void main(Runnable task) { instrumentation.runOnMainSync(task); }
    private void waitCommit() { SystemClock.sleep(450); }
}
