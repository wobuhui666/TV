package com.fongmi.quickjs.method;

/** Close every independently owned resource before reporting the first failure. */
final class BridgeCleanup {
    private Throwable failure;

    void run(Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
    }

    void finish() {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
