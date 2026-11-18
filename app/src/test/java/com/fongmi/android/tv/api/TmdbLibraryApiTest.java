package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.LibraryMedia;
import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

public class TmdbLibraryApiTest {
    @Test public void shouldQueryExactAiringDateWithoutFilteringSeriesPremieres() {
        var parameters = TmdbLibraryApi.calendarParams(LocalDate.of(2026, 11, 18), 2);
        assertEquals("2026-11-18", parameters.get("air_date.gte"));
        assertEquals("2026-11-18", parameters.get("air_date.lte"));
        assertEquals("Asia/Shanghai", parameters.get("timezone"));
        assertEquals("2", parameters.get("page"));
        assertEquals("false", parameters.get("include_adult"));
        assertEquals("popularity.desc", parameters.get("sort_by"));
        assertFalse(parameters.containsKey("first_air_date.gte"));
        assertFalse(parameters.containsKey("screened_theatrically"));
        assertEquals("1", TmdbLibraryApi.calendarParams(LocalDate.of(2026, 11, 18), 0).get("page"));
    }

    @Test public void shouldKeepExplicitSpecialsAndZeroEpisodeSeasonsWithoutInventingCoordinates() {
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8),
                "{\"id\":8,\"name\":\"作品\",\"seasons\":[null,3,{},"
                        + "{\"season_number\":null},{\"season_number\":false},{\"season_number\":-1},"
                        + "{\"season_number\":1.5},{\"season_number\":2147483648},"
                        + "{\"season_number\":0,\"episode_count\":0},{\"season_number\":3,\"episode_count\":0}]}");
        assertEquals(2, metadata.seasons().size());
        assertEquals(0, metadata.seasons().get(0).number());
        assertEquals(3, metadata.seasons().get(1).number());
        assertEquals(0, metadata.seasons().get(1).episodes());
    }

    @Test public void shouldIgnoreMalformedEpisodeEntriesWithoutTruncatingCoordinates() {
        var episodes = TmdbLibraryApi.parseEpisodes(0,
                "{\"season_number\":0,\"episodes\":[null,3,{},"
                        + "{\"episode_number\":false},{\"episode_number\":1.5},"
                        + "{\"season_number\":0.5,\"episode_number\":3},"
                        + "{\"season_number\":0,\"episode_number\":2,\"air_date\":\"2026-11-18\"}]}");
        assertEquals(1, episodes.size());
        assertEquals(0, episodes.get(0).season());
        assertEquals(2, episodes.get(0).number());
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8),
                "{\"id\":8,\"name\":\"作品\",\"next_episode_to_air\":{\"season_number\":1.5,\"episode_number\":2}}");
        assertNull(metadata.nextAiring());
    }

    @Test public void shouldKeepExactSeasonCoordinatesAndMissingEpisodeImages() {
        var items = TmdbLibraryApi.parseEpisodes(2, "{\"episodes\":[{\"episode_number\":4,\"name\":\"重逢\",\"runtime\":45},{\"episode_number\":7,\"name\":\"归途\",\"still_path\":\"/frame.jpg\"}]}");
        assertEquals(2, items.size());
        assertEquals(2, items.get(0).season());
        assertEquals(4, items.get(0).number());
        assertEquals(7, items.get(1).number());
        assertEquals("", items.get(0).still());
        assertTrue(items.get(1).still().endsWith("/w500/frame.jpg"));
    }

    @Test public void shouldPreferChineseTitleArtworkAndCarryAliases() {
        LibraryMedia.Metadata metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8), "{\"id\":8,\"name\":\"庆余年\",\"original_name\":\"Joy of Life\",\"seasons\":[{\"season_number\":1,\"episode_count\":46}],\"images\":{\"logos\":[{\"iso_639_1\":\"en\",\"file_path\":\"/en.png\"},{\"iso_639_1\":\"zh\",\"file_path\":\"/zh.png\"}]},\"alternative_titles\":{\"results\":[{\"title\":\"慶餘年\"}]}}");
        assertTrue(metadata.logo().endsWith("/zh.png"));
        assertEquals(1, metadata.seasons().get(0).number());
        assertTrue(metadata.aliases().contains("Joy of Life"));
        assertTrue(metadata.aliases().contains("慶餘年"));
    }

    @Test public void shouldOnlyExposeSupportedTrailerLinksAndKeepImagesOptional() {
        LibraryMedia.Extras extras = TmdbLibraryApi.parseExtras(DiscoverMediaKey.of("movie", 1), "{\"videos\":{\"results\":[{\"site\":\"YouTube\",\"key\":\"abcdef123\",\"name\":\"预告\"},{\"site\":\"Other\",\"key\":\"abcdef123\"},{\"site\":\"YouTube\",\"key\":\"javascript:bad\"}]}}");
        assertEquals(1, extras.videos().size());
        assertTrue(extras.videos().get(0).url().startsWith("https://www.youtube.com/watch?v="));
        assertTrue(extras.images().isEmpty());
    }

    @Test public void shouldPreferTheOriginalLanguageWhenChineseArtworkIsMissing() {
        var logos = TmdbLibraryApi.parseLogos("{\"logos\":[{\"iso_639_1\":\"en\",\"file_path\":\"/en.png\",\"vote_average\":10},{\"iso_639_1\":\"ja\",\"file_path\":\"/ja.png\",\"vote_average\":1},{\"iso_639_1\":null,\"file_path\":\"/neutral.png\"},{\"iso_639_1\":\"ja\",\"file_path\":\"/ja.png\"}]}", "ja");
        assertEquals(3, logos.size());
        assertTrue(logos.get(0).endsWith("/ja.png"));
        assertTrue(logos.get(1).endsWith("/en.png"));
        assertTrue(logos.get(2).endsWith("/neutral.png"));
    }

    @Test public void shouldUseLanguageThenRatingsAndWidthWhenChoosingALogo() {
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8), "{\"id\":8,\"name\":\"作品\",\"original_language\":\"ja\",\"images\":{\"logos\":[{\"iso_639_1\":\"ja\",\"file_path\":\"/original.png\",\"vote_average\":10},{\"iso_639_1\":\"zh\",\"file_path\":\"/small.png\",\"width\":100},{\"iso_639_1\":\"zh\",\"file_path\":\"/large.png\",\"width\":1000}]}}");
        assertEquals("ja", metadata.originalLanguage());
        assertTrue(metadata.logo().endsWith("/large.png"));
        assertTrue(metadata.logos().get(1).endsWith("/small.png"));
        assertTrue(metadata.logos().get(2).endsWith("/original.png"));
    }

    @Test public void shouldCarrySeriesUpdateAndNextAiringWithoutUsingTheWholeSeriesTotalForOneSeason() {
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8), "{\"id\":8,\"name\":\"动漫\",\"status\":\"Returning Series\",\"in_production\":true,\"number_of_episodes\":36,\"seasons\":[{\"season_number\":1,\"episode_count\":24},{\"season_number\":2,\"episode_count\":12}],\"last_episode_to_air\":{\"season_number\":2,\"episode_number\":4,\"name\":\"第四话\",\"air_date\":\"2026-11-10\",\"runtime\":24},\"next_episode_to_air\":{\"season_number\":2,\"episode_number\":5,\"air_date\":\"2026-11-17\"}}");
        assertEquals(36, metadata.totalEpisodes());
        assertEquals(12, metadata.seasonEpisodeCount(2));
        assertEquals(24, metadata.seasonEpisodeCount(1));
        assertEquals(0, metadata.seasonEpisodeCount(3));
        assertTrue(metadata.inProduction());
        assertEquals(2, metadata.lastAired().season());
        assertEquals(4, metadata.lastAired().number());
        assertEquals("2026-11-17", metadata.nextAiringForSeason(2).airDate());
        assertNull(metadata.nextAiringForSeason(1));
        var second = metadata.seasonProgress(2, List.of(), LocalDate.of(2026, 11, 12));
        assertEquals(4, second.updatedEpisode());
        assertEquals(12, second.totalEpisodes());
        assertFalse(second.complete());
        assertEquals(5, second.nextEpisode().number());
        assertEquals(0, metadata.seasonProgress(1, List.of(), LocalDate.of(2026, 11, 12)).updatedEpisode());
    }

    @Test public void shouldOnlyCountKnownAiredEpisodesOfTheSelectedSeason() {
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8), "{\"id\":8,\"name\":\"动漫\",\"status\":\"Ended\",\"seasons\":[{\"season_number\":1,\"episode_count\":4}],\"last_episode_to_air\":{\"season_number\":2,\"episode_number\":2,\"air_date\":\"2026-11-20\"}}");
        var episodes = List.of(new LibraryMedia.Episode(1, 1, "", "", "", "2026-11-01", 24),
                new LibraryMedia.Episode(1, 2, "", "", "", "2026-11-08", 24),
                new LibraryMedia.Episode(1, 3, "", "", "", "2026-11-15", 24),
                new LibraryMedia.Episode(1, 4, "", "", "", "", 24),
                new LibraryMedia.Episode(2, 8, "", "", "", "2026-11-01", 24));
        var progress = metadata.seasonProgress(1, episodes, LocalDate.of(2026, 11, 12));
        assertEquals(2, progress.updatedEpisode());
        assertEquals(4, progress.totalEpisodes());
        assertFalse(progress.complete());
        assertTrue(metadata.ended());
    }

    @Test public void shouldNotInventUpdatesFromMissingOrInvalidEpisodeCoordinates() {
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8), "{\"id\":8,\"name\":\"动漫\",\"last_episode_to_air\":{\"episode_number\":4,\"air_date\":\"2026-11-10\"},\"next_episode_to_air\":{\"season_number\":1,\"episode_number\":5,\"air_date\":\"2026-02-31\"}}");
        assertNull(metadata.lastAired());
        assertNull(metadata.nextAiring());
        assertEquals(0, metadata.seasonProgress(1, List.of(), LocalDate.of(2026, 11, 12)).updatedEpisode());
    }

    @Test public void shouldRejectAnUnexpectedSeasonResponseAndIgnoreEpisodesOfAnotherSeason() {
        var episodes = TmdbLibraryApi.parseEpisodes(2, "{\"season_number\":2,\"episodes\":[{\"season_number\":1,\"episode_number\":1},{\"season_number\":2,\"episode_number\":4}]}");
        assertEquals(1, episodes.size());
        assertEquals(4, episodes.get(0).number());
        assertEquals(2, episodes.get(0).season());
        try {
            TmdbLibraryApi.parseEpisodes(2, "{\"season_number\":1,\"episodes\":[]}");
            fail("A response for another season must not be relabelled");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void shouldNotRelabelAnotherWorkAsTheRequestedTmdbIdentity() {
        try {
            TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8), "{\"id\":9,\"name\":\"Another Work\"}");
            fail("The detail identity must match the requested work");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void shouldKeepEpisodeCoordinatesWhenTheCatalogueTotalIsIncomplete() {
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8), "{\"id\":8,\"name\":\"动漫\",\"seasons\":[{\"season_number\":1,\"episode_count\":4}],\"last_episode_to_air\":{\"season_number\":1,\"episode_number\":8,\"air_date\":\"2026-11-10\"}}");
        var progress = metadata.seasonProgress(1, List.of(), LocalDate.of(2026, 11, 12));
        assertEquals(8, progress.updatedEpisode());
        assertEquals(4, progress.totalEpisodes());
        assertFalse(progress.complete());
    }

    @Test public void shouldReadCompanyAndNetworkLogosFromTheExistingDetailResponse() {
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8),
                "{\"id\":8,\"name\":\"作品\",\"production_companies\":[{\"logo_path\":\"/studio.png\"},{\"logo_path\":\"/studio.png\"}],"
                        + "\"networks\":[{\"logo_path\":\"/studio.png\"},{\"logo_path\":\"/network.png\"}]}");
        assertEquals(2, metadata.productionLogos().size());
        assertTrue(metadata.productionLogos().get(0).endsWith("/w300/studio.png"));
        assertTrue(metadata.productionLogos().get(1).endsWith("/w300/network.png"));
    }

    @Test public void shouldKeepProductionLogosOptionalAndPreserveMetadataConstructors() {
        for (String fields : List.of("", ",\"production_companies\":[],\"networks\":[]",
                ",\"production_companies\":null,\"networks\":{}")) {
            var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("movie", 8),
                    "{\"id\":8,\"title\":\"作品\"" + fields + "}");
            assertTrue(metadata.productionLogos().isEmpty());
            assertTrue(new LibraryMedia.Metadata(metadata.detail(), metadata.logo(), metadata.seasons(),
                    metadata.aliases()).productionLogos().isEmpty());
            assertTrue(new LibraryMedia.Metadata(metadata.detail(), metadata.logo(), metadata.seasons(),
                    metadata.aliases(), metadata.logos(), metadata.originalLanguage(), metadata.lastAired(),
                    metadata.nextAiring(), metadata.totalEpisodes(), metadata.inProduction()).productionLogos().isEmpty());
        }
    }

    @Test public void shouldIgnoreMalformedProductionLogoValuesAndUnsafePaths() {
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("movie", 8),
                "{\"id\":8,\"title\":\"作品\",\"production_companies\":[null,4,\"studio\",{},"
                        + "{\"logo_path\":null},{\"logo_path\":[]},{\"logo_path\":{}},{\"logo_path\":false},{\"logo_path\":7},"
                        + "{\"logo_path\":\"\"},{\"logo_path\":\"https://other.example/logo.png\"},"
                        + "{\"logo_path\":\"//other.example/logo.png\"},{\"logo_path\":\"/../logo.png\"},"
                        + "{\"logo_path\":\"/logo.png?token=abc\"},{\"logo_path\":\"/logo\\n.png\"},"
                        + "{\"logo_path\":\"/valid-logo.png\"}],\"networks\":\"invalid\"}");
        assertEquals(1, metadata.productionLogos().size());
        assertTrue(metadata.productionLogos().get(0).endsWith("/w300/valid-logo.png"));
    }

    @Test public void shouldLimitProductionLogosToFiveDistinctImages() {
        var metadata = TmdbLibraryApi.parseMetadata(DiscoverMediaKey.of("tv", 8),
                "{\"id\":8,\"name\":\"作品\",\"production_companies\":["
                        + "{\"logo_path\":\"/one.png\"},{\"logo_path\":\"/one.png\"},{\"logo_path\":\"/two.png\"},"
                        + "{\"logo_path\":\"/three.png\"},{\"logo_path\":\"/four.png\"},{\"logo_path\":\"/five.png\"},"
                        + "{\"logo_path\":\"/six.png\"}],\"networks\":[{\"logo_path\":\"/seven.png\"}]}");
        assertEquals(5, metadata.productionLogos().size());
        assertTrue(metadata.productionLogos().get(4).endsWith("/w300/five.png"));
    }
}
