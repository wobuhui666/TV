package com.fongmi.android.tv.ai.skip;

import org.junit.Test;

import java.util.List;

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

    @Test
    public void shouldSubmitFinalizedCaptureWithoutActivePlaybackSession() {
        List<AiSkipApi.Sample> samples = List.of(
                new AiSkipApi.Sample("opening", 0, 30_000, "samples/opening.wav"),
                new AiSkipApi.Sample("ending", 570_000, 30_000, "samples/ending.wav"));

        assertTrue(AiSkipRuntime.isReadyToSubmit(true, false, 0, 600_000, samples));
        assertFalse(AiSkipRuntime.isReadyToSubmit(true, false, 1, 600_000, samples));
    }
}
