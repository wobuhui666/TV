package com.fongmi.android.tv.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaybackOrientationTest {

    @Test
    public void shouldUseTheDisplayAspectRatioForNonSquarePixels() {
        assertEquals(810, PlaybackOrientation.getVideoFrameHeight(720, 576, 16f / 15, 1080, 1920, 150));
        assertEquals(608, PlaybackOrientation.getVideoFrameHeight(720, 576, 64f / 45, 1080, 1920, 150));
    }

    @Test
    public void shouldKeepThePortraitFrameWhenScreenDimensionsSwap() {
        assertEquals(810, PlaybackOrientation.getVideoFrameHeight(720, 576, 16f / 15, 1920, 1080, 150));
    }

    @Test
    public void shouldBoundPortraitAndVeryWideVideoFrames() {
        assertEquals(960, PlaybackOrientation.getVideoFrameHeight(1080, 1920, 1, 1080, 1920, 150));
        assertEquals(150, PlaybackOrientation.getVideoFrameHeight(3840, 100, 1, 1080, 1920, 150));
    }

    @Test
    public void shouldWaitForKnownDimensionsAndTolerateInvalidPixelRatios() {
        assertEquals(0, PlaybackOrientation.getVideoFrameHeight(0, 576, 1, 1080, 1920, 150));
        assertEquals(864, PlaybackOrientation.getVideoFrameHeight(720, 576, Float.NaN, 1080, 1920, 150));
        assertEquals(864, PlaybackOrientation.getVideoFrameHeight(720, 576, 0, 1080, 1920, 150));
    }
}
