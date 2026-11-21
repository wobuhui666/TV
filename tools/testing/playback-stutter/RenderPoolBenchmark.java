package androidx.media3.ui.danmaku;

import android.util.DisplayMetrics;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/** Same production before/after classes and shared input; headless allocation/CPU comparison. */
public final class RenderPoolBenchmark {
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        DanmakuRenderPool patched = DanmakuRenderPoolTest.pool();
        LegacyDanmakuRenderPool original = new LegacyDanmakuRenderPool(
                new DanmakuPainter(new DisplayMetrics()), (items, generation) -> {});
        for (int chunk = 0; chunk < 80; chunk++) {
            List<DanmakuRenderItem> items = new ArrayList<>();
            for (int i = 0; i < 1_200; i++) {
                items.add(new DanmakuRenderItem(new Danmaku("item" + i, chunk * 90_000L + i * 100L)));
            }
            DanmakuRenderPoolTest.add(patched, items);
            DanmakuRenderPoolTest.add(original, items);
        }
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        for (boolean empty : new boolean[]{false, true}) {
            long to = empty ? 8_000_000 : 79 * 90_000L + 60_000L;
            List<DanmakuRenderItem> output = new ArrayList<>(256);
            for (int warm = 0; warm < 5_000; warm++) {
                original.copyRange(to - 3_000, to, output); output.clear();
                patched.copyRange(to - 3_000, to, output); output.clear();
            }
            long thread = Thread.currentThread().threadId();
            long originalBytes = memory.getThreadAllocatedBytes(thread);
            long originalNs = System.nanoTime();
            for (int i = 0; i < 20_000; i++) {
                original.copyRange(to - 3_000, to, output); sink += output.size(); output.clear();
            }
            originalNs = System.nanoTime() - originalNs;
            originalBytes = memory.getThreadAllocatedBytes(thread) - originalBytes;
            long patchedBytes = memory.getThreadAllocatedBytes(thread);
            long patchedNs = System.nanoTime();
            for (int i = 0; i < 20_000; i++) {
                patched.copyRange(to - 3_000, to, output); sink += output.size(); output.clear();
            }
            patchedNs = System.nanoTime() - patchedNs;
            patchedBytes = memory.getThreadAllocatedBytes(thread) - patchedBytes;
            if (patchedBytes / 20_000 > 64 || originalBytes <= patchedBytes) {
                throw new AssertionError("Historical chunks should not allocate per query");
            }
            System.out.println("{\"case\":\"" + (empty ? "empty_window" : "current_window")
                    + "\",\"chunks\":80,\"items\":96000,\"queries\":20000,\"originalBytesPerQuery\":"
                    + originalBytes / 20_000 + ",\"patchedBytesPerQuery\":" + patchedBytes / 20_000
                    + ",\"originalNsPerQuery\":" + originalNs / 20_000 + ",\"patchedNsPerQuery\":"
                    + patchedNs / 20_000 + "}");
        }
    }
}
