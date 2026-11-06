package com.fongmi.android.tv.bean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HistorySnapshotTest {
    @Test
    public void shouldKeepDispatchedHistoryIndependentFromContinuedPlaybackAndReceiverChanges() {
        History source = new History();
        source.setKey("site@@@video@@@1");
        source.setCid(1);
        source.setEpisodeUrl("episode-4");
        source.setEpisodeLabelGenerated(true);
        source.setVodRemarks("04");
        source.setPosition(120_000);
        source.setDuration(1_800_000);
        source.setSpeed(1.25f);
        History dispatched = source.copy();
        source.setPosition(130_000);
        source.setEpisodeUrl("episode-5");
        dispatched.setKey("site@@@video@@@2");
        dispatched.setCid(2);

        assertEquals(120_000, dispatched.getPosition());
        assertEquals("episode-4", dispatched.getEpisodeUrl());
        assertTrue(dispatched.isEpisodeLabelGenerated());
        assertEquals("04", dispatched.getVodRemarks());
        assertEquals(1_800_000, dispatched.getDuration());
        assertEquals(1.25f, dispatched.getSpeed(), 0.001f);
        assertEquals("site@@@video@@@1", source.getKey());
        assertEquals(1, source.getCid());
    }
}
