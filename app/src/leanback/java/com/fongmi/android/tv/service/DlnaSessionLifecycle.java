package com.fongmi.android.tv.service;

// Guard this state and the corresponding Android service calls with the same lock.
final class DlnaSessionLifecycle {

    private int sequence;
    private int castToken;
    private boolean stopRequested;

    void start() {
        stopRequested = false;
    }

    boolean requestStop() {
        stopRequested = isCasting();
        return !stopRequested;
    }

    int getCastToken() {
        return castToken;
    }

    boolean isCasting() {
        return castToken != 0;
    }

    boolean isCurrentCast(int token) {
        return token != 0 && token == castToken;
    }

    int beginCast() {
        if (++sequence == 0) ++sequence;
        castToken = sequence;
        return castToken;
    }

    void restoreCast(int token, int previousToken) {
        if (isCurrentCast(token)) castToken = previousToken;
    }

    boolean endCast(int token) {
        if (!isCurrentCast(token)) return false;
        castToken = 0;
        boolean shouldStop = stopRequested;
        stopRequested = false;
        return shouldStop;
    }
}
