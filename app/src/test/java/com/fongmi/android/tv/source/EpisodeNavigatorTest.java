package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class EpisodeNavigatorTest {

    @Test
    public void shouldHandleEmptyListsAndZeroOffset() {
        assertNull(EpisodeNavigator.relative(null, 0, 1));
        assertNull(EpisodeNavigator.relative(List.of(), 0, 1));
        List<Episode> episodes = list("01", "02");
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 1, 0));
    }

    @Test
    public void shouldKeepSourceOrderForOrdinaryListsIncludingGaps() {
        List<Episode> episodes = list("第1集", "第3集", "特别访谈");
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 1, 1));
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 2, 1));
    }

    @Test
    public void shouldKeepSourceOrderForUnknownAndGeneratedLabels() {
        List<Episode> episodes = list("前言", "访谈", "花絮");
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 0, 1));
        List<Episode> generated = list("01", "02", "03");
        generated.forEach(episode -> episode.setGeneratedLabel(true));
        assertSame(generated.get(1), EpisodeNavigator.relative(generated, 0, 1));
    }

    @Test
    public void shouldStopBeforeAnotherReleaseRestartsAtEpisodeOne() {
        List<Episode> episodes = list(ani(1), ani(2), toaru(1), toaru(2));
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 1, 1));
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 2, -1));
    }

    @Test
    public void shouldNotSwitchReleaseWhenVersionsHaveNoOverlappingEpisodes() {
        List<Episode> episodes = list(ani(1), ani(2), toaru(3), toaru(4));
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 1, 1));
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 2, -1));
    }

    @Test
    public void shouldFollowCurrentReleaseThroughInterleavedVersions() {
        List<Episode> episodes = list(ani(1), toaru(1), ani(2), toaru(2));
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(episodes.get(3), EpisodeNavigator.relative(episodes, 1, 1));
        assertSame(episodes.get(0), EpisodeNavigator.relative(episodes, 2, -1));
    }

    @Test
    public void shouldKeepDifferentQualityReleasesSeparate() {
        List<Episode> episodes = list("[ANi] Show - 01 [1080p].mkv", "[ANi] Show - 01 [2160p].mkv",
                "[ANi] Show - 02 [1080p].mkv", "[ANi] Show - 02 [2160p].mkv");
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(episodes.get(3), EpisodeNavigator.relative(episodes, 1, 1));
    }

    @Test
    public void shouldContinueWithinOneReleaseWhenFileRevisionsDiffer() {
        List<Episode> episodes = list("[ANi] Show - 01v2 [1080p].mkv", toaru(1),
                "[ANi] Show - 02v1 [1080p].mkv");
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldSkipOtherRevisionsOfTheSameEpisode() {
        List<Episode> episodes = list("[ANi] Show - 01v2 [1080p].mkv", "[ANi] Show - 01v1 [1080p].mkv",
                "[ANi] Show - 02v1 [1080p].mkv", toaru(1));
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldSkipDuplicateBareEpisodeRevisionsWithoutOtherVersions() {
        List<Episode> episodes = list("01v2", "01v1", "02v1");
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldRecognizeAscendingOrderAcrossRepeatedRevisionBlocks() {
        List<Episode> episodes = list("[ANi] Show - 01v1 [1080p].mkv", "[ANi] Show - 02v1 [1080p].mkv",
                "[ANi] Show - 01v2 [1080p].mkv", "[ANi] Show - 02v2 [1080p].mkv", toaru(1), toaru(2));
        assertSame(episodes.get(3), EpisodeNavigator.relative(episodes, 2, 1));
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 1, 1));
    }

    @Test
    public void shouldPreferTheSameRevisionForDuplicateNextEpisodeCandidates() {
        List<Episode> episodes = list("[ANi] Show - 01v2 [1080p].mkv", "[ANi] Show - 02v1 [1080p].mkv",
                toaru(1), "[ANi] Show - 02v2 [1080p].mkv", toaru(2));
        assertSame(episodes.get(3), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldUseAnotherRevisionOnlyBeforeTheReleaseBlockResets() {
        List<Episode> episodes = list("[ANi] Show - 01v2 [1080p].mkv", "[ANi] Show - 02v1 [1080p].mkv",
                "[ANi] Show - 01v1 [1080p].mkv", "[ANi] Show - 02v2 [1080p].mkv", toaru(1));
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldRecognizeDescendingOrderAcrossReversedRevisionBlocks() {
        List<Episode> episodes = list("[ANi] Show - 01v1 [1080p].mkv", "[ANi] Show - 02v1 [1080p].mkv",
                "[ANi] Show - 01v2 [1080p].mkv", "[ANi] Show - 02v2 [1080p].mkv", toaru(1));
        Collections.reverse(episodes);
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 1, 1));
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 2, 1));
    }

    @Test
    public void shouldIgnoreFileSizeAndChecksumChangesWithinOneRelease() {
        List<Episode> episodes = list("[494.7MB][ANi] Show - 01 [1080p][12ABCDEF].mkv", toaru(1),
                "[501.2MB][ANi] Show - 02 [1080p][DEADBEEF].mkv");
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldSkipSameEpisodeDuplicatesInsideMixedLists() {
        List<Episode> episodes = list(ani(1), ani(1), toaru(1), ani(2));
        assertSame(episodes.get(3), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldStopWhenCurrentReleaseIsMissingTheNextEpisode() {
        List<Episode> episodes = list(ani(1), ani(3), toaru(1), toaru(2), toaru(3));
        assertSame(episodes.get(0), EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 1, -1));
    }

    @Test
    public void shouldWorkAfterReversingTheDisplayList() {
        List<Episode> episodes = list(ani(1), toaru(1), ani(2), toaru(2));
        Collections.reverse(episodes);
        assertSame(episodes.get(3), EpisodeNavigator.relative(episodes, 1, 1));
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 3, -1));
    }

    @Test
    public void shouldUseNewlyAppendedEpisodesWithoutChangingTheCurrentObject() {
        List<Episode> episodes = list(ani(1), toaru(1));
        Episode current = episodes.get(0);
        assertSame(current, EpisodeNavigator.relative(episodes, 0, 1));
        episodes.add(episode(toaru(2), "other-next"));
        Episode next = episode(ani(2), "current-release-next");
        episodes.add(next);
        assertSame(next, EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(current, episodes.get(0));
    }

    @Test
    public void shouldStayInTheExplicitlySelectedSeason() {
        List<Episode> episodes = list("[ANi] Show S01E01.mkv", "[ANi] Show S02E01.mkv", "[ANi] Show S01E02.mkv");
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 1, 1));
    }

    @Test
    public void shouldAllowOneUnambiguousSeasonWithinTheSelectedRelease() {
        List<Episode> episodes = list("[ANi] Show - 01 [1080p].mkv", toaru(1), "[ANi] Show S01E02 [1080p].mkv");
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldNotGuessWhichSeasonAnUnqualifiedEpisodeBelongsTo() {
        List<Episode> episodes = list("01", "S01E02", "S02E02");
        assertSame(episodes.get(0), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldPreserveUpperAndLowerPartsWithinTheSameRelease() {
        List<Episode> episodes = list("[ANi] Show 第1集上.mkv", "[Other] Show 第1集上.mkv",
                "[ANi] Show 第1集下.mkv", "[ANi] Show 第2集上.mkv");
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(episodes.get(3), EpisodeNavigator.relative(episodes, 2, 1));
        assertSame(episodes.get(0), EpisodeNavigator.relative(episodes, 2, -1));
    }

    @Test
    public void shouldNotChooseMergedEpisodesOrPreviewsAsTheNextEpisode() {
        List<Episode> episodes = list(ani(1), toaru(1), "[ANi] 暗部共生少女 - 02-03 [1080p].mkv",
                "[ANi] 暗部共生少女 - 02 预告 [1080p].mkv");
        assertSame(episodes.get(0), EpisodeNavigator.relative(episodes, 0, 1));
    }

    @Test
    public void shouldAdvanceMultipleLogicalEpisodesWhenRequested() {
        List<Episode> episodes = list(ani(1), toaru(1), ani(2), toaru(2), ani(3));
        assertSame(episodes.get(4), EpisodeNavigator.relative(episodes, 0, 2));
    }

    @Test
    public void shouldStopOnConflictingReleaseOrderRatherThanGuessing() {
        List<Episode> episodes = list(ani(2), ani(1), toaru(1), ani(3));
        assertSame(episodes.get(0), EpisodeNavigator.relative(episodes, 0, 1));
    }

    private List<Episode> list(String... names) {
        List<Episode> episodes = new ArrayList<>();
        for (int index = 0; index < names.length; index++) episodes.add(episode(names[index], "url-" + index));
        return episodes;
    }

    private Episode episode(String name, String url) {
        return Episode.create(name, url);
    }

    private String ani(int number) {
        return String.format("[494.7MB][ANi] 暗部共生少女 - %02d [1080P][Baha][WEB-DL][AAC AVC].mp4", number);
    }

    private String toaru(int number) {
        return String.format("[1.4GB]Toaru.Anbu.no.Item.S01E%02d.1080p.WEB-DL.AAC.H264.mkv", number);
    }
}
