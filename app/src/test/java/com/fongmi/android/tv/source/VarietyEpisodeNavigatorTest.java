package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertSame;

public class VarietyEpisodeNavigatorTest {

    @Test
    public void shouldSkipBonusAndMemberEntriesBetweenRegularIssues() {
        List<Episode> episodes = list("第1期", "第1期 加更", "第1期 会员版", "第2期", "第2期 加更", "第3期");

        assertNext(episodes, 0, 3);
        assertNext(episodes, 3, 5);
        assertSame(episodes.get(0), EpisodeNavigator.relative(episodes, 3, -1));
        assertSame(episodes.get(5), EpisodeNavigator.relative(episodes, 0, 2));
    }

    @Test
    public void shouldKeepTheSelectedBonusOrPureTrack() {
        List<Episode> episodes = list("第1期", "第1期 加更", "第1期 纯享", "第2期", "第2期 加更", "第2期 纯享");

        assertNext(episodes, 1, 4);
        assertNext(episodes, 2, 5);
    }

    @Test
    public void shouldKeepRegularNumericEntriesOnTheRegularVarietyTrack() {
        List<Episode> episodes = list("1", "第1期 加更", "2", "第2期 加更", "3");

        assertNext(episodes, 0, 2);
        assertNext(episodes, 2, 4);
    }

    @Test
    public void shouldUseActualDateOrderIncludingCalendarAndIssueGaps() {
        List<Episode> episodes = list("20260301 第1期", "20260301 第1期 加更", "20260315 第4期", "20260402 第9期");

        assertNext(episodes, 0, 2);
        assertNext(episodes, 2, 3);
        assertSame(episodes.get(0), EpisodeNavigator.relative(episodes, 2, -1));
    }

    @Test
    public void shouldNavigateDateOnlyEntriesAndSkipDifferentVariants() {
        List<Episode> episodes = list("2026-03-01", "20260301 会员版", "2026年3月8日 完整版", "2026.3.22");

        assertNext(episodes, 0, 2);
        assertNext(episodes, 2, 3);
        assertNext(episodes, 1, 1);
    }

    @Test
    public void shouldPreserveDescendingAndReversedDisplayOrder() {
        List<Episode> episodes = list("20260322", "20260322 加更", "20260308", "20260301");

        assertNext(episodes, 0, 2);
        assertNext(episodes, 2, 3);
        Collections.reverse(episodes);
        assertNext(episodes, 0, 1);
        assertNext(episodes, 1, 3);
    }

    @Test
    public void shouldNotFallBackFromDatesToUndatedIssueNumbers() {
        List<Episode> episodes = list("20260301 第1期", "第2期", "第3期");

        assertNext(episodes, 0, 0);
        assertSame(episodes.get(1), EpisodeNavigator.relative(episodes, 1, -1));
    }

