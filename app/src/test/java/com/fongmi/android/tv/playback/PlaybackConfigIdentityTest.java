package com.fongmi.android.tv.playback;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaybackConfigIdentityTest {
    @Test
    public void normalizesSchemeHostDefaultPortAndTrailingSlash() {
        assertEquals(PlaybackConfigIdentity.keyForUrl("HTTPS://Example.COM:443/config/"), PlaybackConfigIdentity.keyForUrl("https://example.com/config"));
    }
}
