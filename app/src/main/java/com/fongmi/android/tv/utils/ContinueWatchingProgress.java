package com.fongmi.android.tv.utils;

public final class ContinueWatchingProgress {

    private ContinueWatchingProgress() {
    }

    public static float fraction(long position, long duration) {
        if (position <= 0 || duration <= 0) return 0;
        return (float) Math.min(1.0, (double) position / duration);
    }

    public static long remainingMinutes(long position, long duration) {
        if (duration <= 0) return -1;
        long remaining = duration - Math.min(duration, Math.max(0, position));
        return remaining / 60_000 + (remaining % 60_000 == 0 ? 0 : 1);
    }
}
