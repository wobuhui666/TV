package com.fongmi.android.tv.ui.custom;

final class PendingSeek implements Runnable {

    private final Callback callback;
    private final Scheduler scheduler;
    private long deltaMs;

    PendingSeek(Callback callback, Scheduler scheduler) {
        this.callback = callback;
        this.scheduler = scheduler;
    }

    long add(long valueMs) {
        return deltaMs += valueMs;
    }

    void post(long delayMs) {
        scheduler.post(this, delayMs);
    }

    void cancel() {
        scheduler.cancel(this);
    }

    @Override
    public void run() {
        long valueMs = deltaMs;
        deltaMs = 0;
        if (valueMs != 0) callback.onSeek(valueMs);
    }

    void clear() {
        cancel();
        deltaMs = 0;
    }

    interface Callback {

        void onSeek(long deltaMs);
    }

    interface Scheduler {

        void post(Runnable runnable, long delayMs);

        void cancel(Runnable runnable);
    }
}
