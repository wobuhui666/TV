package com.fongmi.android.tv.player.skip;

import com.fongmi.android.tv.bean.History;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackEpisodeSnapshotTest {

    private static History history() {
        History history = new History();
        history.setCid(1);
        history.setKey("source|series");
        history.setVodFlag("line1");
        history.setVodRemarks("S02E03");
        history.setEpisodeUrl("episode3");
        return history;
    }

    @Test
    public void shouldRejectResultAfterTheMutableHistorySwitchesEpisode() {
        History history = history();
        PlaybackEpisodeSnapshot snapshot = PlaybackEpisodeSnapshot.from(history);
        history.setVodRemarks("S02E04");
        history.setEpisodeUrl("episode4");
        assertFalse(snapshot.matches(history));
    }

    @Test
    public void shouldRejectAnotherConfigSourceOrPlaybackLine() {
        History history = history();
        PlaybackEpisodeSnapshot snapshot = PlaybackEpisodeSnapshot.from(history);
        history.setCid(2);
        assertFalse(snapshot.matches(history));
        history.setCid(1);
        history.setKey("other-source|series");
        assertFalse(snapshot.matches(history));
        history.setKey("source|series");
        history.setVodFlag("line2");
        assertFalse(snapshot.matches(history));
    }

    @Test
    public void shouldAllowProgressAndManualChangesDuringTheSameEpisode() {
        History history = history();
        PlaybackEpisodeSnapshot snapshot = PlaybackEpisodeSnapshot.from(history);
        history.setPosition(100_000);
        history.setDuration(1_800_000);
        history.setOpening(0);
        history.setOpeningSource("manual");
        assertTrue(snapshot.matches(history));
    }
}
