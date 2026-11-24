package com.fongmi.android.tv.api.rex;

import org.junit.Test;

import java.util.Map;

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

    @Test public void domesticMovieWallExpandsBeyondTheHomePreviewWithoutChangingHome() {
        RexTmdbQuery.Query preview = RexTmdbQuery.chinese("movie", false);
        String previewStart = preview.params.get("primary_release_date.gte");
        String previewEnd = preview.params.get("primary_release_date.lte");
        RexTmdbQuery.Query wall = RexWallQuery.browse(preview);
        assertEquals("CN", wall.params.get("with_origin_country"));
        assertFalse(wall.params.containsKey("primary_release_date.gte"));
        assertFalse(wall.params.containsKey("primary_release_date.lte"));
        assertEquals(previewStart, preview.params.get("primary_release_date.gte"));
        assertEquals(previewEnd, preview.params.get("primary_release_date.lte"));
        assertNotNull(previewStart);
        assertNotNull(previewEnd);
    }

    @Test public void domesticSeriesWallIncludesOlderBroadcastsAndLetsCountryClear() {
        RexTmdbQuery.Query preview = RexTmdbQuery.chinese("tv", false);
        RexTmdbQuery.Query wall = RexWallQuery.browse(preview);
        assertFalse(wall.params.containsKey("air_date.gte"));
        assertFalse(wall.params.containsKey("air_date.lte"));
        assertSame(wall, RexWallQuery.apply(wall, "", "", "CN", "", "", "", ""));
        RexTmdbQuery.Query allCountries = RexWallQuery.apply(wall, "", "", "", "", "", "", "");
        assertFalse(allCountries.params.containsKey("with_origin_country"));
        assertEquals("CN", wall.params.get("with_origin_country"));
        assertNotNull(preview.params.get("air_date.gte"));
    }

    @Test public void browseKeepsFixedGenresProvidersAndNonDomesticDateConstraints() {
        RexTmdbQuery.Query scoped = new RexTmdbQuery.Query("discover/movie", "movie", Map.of(
                "with_origin_country", "KR", "with_genres", "28", "with_watch_providers", "8",
                "primary_release_date.gte", "2025-01-01"));
        assertSame(scoped, RexWallQuery.browse(scoped));
    }

    @Test public void varietyCombinesRealityAndTalkWithOrAndAllowsScriptedAfterwards() {
        RexTmdbQuery.Query wall = RexWallQuery.browse(RexTmdbQuery.chinese("tv", false));
        RexTmdbQuery.Query variety = RexWallQuery.apply(wall, "", "", "CN", "", "", "", "variety");
        assertEquals("discover/tv", variety.path);
        assertEquals("10764|10767", variety.params.get("with_genres"));
        assertFalse(variety.params.containsKey("with_type"));
        RexTmdbQuery.Query scripted = RexWallQuery.apply(wall, "", "18", "CN", "", "", "", "4");
        assertEquals("4", scripted.params.get("with_type"));
        assertEquals("18", scripted.params.get("with_genres"));
        assertFalse(wall.params.containsKey("with_type"));
        assertFalse(wall.params.containsKey("with_genres"));
    }

    @Test public void allSevenProgramTypesUseSingleWithTypeValuesIncludingZero() {
        RexTmdbQuery.Query original = RexTmdbQuery.popular("tv");
        for (int value = 0; value <= 6; value++) {
            RexTmdbQuery.Query query = RexWallQuery.apply(original, "", "", "", "", "", "", String.valueOf(value));
            assertEquals("discover/tv", query.path);
            assertEquals(String.valueOf(value), query.params.get("with_type"));
        }
        assertSame(original, RexWallQuery.apply(original, "", "", "", "", "", "", ""));
    }

    @Test public void varietyPresetCanClearWithoutLeavingOriginalGenreOrTypeBehind() {
        RexTmdbQuery.Query preview = new RexTmdbQuery.Query("discover/tv", "tv", Map.of(
                "with_origin_country", "CN", "with_genres", "10764|10767", "with_type", "3"));
        RexTmdbQuery.Query wall = RexWallQuery.browse(preview);
        assertFalse(wall.params.containsKey("with_genres"));
        assertFalse(wall.params.containsKey("with_type"));
        assertSame(wall, RexWallQuery.apply(wall, "", "", "CN", "", "", "", ""));
        assertEquals("10764|10767", preview.params.get("with_genres"));
        assertEquals("3", preview.params.get("with_type"));
    }

    @Test public void varietyRemovesConflictingGenreExclusionsButKeepsOtherExclusions() {
        RexTmdbQuery.Query original = new RexTmdbQuery.Query("discover/tv", "tv", Map.of(
                "without_genres", "10764,16,10767"));
        RexTmdbQuery.Query query = RexWallQuery.apply(original, "", "", "", "", "", "", "variety");
        assertEquals("10764|10767", query.params.get("with_genres"));
        assertEquals("16", query.params.get("without_genres"));
        assertEquals("10764,16,10767", original.params.get("without_genres"));
    }

    @Test public void explicitYearReplacesBothOriginalBoundsIncludingOpenEndedRanges() {
        RexTmdbQuery.Query original = new RexTmdbQuery.Query("discover/movie", "movie", Map.of(
                "primary_release_date.gte", "2025-01-01", "primary_release_date.lte", "2026-11-21"));
        RexTmdbQuery.Query older = RexWallQuery.apply(original, "", "", "", "", "1959-12-31", "");
        assertFalse(older.params.containsKey("primary_release_date.gte"));
        assertEquals("1959-12-31", older.params.get("primary_release_date.lte"));
        RexTmdbQuery.Query year = RexWallQuery.apply(original, "", "", "", "2020-01-01", "2020-12-31", "");
        assertEquals("2020-01-01", year.params.get("primary_release_date.gte"));
        assertEquals("2020-12-31", year.params.get("primary_release_date.lte"));
        assertEquals("2025-01-01", original.params.get("primary_release_date.gte"));
    }

    @Test public void advancedTrendsUseDiscoverAndClearingRestoresTheOriginalWindow() {
        RexTmdbQuery.Query original = RexTmdbQuery.trending("week");
        RexTmdbQuery.Query query = RexWallQuery.apply(original, "tv", "18", "KR", "2024-01-01", "2024-12-31", "rating", "4");
        assertEquals("discover/tv", query.path);
        assertEquals("18", query.params.get("with_genres"));
        assertEquals("KR", query.params.get("with_origin_country"));
        assertEquals("4", query.params.get("with_type"));
        assertEquals("vote_average.desc", query.params.get("sort_by"));
        assertEquals("100", query.params.get("vote_count.gte"));
        assertEquals("2024-01-01", query.params.get("first_air_date.gte"));
        assertSame(original, RexWallQuery.apply(original, "", "", "", "", "", "", ""));
        assertEquals("trending/tv/week", RexWallQuery.apply(original, "tv", "", "", "", "", "", "").path);
    }

    @Test public void varietyFromMixedTrendsChoosesTvAndNeverAddsMovieDateFields() {
        RexTmdbQuery.Query query = RexWallQuery.apply(RexTmdbQuery.trending("day"), "", "", "", "2020-01-01", "2020-12-31", "", "variety");
        assertEquals("discover/tv", query.path);
        assertEquals("tv", query.fallbackMediaType);
        assertEquals("10764|10767", query.params.get("with_genres"));
        assertFalse(query.params.containsKey("primary_release_date.gte"));
    }
}
