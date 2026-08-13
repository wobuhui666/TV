package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.PlaybackException;

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

    @Test
    public void shouldMapNativeFileErrorsToRecoveryActions() {
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, MpvEndFile.playbackErrorCode(0));
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, MpvEndFile.playbackErrorCode(-13));
        assertEquals(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, MpvEndFile.playbackErrorCode(-17));
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, MpvEndFile.playbackErrorCode(2));
    }
}
