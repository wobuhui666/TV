package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LibrarySourceMatcherPreparedTest {
    private record Target(String title, List<String> aliases, String year, boolean movie, Integer season) { }

    private Vod source(String title, String year, String type) {
        Vod source = new Vod();
        source.setName(title); source.setYear(year); source.setTypeName(type);
        return source;
    }

    @Test public void shouldPreserveScoresAndSafetyWhenPreparedEvidenceIsReused() {
        List<Target> targets = List.of(
                new Target("黑客帝国", List.of("The Matrix"), "1999", true, null),
                new Target("The Matrix", List.of(), "1999", true, null),
                new Target("It", List.of(), "2017", true, null),
                new Target("星际穿越", List.of("Interstellar"), "2014", true, null),
                new Target("公园男孩", List.of("Trailer Park Boys"), "2001", false, null),
                new Target("庆余年", List.of("Joy of Life"), "2019", false, null),
                new Target("庆余年", List.of("Joy of Life"), "2019", false, 1),
                new Target("庆余年", List.of("Joy of Life"), "2019", false, 2),
                new Target("Planet Earth", List.of("地球脉动"), "2006", false, 2),
                new Target("Sweet", List.of(), "2019", false, null),
                new Target("4K", List.of(), "2020", true, null),
                new Target("1917", List.of(), "2019", true, null),
                new Target("爱", List.of(), "2020", true, null));
        List<LibrarySourceMatcher.PreparedWork> prepared = targets.stream().map(target -> LibrarySourceMatcher.prepareWork(
                target.title, target.aliases, target.year, target.movie, target.season)).toList();
        for (String title : List.of("黑客帝国 / The Matrix", "黑客帝国（The Matrix）", "毒液（又名：猛毒）",
                "The Matrix (Trailer)", "The Matrix II", "It", "Titanic", "Interstellar",
                "Trailer Park Boys", "Trailer Park Boys / Review", "庆余年", "慶餘年２【國語】 1080p",
                "Joy of Life S02", "庆余年 第二季 / 影评", "Planet Earth II", "Sweet2", "4K", "1917", "因为爱情有幸福")) {
            for (String year : List.of("", "2019", "2024")) for (String type : List.of("电影", "电视剧", "")) {
                Vod source = source(title, year, type);
                var candidate = LibrarySourceMatcher.prepareSource(source);
                for (int index = 0; index < targets.size(); index++) {
                    Target target = targets.get(index);
                    assertEquals(title + " / " + year + " / " + type + " -> " + target,
                            LibrarySourceMatcher.match(source, target.title, target.aliases, target.year, target.movie, target.season),
                            LibrarySourceMatcher.match(candidate, prepared.get(index)));
                }
            }
        }
    }

    @Test public void shouldGiveTheSameVerdictWhenOnePreparedWorkJudgesSummariesDirectly() {
        List<String> aliases = List.of("Joy of Life", "慶餘年");
        var work = LibrarySourceMatcher.prepareWork("庆余年", aliases, "2019", false, 1);
        for (String title : List.of("庆余年", "Joy of Life", "慶餘年 第一季", "庆余年 第二季", "庆余年 / 影评", "余年")) {
            for (String year : List.of("", "2019", "2024")) for (String type : List.of("电影", "电视剧", "")) {
                Vod source = source(title, year, type);
                LibrarySourceMatcher.Match expected = LibrarySourceMatcher.match(source, "庆余年", aliases, "2019", false, 1);
                assertEquals(title + " / " + year + " / " + type, expected, work.match(source));
                assertEquals(title + " / " + year + " / " + type, expected, work.match(LibrarySourceMatcher.prepareSource(source)));
            }
        }
    }

    @Test public void shouldAcceptAKeptSummaryOnceRefinedAliasesNameIt() {
        var summary = LibrarySourceMatcher.prepareSource(source("Joy of Life", "", "电视剧"));
        assertFalse(LibrarySourceMatcher.prepareWork("庆余年", List.of(), "2019", false, 1).match(summary).matches());
        assertTrue(LibrarySourceMatcher.prepareWork("庆余年", List.of("Joy of Life"), "2019", false, 1).match(summary).matches());
        assertFalse(LibrarySourceMatcher.prepareWork("庆余年", List.of("Joy of Life"), "2019", false, 2).match(
                LibrarySourceMatcher.prepareSource(source("Joy of Life 第一季", "", "电视剧"))).matches());
    }

    @Test public void shouldRejectEverySourceBeforeAWorkTitleIsKnown() {
        var unknown = LibrarySourceMatcher.prepareWork("", List.of(), "", false, null);
        assertEquals(new LibrarySourceMatcher.Match(false, false, 0), unknown.match(source("庆余年", "2019", "电视剧")));
        assertEquals(new LibrarySourceMatcher.Match(false, false, 0), unknown.match((Vod) null));
    }

    @Test public void shouldKeepLaterSeasonYearRulesAndImplicitFirstSeasonDistinct() {
        var first = LibrarySourceMatcher.prepareWork("庆余年", List.of(), "2019", false, 1);
        var second = LibrarySourceMatcher.prepareWork("庆余年", List.of(), "2019", false, 2);
        var unselected = LibrarySourceMatcher.prepareWork("庆余年", List.of(), "2019", false, null);
        var firstSource = LibrarySourceMatcher.prepareSource(source("庆余年", "2019", "电视剧"));
        var laterSource = LibrarySourceMatcher.prepareSource(source("庆余年 第二季", "2024", "电视剧"));
        assertEquals(new LibrarySourceMatcher.Match(true, true, 90), LibrarySourceMatcher.match(firstSource, first));
        assertEquals(new LibrarySourceMatcher.Match(false, false, 0), LibrarySourceMatcher.match(laterSource, first));
        assertEquals(new LibrarySourceMatcher.Match(true, true, 90), LibrarySourceMatcher.match(laterSource, second));
        assertEquals(new LibrarySourceMatcher.Match(true, false, 80), LibrarySourceMatcher.match(laterSource, unselected));
    }

    @Test public void shouldPreserveAliasScoreAndExcludeSupplementaryClips() {
        var work = LibrarySourceMatcher.prepareWork("黑客帝国", List.of("The Matrix"), "1999", true, null);
        assertEquals(new LibrarySourceMatcher.Match(true, true, 86), LibrarySourceMatcher.match(
                LibrarySourceMatcher.prepareSource(source("The Matrix", "1999", "电影")), work));
        assertEquals(new LibrarySourceMatcher.Match(false, false, 45), LibrarySourceMatcher.match(
                LibrarySourceMatcher.prepareSource(source("The Matrix / Trailer", "1999", "电影")), work));
        assertEquals(new LibrarySourceMatcher.Match(false, false, 0), LibrarySourceMatcher.match(
                LibrarySourceMatcher.prepareSource(source("The Matrix", "1999", "电视剧")), work));
    }

    @Test public void shouldPreserveMissingMetadataAndPlaybackEvidenceWhenPrepared() {
        List<Target> targets = List.of(
                new Target("星际穿越", List.of("Interstellar"), "2014", true, null),
                new Target("庆余年", List.of("Joy of Life"), "2019", false, 1),
                new Target("庆余年", List.of("Joy of Life"), "2019", false, 2));
        for (String title : List.of("星际穿越", "Interstellar", "庆余年", "庆余年 第一季", "庆余年 第二季")) {
            for (String year : List.of("", "2014", "2019", "2024")) for (String type : List.of("", "剧情,国产", "电影", "电视剧")) {
                for (String url : List.of("", "   ", "dynamic-episode-token")) {
                    Vod source = source(title, year, type);
                    Flag flag = new Flag("line"); flag.getEpisodes().add(Episode.create("1", url)); source.getFlags().add(flag);
                    var preparedSource = LibrarySourceMatcher.prepareSource(source);
                    for (Target target : targets) {
                        assertEquals(title + " / " + year + " / " + type + " / " + url + " -> " + target,
                                LibrarySourceMatcher.match(source, target.title, target.aliases, target.year, target.movie, target.season),
                                LibrarySourceMatcher.match(preparedSource, LibrarySourceMatcher.prepareWork(
                                        target.title, target.aliases, target.year, target.movie, target.season)));
                    }
                }
            }
        }
    }

    @Test public void shouldExtractTheSameSeasonWithoutPreparingUnusedMediaIdentityFields() {
        for (String title : List.of("庆余年", "慶餘年２【國語】 1080p", "某剧 第十二季", "Joy of Life S02",
                "Planet Earth II", "Sweet2", "庆余年 (2019)", "1917", "4K", "黑客帝国 / The Matrix", "Dark Season 3")) {
            for (String year : List.of("", "2019", "2024")) for (String type : List.of("电影", "电视剧", "")) {
                Vod source = source(title, year, type);
                source.setRemarks("全集 2026 第五季");
                assertEquals(title, MediaMatcher.identity(source).getSeason(), MediaMatcher.extractSeason(source.getName()));
            }
        }
    }

    @Test public void shouldPreserveDateYearNormalizationAndExplicitTitleYearsWhenPrepared() {
        for (String title : List.of("流浪地球", "流浪地球 (2019)", "流浪地球 (2020)", "庆余年 第一季")) {
            for (String year : List.of("2019", "2020", "2019-11-26", "2020-11-26", "上映：2020", "")) {
                Vod source = source(title, year, "");
                Flag flag = new Flag("line"); flag.getEpisodes().add(Episode.create("1", "playback-token")); source.getFlags().add(flag);
                for (boolean movie : List.of(true, false)) {
                    String expected = movie ? "流浪地球" : "庆余年";
                    Integer season = movie ? null : 1;
                    assertEquals(title + " / " + year + " / " + movie,
                            LibrarySourceMatcher.match(source, expected, List.of(), "2019", movie, season),
                            LibrarySourceMatcher.match(LibrarySourceMatcher.prepareSource(source),
                                    LibrarySourceMatcher.prepareWork(expected, List.of(), "2019", movie, season)));
                }
            }
        }
    }
}
