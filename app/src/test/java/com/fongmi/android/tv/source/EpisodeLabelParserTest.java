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
    public void shouldReturnAnUnknownLabelForEmptyOrExcessivelyLongInputs() {
        assertNull(EpisodeLabelParser.parse(null).number());
        assertEquals("", EpisodeLabelParser.parse(null).edition());
        assertNull(EpisodeLabelParser.parse("").number());
        assertTrue(EpisodeLabelParser.parse("x".repeat(4097)).isAmbiguous());
    }
}
