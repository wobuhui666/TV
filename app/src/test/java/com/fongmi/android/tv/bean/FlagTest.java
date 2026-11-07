package com.fongmi.android.tv.bean;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class FlagTest {

    @Test
    public void shouldIgnoreOutOfBoundsSavedPosition() {
        Flag flag = new Flag("main");
        Episode first = Episode.create("01", "url1");
        flag.getEpisodes().add(first);
        flag.getEpisodes().add(Episode.create("02", "url2"));
        flag.setPosition(5);

        assertNull(flag.find("unknown", true));
        assertSame(first, flag.find("unknown", false));
    }

    @Test
    public void shouldKeepCurrentEpisodeWhenPrependingNewEpisodes() {
        Flag flag = new Flag("main");
        Episode current = Episode.create("02", "url2");
        flag.getEpisodes().addAll(List.of(current, Episode.create("01", "url1")));
        flag.setPosition(0);

        flag.mergeEpisodes(List.of(Episode.create("03", "url3"), Episode.create("04", "url4")), true);

        assertEquals(2, flag.getPosition());
        assertSame(current, flag.getEpisodes().get(flag.getPosition()));
    }

    @Test
    public void shouldKeepCurrentEpisodeWhenMergingDuplicates() {
        Flag flag = new Flag("main");
        Episode current = Episode.create("01", "url1");
        flag.getEpisodes().add(current);
        flag.setPosition(0);

        flag.mergeEpisodes(List.of(Episode.create("01", "url1"), Episode.create("02", "url2")), true);
        flag.mergeEpisodes(List.of(Episode.create("01", "url1"), Episode.create("02", "url2")), true);

        assertEquals(2, flag.getEpisodes().size());
        assertEquals(1, flag.getPosition());
        assertSame(current, flag.getEpisodes().get(flag.getPosition()));
    }

    @Test
    public void shouldKeepForwardSelectionAndUnsetPositionWhenMerging() {
        Flag flag = new Flag("main");
        Episode current = Episode.create("01", "url1");
        flag.getEpisodes().add(current);
        flag.setPosition(0);
        flag.mergeEpisodes(List.of(Episode.create("02", "url2")), false);
        assertEquals(0, flag.getPosition());
        assertSame(current, flag.getEpisodes().get(flag.getPosition()));

        flag.setPosition(-1);
        flag.mergeEpisodes(List.of(Episode.create("03", "url3")), true);
        assertEquals(-1, flag.getPosition());
        flag.setPosition(100);
        flag.mergeEpisodes(List.of(Episode.create("04", "url4")), true);
        assertEquals(100, flag.getPosition());
    }

    @Test
    public void shouldRejectWrongDateEvenForSingleton() {
        Flag flag = Flag.create("main", "2026-03-08 第3期$url");

        assertNull(flag.find("2026-03-01 第3期", false));
        assertNull(flag.find("2026-03-01 第3期", true));
    }

    @Test
    public void shouldNotUseSavedPositionForMissingIssueOrDate() {
        Flag flag = Flag.create("main", "2026-03-01 第1期$one#2026-03-08 第2期$two");
        flag.setPosition(1);

        assertNull(flag.find("2026-03-15 第3期", false));
        assertNull(flag.find("第3期", false));
        assertNull(flag.find("2026-03-01 第2期", false));
    }

    @Test
    public void shouldMatchEquivalentDatesAndRegularAliases() {
        Flag flag = Flag.create("main", "2026年3月1日 第3期 正片$regular#2026-03-01 第3期 加更$extra");

        assertSame(flag.getEpisodes().get(0), flag.find("20260301 第3期", false));
        assertSame(flag.getEpisodes().get(1), flag.find("20260301 第3期 加更版", true));
    }

    @Test
    public void shouldKeepPartAndVersionWhenMatchingIssue() {
        Flag flag = Flag.create("main", "第3期 上期$upper#第3期 下期$lower#第3期 会员版$member#第3期 纯享版$pure");

        assertSame(flag.getEpisodes().get(1), flag.find("第3期（下期）", false));
        assertSame(flag.getEpisodes().get(2), flag.find("第3期会员版", false));
        assertNull(flag.find("第3期", false));
        assertNull(flag.find("第3期 加更", false));
    }

    @Test
    public void shouldRequireChoiceForEqualDateCandidatesWithDifferentUrls() {
        Flag flag = Flag.create("main", "20260301$one#20260301$two");
        flag.setPosition(0);

        assertNull(flag.find("20260301", false));
    }

    @Test
    public void shouldAllowEqualDateCandidatesSharingTheSameUrl() {
        Flag flag = new Flag("main");
        Episode first = Episode.create("2026-03-01", "same");
        flag.getEpisodes().addAll(List.of(first, Episode.create("2026年3月1日", "same")));

        assertSame(first, flag.find("20260301", true));
    }

    @Test
    public void shouldRejectInvalidDatesAndMergedIssueRangesEvenWithExactNames() {
        for (String name : List.of("20260230", "20260301 20260308", "第3-4期")) {
            Flag flag = Flag.create("main", name + "$url");

            assertNull(flag.find(name, false));
            assertEquals(0, flag.getEpisodes().get(0).getScore(name, -1));
        }
    }

    @Test
    public void shouldKeepOrdinaryFirstEpisodeAndSavedPositionBehavior() {
        Flag singleton = Flag.create("main", "正片$url");
        assertSame(singleton.getEpisodes().get(0), singleton.find("", false));
        Flag flag = Flag.create("main", "第1集$one#第2集$two");
        flag.setPosition(1);

        assertSame(flag.getEpisodes().get(1), flag.find("unknown", false));
        assertSame(flag.getEpisodes().get(0), flag.find("第1集", true));
        assertEquals(100, flag.getEpisodes().get(0).getScore("第1集", 1));
    }
}
