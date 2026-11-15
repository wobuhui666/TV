package androidx.media3.ui.danmaku;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.test.CorePlaybackActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real hardware display-list recording and PixelCopy comparisons, never bitmap-canvas substitutes. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public final class DanmakuDisplayListAndroidTest {
    private static final int BACKGROUND = 0xff234567;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private CorePlaybackActivity activity;
    private DrawView view;

    @Before public void setup() {
        activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> { view = new DrawView(); activity.setContentView(view); });
        instrumentation.waitForIdleSync();
    }

    @After public void cleanup() {
        if (activity != null) main(() -> {
            view.painter.clearTextCache();
            activity.finish();
        });
        instrumentation.waitForIdleSync();
    }

    @Test public void hardwarePixelsPreserveStylesGradientsAndFractionalPositions() throws Exception {
        for (int style : new int[]{DanmakuConfig.STYLE_NONE, DanmakuConfig.STYLE_STROKE,
                DanmakuConfig.STYLE_PROJECTION, DanmakuConfig.STYLE_SHADOW}) {
            for (int color : new int[]{DanmakuConfig.COLOR_MODE_DEFAULT,
                    DanmakuConfig.COLOR_MODE_COLORFUL, DanmakuConfig.COLOR_MODE_GRADIENT}) {
                frame(view, () -> {
                    view.painter.clearTextCache();
                    view.painter.setConfig(config().setStyleMode(style).setColorMode(color)
                            .setTransparency(0.35f).setTypeface(Typeface.create("serif", Typeface.ITALIC)).build());
                    view.cached = false;
                });
                Bitmap direct = snapshot(view);
                frame(view, () -> view.cached = true);
                assertTrue("PixelCopy must exercise a hardware canvas", view.hardware);
                assertTrue("Both text rows must use actual RenderNodes", entries(view.painter).size() == 2);
                for (Object entry : entries(view.painter).values()) assertTrue(node(entry).hasDisplayList());
                Bitmap cached = snapshot(view);
                assertSimilarPixels(direct, cached, style + "/" + color);
                direct.recycle();
                cached.recycle();
            }
        }
    }

    @Test public void softwareCanvasKeepsOriginalTextDrawing() throws Exception {
        main(() -> {
            DanmakuPainter painter = new DanmakuPainter(activity.getResources().getDisplayMetrics());
            painter.setConfig(config().build());
            DanmakuRenderItem item = item("Software canvas", 0);
            Bitmap direct = Bitmap.createBitmap(1024, 256, Bitmap.Config.ARGB_8888);
            Bitmap cached = Bitmap.createBitmap(1024, 256, Bitmap.Config.ARGB_8888);
            draw(painter, new Canvas(direct), item, 40.25f, 120.5f, false);
            draw(painter, new Canvas(cached), item, 40.25f, 120.5f, true);
            assertNull(value(painter, "displayListCache"));
            int[] expected = new int[1024 * 256], actual = new int[expected.length];
            direct.getPixels(expected, 0, 1024, 0, 0, 1024, 256);
            cached.getPixels(actual, 0, 1024, 0, 0, 1024, 256);
            assertArrayEquals(expected, actual);
            direct.recycle(); cached.recycle();
        });
    }

    @Test public void visibleNodesAreReusedAndAppearanceChangesDiscardThem() throws Exception {
        frame(view, () -> view.cached = true);
        Object first = entries(view.painter).get(view.items.get(0));
        RenderNode firstNode = node(first);
        frame(view, () -> view.x += 0.5f);
        assertSame(first, entries(view.painter).get(view.items.get(0)));
        main(() -> view.painter.setConfig(config().setDurationMs(12_000).setMaxOnScreen(40).build()));
        assertSame(first, entries(view.painter).get(view.items.get(0)));
        main(() -> view.painter.setConfig(config().setTransparency(0.7f).build()));
        assertNull(value(view.painter, "displayListCache"));
        assertFalse("Configuration changes must free native display lists", firstNode.hasDisplayList());
    }

    @Test public void recordingAdmissionAndNativeMemoryStayBoundedWithoutVisibleEviction() {
        main(() -> {
            DanmakuPainter painter = new DanmakuPainter(activity.getResources().getDisplayMetrics());
            painter.setConfig(config().setTextSizeSp(8).build());
            ArrayList<DanmakuRenderItem> items = new ArrayList<>();
            for (int i = 0; i < 220; i++) items.add(item("N" + i, i));
            Object first = null;
            try {
                for (int frame = 0; frame < 105; frame++) {
                    RenderNode target = new RenderNode("Test frame");
                    Canvas canvas = target.beginRecording(1024, 256);
                    assertTrue(canvas.isHardwareAccelerated());
                    painter.beginFrame();
                    try {
                        for (DanmakuRenderItem item : items) draw(painter, canvas, item, 40, 120, true);
                    } finally { target.endRecording(); target.discardDisplayList(); }
                    if (frame == 0) {
                        assertEquals("Only two new recordings per frame", 2, entries(painter).size());
                        first = entries(painter).get(items.get(0));
                    }
                    assertSame("Visible nodes must not be evicted", first, entries(painter).get(items.get(0)));
                    assertTrue(entries(painter).size() <= 200);
                    long bytes = (long) value(value(painter, "displayListCache"), "bytes");
                    assertTrue(bytes > 0 && bytes <= 4 * 1024 * 1024);
                }
                assertEquals(200, entries(painter).size());
                assertTrue(node(first).hasDisplayList());
            } finally { painter.clearTextCache(); }
            assertFalse(node(first).hasDisplayList());
        });
    }

    @Test public void unusedItemsDiscardTheirNativeDisplayLists() throws Exception {
        frame(view, () -> view.cached = true);
        RenderNode old = node(entries(view.painter).get(view.items.get(0)));
        frame(view, () -> view.items.clear());
        frame(view, () -> {});
        assertTrue(entries(view.painter).isEmpty());
        assertFalse(old.hasDisplayList());
    }

    @Test public void stoppedClearedHiddenAndExpiredViewsReleaseNativeListsImmediately() throws Exception {
        for (String action : new String[]{"stop", "clear", "release", "replace", "seek", "resize", "hide", "expiry"}) {
            DanmakuView[] overlay = new DanmakuView[1];
            main(() -> {
                overlay[0] = new DanmakuView(activity);
                activity.setContentView(overlay[0]);
            });
            instrumentation.waitForIdleSync();
            DanmakuView active = overlay[0];
            try {
                frame(active, () -> {
                    active.setConfig(config().setFixedDurationMs(5_000).build());
                    active.start(0);
                    active.sendNow(new Danmaku("Cache lifecycle", 0, Danmaku.TYPE_TOP, Color.WHITE));
                    active.pause();
                });
                DanmakuPainter painter = (DanmakuPainter) value(active, "painter");
                assertEquals(action, 1, entries(painter).size());
                RenderNode old = node(entries(painter).values().iterator().next());
                if (action.equals("expiry")) {
                    frame(active, () -> active.getFirstActiveItem().activatedTimeMs = active.getCurrentPositionMs() - 5_000);
                } else main(() -> {
                    switch (action) {
                        case "stop": active.stop(); break;
                        case "clear": active.clear(); break;
                        case "release": active.release(); break;
                        case "replace": active.replacePool(Collections.emptyList()); break;
                        case "seek": active.resume(); active.seekTo(10_000); break;
                        case "resize": active.layout(0, 0, active.getWidth() - 20, active.getHeight()); break;
                        case "hide": active.setDrawEnabled(false); break;
                        default: throw new AssertionError(action);
                    }
                });
                assertNull(action + " must release references without another frame", value(painter, "displayListCache"));
                assertFalse(action + " must discard native content", old.hasDisplayList());
            } finally { main(active::release); }
        }
    }

    private DanmakuConfig.Builder config() { return new DanmakuConfig.Builder().setTextSizeSp(18); }

    private static DanmakuRenderItem item(String text, long time) {
        return new DanmakuRenderItem(new Danmaku(text, time, Danmaku.TYPE_TOP, 0xdde9b566, 0,
                new int[]{Color.RED, Color.GREEN, Color.BLUE}));
    }

    private static void draw(DanmakuPainter painter, Canvas canvas, DanmakuRenderItem item,
                             float x, float y, boolean cached) {
        try {
            item.measuredWidth = painter.measureWidth(item);
            Method method = DanmakuPainter.class.getDeclaredMethod(cached ? "drawAt" : "drawTextAt",
                    Canvas.class, DanmakuRenderItem.class, float.class, float.class, float.class);
            method.setAccessible(true);
            method.invoke(painter, canvas, item, x, y, painter.textSizePx());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static Object value(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static Map<?, ?> entries(DanmakuPainter painter) {
        Object cache = value(painter, "displayListCache");
        assertNotNull("Hardware drawing must create the real display-list cache", cache);
        return (Map<?, ?>) value(cache, "entries");
    }

    private static RenderNode node(Object entry) {
        assertNotNull(entry);
        RenderNode node = (RenderNode) value(entry, "node");
        assertNotNull(node);
        return node;
    }

    private void main(Runnable job) { instrumentation.runOnMainSync(job); }

    private void frame(View target, Runnable change) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        main(() -> {
            change.run();
            target.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            target.invalidate();
        });
        assertTrue("A hardware frame must commit before PixelCopy", committed.await(5, TimeUnit.SECONDS));
    }

    private Bitmap snapshot(View target) throws Exception {
        Bitmap[] bitmap = new Bitmap[1];
        CountDownLatch copied = new CountDownLatch(1);
        AtomicInteger status = new AtomicInteger(-1);
        main(() -> {
            int[] location = new int[2];
            target.getLocationInWindow(location);
            int height = Math.min(256, target.getHeight());
            bitmap[0] = Bitmap.createBitmap(target.getWidth(), height, Bitmap.Config.ARGB_8888);
            PixelCopy.request(activity.getWindow(), new Rect(location[0], location[1],
                    location[0] + target.getWidth(), location[1] + height), bitmap[0],
                    result -> { status.set(result); copied.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        assertTrue(copied.await(5, TimeUnit.SECONDS));
        assertEquals(PixelCopy.SUCCESS, status.get());
        return bitmap[0];
    }

    private static void assertSimilarPixels(Bitmap direct, Bitmap cached, String label) {
        int width = direct.getWidth(), height = direct.getHeight();
        int[] expected = new int[width * height], actual = new int[expected.length];
        direct.getPixels(expected, 0, width, 0, 0, width, height);
        cached.getPixels(actual, 0, width, 0, 0, width, height);
        long difference = 0;
        int ink = 0;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] == BACKGROUND && actual[i] == BACKGROUND) continue;
            ink++;
            difference += Math.abs(Color.red(expected[i]) - Color.red(actual[i]));
            difference += Math.abs(Color.green(expected[i]) - Color.green(actual[i]));
            difference += Math.abs(Color.blue(expected[i]) - Color.blue(actual[i]));
        }
        assertTrue("Must compare visible glyphs", ink > 100);
        double normalized = difference / (ink * 3.0 * 255);
        assertTrue(label + " normalized glyph difference=" + normalized, normalized <= 0.015);
    }

    private final class DrawView extends View {
        final DanmakuPainter painter;
        final ArrayList<DanmakuRenderItem> items = new ArrayList<>();
        boolean cached = true;
        volatile boolean hardware;
        float x = 32.25f;

        DrawView() {
            super(activity);
            painter = new DanmakuPainter(getResources().getDisplayMetrics());
            painter.setConfig(config().build());
            items.add(item("弹幕 jW 123", 0));
            items.add(item("Arabic مرحبا", 1));
        }

        @Override protected void onDraw(Canvas canvas) {
            hardware = canvas.isHardwareAccelerated();
            canvas.drawColor(BACKGROUND);
            painter.beginFrame();
            for (int i = 0; i < items.size(); i++) {
                DanmakuDisplayListAndroidTest.draw(painter, canvas, items.get(i), x, 76.5f + i * 100, cached);
            }
        }
    }
}
