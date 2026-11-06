package com.fongmi.android.tv.bean;

/** Keeps asynchronous device responses within the media item that was dispatched. */
public final class CastRequestState {
    private long generation;
    private boolean closed;

    public record Request(long generation, Object mediaIdentity) {}

    public Request begin(Object mediaIdentity) {
        return new Request(++generation, mediaIdentity);
    }

    public boolean isCurrent(Request request, Object mediaIdentity) {
        return !closed && request != null && request.generation() == generation
                && request.mediaIdentity() == mediaIdentity;
    }

    public void close() {
        closed = true;
        generation++;
    }
}
