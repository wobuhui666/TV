package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverRequestState;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class DiscoverActivityTest {

    @Test
    public void shouldAssembleMixedHeroAndDeduplicateTitles() {
        Map<DiscoverApi.Row, List<Vod>> content = new EnumMap<>(DiscoverApi.Row.class);
        content.put(DiscoverApi.Row.TMDB_DAY, List.of(vod("tmdb:movie:1", "同名"), vod("tmdb:movie:2", "今日二")));
        content.put(DiscoverApi.Row.DOUBAN_HOT_MOVIE, List.of(vod("douban:1", "同 名"), vod("douban:2", "豆瓣电影")));
        content.put(DiscoverApi.Row.TMDB_WEEK, List.of(vod("tmdb:tv:3", "本周")));
        content.put(DiscoverApi.Row.DOUBAN_HOT_TV, List.of(vod("douban:3", "豆瓣剧")));
        content.put(DiscoverApi.Row.TMDB_NOW_PLAYING, List.of(vod("tmdb:movie:4", "上映")));

        List<Vod> hero = DiscoverActivity.assembleHero(content);

        assertEquals(5, hero.size());
        assertEquals("同名", hero.get(0).getName());
        assertEquals("今日二", hero.get(1).getName());
        assertEquals("豆瓣电影", hero.get(4).getName());
    }

    @Test
    public void shouldFillHeroFromOtherSourceWhenPreferredSourceFails() {
        Map<DiscoverApi.Row, List<Vod>> content = new EnumMap<>(DiscoverApi.Row.class);
        content.put(DiscoverApi.Row.TMDB_DAY, List.of(vod("tmdb:1", "一")));
        content.put(DiscoverApi.Row.TMDB_WEEK, List.of(vod("tmdb:2", "二")));
        content.put(DiscoverApi.Row.TMDB_POPULAR_MOVIE, List.of(vod("tmdb:3", "三"), vod("tmdb:4", "四"), vod("tmdb:5", "五")));

        assertEquals(5, DiscoverActivity.assembleHero(content).size());
    }

    @Test
    public void shouldSkipTmdbItemsWithoutBackdrop() {
        Map<DiscoverApi.Row, List<Vod>> content = new EnumMap<>(DiscoverApi.Row.class);
        Vod posterOnly = vod("tmdb:movie:1", "竖图");
        posterOnly.setBackdrop("poster");
        Vod immersive = vod("tmdb:movie:2", "横图");
        immersive.setBackdrop("backdrop");
        content.put(DiscoverApi.Row.TMDB_DAY, List.of(posterOnly, immersive));

        List<Vod> hero = DiscoverActivity.assembleHero(content);

        assertEquals(1, hero.size());
        assertEquals("横图", hero.get(0).getName());
    }

    @Test
    public void shouldKeepDoubanPosterAsHeroFallback() {
        Map<DiscoverApi.Row, List<Vod>> content = new EnumMap<>(DiscoverApi.Row.class);
        content.put(DiscoverApi.Row.DOUBAN_HOT_MOVIE, List.of(vod("douban:1", "豆瓣兜底")));

        assertEquals(1, DiscoverActivity.assembleHero(content).size());
    }

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
