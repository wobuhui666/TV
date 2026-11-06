package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Uses the production controller and real Android settings without requesting a provider or player. */
@RunWith(AndroidJUnit4.class)
public class ExplicitEpisodeSelectionAndroidTest {

    private static final String HISTORY_KEY = "explicit-fixture@@@show@@@0";
    private static final Map<String, Object> SETTINGS = Map.of(
            "incognito", true, "community_skip_enabled", false,
            "preload_next", false, "source_selection_mode", "SMART");

    private final Map<String, Object> saved = new HashMap<>();
    private final List<VodPlayRequest> requests = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private VodPlaybackController controller;
    private VodPlaybackState state;
    private History history;
    private History incoming;
    private String playId = "";
    private String mark = "";
    private int clears;
    private int searches;
    private int finishes;
    private boolean finishing;

    @Before
    public void setup() {
        String applicationId = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        assertTrue("Use a disposable validation application", applicationId.equals("com.fongmi.android.tv.preview")
                || applicationId.equals("com.fongmi.android.tv.sourceprobe"));
        Map<String, ?> preferences = Prefers.getPrefers().getAll();
        for (String key : SETTINGS.keySet()) if (preferences.containsKey(key)) saved.put(key, preferences.get(key));
        SETTINGS.forEach(Prefers::put);
        history = history("line", "第1集", "first");
        state = new VodPlaybackState();
        state.setHistory(history);
        VodPlaybackHost host = (VodPlaybackHost) Proxy.newProxyInstance(VodPlaybackHost.class.getClassLoader(),
                new Class<?>[]{VodPlaybackHost.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVodPlayId": return playId;
                        case "clearVodPlayId": playId = ""; clears++; return null;
                        case "getVodMark": return mark;
                        case "getHistoryKey": return HISTORY_KEY;
                        case "getVodKey": return "explicit-fixture";
                        case "getVodId": return "show";
                        case "getVodName": return "Show";
                        case "getVodPic": return "";
                        case "isHostFinishing": return finishing;
                        case "isSiteChangeable": return true;
                        case "takeCastHistory": {
                            History value = incoming;
                            incoming = null;
                            return value;
                        }
                        case "requestPlayer": requests.add((VodPlayRequest) args[0]); return null;
                        case "requestSearch": searches++; return null;
                        case "requestDetail": throw new AssertionError("The fixture must not request provider content");
                        case "resetPlaybackForError": errors.add((String) args[0]); return null;
                        case "finishVod": finishes++; return null;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == String.class) return "";
                    if (method.getReturnType() == List.class) return List.of();
                    return null;
                });
        controller = new VodPlaybackController(host, state);
    }

    @After
    public void cleanup() {
        if (controller != null) controller.reset();
        for (String key : SETTINGS.keySet()) {
            if (saved.containsKey(key)) Prefers.put(key, saved.get(key));
            else Prefers.getPrefers().edit().remove(key).apply();
        }
    }

    @Test
    public void shouldSelectTheExactIdBeforeHistoryMarkAndClearItOnlyOnce() {
        playId = "selected";
        mark = "第1集";
        history.setEpisodeUrl("selected");
        history.setPosition(90_000);
        history.setDuration(1_800_000);
        Episode selected = Episode.create("第4集", "selected");
        detail(flag("line", Episode.create("selected", "wrong-id")),
                flag("other", selected, Episode.create("第5集", "next")));

        assertSame(selected, state.getEpisode());
        assertEquals("selected", requests.get(0).getId());
        assertEquals(90_000, state.getHistory().getPosition());
        assertEquals(1_800_000, state.getHistory().getDuration());
        assertEquals("", playId);
        assertEquals(1, clears);
        controller.nextEpisode(false);
        assertEquals("next", requests.get(1).getId());
        assertEquals(1, clears);
    }

    @Test
    public void shouldClearProgressWhenAnExplicitIdChangesDespiteTheSameTitle() {
        playId = "new-id";
        history.setVodRemarks("第4集");
        history.setEpisodeUrl("old-id");
        history.setPosition(90_000);
        history.setDuration(1_800_000);

        detail(flag("line", Episode.create("第4集", "new-id")));

        assertEquals("new-id", requests.get(0).getId());
        assertEquals(C.TIME_UNSET, state.getHistory().getPosition());
        assertEquals(C.TIME_UNSET, state.getHistory().getDuration());
        assertEquals(90_000, history.getPosition());
        assertEquals("old-id", history.getEpisodeUrl());
    }

    @Test
    public void shouldNotPlayOrFallBackAfterAMissingExplicitSelectionUntilTheUserSelects() {
        playId = "missing-id";
        Flag line = flag("line", Episode.create("第1集", "first"), Episode.create("第2集", "second"));
        detail(line);
        controller.playbackEnded();
        controller.refresh();
        controller.replay();
        controller.playbackError("late error");
        controller.onPlayerResult(new Result());

        assertTrue(requests.isEmpty());
        assertEquals(0, searches);
        assertFalse(errors.isEmpty());
        assertEquals(0, clears);
        assertEquals("missing-id", playId);
        controller.selectEpisode(line.getEpisodes().get(1));
        assertEquals("second", requests.get(0).getId());
        assertEquals("", playId);
    }

    @Test
    public void shouldRejectAmbiguousNumberedSelectionsAcrossFlags() {
        playId = "4";
        detail(flag("line", Episode.create("EP04", "release-a")),
                flag("other", Episode.create("第4集", "release-b")));

        assertTrue(requests.isEmpty());
        assertEquals(0, searches);
        assertEquals(1, errors.size());
    }

    @Test
    public void shouldShowAnExplicitErrorForEmptyDetailsWithoutFinishingOrSearching() {
        playId = "missing";
        Result empty = new Result();
        empty.setMsg("provider error");
        controller.onDetailResult(empty);

        assertEquals(1, errors.size());
        assertEquals(0, searches);
        assertEquals(0, finishes);
        assertEquals(0, clears);
    }

    @Test
    public void shouldRestoreIncomingProgressBeforeCurrentHistoryAndConsumeItOnce() {
        incoming = history("line", "第4集", "fourth");
        incoming.setPosition(120_000);
        incoming.setDuration(1_800_000);
        History received = incoming;
        detail(flag("line", Episode.create("第1集", "first"), Episode.create("第4集", "fourth")));

        assertNotSame(received, state.getHistory());
        assertEquals("fourth", requests.get(0).getId());
        assertEquals(120_000, state.getHistory().getPosition());
        state.getHistory().setPosition(150_000);
        detail(flag("line", Episode.create("第1集", "first"), Episode.create("第4集", "fourth")));
        assertNotSame(received, state.getHistory());
        assertEquals(150_000, state.getHistory().getPosition());
        assertEquals(120_000, received.getPosition());
    }

    @Test
    public void shouldGuardAsyncResultsWhileAnotherRequestIsPendingOrTheHostIsFinishing() {
        Result current = new Result();
        state.setQuality(current);
        assertTrue(controller.isCurrentResult(current));
        assertFalse(controller.isCurrentResult(new Result()));
        state.setPendingRequest(VodPlayRequest.create("explicit-fixture", flag("line"), Episode.create("第2集", "second")));
        assertFalse(controller.isCurrentResult(current));
        state.clearPlayRequest();
        playId = "pending-selection";
        assertFalse(controller.isCurrentResult(current));
        playId = "";
        finishing = true;
        assertFalse(controller.isCurrentResult(current));
    }

    private History history(String flag, String label, String id) {
        History history = new History();
        history.setKey(HISTORY_KEY);
        history.setVodName("Show");
        history.setVodFlag(flag);
        history.setVodRemarks(label);
        history.setEpisodeUrl(id);
        return history;
    }

    private Flag flag(String name, Episode... episodes) {
        Flag flag = new Flag(name);
        flag.getEpisodes().addAll(List.of(episodes));
        return flag;
    }

    private void detail(Flag... flags) {
        Vod item = new Vod();
        item.setName("Show");
        item.setFlags(List.of(flags));
        Result result = new Result();
        result.setList(List.of(item));
        controller.onDetailResult(result);
    }
}
