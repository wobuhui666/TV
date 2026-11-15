package com.fongmi.android.tv.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.catvod.utils.CallTimeout;
import com.github.catvod.Init;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class Task {

    private static final ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(5));
    private static final ListeningExecutorService largeExecutor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(20));
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static ListeningExecutorService executor() {
        return executor;
    }

    public static ListeningExecutorService largeExecutor() {
        return largeExecutor;
    }

    public static ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public static Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    /** The deadline counts work, but is suspended while a source waits for dialog input. */
    public static <T> ListenableFuture<T> submit(Callable<T> callable, long timeoutMillis) {
        return submit(executor, callable, timeoutMillis, true);
    }

    public static <T> ListenableFuture<T> submit(Callable<T> callable, long timeoutMillis, boolean allowDialogs) {
        return submit(executor, callable, timeoutMillis, allowDialogs);
    }

    public static <T> ListenableFuture<T> submitLarge(Callable<T> callable, long timeoutMillis) {
        return submit(largeExecutor, callable, timeoutMillis, true);
    }

    public static <T> ListenableFuture<T> submitLarge(Callable<T> callable, long timeoutMillis, boolean allowDialogs) {
        return submit(largeExecutor, callable, timeoutMillis, allowDialogs);
    }

    private static <T> ListenableFuture<T> submit(ListeningExecutorService pool, Callable<T> callable,
                                                long timeoutMillis, boolean allowDialogs) {
        SettableFuture<T> result = SettableFuture.create();
        CallTimeout timeout = new CallTimeout(timeoutMillis, allowDialogs);
        var activityOwner = Init.activityOwner();
        ListenableFutureTask<T> task = ListenableFutureTask.create(() -> {
            Runnable restore = timeout.enter();
            Runnable restoreActivity = Init.enterActivity(activityOwner);
            try {
                return callable.call();
            } catch (Exception failure) {
                throw timeout.redactFailure(failure);
            } finally {
                restoreActivity.run();
                restore.run();
            }
        });
        result.addListener(() -> {
            timeout.close();
            if (!task.isDone()) task.cancel(true);
        }, MoreExecutors.directExecutor());
        Futures.addCallback(task, callback(result::set, result::setException), MoreExecutors.directExecutor());
        timeout.start(() -> result.setException(new TimeoutException("Source request timed out")));
        try {
            pool.execute(task);
        } catch (RejectedExecutionException error) {
            result.setException(error);
        }
        return result;
    }

    public static Future<?> submitLarge(Runnable task) {
        return largeExecutor.submit(task);
    }

    public static void execute(Runnable task) {
        executor.execute(task);
    }

    public static void schedule(Runnable task, long delay, TimeUnit unit) {
        scheduler.schedule(task, delay, unit);
    }

    public static <T> FutureCallback<T> callback(Consumer<T> onSuccess) {
        return callback(onSuccess, null);
    }

    public static <T> FutureCallback<T> callback(Consumer<T> onSuccess, @Nullable Consumer<Throwable> onFailure) {
        return new FutureCallback<>() {
            @Override
            public void onSuccess(T result) {
                onSuccess.accept(result);
            }

            @Override
            public void onFailure(@NonNull Throwable error) {
                if (onFailure != null) onFailure.accept(error);
            }
        };
    }
}
