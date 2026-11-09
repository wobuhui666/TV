package com.github.catvod.utils;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

public final class CallTimeout implements AutoCloseable {

    private static final ScheduledThreadPoolExecutor SCHEDULER = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "Spider call deadlines");
        thread.setDaemon(true);
        return thread;
    });
    static { SCHEDULER.setRemoveOnCancelPolicy(true); }
    private static final ThreadLocal<CallTimeout> CURRENT = new ThreadLocal<>();

    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    private final CallTimeout parent;
    private final boolean allowDialogs;
    private final boolean enabled;
    private final boolean resourceScope;
    private final long budget;
    private long remaining;
    private long resumed = System.nanoTime();
    private long generation;
    private int waits;
    private boolean closed;
    private volatile boolean usedDialog;
    private volatile boolean deniedDialog;
    private Runnable expired;
    private ScheduledFuture<?> scheduled;

    public CallTimeout(long timeoutMillis) {
        this(timeoutMillis, CURRENT.get(), true);
    }

    public CallTimeout(long timeoutMillis, boolean allowDialogs) {
        this(timeoutMillis, CURRENT.get(), allowDialogs);
    }

    /** Explicit parent is used when queued work starts on an unrelated worker thread. */
    public CallTimeout(long timeoutMillis, CallTimeout parent, boolean allowDialogs) {
        this(timeoutMillis, parent, allowDialogs, false);
    }

    private CallTimeout(long timeoutMillis, CallTimeout parent, boolean allowDialogs, boolean resourceScope) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Timeout must be nonnegative");
        this.parent = parent;
        this.allowDialogs = allowDialogs && (parent == null || parent.allowDialogs);
        enabled = timeoutMillis > 0;
        this.resourceScope = resourceScope;
        budget = remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    /** Long-lived source resources outlive the request which created them. */
    public static CallTimeout resourceScope() {
        return new CallTimeout(0, null, false, true);
    }

    public boolean isResourceScope() {
        return resourceScope;
    }

    public static void markDialogUse() {
        for (CallTimeout current = CURRENT.get(); current != null; current = current.parent) current.usedDialog = true;
    }

    public boolean hasDialog() {
        return usedDialog;
    }

    public static boolean currentDeniedDialog() {
        return CURRENT.get() != null && CURRENT.get().deniedDialog;
    }

    public static boolean currentHasDialog() {
        return CURRENT.get() != null && CURRENT.get().hasDialog();
    }

    /** Form values can appear in provider exceptions; keep their content out of host logs. */
    public Exception redactFailure(Exception failure) {
        if (!usedDialog) return failure;
        if (failure instanceof CancellationException) return new CancellationException("Source dialog request cancelled");
        if (failure instanceof InterruptedException) return new InterruptedException("Source dialog request interrupted");
        return new Exception("Source request failed after dialog (" + failure.getClass().getSimpleName() + ")");
    }

    public static void requireDialogsAllowed() {
        for (CallTimeout current = CURRENT.get(); current != null; current = current.parent) {
            synchronized (current) {
                if (current.closed || current.isExpired()) throw new CancellationException("Source request ended");
                if (!current.allowDialogs) {
                    for (CallTimeout owner = CURRENT.get(); owner != null; owner = owner.parent) owner.deniedDialog = true;
                    throw new IllegalStateException("Interactive dialogs are unavailable during background requests");
                }
            }
        }
    }

    /** Active work only, excluding all suspended input waits. */
    public synchronized long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, budget - remainingNow()));
    }

    public synchronized boolean isExpired() {
        return enabled && remainingNow() <= 0;
    }

    private long remainingNow() {
        return remaining - (!closed && waits == 0 ? System.nanoTime() - resumed : 0);
    }

    public static <T> T withoutDialogs(Callable<T> callable) throws Exception {
        try (CallTimeout scope = new CallTimeout(0, false)) {
            Runnable restore = scope.enter();
            try { return callable.call(); }
            finally { restore.run(); }
        }
    }

    public static CallTimeout current() {
        return CURRENT.get();
    }

    public static Runnable suspendCurrent() {
        CallTimeout current = CURRENT.get();
        return current == null ? () -> {} : current.suspend();
    }

    public static void trackCurrent(CompletableFuture<?> future) {
        for (CallTimeout current = CURRENT.get(); current != null; current = current.parent) current.track(future);
    }

    private void track(CompletableFuture<?> future) {
        pending.add(future);
        future.whenComplete((value, error) -> pending.remove(future));
        boolean ended;
        synchronized (this) {
            ended = closed;
        }
        if (ended) future.cancel(true);
    }

    public Runnable enter() {
        CallTimeout previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public synchronized void start(Runnable expired) {
        this.expired = expired;
        schedule();
    }

    public Runnable suspend() {
        Runnable resumeParent = parent == null ? () -> {} : parent.suspend();
        synchronized (this) {
            if (!closed && waits++ == 0) {
                remaining -= System.nanoTime() - resumed;
                cancel();
            }
        }
        AtomicBoolean once = new AtomicBoolean();
        return () -> {
            if (!once.compareAndSet(false, true)) return;
            synchronized (this) {
                if (!closed && --waits == 0) {
                    resumed = System.nanoTime();
                    schedule();
                }
            }
            resumeParent.run();
        };
    }

    private void schedule() {
        if (closed || !enabled || expired == null || waits > 0) return;
        long current = ++generation;
        long delay = Math.max(0, remaining - (System.nanoTime() - resumed));
        scheduled = SCHEDULER.schedule(() -> expire(current), delay, TimeUnit.NANOSECONDS);
    }

    private void expire(long current) {
        synchronized (this) {
            if (closed || waits > 0 || current != generation) return;
            remaining = remainingNow();
            closed = true;
        }
        try {
            expired.run();
        } finally {
            cancelPending();
        }
    }

    private void cancel() {
        generation++;
        if (scheduled != null) scheduled.cancel(false);
    }

    @Override
    public void close() {
        synchronized (this) {
            remaining = remainingNow();
            closed = true;
            cancel();
        }
        cancelPending();
    }

    private void cancelPending() {
        for (CompletableFuture<?> future : pending) future.cancel(true);
    }
}
