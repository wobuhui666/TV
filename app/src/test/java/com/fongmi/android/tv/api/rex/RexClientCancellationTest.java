package com.fongmi.android.tv.api.rex;

import com.github.catvod.net.OkHttp;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RexClientCancellationTest {

    @Test(timeout = 10_000)
    public void shouldCancelFeedAfterHeadersWithoutDeliveringErrorOrFallback() throws Exception {
        CountDownLatch readingBody = new CountDownLatch(1);
        CountDownLatch idle = new CountDownLatch(1);
        AtomicReference<Call> active = new AtomicReference<>();
        AtomicInteger replies = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().eventListener(new EventListener() {
            @Override
            public void responseBodyStart(Call call) {
                active.set(call);
                readingBody.countDown();
            }
        }).build();
        client.dispatcher().setIdleCallback(idle::countDown);
        Field transport = OkHttp.class.getDeclaredField("client");
        transport.setAccessible(true);
        Object previous = transport.get(OkHttp.get());
        Object tag = new Object();
        try (MockWebServer server = new MockWebServer()) {
            transport.set(OkHttp.get(), client);
            server.enqueue(new MockResponse().setBody("{\"items\":[]}").setBodyDelay(2, TimeUnit.SECONDS));
            server.start();
            DoubanBridgeClient.fetchCatalog(server.url("/").toString(), null, null, "test", 0, tag, new DoubanBridgeClient.Listener() {
                @Override
                public void onSuccess(java.util.List<com.fongmi.android.tv.bean.Vod> items) {
                    replies.incrementAndGet();
                }

                @Override
                public void onError(Exception error) {
                    replies.incrementAndGet();
                }

                @Override
                public void onUnauthorized() {
                    replies.incrementAndGet();
                }
            });
            assertTrue(readingBody.await(3, TimeUnit.SECONDS));

            DoubanBridgeClient.cancel(tag);

            assertTrue(active.get().isCanceled());
            assertTrue(idle.await(3, TimeUnit.SECONDS));
            assertEquals(0, replies.get());
        } finally {
            DoubanBridgeClient.cancel(tag);
            transport.set(OkHttp.get(), previous);
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }
}
