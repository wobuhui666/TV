package com.fongmi.android.tv.player.failure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import java.net.SocketTimeoutException;

public class PlaybackFailureClassifierTest {

    @Test
    public void decoderAndDrmFailuresDoNotAffectSiteHealth() {
        PlaybackFailure decoder = PlaybackFailureClassifier.classifyExo(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null), "https://media.test/a");
        PlaybackFailure drm = PlaybackFailureClassifier.classifyExo(error(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, null), "https://media.test/a");
        assertEquals(PlaybackFailure.Category.DECODER, decoder.category());
        assertEquals(PlaybackFailure.Category.DRM, drm.category());
        assertFalse(decoder.affectsSiteHealth());
        assertFalse(drm.affectsSiteHealth());
    }

    @Test
    public void timeoutIsRecoverableSourceNetworkFailure() {
        PlaybackFailure failure = PlaybackFailureClassifier.classifyExo(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, new SocketTimeoutException()), "https://media.test/a");
        assertEquals(PlaybackFailure.Category.NETWORK, failure.category());
        assertTrue(failure.recoverable());
        assertTrue(failure.affectsSiteHealth());
    }

    @Test
    public void evidenceRemovesUrlsAndSecrets() {
        String evidence = PlaybackFailureClassifier.sanitize("GET https://example.test/a?token=abc Authorization: secret Cookie=x");
        assertFalse(evidence.contains("example.test"));
        assertFalse(evidence.contains("secret"));
        assertFalse(evidence.contains("Cookie=x"));
    }

    @Test
    public void mpvDoesNotGuessCategoryFromFreeText() {
        PlaybackException error = new PlaybackException("network codec demux error", null, PlaybackException.ERROR_CODE_UNSPECIFIED);
        PlaybackFailure failure = PlaybackFailureClassifier.classifyMpv(error, "https://media.test/a", 0);
        assertEquals(PlaybackFailure.Category.UNKNOWN, failure.category());
        assertFalse(failure.affectsSiteHealth());
    }

    @Test
    public void mpvMapsControlledNativeFileErrors() {
        PlaybackException error = error(PlaybackException.ERROR_CODE_UNSPECIFIED, null);
        PlaybackFailure network = PlaybackFailureClassifier.classifyMpv(error, "https://media.test/a", -13);
        PlaybackFailure output = PlaybackFailureClassifier.classifyMpv(error, "https://media.test/a", -14);
        PlaybackFailure media = PlaybackFailureClassifier.classifyMpv(error, "https://media.test/a", -17);
        assertEquals(PlaybackFailure.Category.NETWORK, network.category());
        assertEquals(PlaybackFailure.Category.OUTPUT, output.category());
        assertEquals(PlaybackFailure.Category.MEDIA, media.category());
        assertTrue(network.recoverable());
        assertFalse(output.affectsSiteHealth());
    }

    @Test
    public void unspecifiedErrorsHaveActionableChineseMessages() {
        PlaybackException error = error(PlaybackException.ERROR_CODE_UNSPECIFIED, null);
        PlaybackFailure exo = PlaybackFailureClassifier.classifyExo(error, "https://media.test/a");
        PlaybackFailure mpv = PlaybackFailureClassifier.classifyMpv(error, "https://media.test/a", 0);
        assertFalse(exo.userMessage().toLowerCase().contains("unspecified"));
        assertFalse(mpv.userMessage().toLowerCase().contains("unspecified"));
        assertTrue(exo.userMessage().contains("重试"));
        assertTrue(mpv.userMessage().contains("重试"));
    }

    private static PlaybackException error(int code, Throwable cause) {
        return new PlaybackException("failure", cause, code);
    }
}
