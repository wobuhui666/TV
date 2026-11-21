package com.fongmi.android.tv.api.rex;

import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.net.OkHttp;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RexRegionalFeedTest {

    @Test(timeout = 10_000)
    public void shouldKeepAnEmptyChineseSeriesFeedSeparateFromWesternPrograms() throws Exception {
        FetchResult result = fetch("series", "current", "missing");
        assertNull(result.error);
        assertTrue(result.items.isEmpty());
        assertEquals(1 + RexFeedDate.MAX_BACKTRACK_DAYS, result.paths.size());
        for (String path : result.paths) {
            assertTrue(path.endsWith("latest.json") || path.endsWith("chinese_series.json"));
        }
    }

    @Test(timeout = 10_000)
    public void shouldKeepAShortChineseVarietyFeedWithoutWesternPadding() throws Exception {
        FetchResult result = fetch("variety", "current", "one");
        assertNull(result.error);
        assertEquals(1, result.items.size());
        assertEquals("tmdb:tv:1", result.items.get(0).getId());
        assertEquals(2, result.paths.size());
        assertTrue(result.paths.get(1).endsWith("chinese_variety.json"));
    }

    @Test(timeout = 10_000)
    public void shouldTryTodayWhenManifestIsUnavailableOrStale() throws Exception {
        for (String manifest : List.of("failed", "stale")) {
            FetchResult result = fetch("series", manifest, "one");
            assertNull(result.error);
            assertEquals(1, result.items.size());
            assertTrue(result.paths.get(1).contains("/" + RexFeedDate.compact(System.currentTimeMillis()) + "/"));
            assertEquals(2, result.paths.size());
        }
    }

    @Test(timeout = 10_000)
    public void shouldStopDateRetriesOnTransportFailureToAllowTheLoaderFallback() throws Exception {
        FetchResult result = fetch("series", "current", "transport-error");
        assertNotNull(result.error);
        assertEquals(2, result.paths.size());
    }

    private static FetchResult fetch(String category, String manifest, String feed) throws Exception {
        FetchResult result = new FetchResult();
        CountDownLatch reply = new CountDownLatch(1);
        CountDownLatch idle = new CountDownLatch(1);
        AtomicReference<List<Vod>> items = new AtomicReference<>(List.of());
        AtomicReference<Exception> error = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            result.paths.add(path);
            int code = 200;
            String body;
            if (path.endsWith("latest.json")) {
                if (manifest.equals("failed")) code = 503;
                String date = manifest.equals("stale") ? "20000101" : RexFeedDate.compact(System.currentTimeMillis());
                body = "{\"schemaVersion\":1,\"date\":\"" + date + "\"}";
            } else {
                if (feed.equals("transport-error")) throw new IOException("offline");
                if (feed.equals("missing")) code = 404;
                body = "{\"schemaVersion\":1,\"region\":\"chinese\",\"category\":\"" + category
                        + "\",\"items\":[{\"id\":\"1\",\"title\":\"当期节目\",\"mediaType\":\"tv\"}]}";
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        client.dispatcher().setIdleCallback(idle::countDown);
        Field transport = OkHttp.class.getDeclaredField("client");
        transport.setAccessible(true);
        Object previous = transport.get(OkHttp.get());
        Object tag = new Object();
        try {
            transport.set(OkHttp.get(), client);
            RexNowClient.fetchRegional("chinese", category, tag, new RexNowClient.ItemsListener() {
                @Override public void onSuccess(List<Vod> value) { items.set(value); reply.countDown(); }
                @Override public void onError(Exception value) { error.set(value); reply.countDown(); }
            });
            assertTrue("Regional response arrives", reply.await(4, TimeUnit.SECONDS));
            assertTrue("Feed transport finishes", idle.await(4, TimeUnit.SECONDS));
            result.items = items.get();
            result.error = error.get();
            return result;
        } finally {
            RexNowClient.cancel(tag);
            transport.set(OkHttp.get(), previous);
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    private static final class FetchResult {
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private List<Vod> items;
        private Exception error;
    }
}
