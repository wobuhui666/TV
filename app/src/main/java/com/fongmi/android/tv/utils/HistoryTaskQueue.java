package com.fongmi.android.tv.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Main-thread owned queue: reads may be replaced, but accepted writes always finish in order. */
public final class HistoryTaskQueue implements AutoCloseable {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> query;

    public void replaceQuery(Runnable read) {
        cancelQuery();
        query = worker.submit(read);
    }

    public void write(Runnable mutation) {
        worker.execute(mutation);
    }

    public void cancelQuery() {
        if (query != null) query.cancel(true);
        query = null;
    }

    @Override
    public void close() {
        cancelQuery();
        // shutdownNow would discard deletions accepted just before the screen closed.
        worker.shutdown();
    }
}
