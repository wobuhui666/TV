package com.fongmi.android.tv.service;

import java.util.Objects;

/** Tracks the latest media-resolution request and whether it is still pending. */
public final class MediaResolutionRequestGate {

    public static final long NO_GENERATION = -1;

    private final NavigationRequestGate requests = new NavigationRequestGate();
    private NavigationRequestGate.Request current;
    private String resolvedMediaId;
    private boolean pending;

    public synchronized NavigationRequestGate.Request begin(String mediaId, String playbackKey) {
        current = requests.begin(mediaId, playbackKey);
        resolvedMediaId = null;
        pending = true;
        return current;
    }

    public synchronized boolean isCurrent(NavigationRequestGate.Request request) {
        return Objects.equals(current, request) && requests.isCurrent(request);
    }

    public synchronized boolean isPending() {
        return pending && current != null && requests.isCurrent(current);
    }

    /** Records the identity produced by resolving the latest requested media item. */
    public synchronized boolean stage(NavigationRequestGate.Request request, String mediaId, String playbackKey) {
        if (!isCurrent(request) || !Objects.equals(request.playbackKey(), playbackKey)) return false;
        resolvedMediaId = mediaId;
        return true;
    }

    /** Accepts exactly one player apply for the latest staged request generation. */
    public synchronized boolean accept(long generation, String mediaId, String playbackKey) {
        if (current == null || current.generation() != generation || !Objects.equals(resolvedMediaId, mediaId)
                || !Objects.equals(current.playbackKey(), playbackKey) || !requests.isCurrent(current)) return false;
        requests.invalidate();
        current = null;
        resolvedMediaId = null;
        pending = false;
        return true;
    }

    public synchronized boolean cancel(NavigationRequestGate.Request request) {
        if (!Objects.equals(current, request) || !requests.isCurrent(request)) return false;
        clear();
        return true;
    }

    public synchronized boolean cancel(long generation) {
        if (current == null || current.generation() != generation || !requests.isCurrent(current)) return false;
        clear();
        return true;
    }

    private void clear() {
        requests.invalidate();
        current = null;
        resolvedMediaId = null;
        pending = false;
    }

    public synchronized long invalidate() {
        long generation = current == null ? NO_GENERATION : current.generation();
        requests.invalidate();
        current = null;
        resolvedMediaId = null;
        pending = false;
        return generation;
    }
}
