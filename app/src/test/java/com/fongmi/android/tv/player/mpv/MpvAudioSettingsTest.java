package com.fongmi.android.tv.player.mpv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MpvAudioSettingsTest {

    @Test
    public void composesChannelAndLoudnessFilters() {
        assertEquals("", MpvAudioSettings.buildFilter(false, 0));
        assertEquals("lavfi=[pan=stereo|FL=FR|FR=FR]", MpvAudioSettings.buildFilter(false, 3));
        assertEquals("lavfi=[loudnorm=I=-18:LRA=11:TP=-1.5]", MpvAudioSettings.buildFilter(true, 0));
        assertEquals("lavfi=[pan=stereo|FL=0.5*FL+0.5*FR|FR=0.5*FL+0.5*FR],lavfi=[loudnorm=I=-18:LRA=11:TP=-1.5]", MpvAudioSettings.buildFilter(true, 2));
    }
}
