package com.fongmi.quickjs.method;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.quickjs.utils.Async;
import com.github.catvod.Init;
import com.github.catvod.net.Net;
import com.github.catvod.utils.Local;
import com.github.catvod.utils.CallTimeout;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NetBridgeNativeTest {
    private ExecutorService js;
    private QuickJSContext context;
    private Global global;
    private NetBridge bridge;
    private Net net;

    @Before public void create() throws Exception {
        Init.set(InstrumentationRegistry.getInstrumentation().getTargetContext());
        QuickJSLoader.init();
        js = Executors.newSingleThreadExecutor();
        onJs(() -> {
            context = QuickJSContext.create();
            context.setConsole(new Console());
            context.evaluate("void Object.defineProperty(globalThis, 'format', {value: globalThis.format,writable:false,configurable:false});");
            global = Global.create(context, js);
            net = new Net(null);
            bridge = new NetBridge(context, js, net, new Local("js-native-network"), "js-native-network");
            return null;
        });
    }

    @After public void close() throws Exception {
        if (js != null) {
            try { onJs(() -> { if (bridge != null) bridge.close(); if (global != null) global.destroy(); if (context != null) context.destroy(); return null; }); }
            finally { js.shutdownNow(); }
        }
    }

    @Test(timeout = 10000) public void readsHttpIncrementallyAndClosingCancelsBlockedNativeRead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    readHeaders(socket);
                    socket.getOutputStream().write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-flv\r\nContent-Length: 8\r\nX-Probe: incremental\r\n\r\nAB".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    release.await(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            });
            assertEquals("[206,\"video/x-flv\",\"incremental\",[65,66]]", evaluate("(async()=>{globalThis.reader=await net.openStream('http://127.0.0.1:" + server.getLocalPort() + "/stream'); return [reader.code,reader.contentType,reader.headers['x-probe'],Array.from(new Uint8Array(await reader.read(2)))];})()"));
            CompletableFuture<String> blocked = begin("reader.read(2)");
            Thread.sleep(100);
            assertFalse(blocked.isDone());
            evaluate("reader.close()");
            assertThrows(Exception.class, () -> blocked.get(2, TimeUnit.SECONDS));
            release.countDown();
            served.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test(timeout = 10000) public void generatorBytesTransferWithoutBufferingAndStopExactlyOnce() throws Exception {
        String result = evaluate("(async()=>{globalThis.produced=0; globalThis.stopped=0; const reply=await net.stream((async function*(){try {produced++; yield new Uint8Array([9,0,127,128,255,8]).subarray(1,5); produced++; yield new Uint8Array([4]);}finally{stopped++;}})());return {token:reply.stream.$tvStream,produced};})()");
        JSONObject data = new JSONObject(result);
        assertEquals(0, data.getInt("produced"));
        InputStream stream = bridge.takeStream(data.getInt("token"));
        byte[] first = new byte[2];
        assertEquals(2, stream.read(first));
        assertArrayEquals(new byte[]{0,127}, first);
        assertEquals("1", evaluate("produced"));
        assertEquals(2, stream.read(first));
        assertArrayEquals(new byte[]{(byte)128,(byte)255}, first);
        stream.close();
        stream.close();
        assertEquals("1", evaluate("stopped"));
    }

    @Test(timeout = 10000) public void generatorCloseCancelsItsSleepAndCachedLoader() throws Exception {
        JSONObject data = new JSONObject(evaluate("(async()=>{globalThis.stopped=0;const reply=await net.stream((async function*(){try {yield new Uint8Array([1]);await net.cached('blocked',{ttl:1},async()=>{await net.sleep(60000);return 1;});yield new Uint8Array([2]);}finally{stopped++;}})());return {token:reply.stream.$tvStream};})()"));
        InputStream stream = bridge.takeStream(data.getInt("token"));
        assertEquals(1, stream.read());
        CompletableFuture<Integer> blocked = CompletableFuture.supplyAsync(() -> { try { return stream.read(); } catch (Exception e) { throw new RuntimeException(e); } });
        Thread.sleep(100);
        assertFalse(blocked.isDone());
        stream.close();
        assertThrows(Exception.class, () -> blocked.get(2, TimeUnit.SECONDS));
        awaitJs("stopped===1");
    }

    @Test(timeout = 10000) public void generatorCloseCancelsItsOrdinaryHttpRequest() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    readHeaders(socket);
                    accepted.countDown();
                    release.await(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            });
            JSONObject reply = new JSONObject(evaluate("(async()=>{globalThis.stopped=0;const reply=await net.stream((async function*(){try{yield new Uint8Array([1]);await net.http('http://127.0.0.1:" + server.getLocalPort() + "/pending',{timeout:60000});yield new Uint8Array([2]);}finally{stopped++;}})());return {token:reply.stream.$tvStream};})()"));
            InputStream stream = bridge.takeStream(reply.getInt("token"));
            assertEquals(1, stream.read());
            CompletableFuture<Integer> blocked = CompletableFuture.supplyAsync(() -> { try { return stream.read(); } catch (Exception e) { throw new RuntimeException(e); } });
            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            assertFalse(blocked.isDone());
            stream.close();
            assertThrows(Exception.class, () -> blocked.get(2, TimeUnit.SECONDS));
            awaitJs("stopped===1");
            release.countDown();
            served.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test(timeout = 10000) public void websocketDeliversMultipleTextAndBinaryEventsThenSessionCancelStopsIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    String key = readHeaders(socket);
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(new byte[]{(byte)0x81,1,'a',(byte)0x82,2,0,(byte)255,(byte)0x81,1,'b'});
                    socket.getOutputStream().flush();
                    release.await(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            });
            try (CallTimeout setup = new CallTimeout(5000)) {
                onJs(() -> {
                    Runnable restore = setup.enter();
                    try { context.evaluate("globalThis.events=[];globalThis.child=net.session();globalThis.socket=child.connect('ws://127.0.0.1:" + server.getLocalPort() + "/socket',{},(s,event)=>{if(event.type==='message')events.push(event.binary?Array.from(event.content):event.content);});"); }
                    finally { restore.run(); }
                    return null;
                });
            }
            awaitJs("events.length===3");
            assertEquals("[\"a\",[0,255],\"b\"]", evaluate("events"));
            evaluate("(()=>{child.close();return true;})()");
            assertThrows(Exception.class, () -> evaluate("socket.send('late')"));
            release.countDown();
            served.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test(timeout = 10000) public void resourceScopeClosesAnIdleReaderAndSocketAfterSetupReturns() throws Exception {
        CountDownLatch release = new CountDownLatch(1), wsAccepted = new CountDownLatch(1);
        try (ServerSocket http = new ServerSocket(0); ServerSocket ws = new ServerSocket(0); CallTimeout scope = CallTimeout.resourceScope()) {
            CompletableFuture<Void> servingHttp = CompletableFuture.runAsync(() -> {
                try (Socket socket = http.accept()) {
                    readHeaders(socket);
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    release.await(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            });
            CompletableFuture<Void> servingWs = CompletableFuture.runAsync(() -> {
                try (Socket socket = ws.accept()) {
                    String key = readHeaders(socket);
                    wsAccepted.countDown();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    release.await(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            });
            CompletableFuture<String> initialized;
            Runnable restore = scope.enter();
            try { initialized = begin("(async()=>{globalThis.scopedReader=await net.openStream('http://127.0.0.1:" + http.getLocalPort() + "/idle');globalThis.scopedSocket=net.connect('ws://127.0.0.1:" + ws.getLocalPort() + "/idle',{},()=>{});return true;})()"); }
            finally { restore.run(); }
            assertEquals("true", initialized.get(3, TimeUnit.SECONDS));
            assertTrue(wsAccepted.await(2, TimeUnit.SECONDS));
            scope.close();
            assertThrows(Exception.class, () -> evaluate("scopedReader.read(1)"));
            assertEquals("false", evaluate("scopedSocket.send('late')"));
            release.countDown();
            servingHttp.get(2, TimeUnit.SECONDS); servingWs.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test(timeout = 10000) public void throwingOwnerCloseStillReleasesPendingRequestsAndOtherStreams() throws Exception {
        CompletableFuture<String> pending = begin("net.sleep(60000)");
        JSONObject reply = new JSONObject(evaluate("(async()=>{const one=await net.stream((async function*(){yield new Uint8Array([1]);})());const two=await net.stream((async function*(){yield new Uint8Array([2]);})());return {first:one.stream.$tvStream,second:two.stream.$tvStream};})()"));
        int failingToken = reply.getInt("first");
        InputStream second = bridge.takeStream(reply.getInt("second"));
        java.lang.reflect.Field streamsField = NetBridge.class.getDeclaredField("streams");
        streamsField.setAccessible(true);
        java.util.Map<?,?> entries = (java.util.Map<?,?>) streamsField.get(bridge);
        Object entry = entries.get(failingToken);
        java.lang.reflect.Constructor<?> constructor = entry.getClass().getDeclaredConstructor(int.class, int.class, InputStream.class, CallTimeout.class);
        constructor.setAccessible(true);
        InputStream failing = new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() throws java.io.IOException { throw new java.io.IOException("test close failure"); }
        };
        bridge.closeStream(failingToken);
        @SuppressWarnings("unchecked") java.util.Map<Integer,Object> mutableEntries = (java.util.Map<Integer,Object>) entries;
        mutableEntries.put(failingToken, constructor.newInstance(0, failingToken, failing, null));
        assertThrows(Exception.class, () -> onJs(() -> { bridge.close(); return null; }));
        assertTrue(entries.isEmpty());
        assertEquals(-1, second.read());
        assertThrows(Exception.class, () -> pending.get(2, TimeUnit.SECONDS));
        java.lang.reflect.Field ownersField = NetBridge.class.getDeclaredField("owners");
        ownersField.setAccessible(true);
        assertTrue(((java.util.Map<?,?>) ownersField.get(bridge)).isEmpty());
    }

    private <T> T onJs(Callable<T> work) throws Exception { return js.submit(work).get(5, TimeUnit.SECONDS); }

    private CompletableFuture<String> begin(String expression) throws Exception {
        CallTimeout scope = CallTimeout.current();
        return onJs(() -> {
            Runnable restore = scope == null ? () -> {} : scope.enter();
            try {
                JSObject runner = (JSObject) context.evaluate("({run(){return (" + expression + ");}})");
                CompletableFuture<Object> result = Async.runJson(runner, "run");
                result.whenComplete((value,error) -> js.execute(runner::release));
                return result.thenApply(value -> value instanceof String text ? text : String.valueOf(value));
            } finally { restore.run(); }
        });
    }

    private String evaluate(String expression) throws Exception { return begin(expression).get(4, TimeUnit.SECONDS); }

    private void awaitJs(String condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!evaluate(condition).equals("true") && System.nanoTime() < end) Thread.sleep(10);
        assertEquals("true", evaluate(condition));
    }

    private static String readHeaders(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String key = "";
        for (String line; (line = reader.readLine()) != null && !line.isEmpty();) if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(line.indexOf(':')+1).trim();
        return key;
    }
}
