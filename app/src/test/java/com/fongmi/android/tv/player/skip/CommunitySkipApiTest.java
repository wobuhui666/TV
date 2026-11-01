package com.fongmi.android.tv.player.skip;

import org.junit.Test;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CommunitySkipApiTest {

    private static final EpisodeIdentity TV = new EpisodeIdentity(1396, "tt0903747", 1, 1);
    private static final long DURATION = 3_500_000;

    @Test
    public void shouldFillMissingCreditsWithoutReplacingPreferredIntro() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"tvdb_id\":81189,\"type\":\"tv\",\"season\":1,\"episode\":1,\"intro\":[{\"start_ms\":20000,\"end_ms\":70000}]}"));
            server.enqueue(new MockResponse().setBody("{\"imdb_id\":\"tt0903747\",\"season\":1,\"episode\":1,\"intro\":{\"start_ms\":0,\"end_ms\":50000},\"outro\":{\"start_ms\":3431000,\"end_ms\":3470000}}"));
            List<SkipSegment> result = api(server).fetch(TV, DURATION);
            assertEquals(2, result.size());
            assertEquals("theintrodb", result.get(0).source());
            assertEquals(20_000, result.get(0).startMs());
            assertEquals("introdb", result.get(1).source());
            assertEquals(3_470_000, result.get(1).endMs());
            assertEquals(2, server.getRequestCount());
            RecordedRequest first = server.takeRequest();
            assertEquals("1396", first.getRequestUrl().queryParameter("tmdb_id"));
            assertEquals(Long.toString(DURATION), first.getRequestUrl().queryParameter("duration_ms"));
            assertEquals("false", first.getRequestUrl().queryParameter("merge_unknown"));
            assertNull(first.getHeader("Authorization"));
            assertEquals("tt0903747", server.takeRequest().getRequestUrl().queryParameter("imdb_id"));
        }
    }

    @Test
    public void shouldFallbackThroughUnavailableProvidersAndUseReadOnlyPost() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429));
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setBody("{\"items\":[{\"video_type\":\"tv\",\"version_runtime\":3500,\"base_video_season\":{\"season_number\":1},\"base_video_episode\":{\"episode_number\":1},\"marker\":[{\"marker_type\":\"intro\",\"time_start\":15,\"time_end\":35}]}]}"));
            List<SkipSegment> result = api(server).fetch(TV, DURATION);
            assertEquals(List.of(new SkipSegment(SkipSegment.Type.INTRO, 15_000, 35_000, "theotherdb")), result);
            server.takeRequest();
            server.takeRequest();
            RecordedRequest post = server.takeRequest();
            assertEquals("POST", post.getMethod());
            assertEquals("/theotherdb", post.getPath());
            assertTrue(post.getBody().readUtf8().contains("\"platform_type\":\"tmdb_id_tv\""));
            assertNull(post.getHeader("Authorization"));
        }
    }

    @Test
    public void shouldUseMovieQueriesWithoutSeasonOrEpisode() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404));
            server.enqueue(new MockResponse().setResponseCode(404));
            server.enqueue(new MockResponse().setBody("{\"items\":[]}"));
            assertTrue(api(server).fetch(EpisodeIdentity.movie(550, "tt0137523"), DURATION).isEmpty());
            RecordedRequest intro = server.takeRequest();
            assertNull(intro.getRequestUrl().queryParameter("season"));
            assertNull(intro.getRequestUrl().queryParameter("episode"));
            RecordedRequest fallback = server.takeRequest();
            assertEquals("true", fallback.getRequestUrl().queryParameter("is_movie"));
            assertNull(fallback.getRequestUrl().queryParameter("season"));
            String body = server.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("\"platform_type\":\"tmdb_id_movie\""));
            assertFalse(body.contains("season_number"));
            assertFalse(body.contains("episode_number"));
        }
    }

    @Test
    public void shouldCancelRunningLookupWithoutStartingFallback() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"type\":\"tv\"}").setBodyDelay(20, TimeUnit.SECONDS));
            CommunitySkipApi api = api(server);
            Future<List<SkipSegment>> result = executor.submit(() -> api.fetch(TV, DURATION));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            api.cancel();
            assertTrue(result.get(2, TimeUnit.SECONDS).isEmpty());
            assertEquals(1, server.getRequestCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldSkipNetworkAfterCancellationOrWithoutDuration() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            CommunitySkipApi api = api(server);
            assertTrue(api.fetch(TV, 0).isEmpty());
            api.cancel();
            assertTrue(api.fetch(TV, DURATION).isEmpty());
            assertEquals(0, server.getRequestCount());
        }
    }

    private static CommunitySkipApi api(MockWebServer server) {
        return new CommunitySkipApi(new OkHttpClient(), server.url("/theintrodb").toString(),
                server.url("/introdb").toString(), server.url("/theotherdb").toString());
    }
}
