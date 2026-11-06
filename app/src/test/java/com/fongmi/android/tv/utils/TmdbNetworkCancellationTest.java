package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.api.DiscoverApi;
import com.github.catvod.net.OkHttp;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.*;

public class TmdbNetworkCancellationTest {
    @Test(timeout = 10_000)
    public void shouldCancelTheRequestedTagAcrossBothTransportDispatchers() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        OkHttpClient base = new OkHttpClient.Builder().addInterceptor(chain -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            throw new java.io.IOException("Synthetic transport cancelled");
        }).build();
        OkHttpClient tmdb = base.newBuilder().dispatcher(new okhttp3.Dispatcher()).build();
        Field baseField = OkHttp.class.getDeclaredField("client");
        Field tmdbField = TmdbNetwork.class.getDeclaredField("client");
        baseField.setAccessible(true);
        tmdbField.setAccessible(true);
        Object oldBase = baseField.get(OkHttp.get());
        Object oldTmdb = tmdbField.get(null);
        Object tag = new Object();
        Call baseCall = base.newCall(new Request.Builder().url("https://fixture.invalid/base").tag(tag).build());
        Call tmdbCall = tmdb.newCall(new Request.Builder().url("https://fixture.invalid/tmdb").tag(tag).build());
        Call unrelated = tmdb.newCall(new Request.Builder().url("https://fixture.invalid/other").tag(new Object()).build());
        Callback ignored = new Callback() {
            @Override public void onFailure(Call call, java.io.IOException error) {}
            @Override public void onResponse(Call call, Response response) { response.close(); }
        };
        try {
            baseField.set(OkHttp.get(), base);
            tmdbField.set(null, tmdb);
            baseCall.enqueue(ignored);
            tmdbCall.enqueue(ignored);
            assertTrue(entered.await(3, TimeUnit.SECONDS));

            DiscoverApi.cancel(tag);

            assertTrue(baseCall.isCanceled());
            assertTrue(tmdbCall.isCanceled());
            assertFalse(unrelated.isCanceled());
        } finally {
            release.countDown();
            base.dispatcher().cancelAll();
            tmdb.dispatcher().cancelAll();
            baseField.set(OkHttp.get(), oldBase);
            tmdbField.set(null, oldTmdb);
            base.connectionPool().evictAll();
            tmdb.connectionPool().evictAll();
            base.dispatcher().executorService().shutdownNow();
            tmdb.dispatcher().executorService().shutdownNow();
        }
    }
}
