package com.fongmi.quickjs.utils;

import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.*;

public class AsyncTest {

    @Test
    public void serializesOrdinaryDataObjectOnItsOwningThread() throws Exception {
        AtomicInteger releases = new AtomicInteger();
        JSObject value = object("{\"list\":[{\"vod_id\":\"1\"}]}", releases);
        assertEquals("{\"list\":[{\"vod_id\":\"1\"}]}", Async.runJson(spider(function(args -> value)), "home").get());
        assertEquals(1, releases.get());
    }

    @Test
    public void retainsLegacyStringsAndRawProxyObjects() throws Exception {
        assertEquals("legacy-json", Async.runJson(spider(function(args -> "legacy-json")), "home").get());
        AtomicInteger releases = new AtomicInteger();
        JSObject proxy = object("[200,\"text/plain\",\"body\"]", releases);
        assertSame(proxy, Async.run(spider(function(args -> proxy)), "proxy").get());
        assertEquals(0, releases.get());
        assertEquals(Boolean.TRUE, Async.run(spider(function(args -> true)), "sniffer").get());
    }

    @Test
    public void waitsForPromiseAndSerializesItsResolvedObject() throws Exception {
        AtomicReference<JSCallFunction> success = new AtomicReference<>();
        AtomicReference<JSCallFunction> failure = new AtomicReference<>();
        JSObject promise = promise(success, failure);
        CompletableFuture<Object> result = Async.runJson(spider(function(args -> promise)), "category");
        assertFalse(result.isDone());
        AtomicInteger releases = new AtomicInteger();
        success.get().call(object("{\"page\":2,\"list\":[]}", releases));
        assertEquals("{\"page\":2,\"list\":[]}", result.get());
        assertEquals(1, releases.get());
    }

    @Test
    public void exposesPromiseRejectionInsteadOfReturningEmptyData() {
        AtomicReference<JSCallFunction> success = new AtomicReference<>();
        AtomicReference<JSCallFunction> failure = new AtomicReference<>();
        CompletableFuture<Object> result = Async.runJson(spider(function(args -> promise(success, failure))), "search");
        failure.get().call("HTTP 403");
        ExecutionException error = assertThrows(ExecutionException.class, result::get);
        assertEquals("HTTP 403", error.getCause().getMessage());
    }

    @Test
    public void rejectionDoesNotLeaveAnUnhandledPromiseBranch() {
        List<JSCallFunction> rejections = new ArrayList<>();
        AtomicInteger unhandled = new AtomicInteger();
        JSObject promise = fake(JSObject.class, (name, args) -> {
            if (!name.equals("getJSFunction")) return null;
            if (args[0].equals("then")) return function(values -> {
                // A success-only then creates a child that also rejects, even if its parent has catch.
                rejections.add(values.length > 1 ? (JSCallFunction) values[1] : ignored -> {
                    unhandled.incrementAndGet();
                    return null;
                });
                return null;
            });
            if (args[0].equals("catch")) return function(values -> {
                rejections.add((JSCallFunction) values[0]);
                return null;
            });
            return null;
        });
        CompletableFuture<Object> result = Async.runJson(spider(function(args -> promise)), "action");
        for (JSCallFunction rejection : rejections) rejection.call("Dialog cancelled");
        assertEquals("Dialog cancelled", assertThrows(ExecutionException.class, result::get).getCause().getMessage());
        assertEquals("No rejected child may remain without an error handler", 0, unhandled.get());
    }

    @Test
    public void readsAndReleasesRejectedErrorWithoutCallingItsToString() {
        AtomicInteger releases = new AtomicInteger();
        JSObject reason = fake(JSObject.class, (name, args) -> {
            if (name.equals("getProperty")) { assertEquals("message", args[0]); return "Dialog cancelled"; }
            if (name.equals("release")) releases.incrementAndGet();
            if (name.equals("toString")) fail("Rejected JS values must not execute toString");
            return null;
        });
        assertEquals("Dialog cancelled", reject(reason).getMessage());
        assertEquals(1, releases.get());
    }

