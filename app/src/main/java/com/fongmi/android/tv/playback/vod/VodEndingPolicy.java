package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.C;

import com.fongmi.android.tv.Constant;

final class VodEndingPolicy {

    private static final long MAX_DURATION_DRIFT_MS = 2_000;

    private long previousPositionMs;
    private long previousDurationMs;
    private boolean triggered;

    VodEndingPolicy() {
        reset();
    }

    public boolean update(long positionMs, long durationMs, long endingMs) {
        boolean shouldAdvance = !triggered && shouldAdvance(previousPositionMs, previousDurationMs, positionMs, durationMs, endingMs);
        previousPositionMs = positionMs;
        previousDurationMs = durationMs;
        triggered |= shouldAdvance;
        return shouldAdvance;
    }

    public void resetPosition() {
        previousPositionMs = C.TIME_UNSET;
        previousDurationMs = C.TIME_UNSET;
    }

    public void reset() {
        resetPosition();
        triggered = false;
    }

    static boolean shouldAdvance(long previousPositionMs, long previousDurationMs, long positionMs, long durationMs, long endingMs) {
        if (previousPositionMs < 0 || previousDurationMs <= 0 || positionMs < 0 || durationMs <= 0) return false;
        if (endingMs <= 0 || endingMs > Constant.getOpEdLimit(durationMs) || endingMs >= durationMs) return false;
        if (Math.abs(durationMs - previousDurationMs) > MAX_DURATION_DRIFT_MS) return false;
        long positionStepMs = positionMs - previousPositionMs;
        if (positionStepMs <= 0 || positionStepMs >= Constant.INTERVAL_SEEK) return false;
        long endingStartMs = durationMs - endingMs;
        return previousPositionMs < endingStartMs && positionMs >= endingStartMs;
    }
}
