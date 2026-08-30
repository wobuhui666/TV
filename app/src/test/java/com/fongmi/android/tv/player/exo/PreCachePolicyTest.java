package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.Player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreCachePolicyTest {

    @Test
    public void shouldOnlyStartAfterPlaybackIsReadyAndNotLoading() {
        assertFalse(PreCachePolicy.canStart(Player.STATE_IDLE, false));
        assertFalse(PreCachePolicy.canStart(Player.STATE_BUFFERING, false));
        assertFalse(PreCachePolicy.canStart(Player.STATE_READY, true));
        assertTrue(PreCachePolicy.canStart(Player.STATE_READY, false));
    }

    @Test
    public void shouldOnlyPreCacheProgressiveMedia() {
        assertTrue(PreCachePolicy.supportsContentType(C.CONTENT_TYPE_OTHER));
        assertFalse(PreCachePolicy.supportsContentType(C.CONTENT_TYPE_HLS));
        assertFalse(PreCachePolicy.supportsContentType(C.CONTENT_TYPE_DASH));
        assertFalse(PreCachePolicy.supportsContentType(C.CONTENT_TYPE_SS));
    }

    @Test
    public void shouldCacheFromSeekPositionImmediately() {
        assertEquals(90_000, PreCachePolicy.getStart(10_000, 90_000));
        assertTrue(PreCachePolicy.shouldPreCache(90_000, 90_000, true, 120_000));
    }

    @Test
    public void shouldAdvanceCacheWindowOnlyAfterConfiguredStep() {
        assertFalse(PreCachePolicy.shouldPreCache(19_999, 0, false, 80_000));
        assertTrue(PreCachePolicy.shouldPreCache(20_000, 0, false, 80_000));
        assertTrue(PreCachePolicy.shouldPreCache(0, C.TIME_UNSET, false, 80_000));
    }

    @Test
    public void shouldLimitCacheWindowToRemainingMedia() {
        assertEquals(30_000, PreCachePolicy.getLength(120_000, 90_000, 80_000));
        assertEquals(0, PreCachePolicy.getLength(120_000, 120_000, 80_000));
    }
}
