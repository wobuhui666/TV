package androidx.media3.ui.danmaku;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.DisplayMetrics;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/** Exercises real Canvas text rasterization and the bounded cache, including fallback drawing. */
@RunWith(AndroidJUnit4.class)
public final class DanmakuTextCacheAndroidTest {
    private static final int BACKGROUND = 0xff234567;

    @Test
    public void cachedStylesPreserveInkAndColorsAtIntegerCoordinates() throws Exception {
        for (int style : new int[]{DanmakuConfig.STYLE_NONE, DanmakuConfig.STYLE_STROKE,
                DanmakuConfig.STYLE_PROJECTION, DanmakuConfig.STYLE_SHADOW}) {
            for (int colorMode : new int[]{DanmakuConfig.COLOR_MODE_DEFAULT,
                    DanmakuConfig.COLOR_MODE_COLORFUL, DanmakuConfig.COLOR_MODE_GRADIENT}) {
                DanmakuConfig config = config().setStyleMode(style).setColorMode(colorMode).build();
                DanmakuRenderItem item = item("测试 Arabic مرحبا jW", 0xffe4ab68, 0,
                        new int[]{Color.RED, Color.GREEN, Color.BLUE});
                assertImagesSimilar(config, item, 32, 76, 0.015);
            }
        }
    }

    @Test
    public void transparencyAndFractionalMovementPreserveAppearance() throws Exception {
        for (int style : new int[]{DanmakuConfig.STYLE_NONE, DanmakuConfig.STYLE_STROKE,
                DanmakuConfig.STYLE_PROJECTION}) {
            DanmakuConfig config = config().setStyleMode(style).setTransparency(0.4f)
                    .setColorMode(DanmakuConfig.COLOR_MODE_GRADIENT).build();
            // Fractional bitmap translation and text hinting differ slightly at glyph edges.
            assertImagesSimilar(config, item("弹幕 jW movement", 0xbbea9854, 0, null),
                    32.25f, 76.5f, 0.16);
        }
    }

    @Test
    public void italicOverhangAndItemTextSizeAreNotClipped() throws Exception {
        DanmakuConfig config = config().setTypeface(Typeface.create("serif", Typeface.ITALIC))
                .setTextBold(true).setStrokeWidthMultiplier(0.3f).build();
        assertImagesSimilar(config, item("jfÁý W", Color.WHITE, 48, null), 36, 85, 0.015);
    }

    @Test
    public void displayDensityDoesNotScaleCachedPhysicalPixels() throws Exception {
        assertImagesSimilar(config().build(), item("density independent", Color.WHITE, 0, null),
                32, 76, 0.015, DisplayMetrics.DENSITY_XXXHIGH);
    }

    @Test
    public void visibleTextReusesBitmapAndUnusedTextExpires() throws Exception {
        DanmakuPainter painter = painter(config().build());
        DanmakuRenderItem item = item("same visible text", Color.WHITE, 0, null);
        Canvas canvas = canvas();
        draw(painter, canvas, item, 32, 76, true);
        Object cached = cache(painter).get(item);
        assertNotNull(bitmap(cached));
        for (int frame = 0; frame < 10; frame++) {
            draw(painter, canvas, item, 32 + frame, 76, true);
            assertSame(cached, cache(painter).get(item));
        }
        painter.beginFrame();
        assertTrue(cache(painter).containsKey(item));
        painter.beginFrame();
        assertTrue(cache(painter).isEmpty());
        assertEquals(0, integer(painter, "textCacheBytes"));
    }

    @Test
    public void timingChangesKeepCacheButAppearanceChangesInvalidateIt() throws Exception {
        DanmakuConfig original = config().build();
        DanmakuPainter painter = painter(original);
        DanmakuRenderItem item = item("change style", Color.WHITE, 0, null);
        draw(painter, canvas(), item, 32, 76, true);
        Object cached = cache(painter).get(item);
        painter.setConfig(original.buildUpon().setDurationMs(12_000).setMaxOnScreen(40).build());
        assertSame(cached, cache(painter).get(item));
        painter.setConfig(original.buildUpon().setTransparency(0.5f).build());
        assertTrue(cache(painter).isEmpty());
        assertEquals(0, integer(painter, "textCacheBytes"));
        draw(painter, canvas(), item, 32, 76, true);
        assertNotSame(cached, cache(painter).get(item));
    }

    @Test
    public void cacheAdmissionIsLimitedPerFrame() throws Exception {
        DanmakuPainter painter = painter(config().build());
        Canvas canvas = canvas();
        painter.beginFrame();
        for (int i = 0; i < 100; i++) drawInFrame(painter, canvas,
                item("new item " + i, Color.WHITE, 0, null), 32, 76, true);
        assertEquals(2, cache(painter).size());
    }

