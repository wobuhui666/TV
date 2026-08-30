package com.fongmi.android.tv.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaResolutionRequestGateTest {

    @Test
    public void shouldKeepOnlyLatestResolutionPending() {
        MediaResolutionRequestGate gate = new MediaResolutionRequestGate();
        NavigationRequestGate.Request first = gate.begin("media-1", "key-1");
        NavigationRequestGate.Request second = gate.begin("media-2", "key-2");

        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
        assertTrue(gate.isPending());
    }

    @Test
    public void shouldNotClearLatestResolutionWhenOldApplyArrives() {
        MediaResolutionRequestGate gate = new MediaResolutionRequestGate();
        NavigationRequestGate.Request first = gate.begin("media-1", "key-1");
        NavigationRequestGate.Request second = gate.begin("media-2", "key-2");

        assertFalse(gate.accept(first.generation(), "media-1", "key-1"));

        assertTrue(gate.isCurrent(second));
        assertTrue(gate.isPending());
    }

    @Test
    public void shouldKeepResolutionPendingUntilApplyOrInvalidation() {
        MediaResolutionRequestGate gate = new MediaResolutionRequestGate();
        NavigationRequestGate.Request request = gate.begin("media-1", "key-1");

        assertTrue(gate.isPending());
        assertTrue(gate.stage(request, "media-1", "key-1"));
        assertTrue(gate.accept(request.generation(), "media-1", "key-1"));
        assertFalse(gate.isPending());

        gate.begin("media-2", "key-2");
        gate.invalidate();
        assertFalse(gate.isPending());
    }

    @Test
    public void shouldCompleteOnlyWhenAppliedMediaMatchesLatestRequest() {
        MediaResolutionRequestGate gate = new MediaResolutionRequestGate();
        NavigationRequestGate.Request request = gate.begin("media-2", "key-2");

        assertTrue(gate.stage(request, "media-2", "key-2"));
        assertFalse(gate.accept(request.generation(), "media-1", "key-2"));
        assertTrue(gate.isPending());

        assertTrue(gate.accept(request.generation(), "media-2", "key-2"));
        assertFalse(gate.isPending());
    }

    @Test
    public void shouldRejectOldApplyWhenLatestRequestUsesSameMediaId() {
        MediaResolutionRequestGate gate = new MediaResolutionRequestGate();
        NavigationRequestGate.Request first = gate.begin("media-1", "key-1");
        NavigationRequestGate.Request second = gate.begin("media-1", "key-1");

        assertTrue(gate.stage(second, "media-1", "key-1"));
        assertFalse(gate.accept(first.generation(), "media-1", "key-1"));
        assertTrue(gate.isPending());
        assertTrue(gate.accept(second.generation(), "media-1", "key-1"));
    }

    @Test
    public void shouldNotCancelLatestRequestWhenOldRequestIsCanceled() {
        MediaResolutionRequestGate gate = new MediaResolutionRequestGate();
        NavigationRequestGate.Request first = gate.begin("media-1", "key-1");
        NavigationRequestGate.Request second = gate.begin("media-2", "key-2");

        gate.cancel(first);

        assertTrue(gate.isCurrent(second));
        assertTrue(gate.isPending());
    }

    @Test
    public void shouldAcceptResolvedMediaIdDifferentFromRequestedMediaId() {
        MediaResolutionRequestGate gate = new MediaResolutionRequestGate();
        NavigationRequestGate.Request request = gate.begin("VP:history", "playing-key");

        assertTrue(gate.stage(request, "VE:episode", "playing-key"));
        assertTrue(gate.accept(request.generation(), "VE:episode", "playing-key"));
        assertFalse(gate.isPending());
    }

    @Test
    public void shouldRejectStageAndApplyAfterPlaybackKeyChanges() {
        MediaResolutionRequestGate gate = new MediaResolutionRequestGate();
        NavigationRequestGate.Request request = gate.begin("VP:history", "old-key");

        assertFalse(gate.stage(request, "VE:episode", "new-key"));
        assertTrue(gate.isPending());

        assertTrue(gate.stage(request, "VE:episode", "old-key"));
        assertFalse(gate.accept(request.generation(), "VE:episode", "new-key"));
        assertTrue(gate.isPending());
    }

    @Test
    public void shouldCancelMatchingGenerationWithoutCancelingNewerRequest() {
        MediaResolutionRequestGate gate = new MediaResolutionRequestGate();
        NavigationRequestGate.Request old = gate.begin("old", "key");
        NavigationRequestGate.Request current = gate.begin("current", "key");

        assertFalse(gate.cancel(old.generation()));
        assertTrue(gate.isCurrent(current));
        assertTrue(gate.cancel(current.generation()));
        assertFalse(gate.isPending());
    }
}
