package com.fongmi.android.tv.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.ThemeMode;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.fongmi.android.tv.ui.custom.JetStreamDiscoverArtworkLayout;
import com.fongmi.android.tv.ui.custom.JetStreamPageBackgroundView;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.ui.theme.JetStreamThemeController;
import com.fongmi.android.tv.ui.theme.JetStreamWallpaper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Pixel regressions against the two shipping hero layouts and the actual translucent canvas. */
@RunWith(AndroidJUnit4.class)
public final class HomeBackgroundBlendAndroidTest {
    private static final int WALLPAPER = 0xFF357D9D;
    private static final int ARTWORK = 0xFFFF7043;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private CorePlaybackActivity activity;
    private FrameLayout root;
    private ViewGroup hero;
    private Context themedContext;
    private ThemeMode originalTheme;
    private boolean originalWallpaper;

    @Before public void setUp() {
        String app = instrumentation.getTargetContext().getPackageName();
        assertTrue("Disposable TV package only", app.equals("com.fongmi.android.tv.preview")
                || app.equals("com.fongmi.android.tv.sourceprobe"));
        originalTheme = Setting.getThemeMode();
        originalWallpaper = JetStreamWallpaper.isVisible();
        activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(activity::hasWindowFocus, "test Activity has focus");
    }

    @After public void tearDown() {
        main(() -> {
            if (activity != null) activity.finish();
            JetStreamWallpaper.setVisible(originalWallpaper);
            Setting.putThemeMode(originalTheme);
            JetStreamThemeController.refresh();
        });
        instrumentation.waitForIdleSync();
    }

    @Test public void lightWallpaperShowsTheSameCanvasAcrossBothHeroEdges() {
        verifyWallpaperEdges(ThemeMode.LIGHT);
    }

    @Test public void darkWallpaperShowsTheSameCanvasAcrossBothHeroEdges() {
        verifyWallpaperEdges(ThemeMode.DARK);
    }

    @Test public void backgroundOffRetainsTheOpaqueThemeCanvas() {
        for (ThemeMode mode : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            for (int layout : layouts()) {
                mount(layout, mode, false);
                Bitmap frame = render();
                try {
                    int base = value(() -> JetStreamPalette.resolveColor(themedContext, R.color.jetstream_background));
                    assertNear(base, frame.getPixel(frame.getWidth() - 4, hero.getBottom() - 1), 3);
                    assertNear(base, frame.getPixel(frame.getWidth() - 4, hero.getBottom() + 4), 0);
                } finally { frame.recycle(); }
            }
        }
    }

    @Test public void attachedHeroesFollowBackgroundTogglesWithoutRebinding() {
        for (int layout : layouts()) {
            mount(layout, ThemeMode.LIGHT, false);
            View sameHero = hero;
            int plain = edgeColor();
            main(() -> JetStreamWallpaper.setVisible(true));
            assertContinuousEdge();
            int wallpaper = edgeColor();
            assertTrue("Wallpaper is actually visible at the edge", distance(plain, wallpaper) > 20);
            assertSame(sameHero, hero);
            main(() -> JetStreamWallpaper.setVisible(false));
            assertNear(plain, edgeColor(), 0);
        }
    }

    @Test public void titlesActionsAndIndicatorsStayOutsideTheArtworkFade() {
        for (int layout : layouts()) {
            mount(layout, ThemeMode.LIGHT, false);
            View copy = hero.findViewById(layout == R.layout.adapter_discover_hero ? R.id.details : R.id.copy);
            View action = hero.findViewById(R.id.action);
            View indicator = hero.findViewById(R.id.indicator);
            assertFalse("Title and summary are not masked", inArtwork(copy));
            assertFalse("Action is not masked", inArtwork(action));
            assertFalse("Indicator is not masked", inArtwork(indicator));
            int[] point = value(() -> {
                int[] location = new int[2];
                int[] origin = new int[2];
                action.getLocationInWindow(location);
                root.getLocationInWindow(origin);
                // Interior of the opaque action surface, clear of its icon and label.
                return new int[]{location[0] - origin[0] + action.getWidth() / 2,
                        location[1] - origin[1] + dp(5)};
            });
            Bitmap before = render();
            main(() -> JetStreamWallpaper.setVisible(true));
            Bitmap after = render();
            try {
                assertEquals("Button surface retains full opacity", before.getPixel(point[0], point[1]),
                        after.getPixel(point[0], point[1]));
                assertEquals(255, Color.alpha(after.getPixel(point[0], point[1])));
                assertEquals(1f, copy.getAlpha(), 0f);
                assertTrue(action.isShown());
            } finally { before.recycle(); after.recycle(); }
        }
    }

