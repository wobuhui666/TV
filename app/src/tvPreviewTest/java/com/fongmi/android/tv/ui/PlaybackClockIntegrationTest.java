package com.fongmi.android.tv.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.activity.CastActivity;
import com.fongmi.android.tv.ui.activity.LiveActivity;
import com.fongmi.android.tv.ui.activity.SettingActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.custom.JetStreamSettingView;
import com.fongmi.android.tv.ui.custom.JetStreamVodControlView;
import com.fongmi.android.tv.ui.custom.ProgressLayout;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.BooleanSupplier;

/** Actual TV activities with empty media requests; no provider or playback fixture is needed. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackClockIntegrationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Map<String, Object> saved = new HashMap<>();
    private static final String[] KEYS = {"playback_clock", "player_engine", "background"};
    private Activity activity;
    private SettingActivity settings;

    @Before public void setup() {
        assertEquals("Disposable preview only", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        for (String key : KEYS) if (Prefers.getPrefers().contains(key)) saved.put(key, Prefers.getPrefers().getAll().get(key));
        Prefers.remove("playback_clock");
        PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
        PlayerSetting.putBackground(0);
    }

    @After public void cleanup() {
        if (settings != null) main(settings::finish);
        closePlayback();
        for (String key : KEYS) {
            if (saved.containsKey(key)) Prefers.put(key, saved.get(key));
            else Prefers.remove(key);
        }
    }

    @Test public void shouldDefaultToHiddenInEveryTvPlaybackActivity() {
        assertFalse(PlayerSetting.isPlaybackClock());
        for (Class<? extends Activity> type : playbackActivities()) {
            open(type);
            main(() -> assertEquals(View.GONE, clock().getVisibility()));
            closePlayback();
        }
    }

    @Test public void shouldApplySettingsToggleWhenReturningToPlayback() {
        open(CastActivity.class);
        settings = (SettingActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), SettingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("Settings window", settings::hasWindowFocus);
        main(() -> ((JetStreamSettingView) settings.findViewById(R.id.settingView)).showSection(JetStreamSettingView.SECTION_PLAYBACK));
        UiObject2 toggle = UiDevice.getInstance(instrumentation).wait(Until.findObject(By.text(settings.getString(R.string.player_clock))), 5000);
        assertNotNull("Playback clock option", toggle);
        toggle.click();
        await("Saved clock preference", PlayerSetting::isPlaybackClock);
        NativeUiEvidence.capture("playback-clock-settings");
        main(settings::finish);
        await("Playback clock after settings", () -> activity.hasWindowFocus() && clock().isShown());
        NativeUiEvidence.capture("playback-clock-cast");
    }

    @Test public void shouldKeepClockAboveControlsAndFollowTimeZoneInEveryTvActivity() {
        PlayerSetting.putPlaybackClock(true);
        for (Class<? extends Activity> type : playbackActivities()) {
            open(type);
            await("Visible clock", () -> clock().isShown() && clock().getWidth() > 0);
            main(() -> {
                TextClock clock = clock();
                assertFalse("Clock must not take remote focus", clock.isFocusable());
                assertFalse("Clock must not consume taps", clock.isClickable());
                assertEquals(Color.WHITE, clock.getCurrentTextColor());
                int[] location = new int[2];
                clock.getLocationOnScreen(location);
                assertTrue("Clock at top right", location[0] > activity.getWindow().getDecorView().getWidth() / 2);
                assertTrue("Clock stays within the top inset", location[1] < 150);
                clock.setTimeZone("GMT+08:00");
                SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());
                format.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));
                assertEquals(format.format(new Date()), clock.getText().toString());
                clock.setTimeZone(null);
                JetStreamVodControlView controls = activity.findViewById(R.id.jetstream);
                controls.setInfoState(true, true, "1920 x 1080", "23:59:59", JetStreamVodControlView.ACTION_PLAY, "00:10:00", "01:00:00");
                activity.findViewById(R.id.control).setVisibility(View.VISIBLE);
                assertTrue(clock.isShown());
            });
            NativeUiEvidence.capture("playback-clock-" + type.getSimpleName().toLowerCase(Locale.ROOT) + "-paused");
            main(() -> {
                activity.findViewById(R.id.control).setVisibility(View.GONE);
                activity.findViewById(R.id.widget).setVisibility(View.GONE);
                assertTrue("Clock survives hiding both overlays", clock().isShown());
            });
            NativeUiEvidence.capture("playback-clock-" + type.getSimpleName().toLowerCase(Locale.ROOT));
            closePlayback();
        }
    }

    private java.util.List<Class<? extends Activity>> playbackActivities() {
        return java.util.List.of(VideoActivity.class, LiveActivity.class, CastActivity.class);
    }

    private void open(Class<? extends Activity> type) {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), type)
                .putExtra("empty", false).putExtra("cast", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("Playback window", activity::hasWindowFocus);
        instrumentation.waitForIdleSync();
        main(() -> {
            ProgressLayout progress = activity.findViewById(R.id.progressLayout);
            if (progress != null) progress.showContent();
            activity.findViewById(R.id.progress).setVisibility(View.GONE);
        });
    }

    private TextClock clock() { return activity.findViewById(R.id.playbackClock); }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void closePlayback() {
        if (activity == null) return;
        main(activity::finish);
        await("Playback closed", activity::isDestroyed);
        activity = null;
    }
    private void await(String description, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        fail(description);
    }
}
