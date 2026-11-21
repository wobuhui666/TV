package com.fongmi.android.tv.test;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;

import static org.junit.Assert.*;

/** Verify the installed TV entry point through the same APIs used by launchers. */
@RunWith(AndroidJUnit4.class)
public final class LauncherBannerTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void shouldExposeAWideBitmapThroughBothLauncherEntryPoints() throws Exception {
        PackageManager manager = context.getPackageManager();
        ApplicationInfo application = manager.getApplicationInfo(context.getPackageName(), 0);
        assertTrue("The installed application advertises a banner", application.banner != 0);
        verifyBanner(manager.getApplicationBanner(application), "application").recycle();
        for (String category : List.of(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(context.getPackageName());
            List<ResolveInfo> matches = manager.queryIntentActivities(intent, 0);
            assertEquals("One discoverable TV entry for " + category, 1, matches.size());
            ActivityInfo activity = matches.get(0).activityInfo;
            assertEquals("The existing entry remains HomeActivity", "com.fongmi.android.tv.ui.activity.HomeActivity", activity.name);
            assertTrue("Launchers reading the activity directly receive its banner", activity.banner != 0);
            assertEquals("Application and activity advertise the same card", application.banner, activity.banner);
            verifyBanner(activity.loadBanner(manager), category).recycle();
            verifyBanner(manager.getActivityBanner(new ComponentName(activity.packageName, activity.name)), category).recycle();
        }
    }

    @Test public void shouldLoadLocalizedFullCardsWithoutAnAdaptiveMask() throws Exception {
        ApplicationInfo application = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
        Set<Integer> labelHashes = new HashSet<>();
        Integer expectedLogoHash = null;
        for (String language : List.of("en", "zh-CN", "zh-TW")) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocale(Locale.forLanguageTag(language));
            Context localized = context.createConfigurationContext(configuration);
            Drawable banner = localized.getResources().getDrawable(application.banner, localized.getTheme());
            Bitmap bitmap = verifyBanner(banner, language);
            try {
                int[] logo = new int[320 * 105];
                bitmap.getPixels(logo, 0, 320, 0, 0, 320, 105);
                int logoHash = Arrays.hashCode(logo);
                if (expectedLogoHash == null) expectedLogoHash = logoHash;
                assertEquals("The same brand mark is used in every locale", expectedLogoHash.intValue(), logoHash);
                int[] label = new int[320 * 75];
                bitmap.getPixels(label, 0, 320, 0, 105, 320, 75);
                assertTrue("Each locale loads its own name artwork: " + language, labelHashes.add(Arrays.hashCode(label)));
                File directory = context.getExternalFilesDir("native-usability-evidence");
                assertNotNull(directory);
                assertTrue(directory.isDirectory() || directory.mkdirs());
                try (FileOutputStream output = new FileOutputStream(new File(directory, "launcher-banner-" + language + ".png"))) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                }
            } finally {
                bitmap.recycle();
            }
        }
    }

    private Bitmap verifyBanner(Drawable banner, String source) {
        assertNotNull("A launcher can load the banner: " + source, banner);
        assertTrue("The card remains a bitmap on old and new Android: " + source, banner instanceof BitmapDrawable);
        int width = banner.getIntrinsicWidth();
        int height = banner.getIntrinsicHeight();
        assertTrue("The banner is landscape: " + source, width > height && height > 0);
        assertEquals("The launcher receives 16:9 artwork: " + source, 16f / 9f, (float) width / height, 0.02f);
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        banner.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        banner.draw(new Canvas(bitmap));
        int logoPixels = 0;
        int labelPixels = 0;
        Rect logoBounds = new Rect();
        Rect labelBounds = new Rect();
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                assertEquals("The artwork fills the entire launcher card", 255, Color.alpha(pixel));
                if (Color.red(pixel) < 120 && Color.green(pixel) < 120 && Color.blue(pixel) < 120) {
                    if (y < 105) {
                        logoPixels++;
                        logoBounds.union(x, y, x + 1, y + 1);
                    } else {
                        labelPixels++;
                        labelBounds.union(x, y, x + 1, y + 1);
                    }
                }
            }
        }
        assertTrue("The brand mark is visible: " + source, logoPixels > 1000);
        assertTrue("The app name is rendered below the mark: " + source, labelPixels > 200);
        assertEquals("The visible brand is horizontally centered: " + source, 160d, logoBounds.exactCenterX(), 1d);
        assertEquals("The visible name is horizontally centered: " + source, 160d, labelBounds.exactCenterX(), 1d);
        assertEquals("The brand and name have balanced top and bottom space: " + source,
                90d, (logoBounds.top + labelBounds.bottom) / 2d, 1d);
        return bitmap;
    }
}
