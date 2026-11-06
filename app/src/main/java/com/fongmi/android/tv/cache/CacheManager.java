package com.fongmi.android.tv.cache;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.exo.MediaSourceFactory;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Explicit manual cleanup. Unknown files, scripts, imports and user data are always retained. */
public final class CacheManager {

    public enum Category { IMAGES, PLAYBACK, UPDATES, OTHER }

    public static final class Entry {
        public final Category category;
        public final long bytes;

        Entry(Category category, long bytes) {
            this.category = category;
            this.bytes = bytes;
        }
    }

    private CacheManager() {}

    private static File directory(String name) {
        return new File(Path.cache(), name);
    }

    /** Hold from before player creation until all engines and preload workers have stopped. */
    public static CacheLease playbackLease() {
        return CacheLease.acquire(directory("exo"), directory("mpv"), directory("assrt-subtitles"));
    }

    /** Call on a worker thread; sizes count only regular files inside the app cache root. */
    public static List<Entry> snapshot() {
        List<Entry> entries = new ArrayList<>();
        try {
            CacheFiles files = new CacheFiles(Path.cache());
            long images = files.size(directory("image_manager_disk_cache"));
            long playback = files.size(directory("exo")) + files.size(directory("mpv")) + files.size(directory("assrt-subtitles"));
            long updates = 0;
            for (File file : updateFiles()) if (isUpdate(file)) updates += files.size(file);
            entries.add(new Entry(Category.IMAGES, images));
            entries.add(new Entry(Category.PLAYBACK, playback));
            entries.add(new Entry(Category.UPDATES, updates));
            entries.add(new Entry(Category.OTHER, Math.max(0, files.size(Path.cache()) - images - playback - updates)));
        } catch (IOException unavailable) {
            for (Category category : Category.values()) entries.add(new Entry(category, 0));
        }
        return entries;
    }

    public static long totalBytes() {
        long bytes = 0;
        for (Entry entry : snapshot()) bytes += entry.bytes;
        return bytes;
    }

    public static CacheResult clearAll() {
        CacheResult result = new CacheResult();
        for (Category category : Category.values()) {
            if (category == Category.OTHER) continue;
            result.add(clear(category));
            if (result.cancelled) break;
        }
        return result;
    }

    /** Synchronous worker-thread API; all entry points share leases and path checks. */
    public static CacheResult clear(Category category) {
        CacheResult result = new CacheResult();
        if (Thread.currentThread().isInterrupted()) {
            result.cancel();
            return result;
        }
        try {
            CacheFiles files = new CacheFiles(Path.cache());
            switch (category) {
                case IMAGES:
                    clearOwned(files, directory("image_manager_disk_cache"),
                            () -> Glide.get(App.get()).clearDiskCache(), result);
                    break;
                case PLAYBACK:
                    clearOwned(files, directory("exo"), MediaSourceFactory::clearCacheResources, result);
                    result.add(files.clear(directory("mpv")));
                    result.add(files.clear(directory("assrt-subtitles")));
                    break;
                case UPDATES:
                    clearUpdates(files, Long.MAX_VALUE, result);
                    break;
                default:
                    result.skip(CacheResult.Reason.UNMANAGED);
            }
        } catch (IOException | RuntimeException unavailable) {
            result.fail(CacheResult.Reason.OWNER_FAILED);
        }
        return result;
    }

    /** OTA retention uses exactly the same guard as the settings button. */
    public static CacheResult clearOldUpdates(long cutoffMillis) {
        CacheResult result = new CacheResult();
        try {
            clearUpdates(new CacheFiles(Path.cache()), cutoffMillis, result);
        } catch (IOException | RuntimeException unavailable) {
            result.fail(CacheResult.Reason.OWNER_FAILED);
        }
        return result;
    }

    private static void clearUpdates(CacheFiles files, long olderThan, CacheResult result) {
        for (File file : updateFiles()) {
            if (Thread.currentThread().isInterrupted()) { result.cancel(); break; }
            if (!files.isSafe(file)) result.skip(CacheResult.Reason.UNSAFE_PATH);
            else if (isUpdate(file)) {
                // Also survive a process restart while Android is waiting to open the APK.
                long cutoff = file.getName().endsWith(".apk")
                        ? Math.min(olderThan, System.currentTimeMillis() - java.util.concurrent.TimeUnit.MINUTES.toMillis(10))
                        : olderThan;
                result.add(files.clear(file, cutoff));
            }
            else if (file.exists()) result.skip(CacheResult.Reason.UNMANAGED);
        }
    }

    private static List<File> updateFiles() {
        List<File> files = new ArrayList<>();
        File[] downloads = directory("ota").listFiles();
        if (downloads != null) java.util.Collections.addAll(files, downloads);
        files.add(directory("update.apk"));
        files.add(directory("update.apk.part"));
        return files;
    }

    private static boolean isUpdate(File file) {
        return file.isFile() && file.getName().matches("update(?:-[0-9]+--?[0-9]+)?\\.apk(?:\\.part)?");
    }

    private static void clearOwned(CacheFiles files, File directory, Owner owner, CacheResult result) {
        synchronized (CacheLease.GATE) {
            if (Thread.currentThread().isInterrupted()) { result.cancel(); return; }
            if (!files.isTreeSafe(directory)) { result.skip(CacheResult.Reason.UNSAFE_PATH); return; }
            if (!directory.exists()) return;
            if (CacheLease.isInUse(directory)) { result.skip(CacheResult.Reason.IN_USE); return; }
            long before = files.size(directory);
            try {
                // The owner keeps its in-memory index and open cache object valid.
                owner.clear();
            } catch (Exception unavailable) {
                result.fail(CacheResult.Reason.OWNER_FAILED);
            } finally {
                result.releasedBytes += Math.max(0, before - files.size(directory));
                if (Thread.currentThread().isInterrupted()) result.cancel();
            }
        }
    }

    private interface Owner {
        void clear() throws Exception;
    }
}
