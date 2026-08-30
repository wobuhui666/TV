package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

public class PreloadSetting {

    public static final int MIN_SIZE_MB = 128;
    public static final int MAX_SIZE_MB = 4096;
    public static final int STEP_SIZE_MB = 128;
    public static final int MIN_TIME_SECONDS = 20;
    public static final int MAX_TIME_SECONDS = 120;
    public static final int STEP_TIME_SECONDS = 10;

    public static boolean isPreload() {
        return Prefers.getBoolean("preload");
    }

    public static void putPreload(boolean preload) {
        Prefers.put("preload", preload);
    }

    public static int getPreloadSizeMb() {
        int size = Math.clamp(Prefers.getInt("preload_size", MIN_SIZE_MB), MIN_SIZE_MB, MAX_SIZE_MB);
        return Math.clamp(MIN_SIZE_MB + (long) Math.round((float) (size - MIN_SIZE_MB) / STEP_SIZE_MB) * STEP_SIZE_MB, MIN_SIZE_MB, MAX_SIZE_MB);
    }

    public static void putPreloadSizeMb(int size) {
        Prefers.put("preload_size", Math.clamp(size, MIN_SIZE_MB, MAX_SIZE_MB));
    }

    public static long getPreloadSizeBytes() {
        return getPreloadSizeMb() * 1024L * 1024L;
    }

    public static int getPreloadTimeSeconds() {
        int seconds = Math.clamp(Prefers.getInt("preload_time", MAX_TIME_SECONDS), MIN_TIME_SECONDS, MAX_TIME_SECONDS);
        return Math.clamp(MIN_TIME_SECONDS + (long) Math.round((float) (seconds - MIN_TIME_SECONDS) / STEP_TIME_SECONDS) * STEP_TIME_SECONDS, MIN_TIME_SECONDS, MAX_TIME_SECONDS);
    }

    public static void putPreloadTimeSeconds(int seconds) {
        Prefers.put("preload_time", Math.clamp(seconds, MIN_TIME_SECONDS, MAX_TIME_SECONDS));
    }

    public static long getPreloadDurationMs() {
        return getPreloadTimeSeconds() * 1000L;
    }
}
