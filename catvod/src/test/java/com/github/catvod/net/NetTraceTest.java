package com.github.catvod.net;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import static org.junit.Assert.*;

public class NetTraceTest {
    @Test
    public void capturesRequestStatusAcrossOwnedSessions() throws Exception {
        try (NetTrace trace = new NetTrace(UUID.randomUUID().toString());
             mockwebserver3.MockWebServer server = new mockwebserver3.MockWebServer();
             Net net = new Net(new OkHttpClient(), null, trace)) {
            server.start();
            server.enqueue(new mockwebserver3.MockResponse.Builder().code(201).body("one").build());
            server.enqueue(new mockwebserver3.MockResponse.Builder().code(202).body("two").build());
            trace.start();
            assertEquals("one", net.get(server.url("/one").toString()));
            try (Net session = net.session()) {
                assertEquals("two", session.get(server.url("/two").toString()));
            }
            trace.stop();
            List<?> records = (List<?>) trace.snapshot().get("records");
            assertEquals(2, records.size());
            assertEquals(201, ((Map<?, ?>) records.get(0)).get("code"));
            assertEquals(202, ((Map<?, ?>) records.get(1)).get("code"));
            assertEquals(true, ((Map<?, ?>) records.get(1)).get("complete"));
        }
    }

    @Test
    public void limitsRecordsAndStripsUrlCredentials() {
        try (NetTrace trace = new NetTrace(UUID.randomUUID().toString())) {
            trace.start();
            OkHttpClient client = new OkHttpClient();
            for (int i = 0; i < 101; i++) {
                Call call = client.newCall(new Request.Builder().url("https://name:secret@example.invalid/fixture").build());
                EventListener listener = trace.listener(call);
                listener.callEnd(call);
            }
            Map<String, Object> snapshot = trace.snapshot();
            assertEquals(101, snapshot.get("total"));
            assertEquals(true, snapshot.get("truncated"));
            List<?> records = (List<?>) snapshot.get("records");
            assertEquals(100, records.size());
            assertFalse(records.toString().contains("secret"));
            assertFalse(records.toString().contains("name:"));
        }
    }
}
