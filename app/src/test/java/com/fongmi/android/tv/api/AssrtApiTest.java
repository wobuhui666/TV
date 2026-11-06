package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.AssrtSubtitle;
import com.fongmi.android.tv.bean.AssrtSubtitleFile;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AssrtApiTest {

    @Test
    public void shouldSendBearerInHeaderAndAllowShortChineseTitles() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(search(123, "棋魂")));
            AssrtApi.Client client = client(server, new AtomicLong());
            SearchCapture result = new SearchCapture();
            client.search(" 棋魂 ", 15, new Object(), result);
            result.await();

            assertNull(result.error);
            assertEquals("棋魂", result.items.get(0).getTitle());
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("GET", request.getMethod());
            assertEquals("/v1/sub/search", request.getRequestUrl().encodedPath());
            assertEquals("棋魂", request.getRequestUrl().queryParameter("q"));
            assertEquals("15", request.getRequestUrl().queryParameter("pos"));
            assertEquals("15", request.getRequestUrl().queryParameter("cnt"));
            assertEquals("1", request.getRequestUrl().queryParameter("filelist"));
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"));
            assertNull(request.getRequestUrl().queryParameter("token"));
            assertFalse(request.getPath().contains("fixture-token"));
        }
    }

    @Test
    public void shouldParseMetadataAndKeepSearchDownloadLinksEmpty() throws Exception {
        String body = "{\"status\":0,\"sub\":{\"subs\":[{\"id\":123,\"native_name\":\"Show S02E03\","
                + "\"videoname\":\"Show.S02E03.1080p\",\"subtype\":\"Subrip(srt)\",\"release_site\":\"Group\","
                + "\"upload_time\":\"2026-11-06\",\"lang\":{\"desc\":\"双语\"},\"filelist\":["
                + "{\"f\":\"Show.S02E03.srt\",\"s\":\"52KB\",\"url\":\"https://file.assrt.net/temporary\"}]}]}}";
        AssrtSubtitle subtitle = AssrtApi.parseSearch(body).get(0);

        assertEquals(123, subtitle.getId());
        assertEquals("双语", subtitle.getLanguageText());
        assertEquals("Group", subtitle.getReleaseSite());
        assertEquals("Show.S02E03.1080p", subtitle.getReleaseName());
        assertEquals("Subrip(srt)", subtitle.getFormat());
        assertEquals("2026-11-06", subtitle.getUploadTime());
        assertEquals("Show.S02E03.srt", subtitle.getFiles().get(0).getName());
        assertEquals("52KB", subtitle.getFiles().get(0).getSizeText());
        assertEquals(0, subtitle.getFiles().get(0).getSizeBytes());
        assertEquals("", subtitle.getFiles().get(0).getUrl());
    }

    @Test
    public void shouldSelectTheRequestedDetailAndRejectUnsafeDownloadSchemes() throws Exception {
        String body = "{\"status\":0,\"sub\":{\"subs\":[{\"id\":1,\"native_name\":\"Other\"},"
                + "{\"id\":123,\"native_name\":\"Show\",\"filelist\":["
                + "{\"f\":\"one.ASS\",\"s\":1234,\"url\":\"https://file.assrt.net/one.ass?expires=1\"},"
                + "{\"f\":\"two.srt\",\"s\":\"3KB\",\"url\":\"file:///private/two.srt\"}] }]}}";
        AssrtSubtitle subtitle = AssrtApi.parseDetail(body, 123);

        assertEquals(123, subtitle.getId());
        assertEquals(2, subtitle.getFiles().size());
        assertEquals("ass", subtitle.getFiles().get(0).getFormat());
        assertEquals(1234, subtitle.getFiles().get(0).getSizeBytes());
        assertEquals("https://file.assrt.net/one.ass?expires=1", subtitle.getFiles().get(0).getUrl());
        assertEquals("", subtitle.getFiles().get(1).getUrl());
    }

    @Test
    public void shouldUseDirectFileWhenAFileListIsAbsent() throws Exception {
        String body = "{\"status\":0,\"sub\":{\"subs\":[{\"id\":123,\"filename\":\"Show.zip\","
                + "\"size\":42,\"url\":\"https://file.assrt.net/show.zip\"}]}}";
        AssrtSubtitle subtitle = AssrtApi.parseDetail(body, 123);
        AssrtSubtitleFile file = subtitle.getFiles().get(0);

        assertEquals("Show.zip", subtitle.getTitle());
        assertEquals("Show.zip", file.getName());
        assertEquals(42, file.getSizeBytes());
        assertEquals("https://file.assrt.net/show.zip", file.getUrl());
    }

    @Test
    public void shouldKeepDirectFilesFirstAndOfferTheFreshOriginalArchive() throws Exception {
        String body = "{\"status\":0,\"sub\":{\"subs\":[{\"id\":123,\"filename\":\"Provider label\","
                + "\"size\":23000000,\"url\":\"http://file.assrt.net/Original%20package.RAR?expires=1\","
                + "\"filelist\":[{\"f\":\"Show.srt\",\"s\":\"52KB\",\"url\":\"https://file.assrt.net/onthefly/Show.srt?expires=1\"}]}]}}";
        List<AssrtSubtitleFile> files = AssrtApi.parseDetail(body, 123).getFiles();
        assertEquals(2, files.size());
        assertEquals("Show.srt", files.get(0).getName());
        assertEquals("Original package.RAR", files.get(1).getName());
        assertEquals("rar", files.get(1).getFormat());
        assertEquals(23000000, files.get(1).getSizeBytes());
        assertEquals("http://file.assrt.net/Original%20package.RAR?expires=1", files.get(1).getUrl());

        List<AssrtSubtitleFile> searchFiles = AssrtApi.parseSearch(body).get(0).getFiles();
        assertEquals(1, searchFiles.size());
        assertEquals("", searchFiles.get(0).getUrl());
    }

    @Test
    public void shouldNotDuplicateArchivesOrExposeNonHttpAndNonArchiveAlternatives() throws Exception {
        for (String url : new String[]{"https://file.assrt.net/Show.zip?expires=1", "file:///private/Show.rar", "https://file.assrt.net/download/Show.bin"}) {
            String body = "{\"status\":0,\"sub\":{\"subs\":[{\"id\":123,\"url\":\"" + url + "\","
                    + "\"filelist\":[{\"f\":\"Show.zip\",\"s\":\"52KB\",\"url\":\"https://file.assrt.net/Show.zip?expires=1\"}]}]}}";
            List<AssrtSubtitleFile> files = AssrtApi.parseDetail(body, 123).getFiles();
            assertEquals(1, files.size());
            assertEquals("https://file.assrt.net/Show.zip?expires=1", files.get(0).getUrl());
        }
    }

    @Test
    public void shouldReturnEmptySearchResultsButNotHideMissingResponseFields() throws Exception {
        assertTrue(AssrtApi.parseSearch("{\"status\":0,\"sub\":{\"subs\":[]}}").isEmpty());
        for (String body : new String[]{"{}", "[]", "{\"status\":0}", "{\"status\":0.5,\"sub\":{\"subs\":[]}}", "broken"}) {
            try {
                AssrtApi.parseSearch(body);
                fail("Malformed responses must not become a successful empty list");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    @Test
    public void shouldIgnoreInvalidIdsAndTolerateOptionalMetadata() throws Exception {
        List<AssrtSubtitle> subtitles = AssrtApi.parseSearch("{\"status\":0,\"sub\":{\"subs\":["
                + "{\"id\":0},{\"id\":-1},{\"id\":\"invalid\"},{\"id\":123,\"videoname\":\"Show\"}]}}");
        assertEquals(1, subtitles.size());
        assertEquals("Show", subtitles.get(0).getTitle());
        assertEquals("", subtitles.get(0).getLanguageText());
        assertTrue(subtitles.get(0).getFiles().isEmpty());
        try {
            AssrtApi.parseDetail("{\"status\":0,\"sub\":{\"subs\":[{\"id\":124}]}}", 123);
            fail("A different subtitle id must not be returned");
        } catch (AssrtApi.ApiException expected) {
            assertEquals(20900, expected.getCode());
        }
    }

    @Test
    public void shouldCacheSearchMetadataAndExpireItWithoutCachingDetailUrls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicLong clock = new AtomicLong();
            AssrtApi.Client client = client(server, clock);
            server.enqueue(json(search(123, "Show")));
            SearchCapture first = new SearchCapture();
            client.search("Show", 0, new Object(), first);
            first.await();
            SearchCapture cached = new SearchCapture();
            client.search(" Show ", 0, new Object(), cached);
            cached.await();
            assertEquals(1, server.getRequestCount());

            server.enqueue(json(detail(123, "https://file.assrt.net/one?expires=1")));
            server.enqueue(json(detail(123, "https://file.assrt.net/two?expires=2")));
            DetailCapture one = new DetailCapture();
            client.detail(123, new Object(), one);
            one.await();
            DetailCapture two = new DetailCapture();
            client.detail(123, new Object(), two);
            two.await();
            assertEquals("https://file.assrt.net/one?expires=1", one.subtitle.getFiles().get(0).getUrl());
            assertEquals("https://file.assrt.net/two?expires=2", two.subtitle.getFiles().get(0).getUrl());
            assertEquals(3, server.getRequestCount());

            clock.set(5 * 60_000);
            server.enqueue(json(search(124, "New upload")));
            SearchCapture expired = new SearchCapture();
            client.search("Show", 0, new Object(), expired);
            expired.await();
            assertEquals(124, expired.items.get(0).getId());
            assertEquals(4, server.getRequestCount());
        }
    }

    @Test
    public void shouldSuppressNetworkAndCachedCallbacksCancelledBeforeUiDelivery() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(search(123, "Show")));
            BlockingQueue<Runnable> dispatch = new LinkedBlockingQueue<>();
            AssrtApi.Client client = new AssrtApi.Client(server.url("/v1").toString(), "fixture-token",
                    new OkHttpClient()::newCall, dispatch::add, () -> 0);
            Object tag = new Object();
            AtomicInteger callbacks = new AtomicInteger();
            AssrtApi.SearchListener listener = new AssrtApi.SearchListener() {
                @Override public void onSuccess(List<AssrtSubtitle> items) { callbacks.incrementAndGet(); }
                @Override public void onError(Exception error) { callbacks.incrementAndGet(); }
            };
            client.search("Show", 0, tag, listener);
            Runnable networkResult = dispatch.poll(5, TimeUnit.SECONDS);
            assertNotNull(networkResult);
            client.cancel(tag);
            networkResult.run();
            assertEquals(0, callbacks.get());

            client.search("Show", 0, tag, listener);
            Runnable cachedResult = dispatch.poll(1, TimeUnit.SECONDS);
            assertNotNull(cachedResult);
            client.cancel(tag);
            cachedResult.run();
            assertEquals(0, callbacks.get());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldCancelInFlightDetailWithoutAnErrorCallback() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(detail(123, "https://file.assrt.net/one")).setBodyDelay(1, TimeUnit.SECONDS));
            AssrtApi.Client client = client(server, new AtomicLong());
            DetailCapture result = new DetailCapture();
            Object tag = new Object();
            client.detail(123, tag, result);
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
            client.cancel(tag);
            assertFalse(result.completed.await(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void shouldClassifyQuotaAndInvalidTokenWithoutExposingServerEchoedSecrets() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AssrtApi.Client client = client(server, new AtomicLong());
            server.enqueue(json("{\"status\":30900,\"message\":\"fixture-token secret\"}"));
            SearchCapture rate = new SearchCapture();
            client.search("Show", 0, new Object(), rate);
            rate.await();
            assertTrue(rate.error instanceof AssrtApi.ApiException);
            assertTrue(((AssrtApi.ApiException) rate.error).isRateLimited());
            assertFalse(rate.error.getMessage().contains("fixture-token"));

            server.enqueue(json("{\"status\":20001}").setResponseCode(403));
            SearchCapture invalid = new SearchCapture();
            client.search("Other", 0, new Object(), invalid);
            invalid.await();
            assertEquals(20001, ((AssrtApi.ApiException) invalid.error).getCode());

            server.enqueue(new MockResponse().setResponseCode(503).setBody("not JSON"));
            SearchCapture unavailable = new SearchCapture();
            client.search("Another", 0, new Object(), unavailable);
            unavailable.await();
            assertTrue(unavailable.error.getMessage().contains("HTTP 503"));
        }
    }

    @Test
    public void shouldRejectMissingCredentialEmptyQueryAndUnsafeHeaderValuesWithoutRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String token : new String[]{"", "Bearer ", "invalid\r\nHeader"}) {
                AssrtApi.Client client = new AssrtApi.Client(server.url("/v1").toString(), token,
                        new OkHttpClient()::newCall, Runnable::run, () -> 0);
                SearchCapture result = new SearchCapture();
                client.search("Show", 0, new Object(), result);
                result.await();
                assertEquals(20001, ((AssrtApi.ApiException) result.error).getCode());
            }
            SearchCapture empty = new SearchCapture();
            client(server, new AtomicLong()).search("  ", 0, new Object(), empty);
            empty.await();
            assertEquals(101, ((AssrtApi.ApiException) empty.error).getCode());
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test
    public void shouldKeepLocalRequestsWithinTheDocumentedPerMinuteQuota() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicLong clock = new AtomicLong();
            AssrtApi.Client client = client(server, clock);
            for (int index = 0; index < 20; index++) {
                server.enqueue(json(search(123, "Show")));
                SearchCapture result = new SearchCapture();
                client.search("Show " + index, 0, new Object(), result);
                result.await();
                assertNull(result.error);
            }
            SearchCapture exhausted = new SearchCapture();
            client.search("Another", 0, new Object(), exhausted);
            exhausted.await();
            assertEquals(30900, ((AssrtApi.ApiException) exhausted.error).getCode());
            assertEquals(20, server.getRequestCount());
            clock.set(60_000);
            server.enqueue(json(search(124, "Another")));
            SearchCapture renewed = new SearchCapture();
            client.search("Another", 0, new Object(), renewed);
            renewed.await();
            assertNull(renewed.error);
            assertEquals(21, server.getRequestCount());
        }
    }

    @Test
    public void shouldBoundUnexpectedlyLargeApiResponses() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)));
            SearchCapture result = new SearchCapture();
            client(server, new AtomicLong()).search("Show", 0, new Object(), result);
            result.await();
            assertNotNull(result.error);
            assertTrue(result.error.getMessage().contains("too large"));
        }
    }

    private static AssrtApi.Client client(MockWebServer server, AtomicLong clock) {
        return new AssrtApi.Client(server.url("/v1/").toString(), "Bearer fixture-token",
                new OkHttpClient()::newCall, Runnable::run, clock::get);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String search(long id, String title) {
        return "{\"status\":0,\"sub\":{\"subs\":[{\"id\":" + id + ",\"native_name\":\"" + title + "\"}]}}";
    }

    private static String detail(long id, String url) {
        return "{\"status\":0,\"sub\":{\"subs\":[{\"id\":" + id + ",\"native_name\":\"Show\","
                + "\"filelist\":[{\"f\":\"Show.srt\",\"s\":\"52KB\",\"url\":\"" + url + "\"}]}]}}";
    }

    private static final class SearchCapture implements AssrtApi.SearchListener {
        final CountDownLatch completed = new CountDownLatch(1);
        volatile List<AssrtSubtitle> items;
        volatile Exception error;
        @Override public void onSuccess(List<AssrtSubtitle> items) { this.items = items; completed.countDown(); }
        @Override public void onError(Exception error) { this.error = error; completed.countDown(); }
        void await() throws InterruptedException { assertTrue("ASSRT callback timed out", completed.await(5, TimeUnit.SECONDS)); }
    }

    private static final class DetailCapture implements AssrtApi.DetailListener {
        final CountDownLatch completed = new CountDownLatch(1);
        volatile AssrtSubtitle subtitle;
        volatile Exception error;
        @Override public void onSuccess(AssrtSubtitle subtitle) { this.subtitle = subtitle; completed.countDown(); }
        @Override public void onError(Exception error) { this.error = error; completed.countDown(); }
        void await() throws InterruptedException { assertTrue("ASSRT callback timed out", completed.await(5, TimeUnit.SECONDS)); }
    }
}
