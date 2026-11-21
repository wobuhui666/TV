package com.github.catvod.net;

import android.annotation.SuppressLint;

import androidx.collection.ArrayMap;

import com.github.catvod.bean.Doh;
import com.github.catvod.net.ech.ConscryptEchSocketFactory;
import com.github.catvod.net.ech.EchDnsResolver;
import com.github.catvod.net.ech.EchSettings;
import com.github.catvod.net.interceptor.AuthInterceptor;
import com.github.catvod.net.interceptor.RequestInterceptor;
import com.github.catvod.net.interceptor.ResponseInterceptor;

import org.conscrypt.Conscrypt;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

public class OkHttp {

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(30);

    private ResponseInterceptor responseInterceptor;
    private RequestInterceptor requestInterceptor;
    private AuthInterceptor authInterceptor;
    private OkAuthenticator authenticator;
    private OkProxySelector selector;
    private volatile OkHttpClient client;
    private volatile OkHttpClient player;
    private volatile OkHttpClient trusted;
    private final Object echLock = new Object();
    private final OkDns dns = new OkDns();
    private final CloudflarePreferredInterceptor preferred = new CloudflarePreferredInterceptor(dns);
    private final IdleConnectionEvictor poolEvictor = new IdleConnectionEvictor(
            () -> { if (client != null) client.connectionPool().evictAll(); },
            () -> { if (player != null) player.connectionPool().evictAll();
                if (trusted != null) trusted.connectionPool().evictAll(); });
    private EchResolverState echResolver;
    private long echGeneration;

    public static OkHttp get() {
        return Loader.INSTANCE;
    }

    public static OkDns dns() {
        return get().dns;
    }

    public static ResponseInterceptor responseInterceptor() {
        if (get().responseInterceptor != null) return get().responseInterceptor;
        return get().responseInterceptor = new ResponseInterceptor();
    }

    public static RequestInterceptor requestInterceptor() {
        if (get().requestInterceptor != null) return get().requestInterceptor;
        return get().requestInterceptor = new RequestInterceptor();
    }

    public static AuthInterceptor authInterceptor() {
        if (get().authInterceptor != null) return get().authInterceptor;
        return get().authInterceptor = new AuthInterceptor();
    }

    public static OkAuthenticator authenticator() {
        if (get().authenticator != null) return get().authenticator;
        return get().authenticator = new OkAuthenticator(selector());
    }

    public static OkProxySelector selector() {
        if (get().selector != null) return get().selector;
        return get().selector = new OkProxySelector();
    }

    public static synchronized OkHttpClient client() {
        if (get().client != null) return get().client;
        return get().client = getBuilder().build();
    }

    public static synchronized OkHttpClient player() {
        if (get().player != null) return get().player;
        return get().player = getBuilder().build();
    }

    /** Metadata/images use platform trust while retaining the configured DNS, ECH and proxy. */
    public static synchronized OkHttpClient trustedClient() {
        if (get().trusted != null) return get().trusted;
        // Startup ordering and instrumentation can initialize a client before OkHttp's provider.
        android.content.Context context = com.github.catvod.Init.context();
        if (context != null) okhttp3.OkHttp.INSTANCE.initialize(context);
        OkHttpClient platform = new OkHttpClient();
        return get().trusted = getBuilder(platform.x509TrustManager())
                .hostnameVerifier(platform.hostnameVerifier())
                .dispatcher(client().dispatcher()).build();
    }

