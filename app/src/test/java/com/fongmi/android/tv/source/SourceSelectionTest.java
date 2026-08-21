package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class SourceSelectionTest {

    @Test
    public void groupsSameMovieFromMultipleSitesAndKeepsCandidates() {
        Vod first = vod("庆余年2", "site-a", "a");
        Vod second = vod("庆餘年 第二季", "site-b", "b");

        List<Vod> result = new SourceAggregator().aggregate(List.of(first, second));

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getSourceCount());
        assertEquals("site-b", result.get(0).getSourceCandidates().get(0).getSiteKey());
    }

    @Test
    public void mergePreservesVisibleRepresentativesAndAppendsNewGroups() {
        Vod first = vod("庆余年2", "site-a", "a");
        Vod same = vod("庆餘年 第二季", "site-b", "b");
        Vod next = vod("琅琊榜", "site-c", "c");
        List<Vod> result = new ArrayList<>(List.of(first));

        new SourceAggregator().mergeInto(result, List.of(same, next));

        assertSame(first, result.get(0));
        assertEquals(2, result.get(0).getSourceCount());
        assertSame(next, result.get(1));
    }

    @Test
    public void selectsOnlyLineWithTargetEpisode() {
        Vod source = vod("庆余年2", "site-a", "a");
        Flag empty = new Flag("broken");
        empty.getEpisodes().add(Episode.create("01", "broken"));
        Flag playable = new Flag("playable");
        playable.getEpisodes().add(Episode.create("01", "ok"));
        playable.getEpisodes().add(Episode.create("02", "ok2"));
        source.setFlags(List.of(empty, playable));

        SmartSourceSelector.FlagSelection selected = SmartSourceSelector.selectFlag(source, EpisodeTarget.of(2), "", new SourceReliabilityStore(new HashMap<>()));

        assertSame(playable, selected.flag());
        assertEquals("ok2", selected.episode().getUrl());
    }

    @Test
    public void reliabilityOnlyChangesOrderWithinMatchingSources() {
        Vod first = vod("同名", "site-a", "a");
        Vod second = vod("同名", "site-b", "b");
        addEpisode(first, "01", "a1");
        addEpisode(second, "01", "b1");
        SourceReliabilityStore store = new SourceReliabilityStore(new HashMap<>());
        store.recordFailure(first);

        assertSame(second, SmartSourceSelector.selectSource(List.of(first, second), EpisodeTarget.of(1), "other", store));
    }

    @Test
    public void allowsUndetailedCandidateForDeferredEpisodeValidation() {
        Vod missing = vod("同名", "site-a", "a");
        Vod pending = vod("同名", "site-b", "b");
        addEpisode(missing, "01", "a1");

        assertSame(pending, SmartSourceSelector.selectSource(List.of(missing, pending), EpisodeTarget.of(2), "other", new SourceReliabilityStore(new HashMap<>())));
    }

    @Test
    public void modesKeepLegacyAndGroupedBehaviorSeparate() {
        assertEquals(false, SourceSelectionPolicies.forMode(SourceSelectionMode.LEGACY).groupResults());
        assertEquals(true, SourceSelectionPolicies.forMode(SourceSelectionMode.GROUP_ONLY).groupResults());
        assertEquals(false, SourceSelectionPolicies.forMode(SourceSelectionMode.GROUP_ONLY).smartFallback());
        assertEquals(true, SourceSelectionPolicies.forMode(SourceSelectionMode.SMART).smartFallback());
    }

    private static Vod vod(String name, String site, String id) {
        Vod vod = new Vod();
        vod.setName(name);
        vod.setSite(Site.get(site, site));
        vod.setId(id);
        return vod;
    }

    private static void addEpisode(Vod vod, String name, String url) {
        Flag flag = new Flag("main");
        flag.getEpisodes().add(Episode.create(name, url));
        vod.setFlags(List.of(flag));
    }
}
