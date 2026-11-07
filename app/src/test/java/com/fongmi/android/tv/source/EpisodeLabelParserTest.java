package com.fongmi.android.tv.source;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EpisodeLabelParserTest {

    @Test
    public void shouldParseExplicitSeasonsSeparatelyFromEpisodes() {
        for (String name : new String[]{"S02E03", "s02.e03", "2x03", "第2季第3集", "第二季第三集", "Ｓ０２Ｅ０３"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, Integer.valueOf(2), label.season());
            assertEquals(name, Integer.valueOf(3), label.number());
            assertTrue(name, label.isApiSafe());
            assertEquals(name, "", label.edition());
        }
    }

    @Test
    public void shouldParseChineseAndFullWidthNumbersWithoutInventingASeason() {
        for (String name : new String[]{"第十二集", "十二", "12", "ＥＰ１２", "Episode 12", "十二话", "第１２集"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertNull(name, label.season());
            assertEquals(name, Integer.valueOf(12), label.number());
            assertEquals(name, "", label.edition());
        }
        assertEquals(Integer.valueOf(1023), EpisodeLabelParser.parse("第一千零二十三集").number());
        assertEquals(Integer.valueOf(203), EpisodeLabelParser.parse("第两百零三話").number());
        assertEquals(Integer.valueOf(2), EpisodeLabelParser.parse("Show 第二季").season());
        assertNull(EpisodeLabelParser.parse("Show 第二季").number());
    }

    @Test
    public void shouldKeepTheSameEditionAcrossAnimeEpisodesAndDifferentSizes() {
        EpisodeLabel first = EpisodeLabelParser.parse("[494.7MB][ANi] 暗部共生少女 - 04 [1080P][Baha][WEB-DL][AAC AVC][1A2B3C4D].mp4");
        EpisodeLabel next = EpisodeLabelParser.parse("[512.1MB][ANi] 暗部共生少女 - 05 [1080P][Baha][WEB-DL][AAC AVC][9A8B7C6D].mp4");
        EpisodeLabel other = EpisodeLabelParser.parse("[1.4GB]Toaru.Anbu.no.Item.S01E04.1080p.WEB-DL.AAC.H264.mkv");
        assertEquals(Integer.valueOf(4), first.number());
        assertEquals(Integer.valueOf(5), next.number());
        assertEquals(first.edition(), next.edition());
        assertNotEquals(first.edition(), other.edition());
        assertFalse(first.edition().isEmpty());
        assertTrue(first.isApiSafe());
        assertEquals(Integer.valueOf(1), other.season());
        assertEquals(Integer.valueOf(4), other.number());
    }

    @Test
    public void shouldSeparateQualityLanguageAndReleaseRevision() {
        EpisodeLabel first = EpisodeLabelParser.parse("[Group] Show - 01v2 [1080p][CHS].mkv");
        EpisodeLabel next = EpisodeLabelParser.parse("[Group] Show - 02v2 [CHS][1080p].mkv");
        assertEquals(Integer.valueOf(1), first.number());
        assertEquals(first.edition(), next.edition());
        assertNotEquals(first.edition(), EpisodeLabelParser.parse("[Group] Show - 01 [1080p][CHS].mkv").edition());
        assertNotEquals(first.edition(), EpisodeLabelParser.parse("[Group] Show - 01v2 [720p][CHS].mkv").edition());
        assertNotEquals(first.edition(), EpisodeLabelParser.parse("[Group] Show - 01v2 [1080p][CHT].mkv").edition());
        assertNotEquals(EpisodeLabelParser.parse("[Group] Show - 01 [1920x1080].mkv").edition(),
                EpisodeLabelParser.parse("[Group] Show - 01 [3840x2160].mkv").edition());
    }

    @Test
    public void shouldKeepAudioChannelNumbersOutOfEpisodeMatching() {
        EpisodeLabel first = EpisodeLabelParser.parse("Show - 04 AAC 5.1.mkv");
        EpisodeLabel next = EpisodeLabelParser.parse("Show - 05 AAC 5.1.mkv");
        assertEquals(Integer.valueOf(4), first.number());
        assertEquals(Integer.valueOf(5), next.number());
        assertEquals(first.edition(), next.edition());
        assertEquals(Integer.valueOf(4), EpisodeLabelParser.parse("Show - 04 DTS-HD.MA.5.1.mkv").number());
        assertEquals(Integer.valueOf(4), EpisodeLabelParser.parse("Show - 04 DDP5.1.mkv").number());
    }

    @Test
    public void shouldNotTreatEachEpisodesPlotTitleAsADifferentEdition() {
        for (String name : new String[]{"第01集：初遇", "第02集：重逢", "EP01 Hello", "EP02 World", "第03集 [再会]"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertTrue(name, label.isSingleEpisode());
            assertEquals(name, "", label.edition());
        }
        assertEquals(EpisodeLabelParser.parse("Show EP01 Hello").edition(), EpisodeLabelParser.parse("Show EP02 World").edition());
        assertEquals(EpisodeLabelParser.parse("Show.S01E01.Pilot.1080p.mkv").edition(),
                EpisodeLabelParser.parse("Show.S01E02.Return.1080p.mkv").edition());
        assertEquals(EpisodeLabelParser.parse("Show.S01E01.The.Beginning.1080p.mkv").edition(),
                EpisodeLabelParser.parse("Show.S01E02.The.End.1080p.mkv").edition());
        assertNotEquals(EpisodeLabelParser.parse("Show.S01E01.The.Beginning.1080p.mkv").edition(),
                EpisodeLabelParser.parse("Show.S01E02.The.End.2160p.mkv").edition());
    }

    @Test
    public void shouldIgnoreUnknownPostEpisodePlotBracketsWhileKeepingGroupsAndPlatforms() {
        EpisodeLabel first = EpisodeLabelParser.parse("[ANi] Show.S01E01.[The.Beginning].[Baha].1080p.mkv");
        EpisodeLabel next = EpisodeLabelParser.parse("[ANi] Show.S01E02.[The.End].[Baha].1080p.mkv");
        assertEquals(Integer.valueOf(1), first.number());
        assertEquals(Integer.valueOf(2), next.number());
        assertEquals(first.edition(), next.edition());
        assertNotEquals(first.edition(), EpisodeLabelParser.parse("[Other] Show.S01E01.[The.Beginning].[Baha].1080p.mkv").edition());
        assertNotEquals(first.edition(), EpisodeLabelParser.parse("[ANi] Show.S01E01.[The.Beginning].[bilibili].1080p.mkv").edition());
        assertEquals(EpisodeLabelParser.parse("Show.S01E01.[The.Beginning].1080p.mkv").edition(),
                EpisodeLabelParser.parse("Show.S01E02.[The.End].1080p.mkv").edition());
    }

    @Test
    public void shouldPreserveSplitEpisodesAndRejectThemForApiCoordinates() {
        EpisodeLabel upper = EpisodeLabelParser.parse("第12集上");
        EpisodeLabel lower = EpisodeLabelParser.parse("第十二话（下）");
        assertEquals(Integer.valueOf(12), upper.number());
        assertEquals(Integer.valueOf(12), lower.number());
        assertEquals("upper", upper.part());
        assertEquals("lower", lower.part());
        assertTrue(upper.isSingleEpisode());
        assertFalse(upper.isApiSafe());
        assertEquals("upper", EpisodeLabelParser.parse("第12集上半集").part());
        assertEquals("part1", EpisodeLabelParser.parse("S01E03 Part 1").part());
        assertFalse(EpisodeLabelParser.parse("S01E03 Part 1").isApiSafe());
    }

    @Test
    public void shouldKeepSpecialsAndPreviewsSeparateFromRegularEpisodes() {
        for (String name : new String[]{"特别篇 第1集", "第1集 SP", "OVA1", "第1集 特典"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertTrue(name, label.isSpecial());
            assertFalse(name, label.isApiSafe());
        }
        assertTrue(EpisodeLabelParser.parse("03预告").isPreview());
        assertEquals(Integer.valueOf(3), EpisodeLabelParser.parse("03预告").number());
        assertFalse(EpisodeLabelParser.parse("03预告").isApiSafe());
        assertTrue(EpisodeLabelParser.parse("S00E01 Special").isApiSafe());
        assertEquals(Integer.valueOf(0), EpisodeLabelParser.parse("S00E01").season());
    }

    @Test
    public void shouldRejectRangesMultipleEpisodesAndConflictingSeasons() {
        for (String name : new String[]{"S01E02E03", "S01E02 S01E03", "S01E02 Episode3", "第01-02集", "第1集-第2集",
                "第十二至十三集", "第十二、十三集", "Episode 1-2", "01-02", "01 – 02", "S01E01_02", "S01E01–02", "[Group] Show - 01-02 [1080p].mkv",
                "S01 第2季 第3集", "S01E00", "第12集上下", "Episode 12.5", "Show - 12.5.mkv", "全集"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertTrue(name, label.isAmbiguous());
            assertNull(name, label.number());
            assertFalse(name, label.isApiSafe());
        }
    }

    @Test
    public void shouldIgnoreSizesHashesDatesYearsCodecsAndResolutionNumbers() {
        for (String name : new String[]{"1080p", "[1.4GB]", "494.7MB", "[1A2B3C4D]", "2026-10-05", "Show 2026.10.05 1080p",
                "2026", "1920x1080", "H264", "第一现场", "集结号", "第一集训队", "Show 03"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertNull(name, label.number());
            assertFalse(name, label.isApiSafe());
        }
        assertEquals(Integer.valueOf(3), EpisodeLabelParser.parse("[1.4GB] Show.S02E03.1080p.H264.mkv").number());
        assertNull(EpisodeLabelParser.parse("86 - 01 [1080p].mkv").number());
    }

    @Test
    public void shouldReadSeasonFoldersButNotUseDirectoryNumbersForEditionOrEpisode() {
        EpisodeLabel first = EpisodeLabelParser.parse("/downloads/Show 第二季/01.mp4");
        EpisodeLabel second = EpisodeLabelParser.parse("/downloads/Show 第二季/02.mp4");
        assertEquals(Integer.valueOf(2), first.season());
        assertEquals(Integer.valueOf(1), first.number());
        assertEquals(first.edition(), second.edition());
        assertEquals("", first.edition());
    }

    @Test
    public void shouldParseCalendarDatesWithoutUsingThemAsEpisodeNumbers() {
        for (String name : new String[]{"20261107", "20261107期", "第20261107期", "2026-11-07", "2026.11.7",
                "2026/11/07", "2026_11_07", "2026 11 07", "2026年11月7日", "２０２６１１０７", "[2026-11-07]"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, "2026-11-07", label.airDate());
            assertTrue(name, label.hasAirDate());
            assertTrue(name, label.isVariety());
            assertFalse(name, label.isAmbiguous());
            assertFalse(name, label.isApiSafe());
            assertNull(name, label.number());
            assertEquals(name, "", label.edition());
        }
        assertEquals("2024-02-29", EpisodeLabelParser.parse("20240229").airDate());
        assertEquals("2026-01-02", EpisodeLabelParser.parse("2026年1月2日").airDate());
    }

    @Test
    public void shouldRejectInvalidOrConflictingDates() {
        for (String name : new String[]{"20260229", "2026-02-30", "2026/13/07", "2026年11月31日", "20260007",
                "20261100", "0000-11-07", "20261107 20261108", "第3期 2026-02-30"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertTrue(name, label.isAmbiguous());
            assertFalse(name, label.isApiSafe());
            assertNull(name, label.number());
        }
        assertEquals("", EpisodeLabelParser.parse("20261107 20261108").airDate());
        assertEquals("2026-11-07", EpisodeLabelParser.parse("20261107 [2026-11-07]").airDate());
        assertFalse(EpisodeLabelParser.parse("20261107 [2026-11-07]").isAmbiguous());
    }

    @Test
    public void shouldKeepIssueNumbersAndDatesAsSeparateCoordinates() {
        for (String name : new String[]{"第3期", "第三期", "3期", "第３期", "[第三期]"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, Integer.valueOf(3), label.number());
            assertTrue(name, label.isIssue());
            assertTrue(name, label.isVariety());
            assertTrue(name, label.isApiSafe());
        }
        for (String name : new String[]{"20261107 第3期", "第3期 2026-11-07", "[2026年11月7日][第三期]"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, Integer.valueOf(3), label.number());
            assertEquals(name, "2026-11-07", label.airDate());
            assertTrue(name, label.isIssue());
            assertFalse(name, label.isApiSafe());
            assertEquals(name, "", label.edition());
        }
        assertFalse(EpisodeLabelParser.parse("第3集").isIssue());
        assertFalse(EpisodeLabelParser.parse("第3集").isVariety());
    }

    @Test
    public void shouldNormalizeVarietyVariantsBeforeRemovingEpisodePlotBrackets() {
        for (String name : new String[]{"第3期加更", "加更版 第3期", "[加更篇]第三期", "第三期[加更版]", "20261107加更版"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, "extra", label.variant());
            assertTrue(name, label.isVariety());
            assertFalse(name, label.isPreview());
            assertFalse(name, label.isApiSafe());
            assertEquals(name, "", label.edition());
        }
        for (String name : new String[]{"第3期会员版", "会员加长版第3期", "第三期（會員加長版）"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, Integer.valueOf(3), label.number());
            assertEquals(name, "member", label.variant());
        }
        assertEquals("pure", EpisodeLabelParser.parse("第三期純享版").variant());
        assertEquals("behind", EpisodeLabelParser.parse("第三期未播").variant());
        assertEquals("behind", EpisodeLabelParser.parse("第三期幕后篇").variant());
        assertEquals("behind", EpisodeLabelParser.parse("第三期幕後版").variant());
        EpisodeLabel behind = EpisodeLabelParser.parse("第三期花絮");
        assertEquals("behind", behind.variant());
        assertTrue(behind.isPreview());
        assertFalse(EpisodeLabelParser.parse("第三期幕后").isPreview());
        assertEquals("extra|member", EpisodeLabelParser.parse("第3期加更会员版").variant());
        assertEquals("extra|member", EpisodeLabelParser.parse("会员版加更第3期").variant());
        assertEquals(EpisodeLabelParser.parse("Show 第3期 [加更版][初遇]").edition(),
                EpisodeLabelParser.parse("Show 第4期 [加更篇][重逢]").edition());
    }

    @Test
    public void shouldTreatRegularContentLabelsAsTheUnmarkedVariant() {
        for (String name : new String[]{"第3期正片", "正片 第3期", "第3期完整版", "第3期 full", "第3期 Full Episode", "第3期 regular"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, Integer.valueOf(3), label.number());
            assertEquals(name, "", label.variant());
            assertEquals(name, "", label.edition());
            assertTrue(name, label.isApiSafe());
        }
        assertEquals(EpisodeLabelParser.parse("Show 第3期").edition(), EpisodeLabelParser.parse("Show 第3期正片").edition());
    }

    @Test
    public void shouldParseVarietyPartsIndependentlyFromContentVariants() {
        for (String name : new String[]{"第3期上期", "第三期（上期）", "第3期上半期", "第3期正片上", "第3期加更版上期", "第3期上期加更版"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, Integer.valueOf(3), label.number());
            assertEquals(name, "upper", label.part());
            assertFalse(name, label.isApiSafe());
        }
        assertEquals("middle", EpisodeLabelParser.parse("第3期中期").part());
        assertEquals("lower", EpisodeLabelParser.parse("第3期下半期").part());
        assertEquals("extra", EpisodeLabelParser.parse("第3期上期加更版").variant());
        for (String name : new String[]{"第3期上下期", "第3期上中下期", "第3期上期 下期", "第3期上期+下期", "第3期上/下", "第3-4期", "第3期 第4期", "第3期第4期", "3期4期", "第3期合集"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertTrue(name, label.isAmbiguous());
            assertNull(name, label.number());
        }
    }

    @Test
    public void shouldKeepIssuePartsBeforeTrailingPreviewMarkers() {
        for (String name : new String[]{"第3期下预告", "第3期下预告片", "第3期下 预告", "第3期下[预告]", "第3期[下][预告]",
                "第三期（下期）（预告）", "第3期下期 先导", "第3期下期 trailer"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, Integer.valueOf(3), label.number());
            assertEquals(name, "lower", label.part());
            assertTrue(name, label.isIssue());
            assertTrue(name, label.isVariety());
            assertTrue(name, label.isPreview());
            assertFalse(name, label.isAmbiguous());
            assertFalse(name, label.isApiSafe());
        }
        assertTrue(EpisodeLabelParser.parse("第3期上下期预告").isAmbiguous());
        assertNull(EpisodeLabelParser.parse("第3期上下期预告").number());
    }

    @Test
    public void shouldPreserveDateAndDramaCoordinatesBeforeSplitPreviews() {
        EpisodeLabel dated = EpisodeLabelParser.parse("20260301 第3期上预告");
        assertEquals("2026-03-01", dated.airDate());
        assertEquals(Integer.valueOf(3), dated.number());
        assertEquals("upper", dated.part());
        assertTrue(dated.isIssue());
        assertTrue(dated.isPreview());
        assertFalse(dated.isApiSafe());
        EpisodeLabel drama = EpisodeLabelParser.parse("第3集下 预告");
        assertEquals(Integer.valueOf(3), drama.number());
        assertEquals("lower", drama.part());
        assertTrue(drama.isPreview());
        assertFalse(drama.isVariety());
    }

    @Test
    public void shouldKeepIssuePartsBeforeTrailingSpecialMarkers() {
        for (String name : new String[]{"第3期下特别篇", "第3期下 特别篇", "第3期[下][特辑]", "第3期下 SP"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, Integer.valueOf(3), label.number());
            assertEquals(name, "lower", label.part());
            assertTrue(name, label.isIssue());
            assertTrue(name, label.isSpecial());
            assertFalse(name, label.isAmbiguous());
            assertFalse(name, label.isApiSafe());
        }
        assertTrue(EpisodeLabelParser.parse("第3期上下期特别篇").isAmbiguous());
    }

    @Test
    public void shouldIgnorePreviewDirectoriesWhenReadingLeafIssueParts() {
        EpisodeLabel regular = EpisodeLabelParser.parse("/downloads/预告/2026-03-01/第3期下.mp4");
        assertEquals(Integer.valueOf(3), regular.number());
        assertEquals("lower", regular.part());
        assertEquals("", regular.airDate());
        assertTrue(regular.isIssue());
        assertFalse(regular.isPreview());
        assertFalse(regular.isAmbiguous());
        EpisodeLabel preview = EpisodeLabelParser.parse("/downloads/2026-03-01/第3期下[预告].mp4");
        assertEquals(Integer.valueOf(3), preview.number());
        assertEquals("lower", preview.part());
        assertEquals("", preview.airDate());
        assertTrue(preview.isPreview());
    }

    @Test
    public void shouldKeepReleaseFoldersAndSimilarPlotWordsOutOfVarietyMetadata() {
        EpisodeLabel filename = EpisodeLabelParser.parse("/downloads/2026-11-07/Show 第二季/第3期.mp4");
        assertEquals("", filename.airDate());
        assertEquals(Integer.valueOf(3), filename.number());
        assertEquals(Integer.valueOf(2), filename.season());
        assertEquals("", filename.edition());
        for (String name : new String[]{"1107", "11月7日", "2026", "Movie.2026.1080p.mkv", "[20261107deadbeef]", "[deadbeef20261107]", "幕后玩家", "幕後玩家", "加更的日子", "纯享受生活", "第一集训队", "第3期王某加更", "第3期王某心中加更", "加更上瘾", "加更会员的家"}) {
            EpisodeLabel label = EpisodeLabelParser.parse(name);
            assertEquals(name, "", label.airDate());
            assertEquals(name, "", label.variant());
            assertFalse(name, label.isVariety());
        }
        // A four-digit number without a year/month/day structure retains the legacy episode interpretation.
        assertEquals(Integer.valueOf(1107), EpisodeLabelParser.parse("1107").number());
        assertNull(EpisodeLabelParser.parse("第3期王某加更").number());
        EpisodeLabel leafDate = EpisodeLabelParser.parse("/downloads/2026-11-06/20261107 第3期.mp4");
        assertEquals("2026-11-07", leafDate.airDate());
        assertFalse(leafDate.isAmbiguous());
        assertEquals("fullhouse", EpisodeLabelParser.parse("Full House S01E03").edition());
        assertEquals("regularshow", EpisodeLabelParser.parse("Regular.Show.S01E03.mkv").edition());
        assertEquals("", EpisodeLabelParser.parse("Show.S01E03.[20261107deadbeef].1080p.mkv").airDate());
    }

    @Test
    public void shouldPreserveVarietyMetadataWhenAddingAnInheritedSeason() {
        EpisodeLabel parsed = EpisodeLabelParser.parse("20261107 第3期会员版上期");
        EpisodeLabel inherited = parsed.withSeason(2);
        assertEquals(Integer.valueOf(2), inherited.season());
        assertEquals(parsed.number(), inherited.number());
        assertEquals(parsed.airDate(), inherited.airDate());
        assertEquals(parsed.variant(), inherited.variant());
        assertEquals(parsed.part(), inherited.part());
        assertEquals(parsed.edition(), inherited.edition());
        assertEquals(parsed.isIssue(), inherited.isIssue());
        assertFalse(inherited.isApiSafe());
        EpisodeLabel legacy = new EpisodeLabel("第3集", null, 3, "", "", false, false, false);
        assertEquals("", legacy.airDate());
        assertEquals("", legacy.variant());
        assertFalse(legacy.isIssue());
        assertTrue(legacy.isApiSafe());
        assertTrue(legacy.withSeason(0).isSpecial());
        assertTrue(legacy.withSeason(0).isApiSafe());
    }

    @Test
    public void shouldReturnAnUnknownLabelForEmptyOrExcessivelyLongInputs() {
        assertNull(EpisodeLabelParser.parse(null).number());
        assertEquals("", EpisodeLabelParser.parse(null).edition());
        assertNull(EpisodeLabelParser.parse("").number());
        assertTrue(EpisodeLabelParser.parse("x".repeat(4097)).isAmbiguous());
    }
}
