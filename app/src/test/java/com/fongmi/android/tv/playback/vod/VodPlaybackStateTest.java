package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VodPlaybackStateTest {

    @Test
    public void flagFindPrefersStableEpisodeUrl() {
        Flag flag = Flag.create("line");
        Episode oldName = Episode.create("Old name", "episode://stable");
        Episode sameName = Episode.create("New name", "episode://other");
        flag.getEpisodes().add(oldName);
        flag.getEpisodes().add(sameName);

        assertSame(oldName, flag.find("New name", "episode://stable", true));
    }

    @Test
    public void historyCopyPreservesResumeIdentityAndProgress() {
        History history = new History();
        history.setKey("site@@vod");
        history.setCid(7);
        history.setVodName("Movie");
        history.setVodFlag("line");
        history.setVodRemarks("Episode 3");
        history.setEpisodeUrl("episode://stable");
        history.setCreateTime(1234L);
        history.setPosition(5678L);
        history.setDuration(9000L);
        history.setSpeed(1.5f);
        history.setScale(2);

        History copy = history.copy();

        assertEquals(history.getKey(), copy.getKey());
        assertEquals(history.getCid(), copy.getCid());
        assertEquals(history.getVodFlag(), copy.getVodFlag());
        assertEquals(history.getVodRemarks(), copy.getVodRemarks());
        assertEquals(history.getEpisodeUrl(), copy.getEpisodeUrl());
        assertEquals(history.getCreateTime(), copy.getCreateTime());
        assertEquals(history.getPosition(), copy.getPosition());
        assertEquals(history.getDuration(), copy.getDuration());
        assertEquals(history.getSpeed(), copy.getSpeed(), 0.0f);
        assertEquals(history.getScale(), copy.getScale());
    }

    @Test
    public void shouldNormalizeMissingEpisodePositionToFirstEpisode() {
        VodPlaybackState state = new VodPlaybackState();
        Flag flag = createFlagWithTwoEpisodes();
        flag.setPosition(-1);
        state.setFlags(Collections.singletonList(flag));

        Episode episode = state.getEpisode();

        assertSame(flag.getEpisodes().get(0), episode);
        assertEquals(0, flag.getPosition());
        assertTrue(flag.getEpisodes().get(0).isSelected());
        assertFalse(flag.getEpisodes().get(1).isSelected());
    }

    @Test
    public void shouldNormalizeOutOfBoundsEpisodePositionToFirstEpisode() {
        VodPlaybackState state = new VodPlaybackState();
        Flag flag = createFlagWithTwoEpisodes();
        flag.setPosition(5);
        state.setFlags(Collections.singletonList(flag));

        Episode episode = state.getEpisode();

        assertSame(flag.getEpisodes().get(0), episode);
        assertEquals(0, flag.getPosition());
        assertTrue(flag.getEpisodes().get(0).isSelected());
        assertFalse(flag.getEpisodes().get(1).isSelected());
    }

    @Test
    public void shouldKeepCurrentEpisodeWhenReversingEpisodes() {
        Flag flag = createFlagWithTwoEpisodes();
        Episode first = flag.getEpisodes().get(0);
        flag.toggle(true, first);

        VodPlaybackController.reverseEpisodesKeepingCurrent(flag);

        assertSame(first, flag.getEpisodes().get(1));
        assertEquals(1, flag.getPosition());
        assertTrue(first.isSelected());
        assertFalse(flag.getEpisodes().get(0).isSelected());
    }

    private Flag createFlagWithTwoEpisodes() {
        Flag flag = new Flag("main");
        flag.getEpisodes().add(Episode.create("01", "url1"));
        flag.getEpisodes().add(Episode.create("02", "url2"));
        return flag;
    }
}
