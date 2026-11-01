package com.fongmi.android.tv.player.skip;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EpisodeIdentityResolverTest {

    @Test
    public void shouldParseExplicitSeasonAndEpisodeWithoutUsingListPosition() {
        for (String name : new String[]{"S02E03", "s02.e03", "2x03", "第2季第3集", "第二季第三集"}) {
            EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("Show", name);
            assertNotNull(name, parsed);
            assertEquals(name, Integer.valueOf(2), parsed.season());
            assertEquals(name, 3, parsed.episode());
        }
    }

    @Test
    public void shouldCombineTitleSeasonWithChineseOrNumericEpisodeLabels() {
        for (String name : new String[]{"第十二集", "Episode 12", "12", "十二", "ＥＰ１２"}) {
            EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("剧名 第二季", name);
            assertNotNull(name, parsed);
            assertEquals(Integer.valueOf(2), parsed.season());
            assertEquals(12, parsed.episode());
            assertEquals("剧名", parsed.queryTitle());
        }
    }

    @Test
    public void shouldLeaveSeasonUnknownWhenSourceOnlySaysEpisodeNumber() {
        EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("Show", "第3集");
        assertNotNull(parsed);
        assertNull(parsed.season());
        assertEquals(3, parsed.episode());
    }

    @Test
    public void shouldRejectConflictingSeasonsAndCombinedOrSplitEpisodes() {
        assertNull(EpisodeIdentityResolver.parseEpisode("Show 第二季", "S01E03"));
        assertNull(EpisodeIdentityResolver.parseEpisode("Show S01 第2季", "第3集"));
        for (String name : new String[]{"S01E02E03", "S01E02 S01E03", "S01E02 Episode3", "第01-02集", "第1集-第2集",
                "第十二集上", "第十二集(下)", "第3集上半集", "第十二至十三集", "第十二、十三集", "Episode 3 Part 1",
                "特别篇 第1集", "第1集 SP", "03 预告", "全集", "1080p", "S01E00"}) {
            assertNull(name, EpisodeIdentityResolver.parseEpisode("Show", name));
        }
    }

    @Test
    public void shouldPreserveExplicitSpecialSeasonZero() {
        EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("Show", "S00E01");
        assertNotNull(parsed);
        assertEquals(Integer.valueOf(0), parsed.season());
        assertEquals(Integer.valueOf(0), EpisodeIdentityResolver.parseEpisode("Show", "S00E01 Special").season());
    }

    @Test
    public void shouldOnlyInferSeasonFromOneConfirmedNormalSeason() {
        EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("Show", "第3集");
        assertEquals(new EpisodeIdentity(123, "tt1234567", 1, 3),
                EpisodeIdentityResolver.identityFromDetail(detail("[{\"season_number\":0,\"episode_count\":2},{\"season_number\":1,\"episode_count\":12,\"air_date\":\"2020-01-01\"}]"), 123, parsed, "2020", true));
        assertNull(EpisodeIdentityResolver.identityFromDetail(detail("[{\"season_number\":1,\"episode_count\":12},{\"season_number\":2,\"episode_count\":12}]"), 123, parsed, "", false));
    }

    @Test
    public void shouldRejectWrongSeriesIdOutOfRangeEpisodesAndWrongReleaseYear() {
        JsonObject detail = detail("[{\"season_number\":1,\"episode_count\":2,\"air_date\":\"2020-01-01\"}]");
        EpisodeIdentityResolver.ParsedEpisode episode = EpisodeIdentityResolver.parseEpisode("Show", "S01E03");
        assertNull(EpisodeIdentityResolver.identityFromDetail(detail, 123, episode, "", false));
        episode = EpisodeIdentityResolver.parseEpisode("Show", "S01E02");
        assertNull(EpisodeIdentityResolver.identityFromDetail(detail, 124, episode, "", false));
        assertNull(EpisodeIdentityResolver.identityFromDetail(detail, 123, episode, "2021", true));
    }

    @Test
    public void shouldSelectExactTitleAndYearAndRetainAmbiguousCandidates() {
        JsonObject search = json("{\"results\":[{\"id\":1,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\"},"
                + "{\"id\":2,\"name\":\"Show Returns\",\"first_air_date\":\"2020-01-01\"},"
                + "{\"id\":3,\"name\":\"Show\",\"first_air_date\":\"2021-01-01\"},"
                + "{\"id\":4,\"original_name\":\"Show\",\"first_air_date\":\"2020-01-01\"}]}");
        assertEquals(List.of(1L, 4L), EpisodeIdentityResolver.matchingCandidates(search, "Show", "2020", null));
        assertEquals(List.of(1L, 3L, 4L), EpisodeIdentityResolver.matchingCandidates(search, "Show", "2020", 2));
    }

    @Test
    public void shouldRejectUnknownSeasonWithoutMetadataEvenForKnownTmdbId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            EpisodeIdentityResolver resolver = new EpisodeIdentityResolver("", server.url("/3/").toString(), new OkHttpClient()::newCall);
            assertNull(resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "第3集"));
            assertEquals(new EpisodeIdentity(123, 2, 3), resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "S02E03"));
            assertNull(resolver.resolve("source-id-123", "Show 第二季", "2020", "tv", "第3集"));
            assertEquals(EpisodeIdentity.movie(123, ""), resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "正片"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "上半部"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "下半部分"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "第1部分"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "第1部分 Part 1"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "预告"));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test
    public void shouldDistinguishMovieFromSeriesWithTheSameTmdbNumber() {
        EpisodeIdentity movie = EpisodeIdentity.movie(123, "tt1234567");
        assertTrue(movie.isMovie());
        assertEquals("tmdb:movie:123", movie.key());
        assertEquals("tmdb:tv:123:1:2", new EpisodeIdentity(123, 1, 2).key());
    }

    @Test
    public void shouldResolveKnownMovieAndFetchImdbWithoutGuessingAnEpisode() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"id\":123,\"imdb_id\":\"tt1234567\"}"));
            assertEquals(EpisodeIdentity.movie(123, "tt1234567"), resolver(server).resolve("tmdb:movie:123", "Film", "2020", "movie", "HD"));
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().startsWith("/3/movie/123?"));
        }
    }

    @Test
    public void shouldResolveMovieOnlyWithExactTitleAndReleaseYear() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":123,\"title\":\"Film\",\"release_date\":\"2020-01-01\"},"
                    + "{\"id\":124,\"title\":\"Film\",\"release_date\":\"2021-01-01\"}]}"));
            server.enqueue(new MockResponse().setBody("{\"id\":123,\"title\":\"Film\",\"release_date\":\"2020-01-01\",\"external_ids\":{\"imdb_id\":\"tt1234567\"}}"));
            assertEquals(EpisodeIdentity.movie(123, "tt1234567"), resolver(server).resolve("source-123", "Film", "2020", "movie", "正片"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    public void shouldRejectAmbiguousMovieMatchesWithoutRequestingDetails() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":123,\"title\":\"Film\",\"release_date\":\"2020-01-01\"},"
                    + "{\"id\":124,\"title\":\"Film\",\"release_date\":\"2020-01-01\"}]}"));
            assertNull(resolver(server).resolve("source-123", "Film", "2020", "movie", "正片"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldLoadImdbAndInferSingleSeasonWithOneMetadataRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(detail("[{\"season_number\":1,\"episode_count\":12}]").toString()));
            EpisodeIdentityResolver resolver = resolver(server);
            assertEquals(new EpisodeIdentity(123, "tt1234567", 1, 3), resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "第3集"));
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertTrue(request.getPath().startsWith("/3/tv/123?"));
            assertTrue(request.getPath().contains("append_to_response=external_ids"));
            assertEquals(new EpisodeIdentity(123, "tt1234567", 1, 4), resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "第4集"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldRejectTwoValidSameTitleAndYearSeries() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":123,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\"},"
                    + "{\"id\":124,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\"}]}"));
            server.enqueue(new MockResponse().setBody(detail("[{\"season_number\":1,\"episode_count\":12}]").toString()));
            JsonObject second = detail("[{\"season_number\":1,\"episode_count\":12}]");
            second.addProperty("id", 124);
            server.enqueue(new MockResponse().setBody(second.toString()));
            assertNull(resolver(server).resolve("source-123", "Show", "2020", "tv", "第3集"));
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test
    public void shouldCancelAnInFlightLookupWithoutPublishingFallbackIdentity() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setBody(detail("[{\"season_number\":1,\"episode_count\":12}]").toString()));
            EpisodeIdentityResolver resolver = resolver(server);
            Future<EpisodeIdentity> result = worker.submit(() -> resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "S01E03"));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            resolver.cancel();
            assertNull(result.get(1, TimeUnit.SECONDS));
            assertNull(resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "S01E04"));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void shouldShareOneDeadlineAcrossSearchAndDetailRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeadersDelay(150, TimeUnit.MILLISECONDS)
                    .setBody("{\"results\":[{\"id\":123,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\"}]}"));
            server.enqueue(new MockResponse().setHeadersDelay(400, TimeUnit.MILLISECONDS)
                    .setBody(detail("[{\"season_number\":1,\"episode_count\":12}]").toString()));
            EpisodeIdentityResolver resolver = new EpisodeIdentityResolver("fixture-key", server.url("/3/").toString(), new OkHttpClient()::newCall, 450);
            try {
                resolver.resolve("source-123", "Show", "2020", "tv", "第3集");
                fail("The detail response exceeds the remaining shared lookup budget");
            } catch (IOException expected) {
                assertEquals(2, server.getRequestCount());
            }
        }
    }

    private static EpisodeIdentityResolver resolver(MockWebServer server) {
        return new EpisodeIdentityResolver("fixture-key", server.url("/3/").toString(), new OkHttpClient()::newCall);
    }

    private static JsonObject detail(String seasons) {
        return json("{\"id\":123,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\",\"external_ids\":{\"imdb_id\":\"tt1234567\"},\"seasons\":" + seasons + "}");
    }

    private static JsonObject json(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }
}
