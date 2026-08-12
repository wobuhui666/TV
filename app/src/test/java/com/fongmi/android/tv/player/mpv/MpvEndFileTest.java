package com.fongmi.android.tv.player.mpv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class MpvEndFileTest {

    @Test
    public void shouldMapKnownReasonsToLibmpvCodes() {
        assertEquals(MpvEndFile.REASON_EOF, MpvEndFile.mapReason("eof"));
        assertEquals(MpvEndFile.REASON_STOP, MpvEndFile.mapReason("stop"));
        assertEquals(MpvEndFile.REASON_QUIT, MpvEndFile.mapReason("quit"));
        assertEquals(MpvEndFile.REASON_ERROR, MpvEndFile.mapReason("error"));
        assertEquals(MpvEndFile.REASON_REDIRECT, MpvEndFile.mapReason("redirect"));
    }

    @Test
    public void shouldNeverMapStopToEof() {
        // A stop mapped to EOF surfaces STATE_ENDED and triggers auto-next on user stop.
        assertNotEquals(MpvEndFile.REASON_EOF, MpvEndFile.mapReason("stop"));
        assertNotEquals(MpvEndFile.REASON_EOF, MpvEndFile.mapReason("quit"));
        assertNotEquals(MpvEndFile.REASON_EOF, MpvEndFile.mapReason("redirect"));
    }

    @Test
    public void shouldMapUnknownOrMissingReasonToStop() {
        assertEquals(MpvEndFile.REASON_STOP, MpvEndFile.mapReason(null));
        assertEquals(MpvEndFile.REASON_STOP, MpvEndFile.mapReason(""));
        assertEquals(MpvEndFile.REASON_STOP, MpvEndFile.mapReason("unknown"));
    }
}
