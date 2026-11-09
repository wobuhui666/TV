package com.fongmi.android.tv.player.skip;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.Dispatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    public void shouldPublishUsableIntroBeforeTheSlowCreditsProviderFinishes() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch releaseFallback = new CountDownLatch(1);
        CountDownLatch fallbackStarted = new CountDownLatch(1);
        CountDownLatch firstPublished = new CountDownLatch(1);
        List<List<SkipSegment>> updates = new CopyOnWriteArrayList<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (request.getPath().startsWith("/theintrodb")) return introResponse();
                    fallbackStarted.countDown();
                    releaseFallback.await(2, TimeUnit.SECONDS);
                    return creditsResponse();
                }
            });
            Future<List<SkipSegment>> result = worker.submit(() -> api(server).fetch(TV, DURATION, ranges -> {
                updates.add(ranges);
                firstPublished.countDown();
            }));
            assertTrue("An available intro is delivered immediately", firstPublished.await(1, TimeUnit.SECONDS));
            assertTrue("Credits lookup has begun", fallbackStarted.await(1, TimeUnit.SECONDS));
            assertFalse("The full lookup is still waiting for credits", result.isDone());
            assertEquals(List.of(new SkipSegment(SkipSegment.Type.INTRO, 20_000, 70_000, "theintrodb")), updates.get(0));
            assertImmutable(updates.get(0));
            releaseFallback.countDown();
            List<SkipSegment> completed = result.get(2, TimeUnit.SECONDS);
            assertEquals(2, updates.size());
            assertEquals(completed, updates.get(1));
            assertEquals(2, completed.size());
            assertEquals("Preferred intro is not replaced", "theintrodb", completed.get(0).source());
            assertImmutable(updates.get(1));
        } finally {
            releaseFallback.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void shouldKeepAvailableIntroWhenFallbackHasNoUsableRanges() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(introResponse());
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setBody("{\"items\":[]}"));
            List<List<SkipSegment>> updates = new CopyOnWriteArrayList<>();
            List<SkipSegment> result = api(server).fetch(TV, DURATION, updates::add);
            assertEquals(1, updates.size());
            assertEquals(result, updates.get(0));
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test
    public void shouldNotPublishLateCreditsAfterTheSessionIsCancelled() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch fallbackStarted = new CountDownLatch(1);
        CountDownLatch releaseFallback = new CountDownLatch(1);
        CountDownLatch firstPublished = new CountDownLatch(1);
        List<List<SkipSegment>> updates = new CopyOnWriteArrayList<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (request.getPath().startsWith("/theintrodb")) return introResponse();
                    fallbackStarted.countDown();
                    releaseFallback.await(2, TimeUnit.SECONDS);
                    return creditsResponse();
                }
            });
            CommunitySkipApi api = api(server);
            Future<List<SkipSegment>> result = worker.submit(() -> api.fetch(TV, DURATION, ranges -> {
                updates.add(ranges);
                firstPublished.countDown();
            }));
            assertTrue(firstPublished.await(1, TimeUnit.SECONDS));
            assertTrue(fallbackStarted.await(1, TimeUnit.SECONDS));
            api.cancel();
            releaseFallback.countDown();
            assertTrue(result.get(2, TimeUnit.SECONDS).isEmpty());
            assertEquals("Cancelled response cannot add credits", 1, updates.size());
            assertEquals("Cancellation cannot start another provider", 2, server.getRequestCount());
        } finally {
            releaseFallback.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void shouldDeliverCachedRangesWithoutRepeatingNetworkLookup() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            EpisodeIdentity identity = new EpisodeIdentity(908761234, "tt0903747", 1, 1);
            server.enqueue(new MockResponse().setBody("{\"tvdb_id\":81189,\"type\":\"tv\",\"season\":1,\"episode\":1,"
                    + "\"intro\":[{\"start_ms\":20000,\"end_ms\":70000}],"
                    + "\"credits\":[{\"start_ms\":3431000,\"end_ms\":3470000}]}"));
            CommunitySkipApi first = cachedApi(server);
            List<SkipSegment> expected = first.fetch(identity, DURATION);
            assertEquals(2, expected.size());
            List<List<SkipSegment>> updates = new CopyOnWriteArrayList<>();
            assertEquals(expected, cachedApi(server).fetch(identity, DURATION, updates::add));
            assertEquals(List.of(expected), updates);
            assertImmutable(updates.get(0));
            assertEquals(1, server.getRequestCount());
            CommunitySkipApi cancelled = cachedApi(server);
            cancelled.cancel();
            assertTrue(cancelled.fetch(identity, DURATION, ignored -> fail("Cancelled cache hit must not publish")).isEmpty());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldContinueProviderFallbackWhenTheObserverThrows() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(introResponse());
            server.enqueue(creditsResponse());
            List<SkipSegment> result = api(server).fetch(TV, DURATION, ignored -> {
                throw new IllegalStateException("Test observer failed");
            });
            assertEquals(2, result.size());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    public void shouldCancelWithoutWaitingForAnObserverOnTheWorker() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch observerStarted = new CountDownLatch(1);
        CountDownLatch releaseObserver = new CountDownLatch(1);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(introResponse());
            CommunitySkipApi api = api(server);
            Future<List<SkipSegment>> result = workers.submit(() -> api.fetch(TV, DURATION, ignored -> {
                observerStarted.countDown();
                try { releaseObserver.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(observerStarted.await(1, TimeUnit.SECONDS));
            // Cancellation is also called from the UI thread; an observer must not own its lock.
            workers.submit(api::cancel).get(500, TimeUnit.MILLISECONDS);
            releaseObserver.countDown();
            assertTrue(result.get(1, TimeUnit.SECONDS).isEmpty());
            assertEquals("No fallback is started after cancellation", 1, server.getRequestCount());
        } finally {
            releaseObserver.countDown();
            workers.shutdownNow();
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

    private static CommunitySkipApi cachedApi(MockWebServer server) {
        return new CommunitySkipApi(new OkHttpClient(), server.url("/theintrodb").toString(),
                server.url("/introdb").toString(), server.url("/theotherdb").toString(), true);
    }

    private static MockResponse introResponse() {
        return new MockResponse().setBody("{\"tvdb_id\":81189,\"type\":\"tv\",\"season\":1,\"episode\":1,"
                + "\"intro\":[{\"start_ms\":20000,\"end_ms\":70000}]}");
    }

    private static MockResponse creditsResponse() {
        return new MockResponse().setBody("{\"imdb_id\":\"tt0903747\",\"season\":1,\"episode\":1,"
                + "\"outro\":{\"start_ms\":3431000,\"end_ms\":3470000}}");
    }

    private static void assertImmutable(List<SkipSegment> segments) {
        try {
            segments.clear();
            fail("Published ranges must be immutable snapshots");
        } catch (UnsupportedOperationException expected) { }
    }
}
