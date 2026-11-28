package com.fongmi.android.tv.player.mpv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MpvBufferingTest {
    @Test
    public void reportsForwardCacheInsteadOfTheWholeMovie() {
        assertEquals(15_500, MpvBuffering.bufferedPositionMs(10_000, 3_600_000, 5.5));
    }

    @Test
    public void unknownOrInvalidCacheDoesNotInventBufferedData() {
        for (double missing : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -1, 0}) {
            assertEquals(10_000, MpvBuffering.bufferedPositionMs(10_000, 3_600_000, missing));
        }
    }

    @Test
    public void capsTheBufferAtTheKnownDuration() {
        assertEquals(12_000, MpvBuffering.bufferedPositionMs(10_000, 12_000, 5));
        assertEquals(12_000, MpvBuffering.bufferedPositionMs(13_000, 12_000, Double.NaN));
        assertEquals(0, MpvBuffering.bufferedPositionMs(0, 0, 5));
    }

    @Test
    public void supportsLiveStreamsWithoutAFileDuration() {
        assertEquals(25_250, MpvBuffering.bufferedPositionMs(20_000, Long.MIN_VALUE + 1, 5.25));
    }

    @Test
    public void seekWithUnknownCacheStartsAtTheNewPosition() {
        assertEquals(3_000, MpvBuffering.bufferedPositionMs(3_000, 60_000, Double.NaN));
        assertEquals(50_000, MpvBuffering.bufferedPositionMs(50_000, 60_000, Double.NaN));
    }

    @Test
    public void malformedLargeValuesCannotWrapTheTimeline() {
        assertEquals(Long.MAX_VALUE, MpvBuffering.bufferedPositionMs(1_000, -1, Double.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, MpvBuffering.bufferedPositionMs(Long.MAX_VALUE - 10, -1, 1));
        assertEquals(500, MpvBuffering.bufferedPositionMs(-1, -1, 0.5));
    }
}
