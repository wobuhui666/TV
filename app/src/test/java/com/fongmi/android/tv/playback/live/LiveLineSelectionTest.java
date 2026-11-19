package com.fongmi.android.tv.playback.live;

import com.fongmi.android.tv.bean.Channel;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiveLineSelectionTest {

    @Test
    public void shouldShowNamedLinesAndNumberUnlabelledLinesWithoutTheirUrls() {
        Channel channel = channel("https://example.test/one$主线路", "https://example.test/two", "https://example.test/three$ ");
        LiveLineSelection selection = new LiveLineSelection(channel, 4);

        assertEquals(Arrays.asList("主线路", "线路 2", "线路 3"), selection.labels(number -> "线路 " + number));
    }

    @Test
    public void shouldKeepOnlyTheLineNameAndHideUrlMetadata() {
        Channel channel = channel("https://example.test/one$备用$metadata", "https://example.test/two$https://private.test/token");

        assertEquals(Arrays.asList("备用", "Line 2"), new LiveLineSelection(channel, 0).labels(number -> "Line " + number));
    }

    @Test
    public void shouldNumberNullAndBlankUrlsWithoutChangingTheirIndices() {
        Channel channel = channel("one$Main", null, "", "   ");
        LiveLineSelection selection = new LiveLineSelection(channel, 0);

        assertEquals(Arrays.asList("Main", "Line 2", "Line 3", "Line 4"), selection.labels(number -> "Line " + number));
        assertTrue(selection.contains(0));
        assertFalse(selection.contains(1));
        assertFalse(selection.contains(2));
        assertFalse(selection.contains(3));
    }

    @Test
    public void shouldRejectReorderedOrReplacedLineUrls() {
        Channel channel = channel("one", "two");
        LiveLineSelection selection = new LiveLineSelection(channel, 4);
        Collections.reverse(channel.getUrls());
        assertFalse(selection.matches(channel, 4));
        channel.setUrls(Arrays.asList("one", "replacement"));
        assertFalse(selection.matches(channel, 4));
    }

    @Test
    public void shouldRequireTheSameChannelObjectAndSelectionGeneration() {
        Channel channel = channel("one", "two");
        LiveLineSelection selection = new LiveLineSelection(channel, 4);

        assertTrue(selection.matches(channel, 4));
        assertFalse(selection.matches(channel("one", "two"), 4));
        assertFalse(selection.matches(channel, 5));
        assertFalse(selection.matches(null, 4));
    }

    @Test
    public void shouldRememberTheHighlightedLineAndRejectInvalidIndices() {
        Channel channel = channel("one", "two", "three");
        channel.setIndex(2);
        LiveLineSelection selection = new LiveLineSelection(channel, 0);

        channel.setIndex(1);

        assertEquals(2, selection.getSelectedIndex());
        assertTrue(selection.contains(0));
        assertTrue(selection.contains(2));
        assertFalse(selection.contains(-1));
        assertFalse(selection.contains(3));
    }

    private static Channel channel(String... urls) {
        Channel channel = Channel.create("channel");
        channel.setUrls(new ArrayList<>(Arrays.asList(urls)));
        return channel;
    }
}
