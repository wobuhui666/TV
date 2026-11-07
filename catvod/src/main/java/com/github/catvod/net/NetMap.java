package com.github.catvod.net;

import com.github.catvod.utils.CallTimeout;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Bounded workers preserve source call ownership and cancel together. */
final class NetMap<T, R> {

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "Spider map");
        thread.setDaemon(true);
        return thread;
    });

    private final List<T> items;
    private final List<R> values;
    private final Net.Mapper<T, R> mapper;
    private final CallTimeout scope;
    private final List<FutureTask<Void>> workers = new ArrayList<>();
    private final CompletableFuture<List<R>> result = new CompletableFuture<>();
    private final AtomicInteger remaining;
    private int index;
    private boolean stopped;

    NetMap(List<T> items, Net.Mapper<T, R> mapper, int limit, Consumer<NetMap<?, ?>> complete) {
        this.items = new ArrayList<>(items);
        this.values = new ArrayList<>(Collections.nCopies(this.items.size(), null));
        this.mapper = mapper;
        scope = CallTimeout.current();
        int count = Math.min(this.items.size(), limit);
        remaining = new AtomicInteger(count);
        for (int i = 0; i < count; i++) workers.add(createWorker());
        result.whenComplete((value, error) -> {
            if (error != null) stopWorkers();
            complete.accept(this);
        });
    }

    private FutureTask<Void> createWorker() {
        return new FutureTask<>(this::work, null) {
            @Override
            protected void done() {
                if (remaining.decrementAndGet() == 0) result.complete(values);
            }
        };
    }

    CompletableFuture<List<R>> start() {
        CallTimeout.trackCurrent(result);
        if (workers.isEmpty()) result.complete(values);
        try {
            for (FutureTask<Void> worker : workers) WORKERS.execute(worker);
        } catch (RuntimeException error) {
            fail(error);
        }
        return result;
    }

    private synchronized int next() {
        return stopped || index == items.size() ? -1 : index++;
    }

    private void work() {
        Runnable restore = scope == null ? () -> {} : scope.enter();
        try {
            for (int current; (current = next()) >= 0;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Map canceled");
                values.set(current, mapper.apply(items.get(current), current));
            }
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            fail(error);
        } finally {
            restore.run();
        }
    }

    private void fail(Throwable error) {
        synchronized (this) {
            stopped = true;
        }
        result.completeExceptionally(error);
    }

    private void stopWorkers() {
        synchronized (this) {
            stopped = true;
        }
        for (FutureTask<Void> worker : workers) worker.cancel(true);
    }

    void cancel() {
        fail(new InterruptedIOException("Map canceled"));
    }
}
