package com.fongmi.android.tv.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import androidx.test.filters.SdkSuppress;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.github.catvod.net.OkHttp;
import com.github.catvod.net.ech.ConscryptEchSocketFactory;
import com.github.catvod.net.ech.EchSettings;
import com.github.catvod.utils.Prefers;

import org.conscrypt.Conscrypt;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TlsVersion;

/** Opt-in, public-network TLS diagnostic. It never launches UI or invokes a source. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 31)
public final class PreviewConscryptNativeProbeAndroidTest {
    private static final String[] HOSTS = {"crypto.cloudflare.com", "www.cloudflare.com"};
    private static final String VERSION = "2.9.0";
    private static final String X86_JNI_SHA256 =
            "1018d9c3878e0bba16aa038b2875506efb31459bb2b6b0b4c7f72418d9408fa8";
    private static final AtomicInteger FINALIZED = new AtomicInteger();
    private final ReferenceQueue<Object> released = new ReferenceQueue<>();
    private final List<WatchedReference> watched = new ArrayList<>();
    private File directory;
    private JSONObject report;
    private CopyGroup platform;
    private CopyGroup bundled;
    private int closedSockets;
    private int collected;
    private int registryTrue;
    private int registryFalse;
    private int registryUnavailable;
    private int finalizersBefore;

    @Test(timeout = 900_000L)
    public void nativeX86TlsAndBothCertificateProvidersSurviveExplicitRelease() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Explicit library native probe only",
                "true".equals(args.getString("library_native_probe", "false")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String packageName = context.getPackageName();
        assertTrue("Only an isolated Preview application may run this diagnostic", packageName.endsWith(".preview"));
        int expectedApi = Integer.parseInt(args.getString("probe_expected_api", "31"));
        String sourceSha = args.getString("probe_source_sha", "UNSPECIFIED");
        assertTrue("Source SHA must be an actual commit or explicitly unspecified",
                "UNSPECIFIED".equals(sourceSha) || sourceSha.matches("[0-9a-f]{40}"));
        File external = context.getExternalFilesDir(null);
        assertTrue("External report directory must exist", external != null);
        directory = new File(external, "ech-validation");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("NO_REPORT_DIRECTORY");
        SharedPreferences preferences = Prefers.getPrefers();
        boolean hadEchKey = preferences.contains("ech_enabled");
        String originalDoh = OkHttp.dns().getDoh().toString();
        finalizersBefore = FINALIZED.get();
        report = new JSONObject().put("schema", "tv.preview-conscrypt-native-probe.v1")
                .put("state", "RUNNING").put("phase", "BOOTSTRAP")
                .put("packageName", packageName).put("sourceSha", sourceSha)
                .put("pid", Process.myPid()).put("sdk", Build.VERSION.SDK_INT)
                .put("expectedSdk", expectedApi).put("mode", "TLS_ONLY_NO_SOURCE_UI")
                .put("uiLaunchedByProbe", false).put("realSourceRequestsByProbe", 0)
                .put("traceBodyStored", false).put("encodedCertificatesStored", false)
                .put("credentialHeadersAllowed", false).put("customTrustAcceptance", false)
                .put("preferencesWritten", false).put("echKeyInitiallyAbsent", !hadEchKey)
                .put("explicitGcCalls", 0).put("requests", new JSONArray()).put("gcRounds", new JSONArray())
                .put("runtime", new JSONObject()).put("registryCallbackCompletionProven", false)
                .put("coverageLimit", "FINITE_TLS_AND_EXPLICIT_GC; NOT_REAL_SOURCE_OR_NATURAL_RECLAIM_ACCEPTANCE");
        writeReport();
        boolean passed = false;
        try {
            assertEquals("Compare the requested Android API", expectedApi, Build.VERSION.SDK_INT);
            assertTrue("A genuinely absent ECH key must use the production enabled default",
                    !hadEchKey && EchSettings.isEnabled());
            Conscrypt.checkAvailability();
            Conscrypt.Version version = Conscrypt.version();
            String actualVersion = version.major() + "." + version.minor() + "." + version.patch();
            assertEquals("Load the pinned Conscrypt artifact", VERSION, actualVersion);
            report.put("artifact", "org.conscrypt:conscrypt-android:" + actualVersion);
            captureNativeRuntime(context);
            platform = new CopyGroup("PLATFORM", "com.android.org.conscrypt.OpenSSLX509Certificate",
                    CertificateFactory.getInstance("X.509"));
            bundled = new CopyGroup("BUNDLED", "org.conscrypt.OpenSSLX509Certificate",
                    CertificateFactory.getInstance("X.509", Conscrypt.newProvider()));
            assertTrue("The default platform and bundled providers must be distinct",
                    !platform.factory.getProvider().getClass().getName()
                            .equals(bundled.factory.getProvider().getClass().getName()));
            report.put("certificateGroups", new JSONArray().put(platform.json()).put(bundled.json()));
            OkHttpClient base = OkHttp.trustedClient();
            assertTrue("Retain the actual production ECH factory",
                    base.sslSocketFactory() instanceof ConscryptEchSocketFactory);
            report.put("productionFactoryClass", base.sslSocketFactory().getClass().getName())
                    .put("certificatePolicy", "PRODUCTION_TRUSTED_METADATA_CLIENT");
            for (String host : HOSTS) request(base, host, 0);
            for (int round = 1; round <= 4; round++) {
                report.put("phase", "TLS_COPY_BATCH_" + round);
                writeReport();
                for (int index = 0; index < 8; index++) {
                    request(base, HOSTS[index % HOSTS.length], 16);
                    SystemClock.sleep(250);
                }
                report.getJSONArray("gcRounds").put(collectRound(round));
                writeReport();
            }
            assertTrue("Allocate at least 512 real platform native-backed certificate copies", platform.allocated >= 512);
            assertTrue("Allocate at least 512 real bundled native-backed certificate copies", bundled.allocated >= 512);
            assertEquals("Observe every successful fresh TLS socket closing", 34, closedSockets);
            assertTrue("Collect at least 90% of platform copy owners", platform.collected * 10 >= platform.allocated * 9);
            assertTrue("Collect at least 90% of bundled copy owners", bundled.collected * 10 >= bundled.allocated * 9);
            assertTrue("Execute real finalizer witnesses in this invocation", FINALIZED.get() - finalizersBefore >= 4);
            assertTrue("Do not change the ECH default", !preferences.contains("ech_enabled") && EchSettings.isEnabled());
            assertEquals("Do not change DoH selection", originalDoh, OkHttp.dns().getDoh().toString());
            report.put("phase", "SAME_PID_CLEANUP_TAIL");
            writeReport();
            SystemClock.sleep(15_000);
            drainReleased();
            passed = true;
        } catch (Throwable error) {
            report.put("errorTypes", exceptionTypes(error)).put("failurePhase", report.optString("phase"));
            throw error;
        } finally {
            boolean unchanged = preferences.contains("ech_enabled") == hadEchKey
                    && originalDoh.equals(OkHttp.dns().getDoh().toString());
            report.put("state", passed && unchanged ? "PASS" : "FAIL").put("phase", "FINISHED")
                    .put("pidAfter", Process.myPid()).put("echAndDohUnchanged", unchanged)
                    .put("closedTlsSockets", closedSockets).put("weakReferencesTracked", watched.size())
                    .put("weakReferencesCollected", collected)
                    .put("finalizerWitnessCount", FINALIZED.get() - finalizersBefore)
                    .put("nativeAllocationRegistryTrue", registryTrue)
                    .put("nativeAllocationRegistryFalse", registryFalse)
                    .put("nativeAllocationRegistryUnavailable", registryUnavailable)
                    .put("registryPathCovered", registryTrue > 0)
                    .put("gcConclusion", "EXPLICIT_GC_AND_FINALIZER_WITNESSES_DO_NOT_PROVE_EACH_NATIVE_FREE");
            if (platform != null && bundled != null) {
                report.put("certificateGroups", new JSONArray().put(platform.json()).put(bundled.json()));
            }
            writeReport();
            assertTrue("Keep test-observed settings unchanged", unchanged);
        }
    }

    private void captureNativeRuntime(Context context) throws Exception {
        File library = new File(context.getApplicationInfo().nativeLibraryDir, "libconscrypt_jni.so");
        assertTrue("Use extracted JNI from the isolated app", library.isFile());
        byte[] header = new byte[20];
        try (FileInputStream input = new FileInputStream(library)) {
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) throw new IOException("TRUNCATED_ELF");
                offset += count;
            }
        }
        assertTrue("A real little-endian ELF64 library is required",
                header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                        && header[4] == 2 && header[5] == 1);
        int machine = (header[18] & 255) | ((header[19] & 255) << 8);
        String nativeSha = sha256(library);
        JSONArray mappings = new JSONArray();
        boolean x86Mapped = false;
        boolean armMapped = false;
        boolean bridgeMapped = false;
        String maps = readSmall(new FileInputStream("/proc/self/maps"), 4 * 1024 * 1024);
        for (String line : maps.split("\\n")) {
            if (line.contains("libndk_translation.so") || line.contains("libhoudini.so")) bridgeMapped = true;
            if (!line.contains("libconscrypt_jni.so")) continue;
            boolean x86 = line.contains("/x86_64/");
            boolean arm = line.contains("/arm64/") || line.contains("/arm64-v8a/");
            x86Mapped |= x86;
            armMapped |= arm;
            mappings.put(new JSONObject().put("basename", "libconscrypt_jni.so")
                    .put("pathContainsX86_64", x86).put("pathContainsArm64", arm));
        }
        String arch = System.getProperty("os.arch", "UNKNOWN");
        File platformCrypto = new File("/apex/com.android.conscrypt/lib64/libcrypto.so");
        JSONObject runtime = new JSONObject().put("osArch", arch).put("is64Bit", Process.is64Bit())
                .put("supportedAbis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)))
                .put("buildFingerprint", Build.FINGERPRINT).put("securityPatch", Build.VERSION.SECURITY_PATCH)
                .put("platformCryptoSha256", platformCrypto.isFile() ? sha256(platformCrypto) : "UNAVAILABLE")
                .put("nativeBridgeProperty", systemProperty("ro.dalvik.vm.native.bridge"))
                .put("nativeBridgeMapped", bridgeMapped).put("conscryptElfMachine", machine)
                .put("conscryptJniSha256", nativeSha).put("conscryptMappings", mappings)
                .put("conscryptNativeX86Mapped", x86Mapped).put("conscryptArmMapped", armMapped)
                .put("installedApkSha256", sha256(new File(context.getApplicationInfo().sourceDir)));
        report.put("runtime", runtime);
        writeReport();
        assertTrue("Run a native x86_64 process", Process.is64Bit() && ("x86_64".equals(arch) || "amd64".equals(arch)));
        assertEquals("Use x86_64 ELF, not a translated ARM Conscrypt library", 62, machine);
        assertEquals("Use the pinned genuine x86_64 Conscrypt JNI bytes", X86_JNI_SHA256, nativeSha);
        assertTrue("Observe that JNI mapped from x86_64 and no ARM Conscrypt path", x86Mapped && !armMapped);
    }

    private void request(OkHttpClient base, String host, int copies) throws Exception {
        Events events = new Events();
        String url = "https://" + host + "/cdn-cgi/trace";
        OkHttpClient client = base.newBuilder().proxy(Proxy.NO_PROXY)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES).cache(null).dispatcher(new Dispatcher())
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .callTimeout(20, TimeUnit.SECONDS).eventListener(events)
                .addNetworkInterceptor(chain -> {
                    Request current = chain.request();
                    if (!url.equals(current.url().toString()) || current.header("Cookie") != null
                            || current.header("Authorization") != null || current.header("Proxy-Authorization") != null) {
                        throw new IOException("CREDENTIAL_OR_ORIGIN_GUARD_REJECTED");
                    }
                    return chain.proceed(current);
                }).build();
        assertTrue("Preserve production factory, strict trust, hostname checks, and DNS",
                client.sslSocketFactory() == base.sslSocketFactory()
                        && client.x509TrustManager() == base.x509TrustManager()
                        && client.hostnameVerifier() == base.hostnameVerifier() && client.dns() == base.dns());
        JSONObject result = new JSONObject().put("host", host).put("copiesPerPeerPerProvider", copies)
                .put("state", "RUNNING").put("credentialsAdded", false)
                .put("productionFactoryPreserved", true).put("peerCertificates", new JSONArray());
        report.getJSONArray("requests").put(result);
        report.put("phase", "TLS_CONNECT");
        writeReport();
        int platformBefore = platform.allocated;
        int bundledBefore = bundled.allocated;
        Set<Certificate> fresh = Collections.newSetFromMap(new IdentityHashMap<Certificate, Boolean>());
        Set<Long> platformContexts = new HashSet<>();
        Set<Long> bundledContexts = new HashSet<>();
        try (Response response = client.newCall(new Request.Builder().url(url).header("Connection", "close").build()).execute()) {
            Handshake handshake = response.handshake();
            assertTrue("Require same-target, uncached TLS1.3 HTTP200",
                    response.code() == 200 && response.cacheResponse() == null && !response.isRedirect()
                            && url.equals(response.request().url().toString())
                            && handshake != null && handshake.tlsVersion() == TlsVersion.TLS_1_3);
            String trace = readSmall(response.body().byteStream(), 32768);
            assertTrue("Require actual Conscrypt and same-response server ECH proof",
                    "encrypted".equals(field(trace, "sni")) && host.equals(field(trace, "h"))
                            && events.conscrypt && events.handshakes == 1 && events.acquired == 1);
            assertTrue("Require actual peer certificates", !handshake.peerCertificates().isEmpty());
            report.put("phase", "COPY_CERTIFICATES");
            writeReport();
            for (Certificate peer : handshake.peerCertificates()) {
                watch(peer, null);
                byte[] encoded = peer.getEncoded();
                String peerSha = sha256(encoded);
                result.getJSONArray("peerCertificates").put(new JSONObject()
                        .put("actualClass", peer.getClass().getName()).put("derSha256", peerSha)
                        .put("provider", "NOT_EXPOSED_BY_CERTIFICATE_API"));
                for (int index = 0; index < copies; index++) {
                    copy(platform, peer, encoded, peerSha, fresh, platformContexts);
                    copy(bundled, peer, encoded, peerSha, fresh, bundledContexts);
                }
            }
            result.put("sni", "encrypted").put("tls", handshake.tlsVersion().javaName())
                    .put("socketClass", events.socket.getClass().getName()).put("state", "PASS");
        } catch (Throwable error) {
            result.put("state", "FAIL").put("exceptionTypes", exceptionTypes(error));
            throw error;
        } finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
            boolean closed = events.socket != null && events.socket.isClosed();
            result.put("socketClosed", closed).put("conscryptSocket", events.conscrypt)
                    .put("secureConnectEnds", events.handshakes).put("connectionsAcquired", events.acquired)
                    .put("platformCopiesAllocated", platform.allocated - platformBefore)
                    .put("bundledCopiesAllocated", bundled.allocated - bundledBefore);
            if (events.socket != null) {
                watch(events.socket, null);
                events.socket = null;
                if (closed) closedSockets++;
            }
            report.put("phase", "TLS_OWNERS_CLOSED");
            report.put("certificateGroups", new JSONArray().put(platform.json()).put(bundled.json()));
            writeReport();
            if ("PASS".equals(result.optString("state"))) assertTrue("Close socket before collection", closed);
        }
    }

    private void copy(CopyGroup group, Certificate peer, byte[] encoded, String peerSha,
                      Set<Certificate> fresh, Set<Long> contexts) throws Exception {
        Certificate certificate = group.factory.generateCertificate(new ByteArrayInputStream(encoded));
        String actualClass = certificate.getClass().getName();
        assertTrue("Create the requested native-backed certificate class", actualClass.startsWith(group.namespace));
        assertTrue("Create fresh Java certificate owners", certificate != peer && fresh.add(certificate));
        group.allocated++;
        group.classes.add(actualClass);
        watch(certificate, group);
        try {
            Field context = certificate.getClass().getDeclaredField("mContext");
            context.setAccessible(true);
            long handle = context.getLong(certificate);
            assertTrue("Distinct native owners within one provider", handle != 0 && contexts.add(handle));
            group.contextsVerified++;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            group.contextsUnavailable++;
        }
        assertTrue("Read an actual public key", certificate.getPublicKey().getEncoded().length > 0);
        assertEquals("Preserve the actual peer certificate encoding", peerSha, sha256(certificate.getEncoded()));
        X509Certificate x509 = (X509Certificate) certificate;
        x509.getSubjectX500Principal().getEncoded();
        x509.getIssuerX500Principal().getEncoded();
        if (group == bundled) {
            try {
                Field registry = certificate.getClass().getDeclaredField("mNativeAllocationRegistered");
                registry.setAccessible(true);
                if (registry.getBoolean(certificate)) registryTrue++; else registryFalse++;
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                registryUnavailable++;
            }
        }
    }

    private JSONObject collectRound(int round) throws Exception {
        report.put("phase", "EXPLICIT_GC_ROUND_" + round);
        watch(new FinalizerWitness(), null);
        writeReport();
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            System.runFinalization();
            SystemClock.sleep(500);
            drainReleased();
            report.put("explicitGcCalls", report.optInt("explicitGcCalls") + 1);
        }
        return new JSONObject().put("round", round).put("pid", Process.myPid()).put("explicitGcCalls", 3)
                .put("platformCopiesAllocated", platform.allocated).put("platformCopiesCollected", platform.collected)
                .put("bundledCopiesAllocated", bundled.allocated).put("bundledCopiesCollected", bundled.collected)
                .put("finalizerWitnessCount", FINALIZED.get() - finalizersBefore).put("naturalGcClaim", false);
    }

    private void watch(Object value, CopyGroup group) { watched.add(new WatchedReference(value, released, group)); }

    private void drainReleased() {
        Reference<?> reference;
        while ((reference = released.poll()) != null) {
            collected++;
            CopyGroup group = ((WatchedReference) reference).group;
            if (group != null) group.collected++;
        }
    }

    private static final class CopyGroup {
        final String kind;
        final String namespace;
        final CertificateFactory factory;
        final Set<String> classes = new LinkedHashSet<>();
        int allocated, collected, contextsVerified, contextsUnavailable;
        CopyGroup(String kind, String namespace, CertificateFactory factory) {
            this.kind = kind; this.namespace = namespace; this.factory = factory;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("kind", kind).put("provider", factory.getProvider().getName())
                    .put("providerClass", factory.getProvider().getClass().getName())
                    .put("actualClasses", new JSONArray(classes)).put("allocated", allocated).put("collected", collected)
                    .put("distinctNativeContextsVerified", contextsVerified).put("nativeContextReadUnavailable", contextsUnavailable);
        }
    }

    private static final class WatchedReference extends WeakReference<Object> {
        final CopyGroup group;
        WatchedReference(Object value, ReferenceQueue<Object> queue, CopyGroup group) {
            super(value, queue); this.group = group;
        }
    }

    private static final class FinalizerWitness {
        @Override protected void finalize() { FINALIZED.incrementAndGet(); }
    }

    private static final class Events extends EventListener {
        Socket socket;
        boolean conscrypt;
        int handshakes, acquired;
        @Override public void secureConnectEnd(Call call, Handshake handshake) { handshakes++; }
        @Override public void connectionAcquired(Call call, Connection connection) {
            acquired++;
            socket = connection.socket();
            conscrypt = socket instanceof SSLSocket && Conscrypt.isConscrypt((SSLSocket) socket);
        }
    }

    private static JSONArray exceptionTypes(Throwable error) {
        JSONArray result = new JSONArray();
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) result.put(error.getClass().getName());
        return result;
    }

    private static String field(String trace, String key) {
        String value = null;
        for (String line : trace.split("\\r?\\n")) {
            if (!line.startsWith(key + "=")) continue;
            if (value != null) return "AMBIGUOUS";
            value = line.substring(key.length() + 1);
        }
        return value;
    }

    private static String readSmall(InputStream input, int limit) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("INPUT_SIZE_LIMIT");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String systemProperty(String name) throws Exception {
        java.lang.Process process = new ProcessBuilder("/system/bin/getprop", name).start();
        try {
            assertTrue("Bound getprop completion", process.waitFor(3, TimeUnit.SECONDS));
            assertEquals("Read native bridge setting", 0, process.exitValue());
            return readSmall(process.getInputStream(), 1024).trim();
        } finally { process.destroy(); }
    }

    private static String sha256(File file) throws Exception {
        try (InputStream source = new FileInputStream(file)) { return sha256(source); }
    }

    private static String sha256(byte[] bytes) throws Exception { return sha256(new ByteArrayInputStream(bytes)); }

    private static String sha256(InputStream source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int count;
        while ((count = source.read(buffer)) != -1) digest.update(buffer, 0, count);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    private void writeReport() throws Exception {
        File temporary = new File(directory, "preview-native-probe.json.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(new File(directory, "preview-native-probe.json"))) throw new IOException("REPORT_RENAME_FAILED");
    }
}
