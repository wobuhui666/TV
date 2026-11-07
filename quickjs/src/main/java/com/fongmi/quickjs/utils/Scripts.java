package com.fongmi.quickjs.utils;

import androidx.annotation.Keep;

import com.fongmi.quickjs.method.Global;
import com.fongmi.quickjs.method.Invocation;
import com.github.catvod.utils.CallTimeout;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Short-lived isolated evaluations on the existing QuickJS wrapper and callback thread. */
@Keep
public final class Scripts {

    private Scripts() {
    }

    public static CompletableFuture<String> evaluate(String source, String options) {
        return new Evaluation(new ScriptOptions(source, options)).start();
    }

    private static final class Evaluation {

        private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "Spider script evaluation"));
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final AtomicReference<Throwable> stopped = new AtomicReference<>();
        private final ScriptOptions options;
        private final CallTimeout timeout;
        private final Invocation invocation;
        private volatile NativeBudget budget;
        private volatile CompletableFuture<Object> pending;
        private QuickJSContext context;
        private JSObject runner;
        private Global global;
        private boolean released;

        Evaluation(ScriptOptions options) {
            this.options = options;
            timeout = new CallTimeout(options.timeout, false);
            Runnable restore = timeout.enter();
            try { invocation = new Invocation(); }
            finally { restore.run(); }
            invocation.bind(result);
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) stop(new CancellationException("Script evaluation canceled"));
            });
        }

        CompletableFuture<String> start() {
            timeout.start(() -> stop(new TimeoutException("Script evaluation exceeded " + options.timeout + " ms")));
            try { executor.execute(Invocation.wrap(invocation, this::begin)); }
            catch (RejectedExecutionException error) { if (!result.isDone()) stop(error); }
            CallTimeout.trackCurrent(result);
            return result;
        }

        private void begin() {
            if (stopped.get() != null) { finish(null, stopped.get()); return; }
            try {
                QuickJSLoader.init();
                context = QuickJSContext.create();
                context.setMemoryLimit(64 * 1024 * 1024);
                budget = new NativeBudget();
                budget.attach(context, options.timeout);
                if (stopped.get() != null) budget.cancel();
                // The pinned wrapper uses this function while releasing its value records.
                context.evaluate("void Object.defineProperty(globalThis, 'format', {value: globalThis.format, writable: false, configurable: false});");
                global = Global.createStrict(context, executor);
                runner = (JSObject) context.evaluate(options.script, "spider-eval.js");
                pending = Async.runJson(runner, "run");
                pending.whenComplete((value, error) -> postFinish(value, error));
            } catch (Throwable error) {
                if (stopped.get() == null && timeout.isExpired()) {
                    TimeoutException expired = new TimeoutException("Script evaluation exceeded " + options.timeout + " ms");
                    expired.addSuppressed(error);
                    error = expired;
                }
                finish(null, error);
            }
        }

        private void stop(Throwable error) {
            if (!stopped.compareAndSet(null, error)) return;
            NativeBudget current = budget;
            if (current != null) current.cancel();
            timeout.close();
            invocation.close();
            CompletableFuture<Object> currentResult = pending;
            if (currentResult != null) currentResult.cancel(false);
            postFinish(null, error);
        }

        private void postFinish(Object value, Throwable error) {
            try { executor.execute(() -> finish(value, error)); }
            catch (RejectedExecutionException ignored) { /* An earlier completion already released this evaluation. */ }
        }

        private void finish(Object value, Throwable error) {
            if (released) return;
            released = true;
            if (stopped.get() != null) error = stopped.get();
            timeout.close();
            invocation.close();
            error = cleanup(error, () -> { if (budget != null) budget.close(); });
            error = cleanup(error, () -> { if (global != null) global.destroy(); });
            error = cleanup(error, () -> { if (runner != null) runner.release(); });
            error = cleanup(error, () -> { if (context != null) context.destroy(); });
            executor.shutdownNow();
            if (error != null) result.completeExceptionally(error);
            else if (value instanceof String json) result.complete(json);
            else result.completeExceptionally(new IllegalStateException("Script result must be JSON text"));
        }

        private Throwable cleanup(Throwable failure, Runnable action) {
            try { action.run(); }
            catch (Throwable error) {
                if (failure == null) return error;
                if (failure != error) failure.addSuppressed(error);
            }
            return failure;
        }
    }
}