    @Test
    public void throwingRejectionGetterStillCompletesFutureAndReleasesValue() {
        AtomicInteger releases = new AtomicInteger();
        IllegalStateException getterError = new IllegalStateException("Error.message getter failed");
        JSObject reason = fake(JSObject.class, (name, args) -> {
            if (name.equals("getProperty") || name.equals("toString")) throw getterError;
            if (name.equals("release")) releases.incrementAndGet();
            return null;
        });
        assertSame(getterError, reject(reason));
        assertEquals(1, releases.get());
    }

    @Test
    public void throwingRejectionReleaseIsPreservedWithoutEscapingCallback() {
        IllegalStateException releaseError = new IllegalStateException("Error release failed");
        JSObject reason = fake(JSObject.class, (name, args) -> {
            if (name.equals("getProperty")) return "Dialog cancelled";
            if (name.equals("release")) throw releaseError;
            return null;
        });
        Throwable failure = reject(reason);
        assertEquals("Dialog cancelled", failure.getMessage());
        assertArrayEquals(new Throwable[]{releaseError}, failure.getSuppressed());
    }

    @Test
    public void releasesObjectValuedErrorMessageWithoutRunningItsConverter() {
        AtomicInteger releases = new AtomicInteger();
        JSObject message = fake(JSObject.class, (name, args) -> {
            if (name.equals("release")) releases.incrementAndGet();
            if (name.equals("toString")) fail("Object-valued messages must not execute toString");
            return null;
        });
        JSObject reason = fake(JSObject.class, (name, args) -> {
            if (name.equals("getProperty")) return message;
            if (name.equals("release")) releases.incrementAndGet();
            return null;
        });
        assertEquals("JavaScript promise rejected", reject(reason).getMessage());
        assertEquals(2, releases.get());
    }

    private Throwable reject(JSObject reason) {
        AtomicReference<JSCallFunction> success = new AtomicReference<>();
        AtomicReference<JSCallFunction> failure = new AtomicReference<>();
        CompletableFuture<Object> result = Async.runJson(spider(function(args -> promise(success, failure))), "action");
        failure.get().call(reason);
        return assertThrows(ExecutionException.class, result::get).getCause();
    }

    @Test
    public void throwingThenGetterReleasesThePromiseAndCompletesTheResult() {
        AtomicInteger releases = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("then getter failed");
        JSObject value = fake(JSObject.class, (name, args) -> {
            if (name.equals("getJSFunction")) throw failure;
            if (name.equals("release")) releases.incrementAndGet();
            return null;
        });
        CompletableFuture<Object> result = Async.runJson(spider(function(args -> value)), "run");
        assertSame(failure, assertThrows(ExecutionException.class, result::get).getCause());
        assertEquals(1, releases.get());
    }

    private JSObject spider(JSFunction function) {
        return fake(JSObject.class, (name, args) -> name.equals("getJSFunction") ? function : null);
    }

    private JSObject object(String json, AtomicInteger releases) {
        return fake(JSObject.class, (name, args) -> {
            if (name.equals("stringify")) return json;
            if (name.equals("release")) releases.incrementAndGet();
            return null;
        });
    }

    private JSObject promise(AtomicReference<JSCallFunction> success, AtomicReference<JSCallFunction> failure) {
        return fake(JSObject.class, (name, args) -> {
            if (!name.equals("getJSFunction")) return null;
            if (args[0].equals("then")) return function(values -> {
                success.set((JSCallFunction) values[0]);
                if (values.length > 1) failure.set((JSCallFunction) values[1]);
                return null;
            });
            if (args[0].equals("catch")) return function(values -> { failure.set((JSCallFunction) values[0]); return null; });
            return null;
        });
    }

    private JSFunction function(Function<Object[], Object> function) {
        return fake(JSFunction.class, (name, args) -> {
            if (name.equals("call") || name.equals("callVoid")) return function.apply((Object[]) args[0]);
            return null;
        });
    }

    private interface Invocation {
        Object call(String name, Object[] args);
    }

    private <T> T fake(Class<T> type, Invocation invocation) {
        Thread owner = Thread.currentThread();
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            assertSame("QuickJS value used on another thread", owner, Thread.currentThread());
            return invocation.call(method.getName(), args);
        }));
    }
}
