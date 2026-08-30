package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.concurrent.TimeUnit;

public class VodHistoryPolicyTest {

    @Test
    public void shouldKeepLocalProgressWhenSourceReportsAnotherPosition() {
        History history = new History();
        history.setPosition(240_000);
        history.setDuration(1_800_000);

        assertNull(new VodHistoryPolicy().acceptedResultPosition(history, 0L));
        assertEquals(240_000, history.getPosition());
    }

    @Test
    public void shouldAcceptSourcePositionWhenLocalProgressIsMissing() {
        History history = new History();
        history.setPosition(-1);
        history.setDuration(1_800_000);

        assertEquals(Long.valueOf(90_000), new VodHistoryPolicy().acceptedResultPosition(history, 90_000L));
    }

    @Test
    public void shouldRejectInvalidOrOutOfRangeSourcePosition() {
        History history = new History();
        history.setPosition(-1);
        history.setDuration(1_800_000);
        VodHistoryPolicy policy = new VodHistoryPolicy();

        assertNull(policy.acceptedResultPosition(history, -1L));
        assertNull(policy.acceptedResultPosition(history, 1_800_000L));
        assertNull(policy.acceptedResultPosition(history, null));
    }

    @Test
    public void shouldAcceptZeroSourcePositionForFreshHistory() {
        History history = new History();
        history.setPosition(-1);

        assertEquals(Long.valueOf(0), new VodHistoryPolicy().acceptedResultPosition(history, 0L));
    }

    @Test
    public void shouldTreatNegativeHistoryPositionAsUnset() {
        History history = new History();
        history.setOpening(-1);
        history.setPosition(-1);

        assertEquals(C.TIME_UNSET, new VodHistoryPolicy().startPositionMs(history));
    }

    @Test
    public void shouldRejectImplausibleSourcePositionWhenDurationIsUnknown() {
        History history = new History();
        history.setPosition(-1);

        assertNull(new VodHistoryPolicy().acceptedResultPosition(history, TimeUnit.DAYS.toMillis(1)));
    }

    @Test
    public void shouldClearPreviousEpisodeDurationWhenEpisodeChanges() {
        History history = new History();
        history.setVodRemarks("Episode 1");
        history.setEpisodeUrl("episode-1");
        history.setPosition(240_000);
        history.setDuration(1_800_000);

        new VodHistoryPolicy().updateEpisode(history, Flag.create("line"), Episode.create("Episode 2", "episode-2"));

        assertEquals(C.TIME_UNSET, history.getPosition());
        assertEquals(C.TIME_UNSET, history.getDuration());
    }
}
