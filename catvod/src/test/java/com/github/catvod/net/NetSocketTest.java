package com.github.catvod.net;

import com.github.catvod.utils.CallTimeout;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static org.junit.Assert.*;

public class NetSocketTest {

    @Test
    public void deliversMultipleTextAndBinaryMessagesAndSendsBinary() throws Exception {
        CountDownLatch messages = new CountDownLatch(2);
        CountDownLatch binarySent = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        List<NetSocket.Event> events = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<byte[]> received = new AtomicReference<>();
        try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
            server.start();
            server.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    socket.send("first"); socket.send(ByteString.of(new byte[]{0, (byte) 255}));
                }
                @Override public void onMessage(WebSocket socket, ByteString bytes) {
                    received.set(bytes.toByteArray()); binarySent.countDown();
                }
            }).build());
            NetSocket socket = net.connect(server.url("/ws").toString(), "{\"timeout\":2000}", (current, event) -> {
                assertThrows(IllegalStateException.class, CallTimeout::requireDialogsAllowed);
                events.add(event);
                if (event.type.equals("message")) messages.countDown();
                if (event.type.equals("close")) closed.countDown();
            });
            assertTrue(messages.await(3, TimeUnit.SECONDS));
            assertEquals("open", events.get(0).type);
            assertEquals("first", events.get(1).content);
            assertTrue(events.get(2).binary);
            assertArrayEquals(new byte[]{0, (byte) 255}, (byte[]) events.get(2).content);
            assertTrue(socket.send(new byte[]{(byte) 128, 1}));
            assertTrue(binarySent.await(2, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{(byte) 128, 1}, received.get());
            net.close();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertFalse(socket.send("late"));
        }
    }

    @Test
    public void reconnectsAfterServerDisconnectAndCancelStopsRetries() throws Exception {
        CountDownLatch opened = new CountDownLatch(2);
        CountDownLatch retry = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
            server.start();
            server.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) { socket.close(1012, "restart"); }
            }).build());
            server.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {}).build());
            NetSocket socket = net.connect(server.url("/ws").toString(), "{\"timeout\":2000,\"reconnect\":true}", (current, event) -> {
                if (event.type.equals("open")) opened.countDown();
                if (event.type.equals("retry")) retry.countDown();
                if (event.type.equals("close")) closed.countDown();
            });
            assertTrue(retry.await(3, TimeUnit.SECONDS));
            assertTrue(opened.await(4, TimeUnit.SECONDS));
            socket.cancel();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertEquals(2, server.getRequestCount());
            assertFalse(socket.send("late"));
        }
    }

    @Test
    public void cancelDuringRetryDelayPreventsAnotherHandshake() throws Exception {
        CountDownLatch retry = new CountDownLatch(1);
        CountDownLatch secondRequest = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
            server.start();
            server.setDispatcher(new mockwebserver3.Dispatcher() {
                @Override public MockResponse dispatch(mockwebserver3.RecordedRequest request) {
                    if (requests.incrementAndGet() > 1) secondRequest.countDown();
                    return new MockResponse.Builder().code(503).body("retry later").build();
                }
            });
            NetSocket socket = net.connect(server.url("/ws").toString(), "{\"timeout\":2000,\"reconnect\":true}", (current, event) -> {
                if (event.type.equals("retry")) retry.countDown();
            });
            assertTrue(retry.await(2, TimeUnit.SECONDS));
            socket.cancel();
            assertFalse(secondRequest.await(1200, TimeUnit.MILLISECONDS));
            assertEquals(1, requests.get());
        }
    }

    @Test
    public void idleDeadlineEndsSubscriptionWithoutReconnect() throws Exception {
        CountDownLatch error = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        try (MockWebServer server = new MockWebServer(); Net net = new Net(new OkHttpClient(), null)) {
            server.start();
            server.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {}).build());
            net.connect(server.url("/ws").toString(), "{\"timeout\":2000,\"idle\":100}", (current, event) -> {
                if (event.type.equals("error")) error.countDown();
                if (event.type.equals("close")) closed.countDown();
            });
            assertTrue(error.await(3, TimeUnit.SECONDS));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, server.getRequestCount());
        }
    }
}
