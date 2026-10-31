package com.fongmi.android.tv.api.rex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import okhttp3.Call;

/** Keeps requests cancellable through body consumption and queued UI delivery. */
final class TaggedCalls {

    private final Map<Object, Set<Call>> pending = new HashMap<>();

    synchronized void track(Object tag, Call call) {
        if (tag != null) pending.computeIfAbsent(tag, key -> new HashSet<>()).add(call);
    }

    void cancel(Object tag) {
        if (tag == null) return;
        List<Call> calls;
        synchronized (this) {
            Set<Call> removed = pending.remove(tag);
            if (removed == null) return;
            calls = new ArrayList<>(removed);
        }
        for (Call call : calls) call.cancel();
    }

    void deliver(Object tag, Call call, Runnable callback, Consumer<Runnable> dispatcher) {
        dispatcher.accept(() -> {
            boolean current = true;
            synchronized (this) {
                if (tag != null) {
                    Set<Call> calls = pending.get(tag);
                    current = calls != null && calls.remove(call);
                    if (calls != null && calls.isEmpty()) pending.remove(tag);
                }
            }
            if (current && !call.isCanceled()) callback.run();
        });
    }
}
