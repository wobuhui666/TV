package com.fongmi.quickjs.method;

import com.github.catvod.utils.CallTimeout;
import com.github.catvod.Init;

import android.app.Activity;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Carries the calling request through native callbacks without changing source-wide networking. */
public final class Invocation implements AutoCloseable {

    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();
    private final CallTimeout deadline = CallTimeout.current();
    private final Supplier<Activity> activityOwner = Init.activityOwner();
    private final Set<CompletableFuture<?>> dialogs = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> actions = ConcurrentHashMap.newKeySet();
    private Runnable resumeDeadline;
    private int depth;
    private int activeActions;
    private boolean closed;
    private boolean usedDialog;

    public static Invocation current() {
        return CURRENT.get();
    }

    public static Runnable wrap(Invocation invocation, Runnable runnable) {
        return () -> {
            if (invocation == null) runnable.run();
            else invocation.run(runnable);
        };
    }

    public void run(Runnable runnable) {
        try {
            call(() -> { runnable.run(); return null; });
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public <T> T call(Callable<T> callable) throws Exception {
        Invocation previous = CURRENT.get();
        Runnable restoreDeadline = deadline == null ? () -> {} : deadline.enter();
        Runnable restoreActivity = Init.enterActivity(activityOwner);
        CURRENT.set(this);
        synchronized (this) {
            depth++;
            resume();
        }
        try {
            return callable.call();
        } finally {
            restoreActivity.run();
            restoreDeadline.run();
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            synchronized (this) {
                depth--;
                pause();
            }
        }
    }

    public synchronized void checkActive() {
        if (closed) throw new CancellationException("Source request ended");
    }

    public synchronized void await(CompletableFuture<?> future) {
        usedDialog = true;
        dialogs.add(future);
        future.whenComplete((value, error) -> {
            synchronized (this) {
                dialogs.remove(future);
                if (dialogs.isEmpty()) resume();
            }
        });
        if (closed) future.cancel(true);
        else pause();
    }

    public synchronized boolean hadDialog() { return usedDialog; }

    public void bind(CompletableFuture<?> result) {
        result.whenComplete((value, error) -> close());
    }

    public synchronized Runnable beginAction(CompletableFuture<?> action) {
        checkActive();
        activeActions++;
        actions.add(action);
        resume();
        AtomicBoolean once = new AtomicBoolean();
        return () -> {
            if (!once.compareAndSet(false, true)) return;
            synchronized (this) {
                actions.remove(action);
                activeActions--;
                pause();
            }
        };
    }

    private void pause() {
        if (!closed && deadline != null && resumeDeadline == null && depth == 0
                && activeActions == 0 && !dialogs.isEmpty()) resumeDeadline = deadline.suspend();
    }

    private void resume() {
        Runnable resume = resumeDeadline;
        resumeDeadline = null;
        if (resume != null) resume.run();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            resume();
        }
        for (CompletableFuture<?> dialog : dialogs) dialog.cancel(true);
        for (CompletableFuture<?> action : actions) action.cancel(true);
    }
}
