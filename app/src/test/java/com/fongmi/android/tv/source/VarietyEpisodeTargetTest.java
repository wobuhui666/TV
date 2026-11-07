package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VarietyEpisodeTargetTest {

    @Test
    public void shouldMatchValidatedDateFormatsWithoutInventingIssueNumbers() {
        EpisodeTarget target = target("20260301");

        assertNull(target.getNumber());
        assertTrue(target.matches(episode("2026-03-01")));
        assertTrue(target.matches(episode("2026.3.1")));
        assertTrue(target.matches(episode("2026年3月1日")));
        assertFalse(target.matches(episode("2026-03-02")));
        assertFalse(target.matches(episode("第1期")));
        assertTrue(target.matches(episode("20260301第1期")));
    }

    @Test
    public void shouldRequireKnownDateAndIssueEvidenceToAgree() {
        EpisodeTarget target = target("2026-03-01 第3期");

        assertTrue(target.matches(episode("20260301 第三期")));
        assertTrue(target.matches(episode("20260301")));
        assertFalse(target.matches(episode("2026-03-08 第3期")));
        assertFalse(target.matches(episode("2026-03-01 第4期")));
        assertFalse(target.matches(episode("第3期")));
        assertFalse(target("第3期").matches(episode("2026-03-01 第3期")));
    }

    @Test
    public void shouldAllowOneUnknownIssueOnlyWhenDatesMatch() {
        assertTrue(target("20260301").matches(episode("第3期 2026-03-01")));
        assertFalse(target("20260301").matches(episode("第3期")));
        assertFalse(target("第3期").matches(episode("20260301")));
    }

    @Test
    public void shouldMatchUndatedIssueNumbersToOrdinaryNumericLabels() {
        assertTrue(target("第十二期").matches(episode("12")));
        assertTrue(target("12").matches(episode("第十二期")));
        assertFalse(target("第12期").matches(episode("第13期")));
    }

    @Test
    public void shouldKeepCanonicalVariantsSeparateFromRegularEpisodes() {
        EpisodeTarget regular = target("20260301 正片");
        EpisodeTarget member = target("20260301 会员加长版");

        assertTrue(regular.matches(episode("2026-03-01 完整版")));
        assertTrue(regular.matches(episode("2026-03-01")));
        assertTrue(member.matches(episode("2026年3月1日 會員版")));
        for (String variant : new String[]{"加更", "纯享", "会员版", "未播", "花絮"}) {
            assertFalse(variant, regular.matches(episode("2026-03-01 " + variant)));
        }
        assertFalse(member.matches(episode("20260301 加更")));
    }

    @Test
    public void shouldRequireTheSamePartEvenWhenDatesAndIssuesAgree() {
        EpisodeTarget target = target("20260301 第3期 上");

        assertTrue(target.matches(episode("[2026-03-01][第三期][上期]")));
        assertFalse(target.matches(episode("20260301 第3期 下")));
        assertFalse(target.matches(episode("20260301 第3期")));
        assertNull(target.offset(1));
    }

    @Test
    public void shouldKeepSourceAndExplicitSeasonsForVarietyLabels() {
        EpisodeTarget target = EpisodeTarget.of(episode("20260301 第3期 会员版"), 2);

        assertTrue(target.matches(episode("2026-03-01 第3期 会员版"), 2));
        assertFalse(target.matches(episode("2026-03-01 第3期 会员版"), 1));
        assertFalse(target.matches(episode("第一季 20260301 第3期 会员版"), 2));
        assertFalse(target.matches(episode("2026-03-01 第3期 会员版")));
    }

    @Test
    public void shouldUseExactUrlsWithoutAcceptingContradictoryKnownMetadata() {
        Episode selected = Episode.create("20260301 第3期", "same-url");
        EpisodeTarget target = EpisodeTarget.of(selected);

        assertTrue(target.matches(Episode.create("第3期", "same-url")));
        assertFalse(target.matches(Episode.create("20260308 第3期", "same-url")));
        assertFalse(target.matches(Episode.create("20260301 第4期", "same-url")));
        assertFalse(target.matches(Episode.create("20260301 第3期 加更", "same-url")));
    }

    @Test
    public void shouldRequireChoiceForConflictingUrlsWithTheSameDateAndVariant() {
        Flag flag = new Flag("other");
        flag.getEpisodes().add(Episode.create("2026-03-01 第3期", "first"));
        flag.getEpisodes().add(Episode.create("2026.03.01 第三期", "second"));

        assertNull(SmartSourceSelector.findEpisode(flag, target("20260301")));
        assertSame(flag.getEpisodes().get(1), SmartSourceSelector.findEpisode(flag,
                EpisodeTarget.of(flag.getEpisodes().get(1))));
    }

    @Test
    public void shouldNeverUseSubstringMatchingForDateOrVariantIdentity() {
        assertFalse(target("20260301").isUnnumbered());
        assertFalse(target("加更").isUnnumbered());
        assertEquals(0, episode("20260301 第3期 加更").getScore("20260301", -1));
        assertEquals(0, episode("节目 20260301").getScore("节目", -1));
        assertEquals(0, episode("20260301 第3期").getScore("第3期", 3));
        assertEquals(80, episode("2026-03-01 第3期").getScore("20260301", -1));
    }

    @Test
    public void shouldOffsetOnlyUndatedUnsplitCoordinatesAndPreserveVariants() {
        assertNull(target("20260301").offset(1));
        assertNull(target("20260301 第3期").offset(1));
        assertNull(target("第3期上").offset(1));
        assertTrue(target("第3期").offset(1).matches(episode("第4期")));
        EpisodeTarget next = target("第二季 第3期 加更").offset(1);
        assertTrue(next.matches(episode("第二季 第4期 加更")));
        assertFalse(next.matches(episode("第二季 第4期")));
        assertFalse(next.matches(episode("第一季 第4期 加更")));
        assertNull(EpisodeTarget.of(Integer.MAX_VALUE).offset(1));
    }

    @Test
    public void shouldRejectInvalidOrMultipleDatesDuringAutomaticMatching() {
        assertFalse(target("2026-02-30 第3期").matches(episode("第3期")));
        assertFalse(target("20260301 20260308 第3期").matches(episode("第3期")));
    }

    private EpisodeTarget target(String name) {
        return EpisodeTarget.of(episode(name));
    }

    private Episode episode(String name) {
        return Episode.create(name, "");
    }
}
