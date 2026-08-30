package com.fongmi.android.tv.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NavigationRequestGateTest {

    @Test
    public void shouldKeepOnlyLatestRequestCurrent() {
        NavigationRequestGate gate = new NavigationRequestGate();
        NavigationRequestGate.Request first = gate.begin("media-1", "key-1");
        NavigationRequestGate.Request second = gate.begin("media-1", "key-1");

        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }

    @Test
    public void shouldInvalidateRequestWhenPlaybackIsStopped() {
        NavigationRequestGate gate = new NavigationRequestGate();
        NavigationRequestGate.Request request = gate.begin("media-1", "key-1");

        gate.invalidate();

        assertFalse(gate.isCurrent(request));
    }

    @Test
    public void shouldRequireTheOriginalMediaIdentity() {
        NavigationRequestGate gate = new NavigationRequestGate();
        NavigationRequestGate.Request request = gate.begin("media-1", "key-1");

        assertTrue(gate.isCurrent(request, "media-1", "key-1"));
        assertFalse(gate.isCurrent(request, "media-2", "key-1"));
        assertFalse(gate.isCurrent(request, "media-1", "key-2"));
    }

    @Test
    public void shouldInvalidateIndependentGenerationSnapshots() {
        NavigationRequestGate gate = new NavigationRequestGate();
        long snapshot = gate.snapshot();

        assertTrue(gate.isCurrent(snapshot));
        gate.invalidate();
        assertFalse(gate.isCurrent(snapshot));
    }
}
