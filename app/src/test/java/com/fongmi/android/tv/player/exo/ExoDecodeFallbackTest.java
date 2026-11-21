package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;

import com.fongmi.android.tv.player.engine.PlayerEngine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoDecodeFallbackTest {
    @Test public void audioFailureLeavesHighBitrateVideoOnHardware() {
        ExoDecodeFallback fallback = new ExoDecodeFallback(PlayerEngine.HARD);
        assertTrue(fallback.tryFallback(C.TRACK_TYPE_AUDIO));
        assertEquals(PlayerEngine.SOFT, fallback.getAudioDecode());
        assertEquals(PlayerEngine.HARD, fallback.getVideoDecode());
    }

    @Test public void videoFailureLeavesAudioModeUnchanged() {
        ExoDecodeFallback fallback = new ExoDecodeFallback(PlayerEngine.HARD);
        assertTrue(fallback.tryFallback(C.TRACK_TYPE_VIDEO));
        assertEquals(PlayerEngine.HARD, fallback.getAudioDecode());
        assertEquals(PlayerEngine.SOFT, fallback.getVideoDecode());
    }

    @Test public void eachTrackCanFallbackOnlyOnceUntilANewPlaybackRequest() {
        ExoDecodeFallback fallback = new ExoDecodeFallback(PlayerEngine.HARD);
        assertTrue(fallback.tryFallback(C.TRACK_TYPE_AUDIO));
        assertFalse(fallback.tryFallback(C.TRACK_TYPE_AUDIO));
        assertTrue(fallback.tryFallback(C.TRACK_TYPE_VIDEO));
        assertFalse(fallback.tryFallback(C.TRACK_TYPE_VIDEO));
        assertTrue(fallback.reset());
        assertEquals(PlayerEngine.HARD, fallback.getAudioDecode());
        assertEquals(PlayerEngine.HARD, fallback.getVideoDecode());
        assertFalse(fallback.reset());
        assertTrue(fallback.tryFallback(C.TRACK_TYPE_AUDIO));
    }

    @Test public void manualModeChangeClearsTemporaryRecovery() {
        ExoDecodeFallback fallback = new ExoDecodeFallback(PlayerEngine.HARD);
        assertTrue(fallback.tryFallback(C.TRACK_TYPE_AUDIO));
        fallback.setDecode(PlayerEngine.SOFT);
        assertEquals(PlayerEngine.SOFT, fallback.getAudioDecode());
        assertEquals(PlayerEngine.SOFT, fallback.getVideoDecode());
        assertFalse(fallback.tryFallback(C.TRACK_TYPE_VIDEO));
        fallback.setDecode(PlayerEngine.HARD);
        assertEquals(PlayerEngine.HARD, fallback.getAudioDecode());
        assertTrue(fallback.tryFallback(C.TRACK_TYPE_AUDIO));
    }

    @Test public void unknownAndSubtitleFailuresDoNotChangeAudioOrVideoModes() {
        ExoDecodeFallback fallback = new ExoDecodeFallback(PlayerEngine.HARD);
        assertFalse(fallback.tryFallback(C.TRACK_TYPE_UNKNOWN));
        assertFalse(fallback.tryFallback(C.TRACK_TYPE_TEXT));
        assertEquals(PlayerEngine.HARD, fallback.getAudioDecode());
        assertEquals(PlayerEngine.HARD, fallback.getVideoDecode());
    }
}
