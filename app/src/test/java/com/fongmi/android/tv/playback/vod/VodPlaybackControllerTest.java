package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class VodPlaybackControllerTest {

    @Test
    public void shouldCaptureCurrentPositionBeforeStoppingAndRequestingRefresh() {
        Fixture fixture = new Fixture();
        fixture.playerPosition = 42_345;

        fixture.controller.refresh();

        assertEquals(List.of("position", "stop", "request"), fixture.calls);
        assertEquals(42_345, fixture.positionAtStop);
        assertEquals(42_345, fixture.positionAtRequest);
        assertEquals(42_345, fixture.controller.startPositionMs());
    }

    @Test
    public void shouldCaptureTheNewPositionOnEveryRefresh() {
        Fixture fixture = new Fixture();
        for (long position : new long[]{42_345, 57_891, 83_210}) {
            fixture.playerPosition = position;

            fixture.controller.refresh();

            assertEquals(position, fixture.positionAtStop);
            assertEquals(position, fixture.positionAtRequest);
        }
    }

    @Test
    public void shouldPreserveZeroInsteadOfResumingAnOlderHistoryPosition() {
        Fixture fixture = new Fixture();
        fixture.playerPosition = 0;

        fixture.controller.refresh();

        assertEquals(0, fixture.positionAtStop);
        assertEquals(0, fixture.controller.startPositionMs());
    }

    @Test
    public void shouldKeepHistoryWhenThePlayerPositionIsInvalid() {
        for (long position : new long[]{C.TIME_UNSET, -1}) {
            Fixture fixture = new Fixture();
            fixture.playerPosition = position;

            fixture.controller.refresh();

            assertEquals(12_000, fixture.positionAtStop);
            assertEquals(12_000, fixture.positionAtRequest);
        }
    }

    @Test
    public void shouldNotCopyTimeshiftPositionIntoVodHistory() {
        Fixture fixture = new Fixture();
        fixture.timeshift = true;
        fixture.playerPosition = 42_345;

        fixture.controller.refresh();

        assertEquals(List.of("stop", "request"), fixture.calls);
        assertEquals(12_000, fixture.positionAtStop);
    }

    @Test
    public void shouldNotCopyAnEmptyPlayerPositionIntoHistory() {
        Fixture fixture = new Fixture();
        fixture.playerEmpty = true;

        fixture.controller.refresh();

        assertEquals(List.of("stop", "request"), fixture.calls);
        assertEquals(12_000, fixture.positionAtStop);
    }

    @Test
    public void shouldKeepReplayAtTheBeginningWhenThePlayerIsEmpty() {
        Fixture fixture = new Fixture();
        fixture.playerEmpty = true;
        fixture.playerPosition = 42_345;

        fixture.controller.replay();

        assertEquals(List.of("stop", "request"), fixture.calls);
        assertEquals(C.TIME_UNSET, fixture.positionAtStop);
        assertEquals(C.TIME_UNSET, fixture.controller.startPositionMs());
    }

    @Test
    public void shouldNotCopyPreviousEpisodePositionWhenSelectingAnotherEpisode() {
        Fixture fixture = new Fixture();
        fixture.playerPosition = 42_345;

        fixture.controller.selectEpisode(fixture.secondEpisode);

        assertEquals(List.of("stop", "request"), fixture.calls);
        assertEquals(C.TIME_UNSET, fixture.positionAtStop);
        assertEquals(C.TIME_UNSET, fixture.positionAtRequest);
        assertEquals("episode-2", fixture.request.getId());
    }

    private static final class Fixture {
        final History history = new History();
        final List<String> calls = new ArrayList<>();
        final Episode secondEpisode = Episode.create("Episode 2", "episode-2");
        final VodPlaybackController controller;
        long playerPosition;
        long positionAtStop;
        long positionAtRequest;
        boolean timeshift;
        boolean playerEmpty;
        VodPlayRequest request;

        Fixture() {
            history.setVodRemarks("Episode 1");
            history.setEpisodeUrl("episode-1");
            history.setOpening(C.TIME_UNSET);
            history.setPosition(12_000);
            // No duration has arrived, so refresh must retain progress without a database write.
            history.setDuration(C.TIME_UNSET);
            Flag flag = Flag.create("line");
            flag.getEpisodes().add(Episode.create("Episode 1", "episode-1"));
            flag.getEpisodes().add(secondEpisode);
            flag.toggle(true, flag.getEpisodes().get(0));
            VodPlaybackState state = new VodPlaybackState();
            state.setHistory(history);
            state.setFlags(Collections.singletonList(flag));
            VodPlaybackHost host = (VodPlaybackHost) Proxy.newProxyInstance(
                    VodPlaybackHost.class.getClassLoader(), new Class[]{VodPlaybackHost.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isPlayerEmpty": return playerEmpty;
                            case "isTimeshiftPlayback": return timeshift;
                            case "getPlayerPosition":
                                calls.add("position");
                                return playerPosition;
                            case "stopPlaybackForRefresh":
                                calls.add("stop");
                                positionAtStop = history.getPosition();
                                playerPosition = 0;
                                return null;
                            case "requestPlayer":
                                calls.add("request");
                                positionAtRequest = history.getPosition();
                                request = (VodPlayRequest) args[0];
                                return null;
                        }
                        if (method.getReturnType() == String.class) return "";
                        if (method.getReturnType() == boolean.class) return false;
                        return null;
                    });
            controller = new VodPlaybackController(host, state);
        }
    }
}
