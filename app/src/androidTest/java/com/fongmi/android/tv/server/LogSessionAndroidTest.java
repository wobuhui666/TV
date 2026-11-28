package com.fongmi.android.tv.server;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LogSessionAndroidTest {
    @Test public void shouldReadRealAppLogcatAndStopThroughHttp() throws Exception {
        Nano server = new Nano(0);
        server.start();
        String base = "http://127.0.0.1:" + server.getListeningPort();
        try {
            String code = request(base + "/log", "POST", null, 200).get("code").getAsString();
            String marker = "web-log-test-" + SystemClock.elapsedRealtime();
            Log.i("WebLogTest", marker);
            long deadline = SystemClock.elapsedRealtime() + 15000;
            long cursor = 0;
            boolean received = false;
            while (!received && SystemClock.elapsedRealtime() < deadline) {
                JsonObject page = request(base + "/log?after=" + cursor, "GET", code, 200);
                for (JsonElement entry : page.getAsJsonArray("lines")) {
                    if (entry.getAsJsonObject().get("text").getAsString().contains(marker)) received = true;
                }
                cursor = page.get("next").getAsLong();
                if (!received) SystemClock.sleep(100);
            }
            assertTrue("App logcat must reach the actual HTTP endpoint", received);
            request(base + "/log", "DELETE", code, 200);
            assertFalse(LogSession.get().isRunning());
            request(base + "/log", "GET", code, 410);
            request(base + "/log", "POST", null, 200);
            assertTrue(LogSession.get().isRunning());
        } finally { server.stop(); }
        assertFalse("HTTP shutdown must stop capture", LogSession.get().isRunning());
    }

    private static JsonObject request(String url, String method, String code, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod(method);
        if (code != null) connection.setRequestProperty("X-Log-Code", code);
        if (method.equals("POST")) {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream()) { output.write("{}".getBytes(StandardCharsets.UTF_8)); }
        }
        try {
            assertEquals(expected, connection.getResponseCode());
            assertEquals("no-store", connection.getHeaderField("Cache-Control"));
            if (expected != 200) return new JsonObject();
            try (var input = connection.getInputStream(); var reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } finally { connection.disconnect(); }
    }
}
