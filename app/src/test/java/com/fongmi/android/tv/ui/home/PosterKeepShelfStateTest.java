package com.fongmi.android.tv.ui.home;

import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.PosterKeepShelfState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PosterKeepShelfStateTest {

    @Test
    public void currentConfigVodAndGlobalDiscoverShareNewestFirstOrder() {
        PosterKeepShelfState state = new PosterKeepShelfState();
        Keep current = keep("current", Keep.TYPE_VOD, 7, 20);
        Keep other = keep("other", Keep.TYPE_VOD, 8, 40);
        Keep discovery = keep("tmdb:movie:1", Keep.TYPE_DISCOVER, 8, 30);
        Keep live = keep("live", Keep.TYPE_LIVE, 7, 50);

        assertTrue(state.complete(state.begin(7, true), 7, true, List.of(current, other, discovery, live)));

        assertEquals(List.of(discovery, current), state.getItems());
        assertTrue(state.isVisible());
        assertFalse(state.hasMore());
    }

    @Test
    public void onlyEligibleOverflowEnablesViewAllAndKeepsNewestTwenty() {
        PosterKeepShelfState state = new PosterKeepShelfState();
        List<Keep> saved = new ArrayList<>();
        for (int i = 0; i < PosterKeepShelfState.MAX_POSTERS; i++) saved.add(keep("vod" + i, Keep.TYPE_VOD, 1, i));
        saved.add(keep("other-config", Keep.TYPE_VOD, 2, 100));
        state.complete(state.begin(1, true), 1, true, saved);
        assertEquals(PosterKeepShelfState.MAX_POSTERS, state.getItems().size());
        assertFalse(state.hasMore());

        Keep newest = keep("tmdb:movie:100", Keep.TYPE_DISCOVER, 9, 50);
        saved.add(newest);
        state.complete(state.begin(1, true), 1, true, saved);

        assertEquals(PosterKeepShelfState.MAX_POSTERS, state.getItems().size());
        assertSame(newest, state.getItems().get(0));
        assertFalse(state.getItems().contains(saved.get(0)));
        assertTrue(state.hasMore());
        assertEquals("Selecting a shelf must not mutate the saved collection", 22, saved.size());
    }

    @Test
    public void failedReadKeepsLoadedCardsUntilSuccessfulEmptyRead() {
        PosterKeepShelfState state = new PosterKeepShelfState();
        Keep saved = keep("saved", Keep.TYPE_VOD, 1, 1);
        state.complete(state.begin(1, true), 1, true, List.of(saved));
        assertTrue(state.fail(state.begin(1, true), 1, true));
        assertTrue(state.hasFailed());
        assertEquals(List.of(saved), state.getItems());
        assertTrue(state.isVisible());

        state.complete(state.begin(1, true), 1, true, List.of());

        assertFalse(state.hasFailed());
        assertTrue(state.getItems().isEmpty());
        assertFalse(state.isVisible());
    }

    @Test
    public void initialFailureRemainsActionableAndRetryCanRecover() {
        PosterKeepShelfState state = new PosterKeepShelfState();
        state.fail(state.begin(1, true), 1, true);
        assertTrue(state.isVisible());
        assertTrue(state.hasFailed());
        assertTrue(state.getItems().isEmpty());

        Keep saved = keep("tmdb:tv:1", Keep.TYPE_DISCOVER, 0, 1);
        state.complete(state.begin(1, true), 1, true, List.of(saved));

        assertEquals(List.of(saved), state.getItems());
        assertFalse(state.hasFailed());
        assertTrue(state.isVisible());
    }

    @Test
    public void switchingConfigClearsPreviousCardsBeforeReadAndRejectsLateResults() {
        PosterKeepShelfState state = new PosterKeepShelfState();
        Keep old = keep("old", Keep.TYPE_VOD, 1, 1);
        state.complete(state.begin(1, true), 1, true, List.of(old));
        PosterKeepShelfState.Request previous = state.begin(1, true);
        assertFalse(state.complete(previous, 2, true, List.of(old)));
        PosterKeepShelfState.Request current = state.begin(2, true);

        assertTrue(state.getItems().isEmpty());
        assertFalse(state.isVisible());
        assertFalse(state.complete(previous, 2, true, List.of(old)));
        state.fail(current, 2, true);
        assertTrue(state.getItems().isEmpty());
        assertTrue(state.hasFailed());
    }

    @Test
    public void refreshAfterRemovalCannotBeUndoneByAnOlderReadOrError() {
        PosterKeepShelfState state = new PosterKeepShelfState();
        Keep removed = keep("removed", Keep.TYPE_VOD, 1, 1);
        PosterKeepShelfState.Request beforeRemoval = state.begin(1, true);
        PosterKeepShelfState.Request afterRemoval = state.begin(1, true);
        state.complete(afterRemoval, 1, true, List.of());

        assertFalse(state.complete(beforeRemoval, 1, true, List.of(removed)));
        assertFalse(state.fail(beforeRemoval, 1, true));
        assertTrue(state.getItems().isEmpty());
        assertFalse(state.hasFailed());
        assertFalse(state.isVisible());
    }

    @Test
    public void disablingHidesImmediatelyAndDoesNotModifyStoredItems() {
        PosterKeepShelfState state = new PosterKeepShelfState();
        Keep saved = keep("saved", Keep.TYPE_VOD, 1, 1);
        List<Keep> databaseSnapshot = new ArrayList<>(List.of(saved));
        state.complete(state.begin(1, true), 1, true, databaseSnapshot);
        PosterKeepShelfState.Request pending = state.begin(1, true);
        assertFalse(state.complete(pending, 1, false, databaseSnapshot));
        state.begin(1, false);

        assertFalse(state.isVisible());
        assertTrue(state.getItems().isEmpty());
        assertFalse(state.complete(pending, 1, true, databaseSnapshot));
        assertEquals(List.of(saved), databaseSnapshot);
        assertEquals(1, saved.getCid());
        assertEquals("saved", saved.getKey());

        state.complete(state.begin(1, true), 1, true, databaseSnapshot);
        assertEquals(List.of(saved), state.getItems());
    }

    @Test
    public void leavingAndClosingRejectQueuedCallbacks() {
        PosterKeepShelfState state = new PosterKeepShelfState();
        PosterKeepShelfState.Request beforePause = state.begin(1, true);
        state.invalidate();
        assertFalse(state.fail(beforePause, 1, true));
        PosterKeepShelfState.Request beforeClose = state.begin(1, true);
        state.close();

        assertFalse(state.isCurrent(beforeClose, 1, true));
        assertFalse(state.isCurrent(state.begin(1, true), 1, true));
        assertFalse(state.isVisible());
    }

    private static Keep keep(String key, int type, int cid, long time) {
        Keep keep = new Keep();
        keep.setKey(key);
        keep.setType(type);
        keep.setCid(cid);
        keep.setCreateTime(time);
        keep.setVodName(key);
        keep.setVodPic("");
        keep.setSiteName("fixture");
        return keep;
    }
}
