package com.fongmi.android.tv.ui.custom;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PendingSeekTest {

    @Test
    public void shouldCancelPreviousCommitBeforeCombiningNextPress() {
        long[] received = new long[2];
        TestScheduler scheduler = new TestScheduler();
        PendingSeek pendingSeek = new PendingSeek(deltaMs -> {
            received[0]++;
            received[1] = deltaMs;
        }, scheduler);

        assertEquals(10_000, pendingSeek.add(10_000));
        pendingSeek.post(250);
        pendingSeek.cancel();
        scheduler.runPending();
        assertEquals(0, received[0]);

        assertEquals(30_000, pendingSeek.add(20_000));
        pendingSeek.post(250);
        scheduler.runPending();

        assertEquals(1, received[0]);
        assertEquals(30_000, received[1]);
    }

    @Test
    public void shouldNotDispatchWhenOpposingSeeksCancelOut() {
        long[] calls = new long[1];
        TestScheduler scheduler = new TestScheduler();
        PendingSeek pendingSeek = new PendingSeek(deltaMs -> calls[0]++, scheduler);

        pendingSeek.add(10_000);
        pendingSeek.add(-10_000);
        pendingSeek.post(250);
        scheduler.runPending();

        assertEquals(0, calls[0]);
    }

    @Test
    public void shouldNotDispatchAfterClear() {
        long[] calls = new long[1];
        TestScheduler scheduler = new TestScheduler();
        PendingSeek pendingSeek = new PendingSeek(deltaMs -> calls[0]++, scheduler);

        pendingSeek.add(10_000);
        pendingSeek.post(250);
        pendingSeek.clear();
        scheduler.runPending();

        assertEquals(0, calls[0]);
    }

    private static final class TestScheduler implements PendingSeek.Scheduler {

        private Runnable pending;

        @Override
        public void post(Runnable runnable, long delayMs) {
            pending = runnable;
        }

        @Override
        public void cancel(Runnable runnable) {
            if (pending == runnable) pending = null;
        }

        void runPending() {
            Runnable runnable = pending;
            pending = null;
            if (runnable != null) runnable.run();
        }
    }
}
