package com.fongmi.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeArtworkPolicyTest {

    @Test
    public void shouldKeepEntirePortraitInTheRightSideOfHero() {
        HomeArtworkPolicy.Placement result = HomeArtworkPolicy.place(960, 320, 600, 900);
        assertTrue(result.x() > 960 * 0.55f);
        assertTrue(result.y() >= 0);
        assertTrue(result.x() + 600 * result.scale() <= 960);
        assertTrue(result.y() + 900 * result.scale() <= 320);
    }

    @Test
    public void shouldFillWideHeroWithLandscapeArtwork() {
        HomeArtworkPolicy.Placement result = HomeArtworkPolicy.place(960, 320, 1920, 1080);
        assertTrue(1920 * result.scale() >= 960);
        assertTrue(1080 * result.scale() >= 320);
        assertEquals(0, result.x(), 0.01f);
    }

    @Test
    public void shouldNotTreatSquareArtAsABackdrop() {
        assertFalse(HomeArtworkPolicy.isLandscape(800, 800));
        assertFalse(HomeArtworkPolicy.isLandscape(600, 900));
        assertTrue(HomeArtworkPolicy.isLandscape(1600, 900));
    }

    @Test
    public void shouldSafelyHandleUnmeasuredViewsAndMissingDimensions() {
        HomeArtworkPolicy.Placement result = HomeArtworkPolicy.place(0, 0, -1, -1);
        assertEquals(1, result.scale(), 0);
        assertEquals(0, result.x(), 0);
        assertEquals(0, result.y(), 0);
    }
}
