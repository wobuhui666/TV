package com.fongmi.android.tv.test;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.TvPreviewActivity;
import com.fongmi.android.tv.ui.custom.JetStreamSettingView;
import com.fongmi.android.tv.ui.custom.CustomKeyDownLive;
import com.fongmi.android.tv.ui.custom.CustomKeyDownVod;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.ui.theme.RexTheme;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class TvAppearanceTouchTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private TvPreviewActivity activity;
    private File output;

    private void main(Runnable task) {
        instrumentation.runOnMainSync(task);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(350);
    }

    private UiObject2 text(String text) {
        UiObject2 item = device.wait(Until.findObject(By.text(text)), 8000);
        assertNotNull("Missing visible control: " + text, item);
        return item;
    }

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(600);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Screenshot " + name, bitmap);
        assertTrue(bitmap.getWidth() >= 960);
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        bitmap.recycle();
        device.dumpWindowHierarchy(new File(output, name + ".xml"));
    }

    private int scrollOffset() {
        AtomicInteger value = new AtomicInteger();
        main(() -> value.set(activity.grid.computeVerticalScrollOffset()));
        return value.get();
    }

    @Test public void verifiesBothThemesAndTouchToDpadContinuity() throws Exception {
        main(() -> { Setting.putLightTheme(false); Setting.putThemeColor(Setting.THEME_DEFAULT); });
        Intent intent = new Intent(instrumentation.getTargetContext(), TvPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (TvPreviewActivity) instrumentation.startActivitySync(intent);
        output = new File(activity.getExternalFilesDir(null), "tv-verification");
        assertTrue(output.exists() || output.mkdirs());
        try {
            for (boolean light : new boolean[] {false, true}) {
                String theme = light ? "light" : "dark";
                main(() -> { RexTheme.setLight(light); activity.showPage("home"); });
                assertEquals(light, RexTheme.isLight());
                verifyPaletteContrast();
                capture(theme + "-01-home");

                text("发现").click();
                SystemClock.sleep(400);
                assertEquals("discover", activity.lastAction);
                main(() -> assertTrue("Navigation host owns focus after touch", activity.nav.isFocused()));
                device.pressDPadRight(); device.pressDPadCenter();
                SystemClock.sleep(400);
                assertEquals("search", activity.lastAction);
                capture(theme + "-02-navigation-touch-dpad");

                text("剧集").click();
                SystemClock.sleep(400);
                assertEquals(2, activity.lastChip);
                main(() -> assertTrue("Chip host owns focus after touch", activity.chips.isFocused()));
                device.pressDPadRight(); device.pressDPadCenter();
                SystemClock.sleep(400);
                assertEquals(3, activity.lastChip);
                capture(theme + "-03-chips-touch-dpad");
                Rect chipBounds = text("动漫").getVisibleBounds();
                device.swipe(chipBounds.centerX(), chipBounds.centerY(), chipBounds.centerX(), chipBounds.centerY(), 130);
                SystemClock.sleep(350);
                assertEquals("chip-long-3", activity.lastAction);

                Rect card = text("山海之间 2").getVisibleBounds();
                device.click(card.centerX(), card.centerY());
                SystemClock.sleep(500);
                assertEquals(1, activity.cardClicks);
                main(() -> assertNotNull(activity.grid.findFocus()));
                device.pressDPadRight(); device.pressDPadCenter();
                SystemClock.sleep(350);
                assertEquals("card-2", activity.lastAction);
                capture(theme + "-04-cards-touch-dpad");
                int before = scrollOffset();
                int[] location = new int[2];
                main(() -> activity.grid.getLocationOnScreen(location));
                int x = location[0] + activity.grid.getWidth() / 2;
                int top = location[1] + activity.dp(20);
                int bottom = location[1] + activity.grid.getHeight() - activity.dp(20);
                device.swipe(x, bottom, x, top, 30);
                SystemClock.sleep(900);
                assertTrue("Touch swipe scrolls the grid", scrollOffset() > before);
                capture(theme + "-05-grid-swipe");

                main(() -> activity.showPage("search"));
                text("A").click();
                capture(theme + "-06-search");
                main(() -> activity.showPage("detail"));
                capture(theme + "-07-detail");
                text("收藏").click();
                SystemClock.sleep(350);
                assertEquals("keep", activity.lastAction);
                main(() -> assertTrue(activity.detail.isFocused()));
                device.pressDPadRight(); device.pressDPadCenter();
                SystemClock.sleep(350);
                assertEquals("change", activity.lastAction);
                main(() -> activity.showDialog());
                capture(theme + "-08-dialog");
                text("关闭").click();

                main(() -> activity.showPage("playback"));
                capture(theme + "-09-playback");
                main(() -> activity.controls.setVisibility(View.GONE));
                int centerX = device.getDisplayWidth() / 2;
                int centerY = device.getDisplayHeight() / 2;
                device.click(centerX, centerY);
                SystemClock.sleep(700);
                assertTrue("Video receives single tap after DOWN", activity.singleTaps > 0);
                int oldDoubles = activity.doubleTaps;
                device.click(centerX, centerY); SystemClock.sleep(90); device.click(centerX, centerY);
                SystemClock.sleep(500);
                assertEquals(oldDoubles + 1, activity.doubleTaps);
                device.swipe(centerX - 250, centerY, centerX + 250, centerY, 12);
                SystemClock.sleep(500);
                assertTrue("Swipe uses the existing seek callback", activity.seekTime > 0);
                main(() -> activity.gestures.reset());
                main(() -> activity.showPage("settings"));
                capture(theme + "-09-settings");
                text(light ? "深色" : "浅色").click();
                SystemClock.sleep(700);
                assertEquals(!light, RexTheme.isLight());
                main(() -> assertTrue("Settings host remains focused after switching", activity.settings.isFocused()));
                capture(theme + "-10-settings-switched");
                if (light) device.pressDPadRight(); else device.pressDPadLeft();
                device.pressDPadCenter();
                SystemClock.sleep(400);
                assertEquals("D-pad moves from the touched appearance chip", light, RexTheme.isLight());
                text(light ? "浅色" : "深色").click();
                SystemClock.sleep(350);
                assertEquals(light, RexTheme.isLight());
                main(() -> { activity.cardClicks = 0; activity.cardLongClicks = 0; });
            }
            verifyGestureCancellation();
            main(() -> RexTheme.setLight(true));
            main(() -> activity.recreate());
            SystemClock.sleep(1200);
            activity = TvPreviewActivity.current;
            assertTrue("Light mode survives activity recreation", RexTheme.isLight());
            capture("light-11-recreated");
            try (FileWriter writer = new FileWriter(new File(output, "result.txt"))) {
                writer.write("PASS: dark/light palettes; touch clicks and long press; D-pad continuity; grid swipe; search/detail/dialog/settings; theme toggle and recreation.\n");
            }
        } finally {
            main(() -> { activity.finish(); Setting.putLightTheme(false); Setting.putThemeColor(Setting.THEME_DEFAULT); });
        }
    }

    private void verifyGestureCancellation() {
        main(() -> activity.showPage("playback"));
        int before = activity.singleTaps;
        main(() -> {
            long time = SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, 200, 200, 0);
            android.view.MotionEvent cancel = android.view.MotionEvent.obtain(time, time + 20, android.view.MotionEvent.ACTION_CANCEL, 200, 200, 0);
            assertTrue("Gesture stream claims DOWN", activity.gestures.onTouchEvent(down));
            activity.gestures.onTouchEvent(cancel);
            down.recycle(); cancel.recycle();
        });
        SystemClock.sleep(600);
        assertEquals("Canceled gesture must not activate a tap", before, activity.singleTaps);
        main(() -> activity.gestures.reset());
    }

    private void verifyPaletteContrast() {
        main(() -> {
            for (com.fongmi.android.tv.ui.theme.JetStreamAccentPalette preset : JetStreamPalette.presets()) {
                Setting.putThemeColor(preset.getValue());
                int surface = JetStreamPalette.resolveColor(activity, R.color.jetstream_surface);
                int text = JetStreamPalette.resolveColor(activity, R.color.jetstream_on_surface);
                int container = JetStreamPalette.primaryContainerInt();
                int onContainer = JetStreamPalette.onPrimaryContainerInt();
                assertTrue("Text/surface contrast", ColorUtils.calculateContrast(text, surface) >= 4.5);
                assertTrue("Focus contrast for " + preset.getValue(), ColorUtils.calculateContrast(onContainer, container) >= 4.5);
                assertEquals(surface, JetStreamPalette.resolveColorStateList(activity, R.color.jetstream_surface).getDefaultColor());
            }
            for (int seed : new int[] {0xFF808080, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF}) {
                Setting.putThemeColor(seed);
                assertTrue("Custom light/dark focus contrast", ColorUtils.calculateContrast(JetStreamPalette.onPrimaryContainerInt(), JetStreamPalette.primaryContainerInt()) >= 4.5);
            }
            Setting.putThemeColor(Setting.THEME_DEFAULT);
        });
    }
}