    @Test
    public void shouldStopAtAMissingUndatedIssue() {
        List<Episode> episodes = list("第1期", "第1期 加更", "第3期");

        assertNext(episodes, 0, 0);
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 2, -1));
    }

    @Test
    public void shouldNavigateUpperMiddleAndLowerPartsBeforeTheNextIssue() {
        List<Episode> episodes = list("第1期 上", "第1期 上 加更", "第1期 中", "第1期 下", "第2期 上", "第2期 下");

        assertNext(episodes, 0, 2);
        assertNext(episodes, 2, 3);
        assertNext(episodes, 3, 4);
        assertSame(episodes.get(3), EpisodeNavigator.relative(episodes, 4, -1));
    }

    @Test
    public void shouldNavigateDatePartsAfterReversingTheDisplay() {
        List<Episode> episodes = list("20260301 第1期 上", "20260301 第1期 下", "20260308 第2期 上", "20260308 第2期 下");
        Collections.reverse(episodes);

        assertNext(episodes, 0, 1);
        assertNext(episodes, 1, 2);
        assertNext(episodes, 2, 3);
        assertSame(episodes.get(0), EpisodeNavigator.relative(episodes, 1, -1));
    }

    @Test
    public void shouldNotReplaceASplitIssueWithAnUnsplitEpisode() {
        List<Episode> episodes = list("第1期 上", "第1期", "第1期 下", "第2期", "第2期 上");

        assertNext(episodes, 0, 2);
        assertNext(episodes, 2, 4);
    }

    @Test
    public void shouldStopForDuplicateNextEntriesWithDifferentUrls() {
        List<Episode> episodes = list("20260301", "20260308", "2026-03-08");

        assertNext(episodes, 0, 0);
        List<Episode> issues = list("第1期", "第2期", "第二期");
        assertNext(issues, 0, 0);
    }

    @Test
    public void shouldAllowDuplicateEntriesWhenTheyShareTheSameUrl() {
        List<Episode> episodes = list("20260301", "20260308", "2026-03-08");
        episodes.set(2, Episode.create("2026-03-08", episodes.get(1).getUrl()));

        assertNext(episodes, 0, 1);
    }

    @Test
    public void shouldStopWhenTheSameDateHasConflictingKnownIssueNumbers() {
        List<Episode> episodes = list("20260301", "20260308 第2期", "20260308 第3期");

        assertNext(episodes, 0, 0);
    }

    @Test
    public void shouldKeepTheSelectedSeasonAndRejectUnknownSeasonConflicts() {
        List<Episode> episodes = list("第一季 第1期", "第二季 第1期", "第一季 第2期", "第二季 第2期");

        assertNext(episodes, 0, 2);
        assertNext(episodes, 1, 3);
        List<Episode> unknown = list("第1期", "第一季 第2期", "第二季 第2期");
        assertNext(unknown, 0, 0);
    }

    @Test
    public void shouldKeepPreviewsAndSpecialsOnTheirOwnTracks() {
        List<Episode> episodes = list("第1期", "第1期 预告", "特别篇 第1期", "第2期", "第2期 预告", "特别篇 第2期");

        assertNext(episodes, 0, 3);
        assertNext(episodes, 1, 4);
        assertNext(episodes, 2, 5);
    }

    @Test
    public void shouldKeepSplitPreviewNavigationOnThePreviewTrack() {
        List<Episode> episodes = list("第3期上预告", "第3期上", "第3期下预告", "第4期上",
                "第4期上 预告", "第4期下[预告]");

        assertNext(episodes, 0, 2);
        assertNext(episodes, 2, 4);
        assertNext(episodes, 4, 5);
        assertSame(episodes.get(2), EpisodeNavigator.relative(episodes, 4, -1));
    }

    @Test
    public void shouldKeepSplitSpecialNavigationOnTheSpecialTrack() {
        List<Episode> episodes = list("第3期上特别篇", "第3期上", "第3期下特别篇", "第4期上",
                "第4期上 特辑", "第4期下[特别篇]");

        assertNext(episodes, 0, 2);
        assertNext(episodes, 2, 4);
        assertNext(episodes, 4, 5);
    }

    @Test
    public void shouldPreserveReleaseAndRevisionEvidenceWithinAVarietyTrack() {
        List<Episode> episodes = list("[ANi] Show 第1期 v2 [1080p].mkv", "[Other] Show 第1期 [1080p].mkv",
                "[ANi] Show 第2期 v1 [1080p].mkv", "[ANi] Show 第2期 v2 [1080p].mkv");

        assertNext(episodes, 0, 3);
    }

    @Test
    public void shouldStopOnConflictingSourceDateOrder() {
        List<Episode> episodes = list("20260308", "20260301", "20260315");

        assertNext(episodes, 0, 0);
    }

    @Test
    public void shouldKeepIndexNavigationForUnknownAndGeneratedCurrentItems() {
        List<Episode> episodes = list("访谈", "20260301", "20260308");
        assertNext(episodes, 0, 1);
        episodes.set(0, Episode.create("01", "generated"));
        episodes.get(0).setGeneratedLabel(true);
        assertNext(episodes, 0, 1);
    }

    @Test
    public void shouldUseNewlyAppendedVarietyEntries() {
        List<Episode> episodes = list("20260301", "20260301 加更");
        Episode selected = episodes.get(0);
        assertNext(episodes, 0, 0);
        Episode next = Episode.create("20260315", "next");
        episodes.add(next);

        assertSame(next, EpisodeNavigator.relative(episodes, 0, 1));
        assertSame(selected, episodes.get(0));
    }

    private void assertNext(List<Episode> episodes, int current, int next) {
        assertSame(episodes.get(next), EpisodeNavigator.relative(episodes, current, 1));
    }

    private List<Episode> list(String... names) {
        List<Episode> episodes = new ArrayList<>();
        for (int index = 0; index < names.length; index++) episodes.add(Episode.create(names[index], "url-" + index));
        return episodes;
    }
}
