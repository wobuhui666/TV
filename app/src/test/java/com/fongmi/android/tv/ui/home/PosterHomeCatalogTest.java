package com.fongmi.android.tv.ui.home;

import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.ui.rex.RexFeedResult;
import com.fongmi.android.tv.ui.rex.RexSections;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PosterHomeCatalogTest {

    @Test
    public void retainsDoubanPostersWhenTmdbIsUnavailable() {
        Map<DiscoverApi.Row, List<Vod>> content = new EnumMap<>(DiscoverApi.Row.class);
        content.put(DiscoverApi.Row.DOUBAN_HOT_MOVIE, List.of(vod("douban:1", "电影", "movie", "2026", false)));
        content.put(DiscoverApi.Row.DOUBAN_HOT_TV, List.of(vod("douban:2", "剧集", "tv", "2026", false)));

        assertEquals(List.of("电影", "剧集"), PosterHomeCatalog.hero(content, PosterHomeCatalog.ALL).stream().map(Vod::getName).toList());
        assertEquals(List.of("剧集"), PosterHomeCatalog.hero(content, PosterHomeCatalog.TV).stream().map(Vod::getName).toList());
    }

    @Test
    public void prefersLandscapeAndPreservesDistinctRemakes() {
        Map<DiscoverApi.Row, List<Vod>> content = new EnumMap<>(DiscoverApi.Row.class);
        content.put(DiscoverApi.Row.TMDB_DAY, List.of(vod("tmdb:movie:1", "同名电影", "movie", "2026", true)));
        content.put(DiscoverApi.Row.DOUBAN_HOT_MOVIE, List.of(
                vod("douban:1", "同 名 电 影", "movie", "2026", false),
                vod("douban:2", "同名电影", "movie", "1990", false)));

        List<Vod> hero = PosterHomeCatalog.hero(content, PosterHomeCatalog.ALL);

        assertEquals(2, hero.size());
        assertEquals("tmdb:movie:1", hero.get(0).getId());
        assertEquals("1990", hero.get(1).getYear());
    }

    @Test
    public void keepsMoviesAndSeriesOutOfEachOthersCategory() {
        List<Vod> items = List.of(vod("tmdb:movie:1", "电影", "movie", "2026", true),
                vod("tmdb:tv:2", "剧集", "tv", "2026", true));

        assertEquals(List.of("电影"), PosterHomeCatalog.filter(items, PosterHomeCatalog.MOVIES).stream().map(Vod::getName).toList());
        assertEquals(List.of("剧集"), PosterHomeCatalog.filter(items, PosterHomeCatalog.TV).stream().map(Vod::getName).toList());
        assertFalse(PosterHomeCatalog.visible(DiscoverApi.Row.DOUBAN_HOT_MOVIE, PosterHomeCatalog.TOP));
        assertTrue(PosterHomeCatalog.visible(DiscoverApi.Row.TMDB_TOP_TV, PosterHomeCatalog.TOP));
    }

    @Test
    public void boundsShelfAndHeroWorkForOversizedLists() {
        List<Vod> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) items.add(vod("tmdb:movie:" + i, "电影" + i, "movie", "2026", true));
        Map<DiscoverApi.Row, List<Vod>> content = Map.of(DiscoverApi.Row.TMDB_DAY, items);

        assertEquals(20, PosterHomeCatalog.filter(items, PosterHomeCatalog.ALL).size());
        assertEquals(5, PosterHomeCatalog.hero(content, PosterHomeCatalog.ALL).size());
        assertTrue(PosterHomeCatalog.hero(Map.of(), PosterHomeCatalog.ALL).isEmpty());
    }

    @Test
    public void shouldDeduplicateOnlyExactIdentitiesWithinAShelf() {
        List<Vod> items = List.of(vod("tmdb:movie:1", "同名", "movie", "2026", true),
                vod("tmdb:movie:1", "同名", "movie", "2026", true),
                vod("tmdb:movie:2", "同名", "movie", "1990", true),
                vod("tmdb:tv:1", "同名", "tv", "2026", true));
        assertEquals(List.of("tmdb:movie:1", "tmdb:movie:2", "tmdb:tv:1"),
                PosterHomeCatalog.compactItems(items, PosterHomeCatalog.ALL).stream().map(Vod::getId).toList());
        assertEquals(2, PosterHomeCatalog.compactItems(items, PosterHomeCatalog.MOVIES).size());
    }

    @Test
    public void shouldKeepCompactHomeToItsSelectedShelves() {
        assertEquals(List.of("trending_day", "douban_movie", "douban_tv"), PosterHomeCatalog.compactKeys(PosterHomeCatalog.ALL));
        assertFalse(PosterHomeCatalog.compactKeys(PosterHomeCatalog.MOVIES).contains("douban_tv"));
        assertFalse(PosterHomeCatalog.compactKeys(PosterHomeCatalog.TV).contains("now_playing"));
        assertEquals(List.of("top_movie", "top_tv"), PosterHomeCatalog.compactKeys(PosterHomeCatalog.TOP));
    }

    @Test
    public void shouldBuildHeroFromFeedSnapshotsAndKeepDoubanFallback() {
        List<RexFeedResult> feeds = List.of(new RexFeedResult(RexSections.byKey("douban_movie"),
                List.of(vod("douban:1", "电影", "movie", "2026", false))),
                new RexFeedResult(RexSections.byKey("douban_tv"), List.of(vod("douban:2", "剧集", "tv", "2026", false))));
        assertEquals(2, PosterHomeCatalog.compactHero(feeds, PosterHomeCatalog.ALL).size());
        assertEquals("剧集", PosterHomeCatalog.compactHero(feeds, PosterHomeCatalog.TV).get(0).getName());
        assertTrue(PosterHomeCatalog.compactHero(feeds, PosterHomeCatalog.TOP).isEmpty());
    }

    @Test
    public void shouldPreserveActionsAfterThePreviewCapWithoutArtwork() throws Exception {
        List<Vod> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) items.add(vod("source:" + i, "影片" + i, "movie", "2026", true));
        Vod action = new Vod();
        action.setName("配置 CK");
        Field field = Vod.class.getDeclaredField("action");
        field.setAccessible(true);
        field.set(action, "login");
        items.add(action);
        assertEquals(20, PosterHomeCatalog.sourceVideos(items).size());
        assertEquals(List.of(action), PosterHomeCatalog.sourceActions(items));
        assertTrue(PosterHomeCatalog.compactItems(List.of(action), PosterHomeCatalog.ALL).isEmpty());
    }

    private static Vod vod(String id, String title, String type, String year, boolean landscape) {
        Vod item = new Vod();
        item.setId(id);
        item.setName(title);
        item.setTypeName(type);
        item.setYear(year);
        item.setPic("poster");
        item.setBackdrop(landscape ? "backdrop" : "poster");
        return item;
    }
}
