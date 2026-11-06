package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.setting.Setting;
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
            () -> isRouteRecoveryEnabled() && !TmdbEndpoint.isDirect(), TmdbNetwork::isTmdbUrl,
            host -> OkHttp.dns().hasOverride(host));
    private static volatile OkHttpClient client;

    private TmdbNetwork() { }

    public static boolean isRouteRecoveryEnabled() { return Prefers.getBoolean(KEY, true); }

    public static void setRouteRecoveryEnabled(boolean enabled) {
        Prefers.put(KEY, enabled);
        clearRouteCache();
    }

    public static void clearRouteCache() {
        RECOVERY.clear();
    }

    public static boolean isTmdbUrl(HttpUrl url) {
        return isTmdbUrl(url, Setting.getTmdbConnectionMode(), TmdbEndpoint.getCustomRoot());
    }

    static boolean isTmdbUrl(HttpUrl url, int mode, String customRoot) {
        return matchesBase(url, TmdbEndpoint.getApiBase(mode, customRoot))
                || matchesBase(url, TmdbEndpoint.getImageBase(mode, customRoot));
    }

    private static boolean matchesBase(HttpUrl url, String base) {
        HttpUrl root = HttpUrl.parse(base);
        return url != null && root != null && root.scheme().equals(url.scheme()) && root.host().equals(url.host())
                && root.port() == url.port() && url.encodedPath().startsWith(root.encodedPath());
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
