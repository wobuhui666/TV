package com.fongmi.android.tv.player.skip;

import com.fongmi.android.tv.bean.Episode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CommunitySkipEpisodeOrderTest {

    @Test
    public void shouldRecognizeRealContinuousLabelsRegardlessOfDisplayOrder() {
        List<Episode> episodes = catalogue(171);
        assertEquals(171, CommunitySkipRuntime.continuousEpisodeCount("黑色五叶草", episodes));
        Collections.reverse(episodes);
        assertEquals(171, CommunitySkipRuntime.continuousEpisodeCount("黑色五叶草", episodes));
    }

    @Test
    public void shouldRejectMissingDuplicateAndNonStartingEpisodeNumbers() {
        List<Episode> episodes = catalogue(3);
        episodes.remove(1);
        assertEquals(0, CommunitySkipRuntime.continuousEpisodeCount("Show", episodes));
        episodes = catalogue(3);
        episodes.set(2, Episode.create("第2集", "another-second"));
        assertEquals(0, CommunitySkipRuntime.continuousEpisodeCount("Show", episodes));
        assertEquals(0, CommunitySkipRuntime.continuousEpisodeCount("Show", List.of(Episode.create("第2集", "second"))));
    }

    @Test
    public void shouldRejectGeneratedLabelsExplicitSeasonsAndNonEpisodeContent() {
        for (String label : new String[]{"S02E03", "第3集上", "第3集 预告", "特别篇 第3集", "第3期"}) {
            List<Episode> episodes = catalogue(3);
            episodes.set(2, Episode.create(label, "third"));
            assertEquals(label, 0, CommunitySkipRuntime.continuousEpisodeCount("Show", episodes));
        }
        List<Episode> episodes = catalogue(3);
        episodes.get(2).setGeneratedLabel(true);
        assertEquals(0, CommunitySkipRuntime.continuousEpisodeCount("Show", episodes));
        assertEquals(0, CommunitySkipRuntime.continuousEpisodeCount("Show 第二季", catalogue(3)));
    }

    private static List<Episode> catalogue(int count) {
        List<Episode> episodes = new ArrayList<>();
        for (int number = 1; number <= count; number++) episodes.add(Episode.create("第" + number + "集", "episode-" + number));
        return episodes;
    }
}
