package com.fongmi.android.tv.test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.conscrypt.Conscrypt;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/** Offline coverage for the NativeSsl close/read synchronization added upstream in #1551. */
@RunWith(AndroidJUnit4.class)
public final class ConscryptLifecycleAndroidTest {
    private static final int ENGINE_COUNT = 128;
    private static final int READS_PER_ENGINE = 128;
    private static final String[] GETTERS = {
            "getSessionId", "getPeerCertificates", "getCipherSuite", "getVersion",
            "getApplicationProtocol", "getEchNameOverride", "getEchRetryConfigs"
    };

    @Test(timeout = 60_000L)
    public void shouldKeepNativeSessionReadsSafeDuringAndAfterClose() throws Exception {
        Conscrypt.checkAvailability();
        Conscrypt.Version version = Conscrypt.version();
        String actual = version.major() + "." + version.minor() + "." + version.patch();
        JSONObject report = new JSONObject().put("schema", "tv.conscrypt-lifecycle.v1")
                .put("state", "RUNNING").put("pid", Process.myPid()).put("sdk", Build.VERSION.SDK_INT)
                .put("artifact", "org.conscrypt:conscrypt-android:" + actual)
                .put("networkRequests", 0).put("preferencesReadOrWritten", false)
                .put("completedEngines", 0).put("readsPerEngine", READS_PER_ENGINE)
                .put("getterCount", GETTERS.length).put("upstreamFix", "google/conscrypt#1551");
        writeReport(report);
        SSLContext context = SSLContext.getInstance("TLS", Conscrypt.newProvider());
        context.init(null, null, null);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < ENGINE_COUNT; iteration++) {
                SSLEngine engine = context.createSSLEngine();
                engine.setUseClientMode(true);
                NativeSession session = new NativeSession(engine);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> reads = workers.submit(() -> {
                    start.await();
                    for (int read = 0; read < READS_PER_ENGINE; read++) session.readAll();
                    return null;
                });
                Future<?> close = workers.submit(() -> {
                    start.await();
                    engine.closeOutbound();
                    engine.closeInbound();
                    return null;
                });
                start.countDown();
                try {
                    reads.get(5, TimeUnit.SECONDS);
                    close.get(5, TimeUnit.SECONDS);
                    assertTrue(engine.isInboundDone());
                    assertTrue(engine.isOutboundDone());
                    session.assertClosed();
                    assertNull(Conscrypt.getTlsUnique(engine));
                    assertNull(Conscrypt.exportKeyingMaterial(engine, "lifecycle", null, 16));
                } finally {
                    engine.closeOutbound();
                    engine.closeInbound();
                }
                report.put("completedEngines", iteration + 1);
                if ((iteration + 1) % 16 == 0) writeReport(report);
            }
            report.put("state", "PASS");
        } finally {
            workers.shutdownNow();
            assertTrue("Lifecycle workers must finish", workers.awaitTermination(5, TimeUnit.SECONDS));
            writeReport(report);
        }
    }

    // Keep the allocated NativeSsl reachable after engine.close, then exercise its real getters.
    // Public SSLSession values are cached and would otherwise miss the native close/read boundary.
    private static final class NativeSession {
        private final Object ssl;
        private final Method[] getters = new Method[GETTERS.length];

        NativeSession(SSLEngine engine) throws Exception {
            Field field = engine.getClass().getDeclaredField("ssl");
            field.setAccessible(true);
            ssl = field.get(engine);
            for (int i = 0; i < GETTERS.length; i++) {
                getters[i] = ssl.getClass().getDeclaredMethod(GETTERS[i]);
                getters[i].setAccessible(true);
            }
        }

        void readAll() throws Exception {
            for (Method getter : getters) invoke(getter);
        }

        void assertClosed() throws Exception {
            for (Method getter : getters) assertNull(getter.getName(), invoke(getter));
        }

        private Object invoke(Method getter) throws Exception {
            try {
                return getter.invoke(ssl);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Error fatal) throw fatal;
                if (cause instanceof Exception failure) throw failure;
                throw new AssertionError(cause);
            }
        }
    }

    private static void writeReport(JSONObject report) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IllegalStateException("REPORT_DIRECTORY_UNAVAILABLE");
        File directory = new File(external, "ech-validation");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("REPORT_DIRECTORY_UNAVAILABLE");
        }
        try (FileOutputStream output = new FileOutputStream(new File(directory, "conscrypt-lifecycle.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
