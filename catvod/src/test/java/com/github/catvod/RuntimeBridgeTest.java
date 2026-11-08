package com.github.catvod;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class RuntimeBridgeTest {

    @After
    public void clearHost() {
        Init.setToast(null);
        Init.setDialog(null);
    }

    @Test
    public void oldAndNewToastNamesShareTheCurrentHost() {
        List<String> oldHost = new ArrayList<>();
        List<String> newHost = new ArrayList<>();
        Init.setToast(oldHost::add);
        Runtime.toast("new source");
        Runtime.setToast(newHost::add);
        Init.toast("old source");
        Runtime.toast("new source again");
        assertEquals(List.of("new source"), oldHost);
        assertEquals(List.of("old source", "new source again"), newHost);
    }

    @Test
    public void oldAndNewDialogNamesShareRequestsAndCallbacks() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        Init.setDialog((options, callback) -> {
            received.set(options);
            return callback.apply("event");
        });
        assertEquals("update:event", Runtime.dialog("new options", event -> CompletableFuture.completedFuture("update:" + event)).get());
        assertEquals("new options", received.get());
        CompletableFuture<String> request = new CompletableFuture<>();
        Runtime.setDialog((options, callback) -> request);
        assertSame(request, Init.dialog("old options", null));
        request.cancel(true);
        assertTrue(request.isCancelled());
    }

    @Test
    public void missingDialogHostFailsThroughBothNames() {
        assertThrows(ExecutionException.class, () -> Runtime.dialog("{}", null).get());
        assertThrows(ExecutionException.class, () -> Init.dialog("{}", null).get());
    }
}
