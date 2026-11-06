package com.fongmi.android.tv.bean;

import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VodPlayIdTest {

    @Test
    public void shouldReadTheOptionalPlayIdWithoutUsingRemarksOrChangingItsValue() {
        Gson gson = new Gson();
        for (String json : new String[]{"{}", "{\"vod_play_id\":null}", "{\"vod_play_id\":\"\"}",
                "{\"vod_remarks\":\"第4集\"}"}) assertEquals("", gson.fromJson(json, Vod.class).getPlayId());
        Vod vod = gson.fromJson("{\"vod_play_id\":\"episode-04?token=a&x=1\"}", Vod.class);
        assertEquals("episode-04?token=a&x=1", vod.getPlayId());
        assertEquals(vod.getPlayId(), gson.fromJson(gson.toJson(vod), Vod.class).getPlayId());
    }

    @Test
    public void shouldRefreshCardContentWhenOnlyItsSelectedEpisodeChanges() {
        Vod first = card("episode-1");
        Vod second = card("episode-2");

        assertTrue(first.isSameItem(second));
        assertFalse(first.isSameContent(second));
        assertFalse(first.isSameCardContent(second));
        second.setPlayId("episode-1");
        assertTrue(first.isSameContent(second));
    }

    private Vod card(String playId) {
        Vod vod = new Vod();
        vod.setId("same-video");
        vod.setName("Show");
        vod.setPic("picture");
        vod.setRemarks("第4集");
        vod.setPlayId(playId);
        return vod;
    }
}
