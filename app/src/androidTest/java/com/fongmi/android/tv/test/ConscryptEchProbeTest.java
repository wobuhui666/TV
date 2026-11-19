package com.fongmi.android.tv.test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.github.catvod.net.ech.ConscryptEchSocketFactory;
import com.github.catvod.net.ech.EchDnsResolver;
import com.github.catvod.net.ech.EchSettings;
import com.github.catvod.bean.Doh;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.conscrypt.Conscrypt;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.CookieJar;
import okhttp3.Credentials;
import okhttp3.EventListener;
import okhttp3.Dispatcher;
import okhttp3.Handshake;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TlsVersion;

/** Separate strict-client and shared-transport probes; only isolated packages are permitted. */
@RunWith(AndroidJUnit4.class)
public final class ConscryptEchProbeTest {
    private static final String DOH_URL = "https://cloudflare-dns.com/dns-query";
    private static final long TOTAL_MS = 90_000L;
    private static final int MAX_TRACE_BYTES = 32 * 1024;
    private long deadline;
    private boolean proxyFixture;
    private final AtomicInteger fixtureAuthReplies = new AtomicInteger();

    @Test(timeout = 60_000L)
    public void factoryOffAndOnProducePlaintextAndEncryptedServerEvidence() throws Exception {
        long started = SystemClock.elapsedRealtime();
        deadline = started + TOTAL_MS - 3000L;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("ECH probe requires an isolated sourceprobe or netprobe package",
                "com.fongmi.android.tv.sourceprobe".equals(context.getPackageName())
                        || "com.fongmi.android.tv.netprobe".equals(context.getPackageName()));
        proxyFixture = "fixture".equals(proxyMode());
        long budget = 60_000L;
        deadline = started + budget - 3000L;
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IllegalStateException("REPORT_DIRECTORY_UNAVAILABLE");
        File file = new File(new File(external, "ech-validation"), "conscrypt-probe.json");
        JSONArray phases = new JSONArray();
        JSONObject report = new JSONObject().put("schema", "tv.conscrypt-ech-probe.v1")
                .put("state", "RUNNING").put("accepted", false).put("sdk", Build.VERSION.SDK_INT)
                .put("artifact", "org.conscrypt:conscrypt-android")
                .put("budgetMs", budget).put("dnsQueryType", 65)
                .put("proxyFixture", proxyFixture).put("proxyType", proxyFixture ? "HTTP" : "DIRECT")
                .put("evidenceBasis", "SERVER_TRACE_SAME_TLS_RESPONSE")
                .put("publicClientAcceptedApiAvailable", false)
                .put("customHostnameVerifier", false).put("customTrustAcceptance", false)
                .put("preferencesReadOrWritten", false).put("originCredentialsAdded", false)
                .put("fixtureProxyAuthenticationEnabled", proxyFixture)
                .put("redirectsAllowed", false).put("phases", phases);
        writeReport(file, report);
        OkHttpClient base = null;
        OkHttpClient doh = null;
        boolean passed = false;
        try {
            Conscrypt.checkAvailability();
            Conscrypt.Version version = Conscrypt.version();
            String actual = version.major() + "." + version.minor() + "." + version.patch();
            report.put("version", actual).put("artifact", "org.conscrypt:conscrypt-android:" + actual);
            assertTrue("The probe requires pinned Conscrypt 2.9.0", "2.9.0".equals(actual));
            report.put("trustRejectionPropagation", verifyTrustRejection());
            base = plainBuilder().build();
            // This transport never uses the ECH factory: DoH cannot recurse through itself.
            doh = plainBuilder().callTimeout(12, TimeUnit.SECONDS).build();
            String dohMode = dohMode();
            report.put("dohMode", dohMode).put("resolver", "PRODUCTION_ECH_DNS_RESOLVER");
            EchDnsResolver lookup = new EchDnsResolver(doh, dohUrl(dohMode));
            validatePublishedConfiguration(lookup, report);

            JSONObject disabled = runPhase(base, lookup, false, 20_000L, false);
            phases.put(disabled);
            writeReport(file, report);
            JSONObject enabled = runPhase(base, lookup, true, 25_000L, false);
            phases.put(enabled);
            passed = disabled.optBoolean("passed") && enabled.optBoolean("passed")
                    && (!proxyFixture || fixtureAuthReplies.get() > 0);
            report.put("state", passed ? "PASS" : "FAIL").put("accepted", passed)
                    .put("enabledPhaseAccepted", enabled.optBoolean("passed"));
            report.put("offOnControlPassed", passed);
        } catch (Throwable error) {
            report.put("state", "FAIL").put("errorType", error.getClass().getSimpleName());
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            closeClient(doh);
            closeClient(base);
            report.put("testOwnedClientsReleased", (base == null || base.dispatcher().executorService().isShutdown())
                    && (doh == null || doh.dispatcher().executorService().isShutdown()));
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started);
            report.put("fixtureAuthenticationReplies", fixtureAuthReplies.get());
            report.put("budgetExceeded", remaining() == 0);
            writeReport(file, report);
        }
        assertTrue("Conscrypt ECH off/on was not proven; see ech-validation/conscrypt-probe.json", passed);
    }

    @Test(timeout = TOTAL_MS)
    public void sharedSpiderClientUsesProductionEchAndRestoresSettings() throws Exception {
        long started = SystemClock.elapsedRealtime();
        deadline = started + TOTAL_MS - 3000L;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Shared ECH probe requires an isolated sourceprobe or netprobe package",
                "com.fongmi.android.tv.sourceprobe".equals(context.getPackageName())
                        || "com.fongmi.android.tv.netprobe".equals(context.getPackageName()));
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IllegalStateException("REPORT_DIRECTORY_UNAVAILABLE");
        File file = new File(new File(external, "ech-validation"), "conscrypt-shared-probe.json");
        JSONArray phases = new JSONArray();
        JSONObject report = new JSONObject().put("schema", "tv.conscrypt-shared-ech-probe.v1")
                .put("state", "RUNNING").put("accepted", false).put("sdk", Build.VERSION.SDK_INT)
                .put("budgetMs", TOTAL_MS).put("phases", phases)
                .put("evidenceBasis", "SERVER_TRACE_SAME_TLS_RESPONSE")
                .put("transport", "SPIDER_SHARED_FACTORY_DNS_AND_INTERCEPTORS")
                .put("certificateValidationScope", "EXISTING_SHARED_POLICY_NOT_A_SECURITY_VALIDATION")
                .put("publicClientAcceptedApiAvailable", false)
                .put("proxyProbeExecuted", false).put("credentialsAdded", false)
                .put("settingsRestored", false).put("dohRestored", false);
        writeReport(file, report);

        SharedPreferences preferences = Prefers.getPrefers();
        boolean hadEnabled = preferences.contains("ech_enabled");
        Object oldEnabled = preferences.getAll().get("ech_enabled");
        assertTrue("Unexpected ECH preference type", !hadEnabled || oldEnabled instanceof Boolean);
        Doh originalDoh = OkHttp.dns().getDoh();
        boolean passed = false;
        boolean restored = false;
        try {
            if (!"direct".equals(proxyMode())) throw new IllegalArgumentException("SHARED_PROXY_FIXTURE_UNSUPPORTED");
            String mode = dohMode();
            report.put("dohMode", mode);
            OkHttpClient validationClient = plainBuilder().build();
            try {
                validatePublishedConfiguration(new EchDnsResolver(validationClient, dohUrl(mode)), report);
            } finally {
                closeClient(validationClient);
            }
            // Only the in-memory DoH selection changes; no stored source/DoH configuration is edited.
            OkHttp.dns().setDoh(new Doh().name("ECH probe").url("default".equals(mode) ? "" : dohUrl(mode)));
            OkHttpClient shared = Spider.client();
            assertSame("Spider must expose the actual shared host client", OkHttp.client(), shared);
            assertTrue("Shared transport must contain the production ECH factory",
                    shared.sslSocketFactory() instanceof ConscryptEchSocketFactory);
            report.put("sharedClientIdentityVerified", true).put("productionFactoryInstalled", true);

            EchSettings.setEnabled(false);
            OkHttp.echConfigurationChanged();
            JSONObject disabled = runPhase(shared, null, false, 30_000L, true);
            phases.put(disabled);
            writeReport(file, report);
            EchSettings.setEnabled(true);
            OkHttp.echConfigurationChanged();
            JSONObject enabled = runPhase(shared, null, true, 40_000L, true);
            phases.put(enabled);
            passed = disabled.optBoolean("passed") && enabled.optBoolean("passed");
            report.put("enabledPhaseAccepted", enabled.optBoolean("passed"));
        } catch (Throwable error) {
            report.put("errorType", error.getClass().getSimpleName());
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            boolean settingsRestored = false;
            boolean dohRestored = false;
            try {
                SharedPreferences.Editor edit = preferences.edit();
                if (hadEnabled) edit.putBoolean("ech_enabled", (Boolean) oldEnabled);
                else edit.remove("ech_enabled");
                settingsRestored = edit.commit()
                        && preferences.contains("ech_enabled") == hadEnabled
                        && (!hadEnabled || oldEnabled.equals(preferences.getAll().get("ech_enabled")));
            } catch (Throwable error) {
                report.put("settingsRestoreError", error.getClass().getSimpleName());
            }
            try {
                OkHttp.dns().setDoh(originalDoh);
                dohRestored = originalDoh.toString().equals(OkHttp.dns().getDoh().toString());
                OkHttp.echConfigurationChanged();
            } catch (Throwable error) {
                report.put("dohRestoreError", error.getClass().getSimpleName());
            }
            restored = settingsRestored && dohRestored;
            passed &= restored && remaining() > 0;
            report.put("settingsRestored", settingsRestored).put("dohRestored", dohRestored)
                    .put("state", passed ? "PASS" : "FAIL").put("accepted", passed)
                    .put("offOnControlPassed", passed)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("budgetExceeded", remaining() == 0);
            writeReport(file, report);
        }
        assertTrue("Shared-client ECH or restoration failed; see ech-validation/conscrypt-shared-probe.json", passed && restored);
    }

    private JSONObject runPhase(OkHttpClient base, EchDnsResolver lookup, boolean enabled, long timeoutMs, boolean shared)
            throws Exception {
        long started = SystemClock.elapsedRealtime();
        JSONObject result = new JSONObject().put("enabled", enabled).put("passed", false)
                .put("state", "NOT_STARTED").put("traceSni", "missing")
                .put("phaseTimeoutMs", timeoutMs);
        if (remaining() < 2000L) return result.put("state", "BUDGET_EXHAUSTED");
        PhaseEvents events = new PhaseEvents(proxyFixture);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger configBytes = new AtomicInteger();
        int authenticationBefore = fixtureAuthReplies.get();
        java.util.concurrent.atomic.AtomicReference<String> dnsReason =
                new java.util.concurrent.atomic.AtomicReference<>(shared ? "SHARED_RESOLVER_NOT_INSTRUMENTED" : "NOT_QUERIED");
        ConscryptEchSocketFactory.ConfigProvider provider = new ConscryptEchSocketFactory.ConfigProvider() {
            @Override public boolean isEnabled() { return enabled; }

            @Override public byte[] resolve(String hostname) throws IOException {
                resolutions.incrementAndGet();
                if (!host().equals(hostname)) throw new IOException("UNEXPECTED_ECH_HOST");
                byte[] config = lookup.resolveWithCloudflareFallback(hostname);
                configBytes.set(config == null ? 0 : config.length);
                dnsReason.set(lookup.lastReason());
                return config;
            }
        };
        OkHttpClient.Builder builder = shared ? base.newBuilder() : plainBuilder()
                .sslSocketFactory(new ConscryptEchSocketFactory(
                        base.sslSocketFactory(), base.x509TrustManager(), provider), base.x509TrustManager());
        OkHttpClient client = builder
                .dispatcher(new Dispatcher()).proxy(probeProxy())
                .authenticator(Authenticator.NONE).proxyAuthenticator(probeProxyAuthenticator())
                .cookieJar(CookieJar.NO_COOKIES).cache(null)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .addNetworkInterceptor(chain -> {
                    Request request = chain.request();
                    if (!traceUrl().equals(request.url().toString()) || request.header("Authorization") != null
                            || request.header("Cookie") != null || request.header("Proxy-Authorization") != null)
                        throw new IOException("PROBE_REQUEST_GUARD_REJECTED");
                    Response response = chain.proceed(request);
                    if (response.isRedirect()) {
                        response.close();
                        throw new IOException("PROBE_REDIRECT_BLOCKED");
                    }
                    return response;
                })
                .connectionSpecs(Collections.singletonList(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_3).build()))
                .callTimeout(Math.min(timeoutMs, remaining() - 1000L), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool()).eventListener(events).build();
        result.put("transportScope", shared ? "SPIDER_SHARED_TRANSPORT" : "STRICT_STANDALONE_CLIENT");
        result.put("hostnameVerifierRetained", client.hostnameVerifier() == base.hostnameVerifier());
        result.put("sharedFactoryPreserved", shared && client.sslSocketFactory() == base.sslSocketFactory());
        result.put("sharedDnsPreserved", shared && client.dns() == base.dns());
        result.put("sharedApplicationInterceptorsPreserved", shared && client.interceptors().equals(base.interceptors()));
        result.put("originalTrustManagerRetained", client.x509TrustManager() == base.x509TrustManager());
        try (Response response = client.newCall(new Request.Builder().url(traceUrl()).build()).execute()) {
            result.put("httpStatus", response.code());
            result.put("protocol", response.protocol().toString());
            result.put("redirectReceived", response.isRedirect());
            result.put("cacheResponsePresent", response.cacheResponse() != null);
            boolean sameTarget = traceUrl().equals(response.request().url().toString());
            result.put("sameTargetResponse", sameTarget);
            Handshake handshake = response.handshake();
            boolean tls13 = handshake != null && handshake.tlsVersion() == TlsVersion.TLS_1_3;
            result.put("tlsVersion", handshake == null ? "missing" : handshake.tlsVersion().javaName());
            result.put("peerCertificateCount", handshake == null ? 0 : handshake.peerCertificates().size());
            String traceSni = "missing";
            boolean traceHostMatches = false;
            if (response.body() != null) {
                byte[] body = readBounded(response.body().byteStream(), MAX_TRACE_BYTES);
                result.put("responseBytes", body.length);
                traceSni = traceSni(body);
                traceHostMatches = traceHostMatches(body);
            }
            result.put("traceSni", traceSni).put("traceHostMatches", traceHostMatches);
            boolean expectedSni = (enabled ? "encrypted" : "plaintext").equals(traceSni);
            boolean configPath = enabled ? events.conscryptSocket
                    && (shared || resolutions.get() > 0 && configBytes.get() > 0
                    && (requiresBorrowing() ? "cloudflare_ech_fallback" : "ech_config_available").equals(dnsReason.get()))
                    : shared || resolutions.get() == 0;
            boolean sharedPath = !shared || client.sslSocketFactory() == base.sslSocketFactory()
                    && client.dns() == base.dns() && base == Spider.client() && base == OkHttp.client();
            boolean alpnMatches = response.protocol() != Protocol.HTTP_2 || !enabled || "h2".equals(events.alpn);
            boolean ok = response.code() == 200 && !response.isRedirect() && sameTarget
                    && response.cacheResponse() == null && tls13 && expectedSni && traceHostMatches && configPath && alpnMatches
                    && events.secureEnds.get() == 1 && events.connections.get() == 1 && events.routeMatches && sharedPath;
            result.put("passed", ok).put("state", ok ? "PASS" : "EVIDENCE_MISMATCH")
                    .put("alpnMatchesHttp2", alpnMatches);
        } catch (Throwable error) {
            result.put("state", "REQUEST_FAILED").put("errorType", error.getClass().getSimpleName());
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            closeClient(client);
            boolean socketClosed = events.socket == null || events.socket.isClosed();
            result.put("conscryptSocket", events.conscryptSocket).put("alpn", events.alpn)
                    .put("direct", events.direct).put("proxyFixture", proxyFixture)
                    .put("proxyType", events.proxyType).put("expectedRouteVerified", events.routeMatches)
                    .put("socketClosed", socketClosed)
                    .put("secureConnectStarts", events.secureStarts.get())
                    .put("secureConnectEnds", events.secureEnds.get())
                    .put("connectionsAcquired", events.connections.get())
                    .put("fixtureAuthenticationReplies", fixtureAuthReplies.get() - authenticationBefore)
                    .put("configResolveCalls", shared ? JSONObject.NULL : resolutions.get())
                    .put("echConfigBytes", shared ? JSONObject.NULL : configBytes.get())
                    .put("dnsReason", dnsReason.get() == null ? "UNKNOWN" : safeReason(dnsReason.get()))
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started);
            if (!socketClosed || remaining() == 0) result.put("passed", false)
                    .put("state", socketClosed ? "BUDGET_EXHAUSTED" : "SOCKET_NOT_RELEASED");
        }
        return result;
    }

    private static String targetMode() {
        String mode = InstrumentationRegistry.getArguments().getString("ech_target_mode", "cloudflare_fallback");
        if (!"cloudflare_fallback".equals(mode) && !"cloudflare_apex".equals(mode) && !"published".equals(mode))
            throw new IllegalArgumentException("UNSUPPORTED_TARGET_TEST_MODE");
        return mode;
    }

    private static boolean requiresBorrowing() {
        return !"published".equals(targetMode());
    }

    private static String host() {
        return switch (targetMode()) {
            case "published" -> "crypto.cloudflare.com";
            case "cloudflare_apex" -> "cloudflare.com";
            default -> "www.cloudflare.com";
        };
    }

    private static String traceUrl() {
        return "https://" + host() + "/cdn-cgi/trace";
    }

    private static void validatePublishedConfiguration(EchDnsResolver lookup, JSONObject report) throws Exception {
        byte[] published = lookup.resolve(host());
        String reason = lookup.lastReason();
        report.put("targetHost", host()).put("cloudflareFallbackRequired", requiresBorrowing())
                .put("targetPublishedConfigBytes", published == null ? 0 : published.length)
                .put("targetPublishedDnsReason", reason)
                .put("fallbackConfigOwner", requiresBorrowing() ? "crypto.cloudflare.com" : JSONObject.NULL);
        if (requiresBorrowing()) {
            assertTrue("Borrowing control must have no published ECH config",
                    published == null && ("no_ech".equals(reason) || "no_https_record".equals(reason)));
        } else {
            assertTrue("Published control must advertise its own ECH config", published != null);
        }
    }

    private static String dohMode() {
        String mode = InstrumentationRegistry.getArguments().getString("ech_doh_mode", "default");
        if (!"default".equals(mode) && !"cloudflare".equals(mode) && !"alidns".equals(mode) && !"tencent".equals(mode) && !"360".equals(mode))
            throw new IllegalArgumentException("UNSUPPORTED_DOH_TEST_MODE");
        return mode;
    }

    private static String dohUrl(String mode) {
        return switch (mode) {
            case "cloudflare" -> DOH_URL;
            case "alidns" -> "https://dns.alidns.com/dns-query";
            case "tencent" -> "https://doh.pub/dns-query";
            case "360" -> "https://doh.360.cn/dns-query";
            default -> EchDnsResolver.DEFAULT_DOH_URL;
        };
    }

    private static String proxyMode() {
        String mode = InstrumentationRegistry.getArguments().getString("ech_proxy_mode", "direct");
        if (!"direct".equals(mode) && !"fixture".equals(mode))
            throw new IllegalArgumentException("UNSUPPORTED_PROXY_TEST_MODE");
        return mode;
    }

    private Proxy probeProxy() {
        return proxyFixture ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress("100.101.70.109", 8871)) : Proxy.NO_PROXY;
    }

    private Authenticator probeProxyAuthenticator() {
        if (!proxyFixture) return Authenticator.NONE;
        return (route, response) -> {
            if (route == null || !isFixtureProxy(route.proxy()) || response.code() != 407
                    || response.request().header("Proxy-Authorization") != null
                    || response.priorResponse() != null) return null;
            boolean basic = response.challenges().stream().anyMatch(challenge -> "Basic".equalsIgnoreCase(challenge.scheme()));
            if (!basic) return null; // Includes OkHttp's preemptive pseudo-challenge.
            fixtureAuthReplies.incrementAndGet();
            // Public, task-owned fixture credentials. Never use a user credential or report this header.
            return response.request().newBuilder()
                    .header("Proxy-Authorization", Credentials.basic("ech-test", "ech-test-only")).build();
        };
    }

    private static boolean isFixtureProxy(Proxy proxy) {
        if (proxy.type() != Proxy.Type.HTTP || !(proxy.address() instanceof InetSocketAddress)) return false;
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        return address.getPort() == 8871 && "100.101.70.109".equals(address.getHostString());
    }

    private static final class PhaseEvents extends EventListener {
        final AtomicInteger secureStarts = new AtomicInteger();
        final AtomicInteger secureEnds = new AtomicInteger();
        final AtomicInteger connections = new AtomicInteger();
        private final boolean expectedFixture;
        volatile boolean conscryptSocket, direct, routeMatches;
        volatile String proxyType = "UNOBSERVED";
        volatile String alpn = "unavailable";
        volatile Socket socket;

        PhaseEvents(boolean expectedFixture) { this.expectedFixture = expectedFixture; }

        @Override public void secureConnectStart(Call call) { secureStarts.incrementAndGet(); }
        @Override public void secureConnectEnd(Call call, Handshake handshake) { secureEnds.incrementAndGet(); }

        @Override public void connectionAcquired(Call call, Connection connection) {
            connections.incrementAndGet();
            socket = connection.socket();
            direct = connection.route().proxy().type() == Proxy.Type.DIRECT;
            proxyType = connection.route().proxy().type().name();
            routeMatches = expectedFixture ? isFixtureProxy(connection.route().proxy()) : direct;
            conscryptSocket = socket instanceof SSLSocket && Conscrypt.isConscrypt((SSLSocket) socket);
            if (conscryptSocket) {
                String value = Conscrypt.getApplicationProtocol((SSLSocket) socket);
                alpn = "h2".equals(value) || "http/1.1".equals(value) ? value : "unnegotiated";
            }
        }
    }

    private static JSONObject verifyTrustRejection() throws Exception {
        CertificateException rejection = new CertificateException("PROBE_REJECT_CERTIFICATE");
        X509Certificate[] chain = new X509Certificate[0];
        AtomicInteger calls = new AtomicInteger();
        X509ExtendedTrustManager rejecting = new X509ExtendedTrustManager() {
            private void reject(X509Certificate[] actual) throws CertificateException {
                assertSame(chain, actual);
                calls.incrementAndGet();
                throw rejection;
            }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { reject(c); }
            @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { reject(c); }
            @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { reject(c); }
            @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { reject(c); }
            @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { reject(c); }
            @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { reject(c); }
            @Override public X509Certificate[] getAcceptedIssuers() { return chain; }
        };
        ConscryptEchSocketFactory.PolicyTrustManager wrapper = new ConscryptEchSocketFactory.PolicyTrustManager(rejecting);
        expectRejection(() -> wrapper.checkServerTrusted(chain, "RSA"), rejection);
        expectRejection(() -> wrapper.checkServerTrusted(chain, "RSA", (Socket) null), rejection);
        expectRejection(() -> wrapper.checkServerTrusted(chain, "RSA", (SSLEngine) null), rejection);
        assertTrue("All server trust overloads must preserve rejection", calls.get() == 3);
        return new JSONObject().put("scope", "OFFLINE_DELEGATION_ONLY")
                .put("basic", true).put("socket", true).put("engine", true);
    }

    private interface TrustCheck { void run() throws CertificateException; }

    private static void expectRejection(TrustCheck check, CertificateException expected) throws Exception {
        try {
            check.run();
            throw new AssertionError("Certificate rejection was swallowed");
        } catch (CertificateException actual) {
            assertSame("The original rejection must propagate unchanged", expected, actual);
        }
    }

    private OkHttpClient.Builder plainBuilder() {
        return new OkHttpClient.Builder().proxy(probeProxy()).authenticator(Authenticator.NONE)
                .proxyAuthenticator(probeProxyAuthenticator()).cookieJar(CookieJar.NO_COOKIES).cache(null)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS);
    }

    private static void closeClient(OkHttpClient client) {
        if (client == null) return;
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("RESPONSE_SIZE_LIMIT");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static boolean traceHostMatches(byte[] body) {
        int fields = 0;
        boolean matches = false;
        for (String line : new String(body, StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (line.startsWith("h=")) {
                fields++;
                matches = host().equals(line.substring(2));
            }
        }
        return fields == 1 && matches;
    }

    private static String traceSni(byte[] body) {
        String value = "missing";
        int fields = 0;
        for (String line : new String(body, StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (line.startsWith("sni=")) {
                fields++;
                String field = line.substring(4);
                value = "encrypted".equals(field) || "plaintext".equals(field) ? field : "other";
            }
        }
        return fields > 1 ? "ambiguous" : value;
    }

    private static String safeReason(String reason) {
        return reason.matches("[A-Za-z_]{1,80}") ? reason : "UNCLASSIFIED";
    }

    private long remaining() { return Math.max(0L, deadline - SystemClock.elapsedRealtime()); }

    private static void writeReport(File file, JSONObject report) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("REPORT_DIRECTORY_UNAVAILABLE");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
