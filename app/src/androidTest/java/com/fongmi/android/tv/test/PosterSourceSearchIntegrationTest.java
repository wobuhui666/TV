package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.model.DiscoverSourceSearch;
import com.fongmi.android.tv.source.PosterSourceResults;
import com.fongmi.android.tv.source.SearchRelevance;
import com.google.gson.JsonObject;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import fi.iki.elonen.NanoHTTPD;

import static org.junit.Assert.*;

/** Real scheduler, HTTP, JSON parsing and matching; no configured source or public service is used. */
@RunWith(AndroidJUnit4.class)
public final class PosterSourceSearchIntegrationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private DiscoverSourceSearch search;
    private Fixture server;

    @Before public void startLoopbackFixture() throws Exception {
        String packageName = instrumentation.getTargetContext().getPackageName();
        assertTrue("Run only in an isolated validation application", packageName.equals("com.fongmi.android.tv.preview")
                || packageName.equals("com.fongmi.android.tv.sourceprobe"));
        search = new DiscoverSourceSearch();
        server = new Fixture();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
    }

    @After public void stopSearchAndCloseFixture() {
        try {
            if (search != null) instrumentation.runOnMainSync(search::stop);
        } finally {
            if (server != null) {
                server.releaseOld.countDown();
                server.releaseScheduled.countDown();
                server.stop();
            }
        }
    }

    @Test(timeout = 25_000) public void realJsonSourcesMergeAliasesKeepCrossSiteIdsAndRejectWrongVersions() throws Exception {
        Site a = site("a");
        Site b = site("b");
        SearchRelevance.Query query = new SearchRelevance.Query("沙丘", List.of("Dune"), "2021", null);
        Collector collector = new Collector(query, a.getKey(), List.of(a.getKey(), b.getKey()));

        // The repeated keyword must not create a third round of requests.
        instrumentation.runOnMainSync(() -> search.start(List.of(a, b), List.of("沙丘", "Dune", "沙丘"), collector));
        await(collector.complete, "Two-site alias search did not complete", 15);
        instrumentation.waitForIdleSync();
        collector.assertHealthy(4);

        List<PosterSourceResults.Candidate> candidates = collector.results.snapshot(true);
        assertEquals("Two distinct IDs from each of two sites must survive", 4, candidates.size());
        assertEquals("Site-local IDs must not merge across sites", 4,
                candidates.stream().map(item -> PosterSourceResults.key(item.vod())).distinct().count());
        assertEquals(2, candidates.stream().filter(item -> item.vod().getId().equals("shared")).count());
        assertEquals(2, candidates.stream().filter(item -> item.vod().getId().equals("alias-only")).count());
        assertTrue("Exact year and title/known alias should be confirmed", candidates.stream().allMatch(item -> item.match().confident()));
        assertTrue(candidates.stream().noneMatch(item -> item.vod().getId().equals("wrong-year") || item.vod().getId().equals("wrong-title")));
        assertEquals("Repeated rejected IDs should count once per source", 4, collector.results.hiddenCount());
        assertTrue("The raw HTTP path must retain wrong titles until poster matching", collector.ids.contains("wrong-title"));
        assertEquals("Expected exactly two queries per site", 4, server.apiRequests.get());
        assertEquals("Provided posters must prevent fetchPic detail requests", 0, server.unexpectedRequests.get());
        assertNull("Local fixture failed", server.failure.get());
    }

    @Test(timeout = 30_000) public void stoppingAnOldHttpRoundSuppressesItsCallbacksAndDoesNotPolluteTheNextRound() throws Exception {
        Site a = site("a");
        Collector old = new Collector(SearchRelevance.Query.of("旧片"), a.getKey(), List.of(a.getKey()));
        instrumentation.runOnMainSync(() -> search.start(List.of(a), List.of("old-round"), old));
        await(server.oldArrived, "Old HTTP request never reached the fixture", 8);

        instrumentation.runOnMainSync(search::stop);
        Collector next = new Collector(SearchRelevance.Query.of("新片"), a.getKey(), List.of(a.getKey()));
        instrumentation.runOnMainSync(() -> search.start(List.of(a), List.of("new-round"), next));
        await(next.complete, "A cancelled old request blocked the new search round", 8);
        next.assertHealthy(1);

        // Let the original server handler produce its response only after the new round completed.
        server.releaseOld.countDown();
        await(server.oldResponseClosed, "Old fixture response was not closed after release", 5);
        assertFalse("Stopped search delivered a late result or completion", old.anyCallback.await(500, TimeUnit.MILLISECONDS));
        instrumentation.waitForIdleSync();

        assertEquals(0, old.resultCallbacks.get());
        assertEquals(0, old.completionCallbacks.get());
        assertEquals("New round received an old result callback", List.of("new-only"), next.ids);
        assertEquals(1, next.results.snapshot(true).size());
        assertEquals("new-only", next.results.snapshot(true).get(0).vod().getId());
        next.assertHealthy(1);
        assertEquals(2, server.apiRequests.get());
        assertEquals(0, server.unexpectedRequests.get());
        assertNull("Local fixture failed", server.failure.get());
    }

    @Test(timeout = 30_000) public void preferredSourceAliasesEnterTheSixSlotsBeforeFallbackTitles() throws Exception {
        List<Site> sites = scheduledSites();
        Collector collector = scheduledCollector(sites);
        List<String> priorities = List.of(sites.get(5).getKey(), sites.get(3).getKey(), sites.get(1).getKey(), sites.get(5).getKey());
        instrumentation.runOnMainSync(() -> search.start(sites, List.of("沙丘", "Dune", "沙丘"), priorities, collector));

        assertFirstScheduledWave(List.of("/schedule/priority-a|沙丘", "/schedule/priority-a|Dune",
                "/schedule/priority-b|沙丘", "/schedule/priority-b|Dune", "/schedule/priority-c|沙丘", "/schedule/priority-c|Dune"));
        server.releaseScheduled.countDown();
        assertCompleteScheduledRound(collector, sites);
    }

    @Test(timeout = 30_000) public void anEmptyPriorityKeepsAllPrimaryTitlesAheadOfAliases() throws Exception {
        List<Site> sites = scheduledSites();
        Collector collector = scheduledCollector(sites);
        instrumentation.runOnMainSync(() -> search.start(sites, List.of("沙丘", "Dune"), List.of(), collector));

        assertFirstScheduledWave(List.of("/schedule/fallback-a|沙丘", "/schedule/priority-c|沙丘",
                "/schedule/fallback-b|沙丘", "/schedule/priority-b|沙丘", "/schedule/fallback-c|沙丘", "/schedule/priority-a|沙丘"));
        server.releaseScheduled.countDown();
        assertCompleteScheduledRound(collector, sites);
    }

    @Test(timeout = 30_000) public void cancellingAPriorityWaveDropsQueuedFallbackAndItsLateCallbacks() throws Exception {
        List<Site> sites = scheduledSites();
        Collector old = scheduledCollector(sites);
        List<String> priorities = List.of(sites.get(5).getKey(), sites.get(3).getKey(), sites.get(1).getKey());
        instrumentation.runOnMainSync(() -> search.start(sites, List.of("沙丘", "Dune"), priorities, old));
        assertFirstScheduledWave(List.of("/schedule/priority-a|沙丘", "/schedule/priority-a|Dune",
                "/schedule/priority-b|沙丘", "/schedule/priority-b|Dune", "/schedule/priority-c|沙丘", "/schedule/priority-c|Dune"));

        instrumentation.runOnMainSync(search::stop);
        Site nextSite = site("a");
        Collector next = new Collector(SearchRelevance.Query.of("新片"), nextSite.getKey(), List.of(nextSite.getKey()));
        instrumentation.runOnMainSync(() -> search.start(List.of(nextSite), List.of("new-round"), List.of(nextSite.getKey()), next));
        await(next.complete, "Cancelled priority HTTP calls kept all six slots occupied", 8);
        next.assertHealthy(1);

        server.releaseScheduled.countDown();
        await(server.scheduledResponsesClosed, "Cancelled priority responses were not closed", 5);
        assertFalse("Cancelled priority round delivered a late callback", old.anyCallback.await(500, TimeUnit.MILLISECONDS));
        assertEquals(0, old.resultCallbacks.get());
        assertEquals(0, old.completionCallbacks.get());
        assertEquals(List.of("new-only"), next.ids);
        assertEquals("Queued fallback requests escaped cancellation", 6, server.scheduledSnapshot().size());
        assertEquals(7, server.apiRequests.get());
        assertEquals(0, server.unexpectedRequests.get());
        assertNull("Local fixture failed", server.failure.get());
    }

    private List<Site> scheduledSites() {
        return List.of(site("schedule/fallback-a"), site("schedule/priority-c"), site("schedule/fallback-b"),
                site("schedule/priority-b"), site("schedule/fallback-c"), site("schedule/priority-a"));
    }

    private Collector scheduledCollector(List<Site> sites) {
        return new Collector(new SearchRelevance.Query("沙丘", List.of("Dune"), "2021", null),
                sites.get(0).getKey(), sites.stream().map(Site::getKey).toList());
    }

    private void assertFirstScheduledWave(List<String> expected) throws Exception {
        await(server.scheduledFirstWave, "The scheduler did not fill its first six HTTP slots", 8);
        // All six handlers hold their response. No later job may consume a seventh slot.
        assertFalse("Search exceeded the global six-slot budget", server.scheduledBeyondBudget.await(150, TimeUnit.MILLISECONDS));
        assertEquals(expected.stream().sorted().toList(), server.scheduledSnapshot().stream().sorted().toList());
    }

    private void assertCompleteScheduledRound(Collector collector, List<Site> sites) throws Exception {
        await(collector.complete, "Fallback title and alias queries did not finish", 15);
        collector.assertHealthy(12);
        assertEquals(12, server.apiRequests.get());
        assertEquals("Duplicate source/keyword query reached HTTP", 12, server.scheduledSnapshot().stream().distinct().count());
        assertEquals("Fallback results must remain available", sites.stream().map(Site::getKey).sorted().toList(),
                collector.results.snapshot(false).stream().map(item -> item.vod().getSiteKey()).distinct().sorted().toList());
        assertEquals(0, server.unexpectedRequests.get());
        assertNull("Local fixture failed", server.failure.get());
    }

    private Site site(String name) {
        JsonObject json = new JsonObject();
        json.addProperty("key", "poster-search-fixture-" + server.getListeningPort() + "-" + name);
        json.addProperty("name", "Poster fixture " + name);
        json.addProperty("type", 1);
        json.addProperty("api", "http://127.0.0.1:" + server.getListeningPort() + "/" + name);
        json.addProperty("searchable", 1);
        return App.gson().fromJson(json, Site.class);
    }

    private static void await(CountDownLatch latch, String message, int seconds) throws InterruptedException {
        assertTrue(message, latch.await(seconds, TimeUnit.SECONDS));
    }

    private static final class Collector implements DiscoverSourceSearch.Listener {
        final PosterSourceResults results;
        final CountDownLatch complete = new CountDownLatch(1);
        final CountDownLatch anyCallback = new CountDownLatch(1);
        final AtomicInteger resultCallbacks = new AtomicInteger();
        final AtomicInteger completionCallbacks = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<String> ids = new ArrayList<>();
        boolean deadline;
        int totalQueries = -1;

        Collector(SearchRelevance.Query query, String preferred, List<String> orderedSites) {
            results = new PosterSourceResults(query, preferred, orderedSites);
        }

        @Override public void onResult(Result result, int returned, int total) {
            anyCallback.countDown();
            int count = resultCallbacks.incrementAndGet();
            try {
                assertSame("Result must be delivered on main thread", Looper.getMainLooper(), Looper.myLooper());
                assertEquals("Progress must advance once per completed query", count, returned);
                assertTrue(total >= returned);
                if (totalQueries == -1) totalQueries = total;
                else assertEquals("Progress total must stay stable within a round", totalQueries, total);
                assertNotNull("Fixture query failed instead of returning JSON", result);
                for (Vod vod : result.getList()) ids.add(vod.getId());
                results.add(result.getList());
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        }

        @Override public void onComplete(boolean deadline) {
            anyCallback.countDown();
            completionCallbacks.incrementAndGet();
            this.deadline = deadline;
            try {
                assertSame("Completion must be delivered on main thread", Looper.getMainLooper(), Looper.myLooper());
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            complete.countDown();
        }

        void assertHealthy(int expectedQueries) {
            if (failure.get() != null) throw new AssertionError("Search callback failed", failure.get());
            assertFalse("Loopback requests unexpectedly exhausted the overall deadline", deadline);
            assertEquals(expectedQueries, resultCallbacks.get());
            assertEquals(expectedQueries, totalQueries);
            assertEquals("Each round must complete only once", 1, completionCallbacks.get());
        }
    }

    private static final class Fixture extends NanoHTTPD {
        final AtomicInteger apiRequests = new AtomicInteger();
        final AtomicInteger unexpectedRequests = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch oldArrived = new CountDownLatch(1);
        final CountDownLatch releaseOld = new CountDownLatch(1);
        final CountDownLatch oldResponseClosed = new CountDownLatch(1);
        final CountDownLatch scheduledFirstWave = new CountDownLatch(6);
        final CountDownLatch scheduledBeyondBudget = new CountDownLatch(1);
        final CountDownLatch releaseScheduled = new CountDownLatch(1);
        final CountDownLatch scheduledResponsesClosed = new CountDownLatch(6);
        final List<String> scheduledRequests = new ArrayList<>();

        Fixture() { super("127.0.0.1", 0); }

        List<String> scheduledSnapshot() {
            synchronized (scheduledRequests) { return new ArrayList<>(scheduledRequests); }
        }

        @Override public Response serve(IHTTPSession session) {
            try {
                boolean scheduled = session.getUri().startsWith("/schedule/");
                if (session.getMethod() != Method.GET || !(scheduled || session.getUri().equals("/a") || session.getUri().equals("/b"))
                        || session.getParms().containsKey("ids")) {
                    unexpectedRequests.incrementAndGet();
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unexpected fixture request");
                }
                apiRequests.incrementAndGet();
                String keyword = session.getParms().getOrDefault("wd", "");
                if (keyword.equals("old-round")) {
                    oldArrived.countDown();
                    if (!releaseOld.await(20, TimeUnit.SECONDS)) throw new IOException("Old response was never released");
                    return json(List.of(item("old-only", "旧片", "2020")), oldResponseClosed);
                }
                if (keyword.equals("new-round")) return json(List.of(item("new-only", "新片", "2026")));
                if (!keyword.equals("沙丘") && !keyword.equals("Dune")) throw new IOException("Unexpected fixture keyword");
                if (scheduled) {
                    synchronized (scheduledRequests) {
                        scheduledRequests.add(session.getUri() + "|" + keyword);
                        if (scheduledRequests.size() > 6 && releaseScheduled.getCount() != 0) scheduledBeyondBudget.countDown();
                    }
                    scheduledFirstWave.countDown();
                    if (!releaseScheduled.await(20, TimeUnit.SECONDS)) throw new IOException("Scheduled responses were never released");
                }
                List<JSONObject> items = new ArrayList<>();
                items.add(item("shared", keyword, "2021"));
                items.add(item("wrong-year", keyword, "1984"));
                items.add(item("wrong-title", "完美世界", "2021"));
                if (keyword.equals("Dune")) items.add(item("alias-only", "Dune", "2021"));
                return scheduled ? json(items, scheduledResponsesClosed) : json(items);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Local fixture failure");
            }
        }

        private JSONObject item(String id, String title, String year) throws Exception {
            return new JSONObject().put("vod_id", id).put("vod_name", title).put("vod_year", year)
                    .put("vod_pic", "http://127.0.0.1:" + getListeningPort() + "/poster.png").put("type_name", "电影");
        }

        private Response json(List<JSONObject> items) throws Exception {
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", response(items));
        }

        private Response json(List<JSONObject> items, CountDownLatch closed) throws Exception {
            byte[] data = response(items).getBytes(StandardCharsets.UTF_8);
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", new ByteArrayInputStream(data) {
                @Override public void close() throws IOException {
                    try { super.close(); }
                    finally { closed.countDown(); }
                }
            }, data.length);
        }

        private String response(List<JSONObject> items) throws Exception {
            return new JSONObject().put("page", 1).put("pagecount", 1).put("list", new JSONArray(items)).toString();
        }
    }
}
