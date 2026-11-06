package com.fongmi.android.tv.bean;

import androidx.media3.common.C;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CastHistorySnapshotTest {
    @Test
    public void shouldCaptureThePositionAtDispatchWithoutChangingSavedHistory() {
        History history = new History();
        history.setPosition(120_000);
        History sent = CastHistorySnapshot.capture(history, 135_000);
        history.setPosition(140_000);
        assertEquals(135_000, sent.getPosition());
        assertEquals(140_000, history.getPosition());
        assertEquals(140_000, CastHistorySnapshot.capture(history, C.TIME_UNSET).getPosition());
        assertNull(CastHistorySnapshot.capture(null, 135_000));
    }

    @Test
    public void shouldRewriteOnlyTheMediaIdAndMatchingEpisodeWhileKeepingConfigAndTitles() {
        History history = new History();
        history.setKey("push@@@/storage/movie.mkv@@@7");
        history.setVodName("/storage/movie.mkv original title");
        history.setEpisodeUrl("/storage/movie.mkv");
        History transported = CastHistorySnapshot.transport(history, ignored -> "http://sender/file/movie.mkv");
        assertEquals("push@@@http://sender/file/movie.mkv@@@7", transported.getKey());
        assertEquals("http://sender/file/movie.mkv", transported.getEpisodeUrl());
        assertEquals("/storage/movie.mkv original title", transported.getVodName());
        assertEquals("/storage/movie.mkv", history.getEpisodeUrl());
        assertEquals("push@@@/storage/movie.mkv@@@7", history.getKey());
    }
}
