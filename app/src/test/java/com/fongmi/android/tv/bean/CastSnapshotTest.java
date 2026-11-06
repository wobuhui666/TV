package com.fongmi.android.tv.bean;

import androidx.media3.common.C;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class CastSnapshotTest {
    @Test
    public void shouldKeepValidMpvHeadersAndIgnoreNullableEntriesInAnImmutableRequest() {
        Map<String, String> source = new HashMap<>();
        source.put("User-Agent", "player");
        source.put("Referer", null);
        source.put(null, "unused");
        Map<String, String> sent = CastSnapshot.headers(source);
        source.put("User-Agent", "changed");
        assertEquals(Map.of("User-Agent", "player"), sent);
        assertTrue(CastSnapshot.headers(null).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> sent.put("Cookie", "late"));
    }

    @Test
    public void shouldCaptureThePositionAtDispatchWithoutChangingSavedHistory() {
        History history = new History();
        history.setPosition(120_000);
        History sent = CastSnapshot.capture(history, 135_000);
        history.setPosition(140_000);
        assertEquals(135_000, sent.getPosition());
        assertEquals(140_000, history.getPosition());
        assertEquals(140_000, CastSnapshot.capture(history, C.TIME_UNSET).getPosition());
        assertNull(CastSnapshot.capture(null, 135_000));
    }

    @Test
    public void shouldRewriteOnlyTheMediaIdAndMatchingEpisodeWhileKeepingConfigAndTitles() {
        History history = new History();
        history.setKey("push@@@/storage/movie.mkv@@@7");
        history.setVodName("/storage/movie.mkv original title");
        history.setEpisodeUrl("/storage/movie.mkv");
        History transported = CastSnapshot.transport(history, ignored -> "http://sender/file/movie.mkv");
        assertEquals("push@@@http://sender/file/movie.mkv@@@7", transported.getKey());
        assertEquals("http://sender/file/movie.mkv", transported.getEpisodeUrl());
        assertEquals("/storage/movie.mkv original title", transported.getVodName());
        assertEquals("/storage/movie.mkv", history.getEpisodeUrl());
        assertEquals("push@@@/storage/movie.mkv@@@7", history.getKey());
    }
}
