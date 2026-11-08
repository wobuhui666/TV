package com.fongmi.quickjs.utils;

import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;

import java.util.concurrent.CompletableFuture;

public class Async {

    private CompletableFuture<Object> future;
    private final boolean json;

    private final JSCallFunction success = args -> {
        complete(args != null && args.length > 0 ? args[0] : null);
        return null;
    };

    private final JSCallFunction error = args -> {
        reject(args != null && args.length > 0 ? args[0] : null);
        return null;
    };

    private Async(boolean json) {
        this.json = json;
        this.future = new CompletableFuture<>();
    }

    public static CompletableFuture<Object> run(JSObject object, String name, Object... args) {
        return new Async(false).call(object, name, args);
    }

    public static CompletableFuture<Object> runJson(JSObject object, String name, Object... args) {
        return new Async(true).call(object, name, args);
    }

    private void complete(Object value) {
        try {
            if (json && value instanceof JSObject object) {
                try {
                    future.complete(object.stringify());
                } finally {
                    object.release();
                }
            } else {
                future.complete(value);
            }
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
    }

    private void reject(Object value) {
        Throwable failure;
        try {
            String message = value instanceof JSObject object ? rejectionMessage(object)
                    : value == null ? "" : value.toString();
            failure = new Exception(message);
        } catch (Throwable error) {
            failure = error;
        }
        if (value instanceof JSObject object) {
            try {
                object.release();
            } catch (Throwable error) {
                if (error != failure) failure.addSuppressed(error);
            }
        }
        future.completeExceptionally(failure);
    }

    private String rejectionMessage(JSObject reason) {
        // Error.toString() re-enters JS; a throwing getter/converter must not escape the callback.
        Object message = reason.getProperty("message");
        if (message instanceof JSObject object) {
            object.release();
            return "JavaScript promise rejected";
        }
        return message == null ? "JavaScript promise rejected" : message.toString();
    }

    private CompletableFuture<Object> call(JSObject object, String name, Object... args) {
        JSFunction func = object.getJSFunction(name);
        if (func == null) return empty();
        call(func, args);
        return future;
    }

    private CompletableFuture<Object> empty() {
        future.complete(null);
        return future;
    }

    private void call(JSFunction func, Object... args) {
        try {
            Object result = func.call(args);
            if (result instanceof JSObject) then((JSObject) result);
            else complete(result);
        } catch (Throwable e) {
            future.completeExceptionally(e);
        } finally {
            func.release();
        }
    }

    private void then(JSObject promise) {
        JSFunction then = null;
        boolean completedObject = false;
        try {
            then = promise.getJSFunction("then");
            if (then == null) {
                completedObject = true;
                complete(promise);
            } else {
                // Separate then(success) and catch(error) leave the first child unhandled.
                // QuickJS callVoid releases the child Promise returned by then.
                then.callVoid(success, error);
            }
        } finally {
            try { if (then != null) then.release(); }
            finally { if (!completedObject) promise.release(); }
        }
    }

}
