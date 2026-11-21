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
        assertEquals(List.of("cn_movie", "cn_tv", "trending_day"), PosterHomeCatalog.compactKeys(PosterHomeCatalog.ALL));
        assertFalse(PosterHomeCatalog.compactKeys(PosterHomeCatalog.MOVIES).contains("douban_tv"));
        assertFalse(PosterHomeCatalog.compactKeys(PosterHomeCatalog.TV).contains("now_playing"));
        assertEquals(List.of("cn_top_movie", "cn_top_tv"), PosterHomeCatalog.compactKeys(PosterHomeCatalog.TOP));
    }

    @Test
    public void shouldLoadOnlyTheSelectedCompactShelves() {
        for (int category : new int[]{PosterHomeCatalog.ALL, PosterHomeCatalog.MOVIES, PosterHomeCatalog.TV, PosterHomeCatalog.TOP}) {
            List<com.fongmi.android.tv.bean.RexSection> sections = PosterHomeCatalog.compactSections(category);
            assertEquals(PosterHomeCatalog.compactKeys(category), sections.stream().map(com.fongmi.android.tv.bean.RexSection::getKey).toList());
            assertTrue(sections.size() <= 3);
            assertTrue(sections.stream().allMatch(section -> section.getLimit() == 12));
        }
        List<com.fongmi.android.tv.bean.RexSection> domestic = PosterHomeCatalog.compactSections(PosterHomeCatalog.ALL);
        assertEquals("CN", domestic.get(0).getQuery().params.get("with_origin_country"));
        assertEquals(com.fongmi.android.tv.bean.RexSection.Source.REXNOW_REGIONAL, domestic.get(1).getSource());
        assertEquals("chinese", domestic.get(1).getRegion());
        assertEquals("series", domestic.get(1).getCategory());
        List<com.fongmi.android.tv.bean.RexSection> library = PosterHomeCatalog.compactSections(PosterHomeCatalog.ALL, true);
        assertEquals(List.of("cn_movie", "cn_tv", "trending_day", "cn_variety", "cn_series", "popular_movie", "anime_daily"),
                library.stream().map(com.fongmi.android.tv.bean.RexSection::getKey).toList());
        assertEquals(List.of("cn_movie", "douban_movie", "now_playing", "cn_variety", "cn_series", "popular_movie", "anime_daily"),
                PosterHomeCatalog.compactSections(PosterHomeCatalog.MOVIES, true).stream().map(com.fongmi.android.tv.bean.RexSection::getKey).toList());
        assertEquals(RexSections.librarySpotlights().get(0).getLabelRes(), library.get(3).getLabelRes());
        assertEquals(RexSections.librarySpotlights().get(1).getLabelRes(), library.get(4).getLabelRes());
        assertTrue(library.stream().allMatch(section -> section.getLimit() == 12));
    }

    @Test
    public void shouldBoundCompactPreviewsWithoutChangingSourcePreviews() {
        List<Vod> items = new ArrayList<>();
        for (int i = 0; i < 40; i++) items.add(vod("tmdb:movie:" + i, "电影" + i, "movie", "2026", true));
        assertEquals(12, PosterHomeCatalog.compactItems(items, PosterHomeCatalog.ALL).size());
        assertEquals(20, PosterHomeCatalog.sourceVideos(items).size());
    }

    @Test
    public void shouldBalanceDomesticFilmsAndSeriesInHero() {
        List<Vod> movies = new ArrayList<>();
        List<Vod> series = new ArrayList<>();
        List<Vod> global = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            movies.add(vod("tmdb:movie:" + i, "国产电影" + i, "movie", "2026", true));
            series.add(vod("tmdb:tv:" + i, "国产剧集" + i, "tv", "2026", true));
            global.add(vod("tmdb:movie:" + (100 + i), "海外电影" + i, "movie", "2026", true));
        }
        List<RexFeedResult> feeds = List.of(new RexFeedResult(RexSections.byKey("trending_day"), global),
                new RexFeedResult(RexSections.byKey("cn_tv"), series),
                new RexFeedResult(RexSections.byKey("cn_movie"), movies));
        assertEquals(List.of("国产电影0", "国产剧集0", "海外电影0", "国产电影1", "国产剧集1"),
                PosterHomeCatalog.compactHero(feeds, PosterHomeCatalog.ALL).stream().map(Vod::getName).toList());
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
        action.setId("");
        action.setName("配置 CK");
        Field field = Vod.class.getDeclaredField("action");
        field.setAccessible(true);
        field.set(action, "login");
        items.add(action);
        assertEquals(20, PosterHomeCatalog.sourceVideos(items).size());
        assertEquals(List.of(action), PosterHomeCatalog.sourceActions(items));
        assertTrue(PosterHomeCatalog.compactItems(List.of(action), PosterHomeCatalog.ALL).isEmpty());
    }

    @Test
    public void shouldKeepSourceFoldersReachableBesideConfigurationActions() throws Exception {
        List<Vod> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) items.add(vod("source:" + i, "影片" + i, "movie", "2026", true));
        Vod folder = vod("source-settings", "账号设置", "", "", false);
        Field tag = Vod.class.getDeclaredField("vodTag");
        tag.setAccessible(true);
        tag.set(folder, "folder");
        folder.setPic("");
        items.add(folder);

        assertEquals(List.of(folder), PosterHomeCatalog.sourceActions(items));
        assertEquals(20, PosterHomeCatalog.sourceVideos(items).size());
        assertFalse(PosterHomeCatalog.sourceVideos(items).contains(folder));
    }

    private static Vod vod(String id, String title, String type, String year, boolean landscape) {
        Vod item = new Vod();
        item.setId(id);
        item.setName(title);
        item.setTypeName(type);
        item.setYear(year);
        item.setPic("poster");
        item.setBackdrop(landscape ? "backdrop" : "poster");
        // Local unit tests use Android return-default-value stubs, so initialize the fields
        // whose production getters normally normalize null through TextUtils.isEmpty().
        try {
            Field action = Vod.class.getDeclaredField("action");
            action.setAccessible(true);
            action.set(item, "");
            Field tag = Vod.class.getDeclaredField("vodTag");
            tag.setAccessible(true);
            tag.set(item, "");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        return item;
    }
}
