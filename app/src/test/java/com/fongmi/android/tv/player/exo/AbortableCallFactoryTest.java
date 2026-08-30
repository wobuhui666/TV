package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AbortableCallFactoryTest {

    private static final Request REQUEST = new Request.Builder().url("https://example.com/video.mp4").build();

    @Test
    public void shouldCancelCallsCreatedBeforeAbort() {
        AbortableCallFactory factory = new AbortableCallFactory(new OkHttpClient());
        Call call = factory.newCall(REQUEST);

        assertFalse(call.isCanceled());
        factory.abort();

        assertTrue(call.isCanceled());
    }

    @Test
    public void shouldReturnCanceledCallAfterAbort() {
        AbortableCallFactory factory = new AbortableCallFactory(new OkHttpClient());

        factory.abort();
        Call call = factory.newCall(REQUEST);

        assertTrue(call.isCanceled());
    }

    @Test
    public void shouldNotLetCreationRaceEscapeAbort() throws Exception {
        BlockingCallFactory upstream = new BlockingCallFactory();
        AbortableCallFactory factory = new AbortableCallFactory(upstream);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Call> create = executor.submit(() -> factory.newCall(REQUEST));
            assertTrue(upstream.started.await(1, TimeUnit.SECONDS));
            Future<?> abort = executor.submit(factory::abort);

            upstream.proceed.countDown();
            Call call = create.get(1, TimeUnit.SECONDS);
            abort.get(1, TimeUnit.SECONDS);

            assertTrue(call.isCanceled());
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class BlockingCallFactory implements Call.Factory {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);
        private final OkHttpClient client = new OkHttpClient();

        @Override
        public Call newCall(Request request) {
            Call call = client.newCall(request);
            started.countDown();
            try {
                proceed.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return call;
        }
    }
}
