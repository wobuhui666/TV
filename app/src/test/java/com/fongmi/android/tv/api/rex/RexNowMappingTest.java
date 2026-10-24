package com.fongmi.android.tv.api.rex;

import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RexNowMappingTest {

    private static long epoch(int year, int month, int day, int hourUtc, int minuteUtc) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(year, month - 1, day, hourUtc, minuteUtc, 0);
        return calendar.getTimeInMillis();
    }

    private static Vod vod(String id) {
        Vod item = new Vod();
        item.setId(id);
        return item;
    }

    @Test
    public void parsesRegionalSeriesFeed() {
        String json = "{\"schemaVersion\":1,\"region\":{\"id\":\"chinese\"},\"category\":\"series\",\"items\":["
                + "{\"id\":\"304452\",\"type\":\"tmdb\",\"title\":\"玉簟秋\",\"description\":\"乱世爱情\",\"releaseDate\":\"2026-10-12\","
                + "\"posterPath\":\"/r5N8g3WEes9x1z76Sil614Do9Bv.jpg\",\"rating\":9.0,\"mediaType\":\"tv\",\"genreTitle\":\"剧情\","
                + "\"genreItems\":[{\"id\":\"18\",\"title\":\"剧情\"}]}]}";
        List<Vod> items = RexNowClient.parseRegionalFeed(json, "chinese", "series");
        assertEquals(1, items.size());
        Vod item = items.get(0);
        assertEquals("tmdb:tv:304452", item.getId());
        assertEquals("玉簟秋", item.getName());
        assertEquals("2026", item.getYear());
        assertEquals("剧情", item.getGenreText());
        assertTrue(item.getPic().endsWith("/r5N8g3WEes9x1z76Sil614Do9Bv.jpg"));
    }

    @Test
    public void regionalVarietyUsesChineseGenreTitle() {
        String json = "{\"schemaVersion\":1,\"region\":\"chinese\",\"category\":\"variety\",\"items\":["
                + "{\"id\":\"1\",\"title\":\"你好，星期六\",\"releaseDate\":\"2026-08-01\",\"mediaType\":\"tv\",\"genreTitle\":\"真人秀\"}]}";
        List<Vod> items = RexNowClient.parseRegionalFeed(json, "chinese", "variety");
        assertEquals(1, items.size());
        assertEquals("真人秀", items.get(0).getGenreText());
    }

    @Test
    public void rejectsWrongRegionCategoryOrSchema() {
        String json = "{\"schemaVersion\":1,\"region\":\"chinese\",\"category\":\"variety\",\"items\":["
                + "{\"id\":\"1\",\"title\":\"A\",\"mediaType\":\"tv\"}]}";
        assertTrue(RexNowClient.parseRegionalFeed(json, "chinese", "series").isEmpty());
        assertTrue(RexNowClient.parseRegionalFeed(json, "korean", "variety").isEmpty());
        assertTrue(RexNowClient.parseRegionalFeed("{\"schemaVersion\":2}", "chinese", "variety").isEmpty());
        assertTrue(RexNowClient.parseRegionalFeed("not json", "chinese", "variety").isEmpty());
    }

    @Test
    public void parsesDoubanFeedArrayRoot() {
        String json = "[{\"title\":\"金谍行动\",\"genres\":[\"剧情\",\"动作\",\"惊悚\"],\"tmdb_info\":{\"id\":1122573,\"title\":\"金谍行动\","
                + "\"description\":\"特工\",\"releaseDate\":\"2026-05-13\",\"posterPath\":\"/abc.jpg\",\"rating\":7.6,\"mediaType\":\"movie\","
                + "\"genreTitle\":\"Action, Thriller, Crime\"}}]";
        List<Vod> items = RexNowClient.parseDoubanFeed(json);
        assertEquals(1, items.size());
        assertEquals("tmdb:movie:1122573", items.get(0).getId());
        assertEquals("剧情、动作", items.get(0).getGenreText());
        assertEquals("7.6分", items.get(0).getRemarks());
    }

    @Test
    public void doubanEntryWithoutTmdbInfoIsSkipped() {
        assertTrue(RexNowClient.parseDoubanFeed("[{\"title\":\"无TMDB\"}]").isEmpty());
        assertTrue(RexNowClient.parseDoubanFeed(null).isEmpty());
    }

    @Test
    public void parsesAnimeWeeklyGroups() {
        String json = "{\"星期一\":[{\"bangumi_name\":\"魔法少女育成计划 restart\",\"tmdb_info\":{\"id\":337277,"
                + "\"title\":\"魔法少女育成计划 restart\",\"description\":\"魔女\",\"releaseDate\":\"2026-10-06\","
                + "\"posterPath\":\"/an.jpg\",\"rating\":0.0,\"mediaType\":\"tv\",\"genreTitle\":\"Animation, Drama\"}}],\"星期二\":[]}";
        Map<String, List<Vod>> weekly = RexNowClient.parseAnimeWeekly(json);
        assertEquals(1, weekly.size());
        List<Vod> monday = weekly.get("星期一");
        assertEquals(1, monday.size());
        assertEquals("tmdb:tv:337277", monday.get(0).getId());
        assertEquals("动画、剧情", monday.get(0).getGenreText());
    }

    @Test
    public void weekdayUsesAsiaShanghai() {
        // 2026-01-01 是星期四；北京时间跨午夜时按北京日期换算
        assertEquals("星期四", RexNowClient.weekday(epoch(2026, 1, 1, 4, 0)));
        assertEquals("星期四", RexNowClient.weekday(epoch(2025, 12, 31, 16, 0)));
    }

    @Test
    public void todayFromWeeklyPicksTodayGroup() {
        Map<String, List<Vod>> weekly = RexNowClient.parseAnimeWeekly("{\"星期四\":[{\"bangumi_name\":\"A\","
                + "\"tmdb_info\":{\"id\":1,\"title\":\"A\",\"mediaType\":\"tv\"}}]}");
        assertEquals(1, RexNowClient.todayFromWeekly(weekly, epoch(2026, 1, 1, 4, 0)).size());
        assertTrue(RexNowClient.todayFromWeekly(weekly, epoch(2026, 1, 2, 4, 0)).isEmpty());
        assertTrue(RexNowClient.todayFromWeekly(null, epoch(2026, 1, 1, 4, 0)).isEmpty());
    }

    @Test
    public void mergeDistinctKeepsOrderAndDropsDuplicates() {
        List<Vod> merged = RexNowClient.mergeDistinct(List.of(vod("tmdb:tv:1")),
                List.of(vod("tmdb:tv:1"), vod("tmdb:tv:2")), 0);
        assertEquals(2, merged.size());
        assertEquals("tmdb:tv:1", merged.get(0).getId());
        assertEquals("tmdb:tv:2", merged.get(1).getId());
        // cap 限制合并后的总量：基数已达上限时不再补入
        assertEquals(1, RexNowClient.mergeDistinct(List.of(vod("a")), List.of(vod("b"), vod("c")), 1).size());
        assertEquals(2, RexNowClient.mergeDistinct(List.of(vod("a")), List.of(vod("b"), vod("c")), 2).size());
    }

    @Test
    public void datesPrefersManifestDateThenRecent() {
        List<String> dates = RexNowClient.dates("20261018", epoch(2026, 10, 24, 4, 0));
        assertEquals("20261018", dates.get(0));
        assertEquals(7, dates.size());
        assertEquals("20261024", dates.get(1));
        List<String> olderManifest = RexNowClient.dates("20261018", epoch(2026, 10, 25, 4, 0));
        assertEquals("20261018", olderManifest.get(0));
        assertEquals("20261025", olderManifest.get(1));
        assertEquals(8, olderManifest.size());
        assertEquals("", RexNowClient.manifestDate("{\"schemaVersion\":2,\"date\":\"20261018\"}"));
        assertEquals("20261018", RexNowClient.manifestDate("{\"schemaVersion\":1,\"date\":\"20261018\"}"));
    }
}
