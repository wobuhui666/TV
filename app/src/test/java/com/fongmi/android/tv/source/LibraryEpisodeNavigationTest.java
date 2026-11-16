package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class LibraryEpisodeNavigationTest {
    @Test public void shouldAdvanceAndGoBackByCoordinatesInAReorderedList() {
        Episode first = episode("S01E01", "first"), second = episode("S01E02", "second"), third = episode("S01E03", "third");
        List<Episode> list = List.of(third, first, second);
        assertSame(second, relative(list, 1, 1, 1, null));
        assertSame(first, relative(list, 2, -1, 1, null));
        assertSame(third, relative(list, 1, 2, 1, null));
    }

    @Test public void shouldStopAtTheMissingEpisodeInsteadOfJumpingToTheNextFile() {
        Episode first = episode("第1集", "first"), third = episode("第3集", "third");
        List<Episode> list = List.of(first, third);
        assertSame(first, relative(list, 0, 1, 1, 1));
        assertSame(third, relative(list, 1, -1, 1, 1));
        // Classic/manual source playback intentionally retains its established source order.
        assertSame(third, EpisodeNavigator.relative(list, 0, 1));
    }

    @Test public void shouldNotCrossSeasonOrMapAbsoluteAnimeNumbersToTheNextCoordinate() {
        Episode first = episode("S02E01", "first");
        List<Episode> list = List.of(first, episode("S01E02", "wrong-season"), episode("第14集", "absolute"));
        assertSame(first, relative(list, 0, 1, 2, 2));
    }

    @Test public void shouldNotUseTheCurrentSeasonToGuessAnUnmarkedFileInAMixedCatalogue() {
        Episode first = episode("S01E01", "first");
        List<Episode> list = List.of(first, episode("第2集", "bare"), episode("S02E02", "other-season"));
        assertSame(first, relative(list, 0, 1, 1, null));
    }

    @Test public void shouldIgnorePreviewsPartsAndGeneratedOrdinalsAsNextEpisodes() {
        Episode first = episode("第1集", "first");
        Episode generated = episode("02", "generated"); generated.setGeneratedLabel(true);
        List<Episode> list = List.of(first, episode("第2集预告", "preview"), episode("第2集上", "upper"), generated);
        assertSame(first, relative(list, 0, 1, 1, 1));
        assertSame(generated, relative(list, 3, -1, 1, 1));
    }

    @Test public void shouldKeepTheChosenReleaseAndRejectAmbiguousNextVersions() {
        Episode first = episode("[A] Show.S02E01.1080p.mkv", "first");
        Episode next = episode("[A] Show.S02E02.1080p.mkv", "next");
        List<Episode> list = List.of(first, episode("[B] Show.S02E02.720p.mkv", "other"), next);
        assertSame(next, relative(list, 0, 1, 2, null));
        List<Episode> ambiguous = List.of(episode("S02E01", "plain"), episode("[A] Show.S02E02.1080p.mkv", "a"), episode("[B] Show.S02E02.1080p.mkv", "b"));
        assertSame(ambiguous.get(0), relative(ambiguous, 0, 1, 2, null));
    }

    @Test public void shouldStopSafelyAtSpecialSeasonBoundariesAndUnsupportedCoordinates() {
        Episode special = episode("S00E01", "special"), next = episode("S00E02", "next");
        assertSame(next, relative(List.of(special, next, episode("S01E02", "regular")), 0, 1, 0, null));
        assertNull(relative(List.of(), 0, 1, 1, 1));
        Episode unnumbered = episode("前言", "intro");
        assertSame(unnumbered, relative(List.of(unnumbered, next), 0, 1, 1, null));
    }

    private Episode relative(List<Episode> episodes, int current, int offset, Integer season, Integer implicitSeason) {
        return EpisodeNavigator.relativeCoordinate(episodes, current, offset, season, implicitSeason);
    }

    private Episode episode(String name, String url) { return Episode.create(name, url); }
}
