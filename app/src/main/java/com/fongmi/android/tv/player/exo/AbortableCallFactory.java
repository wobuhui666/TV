package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Request;

/** Owns and cancels every OkHttp call created for one pre-cache helper. */
final class AbortableCallFactory implements Call.Factory {

    private final Object lock = new Object();
    private final Set<Call> calls = new HashSet<>();
    private final Call.Factory upstream;
    private boolean aborted;

    AbortableCallFactory(Call.Factory upstream) {
        this.upstream = upstream;
    }

    @NonNull
    @Override
    public Call newCall(@NonNull Request request) {
        Call call;
        boolean cancel;
        synchronized (lock) {
            call = upstream.newCall(request);
            call.addEventListener(new EventListener() {
                @Override
                public void callEnd(@NonNull Call call) {
                    forget(call);
                }

                @Override
                public void callFailed(@NonNull Call call, @NonNull IOException ioe) {
                    forget(call);
                }
            });
            cancel = aborted;
            if (!cancel) calls.add(call);
        }
        // A canceled Call may still be enqueued later, but OkHttp will reject it without opening
        // a connection. This closes the abort/open race without blocking the playback thread.
        if (cancel) call.cancel();
        return call;
    }

    void abort() {
        List<Call> snapshot;
        synchronized (lock) {
            aborted = true;
            snapshot = new ArrayList<>(calls);
            calls.clear();
        }
        for (Call call : snapshot) call.cancel();
    }

    private void forget(Call call) {
        synchronized (lock) {
            calls.remove(call);
        }
    }

}
