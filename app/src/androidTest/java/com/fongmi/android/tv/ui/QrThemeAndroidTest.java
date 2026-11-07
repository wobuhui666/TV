package com.fongmi.android.tv.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;

import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.ThemeMode;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.activity.PushActivity;
import com.fongmi.android.tv.ui.rex.RexTheme;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.ui.theme.JetStreamThemeController;
import com.fongmi.android.tv.utils.QRCode;
import com.google.android.material.color.MaterialColors;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class QrThemeAndroidTest {

    private static final String ADDRESS = "http://192.168.1.100:9978?tab=4";
    private Instrumentation instrumentation;
    private ThemeMode originalTheme;
    private boolean serverWasRunning;

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        originalTheme = Setting.getThemeMode();
        serverWasRunning = Server.get().isRunning();
    }

    @After
    public void tearDown() {
        main(() -> {
            PushActivity activity = resumedPush();
            if (activity != null) activity.finish();
            Setting.putThemeMode(originalTheme);
            JetStreamThemeController.refresh();
        });
        instrumentation.waitForIdleSync();
        if (!serverWasRunning && Server.get().isRunning()) {
            Server.get().stop();
            long deadline = SystemClock.uptimeMillis() + 3_000;
            while (Server.get().isRunning() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25);
        }
    }

    @Test
    public void shouldUseDarkModulesOnTheLightSurfaceAndDecodeTheOriginalAddress() throws Exception {
        setMode(ThemeMode.LIGHT);
        assertGeneratedCode(QRCode.getJetStreamBitmap(ADDRESS, 200, 0), ADDRESS, true);
    }

    @Test
    public void shouldUseLightModulesOnTheDarkSurfaceAndDecodeTheOriginalAddress() throws Exception {
        setMode(ThemeMode.DARK);
        assertGeneratedCode(QRCode.getJetStreamBitmap(ADDRESS, 200, 0), ADDRESS, false);
    }

    @Test
    public void shouldKeepTheOpaqueQuietZoneForExistingZeroAndOneMarginCallers() throws Exception {
        String[] addresses = {ADDRESS, "https://example.invalid/login?code=fixture",
                "http://192.168.1.100:9978/?tab=2&title=简体中文节目&episode=2026-03-01"};
        for (ThemeMode mode : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            setMode(mode);
            for (String address : addresses) for (int margin : new int[]{0, 1}) {
                assertGeneratedCode(QRCode.getBitmap(address, 180, margin), address, mode == ThemeMode.LIGHT);
            }
        }
    }

    @Test
    public void shouldUseTheMaterialColorsOfTheDialogContext() throws Exception {
        Context base = instrumentation.getTargetContext();
        for (int style : new int[]{R.style.Theme_App_Light, R.style.Theme_App}) {
            Context context = new ContextThemeWrapper(base, style);
            int background = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.BLACK);
            Bitmap code = QRCode.getThemedBitmap(context, ADDRESS, 180, 0);
            assertNotNull(code);
            try {
                assertEquals(ColorUtils.setAlphaComponent(background, 255), code.getPixel(0, 0));
                assertEquals(ADDRESS, decode(code, ColorUtils.calculateLuminance(background) < 0.5));
            } finally {
                code.recycle();
            }
        }
        Bitmap positive = QRCode.getLightBitmap(ADDRESS, 180, 0);
        assertNotNull(positive);
        try {
            assertEquals("Explicit positive-polarity QR stays portable", Color.WHITE, positive.getPixel(0, 0));
            assertEquals(ADDRESS, decode(positive, false));
        } finally {
            positive.recycle();
        }
    }

    @Test
    public void shouldRejectAnEmptyOrUnencodablePayload() {
        assertNull(QRCode.getJetStreamBitmap("", 200, 0));
        assertNull(QRCode.getJetStreamBitmap("x".repeat(6500), 200, 0));
    }

    @Test
    public void shouldDisplayAScannablePushPageInLightMode() throws Exception {
        verifyPushPage(ThemeMode.LIGHT, "push-qr-light");
    }

    @Test
    public void shouldDisplayAScannablePushPageInDarkMode() throws Exception {
        verifyPushPage(ThemeMode.DARK, "push-qr-dark");
    }

    @Test
    public void shouldRegenerateTheVisiblePushCodeWhenTheThemeChanges() throws Exception {
        setMode(ThemeMode.DARK);
        PushActivity previous = launchPush();
        assertVisibleCode(false);
        main(() -> RexTheme.setMode(ThemeMode.LIGHT));
        await(() -> {
            PushActivity active = resumedPush();
            return active != null && active != previous && active.getWindow().getDecorView().hasWindowFocus();
        }, "push page recreated in the light theme");
        instrumentation.waitForIdleSync();
        SystemClock.sleep(700);
        assertVisibleCode(true);
        NativeUiEvidence.capture("push-qr-theme-changed");
    }

    private void setMode(ThemeMode mode) {
        main(() -> {
            Setting.putThemeMode(mode);
            JetStreamThemeController.refresh();
        });
    }

    private void verifyPushPage(ThemeMode mode, String evidence) throws Exception {
        setMode(mode);
        launchPush();
        assertVisibleCode(mode == ThemeMode.LIGHT);
        NativeUiEvidence.capture(evidence);
    }

    private PushActivity launchPush() {
        Context context = instrumentation.getTargetContext();
        assertTrue("Visible QR checks run only in isolated validation packages",
                context.getPackageName().equals("com.fongmi.android.tv.preview")
                        || context.getPackageName().equals("com.fongmi.android.tv.sourceprobe"));
        Server.get().start();
        assertTrue("The local server starts before its address is shown", Server.get().isRunning());
        Intent intent = new Intent(context, PushActivity.class).putExtra("tab", 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PushActivity activity = (PushActivity) instrumentation.startActivitySync(intent);
        await(() -> resumedPush() == activity && activity.getWindow().getDecorView().hasWindowFocus(), "push page visible");
        instrumentation.waitForIdleSync();
        SystemClock.sleep(700);
        return activity;
    }

    private void assertVisibleCode(boolean light) throws Exception {
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try {
            assertEquals("The actual window must preserve the full push deep link",
                    Server.get().getAddress(2), decode(screenshot, !light));
        } finally {
            screenshot.recycle();
        }
    }

    private void assertGeneratedCode(Bitmap code, String expected, boolean light) throws Exception {
        assertNotNull(code);
        try {
            int background = JetStreamPalette.surfaceInt();
            int foreground = JetStreamPalette.onSurfaceInt();
            assertEquals(background, code.getPixel(0, 0));
            assertEquals(255, Color.alpha(code.getPixel(0, 0)));
            assertEquals(255, Color.alpha(code.getPixel(code.getWidth() - 1, code.getHeight() - 1)));
            assertTrue("The QR module contrast must remain at least 7:1", ColorUtils.calculateContrast(foreground, background) >= 7);
            assertEquals(light, ColorUtils.calculateLuminance(foreground) < ColorUtils.calculateLuminance(background));
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 4);
            BitMatrix matrix = new MultiFormatWriter().encode(expected, BarcodeFormat.QR_CODE, code.getWidth(), code.getHeight(), hints);
            int quiet = 0;
            while (!matrix.get(quiet, quiet)) quiet++;
            int finder = 0;
            while (matrix.get(quiet + finder, quiet)) finder++;
            assertTrue("A four-module quiet zone survives legacy margin=0", quiet >= finder / 7 * 4);
            assertEquals(foreground, code.getPixel(quiet + finder / 2, quiet + finder / 2));
            for (int x = 0; x < code.getWidth(); x++) {
                assertEquals(background, code.getPixel(x, quiet - 1));
                assertEquals(background, code.getPixel(x, code.getHeight() - quiet));
            }
            assertEquals(expected, decode(code, !light));
        } finally {
            code.recycle();
        }
    }

    private static String decode(Bitmap bitmap, boolean inverted) throws Exception {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        if (inverted) source = source.invert();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, true);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)), hints).getText();
    }

    private PushActivity resumedPush() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
            if (activity instanceof PushActivity) return (PushActivity) activity;
        }
        return null;
    }

    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 25_000;
        do {
            if (read(condition::getAsBoolean)) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(message);
    }

    private void main(Runnable operation) { instrumentation.runOnMainSync(operation); }

    private <T> T read(Supplier<T> operation) {
        AtomicReference<T> value = new AtomicReference<>();
        main(() -> value.set(operation.get()));
        return value.get();
    }
}
