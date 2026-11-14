package androidx.media3.ui.danmaku;

import static org.junit.Assert.*;

import android.util.DisplayMetrics;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class DanmakuRenderPoolTest {
    static DanmakuRenderPool pool() {
        return new DanmakuRenderPool(new DanmakuPainter(new DisplayMetrics()), (items, generation) -> {});
    }

    static Object invoke(Object owner, String name, Object... arguments) throws Exception {
        Class<?> type = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            method.setAccessible(true);
            return method.invoke(owner instanceof Class ? null : owner, arguments);
        }
        throw new NoSuchMethodException(name);
    }

    static Object measurement(Object pool) throws Exception {
        var generation = pool.getClass().getDeclaredField("poolGeneration");
        generation.setAccessible(true);
        return invoke(pool, "measurement", ((AtomicInteger) generation.get(pool)).get());
    }

    static List<DanmakuRenderItem> items(String prefix, long... times) {
        List<DanmakuRenderItem> items = new ArrayList<>();
        for (int i = 0; i < times.length; i++) items.add(new DanmakuRenderItem(new Danmaku(prefix + i, times[i])));
        return items;
    }

    static void add(Object pool, List<DanmakuRenderItem> items) throws Exception {
        float[] widths = new float[items.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = i + 0.5f;
        Object chunk = invoke(pool.getClass(), "buildChunk", items, widths);
        assertEquals(true, invoke(pool, "commitChunk", chunk, measurement(pool)));
    }

    @Test
    public void shouldPreserveExclusiveStartInclusiveEndAndStableTies() throws Exception {
        DanmakuRenderPool pool = pool();
        List<DanmakuRenderItem> first = items("first", 5, 3, 3);
        List<DanmakuRenderItem> second = items("second", 2, 3, 9);
        add(pool, first);
        add(pool, second);
        DanmakuRenderItem prefix = items("prefix", 999).get(0);
        List<DanmakuRenderItem> result = new ArrayList<>(List.of(prefix));
        pool.copyRange(2, 5, result);
        assertEquals(List.of(prefix, first.get(1), first.get(2), second.get(1), first.get(0)), result);
        assertEquals(0.5f, first.get(0).measuredWidth, 0f);
        assertEquals(1.5f, first.get(1).measuredWidth, 0f);
    }

    @Test
    public void shouldFindNextTimeAcrossUnorderedOverlappingChunks() throws Exception {
        DanmakuRenderPool pool = pool();
        add(pool, items("later", 20, 10));
        add(pool, items("earlier", 9, 5, 10));
        assertEquals(Long.valueOf(5), pool.nextTimeAfter(0));
        assertEquals(Long.valueOf(9), pool.nextTimeAfter(5));
        assertEquals(Long.valueOf(20), pool.nextTimeAfter(10));
        assertNull(pool.nextTimeAfter(20));
    }

    @Test
    public void shouldHandleEmptyGapsAndLongTimeBoundaries() throws Exception {
        DanmakuRenderPool pool = pool();
        add(pool, items("edge", Long.MAX_VALUE, Long.MIN_VALUE, 0));
        add(pool, List.of());
        List<DanmakuRenderItem> result = new ArrayList<>();
        pool.copyRange(1, Long.MAX_VALUE - 1, result);
        assertTrue(result.isEmpty());
        pool.copyRange(0, 0, result);
        assertTrue(result.isEmpty());
        pool.copyRange(Long.MIN_VALUE, 0, result);
        assertEquals(1, result.size());
        assertEquals(0, result.get(0).timeMs);
        assertEquals(Long.valueOf(Long.MAX_VALUE), pool.nextTimeAfter(0));
        assertNull(pool.nextTimeAfter(Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, pool.getFirstItem().timeMs);
    }

    @Test
    public void shouldMatchSortedReferenceDuringForwardAndBackwardQueries() throws Exception {
        DanmakuRenderPool pool = pool();
        List<DanmakuRenderItem> reference = new ArrayList<>();
        Random random = new Random(314159);
        for (int chunk = 0; chunk < 80; chunk++) {
            long[] times = new long[30];
            for (int i = 0; i < times.length; i++) times[i] = random.nextInt(10_000) - 5_000;
            List<DanmakuRenderItem> items = items("chunk" + chunk + "-", times);
            reference.addAll(items);
            add(pool, items);
        }
        reference.sort(Comparator.comparingLong(item -> item.timeMs));
        List<DanmakuRenderItem> result = new ArrayList<>();
        for (int query = 0; query < 250; query++) {
            long from = random.nextInt(12_000) - 6_000;
            long to = from + random.nextInt(3_000);
            List<DanmakuRenderItem> expected = reference.stream().filter(item -> item.timeMs > from && item.timeMs <= to).toList();
            result.clear();
            pool.copyRange(from, to, result);
            assertEquals(expected, result);
        }
    }

    @Test
    public void shouldRetainHistoricalItemsForBackwardSeekAndRemeasurement() throws Exception {
        DanmakuRenderPool pool = pool();
        List<DanmakuRenderItem> past = items("past", 1, 2);
        add(pool, past);
        add(pool, items("future", 100_000));
        List<DanmakuRenderItem> result = new ArrayList<>();
        pool.copyRange(99_999, 100_000, result);
        result.clear();
        pool.copyRange(0, 2, result);
        assertEquals(past, result);
        Object measurement = measurement(pool);
        @SuppressWarnings("unchecked")
        List<DanmakuRenderItem> snapshot = (List<DanmakuRenderItem>) invoke(pool, "snapshotItems", measurement);
        assertEquals(3, snapshot.size());
        invoke(pool, "applyWidths", snapshot, new float[]{10, 20, 30}, measurement);
        assertEquals(10f, past.get(0).measuredWidth, 0f);
        assertEquals(20f, past.get(1).measuredWidth, 0f);
    }

    @Test
    public void shouldRejectLateMeasurementAfterClearOrRelease() throws Exception {
        DanmakuRenderPool pool = pool();
        List<DanmakuRenderItem> first = items("first", 1);
        Object chunk = invoke(pool.getClass(), "buildChunk", first, new float[]{5});
        Object stale = measurement(pool);
        pool.clear();
        assertEquals(false, invoke(pool, "commitChunk", chunk, stale));
        assertEquals(0, pool.size());
        add(pool, items("new", 2));
        assertEquals(1, pool.size());
        stale = measurement(pool);
        pool.release();
        assertEquals(false, invoke(pool, "commitChunk", chunk, stale));
        assertEquals(0, pool.size());
        assertNull(pool.nextTimeAfter(0));
    }

    @Test
    public void shouldRejectOldConfigurationAndDetachedMeasurement() throws Exception {
        DanmakuRenderPool pool = pool();
        List<DanmakuRenderItem> items = items("item", 1);
        add(pool, items);
        Object stale = measurement(pool);
        Object chunk = invoke(pool.getClass(), "buildChunk", items, new float[]{5});
        var generation = pool.getClass().getDeclaredField("measurementGeneration");
        generation.setAccessible(true);
        ((AtomicInteger) generation.get(pool)).incrementAndGet();
        assertEquals(false, invoke(pool, "commitChunk", chunk, stale));
        invoke(pool, "applyWidths", items, new float[]{99}, stale);
        assertEquals(5f, items.get(0).measuredWidth, 0f);
        pool = pool();
        add(pool, items);
        stale = measurement(pool);
        pool.detach();
        assertEquals(false, invoke(pool, "commitChunk", chunk, stale));
        assertEquals(1, pool.size());
    }
}
