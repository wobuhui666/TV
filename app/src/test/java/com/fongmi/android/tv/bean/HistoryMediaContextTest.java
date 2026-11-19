package com.fongmi.android.tv.bean;

import org.junit.Test;

import static org.junit.Assert.*;

public class HistoryMediaContextTest {
    @Test public void shouldKeepDifferentWorksSeasonsAndEpisodesSeparate() {
        History first = linked("tmdb:tv:10", 1, 4);
        assertTrue(first.sameMediaContext(linked("tmdb:tv:10", 1, 4)));
        assertFalse(first.sameMediaContext(linked("tmdb:tv:11", 1, 4)));
        assertFalse(first.sameMediaContext(linked("tmdb:tv:10", 2, 4)));
        assertFalse(first.sameMediaContext(linked("tmdb:tv:10", 1, 5)));
    }

    @Test public void shouldNotMergeAnOldUnlinkedBackupWithALinkedWork() {
        History old = new History();
        History linked = linked("tmdb:movie:10", null, null);
        assertFalse(old.sameMediaContext(linked));
        assertFalse(linked.sameMediaContext(old));
        assertTrue(old.sameMediaContext(new History()));
    }

    @Test public void shouldKeepAnUnresolvedRawEpisodeSeparateFromKnownCoordinates() {
        assertFalse(linked("tmdb:tv:10", null, null).sameMediaContext(linked("tmdb:tv:10", 1, 1)));
    }

    private History linked(String reference, Integer season, Integer episode) {
        History history = new History(); history.setMediaRef(reference); history.setMediaSeason(season); history.setMediaEpisode(episode); return history;
    }
}
