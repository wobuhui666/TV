package com.fongmi.android.tv.utils;

import com.github.catvod.net.CloudflareRouteFallback;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** TMDB metadata and artwork share a bounded, certificate-verified transport. */
public final class TmdbNetwork {
    private static final String KEY = "tmdb_route_recovery";
    private static final CloudflareRouteFallback RECOVERY = new CloudflareRouteFallback(
            TmdbNetwork::isRouteRecoveryEnabled, TmdbNetwork::isTmdbUrl,
            host -> OkHttp.dns().hasOverride(host));
    private static volatile OkHttpClient client;

    private TmdbNetwork() { }

    public static boolean isRouteRecoveryEnabled() { return Prefers.getBoolean(KEY, true); }

    public static void setRouteRecoveryEnabled(boolean enabled) {
        Prefers.put(KEY, enabled);
        RECOVERY.clear();
    }

    public static boolean isTmdbUrl(HttpUrl url) {
        HttpUrl root = HttpUrl.parse(TmdbEndpoint.getEffectiveRoot());
        if (root == null || !root.scheme().equals(url.scheme()) || !root.host().equals(url.host())
                || root.port() != url.port()) return false;
        String path = root.encodedPath();
        return url.encodedPath().startsWith(path + "3/") || url.encodedPath().startsWith(path + "t/p/");
    }

    public static Call newCall(Request request) {
        return (isTmdbUrl(request.url()) ? client() : OkHttp.client()).newCall(request);
    }

    /** The TMDB transport has its own dispatcher; cancel without creating a client. */
    public static void cancel(Object tag) {
        if (tag == null) return;
        cancel(OkHttp.client(), tag);
        OkHttpClient tmdb = client;
        if (tmdb != null && tmdb != OkHttp.client()) cancel(tmdb, tag);
    }

    private static void cancel(OkHttpClient transport, Object tag) {
        for (Call call : transport.dispatcher().queuedCalls()) if (tag.equals(call.request().tag())) call.cancel();
        for (Call call : transport.dispatcher().runningCalls()) if (tag.equals(call.request().tag())) call.cancel();
    }

    private static synchronized OkHttpClient client() {
        if (client == null) client = OkHttp.trustedClient().newBuilder()
                .connectTimeout(3500, TimeUnit.MILLISECONDS).readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).followSslRedirects(false)
                .addInterceptor(RECOVERY).build();
        return client;
    }
}
