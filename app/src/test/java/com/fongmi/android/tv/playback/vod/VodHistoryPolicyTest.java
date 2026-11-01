package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;

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

    @Test
    public void shouldRemoveLegacyAiOffsetsBeforeStartingWithoutLosingProgress() {
        History history = new History();
        history.setOpening(90_000);
        history.setOpeningSource("ai:old-episode");
        history.setEnding(120_000);
        history.setEndingSource("AI");
        history.setPosition(25_000);

        assertEquals(25_000, new VodHistoryPolicy().startPositionMs(history));
        assertEquals(C.TIME_UNSET, history.getOpening());
        assertEquals(C.TIME_UNSET, history.getEnding());
        assertEquals("unknown", history.getOpeningSource());
    }

    @Test
    public void shouldPreserveManualAndImportedOffsetsWhileRemovingLegacyAi() {
        History history = new History();
        history.setOpening(90_000);
        history.setOpeningSource("manual");
        history.setEnding(120_000);
        history.setEndingSource("unknown");

        assertEquals(90_000, new VodHistoryPolicy().startPositionMs(history));
        assertEquals(120_000, history.getEnding());
    }

    @Test
    public void shouldNotRestoreLegacyOffsetsWhenMergingAnotherSource() throws Exception {
        History archivedSource = new History();
        archivedSource.setOpening(90_000);
        archivedSource.setOpeningSource("ai:previous-source");
        archivedSource.setEnding(120_000);
        archivedSource.setEndingSource("ai");
        History current = new History();
        current.setPosition(25_000);
        Method copy = History.class.getDeclaredMethod("copyTo", History.class);
        copy.setAccessible(true);
        copy.invoke(archivedSource, current);

        assertEquals(C.TIME_UNSET, current.getOpening());
        assertEquals(C.TIME_UNSET, current.getEnding());
        assertEquals(25_000, new VodHistoryPolicy().startPositionMs(current));

        archivedSource.setOpening(70_000);
        archivedSource.setOpeningSource("manual");
        copy.invoke(archivedSource, current);
        assertEquals(70_000, current.getOpening());
        assertEquals("manual", current.getOpeningSource());
    }

    @Test
    public void shouldCarryManualZeroToAnotherSourceAndPreserveTheCurrentManualChoice() throws Exception {
        History source = new History();
        source.setOpening(0);
        source.setOpeningSource("manual");
        source.setEnding(0);
        source.setEndingSource("manual");
        History current = new History();
        Method copy = History.class.getDeclaredMethod("copyTo", History.class);
        copy.setAccessible(true);
        copy.invoke(source, current);
        assertEquals(0, current.getOpening());
        assertEquals(0, current.getEnding());
        assertEquals("manual", current.getOpeningSource());
        assertEquals("manual", current.getEndingSource());

        source.setOpening(70_000);
        source.setEnding(90_000);
        copy.invoke(source, current);
        assertEquals(0, current.getOpening());
        assertEquals(0, current.getEnding());
        current.setOpening(50_000);
        copy.invoke(source, current);
        assertEquals(50_000, current.getOpening());
    }
}
