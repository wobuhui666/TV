package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.bean.History;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackEventsTest {

    @Test
    public void customPresetOnlyEmitsRequestedSafeFields() throws Exception {
        History history = new History();
        history.setKey("site@@@vod@@@1");
        history.setPosition(10);
        history.setDuration(20);
        history.setVodName("名称");
        JsonObject result = PlaybackEvents.payload("event", "playback.progress", "config", history,
                "item", false, "custom", List.of("siteKey", "positionMs", "unsupported"), 30L);
        assertTrue(result.has("siteKey"));
        assertTrue(result.has("positionMs"));
        assertFalse(result.has("vodId"));
        assertFalse(result.has("unsupported"));
    }
}
