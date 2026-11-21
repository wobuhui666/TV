package com.fongmi.android.tv.api.rex;

import com.fongmi.android.tv.bean.DiscoverFacet;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class RexTmdbQueryTest {

    @Test
    public void tvSciFiMergesTo10765() {
        RexTmdbQuery.Query query = RexTmdbQuery.discover("878", RexTmdbQuery.TV, null);
        assertEquals("discover/tv", query.path);
        assertEquals("10765", query.params.get("with_genres"));
    }

    @Test
    public void movieSciFiStays878() {
        assertEquals("878", RexTmdbQuery.discover("878", RexTmdbQuery.MOVIE, null).params.get("with_genres"));
    }

    @Test
    public void movieOnlyGenreForcesMovieType() {
        RexTmdbQuery.Query query = RexTmdbQuery.discover("28", RexTmdbQuery.TV, null);
        assertEquals("discover/movie", query.path);
        assertEquals("movie", query.fallbackMediaType);
    }

    @Test
    public void tvOnlyGenreForcesTvType() {
        assertEquals("discover/tv", RexTmdbQuery.discover("10764", RexTmdbQuery.MOVIE, null).path);
    }

    @Test
    public void sortByMapsReleaseDateToMediaDateField() {
        assertEquals("primary_release_date.desc",
                RexTmdbQuery.discover(null, RexTmdbQuery.MOVIE, "release_date.desc").params.get("sort_by"));
        assertEquals("first_air_date.asc",
                RexTmdbQuery.discover(null, RexTmdbQuery.TV, "release_date.asc").params.get("sort_by"));
    }

    @Test
    public void defaultSortByIsDropped() {
        assertFalse(RexTmdbQuery.discover(null, RexTmdbQuery.MOVIE, "default").params.containsKey("sort_by"));
        assertFalse(RexTmdbQuery.discover(null, RexTmdbQuery.TV, "").params.containsKey("sort_by"));
    }

    @Test
    public void networkMovieUsesWatchProviders() {
        RexTmdbQuery.Query query = RexTmdbQuery.networks("4330", RexTmdbQuery.MOVIE, null);
        assertEquals("discover/movie", query.path);
        assertEquals("2303|2616", query.params.get("with_watch_providers"));
        assertEquals("US", query.params.get("watch_region"));
        assertFalse(query.params.containsKey("with_networks"));
    }

    @Test
    public void networkTvKeepsNetworks() {
        RexTmdbQuery.Query query = RexTmdbQuery.networks("213", RexTmdbQuery.TV, null);
        assertEquals("discover/tv", query.path);
        assertEquals("213", query.params.get("with_networks"));
        assertFalse(query.params.containsKey("watch_region"));
    }

    @Test
    public void networkMovieWithoutMappingFallsBackToTv() {
        RexTmdbQuery.Query query = RexTmdbQuery.networks("999999", RexTmdbQuery.MOVIE, null);
        assertEquals("discover/tv", query.path);
        assertEquals("999999", query.params.get("with_networks"));
    }

    @Test
    public void companiesStayMovieDiscover() {
        RexTmdbQuery.Query query = RexTmdbQuery.companies("41077", null);
        assertEquals("discover/movie", query.path);
        assertEquals("41077", query.params.get("with_companies"));
    }

    @Test
    public void fixedListPaths() {
        assertEquals("trending/all/week", RexTmdbQuery.trending("week").path);
        assertEquals("trending/all/day", RexTmdbQuery.trending("day").path);
        assertEquals("movie/now_playing", RexTmdbQuery.nowPlaying().path);
        assertEquals("tv/on_the_air", RexTmdbQuery.onTheAir().path);
        assertEquals("movie/top_rated", RexTmdbQuery.topRated(RexTmdbQuery.MOVIE).path);
        assertEquals("list/8241", RexTmdbQuery.list("8241").path);
    }

    @Test public void legacyGenreAndCompanyFacetsKeepTheirFixedMovieConstraints() {
        RexTmdbQuery.Query genre = RexTmdbQuery.forFacet(new DiscoverFacet(DiscoverFacet.GENRE, "28", "动作", ""));
        RexTmdbQuery.Query company = RexTmdbQuery.forFacet(new DiscoverFacet(DiscoverFacet.COMPANY, "420", "公司", ""));
        assertEquals("discover/movie", genre.path);
        assertEquals("28", genre.params.get("with_genres"));
        assertEquals("420", company.params.get("with_companies"));
        assertEquals("popularity.desc", company.params.get("sort_by"));
    }

    @Test public void legacyProviderFacetsRetainTheirChinaWatchRegion() {
        RexTmdbQuery.Query query = RexTmdbQuery.forFacet(new DiscoverFacet(DiscoverFacet.PROVIDER, "420", "平台", ""));
        assertEquals("420", query.params.get("with_watch_providers"));
        assertEquals("CN", query.params.get("watch_region"));
        assertEquals("movie", query.fallbackMediaType);
    }

    @Test public void legacyFixedListsKeepTheirEndpointAndMediaType() {
        assertEquals("tv/top_rated", RexTmdbQuery.forFacet(new DiscoverFacet(DiscoverFacet.TOP_TV, "", "高分剧集", "")).path);
        assertEquals("movie/now_playing", RexTmdbQuery.forFacet(new DiscoverFacet(DiscoverFacet.NOW_PLAYING, "", "院线", "")).path);
        assertEquals("discover/movie", RexTmdbQuery.forFacet(new DiscoverFacet(null, "", "", "")).path);
    }

    @Test public void shouldSelectDomesticWorksByProductionCountry() {
        for (String type : new String[]{RexTmdbQuery.MOVIE, RexTmdbQuery.TV}) {
            RexTmdbQuery.Query query = RexTmdbQuery.chinese(type, false);
            assertEquals("discover/" + type, query.path);
            assertEquals(type, query.fallbackMediaType);
            assertEquals("CN", query.params.get("with_origin_country"));
            assertEquals("popularity.desc", query.params.get("sort_by"));
            assertFalse("Chinese translations cannot establish production country", query.params.containsKey("with_original_language"));
            assertFalse("A watch region includes foreign productions", query.params.containsKey("watch_region"));
        }
    }

    @Test public void shouldRequireEnoughVotesForDomesticTopRatedWorks() {
        RexTmdbQuery.Query query = RexTmdbQuery.chinese(RexTmdbQuery.MOVIE, true);
        assertEquals("CN", query.params.get("with_origin_country"));
        assertEquals("vote_average.desc", query.params.get("sort_by"));
        assertEquals("100", query.params.get("vote_count.gte"));
    }

    @Test public void shouldLimitPopularDomesticMoviesToRecentReleasedWorks() {
        RexTmdbQuery.Query query = RexTmdbQuery.chinese(RexTmdbQuery.MOVIE, false, now());
        assertEquals("2025-11-19", query.params.get("primary_release_date.gte"));
        assertEquals("2026-11-19", query.params.get("primary_release_date.lte"));
    }

    @Test public void shouldKeepOldWorksInExplicitDomesticTopRatedLists() {
        for (String type : new String[]{RexTmdbQuery.MOVIE, RexTmdbQuery.TV}) {
            RexTmdbQuery.Query query = RexTmdbQuery.chinese(type, true, now());
            assertFalse(query.params.containsKey("primary_release_date.gte"));
            assertFalse(query.params.containsKey("air_date.gte"));
            assertEquals("100", query.params.get("vote_count.gte"));
        }
    }

    @Test public void shouldKeepRegionalFallbackWithinTheSameCountryAndCurrentBroadcasts() {
        RexTmdbQuery.Query chinese = RexTmdbQuery.regionalTv("chinese", "series", now());
        RexTmdbQuery.Query korean = RexTmdbQuery.regionalTv("korean", "series", now());
        assertEquals("discover/tv", chinese.path);
        assertEquals("tv", chinese.fallbackMediaType);
        assertEquals("CN", chinese.params.get("with_origin_country"));
        assertEquals("KR", korean.params.get("with_origin_country"));
        assertEquals("2026-10-20", chinese.params.get("air_date.gte"));
        assertEquals("2026-11-19", chinese.params.get("air_date.lte"));
        assertEquals("Asia/Shanghai", chinese.params.get("timezone"));
        assertFalse("New seasons can belong to an older series", chinese.params.containsKey("first_air_date.gte"));
        assertEquals("10764,10767", chinese.params.get("without_genres"));
        assertNull(RexTmdbQuery.regionalTv("unknown", "series", now()));
    }

    @Test public void shouldKeepLongRunningVarietyInItsOwnFallback() {
        RexTmdbQuery.Query query = RexTmdbQuery.regionalTv("chinese", "variety", now());
        assertEquals("10764|10767", query.params.get("with_genres"));
        assertFalse(query.params.containsKey("without_genres"));
        assertFalse(query.params.containsKey("first_air_date.gte"));
        assertEquals("2026-10-20", query.params.get("air_date.gte"));
    }

    private static long now() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(2026, Calendar.NOVEMBER, 19, 4, 0, 0);
        return calendar.getTimeInMillis();
    }
}
