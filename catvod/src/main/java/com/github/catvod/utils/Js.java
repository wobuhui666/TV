package com.github.catvod.utils;

import androidx.annotation.Keep;

import com.github.catvod.runtime.JsEvaluator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Owns evaluations independently of whether a JavaScript source was loaded first. */
@Keep
public final class Js implements AutoCloseable {

    private final Set<CompletableFuture<String>> pending = new HashSet<>();
    private final JsEvaluator evaluator;
    private boolean closed;

    public Js() {
        this(Js::evaluateWithModule);
    }

    public Js(JsEvaluator evaluator) {
        if (evaluator == null) throw new NullPointerException("Script evaluator is required");
        this.evaluator = evaluator;
    }

    public String eval(String source) throws Exception {
        return eval(source, "{}");
    }

    public String eval(String source, String options) throws Exception {
        CompletableFuture<String> result = evalAsync(source, options);
        try {
            return result.get();
        } catch (InterruptedException error) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException(error.getCause());
        }
    }

    public CompletableFuture<String> evalAsync(String source) {
        return evalAsync(source, "{}");
    }

    public synchronized CompletableFuture<String> evalAsync(String source, String options) {
        if (closed) throw new IllegalStateException("Script owner closed");
        CompletableFuture<String> result = evaluator.evaluate(source, options);
        if (result == null) throw new IllegalStateException("Script evaluator returned no result");
        pending.add(result);
        result.whenComplete((value, error) -> release(result));
        CallTimeout.trackCurrent(result);
        return result;
    }

    private synchronized void release(CompletableFuture<String> result) {
        pending.remove(result);
    }

    @Override
    public void close() {
        ArrayList<CompletableFuture<String>> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = new ArrayList<>(pending);
        }
        for (CompletableFuture<String> result : active) result.cancel(true);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<String> evaluateWithModule(String source, String options) {
        try {
            Method evaluate = Class.forName("com.fongmi.quickjs.utils.Scripts")
                    .getMethod("evaluate", String.class, String.class);
            return (CompletableFuture<String>) evaluate.invoke(null, source, options);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Script evaluation failed", error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("QuickJS script module is unavailable", error);
        }
    }
}
