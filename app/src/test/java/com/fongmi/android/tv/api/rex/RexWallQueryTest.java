package com.fongmi.android.tv.api.rex;

import org.junit.Test;

import static org.junit.Assert.*;

public class RexWallQueryTest {

    @Test public void keepsTheOriginalEndpointWhenAllFiltersAreCleared() {
        RexTmdbQuery.Query original = RexTmdbQuery.topRated("movie");
        assertSame(original, RexWallQuery.apply(original, "", "", "", "", "", ""));
    }

    @Test public void mixedTrendsOnlySwitchTheMediaDimensionAndKeepTheWindow() {
        RexTmdbQuery.Query day = RexWallQuery.apply(RexTmdbQuery.trending("day"), "movie", "", "", "", "", "");
        RexTmdbQuery.Query week = RexWallQuery.apply(RexTmdbQuery.trending("week"), "tv", "", "", "", "", "");
        assertEquals("trending/movie/day", day.path);
        assertEquals("movie", day.fallbackMediaType);
        assertEquals("trending/tv/week", week.path);
        assertTrue(day.params.isEmpty());
    }

    @Test public void filteredTopMoviesRetainRatingOrderAndRejectTinyVoteSamples() {
        RexTmdbQuery.Query original = RexTmdbQuery.topRated("movie");
        RexTmdbQuery.Query query = RexWallQuery.apply(original, "", "878", "CN", "2024-01-01", "2024-12-31", "");
        assertEquals("discover/movie", query.path);
        assertEquals("878", query.params.get("with_genres"));
        assertEquals("CN", query.params.get("with_origin_country"));
        assertEquals("2024-01-01", query.params.get("primary_release_date.gte"));
        assertEquals("2024-12-31", query.params.get("primary_release_date.lte"));
        assertEquals("vote_average.desc", query.params.get("sort_by"));
        assertEquals("100", query.params.get("vote_count.gte"));
        assertTrue("The original query must be reusable after clearing filters", original.params.isEmpty());
    }

    @Test public void seriesUseFirstAirDateInsteadOfMovieReleaseDate() {
        RexTmdbQuery.Query query = RexWallQuery.apply(RexTmdbQuery.popular("tv"), "", "18", "KR", "2026-01-01", "2026-12-31", "latest");
        assertEquals("discover/tv", query.path);
        assertEquals("first_air_date.desc", query.params.get("sort_by"));
        assertEquals("2026-01-01", query.params.get("first_air_date.gte"));
        assertFalse(query.params.containsKey("primary_release_date.gte"));
    }

    @Test public void providerAndCompanyConstraintsSurviveAdditionalFilters() {
        RexTmdbQuery.Query provider = RexTmdbQuery.networks("213", "movie", "release_date.desc");
        RexTmdbQuery.Query filtered = RexWallQuery.apply(provider, "", "35", "JP", "", "", "");
        assertEquals("8", filtered.params.get("with_watch_providers"));
        assertEquals("US", filtered.params.get("watch_region"));
        assertEquals("primary_release_date.desc", filtered.params.get("sort_by"));
        assertFalse(provider.params.containsKey("with_genres"));
        RexTmdbQuery.Query company = RexWallQuery.apply(RexTmdbQuery.companies("2", null), "", "16", "", "", "", "rating");
        assertEquals("2", company.params.get("with_companies"));
        assertEquals("16", company.params.get("with_genres"));
    }

    @Test public void aGenreWallKeepsItsOriginalGenreWhenAddingAnotherGenre() {
        RexTmdbQuery.Query original = RexTmdbQuery.discover("28", "movie", null);
        assertEquals("28,35", RexWallQuery.apply(original, "", "35", "", "", "", "").params.get("with_genres"));
        assertEquals("28", RexWallQuery.apply(original, "", "28", "", "", "", "").params.get("with_genres"));
    }

    @Test public void currentCinemaAndAiringWallsKeepTheirTimeConstraints() {
        RexTmdbQuery.Query cinema = RexWallQuery.apply(RexTmdbQuery.nowPlaying(), "", "18", "", "", "", "");
        assertEquals("2|3", cinema.params.get("with_release_type"));
        assertNotNull(cinema.params.get("release_date.gte"));
        assertNotNull(cinema.params.get("release_date.lte"));
        RexTmdbQuery.Query series = RexWallQuery.apply(RexTmdbQuery.onTheAir(), "", "18", "", "", "", "");
        assertNotNull(series.params.get("air_date.gte"));
        assertNotNull(series.params.get("air_date.lte"));
    }
}
