package com.fongmi.android.tv.ai.skip;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AiSkipRuntimeTest {

    @Test
    public void shouldPreserveExplicitManualBoundaries() {
        assertTrue(AiSkipRuntime.isManual(0, "manual"));
        assertTrue(AiSkipRuntime.isManual(30_000, "manual"));
    }

    @Test
    public void shouldTreatLegacyBoundariesAsManual() {
        assertTrue(AiSkipRuntime.isManual(30_000, "unknown"));
        assertFalse(AiSkipRuntime.isManual(0, "unknown"));
        assertFalse(AiSkipRuntime.isManual(30_000, "ai"));
    }
}
