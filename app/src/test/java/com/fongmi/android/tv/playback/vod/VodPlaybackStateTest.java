package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VodPlaybackStateTest {

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
