package com.fongmi.android.tv.ui.custom;

import org.junit.Test;

import static org.junit.Assert.*;

public class SeekKeySequenceTest {
    @Test public void shouldKeepRepeatsAndReleaseWithTheOriginalDeviceAndDownTime() {
        SeekKeySequence keys = new SeekKeySequence();
        assertTrue(keys.down(22, 7, 100, 0));
        assertFalse(keys.down(22, 8, 100, 1));
        assertFalse(keys.down(22, 7, 101, 1));
        assertFalse(keys.up(22, 8, 100));
        assertFalse(keys.up(22, 7, 101));
        assertTrue(keys.down(22, 7, 100, 10));
        assertTrue(keys.up(22, 7, 100));
        assertFalse(keys.up(22, 7, 100));
    }

    @Test public void shouldIgnoreHeldKeyEventsAfterMediaReplacement() {
        SeekKeySequence keys = new SeekKeySequence();
        assertTrue(keys.down(21, 3, 100, 0));
        keys.clear();
        assertFalse(keys.down(21, 3, 100, 5));
        assertFalse(keys.up(21, 3, 100));
        assertTrue(keys.down(21, 3, 200, 0));
        assertTrue(keys.up(21, 3, 200));
    }

    @Test public void shouldIgnoreTheOldDirectionWhenANewPressTakesOwnership() {
        SeekKeySequence keys = new SeekKeySequence();
        assertTrue(keys.down(22, 3, 100, 0));
        assertTrue(keys.down(21, 3, 200, 0));
        assertFalse(keys.down(22, 3, 100, 4));
        assertFalse(keys.up(22, 3, 100));
        assertTrue(keys.up(21, 3, 200));
    }
}
