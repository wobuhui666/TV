package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.bean.DiscoverRequestState;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class DiscoverActivityTest {

    @Test
    public void shouldReturnOnlyNewResultsWhenAppendingPages() {
        DiscoverRequestState state = new DiscoverRequestState();
        int generation = state.reset();
        state.addAll(generation, List.of(vod("tmdb:1", "一"), vod("tmdb:3", "三")));

        List<Vod> added = state.addAllAndGetAdded(generation, List.of(vod("tmdb:2", "二"), vod("tmdb:3", "三"), vod("tmdb:4", "四")));

        assertEquals(List.of("tmdb:2", "tmdb:4"), added.stream().map(Vod::getId).toList());
        assertEquals(List.of("tmdb:1", "tmdb:3", "tmdb:2", "tmdb:4"), state.getItems().stream().map(Vod::getId).toList());
    }

    private static Vod vod(String id, String name) {
        Vod item = new Vod();
        item.setId(id);
        item.setName(name);
        item.setPic("poster");
        item.setBackdrop(id.startsWith("douban:") ? "poster" : "backdrop-" + id);
        return item;
    }
}
