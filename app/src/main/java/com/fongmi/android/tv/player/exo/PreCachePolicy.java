package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.Player;

final class PreCachePolicy {

    private static final long MIN_STEP_MS = 5000;
    private static final long MAX_STEP_MS = 30000;
    private static final int STEP_DIVISOR = 4;

    static boolean canStart(int playbackState, boolean loading) {
        return playbackState == Player.STATE_READY && !loading;
    }

    static boolean supportsContentType(@C.ContentType int contentType) {
        return contentType == C.CONTENT_TYPE_OTHER;
    }

    static long getStart(long currentPositionMs, long seekPositionMs) {
        return Math.max(0, seekPositionMs == C.TIME_UNSET ? currentPositionMs : seekPositionMs);
    }

    static long getLength(long durationMs, long startMs, long preloadDurationMs) {
        if (durationMs <= 0 || startMs >= durationMs) return 0;
        return Math.min(preloadDurationMs, durationMs - startMs);
    }

    static boolean shouldPreCache(long startMs, long lastStartMs, boolean seeking, long preloadDurationMs) {
        if (seeking || lastStartMs == C.TIME_UNSET) return true;
        return Math.abs(startMs - lastStartMs) >= getStep(preloadDurationMs);
    }

    static long getStep(long preloadDurationMs) {
        return Math.max(MIN_STEP_MS, Math.min(MAX_STEP_MS, preloadDurationMs / STEP_DIVISOR));
    }

    private PreCachePolicy() {
    }
}
