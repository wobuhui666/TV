package com.fongmi.android.tv.ai.skip;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AiSkipAudioProbeTest {

    @Test
    public void shouldSplitProbeAudioIntoThirtySecondChunks() {
        short[] pcm = new short[8_000 * 65];
        pcm[0] = 1;
        pcm[8_000 * 30] = 2;
        pcm[8_000 * 60] = 3;
        List<AiSkipAudioProbe.CapturedSample> output = new ArrayList<>();

        AiSkipAudioProbe.addChunks(output, "ending", 500_000, pcm, pcm.length);

        assertEquals(3, output.size());
        assertEquals(500_000, output.get(0).startMs());
        assertEquals(530_000, output.get(1).startMs());
        assertEquals(560_000, output.get(2).startMs());
        assertEquals(8_000 * 5, output.get(2).pcm().length);
        assertArrayEquals(new short[]{1, 2, 3}, new short[]{
                output.get(0).pcm()[0], output.get(1).pcm()[0], output.get(2).pcm()[0]});
    }

    @Test
    public void shouldDropTailShorterThanFiveSeconds() {
        short[] pcm = new short[8_000 * 34];
        List<AiSkipAudioProbe.CapturedSample> output = new ArrayList<>();

        AiSkipAudioProbe.addChunks(output, "opening", 0, pcm, pcm.length);

        assertEquals(1, output.size());
        assertEquals(8_000 * 30, output.get(0).pcm().length);
    }

    @Test
    public void shouldUseDisjointWindowsForShortMedia() {
        assertEquals(8_000 * 60, AiSkipAudioProbe.captureWindowSamples(120_000));
        assertEquals(8_000 * 90, AiSkipAudioProbe.captureWindowSamples(600_000));
        assertEquals(8_000 * 90, AiSkipAudioProbe.captureWindowSamples(0));
    }
}
