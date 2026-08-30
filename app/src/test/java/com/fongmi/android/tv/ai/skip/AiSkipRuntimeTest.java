package com.fongmi.android.tv.ai.skip;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.History;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AiSkipRuntimeTest {

    @Test
    public void shouldPreserveExplicitManualBoundaries() {
        assertTrue(AiSkipRuntime.isManual(0, "manual"));
        assertTrue(AiSkipRuntime.isManual(30_000, "manual"));
    }

    @Test
    public void shouldTreatLegacyBoundariesAsManual() {
        assertTrue(AiSkipRuntime.isManual(30_000, "unknown"));
        assertFalse(AiSkipRuntime.isManual(0, "unknown"));
        assertFalse(AiSkipRuntime.isManual(30_000, "ai"));
    }

    @Test
    public void shouldSubmitFinalizedCaptureWithoutActivePlaybackSession() {
        List<AiSkipApi.Sample> samples = List.of(
                new AiSkipApi.Sample("opening", 0, 30_000, "samples/opening.wav"),
                new AiSkipApi.Sample("ending", 570_000, 30_000, "samples/ending.wav"));

        assertTrue(AiSkipRuntime.isReadyToSubmit(true, false, 0, 600_000, samples));
        assertFalse(AiSkipRuntime.isReadyToSubmit(true, false, 1, 600_000, samples));
    }

    @Test
    public void shouldEncodeMediaKeyInAiBoundarySource() {
        assertEquals("ai:episode-a", AiSkipRuntime.aiSource("episode-a"));
        assertTrue(AiSkipRuntime.isAiSourceFor("ai:episode-a", "episode-a"));
        assertFalse(AiSkipRuntime.isAiSourceFor("ai:episode-a", "episode-b"));
    }

    @Test
    public void shouldClearLegacyAndForeignAiBoundariesWhenEpisodeChanges() {
        History history = new History();
        history.setOpening(30_000);
        history.setOpeningSource("ai");
        history.setEnding(60_000);
        history.setEndingSource(AiSkipRuntime.aiSource("episode-a"));

        assertTrue(AiSkipRuntime.clearStaleAiBoundaries(history, "episode-b"));
        assertEquals(C.TIME_UNSET, history.getOpening());
        assertEquals("unknown", history.getOpeningSource());
        assertEquals(C.TIME_UNSET, history.getEnding());
        assertEquals("unknown", history.getEndingSource());
    }

    @Test
    public void shouldPreserveCurrentAiAndManualBoundaries() {
        History history = new History();
        history.setOpening(30_000);
        history.setOpeningSource(AiSkipRuntime.aiSource("episode-a"));
        history.setEnding(60_000);
        history.setEndingSource("manual");

        assertFalse(AiSkipRuntime.clearStaleAiBoundaries(history, "episode-a"));
        assertEquals(30_000, history.getOpening());
        assertEquals(AiSkipRuntime.aiSource("episode-a"), history.getOpeningSource());
        assertEquals(60_000, history.getEnding());
        assertEquals("manual", history.getEndingSource());
    }

    @Test
    public void shouldUseHistoryPositionWhenStaleAiBoundaryWasCleared() {
        History history = new History();
        history.setOpening(30_000);
        history.setOpeningSource(AiSkipRuntime.aiSource("episode-a"));
        history.setPosition(120_000);

        assertEquals(120_000, AiSkipRuntime.prepareStartPosition("episode-b", history, 30_000));
        assertEquals(C.TIME_UNSET, history.getOpening());
        assertEquals("unknown", history.getOpeningSource());
    }

    @Test
    public void shouldKeepRequestedPositionForCurrentEpisode() {
        History history = new History();
        history.setOpening(30_000);
        history.setOpeningSource(AiSkipRuntime.aiSource("episode-a"));
        history.setPosition(120_000);

        assertEquals(7_000, AiSkipRuntime.prepareStartPosition("episode-a", history, 7_000));
        assertEquals(30_000, history.getOpening());
        assertEquals(AiSkipRuntime.aiSource("episode-a"), history.getOpeningSource());
    }

    @Test
    public void shouldOnlySaveBoundarySnapshotForSameEpisode() {
        History history = new History();
        history.setCid(1);
        history.setKey("site@@@vod");
        history.setVodRemarks("Episode 1");
        history.setEpisodeUrl("ep-1");
        history.setOpening(30_000);
        history.setOpeningSource(AiSkipRuntime.aiSource("episode-a"));

        AiSkipRuntime.BoundarySnapshot snapshot = AiSkipRuntime.BoundarySnapshot.from(history);

        assertTrue(snapshot.matches(history));
        history.setVodRemarks("Episode 2");
        history.setEpisodeUrl("ep-2");
        assertFalse(snapshot.matches(history));
    }

    @Test
    public void shouldRejectSessionSnapshotAfterEpisodeChanges() {
        History history = new History();
        history.setCid(1);
        history.setKey("site@@@vod");
        history.setVodRemarks("Episode 1");
        history.setEpisodeUrl("ep-1");

        AiSkipRuntime.EpisodeSnapshot snapshot = AiSkipRuntime.EpisodeSnapshot.from(history);

        assertTrue(snapshot.matches(history));
        history.setVodRemarks("Episode 2");
        history.setEpisodeUrl("ep-2");
        assertFalse(snapshot.matches(history));
    }

    @Test
    public void shouldRejectBoundarySnapshotAfterManualBoundaryChanges() {
        History history = new History();
        history.setCid(1);
        history.setKey("site@@@vod");
        history.setVodRemarks("Episode 1");
        history.setEpisodeUrl("ep-1");
        history.setOpening(30_000);
        history.setOpeningSource(AiSkipRuntime.aiSource("episode-a"));

        AiSkipRuntime.BoundarySnapshot snapshot = AiSkipRuntime.BoundarySnapshot.from(history);

        assertTrue(snapshot.matches(history));
        history.setOpening(45_000);
        history.setOpeningSource("manual");
        assertFalse(snapshot.matches(history));
    }
}
