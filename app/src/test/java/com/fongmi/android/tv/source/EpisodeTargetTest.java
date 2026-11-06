package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class EpisodeTargetTest {

    @Test
    public void shouldFindSameChineseNumberedEpisodeAcrossSources() {
        EpisodeTarget target = EpisodeTarget.of(episode("第十二集"));
        Episode same = episode("十二");
        Flag flag = new Flag("other-source");
        flag.getEpisodes().add(episode("十三"));
        flag.getEpisodes().add(same);

        assertEquals(Integer.valueOf(12), target.getNumber());
        assertSame(same, SmartSourceSelector.findEpisode(flag, target));
    }

    @Test
    public void shouldKeepNumericMatchingAcrossDifferentEpisodeFormats() {
        EpisodeTarget target = EpisodeTarget.of(episode("第12集"));

        assertTrue(target.matches(episode("Episode 12")));
    }

    @Test
    public void shouldMatchChineseSplitEpisodesWithoutConfusingParts() {
        EpisodeTarget target = EpisodeTarget.of(episode("第十二集上"));
        Episode same = episode("十二集上");
        Flag flag = new Flag("other-source");
        flag.getEpisodes().add(episode("十二集下"));
        flag.getEpisodes().add(same);

        assertSame(same, SmartSourceSelector.findEpisode(flag, target));
        assertFalse(target.matches(episode("十二集下")));
        assertFalse(target.matches(episode("十二")));
    }

    @Test
    public void shouldKeepSeasonAndEpisodeSeparate() {
        EpisodeTarget target = EpisodeTarget.of(episode("S02E03"));

        assertEquals(Integer.valueOf(3), target.getNumber());
        assertTrue(target.matches(episode("第二季第三集")));
        assertFalse(target.matches(episode("S01E03")));
        assertFalse(target.matches(episode("S02E02")));
    }

    @Test
    public void shouldNotCollapseNumericUpperAndLowerParts() {
        EpisodeTarget target = EpisodeTarget.of(episode("第12集上"));

        assertTrue(target.matches(episode("12集上")));
        assertFalse(target.matches(episode("第12集下")));
        assertFalse(target.matches(episode("第12集")));
        assertNull(target.offset(1));
    }

    @Test
    public void shouldNotMatchPreviewOrSpecialToRegularEpisode() {
        EpisodeTarget target = EpisodeTarget.of(episode("第03集"));

        assertFalse(target.matches(episode("03预告")));
        assertFalse(target.matches(episode("特别篇 第3集")));
        assertFalse(target.matches(episode("S00E03")));
    }

    @Test
    public void shouldRejectQualityAndCollectionNumbers() {
        assertNull(EpisodeTarget.of(episode("1080p")).getNumber());
        assertNull(EpisodeTarget.of(episode("第01-02集")).getNumber());
        assertNull(EpisodeTarget.of(episode("2026-10-05")).getNumber());
    }

    @Test
    public void shouldPreferSelectedReleaseWhenVersionsShareAPlaylist() {
        Episode selected = Episode.create("[494.7MB][ANi] 暗部共生少女 - 04 [1080p].mp4", "selected");
        Episode other = Episode.create("[1.4GB]Toaru.Anbu.no.Item.S01E04.1080p.mkv", "other");
        Episode sameRelease = Episode.create("[510MB][ANi] 暗部共生少女 - 04 [1080p].mp4", "same-release");
        Flag flag = new Flag("mixed");
        flag.getEpisodes().add(other);
        flag.getEpisodes().add(sameRelease);

        assertSame(sameRelease, SmartSourceSelector.findEpisode(flag, EpisodeTarget.of(selected)));
    }

    @Test
    public void shouldRequireChoiceWhenAnUnspecifiedReleaseHasSeveralCandidates() {
        Flag flag = new Flag("mixed");
        flag.getEpisodes().add(Episode.create("[ANi] Show - 03 [1080p].mp4", "ani"));
        flag.getEpisodes().add(Episode.create("[Other] Show - 03 [720p].mp4", "other"));

        assertNull(SmartSourceSelector.findEpisode(flag, EpisodeTarget.of(3)));
    }

    @Test
    public void shouldUseUrlToResumeIdenticalDisplayLabels() {
        Episode first = Episode.create("第03集", "first-url");
        Episode selected = Episode.create("第03集", "selected-url");
        Flag flag = new Flag("mixed");
        flag.getEpisodes().add(first);
        flag.getEpisodes().add(selected);

        assertSame(selected, SmartSourceSelector.findEpisode(flag, EpisodeTarget.of(selected)));
    }

    @Test
    public void shouldNotUseGeneratedPositionAsEpisodeCoordinate() {
        Episode generated = Episode.create("03", "generated-url");
        generated.setGeneratedLabel(true);
        EpisodeTarget target = EpisodeTarget.of(generated);

        assertNull(target.getNumber());
        assertEquals(-1, generated.getNumber());
        assertFalse(target.matches(episode("第03集")));
        assertFalse(EpisodeTarget.of(3).matches(generated));
        assertNull(target.offset(1));
        assertTrue(target.matches(generated));
    }

    @Test
    public void shouldKeepSeasonAndReleaseWhenSearchingNextEpisode() {
        EpisodeTarget next = EpisodeTarget.of(Episode.create("[ANi] Show.S02E03.1080p.mkv", "current")).offset(1);
        Flag flag = new Flag("mixed");
        Episode right = Episode.create("[ANi] Show.S02E04.1080p.mkv", "right");
        flag.getEpisodes().add(Episode.create("[Other] Show.S02E04.1080p.mkv", "other"));
        flag.getEpisodes().add(Episode.create("[ANi] Show.S01E04.1080p.mkv", "wrong-season"));
        flag.getEpisodes().add(right);

        assertSame(right, SmartSourceSelector.findEpisode(flag, next));
    }

    @Test
    public void shouldInvalidateParsedNumberWhenEpisodeIsRenamed() {
        Episode item = episode("S02E03");
        assertEquals(3, item.getNumber());

        item.setName("第十二集下");

        assertEquals(12, item.getNumber());
        assertEquals("lower", item.getLabel().part());
    }

    @Test
    public void shouldUseExplicitSourceSeasonForBareEpisodeLabels() {
        EpisodeTarget target = EpisodeTarget.of(episode("第3集"), 2);

        assertTrue(target.matches(episode("第3集"), 2));
        assertFalse(target.matches(episode("第3集"), 1));
        assertFalse(target.matches(episode("第3集")));
        assertTrue(target.offset(1).matches(episode("第4集"), 2));
        assertFalse(target.offset(1).matches(episode("第4集"), 1));
    }

    @Test
    public void shouldKeepExplicitFilenameSeasonWhenSourceSeasonIsMissingOrWrong() {
        EpisodeTarget target = EpisodeTarget.of(episode("S02E03"), 1);

        assertTrue(target.matches(episode("S02E03"), 1));
        assertFalse(target.matches(episode("S01E03"), 2));
    }

    private Episode episode(String name) {
        Episode episode = new Episode();
        episode.setName(name);
        return episode;
    }
}
