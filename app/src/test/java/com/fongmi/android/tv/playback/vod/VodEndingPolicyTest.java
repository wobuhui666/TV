package com.fongmi.android.tv.playback.vod;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VodEndingPolicyTest {

    private static final long DURATION_MS = 30 * 60 * 1000L;
    private static final long ENDING_MS = 60 * 1000L;
    private static final long ENDING_START_MS = DURATION_MS - ENDING_MS;

    @Test
    public void shouldAdvanceOnceWhenNaturalPlaybackCrossesEnding() {
        VodEndingPolicy policy = new VodEndingPolicy();

        assertFalse(policy.update(ENDING_START_MS - 1_000, DURATION_MS, ENDING_MS));
        assertTrue(policy.update(ENDING_START_MS, DURATION_MS, ENDING_MS));
        assertFalse(policy.update(ENDING_START_MS + 1_000, DURATION_MS, ENDING_MS));
    }

    @Test
    public void shouldNotAdvanceWhenPlaybackStartsInsideEnding() {
        VodEndingPolicy policy = new VodEndingPolicy();

        assertFalse(policy.update(ENDING_START_MS + 1_000, DURATION_MS, ENDING_MS));
        assertFalse(policy.update(ENDING_START_MS + 2_000, DURATION_MS, ENDING_MS));
    }

    @Test
    public void shouldNotAdvanceAfterSeekAcrossEnding() {
        VodEndingPolicy policy = new VodEndingPolicy();

        assertFalse(policy.update(ENDING_START_MS - 5_000, DURATION_MS, ENDING_MS));
        assertFalse(policy.update(ENDING_START_MS + 5_000, DURATION_MS, ENDING_MS));
    }

    @Test
    public void shouldNotAdvanceWhenDurationChanges() {
        VodEndingPolicy policy = new VodEndingPolicy();

        assertFalse(policy.update(ENDING_START_MS - 1_000, DURATION_MS + 10_000, ENDING_MS));
        assertFalse(policy.update(ENDING_START_MS, DURATION_MS, ENDING_MS));
    }

    @Test
    public void shouldRejectImplausibleEnding() {
        VodEndingPolicy policy = new VodEndingPolicy();
        long invalidEndingMs = 11 * 60 * 1000L;

        assertFalse(policy.update(DURATION_MS - invalidEndingMs - 1_000, DURATION_MS, invalidEndingMs));
        assertFalse(policy.update(DURATION_MS - invalidEndingMs, DURATION_MS, invalidEndingMs));
    }

    @Test
    public void shouldResetForNextEpisode() {
        VodEndingPolicy policy = new VodEndingPolicy();
        policy.update(ENDING_START_MS - 1_000, DURATION_MS, ENDING_MS);
        assertTrue(policy.update(ENDING_START_MS, DURATION_MS, ENDING_MS));

        policy.reset();

        assertFalse(policy.update(ENDING_START_MS - 1_000, DURATION_MS, ENDING_MS));
        assertTrue(policy.update(ENDING_START_MS, DURATION_MS, ENDING_MS));
    }
}
