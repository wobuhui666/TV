package com.fongmi.android.tv.playback.live;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Result;

import org.junit.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LivePlaybackLineSelectionTest {

    @Test
    public void shouldSelectTheRequestedLineAndRefreshOnce() {
        Fixture fixture = new Fixture("one", "two", "three");
        LiveLineSelection selection = fixture.controller.getLineSelection();

        assertTrue(fixture.controller.selectLine(selection, 2));

        assertEquals(2, fixture.channel.getIndex());
        assertEquals(1, fixture.controller.refreshes);
        assertSame(fixture.channel, fixture.host.line);
        assertFalse(fixture.host.showLineInfo);
    }

    @Test
    public void shouldKeepPlayingWhenTheCurrentOrInvalidLineIsChosen() {
        Fixture fixture = new Fixture("one", "two");
        LiveLineSelection selection = fixture.controller.getLineSelection();

        assertFalse(fixture.controller.selectLine(selection, 0));
        assertFalse(fixture.controller.selectLine(selection, -1));
        assertFalse(fixture.controller.selectLine(selection, 2));
        assertFalse(fixture.controller.selectLine(null, 1));

        assertEquals(0, fixture.channel.getIndex());
        assertEquals(0, fixture.controller.refreshes);
        assertNull(fixture.host.line);
    }

    @Test
    public void shouldKeepPlayingWhenTheSelectedLineHasNoUrl() {
        Fixture fixture = new Fixture("one", null, "", "$name-only");
        LiveLineSelection selection = fixture.controller.getLineSelection();

        assertFalse(fixture.controller.selectLine(selection, 1));
        assertFalse(fixture.controller.selectLine(selection, 2));
        assertFalse(fixture.controller.selectLine(selection, 3));

        assertEquals(0, fixture.channel.getIndex());
        assertEquals(0, fixture.controller.refreshes);
        assertNull(fixture.host.line);
    }

    @Test
    public void shouldRejectAnOpenListAfterChangingChannelAndReturning() {
        Fixture fixture = new Fixture("one", "two");
        LiveLineSelection selection = fixture.controller.getLineSelection();
        fixture.controller.selectChannel(channel("other-one", "other-two"));
        fixture.controller.selectChannel(fixture.channel);
        fixture.controller.refreshes = 0;

        assertFalse(fixture.controller.selectLine(selection, 1));
        assertEquals(0, fixture.channel.getIndex());
        assertEquals(0, fixture.controller.refreshes);
    }

    @Test
    public void shouldRejectAnOpenListAfterResetOrUrlReplacement() {
        Fixture fixture = new Fixture("one", "two");
        LiveLineSelection selection = fixture.controller.getLineSelection();
        fixture.channel.getUrls().set(1, "changed");
        assertFalse(fixture.controller.selectLine(selection, 1));
        fixture.controller.reset();
        assertFalse(fixture.controller.selectLine(selection, 1));
        assertEquals(0, fixture.controller.refreshes);
    }

    @Test
    public void shouldOfferNoListForMissingOrSingleLineChannels() {
        Fixture fixture = new Fixture();
        assertNull(fixture.controller.getLineSelection());
        fixture.controller.selectChannel(channel("only"));
        assertNull(fixture.controller.getLineSelection());
        fixture.controller.reset();
        assertNull(fixture.controller.getLineSelection());
    }

    @Test
    public void shouldPreservePreviousAndNextLineWrapping() {
        Fixture fixture = new Fixture("one", "two", "three");
        fixture.controller.prevLine();
        assertEquals(2, fixture.channel.getIndex());
        assertTrue(fixture.host.showLineInfo);
        fixture.controller.nextLine(false);
        assertEquals(0, fixture.channel.getIndex());
        assertFalse(fixture.host.showLineInfo);
        assertEquals(2, fixture.controller.refreshes);
    }

    private static Channel channel(String... urls) {
        Channel channel = Channel.create("channel");
        channel.setUrls(new ArrayList<>(Arrays.asList(urls)));
        return channel;
    }

    private static final class Fixture {
        final LivePlaybackState state = new LivePlaybackState();
        final RecordingHost host = new RecordingHost();
        final RecordingController controller = new RecordingController(host, state);
        final Channel channel;

        Fixture(String... urls) {
            channel = channel(urls);
            controller.selectChannel(channel);
            controller.refreshes = 0;
        }
    }

    private static final class RecordingController extends LivePlaybackController {
        int refreshes;

        RecordingController(LivePlaybackHost host, LivePlaybackState state) {
            super(host, state);
        }

        @Override public void refresh() {
            refreshes++;
        }
    }

    private static final class RecordingHost implements LivePlaybackHost {
        Channel line;
        boolean showLineInfo;

        @Override public void renderLineSelection(Channel channel, boolean show) {
            line = channel;
            showLineInfo = show;
        }

        @Override public int getGroupCount() { return 0; }
        @Override public int getGroupPosition() { return 0; }
        @Override public Group getGroup(int position) { return null; }
        @Override public boolean isPlayerLive() { return true; }
        @Override public ZoneId getZoneId() { return ZoneId.of("UTC"); }
        @Override public void requestUrl(LivePlayRequest request) { }
        @Override public void requestCatchupUrl(LivePlayRequest request) { }
        @Override public void stopPlaybackForRefresh() { }
        @Override public void startPlayback(Result result, long position, Channel channel) { }
        @Override public void resetPlaybackForError(String msg) { }
        @Override public void renderGroupSelection(Group group) { }
        @Override public void renderGroupChannels(Group group) { }
        @Override public void renderChannelSelection(Channel channel) { }
        @Override public void renderEpgSelection(Channel channel, EpgData data) { }
        @Override public void showCatchupReady(Channel channel, EpgData data) { }
        @Override public void showProgress() { }
    }
}