    public static OkHttpClient client(long timeout) {
        return client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).build();
    }

    public static OkHttpClient noRedirect() {
        return noRedirect(TIMEOUT);
    }

    public static OkHttpClient noRedirect(long timeout) {
        OkHttpClient.Builder builder = client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false);
        builder.interceptors().removeIf(item -> item instanceof ProxyRedirectInterceptor);
        return builder.build();
    }

    public static OkHttpClient client(boolean redirect, long timeout) {
        return redirect ? client(timeout) : noRedirect(timeout);
    }

    public static String string(String url) {
        if (!url.startsWith("http")) return "";
        try (Response res = newCall(url).execute()) {
            return res.body().string();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static String string(String url, Map<String, String> headers) {
        if (!url.startsWith("http")) return "";
        try (Response res = newCall(url, headers).execute()) {
            return res.body().string();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static Call newCall(String url) {
        return client().newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(String url, String tag) {
        return client().newCall(new Request.Builder().url(url).tag(tag).build());
    }

    public static Call newCall(OkHttpClient client, String url) {
        return client.newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(OkHttpClient client, String url, String tag) {
        return client.newCall(new Request.Builder().url(url).tag(tag).build());
    }

    public static Call newCall(String url, Map<String, String> headers) {
        return client().newCall(new Request.Builder().url(url).headers(Headers.of(headers)).build());
    }

    public static Call newCall(String url, Map<String, String> headers, ArrayMap<String, String> params) {
        return client().newCall(new Request.Builder().url(buildUrl(url, params)).headers(Headers.of(headers)).build());
    }

    public static Call newCall(String url, Map<String, String> headers, RequestBody body) {
        return client().newCall(new Request.Builder().url(url).headers(Headers.of(headers)).post(body).build());
    }

    public static Call newCall(String url, RequestBody body, String tag) {
        return client().newCall(new Request.Builder().url(url).post(body).tag(tag).build());
    }

    public static Call newCall(OkHttpClient client, String url, RequestBody body) {
        return client.newCall(new Request.Builder().url(url).post(body).build());
    }

    public static void cancel(String tag) {
        cancel(client(), tag);
    }

    public static void cancel(OkHttpClient client, String tag) {
        for (Call call : client.dispatcher().queuedCalls()) if (tag.equals(call.request().tag())) call.cancel();
        for (Call call : client.dispatcher().runningCalls()) if (tag.equals(call.request().tag())) call.cancel();
    }

    public static void cancelAll() {
        cancelAll(client());
    }

    public static void cancelAll(OkHttpClient client) {
        client.dispatcher().cancelAll();
    }

    public static FormBody toBody(ArrayMap<String, String> params) {
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) body.add(entry.getKey(), entry.getValue());
        return body.build();
    }

    private static HttpUrl buildUrl(String url, ArrayMap<String, String> params) {
        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) builder.addQueryParameter(entry.getKey(), entry.getValue());
        return builder.build();
    }

    private static OkHttpClient.Builder getBuilder() {
        return getBuilder(trustAllCertificates());
    }

    private static OkHttpClient.Builder getBuilder(X509TrustManager trustManager) {
        OkProxySelector selector = selector();
        ConscryptEchSocketFactory sockets = new ConscryptEchSocketFactory(
                getSSLContext(trustManager).getSocketFactory(), trustManager,
                new ConscryptEchSocketFactory.ConfigProvider() {
                    @Override
                    public boolean isEnabled() {
                        return EchSettings.isEnabled();
                    }

                    @Override
                    public byte[] resolve(String hostname) {
                        EchDnsResolver resolver = echResolver();
                        return resolver == null ? null : resolver.resolveWithCloudflareFallback(hostname);
                    }
                });
        OkHttpClient.Builder builder = new OkHttpClient.Builder().addInterceptor(requestInterceptor()).addInterceptor(authInterceptor()).addInterceptor(new ProxyRedirectInterceptor(selector)).addInterceptor(get().preferred).addNetworkInterceptor(get().preferred.networkInterceptor()).addNetworkInterceptor(responseInterceptor()).connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS).readTimeout(TIMEOUT, TimeUnit.MILLISECONDS).writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS).dns(dns()).hostnameVerifier((hostname, session) -> true).sslSocketFactory(sockets, trustManager).followRedirects(false);
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY);
        builder.proxyAuthenticator(authenticator());
        //builder.addNetworkInterceptor(logging);
        builder.proxySelector(selector);
        return builder;
    }

    private static EchDnsResolver echResolver() {
        OkHttp instance = get();
        while (true) {
            long generation;
            synchronized (instance.echLock) {
                generation = instance.echGeneration;
            }
            // Keep DNS initialization outside echLock: explicit DNS changes take the DNS lock first.
            Doh selection = dns().getDoh();
            String url = selection.getUrl();
            List<String> ips = new ArrayList<>(selection.getIps());
            synchronized (instance.echLock) {
                // A settings change must not be overwritten by an older DNS snapshot.
                if (generation != instance.echGeneration) continue;
                EchResolverState state = instance.echResolver;
                if (state == null || !state.url.equals(url) || !state.ips.equals(ips)) {
                    EchDnsResolver resolver = null;
                    try {
                        OkHttpClient bootstrap = new OkHttpClient.Builder()
                                .proxySelector(selector()).proxyAuthenticator(authenticator()).build();
                        resolver = new EchDnsResolver(bootstrap, url, selection.getHosts());
                    } catch (IllegalArgumentException ignored) {
                        // An unsupported DoH configuration falls back to ordinary TLS.
                    }
                    state = new EchResolverState(url, ips, resolver);
                    instance.echResolver = state;
                }
                return state.resolver;
            }
        }
    }

    /** Apply to new connections without interrupting an active playback or download. */
    public static void echConfigurationChanged() {
        OkHttp instance = get();
        instance.preferred.clear();
        synchronized (instance.echLock) {
            instance.echGeneration++;
            instance.echResolver = null;
        }
        // TLS close may write close_notify. Never close sockets on the settings/UI thread.
        instance.poolEvictor.request();
    }

    private static final class EchResolverState {
        final String url;
        final List<String> ips;
        final EchDnsResolver resolver;

        EchResolverState(String url, List<String> ips, EchDnsResolver resolver) {
            this.url = url;
            this.ips = ips;
            this.resolver = resolver;
        }
    }

    private static SSLContext getSSLContext(X509TrustManager trustManager) {
        try {
            SSLContext context = SSLContext.getInstance("TLS", Conscrypt.newProvider());
            context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize TLS", e);
        }
    }

    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    private static X509TrustManager trustAllCertificates() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    public void clear() {
        cancelAll();
        dns().clear();
        selector().clear();
        authInterceptor().clear();
        requestInterceptor().clear();
        responseInterceptor().clear();
    }

    private static class Loader {
        static volatile OkHttp INSTANCE = new OkHttp();
    }
}