    @Test
    public void fullCacheDoesNotEvictVisibleTextOrRebuildRejectedItems() throws Exception {
        DanmakuPainter painter = painter(config().setTextSizeSp(50).build());
        Canvas canvas = canvas();
        ArrayList<DanmakuRenderItem> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) items.add(item("A fairly long visible comment " + i,
                Color.WHITE, 0, null));
        for (int frame = 0; frame < 55; frame++) {
            painter.beginFrame();
            for (DanmakuRenderItem item : items) drawInFrame(painter, canvas, item, 0, 76, true);
            assertTrue(integer(painter, "textCacheBytes") <= 4 * 1024 * 1024);
        }
        Object first = cache(painter).get(items.get(0));
        Object last = cache(painter).get(items.get(items.size() - 1));
        assertNotNull(bitmap(first));
        assertNotNull("Budget rejection should be remembered while visible", last);
        assertNull(bitmap(last));
        int bytes = integer(painter, "textCacheBytes");
        for (int frame = 0; frame < 5; frame++) {
            painter.beginFrame();
            for (DanmakuRenderItem item : items) drawInFrame(painter, canvas, item, 0, 76, true);
            assertSame(first, cache(painter).get(items.get(0)));
            assertSame(last, cache(painter).get(items.get(items.size() - 1)));
            assertEquals(bytes, integer(painter, "textCacheBytes"));
        }
    }

    @Test
    public void oversizedTextAndShadowUseOriginalDrawing() throws Exception {
        DanmakuPainter painter = painter(config().build());
        DanmakuRenderItem longItem = item(new String(new char[500]).replace('\0', 'W'),
                Color.WHITE, 0, null);
        draw(painter, canvas(), longItem, 0, 76, true);
        assertNull(bitmap(cache(painter).get(longItem)));
        assertEquals(0, integer(painter, "textCacheBytes"));
        painter.setConfig(config().setStyleMode(DanmakuConfig.STYLE_SHADOW).build());
        draw(painter, canvas(), item("shadow", Color.WHITE, 0, null), 32, 76, true);
        assertTrue(cache(painter).isEmpty());
    }

    @Test
    public void viewLifecycleAndFinalExpiryReleaseCachedTextWithoutAnotherFrame() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (String action : new String[]{"stop", "clear", "release", "replace", "seek",
                    "resize", "hide", "expiry"}) {
                DanmakuView view = new DanmakuView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                try {
                    view.layout(0, 0, 1024, 160);
                    view.setConfig(config().setTextSizeSp(10).setFixedDurationMs(5_000).build());
                    view.start(0);
                    view.sendNow(new Danmaku("Cache lifecycle", 0, Danmaku.TYPE_TOP, Color.WHITE));
                    view.pause();
                    view.draw(canvas());
                    Field field = DanmakuView.class.getDeclaredField("painter");
                    field.setAccessible(true);
                    DanmakuPainter painter = (DanmakuPainter) field.get(view);
                    assertEquals(1, view.getActiveItemCount());
                    assertTrue("A real View frame must populate the cache before " + action,
                            integer(painter, "textCacheBytes") > 0);
                    switch (action) {
                        case "stop": view.stop(); break;
                        case "clear": view.clear(); break;
                        case "release": view.release(); break;
                        case "replace": view.replacePool(Collections.emptyList()); break;
                        case "seek": view.resume(); view.seekTo(10_000); break;
                        case "resize": view.layout(0, 0, 800, 160); break;
                        case "hide": view.setDrawEnabled(false); break;
                        case "expiry":
                            view.getFirstActiveItem().activatedTimeMs = view.getCurrentPositionMs() - 5_000;
                            view.draw(canvas());
                            assertEquals(0, view.getActiveItemCount());
                            break;
                        default: throw new AssertionError(action);
                    }
                    assertEquals(action + " must release cached pixels immediately", 0,
                            integer(painter, "textCacheBytes"));
                    assertTrue(action + " must release cached item references", cache(painter).isEmpty());
                } catch (Exception failure) {
                    throw new AssertionError(action, failure);
                } finally {
                    view.release();
                }
            }
        });
    }

    private static DanmakuConfig.Builder config() {
        return new DanmakuConfig.Builder().setTextSizeSp(30).setStyleMode(DanmakuConfig.STYLE_STROKE);
    }

    private static DanmakuRenderItem item(String text, int color, float size, int[] gradient) {
        return new DanmakuRenderItem(new Danmaku(text, 0, Danmaku.TYPE_SCROLL, color, size, gradient));
    }

    private static DanmakuPainter painter(DanmakuConfig config) {
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.density = metrics.scaledDensity = 1;
        DanmakuPainter painter = new DanmakuPainter(metrics);
        painter.setConfig(config);
        return painter;
    }

    private static Canvas canvas() {
        return new Canvas(Bitmap.createBitmap(1024, 160, Bitmap.Config.ARGB_8888));
    }

    private static void draw(DanmakuPainter painter, Canvas canvas, DanmakuRenderItem item,
                             float x, float y, boolean cached) throws Exception {
        painter.beginFrame();
        drawInFrame(painter, canvas, item, x, y, cached);
    }

    private static void drawInFrame(DanmakuPainter painter, Canvas canvas, DanmakuRenderItem item,
                                    float x, float y, boolean cached) throws Exception {
        item.measuredWidth = painter.measureWidth(item);
        float size = item.textSizeSp > 0 ? painter.resolveTextSizePx(item.textSizeSp) : painter.textSizePx();
        Method setSize = DanmakuPainter.class.getDeclaredMethod("setPaintSize", float.class);
        setSize.setAccessible(true);
        setSize.invoke(painter, size);
        Method draw = DanmakuPainter.class.getDeclaredMethod(cached ? "drawAt" : "drawTextAt",
                Canvas.class, DanmakuRenderItem.class, float.class, float.class, float.class);
        draw.setAccessible(true);
        draw.invoke(painter, canvas, item, x, y, size);
    }

    private static Map<?, ?> cache(DanmakuPainter painter) throws Exception {
        Field field = DanmakuPainter.class.getDeclaredField("textCache");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(painter);
    }

    private static Bitmap bitmap(Object cached) throws Exception {
        assertNotNull(cached);
        Field field = cached.getClass().getDeclaredField("bitmap");
        field.setAccessible(true);
        return (Bitmap) field.get(cached);
    }

    private static int integer(DanmakuPainter painter, String name) throws Exception {
        Field field = DanmakuPainter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(painter);
    }

    private static void assertImagesSimilar(DanmakuConfig config, DanmakuRenderItem item,
                                             float x, float y, double tolerance) throws Exception {
        assertImagesSimilar(config, item, x, y, tolerance, Bitmap.DENSITY_NONE);
    }

    private static void assertImagesSimilar(DanmakuConfig config, DanmakuRenderItem item,
                                             float x, float y, double tolerance, int density) throws Exception {
        Bitmap direct = Bitmap.createBitmap(1024, 160, Bitmap.Config.ARGB_8888);
        Bitmap cached = Bitmap.createBitmap(1024, 160, Bitmap.Config.ARGB_8888);
        direct.eraseColor(BACKGROUND);
        cached.eraseColor(BACKGROUND);
        Canvas directCanvas = new Canvas(direct), cachedCanvas = new Canvas(cached);
        directCanvas.setDensity(density);
        cachedCanvas.setDensity(density);
        draw(painter(config), directCanvas, item, x, y, false);
        DanmakuPainter painter = painter(config);
        draw(painter, cachedCanvas, item, x, y, true);
        if (config.styleMode != DanmakuConfig.STYLE_SHADOW) {
            assertNotNull("Comparison must actually exercise a cached bitmap", bitmap(cache(painter).get(item)));
            assertEquals(Bitmap.DENSITY_NONE, bitmap(cache(painter).get(item)).getDensity());
        }
        int[] reference = new int[1024 * 160], actual = new int[reference.length];
        direct.getPixels(reference, 0, 1024, 0, 0, 1024, 160);
        cached.getPixels(actual, 0, 1024, 0, 0, 1024, 160);
        long difference = 0;
        int ink = 0;
        for (int i = 0; i < reference.length; i++) {
            if (reference[i] == BACKGROUND && actual[i] == BACKGROUND) continue;
            ink++;
            difference += Math.abs(Color.red(reference[i]) - Color.red(actual[i]));
            difference += Math.abs(Color.green(reference[i]) - Color.green(actual[i]));
            difference += Math.abs(Color.blue(reference[i]) - Color.blue(actual[i]));
        }
        assertTrue("Text must render visible ink", ink > 100);
        double normalizedDifference = difference / (ink * 3.0 * 255);
        assertTrue("style=" + config.styleMode + " color=" + config.colorMode
                + " normalized ink difference=" + normalizedDifference,
                normalizedDifference <= tolerance);
        direct.recycle();
        cached.recycle();
    }
}
