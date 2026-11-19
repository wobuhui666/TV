package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class LibraryEpisodeSelectionTest {
    @Test public void shouldSelectCoordinatesFromReorderedAndIncompleteSourceLists() {
        Episode fourth = Episode.create("S02E04", "fourth");
        Flag line = flag("line", Episode.create("S02E07", "seventh"), fourth, Episode.create("S02E01", "first"));

        assertSame(fourth, SmartSourceSelector.findEpisode(line, EpisodeTarget.coordinate(2, 4)));
        assertNull(SmartSourceSelector.findEpisode(line, EpisodeTarget.coordinate(2, 2)));
        assertEquals(Integer.valueOf(2), EpisodeTarget.coordinate(2, 4).getSeason());
    }

    @Test public void shouldNotConvertAbsoluteAnimeNumbersIntoSeasonPositions() {
        Vod source = source("作品 第二季", flag("line", Episode.create("第13集", "absolute-13"), Episode.create("第14集", "absolute-14")));
        Integer season = SmartSourceSelector.implicitEpisodeSeason(source);

        assertEquals(Integer.valueOf(2), season);
        assertNull(SmartSourceSelector.findEpisode(source.getFlags().get(0), EpisodeTarget.coordinate(2, 1), season));
    }

    @Test public void shouldRejectGeneratedOrdinalsEvenWhenListPositionLooksCorrect() {
        Episode third = Episode.create("03", "third-file"); third.setGeneratedLabel(true);
        Flag line = flag("line", Episode.create("第1集", "first"), Episode.create("第2集", "second"), third);

        assertNull(SmartSourceSelector.findEpisode(line, EpisodeTarget.coordinate(1, 3), 1));
        assertSame(third, SmartSourceSelector.findEpisode(line, EpisodeTarget.of(third, 1), 1));
    }

    @Test public void shouldNotResumeAGeneratedUrlFromAContradictoryKnownSeason() {
        Episode generated = Episode.create("03", "shared-url"); generated.setGeneratedLabel(true);
        EpisodeTarget target = EpisodeTarget.of(generated, 2);

        assertTrue(target.matches(generated, 2));
        assertFalse(target.matches(generated, 1));
        assertFalse(target.matches(Episode.create("S01E03", "shared-url"), 2));
    }

    @Test public void shouldLeaveBareNumbersUnresolvedInAMixedSeasonCatalogue() {
        Episode bare = Episode.create("第3集", "bare-third");
        Vod source = source("作品", flag("line", Episode.create("S01E01", "first"), Episode.create("S02E01", "second"), bare));

        assertNull(SmartSourceSelector.implicitEpisodeSeason(source));
        assertNull(SmartSourceSelector.findEpisode(source.getFlags().get(0), EpisodeTarget.coordinate(1, 3), SmartSourceSelector.implicitEpisodeSeason(source)));
        assertNull(SmartSourceSelector.findEpisode(source.getFlags().get(0), EpisodeTarget.coordinate(2, 3), SmartSourceSelector.implicitEpisodeSeason(source)));
    }

    @Test public void shouldNotUseAConflictingSourceTitleToAssignBareFileSeasons() {
        Episode explicit = Episode.create("S02E03", "second-season-third");
        Vod source = source("作品 第一季", flag("line", explicit, Episode.create("第3集", "bare-third")));

        assertNull(SmartSourceSelector.implicitEpisodeSeason(source));
        assertSame(explicit, SmartSourceSelector.findEpisode(source.getFlags().get(0), EpisodeTarget.coordinate(2, 3), null));
        assertNull(SmartSourceSelector.findEpisode(source.getFlags().get(0), EpisodeTarget.coordinate(1, 3), null));
    }

    @Test public void shouldAllowAnImplicitFirstSeasonOnlyWithoutConflictingFileEvidence() {
        Vod source = source("作品", flag("line", Episode.create("第1集", "first"), Episode.create("第3集", "third")));
        assertEquals(Integer.valueOf(1), SmartSourceSelector.implicitEpisodeSeason(source));
        assertNull(SmartSourceSelector.findEpisode(source.getFlags().get(0), EpisodeTarget.coordinate(2, 3), SmartSourceSelector.implicitEpisodeSeason(source)));
    }

    @Test public void shouldKeepSpecialsPreviewsAndPartsSeparateFromNormalEpisodes() {
        Episode special = Episode.create("S00E03", "special-third");
        Flag line = flag("line", special, Episode.create("S01E03 预告", "preview"), Episode.create("第3集上", "upper"));

        assertSame(special, SmartSourceSelector.findEpisode(line, EpisodeTarget.coordinate(0, 3)));
        assertNull(SmartSourceSelector.findEpisode(line, EpisodeTarget.coordinate(1, 3), 1));
    }

    @Test public void shouldRequireManualChoiceForEqualCoordinateReleases() {
        Flag line = flag("line", Episode.create("[A] Show.S02E03.1080p.mkv", "release-a"), Episode.create("[B] Show.S02E03.1080p.mkv", "release-b"));
        assertNull(SmartSourceSelector.findEpisode(line, EpisodeTarget.coordinate(2, 3)));
    }

    @Test public void shouldRejectInvalidCatalogueCoordinates() {
        for (int[] coordinate : new int[][]{{-1, 1}, {1000, 1}, {1, 0}, {1, 10000}}) {
            try { EpisodeTarget.coordinate(coordinate[0], coordinate[1]); fail("Invalid coordinate accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void shouldKeepIdenticallyNamedGeneratedFilesSeparateInPreparedSelections() {
        Episode first = Episode.create("03", "first-file"); first.setGeneratedLabel(true);
        Episode second = Episode.create("03", "second-file"); second.setGeneratedLabel(true);
        Episode copied = Episode.create("03", "first-file"); copied.setGeneratedLabel(true);

        assertFalse(EpisodeTarget.of(first, 1).sameSelection(EpisodeTarget.of(second, 1)));
        assertTrue(EpisodeTarget.of(first, 1).sameSelection(EpisodeTarget.of(copied, 1)));
        assertFalse(EpisodeTarget.of(first, 1).sameSelection(EpisodeTarget.of(copied, 2)));
        assertFalse(EpisodeTarget.of(first, 1).sameSelection(EpisodeTarget.coordinate(1, 3)));
    }

    @Test public void shouldKeepRawVersionsAndFreshCoordinatesSeparateInPreparedSelections() {
        assertTrue(EpisodeTarget.coordinate(2, 3).sameSelection(EpisodeTarget.coordinate(2, 3)));
        assertFalse(EpisodeTarget.coordinate(2, 3).sameSelection(EpisodeTarget.coordinate(1, 3)));
        assertFalse(EpisodeTarget.coordinate(2, 3).sameSelection(EpisodeTarget.coordinate(2, 4)));
        assertFalse(EpisodeTarget.of(Episode.create("S02E03", "one")).sameSelection(EpisodeTarget.of(Episode.create("S02E03", "two"))));
        assertTrue(EpisodeTarget.coordinate(2, 3).offset(1).sameSelection(EpisodeTarget.coordinate(2, 3).offset(1)));
        assertFalse(EpisodeTarget.coordinate(2, 3).offset(1).sameSelection(EpisodeTarget.coordinate(1, 3).offset(1)));
    }

    @Test public void shouldSelectRealDecoratedFileCoordinatesWithoutCrossingSeasons() {
        Episode first = Episode.create("[1.4GB]01.4k.mp4【S01/4k】", "first");
        Episode second = Episode.create("[1.5GB]02.4k.mp4【S01/4k】", "second");
        Episode later = Episode.create("[1.5GB]01.4k.mp4【S02/4k】", "later-first");
        Flag multiSeason = flag("line", later, second, first);
        assertSame(first, SmartSourceSelector.findEpisode(multiSeason, EpisodeTarget.coordinate(1, 1)));
        assertSame(second, SmartSourceSelector.findEpisode(multiSeason, EpisodeTarget.coordinate(1, 2)));
        assertSame(later, SmartSourceSelector.findEpisode(multiSeason, EpisodeTarget.coordinate(2, 1)));
        Episode laBi = Episode.create("[5.3GB]Joy.of.Life.2019.S01.EP01.WEB-DL.4K.HEVC.AAC-LeagueWEB.mp4【Q 庆余年 (2019) 4K 高码率 单集5G 国语中字 全46集】", "labi-first");
        assertSame(laBi, SmartSourceSelector.findEpisode(flag("labi", laBi), EpisodeTarget.coordinate(1, 1)));
    }

    private Flag flag(String name, Episode... episodes) {
        Flag flag = new Flag(name); flag.getEpisodes().addAll(List.of(episodes)); return flag;
    }

    private Vod source(String name, Flag... flags) {
        Vod source = new Vod(); source.setName(name); source.setTypeName("电视剧"); source.setFlags(List.of(flags)); return source;
    }
}
