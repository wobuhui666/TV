package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class LibrarySourceMatcherEpisodeEvidenceTest {
    @Test public void shouldAcceptRealSourceGenresAndMissingTypeAfterPlaybackDetailsArrive() {
        for (String type : List.of("剧情,古装,国产", "剧情,古装,内地剧,内地", "")) {
            Vod source = source(type, "第01集", "第02集", "第03集");
            assertTrue(type, match(source, false, 1).automatic());
            assertEquals(match(source, false, 1), LibrarySourceMatcher.match(LibrarySourceMatcher.prepareSource(source),
                    LibrarySourceMatcher.prepareWork("庆余年", List.of(), "2019", false, 1)));
        }
    }

    @Test public void shouldRecognizeReleaseFilenamesWithoutUsingListPositions() {
        Vod source = source("", "[5.3GB]Joy.of.Life.2019.S01.EP03.WEB-DL.4K.HEVC.AAC-LeagueWE",
                "[5.5GB]Joy.of.Life.2019.S01.EP01.WEB-DL.4K.HEVC.AAC-LeagueWE");
        assertTrue(match(source, false, 1).automatic());
    }

    @Test public void shouldAcceptBareNumbersAndSingleEpisodeDetailsWithoutRequiringType() {
        assertTrue(match(source("", "1", "2", "3"), false, 1).automatic());
        assertTrue(match(source("", "第01集"), false, 1).automatic());
        Vod generated = source("", "第01集", "第02集");
        generated.getFlags().get(0).getEpisodes().forEach(episode -> episode.setGeneratedLabel(true));
        assertTrue(match(generated, false, 1).automatic());
    }

    @Test public void shouldKeepUnknownSummariesAsCandidatesUntilPlaybackDetailsArrive() {
        Vod summary = source("");
        assertTrue(match(summary, false, 1).matches());
        assertFalse(match(summary, false, 1).automatic());
        summary.getFlags().get(0).getEpisodes().add(Episode.create("1", ""));
        summary.getFlags().get(0).getEpisodes().add(Episode.create("2", "   "));
        assertFalse(match(summary, false, 1).automatic());
    }

    @Test public void shouldAcceptHydratedMovieWithoutTypeOrYear() {
        Vod movie = source("", "正片");
        movie.setName("星际穿越"); movie.setYear("");
        assertTrue(LibrarySourceMatcher.match(movie, "星际穿越", List.of("Interstellar"), "2014", true, null).automatic());
        assertTrue(LibrarySourceMatcher.match(movie, "星际穿越", List.of("Interstellar"), "2014", true, null).matches());
    }

    @Test public void shouldAcceptHydratedSeriesWithoutTypeOrYear() {
        Vod series = source("", "1", "2", "3");
        series.setName("庆余年"); series.setYear("");
        assertTrue(match(series, false, 1).automatic());
        assertFalse(match(series, false, 2).automatic());
    }

    @Test public void shouldLeavePlaylistVariantsToEpisodeSelection() {
        for (String[] labels : List.of(new String[]{"第1集 预告", "第2集 预告"},
                new String[]{"第1集上", "第2集下"}, new String[]{"S00E01", "S00E02"},
                new String[]{"第1集", "EP01"})) {
            assertTrue(match(source("", labels), false, 1).automatic());
        }
    }

    @Test public void shouldRetainTitleYearSeasonAndDeclaredMovieConflicts() {
        assertFalse(match(source("电影", "第01集", "第02集"), false, 1).automatic());
        Vod declaredSeries = source("电视剧", "正片"); declaredSeries.setName("庆余年");
        assertFalse(match(declaredSeries, true, null).matches());
        Vod source = source("", "第01集", "第02集");
        assertFalse(match(source, true, null).automatic());
        source.setYear("2024");
        assertFalse(match(source, false, 1).matches());
        assertFalse(match(source, false, 1).automatic());
        source.setYear("2019"); source.setName("庆余年 第二季");
        assertFalse(match(source, false, 1).matches());
        assertFalse(match(source, false, 1).automatic());
        source.setName("庆余年之帝王业");
        assertFalse(match(source, false, 1).matches());
        assertFalse(match(source, false, 1).automatic());
    }

    @Test public void shouldRetainExplicitMovieYearConflictEvenWithPlaybackDetails() {
        Vod movie = source("", "正片"); movie.setName("沙丘"); movie.setYear("1984");
        LibrarySourceMatcher.Match mismatch = LibrarySourceMatcher.match(movie, "沙丘", List.of(), "2021", true, null);
        assertFalse(mismatch.matches());
        assertFalse(mismatch.automatic());
    }

    private LibrarySourceMatcher.Match match(Vod source, boolean movie, Integer season) {
        return LibrarySourceMatcher.match(source, "庆余年", List.of(), "2019", movie, season);
    }

    private Vod source(String type, String... names) {
        Vod source = new Vod();
        source.setName("庆余年 第一季"); source.setYear("2019"); source.setTypeName(type);
        Flag flag = new Flag("line");
        for (int i = 0; i < names.length; i++) flag.getEpisodes().add(Episode.create(names[i], "file-" + i));
        source.getFlags().add(flag);
        return source;
    }
}