    @Test public void resizedAndReattachedHeroesRefreshTheirFadeAndBackgroundState() {
        for (int layout : layouts()) {
            mount(layout, ThemeMode.DARK, true);
            assertContinuousEdge();
            main(() -> {
                root.removeView(hero);
                JetStreamWallpaper.setVisible(false);
                root.addView(hero, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
            });
            await(() -> hero.getHeight() == dp(260) && hero.isAttachedToWindow(), "reattached hero resized");
            int base = value(() -> JetStreamPalette.resolveColor(themedContext, R.color.jetstream_background));
            assertNear(base, edgeColor(), 3);
            main(() -> {
                JetStreamWallpaper.setVisible(true);
                hero.getLayoutParams().height = dp(220);
                hero.requestLayout();
            });
            await(() -> hero.getHeight() == dp(220), "fade follows the new height");
            assertContinuousEdge();
        }
    }

    private void verifyWallpaperEdges(ThemeMode mode) {
        for (int layout : layouts()) {
            mount(layout, mode, true);
            assertContinuousEdge();
            Bitmap frame = render();
            try {
                int x = frame.getWidth() - 4;
                int edge = frame.getPixel(x, hero.getBottom() - 1);
                int upper = frame.getPixel(x, hero.getTop() + dp(30));
                assertTrue("The hero still contains artwork above the fade", distance(upper, edge) > 35);
                int base = value(() -> JetStreamPalette.resolveColor(themedContext, R.color.jetstream_background));
                assertTrue("Edge shows the wallpaper rather than an opaque theme color", distance(base, edge) > 15);
            } finally { frame.recycle(); }
        }
    }

    private void mount(int layout, ThemeMode mode, boolean wallpaper) {
        main(() -> {
            Setting.putThemeMode(mode);
            JetStreamThemeController.refresh();
            JetStreamWallpaper.setVisible(wallpaper);
            themedContext = new ContextThemeWrapper(activity,
                    mode == ThemeMode.LIGHT ? R.style.Theme_App_Light : R.style.Theme_App);
            root = new FrameLayout(themedContext);
            root.setBackgroundColor(WALLPAPER);
            root.addView(new JetStreamPageBackgroundView(themedContext), new FrameLayout.LayoutParams(-1, -1));
            hero = (ViewGroup) LayoutInflater.from(themedContext).inflate(layout, root, false);
            for (int id : new int[]{R.id.imageA, R.id.imageB}) {
                ImageView image = hero.findViewById(id);
                image.setImageDrawable(new ColorDrawable(ARTWORK));
                image.setVisibility(id == R.id.imageA ? View.VISIBLE : View.GONE);
                image.setAlpha(1f);
            }
            TextView title = hero.findViewById(R.id.name);
            title.setText("推荐主视觉");
            ((TextView) hero.findViewById(R.id.meta)).setText("2026 · 8.2分 · 电影");
            View copy = hero.findViewById(layout == R.layout.adapter_discover_hero ? R.id.details : R.id.copy);
            copy.setVisibility(View.VISIBLE);
            if (layout == R.layout.adapter_discover_hero) {
                ((TextView) hero.findViewById(R.id.source)).setText("TMDB 编辑推荐");
                ((TextView) hero.findViewById(R.id.content)).setText("图片自然融入背景，文字与操作保持清晰。");
            }
            root.addView(hero, new FrameLayout.LayoutParams(-1, dp(layout == R.layout.adapter_discover_hero ? 392 : 320)));
            activity.setContentView(root);
        });
        await(() -> root.getWidth() > 0 && hero.getHeight() > 0 && root.getHeight() > hero.getBottom() + 8,
                "hero and adjacent canvas are laid out");
        instrumentation.waitForIdleSync();
    }

    private void assertContinuousEdge() {
        Bitmap frame = render();
        try {
            int bottom = hero.getBottom();
            for (int x : new int[]{4, frame.getWidth() / 2, frame.getWidth() - 4}) {
                assertNear(frame.getPixel(x, bottom + 4), frame.getPixel(x, bottom - 1), 1);
            }
            int x = frame.getWidth() - 4;
            for (int y = bottom - dp(110); y < bottom + 3; y++) {
                assertTrue("No new stripe inside the transition at y=" + y,
                        distance(frame.getPixel(x, y), frame.getPixel(x, y + 1)) <= 8);
            }
        } finally { frame.recycle(); }
    }

    private Bitmap render() {
        instrumentation.waitForIdleSync();
        return value(() -> {
            Bitmap frame = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(frame));
            return frame;
        });
    }

    private int edgeColor() {
        Bitmap frame = render();
        try { return frame.getPixel(frame.getWidth() - 4, hero.getBottom() - 1); }
        finally { frame.recycle(); }
    }

    private static boolean inArtwork(View view) {
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof JetStreamDiscoverArtworkLayout) return true;
        }
        return false;
    }

    private static int[] layouts() { return new int[]{R.layout.adapter_discover_hero, R.layout.adapter_featured_vod}; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static int distance(int a, int b) {
        return Math.max(Math.abs(Color.red(a) - Color.red(b)),
                Math.max(Math.abs(Color.green(a) - Color.green(b)), Math.abs(Color.blue(a) - Color.blue(b))));
    }
    private static void assertNear(int expected, int actual, int tolerance) {
        assertTrue("Expected " + Integer.toHexString(expected) + ", actual " + Integer.toHexString(actual),
                distance(expected, actual) <= tolerance);
        assertEquals(Color.alpha(expected), Color.alpha(actual));
    }
    private void main(Runnable operation) { instrumentation.runOnMainSync(operation); }
    private <T> T value(Supplier<T> operation) {
        AtomicReference<T> value = new AtomicReference<>();
        main(() -> value.set(operation.get()));
        return value.get();
    }
    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (value(condition::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail(message);
    }
}
