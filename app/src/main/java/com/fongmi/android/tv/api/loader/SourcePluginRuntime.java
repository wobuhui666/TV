package com.fongmi.android.tv.api.loader;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keeps threads created during source initialization separate from host threads. */
final class SourcePluginRuntime extends ThreadGroup {

    private final Consumer<Throwable> report;
    private final InheritableThreadLocal<AtomicBoolean> cancellation = new InheritableThreadLocal<>();

    SourcePluginRuntime(Consumer<Throwable> report) {
        this(Thread.currentThread().getThreadGroup(), report);
    }

    SourcePluginRuntime(ThreadGroup parent, Consumer<Throwable> report) {
        super(parent, "source-plugin");
        this.report = report;
    }

    <T> T call(Supplier<T> action) {
        // Reentrant source loading must not wait on another thread while holding jar locks.
        if (parentOf(Thread.currentThread().getThreadGroup())) return action.get();
        AtomicBoolean canceled = new AtomicBoolean();
        FutureTask<T> task = new FutureTask<>(() -> {
            cancellation.set(canceled);
            try {
                return action.get();
            } finally {
                cancellation.remove();
            }
        });
        Thread worker = new Thread(this, task, "source-init");
        worker.start();
        try {
            return task.get();
        } catch (InterruptedException e) {
            canceled.set(true);
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Source initialization interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        }
    }

    boolean isCanceled() {
        AtomicBoolean canceled = cancellation.get();
        return Thread.currentThread().isInterrupted() || canceled != null && canceled.get();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        if (isPluginLinkageError(error)) {
            try {
                report.accept(error);
                return; // Only the failed plugin worker ends; the host stays usable.
            } catch (Throwable reportingError) {
                error.addSuppressed(reportingError);
            }
        }
        super.uncaughtException(thread, error);
    }

    static boolean isPluginLinkageError(Throwable error) {
        if (!(error instanceof LinkageError)) return false;
        for (StackTraceElement frame : error.getStackTrace()) {
            String name = frame.getClassName();
            // System.load / ART may lead the trace. The first application frame must
            // be the external spider itself, not host code called by a spider.
            if (name.startsWith("java.") || name.startsWith("dalvik.system.")) continue;
            return name.startsWith("com.github.catvod.spider.");
        }
        return false;
    }
}
