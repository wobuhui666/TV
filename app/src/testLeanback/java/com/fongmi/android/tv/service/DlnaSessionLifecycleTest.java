package com.fongmi.android.tv.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DlnaSessionLifecycleTest {

    @Test
    public void shouldStopImmediatelyWithoutACast() {
        DlnaSessionLifecycle lifecycle = new DlnaSessionLifecycle();

        lifecycle.start();

        assertTrue(lifecycle.requestStop());
        assertFalse(lifecycle.isCasting());
    }

    @Test
    public void shouldKeepDeferredStopWhenReplacingTheMedia() {
        DlnaSessionLifecycle lifecycle = new DlnaSessionLifecycle();
        lifecycle.start();
        int first = lifecycle.beginCast();
        assertFalse(lifecycle.requestStop());

        int second = lifecycle.beginCast();

        assertFalse(lifecycle.endCast(first));
        assertTrue(lifecycle.isCurrentCast(second));
        assertTrue(lifecycle.endCast(second));
        assertFalse(lifecycle.isCasting());
    }

    @Test
    public void shouldCancelDeferredStopWhenTheHomeStartsAgain() {
        DlnaSessionLifecycle lifecycle = new DlnaSessionLifecycle();
        int token = lifecycle.beginCast();
        assertFalse(lifecycle.requestStop());

        lifecycle.start();

        assertFalse(lifecycle.endCast(token));
        assertFalse(lifecycle.isCasting());
    }

    @Test
    public void shouldNotReuseACompletedSessionToken() {
        DlnaSessionLifecycle lifecycle = new DlnaSessionLifecycle();
        int first = lifecycle.beginCast();
        lifecycle.endCast(first);

        int second = lifecycle.beginCast();

        assertNotEquals(first, second);
        assertFalse(lifecycle.endCast(first));
        assertTrue(lifecycle.isCurrentCast(second));
    }

    @Test
    public void shouldIgnoreMissingOrStaleTokens() {
        DlnaSessionLifecycle lifecycle = new DlnaSessionLifecycle();
        int first = lifecycle.beginCast();
        int second = lifecycle.beginCast();
        assertFalse(lifecycle.requestStop());

        assertFalse(lifecycle.endCast(0));
        assertFalse(lifecycle.endCast(first));
        assertEquals(second, lifecycle.getCastToken());
        assertTrue(lifecycle.endCast(second));
    }

    @Test
    public void shouldReleaseACastBeforeThePlayerConnects() {
        DlnaSessionLifecycle lifecycle = new DlnaSessionLifecycle();
        int token = lifecycle.beginCast();

        assertFalse(lifecycle.endCast(token));

        assertFalse(lifecycle.isCasting());
        assertTrue(lifecycle.requestStop());
    }

    @Test
    public void shouldRestoreThePreviousCastIfReplacementCannotLaunch() {
        DlnaSessionLifecycle lifecycle = new DlnaSessionLifecycle();
        int first = lifecycle.beginCast();
        assertFalse(lifecycle.requestStop());
        int failed = lifecycle.beginCast();

        lifecycle.restoreCast(failed, first);

        assertTrue(lifecycle.isCurrentCast(first));
        assertFalse(lifecycle.endCast(failed));
        assertTrue(lifecycle.endCast(first));
    }

    @Test
    public void shouldNotKeepCastingWhenTheFirstLaunchFails() {
        DlnaSessionLifecycle lifecycle = new DlnaSessionLifecycle();
        int failed = lifecycle.beginCast();

        lifecycle.restoreCast(failed, 0);

        assertFalse(lifecycle.isCasting());
        assertTrue(lifecycle.requestStop());
        assertNotEquals(failed, lifecycle.beginCast());
    }
}
