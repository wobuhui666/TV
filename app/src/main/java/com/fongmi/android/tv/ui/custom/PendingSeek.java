package com.fongmi.android.tv.ui.custom;

final class PendingSeek implements Runnable {

    private final Callback callback;
    private final Scheduler scheduler;
    private final Runnable onCancel;
    private long deltaMs;
    private long generation;
    private Runnable posted;
    private boolean pending;

    PendingSeek(Callback callback, Scheduler scheduler) {
        this(callback, scheduler, () -> {});
    }

    PendingSeek(Callback callback, Scheduler scheduler, Runnable onCancel) {
        this.callback = callback;
        this.scheduler = scheduler;
        this.onCancel = onCancel;
    }

    long add(long valueMs) {
        pending = true;
        return deltaMs += valueMs;
    }

    void post(long delayMs) {
        cancel();
        long ticket = generation;
        posted = () -> {
            if (ticket == generation) run();
        };
        scheduler.post(posted, delayMs);
    }

    void cancel() {
        generation++;
        if (posted != null) scheduler.cancel(posted);
        posted = null;
    }

    @Override
    public void run() {
        posted = null;
        long valueMs = deltaMs;
        deltaMs = 0;
        boolean wasPending = pending;
        pending = false;
        if (valueMs != 0) callback.onSeek(valueMs);
        else if (wasPending) onCancel.run();
    }

    void clear() {
        cancel();
        deltaMs = 0;
        boolean wasPending = pending;
        pending = false;
        if (wasPending) onCancel.run();
    }

    interface Callback {

        void onSeek(long deltaMs);
    }

    interface Scheduler {

        void post(Runnable runnable, long delayMs);

        void cancel(Runnable runnable);
    }
}
