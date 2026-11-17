package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.DiscoverDetail;
import com.fongmi.android.tv.bean.DiscoverFacet;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.DiscoverQuery;
import com.fongmi.android.tv.bean.DoubanDetail;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DiscoverApiTest {

    @Test
    public void shouldParseDoubanFilterDataWithCastAndDirectors() {
        String json = "{\"data\":[{\"title\":\"测试剧集\",\"cover\":\"https://img/p.jpg\",\"id\":\"42\",\"rate\":\"8.5\",\"directors\":[\"导演甲\"],\"casts\":[\"演员乙\"]}]}";
        List<Vod> items = DiscoverApi.parseDoubanSubjects(json, "tv");
        assertEquals(1, items.size());
        assertEquals("douban:42", items.get(0).getId());
        assertEquals("tv", items.get(0).getTypeName());
        assertEquals("导演甲", items.get(0).getDirector());
        assertEquals("演员乙", items.get(0).getActor());
    }

    @Test
    public void shouldParseDoubanSubjects() {
        String json = "{\"subjects\":[" +
                "{\"title\":\"沙丘2\",\"rate\":\"8.2\",\"cover\":\"https://img1.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg\",\"id\":\"35575567\"}," +
                "{\"title\":\"无图\",\"rate\":\"7.0\",\"cover\":\"\",\"id\":\"1\"}" +
                "]}";

        List<Vod> items = DiscoverApi.parseDoubanSubjects(json);

        assertEquals(1, items.size());
    }

    @Test
    public void shouldCarryDoubanMediaTypeFromEachList() {
        String json = "{\"subjects\":[{\"title\":\"剧集\",\"cover\":\"https://img/p.jpg\",\"id\":\"1\"}]}";

        assertEquals("movie", DiscoverApi.parseDoubanSubjects(json, "movie").get(0).getTypeName());
        assertEquals("tv", DiscoverApi.parseDoubanSubjects(json, "tv").get(0).getTypeName());
    }

    @Test
    public void shouldParseDoubanAbstractFields() {
        Vod fallback = new Vod();
        fallback.setName("肖申克的救赎");
        fallback.setPic("poster");
        String json = "{\"r\":0,\"subject\":{\"title\":\"肖申克的救赎 The Shawshank Redemption (1994)\",\"rate\":\"9.7\",\"is_tv\":false," +
                "\"directors\":[\"弗兰克\"],\"actors\":[\"蒂姆\",\"摩根\"],\"duration\":\"142分钟\",\"region\":\"美国\"," +
                "\"types\":[\"犯罪\",\"剧情\"],\"release_year\":\"1994\",\"short_comment\":{\"content\":\"希望让你重获自由。\"}}}";

        DoubanDetail detail = DiscoverApi.parseDoubanDetail("1292052", fallback, json);

        assertEquals("肖申克的救赎", detail.getTitle());
        assertEquals("movie", detail.getMediaType());
        assertEquals("1994", detail.getYear());
        assertEquals("犯罪、剧情", detail.getGenres());
        assertEquals("美国", detail.getRegion());
        assertEquals("弗兰克", detail.getDirectors());
        assertEquals("蒂姆、摩根", detail.getActors());
        assertEquals("希望让你重获自由。", detail.getComment());
    }

    @Test
    public void shouldParseDoubanTvEpisodesAndType() {
        Vod fallback = new Vod();
        fallback.setName("狂飙");
        String json = "{\"subject\":{\"title\":\"狂飙 (2023)\",\"is_tv\":true,\"episodes_count\":\"39\",\"duration\":\"45分钟\",\"release_year\":\"2023\"}}";

        DoubanDetail detail = DiscoverApi.parseDoubanDetail("1", fallback, json);

        assertEquals("tv", detail.getMediaType());
        assertEquals("39集 · 45分钟", detail.getDuration());
    }

    @Test
    public void shouldMatchOnlyExactTmdbTitleYearAndType() {
        String json = "{\"results\":[" +
                "{\"id\":1,\"media_type\":\"movie\",\"title\":\"沙丘\",\"release_date\":\"2021-10-22\"}," +
                "{\"id\":2,\"media_type\":\"movie\",\"title\":\"沙丘2\",\"release_date\":\"2024-02-27\"}]}";

        assertEquals(DiscoverMediaKey.of("movie", 2), DiscoverApi.selectTmdbMatch(json, "movie", "沙丘 2", "2024"));
        assertNull(DiscoverApi.selectTmdbMatch(json, "tv", "沙丘2", "2024"));
        assertNull(DiscoverApi.selectTmdbMatch(json, "movie", "沙丘2", "2023"));
        assertNull(DiscoverApi.selectTmdbMatch(json, "movie", "沙丘", "2024"));
    }

    @Test
    public void shouldReturnEmptyListForInvalidDoubanBody() {
        assertTrue(DiscoverApi.parseDoubanSubjects("").isEmpty());
        assertTrue(DiscoverApi.parseDoubanSubjects("not json").isEmpty());
        assertTrue(DiscoverApi.parseDoubanSubjects("{\"msg\":\"检测到有异常请求\"}").isEmpty());
    }

    @Test
    public void shouldAppendRefererToDoubanCover() {
        String pic = DiscoverApi.doubanPic("https://img1.doubanio.com/p1.jpg");

        assertEquals("https://img1.doubanio.com/p1.jpg@Referer=https://movie.douban.com/@User-Agent=Mozilla/5.0", pic);
    }

    @Test
    public void shouldFormatDoubanRemarks() {
        assertEquals("8.2分", DiscoverApi.doubanRemarks("8.2"));
        assertEquals("", DiscoverApi.doubanRemarks(""));
        assertEquals("", DiscoverApi.doubanRemarks(null));
    }

    @Test
    public void shouldKeepWorksWithoutPostersAndExcludePeople() {
        String json = "{\"results\":[" +
                "{\"id\":693134,\"media_type\":\"movie\",\"title\":\"沙丘2\",\"poster_path\":\"/abc.jpg\",\"release_date\":\"2024-02-27\",\"vote_average\":8.16,\"overview\":\"简介\"}," +
                "{\"id\":1,\"media_type\":\"person\",\"name\":\"某演员\",\"poster_path\":\"/p.jpg\"}," +
                "{\"id\":2,\"media_type\":\"tv\",\"name\":\"无海报剧\",\"poster_path\":null}" +
                "]}";

        List<Vod> items = DiscoverApi.parseTmdbResults(json);

        assertEquals(2, items.size());
        assertEquals("tmdb:tv:2", items.get(1).getId());
        assertEquals("", items.get(1).getPic());
    }

    @Test
    public void shouldReturnEmptyListForInvalidTmdbBody() {
        assertTrue(DiscoverApi.parseTmdbResults("").isEmpty());
        assertTrue(DiscoverApi.parseTmdbResults("{\"status_code\":7}").isEmpty());
    }

    @Test
    public void shouldPreferTmdbTitleOverName() {
        JsonObject movie = JsonParser.parseString("{\"title\":\"电影名\",\"name\":\"别名\"}").getAsJsonObject();
        JsonObject tv = JsonParser.parseString("{\"name\":\"剧集名\"}").getAsJsonObject();

        assertEquals("电影名", DiscoverApi.tmdbName(movie));
        assertEquals("剧集名", DiscoverApi.tmdbName(tv));
    }

    @Test
    public void shouldBuildTmdbPosterUrl() {
        assertEquals("https://t.coolmarket.eu.org/t/p/w342/abc.jpg", DiscoverApi.tmdbPic("/abc.jpg"));
        assertEquals("https://t.coolmarket.eu.org/t/p/w342/abc.jpg", DiscoverApi.tmdbPic("abc.jpg"));
        assertEquals("", DiscoverApi.tmdbPic(""));
    }

    @Test
    public void shouldBuildTmdbBackdropAndLogoUrls() {
        assertEquals("https://t.coolmarket.eu.org/t/p/w780/hero.jpg", DiscoverApi.tmdbBackdrop("/hero.jpg"));
        assertEquals("https://t.coolmarket.eu.org/t/p/w300/logo.png", DiscoverApi.tmdbLogo("logo.png"));
    }

    @Test
    public void shouldParseGenresAndProviders() {
        List<DiscoverFacet> genres = DiscoverApi.parseFacets("{\"genres\":[{\"id\":878,\"name\":\"科幻\"}]}", DiscoverFacet.GENRE);
        List<DiscoverFacet> providers = DiscoverApi.parseFacets("{\"results\":[{\"provider_id\":8,\"provider_name\":\"Netflix\",\"logo_path\":\"/n.png\"}]}", DiscoverFacet.PROVIDER);

        assertEquals("878", genres.get(0).getId());
        assertEquals("Netflix", providers.get(0).getName());
        assertEquals("https://t.coolmarket.eu.org/t/p/w300/n.png", providers.get(0).getImage());
    }

    @Test
    public void shouldParseTmdbBackdropAndMediaType() {
        List<Vod> items = DiscoverApi.parseTmdbResults("{\"results\":[{\"id\":1,\"name\":\"剧名\",\"media_type\":\"tv\",\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}]}");

        assertEquals("tmdb:tv:1", items.get(0).getId());
        assertEquals("tv", items.get(0).getTypeName());
        assertEquals("https://t.coolmarket.eu.org/t/p/w780/b.jpg", items.get(0).getBackdrop());
    }

    @Test
    public void shouldExtractTmdbYearFromEitherDateField() {
        JsonObject movie = JsonParser.parseString("{\"release_date\":\"2024-02-27\"}").getAsJsonObject();
        JsonObject tv = JsonParser.parseString("{\"first_air_date\":\"2023-01-14\"}").getAsJsonObject();
        JsonObject none = JsonParser.parseString("{\"release_date\":\"\"}").getAsJsonObject();

        assertEquals("2024", DiscoverApi.tmdbYear(movie));
        assertEquals("2023", DiscoverApi.tmdbYear(tv));
        assertEquals("", DiscoverApi.tmdbYear(none));
    }

    @Test
    public void shouldFormatTmdbRemarks() {
        assertEquals("8.2分", DiscoverApi.tmdbRemarks(8.16));
        assertEquals("", DiscoverApi.tmdbRemarks(0));
    }

    @Test
    public void shouldUseFallbackMediaTypeForDiscoverLists() {
        List<Vod> movies = DiscoverApi.parseTmdbResults("{\"results\":[{\"id\":7,\"title\":\"同号电影\",\"poster_path\":\"/m.jpg\"}]}", "movie");
        List<Vod> shows = DiscoverApi.parseTmdbResults("{\"results\":[{\"id\":7,\"name\":\"同号剧集\",\"poster_path\":\"/t.jpg\"}]}", "tv");

        assertEquals("tmdb:movie:7", movies.get(0).getId());
        assertEquals("tmdb:tv:7", shows.get(0).getId());
    }

    @Test
    public void shouldBuildMovieFilterUrl() {
        DiscoverQuery query = new DiscoverQuery("movie", "878", "US", "2024-01-01", "2024-12-31", DiscoverQuery.SORT_RATING, 3);

        String url = query.buildUrl("https://example.com/3/", "key").toString();

        assertTrue(url.startsWith("https://example.com/3/discover/movie?"));
        assertTrue(url.contains("with_genres=878"));
        assertTrue(url.contains("with_origin_country=US"));
        assertTrue(url.contains("primary_release_date.gte=2024-01-01"));
        assertTrue(url.contains("primary_release_date.lte=2024-12-31"));
        assertTrue(url.contains("sort_by=vote_average.desc"));
        assertTrue(url.contains("vote_count.gte=100"));
        assertTrue(url.contains("page=3"));
    }

    @Test
    public void shouldBuildPopularFilterUrlByDefault() {
        DiscoverQuery query = DiscoverQuery.defaults();

        String url = query.buildUrl("https://example.com/3/", "key").toString();

        assertTrue(url.startsWith("https://example.com/3/discover/movie?"));
        assertTrue(url.contains("sort_by=popularity.desc"));
        assertTrue(url.contains("page=1"));
    }

    @Test
    public void shouldBuildTvLatestFilterUrl() {
        DiscoverQuery query = new DiscoverQuery("tv", "", "KR", "2020-01-01", "2029-12-31", DiscoverQuery.SORT_LATEST, 2);

        String url = query.buildUrl("https://example.com/3/", "key").toString();

        assertTrue(url.startsWith("https://example.com/3/discover/tv?"));
        assertTrue(url.contains("first_air_date.gte=2020-01-01"));
        assertTrue(url.contains("first_air_date.lte=2029-12-31"));
        assertTrue(url.contains("sort_by=first_air_date.desc"));
        assertTrue(url.contains("page=2"));
    }

    @Test
    public void shouldParseMovieDetailAndCredits() {
        String json = "{\"id\":693134,\"title\":\"沙丘2\",\"original_title\":\"Dune: Part Two\",\"release_date\":\"2024-02-27\",\"runtime\":166," +
                "\"vote_average\":8.2,\"genres\":[{\"name\":\"科幻\"}],\"production_countries\":[{\"name\":\"美国\"}],\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"," +
                "\"credits\":{\"crew\":[{\"job\":\"Director\",\"name\":\"丹尼斯\"}],\"cast\":[{\"name\":\"提莫西\",\"character\":\"保罗\",\"profile_path\":\"/a.jpg\"}]}}";

        DiscoverDetail detail = DiscoverApi.parseDetail(DiscoverMediaKey.of("movie", 693134), json);

        assertEquals("沙丘2", detail.getTitle());
        assertEquals("2024", detail.getYear());
        assertEquals(166, detail.getRuntimeMinutes());
        assertEquals("丹尼斯", detail.getCreators());
        assertEquals("提莫西", detail.getCast().get(0).getName());
        assertEquals("https://t.coolmarket.eu.org/t/p/w185/a.jpg", detail.getCast().get(0).getProfile());
    }

    @Test
    public void shouldParseTvDetailWithFallbacks() {
        String json = "{\"id\":1,\"name\":\"剧集\",\"first_air_date\":\"2023-01-14\",\"number_of_seasons\":2,\"number_of_episodes\":16,\"status\":\"Ended\"," +
                "\"episode_run_time\":[45],\"created_by\":[{\"name\":\"主创\"}],\"credits\":{\"cast\":[]}}";

        DiscoverDetail detail = DiscoverApi.parseDetail(DiscoverMediaKey.of("tv", 1), json);

        assertEquals("剧集", detail.getTitle());
        assertEquals(45, detail.getRuntimeMinutes());
        assertEquals(2, detail.getSeasons());
        assertEquals(16, detail.getEpisodes());
        assertEquals("Ended", detail.getStatus());
        assertEquals("主创", detail.getCreators());
        assertTrue(detail.getCast().isEmpty());
    }
}
