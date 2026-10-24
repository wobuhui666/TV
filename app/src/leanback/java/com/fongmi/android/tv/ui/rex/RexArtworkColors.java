package com.fongmi.android.tv.ui.rex;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.palette.graphics.Palette;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sample the displayed artwork once; never download another image just to obtain its color. */
public final class RexArtworkColors {
    private static final LruCache<String, Integer> CACHE = new LruCache<>(64);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, List<IntConsumer>> PENDING = new HashMap<>();

    private RexArtworkColors() { }

    public static void from(String artwork, Bitmap bitmap, IntConsumer callback) {
        Integer cached = CACHE.get(artwork);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        List<IntConsumer> waiting = PENDING.get(artwork);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(callback);
        PENDING.put(artwork, waiting);
        // Own a tiny software copy: Glide may recycle its original while the worker runs.
        Bitmap sample = Bitmap.createScaledBitmap(bitmap, 64, 36, true);
        if (sample == bitmap) sample = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap owned = sample;
        WORKER.execute(() -> {
            int extracted;
            try {
                extracted = Palette.from(owned).maximumColorCount(8).clearFilters().generate().getDominantColor(0xFF777777);
            } catch (RuntimeException error) {
                extracted = 0xFF777777;
            } finally {
                owned.recycle();
            }
            int color = extracted;
            CACHE.put(artwork, color);
            MAIN.post(() -> {
                List<IntConsumer> callbacks = PENDING.remove(artwork);
                if (callbacks != null) for (IntConsumer consumer : callbacks) consumer.accept(color);
            });
        });
    }
}
