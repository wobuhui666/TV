package com.fongmi.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ContinueWatchingProgressTest {

    @Test
    public void shouldCalculateProgressAndRoundRemainingTimeUp() {
        assertEquals(0.25f, ContinueWatchingProgress.fraction(30_000, 120_000), 0);
        assertEquals(2, ContinueWatchingProgress.remainingMinutes(30_000, 120_000));
    }

    @Test
    public void shouldNotShowBogusProgressForUnknownDuration() {
        assertEquals(0, ContinueWatchingProgress.fraction(30_000, -9223372036854775807L), 0);
        assertEquals(-1, ContinueWatchingProgress.remainingMinutes(30_000, -9223372036854775807L));
    }

    @Test
    public void shouldClampRestoredPositionsOutsideTheEpisode() {
        assertEquals(0, ContinueWatchingProgress.fraction(-1, 60_000), 0);
        assertEquals(1, ContinueWatchingProgress.fraction(90_000, 60_000), 0);
        assertEquals(0, ContinueWatchingProgress.remainingMinutes(90_000, 60_000));
        assertEquals(1, ContinueWatchingProgress.remainingMinutes(-1, 60_000));
    }
}
