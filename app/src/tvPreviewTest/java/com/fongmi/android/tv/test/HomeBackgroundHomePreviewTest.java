package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.ThemeMode;
import com.fongmi.android.tv.ui.activity.HomePreviewFixtures;
import com.fongmi.android.tv.ui.activity.TvHomePreviewActivity;
import com.fongmi.android.tv.ui.custom.JetStreamDiscoverImmersiveHeroLayout;
import com.fongmi.android.tv.ui.custom.JetStreamWallView;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.ui.theme.JetStreamThemeController;
import com.fongmi.android.tv.ui.theme.JetStreamWallpaper;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Real HomeActivity and decoded offline artwork over the shipping wallpaper canvas. */
@RunWith(AndroidJUnit4.class)
public final class HomeBackgroundHomePreviewTest {
    private static final List<String> PREFERENCES = List.of("wall", "wall_type", "wall_color", "theme_color",
            "tv_theme_mode", "tv_light_theme", "leanback_wallpaper_visible", "browse_hero_rotation");
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private TvHomePreviewActivity home;

    @Test(timeout = 90_000)
    public void shouldBlendTheRealHomeIntoItsWallpaperInBothThemesAndRetainTheOpaqueCanvasWhenHidden() throws Exception {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        Map<String, ?> savedPreferences = Prefers.getPrefers().getAll();
        boolean savedVisibility = JetStreamWallpaper.isVisible();
        AtomicReference<HomePreviewFixtures.Session> fixture = new AtomicReference<>();
        JSONArray scenarios = new JSONArray();
        try {
            main(() -> {
                fixture.set(HomePreviewFixtures.openCompact(instrumentation.getTargetContext()));
                // The built-in blue/purple wallpaper is constant vertically. Any
                // discontinuity across the hero edge therefore comes from composition.
                Setting.putWall(2);
                Setting.putWallType(0);
                Setting.putThemeColor(Setting.THEME_DEFAULT);
                BrowseExperienceSettings.putHeroRotationMode(BrowseExperienceSettings.HERO_ROTATION_MANUAL);
            });
            for (ThemeMode mode : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                Log.i("HomeBackgroundBlend", "Launch actual home " + mode);
                main(() -> {
                    Setting.putThemeMode(mode);
                    JetStreamThemeController.refresh();
                    JetStreamWallpaper.setVisible(true);
                });
                home = (TvHomePreviewActivity) instrumentation.startActivitySync(new Intent(
                        instrumentation.getTargetContext(), TvHomePreviewActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                await("Production home loads offline feeds and categories", () -> home.hasWindowFocus()
                        && ((Result) HomePreviewFixtures.field(home, "mResult")).getTypes().size() == 2
                        && hero() != null && artworkReady());
                main(home::queueNavRestore);
                await("The complete hero and following canvas are in view", this::completeHeroVisible);
                View retainedHero = read(this::hero);
                await("The shipping wallpaper is decoded beneath the home", this::wallpaperReady);
                Log.i("HomeBackgroundBlend", "Actual home decoded, screenshot " + mode + " wallpaper on");
                scenarios.put(verifySeam(mode, true));

                main(() -> JetStreamWallpaper.setVisible(false));
                await("Turning wallpaper off removes its view and keeps the existing home", () ->
                        HomePreviewFixtures.field(home, "wallpaper") == null && hero() == retainedHero
                                && completeHeroVisible());
                scenarios.put(verifySeam(mode, false));
                closeHome();
            }
            File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
            assertNotNull(directory);
            assertTrue(directory.isDirectory() || directory.mkdirs());
            Files.write(new File(directory, "home-background-home.json").toPath(),
                    new JSONObject().put("realHomeActivity", true).put("decodedArtwork", true)
                            .put("scenarios", scenarios).toString(2).getBytes(StandardCharsets.UTF_8));
        } finally {
            closeHome();
            main(() -> {
                if (fixture.get() != null) fixture.get().close();
                JetStreamWallpaper.setVisible(savedVisibility);
                SharedPreferences.Editor editor = Prefers.getPrefers().edit();
                for (String key : PREFERENCES) {
                    Object value = savedPreferences.get(key);
                    if (value instanceof Integer number) editor.putInt(key, number);
                    else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                    else if (value instanceof String text) editor.putString(key, text);
                    else editor.remove(key);
                }
                assertTrue("Restore the exact pre-test home settings", editor.commit());
                JetStreamThemeController.refresh();
            });
        }
    }

    private JSONObject verifySeam(ThemeMode mode, boolean wallpaper) throws Exception {
        String name = "home-background-home-" + (mode == ThemeMode.LIGHT ? "light" : "dark")
                + (wallpaper ? "-on" : "-off");
        NativeUiEvidence.capture(name);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(150);
        int[] bounds = read(() -> {
            View hero = hero();
            int[] location = new int[2];
            hero.getLocationOnScreen(location);
            return new int[]{location[0], location[1], hero.getWidth(), hero.getHeight()};
        });
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Capture the actual HomeActivity window", screenshot);
        try {
            int boundary = bounds[1] + bounds[3];
            assertTrue("The test includes canvas below the complete hero",
                    boundary > 10 && boundary + 8 < screenshot.getHeight());
            int base = read(() -> JetStreamPalette.resolveColor(home, R.color.jetstream_background));
            int maximumJump = 0;
            int minimumWallpaperDifference = 255;
            // The middle-right strip avoids the title, button, and carousel indicators.
            for (float fraction : new float[]{0.63f, 0.68f, 0.73f}) {
                int x = bounds[0] + Math.round(bounds[2] * fraction);
                int before = averagePixel(screenshot, x, boundary - 2);
                int after = averagePixel(screenshot, x, boundary + 2);
                maximumJump = Math.max(maximumJump, colorDistance(before, after));
                minimumWallpaperDifference = Math.min(minimumWallpaperDifference, colorDistance(base, after));
                assertTrue(name + " has no hard horizontal seam at x=" + x + " (channel jump="
                        + colorDistance(before, after) + ")", colorDistance(before, after) <= 4);
                if (!wallpaper) assertTrue("Wallpaper off retains the opaque theme canvas", colorDistance(base, after) <= 1);
            }
            if (wallpaper) assertTrue("The seam blends into the visible wallpaper rather than a flat theme color",
                    minimumWallpaperDifference >= 12);
            return new JSONObject().put("theme", mode == ThemeMode.LIGHT ? "light" : "dark")
                    .put("wallpaper", wallpaper).put("heroBottom", boundary)
                    .put("maximumChannelJump", maximumJump)
                    .put("minimumWallpaperDifference", minimumWallpaperDifference).put("passed", true);
        } finally {
            screenshot.recycle();
        }
    }

    private JetStreamDiscoverImmersiveHeroLayout hero() {
        RecyclerView.ViewHolder holder = home.pageGrid().findViewHolderForAdapterPosition(0);
        if (holder == null) return null;
        return holder.itemView instanceof JetStreamDiscoverImmersiveHeroLayout hero ? hero : null;
    }

    private boolean artworkReady() {
        View hero = hero();
        if (hero == null) return false;
        for (int id : new int[]{R.id.imageA, R.id.imageB}) {
            ImageView image = hero.findViewById(id);
            if (image.getVisibility() == View.VISIBLE && image.getAlpha() >= 0.99f
                    && image.getDrawable() instanceof BitmapDrawable bitmap && !bitmap.getBitmap().isRecycled()
                    && bitmap.getBitmap().getWidth() >= 500 && bitmap.getBitmap().getHeight() >= 250) return true;
        }
        return false;
    }

    private boolean wallpaperReady() {
        Object wallpaper = HomePreviewFixtures.field(home, "wallpaper");
        if (!(wallpaper instanceof JetStreamWallView view)) return false;
        ImageView image = view.findViewById(R.id.image);
        return image != null && image.getDrawable() instanceof BitmapDrawable;
    }

    private boolean completeHeroVisible() {
        View hero = hero();
        if (hero == null || hero.getHeight() <= 0) return false;
        int[] location = new int[2];
        hero.getLocationOnScreen(location);
        return location[1] >= 0 && location[1] + hero.getHeight() + 10
                < home.getWindow().getDecorView().getHeight();
    }

    private static int averagePixel(Bitmap bitmap, int x, int y) {
        int red = 0, green = 0, blue = 0;
        for (int offset = -3; offset <= 3; offset++) {
            int color = bitmap.getPixel(x + offset, y);
            red += Color.red(color); green += Color.green(color); blue += Color.blue(color);
        }
        return Color.rgb(red / 7, green / 7, blue / 7);
    }

    private static int colorDistance(int first, int second) {
        return Math.max(Math.abs(Color.red(first) - Color.red(second)), Math.max(
                Math.abs(Color.green(first) - Color.green(second)), Math.abs(Color.blue(first) - Color.blue(second))));
    }

    private void closeHome() {
        if (home == null) return;
        TvHomePreviewActivity previous = home;
        main(previous::finish);
        await("Home closes before fixture settings change", previous::isDestroyed);
        home = null;
    }

    private void await(String message, BooleanSupplier predicate) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (read(predicate::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail(message);
    }

    private void main(Runnable operation) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { operation.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("UI operation failed", failure.get());
    }

    private <T> T read(Supplier<T> operation) {
        AtomicReference<T> value = new AtomicReference<>();
        main(() -> value.set(operation.get()));
        return value.get();
    }
}
