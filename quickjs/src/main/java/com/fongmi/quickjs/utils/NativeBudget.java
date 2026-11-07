package com.fongmi.quickjs.utils;

import androidx.annotation.Keep;

import com.whl.quickjs.wrapper.QuickJSContext;

/** Installs a cancellable native interrupt on a context owned by one script evaluation. */
@Keep
final class NativeBudget implements AutoCloseable {

    static { System.loadLibrary("tv-quickjs-budget"); }

    private long handle;
    private boolean canceled;

    synchronized void attach(QuickJSContext context, long timeoutMillis) {
        if (handle != 0) throw new IllegalStateException("Script budget already attached");
        handle = attachNative(context, timeoutMillis);
        if (canceled) cancelNative(handle);
    }

    synchronized void cancel() {
        canceled = true;
        if (handle != 0) cancelNative(handle);
    }

    @Override
    public synchronized void close() {
        if (handle == 0) return;
        detachNative(handle);
        handle = 0;
    }

    private static native long attachNative(QuickJSContext context, long timeoutMillis);
    private static native void cancelNative(long handle);
    private static native void detachNative(long handle);

    static native int activeCount();
}
