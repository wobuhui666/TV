package com.fongmi.android.tv.ui.home;

import com.fongmi.android.tv.bean.Result;

import org.junit.Test;

import static org.junit.Assert.*;

public class HomeSourceStateTest {
    @Test
    public void shouldClearOldCategoriesWhileTheNewSourceLoads() {
        HomeSourceState state = new HomeSourceState();
        HomeSourceState.Request first = state.begin(1, "first");
        Result result = new Result();
        assertTrue(state.complete(first, 1, "first", result));
        assertSame(result, state.getResult());

        HomeSourceState.Request next = state.begin(1, "next");

        assertTrue(state.isLoading());
        assertNotSame(result, state.getResult());
        assertTrue(state.getResult().getTypes().isEmpty());
        assertFalse(state.complete(first, 1, "next", result));
        assertTrue(state.complete(next, 1, "next", null));
        assertFalse(state.isLoading());
    }

    @Test
    public void shouldRejectRepliesAfterConfigSwitchOrNewerRefresh() {
        HomeSourceState state = new HomeSourceState();
        HomeSourceState.Request first = state.begin(1, "source");
        assertFalse(state.complete(first, 2, "source", new Result()));
        HomeSourceState.Request refresh = state.begin(1, "source");
        assertFalse(state.complete(first, 1, "source", new Result()));
        assertTrue(state.complete(refresh, 1, "source", new Result()));
    }

    @Test
    public void shouldKeepNoSourceReadyWithoutWaitingForARequest() {
        HomeSourceState state = new HomeSourceState();
        state.begin(0, "");
        assertFalse(state.isLoading());
        assertTrue(state.getResult().getList().isEmpty());
    }
}
