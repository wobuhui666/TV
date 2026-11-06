package com.fongmi.android.tv.bean;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CastRequestStateTest {
    @Test
    public void shouldIgnoreAnOlderDeviceResponseAfterSelectingAnotherDevice() {
        CastRequestState state = new CastRequestState();
        Object media = new Object();
        CastRequestState.Request old = state.begin(media);
        CastRequestState.Request latest = state.begin(media);
        assertFalse(state.isCurrent(old, media));
        assertTrue(state.isCurrent(latest, media));
    }

    @Test
    public void shouldIgnoreSuccessAfterTheMediaItemIsReplaced() {
        CastRequestState state = new CastRequestState();
        Object originalMedia = new String("same-video-url");
        Object replacementMedia = new String("same-video-url");
        CastRequestState.Request request = state.begin(originalMedia);
        assertTrue(state.isCurrent(request, originalMedia));
        assertFalse(state.isCurrent(request, replacementMedia));
    }

    @Test
    public void shouldIgnoreQueuedResponsesAndNewRequestsAfterClosingTheDialog() {
        CastRequestState state = new CastRequestState();
        Object media = new Object();
        CastRequestState.Request request = state.begin(media);
        state.close();
        assertFalse(state.isCurrent(request, media));
        assertFalse(state.isCurrent(state.begin(media), media));
    }
}
