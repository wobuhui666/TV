package com.fongmi.quickjs.method;

import com.github.catvod.utils.CallTimeout;
import com.github.catvod.Init;
import android.app.Activity;
import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.Assert.*;

public class InvocationTest {
    @Test public void deferredCallbacksKeepTheOriginalActivityOwner() throws Exception {
        Supplier<Activity> owner = () -> null;
        Runnable restore = Init.enterActivity(owner);
        Invocation invocation;
        try { invocation = new Invocation(); }
        finally { restore.run(); }
        try {
            CompletableFuture.runAsync(() -> invocation.run(() -> assertSame(owner, Init.currentActivityOwner()))).get();
            assertNull(Init.currentActivityOwner());
        } finally { invocation.close(); }
    }
    @Test public void preservesCallerAcrossDeferredNativeCallbackAndRestoresScope() throws Exception {
        Invocation first = new Invocation();
        Invocation second = new Invocation();
        Runnable[] deferred = new Runnable[1];
        first.call(() -> { deferred[0] = Invocation.wrap(Invocation.current(), () -> assertSame(first, Invocation.current())); return null; });
        second.run(() -> { deferred[0].run(); assertSame(second, Invocation.current()); });
        assertNull(Invocation.current());
        first.close(); second.close();
    }

    @Test public void returningOrCancellingSourceClosesItsDialogsOnly() {
        Invocation first = new Invocation();
        Invocation second = new Invocation();
        CompletableFuture<String> a = new CompletableFuture<>();
        CompletableFuture<String> b = new CompletableFuture<>();
        first.await(a); second.await(b);
        CompletableFuture<Object> result = new CompletableFuture<>();
        first.bind(result);
        result.complete("done");
        assertTrue(a.isCancelled());
        assertFalse(b.isDone());
        assertThrows(java.util.concurrent.CancellationException.class, first::checkActive);
        second.close();
        assertTrue(b.isCancelled());
    }

    @Test public void waitingForInputPausesDeadlineButCallbackWorkCounts() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        try (CallTimeout timeout = new CallTimeout(120)) {
            Runnable restore = timeout.enter();
            Invocation invocation;
            try { invocation = new Invocation(); } finally { restore.run(); }
            try {
                invocation.run(() -> invocation.await(new CompletableFuture<>()));
                timeout.start(expired::countDown);
                assertFalse(expired.await(220, TimeUnit.MILLISECONDS));
                CompletableFuture<String> callback = new CompletableFuture<>();
                Runnable finish = invocation.beginAction(callback);
                assertTrue(expired.await(2, TimeUnit.SECONDS));
                finish.run();
            } finally { invocation.close(); }
        }
    }

    @Test public void closedInvocationCancelsLateDialogAndActiveCallback() {
        Invocation invocation = new Invocation();
        CompletableFuture<String> callback = new CompletableFuture<>();
        Runnable finish = invocation.beginAction(callback);
        invocation.close();
        assertTrue(callback.isCancelled());
        CompletableFuture<String> late = new CompletableFuture<>();
        invocation.await(late);
        assertTrue(late.isCancelled());
        finish.run(); finish.run();
    }
}
