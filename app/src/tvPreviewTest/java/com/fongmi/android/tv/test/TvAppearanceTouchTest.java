package com.fongmi.android.tv.test;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.TvPreviewActivity;
import com.fongmi.android.tv.ui.activity.TvHomePreviewActivity;
import com.fongmi.android.tv.ui.activity.HomePreviewFixtures;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class TvAppearanceTouchTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private TvPreviewActivity activity;
    private File output;
    private long touchDownTime;

    private void main(Runnable task) {
        instrumentation.runOnMainSync(task);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(350);
    }

    private UiObject2 text(String text) {
        long deadline = SystemClock.uptimeMillis() + 8000;
        UiObject2 item;
        do {
            refreshAccessibility();
            item = device.findObject(By.text(text));
            if (item != null) break;
            SystemClock.sleep(200);
        } while (SystemClock.uptimeMillis() < deadline);
        assertNotNull("Missing visible control: " + text, item);
        return item;
    }

    private void refreshAccessibility() {
        // API 34 can retain old Compose nodes after the pixels and scroll position change.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) instrumentation.getUiAutomation().clearCache();
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
        refreshAccessibility();
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
                main(() -> { RexTheme.setLight(light); activity.showPage("rex-home"); });
                assertEquals(light, RexTheme.isLight());
                assertEquals("Production Rex theme shares the preference", light, com.fongmi.android.tv.ui.rex.RexTheme.isLight());
                verifyPaletteContrast();
                capture(theme + "-01-home");
                verifyRexHome(theme);
                verifyProductionHome(theme);
                main(() -> activity.showPage("home"));
                capture(theme + "-00-controls-home");

                text("发现").click();
                SystemClock.sleep(400);
                assertEquals("discover", activity.lastAction);
                main(() -> assertTrue("Navigation owns focus after touch", activity.nav.hasFocus()));
                device.pressDPadRight(); device.pressDPadCenter();
                SystemClock.sleep(400);
                assertEquals("search", activity.lastAction);
                capture(theme + "-02-navigation-touch-dpad");

                text("剧集").click();
                SystemClock.sleep(400);
                assertEquals(2, activity.lastChip);
                main(() -> assertTrue("Chip row owns focus after touch", activity.chips.hasFocus()));
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
                SystemClock.sleep(350);
                main(() -> assertEquals("星际A", ((android.widget.TextView) activity.findViewById(R.id.keyword)).getText().toString()));
                device.pressDPadRight(); device.pressDPadCenter();
                SystemClock.sleep(350);
                main(() -> assertEquals("星际AB", ((android.widget.TextView) activity.findViewById(R.id.keyword)).getText().toString()));
                capture(theme + "-06-search");
                main(() -> activity.showPage("detail"));
                capture(theme + "-07-detail");
                text("收藏").click();
                SystemClock.sleep(350);
                assertEquals("keep", activity.lastAction);
                main(() -> assertTrue(activity.detail.hasFocus()));
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
                int oldSeeks = activity.seekEvents;
                device.swipe(centerX - 250, centerY, centerX + 250, centerY, 12);
                SystemClock.sleep(500);
                assertEquals("Fast swipe seeks once", oldSeeks + 1, activity.seekEvents);
                device.swipe(centerX + 250, centerY, centerX - 250, centerY, 120);
                SystemClock.sleep(500);
                assertEquals("Slow swipe also seeks once", oldSeeks + 2, activity.seekEvents);
                assertTrue("Left swipe rewinds", activity.seekTime < 0);
                main(() -> activity.gestures.reset());
                main(() -> activity.showPage("settings"));
                scrollSettingsToAppearance();
                capture(theme + "-09-settings");
                text(light ? "深色" : "浅色").click();
                SystemClock.sleep(700);
                assertEquals(!light, RexTheme.isLight());
                main(() -> assertTrue("Settings retains focus after switching", activity.settings.hasFocus()));
                capture(theme + "-10-settings-switched");
                if (light) device.pressDPadRight(); else device.pressDPadLeft();
                device.pressDPadCenter();
                SystemClock.sleep(400);
                assertEquals("D-pad moves from the touched appearance chip", light, RexTheme.isLight());
                text(light ? "浅色" : "深色").click();
                SystemClock.sleep(350);
                assertEquals(light, RexTheme.isLight());
                main(() -> activity.showPage("native-grid-probe"));
                int nativeClicks = activity.cardClicks;
                text("山海之间 2").click();
                SystemClock.sleep(350);
                assertEquals("Native TV grid accepts the first touch", nativeClicks + 1, activity.cardClicks);
                device.pressDPadRight(); device.pressDPadCenter();
                SystemClock.sleep(350);
                assertEquals("Native TV grid continues from the touched card", "card-2", activity.lastAction);
                capture(theme + "-11-native-grid-probe");
                main(() -> activity.showPage("library"));
                capture(theme + "-12-library");
                main(() -> activity.showPage("native-controls"));
                text("确定").click(); SystemClock.sleep(350);
                assertEquals("Native dialog button activates on its first tap", 1, activity.directClicks);
                device.pressDPadCenter(); SystemClock.sleep(350);
                assertEquals("D-pad stays on the touched dialog button", 2, activity.directClicks);
                text("取消").click(); SystemClock.sleep(350);
                assertEquals("Native text button activates on its first tap", 3, activity.directClicks);
                device.pressDPadCenter(); SystemClock.sleep(350);
                assertEquals("D-pad stays on the touched text button", 4, activity.directClicks);
                text("提示开关").click(); SystemClock.sleep(350);
                main(() -> assertTrue("Native switch toggles on its first tap", activity.nativeSwitch.isChecked()));
                device.pressDPadCenter(); SystemClock.sleep(350);
                main(() -> assertFalse("D-pad stays on the touched switch", activity.nativeSwitch.isChecked()));
                capture(theme + "-13-native-controls");
                verifyRetainedNativeTheme(light, theme);
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
                writer.write("PASS: production PosterHomeController/RexSpotlightPresenter/RexPosterPresenter with offline Rex feeds; dark/light palettes; first touch clicks and long press; D-pad continuity; Rex horizontal rail and vertical grid swipe; search/detail/dialog/TV Material settings; retained native title/switch and new dialog theme; theme toggle and recreation.\n");
            }
        } catch (Exception | AssertionError failure) {
            try {
                capture("failure-" + activity.page);
            } catch (Throwable captureFailure) {
                failure.addSuppressed(captureFailure);
            }
            throw failure;
        } finally {
            main(() -> { activity.finish(); Setting.putLightTheme(false); Setting.putThemeColor(Setting.THEME_DEFAULT); });
        }
    }

    private void verifyProductionHome(String theme) throws Exception {
        HomePreviewFixtures.Session[] session = new HomePreviewFixtures.Session[1];
        main(() -> session[0] = HomePreviewFixtures.open(instrumentation.getTargetContext()));
        Intent intent = new Intent(instrumentation.getTargetContext(), TvHomePreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        TvHomePreviewActivity home = (TvHomePreviewActivity) instrumentation.startActivitySync(intent);
        try {
            AtomicInteger categoryCount = new AtomicInteger();
            long categoriesDeadline = SystemClock.uptimeMillis() + 5000;
            do {
                instrumentation.runOnMainSync(() -> categoryCount.set(((Result) HomePreviewFixtures.field(home, "mResult")).getTypes().size()));
                if (categoryCount.get() == 2) break;
                SystemClock.sleep(100);
            } while (SystemClock.uptimeMillis() < categoriesDeadline);
            main(() -> {
                assertEquals("The asynchronous production history query has the same fixture", 3, History.get().size());
                assertEquals("The production library route has loaded source categories", 2, ((Result) HomePreviewFixtures.field(home, "mResult")).getTypes().size());
                ArrayObjectAdapter page = home.pageAdapter();
                assertTrue("Classic offline data supplies the real first hero", home.controller().isHero(page.get(0)) && home.controller().isFocusable(page.get(0)));
                assertTrue(page.get(home.historyPosition()) instanceof ListRow);
                ListRow history = (ListRow) page.get(home.historyPosition());
                assertTrue("Continue watching uses the production presenter", history.getAdapter().getPresenter(history) instanceof HistoryPresenter);
                assertEquals(3, history.getAdapter().size());
                assertTrue("Categories follow the entire continue-watching row", home.categoriesPosition() > home.historyPosition());
                assertTrue("The classic popular shelf follows categories", home.popularPosition() > home.categoriesPosition());
            });
            enterHomeContent(home);
            main(() -> home.focusRow(home.historyPosition()));
            main(() -> {
                assertEquals("Real content navigation hides the toolbar above continue watching", View.GONE, home.toolbar().getVisibility());
                View history = rowView(home, home.historyPosition());
                View categories = rowView(home, home.categoriesPosition());
                View popular = rowView(home, home.popularPosition());
                assertTrue("History and category surfaces do not overlap", history.getBottom() <= categories.getTop());
                assertTrue("Category controls precede the popular shelf", categories.getBottom() <= popular.getTop());
                assertTrue("Categories stay adjacent to history", categories.getTop() - history.getBottom() <= activity.dp(48));
                assertTrue("The populated classic shelf keeps its measured content height", popular.getHeight() >= activity.dp(230));
                ItemBridgeAdapter.ViewHolder bridge = (ItemBridgeAdapter.ViewHolder) home.pageGrid().findViewHolderForAdapterPosition(home.historyPosition());
                ListRowPresenter presenter = (ListRowPresenter) bridge.getPresenter();
                HorizontalGridView rail = ((ListRowPresenter.ViewHolder) presenter.getRowViewHolder(bridge.getViewHolder())).getGridView();
                assertEquals("History cards retain their production geometry", activity.dp(112), rail.getChildAt(0).getHeight());
            });
            capture(theme + "-23-home-history-order");

            device.pressBack();
            SystemClock.sleep(700);
            int[] keys = {R.string.home_tab_home, R.string.home_vod, R.string.home_discover, R.string.home_search, R.string.home_tab_my};
            String[] routes = {null, "VodActivity", "DiscoverActivity", "SearchActivity", "MyActivity"};
            for (int index = 0; index < keys.length; index++) {
                int clicks = home.navClicks;
                text(home.getString(index == 1 ? R.string.home_tab_library : keys[index])).click();
                SystemClock.sleep(500);
                assertEquals("The real toolbar activates on its first tap", clicks + 1, home.navClicks);
                assertEquals(String.valueOf(keys[index]), home.lastNavKey);
                if (routes[index] != null) assertRoute(home, routes[index]);
                else main(() -> assertTrue("The production home action moves focus into content", home.pageGrid().hasFocus()));
            }
            text("片库").click();
            SystemClock.sleep(400);
            device.pressDPadRight(); device.pressDPadCenter();
            SystemClock.sleep(400);
            assertEquals("The real D-pad continues from the touched toolbar item", String.valueOf(R.string.home_discover), home.lastNavKey);
            assertRoute(home, "DiscoverActivity");
            capture(theme + "-24-home-toolbar-touch");

            for (int index = 0; index < keys.length; index++) {
                Rect bounds = text(home.getString(index == 1 ? R.string.home_tab_library : keys[index])).getVisibleBounds();
                int longClicks = home.navLongClicks;
                int clicks = home.navClicks;
                device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), 130);
                SystemClock.sleep(350);
                assertEquals("Each real toolbar item retains its source-menu long press", longClicks + 1, home.navLongClicks);
                assertEquals(String.valueOf(keys[index]), home.lastLongKey);
                assertEquals("Long press does not also route a tap", clicks, home.navClicks);
                text("离线片库 A");
                if (index == keys.length - 1) capture(theme + "-25-home-toolbar-longpress");
                device.pressBack();
                SystemClock.sleep(450);
            }

            Rect library = text("片库").getVisibleBounds();
            int clicks = home.navClicks;
            instrumentation.runOnMainSync(() -> {
                home.queueNavRestore();
                long queuedGeneration = home.focusGeneration();
                dispatchTouch(home, library, android.view.MotionEvent.ACTION_DOWN);
                assertTrue("Touch expires already queued navigation focus", home.focusGeneration() > queuedGeneration);
                home.showToolbar(false);
                assertTrue("Toolbar remains hittable throughout a touch stream", home.toolbar().getVisibility() == View.VISIBLE);
                dispatchTouch(home, library, android.view.MotionEvent.ACTION_UP);
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(600);
            assertEquals("Queued focus and hide cannot consume the toolbar tap", clicks + 1, home.navClicks);
            assertRoute(home, "VodActivity");
            device.pressDPadRight(); device.pressDPadCenter();
            SystemClock.sleep(450);
            assertRoute(home, "DiscoverActivity");

            enterHomeContent(home);
            main(() -> home.focusRow(home.popularPosition()));
            main(() -> assertEquals("Scrolling content hides the real toolbar", View.GONE, home.toolbar().getVisibility()));
            main(() -> home.showToolbar(true));
            Rect search = text("搜索").getVisibleBounds();
            clicks = home.navClicks;
            instrumentation.runOnMainSync(() -> {
                dispatchTouch(home, search, android.view.MotionEvent.ACTION_DOWN);
                home.showToolbar(false);
                dispatchTouch(home, search, android.view.MotionEvent.ACTION_CANCEL);
                assertFalse("Cancellation releases the toolbar guard", home.toolbarTouchActive());
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(500);
            assertEquals("A canceled toolbar touch cannot navigate", clicks, home.navClicks);
            main(() -> assertEquals("Cancellation permits the deferred content hide", View.GONE, home.toolbar().getVisibility()));
            device.pressBack();
            SystemClock.sleep(700);
            text("搜索").click();
            SystemClock.sleep(400);
            assertRoute(home, "SearchActivity");
            capture(theme + "-26-home-toolbar-restored");

            int dialogs = home.sourceDialogs;
            refreshAccessibility();
            UiObject2 title = device.findObject(By.res(instrumentation.getTargetContext().getPackageName(), "title"));
            assertNotNull("Production source switcher is visible", title);
            title.click();
            SystemClock.sleep(350);
            assertEquals("The production title opens its source chooser on the first tap", dialogs + 1, home.sourceDialogs);
            text("离线片库 A");
            capture(theme + "-27-home-title-source");
            device.pressBack();
            SystemClock.sleep(450);

            main(() -> { session[0].replaceHistory(false); home.replaceHistory(List.of()); home.focusRow(0); });
            main(() -> {
                assertTrue("The production history query is empty", History.get().isEmpty());
                assertEquals(0, ((ArrayObjectAdapter) HomePreviewFixtures.field(home, "mHistoryAdapter")).size());
                assertEquals("Empty history restores categories directly after the hero", 1, home.categoriesPosition());
                View hero = rowView(home, 0);
                View categories = rowView(home, home.categoriesPosition());
                assertTrue("Empty history adds no blank header gap", categories.getTop() - hero.getBottom() <= home.pageGrid().getVerticalSpacing() + activity.dp(2));
                assertTrue("Categories remain below the hero", categories.getTop() >= hero.getBottom());
            });
            capture(theme + "-28-home-empty-history");
        } catch (Exception | AssertionError failure) {
            try { capture("failure-production-home-" + theme); }
            catch (Throwable captureFailure) { failure.addSuppressed(captureFailure); }
            throw failure;
        } finally {
            main(home::finish);
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!home.isDestroyed() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            main(() -> session[0].close());
        }
    }

    private void enterHomeContent(TvHomePreviewActivity home) {
        device.pressDPadDown();
        AtomicBoolean contentFocused = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            instrumentation.runOnMainSync(() -> contentFocused.set(home.pageGrid().hasFocus()));
            if (contentFocused.get()) break;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("D-pad movement enters production home content before selecting a lower row", contentFocused.get());
    }

    private View rowView(TvHomePreviewActivity home, int position) {
        RecyclerView.ViewHolder holder = home.pageGrid().findViewHolderForAdapterPosition(position);
        assertNotNull("Production home row is laid out at " + position, holder);
        return holder.itemView;
    }

    private void assertRoute(TvHomePreviewActivity home, String destination) {
        assertNotNull("Production navigation emitted an Intent", home.lastNavigation);
        assertEquals("com.fongmi.android.tv.ui.activity." + destination, home.lastNavigation.getComponent().getClassName());
    }

    private void dispatchTouch(TvHomePreviewActivity home, Rect bounds, int action) {
        long time = SystemClock.uptimeMillis();
        if (action == android.view.MotionEvent.ACTION_DOWN) touchDownTime = time;
        android.view.MotionEvent event = android.view.MotionEvent.obtain(touchDownTime, time, action, bounds.centerX(), bounds.centerY(), 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        home.dispatchTouchEvent(event);
        event.recycle();
    }

    private void verifyRetainedNativeTheme(boolean light, String theme) throws Exception {
        main(() -> activity.showPage("retained-controls"));
        View title = activity.retainedTitle;
        View nativeSwitch = activity.nativeSwitch;
        main(() -> RexTheme.setLight(!light));
        SystemClock.sleep(450);
        main(() -> {
            assertSame("Playback Activity survives theme switching", activity, TvPreviewActivity.current);
            assertFalse("Retained playback Activity is alive", activity.isDestroyed());
            assertTrue("Retained playback title remains attached", title.isAttachedToWindow());
            assertTrue("Retained playback switch remains attached", nativeSwitch.isAttachedToWindow());
            assertSame("Playback native controls survive theme switching", title, activity.retainedTitle);
            assertSame("Retained switch survives theme switching", nativeSwitch, activity.nativeSwitch);
            assertEquals("Retained playback title follows the current foreground",
                    JetStreamPalette.resolveColor(activity, R.color.jetstream_on_surface), activity.retainedTitle.getCurrentTextColor());
            verifySurfaceTheme(activity, !light);
            verifySwitchTint();
        });
        main(() -> activity.showDialog());
        main(() -> verifySurfaceTheme(activity.previewDialog.getContext(), !light));
        text("关闭").click();
        main(() -> RexTheme.setLight(light));
        main(() -> {
            assertEquals("Retained playback title refreshes in both directions",
                    JetStreamPalette.resolveColor(activity, R.color.jetstream_on_surface), activity.retainedTitle.getCurrentTextColor());
            verifySurfaceTheme(activity, light);
            verifySwitchTint();
        });
        capture(theme + "-21-retained-native-controls");
        main(() -> activity.showDialog());
        capture(theme + "-22-retained-dialog");
        text("关闭").click();
    }

    private void verifySurfaceTheme(android.content.Context context, boolean light) {
        android.util.TypedValue surface = new android.util.TypedValue();
        assertTrue(context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, surface, true));
        double luminance = ColorUtils.calculateLuminance(surface.data);
        assertTrue("Retained Activity and new dialogs follow the appearance", light ? luminance > .7 : luminance < .2);
    }

    private void verifySwitchTint() {
        assertNotNull(activity.nativeSwitch.getThumbTintList());
        assertNotNull(activity.nativeSwitch.getTrackTintList());
        for (int[] state : new int[][] {{android.R.attr.state_enabled}, {android.R.attr.state_enabled, android.R.attr.state_checked}}) {
            assertEquals("Retained switch thumb follows the current state palette",
                    JetStreamPalette.resolveColorStateList(activity, R.color.jetstream_switch_thumb).getColorForState(state, 0),
                    activity.nativeSwitch.getThumbTintList().getColorForState(state, 0));
            assertEquals("Retained switch track follows the current state palette",
                    JetStreamPalette.resolveColorStateList(activity, R.color.jetstream_switch_track).getColorForState(state, 0),
                    activity.nativeSwitch.getTrackTintList().getColorForState(state, 0));
        }
    }

    private void scrollSettingsToAppearance() {
        for (int i = 0; i < 40; i++) {
            refreshAccessibility();
            if (device.hasObject(By.text("浅色"))) break;
            UiObject2 content = null;
            for (UiObject2 candidate : device.findObjects(By.scrollable(true))) {
                Rect bounds = candidate.getVisibleBounds();
                if (bounds.centerX() > device.getDisplayWidth() / 2 && bounds.height() > device.getDisplayHeight() / 3) {
                    content = candidate;
                    break;
                }
            }
            assertNotNull("Production settings exposes its content scroll container", content);
            // Small, slow gestures cannot fling past the appearance row between checks.
            // Compose may report scroll-finished before the end; target visibility controls this bounded search.
            content.scroll(Direction.DOWN, .2f, 400);
            SystemClock.sleep(300);
        }
        refreshAccessibility();
        assertTrue("Production settings scrolls to the appearance actions", device.hasObject(By.text("浅色")));
    }

    private void verifyRexHome(String theme) throws Exception {
        main(() -> {
            assertTrue("Production controller registered a Rex spotlight row", activity.rexSpotlightPosition > 0);
            assertTrue("Production controller registered a Rex poster row", activity.rexMixedPosition > activity.rexSpotlightPosition);
            activity.selectRexRow(true);
        });
        capture(theme + "-14-rex-spotlight");
        int before = activity.navigationCount;
        text("星际旅途 1").click();
        SystemClock.sleep(400);
        assertEquals("Rex spotlight activates on its first tap", before + 1, activity.navigationCount);
        assertEquals("trending_day", activity.lastNavigation.getStringExtra("section_key"));
        main(() -> assertNotNull("Touched Rex spotlight owns focus", activity.rexGrid.findFocus()));
        device.pressDPadRight(); device.pressDPadCenter();
        SystemClock.sleep(400);
        assertEquals("D-pad advances from the touched Rex spotlight", "now_playing", activity.lastNavigation.getStringExtra("section_key"));
        capture(theme + "-15-rex-spotlight-touch-dpad");
        Rect spotlight = text("山海之间 13").getVisibleBounds();
        before = activity.navigationCount;
        device.swipe(spotlight.centerX(), spotlight.centerY(), spotlight.centerX(), spotlight.centerY(), 130);
        SystemClock.sleep(400);
        assertEquals("Rex spotlight long press navigates once", before + 1, activity.navigationCount);
        assertEquals("now_playing", activity.lastNavigation.getStringExtra("section_key"));
        verifyRexRailSwipe(true);
        capture(theme + "-16-rex-spotlight-swipe");

        main(() -> activity.selectRexRow(false));
        capture(theme + "-17-rex-mixed");
        before = activity.navigationCount;
        text("山海之间 2").click();
        SystemClock.sleep(400);
        assertEquals("Rex poster activates on its first tap", before + 1, activity.navigationCount);
        assertEquals("tmdb:movie:1001", activity.lastNavigation.getStringExtra("key"));
        main(() -> assertNotNull("Touched Rex poster owns focus", activity.rexGrid.findFocus()));
        device.pressDPadRight(); device.pressDPadCenter();
        SystemClock.sleep(400);
        assertEquals("D-pad advances from the touched Rex poster", "tmdb:movie:1002", activity.lastNavigation.getStringExtra("key"));
        capture(theme + "-18-rex-mixed-touch-dpad");
        Rect poster = text("追光的人 3").getVisibleBounds();
        before = activity.navigationCount;
        device.swipe(poster.centerX(), poster.centerY(), poster.centerX(), poster.centerY(), 130);
        SystemClock.sleep(400);
        assertEquals("Rex poster long press navigates once", before + 1, activity.navigationCount);
        verifyRexRailSwipe(false);
        capture(theme + "-19-rex-mixed-swipe");
        main(() -> activity.rexGrid.requestFocus());
        verifyScrolledBySwipe(activity.rexGrid, device.getDisplayWidth() - 70, device.getDisplayHeight() - 100,
                device.getDisplayWidth() - 70, 200, false);
        capture(theme + "-20-rex-shelves");
    }

    private void verifyRexRailSwipe(boolean spotlight) {
        int[] bounds = new int[4];
        HorizontalGridView[] target = new HorizontalGridView[1];
        main(() -> {
            HorizontalGridView rail = activity.rexRail(spotlight);
            assertNotNull("Production CustomRowPresenter exposes its native rail", rail);
            target[0] = rail;
            int[] location = new int[2];
            rail.getLocationOnScreen(location);
            bounds[0] = location[0]; bounds[1] = location[1];
            bounds[2] = rail.getWidth(); bounds[3] = rail.getHeight();
        });
        int y = Math.min(device.getDisplayHeight() - 80, bounds[1] + bounds[3] / 2);
        verifyScrolledBySwipe(target[0], bounds[0] + bounds[2] - 180, y,
                bounds[0] + 180, y, true);
    }

    private void verifyScrolledBySwipe(RecyclerView recycler, int startX, int startY, int endX, int endY, boolean horizontal) {
        AtomicInteger distance = new AtomicInteger();
        RecyclerView.OnScrollListener listener = new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                distance.addAndGet(horizontal ? dx : dy);
            }
        };
        // Leanback's LayoutManager leaves scrollbar offsets at zero; observe consumed movement.
        main(() -> recycler.addOnScrollListener(listener));
        try {
            device.swipe(startX, startY, endX, endY, 35);
            SystemClock.sleep(800);
            assertTrue(horizontal ? "Touch swipe scrolls the real Rex horizontal rail"
                    : "Production Rex page scrolls vertically to its shelves", distance.get() > 0);
        } finally {
            main(() -> recycler.removeOnScrollListener(listener));
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
        main(() -> {
            long time = SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, 200, 200, 0);
            activity.gestures.onTouchEvent(down);
            activity.gestures.setFull(false);
            down.recycle();
        });
        SystemClock.sleep(600);
        assertEquals("Leaving full screen cancels a pending tap", before, activity.singleTaps);
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
