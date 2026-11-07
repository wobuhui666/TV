package com.fongmi.chaquo;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.github.catvod.Init;
import com.github.catvod.net.Net;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.ByteString;

import static org.junit.Assert.*;

/** Runs CPython, JNI and the production network/QuickJS adapters on loopback only. */
@RunWith(AndroidJUnit4.class)
public class PythonLiveSdkTest {

    private static final Net.Cookies COOKIES = new Net.Cookies() {
        @Override public String getCookie(String url) { return ""; }
        @Override public boolean setCookie(String url, String value) { return true; }
    };

    private final List<Spider> spiders = new ArrayList<>();
    private final Map<String, String> sources = new java.util.concurrent.ConcurrentHashMap<>();
    private Context context;
    private MockWebServer server;
    private volatile CountDownLatch blockedRequest;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Init.set(context);
        server = new MockWebServer();
        blockedRequest = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath().split("\\?", 2)[0];
                if (sources.containsKey(path)) return new MockResponse().setBody(sources.get(path));
                if (path.equals("/data")) return new MockResponse().setBody("binary-fixture")
                        .setHeader("Content-Type", "video/x-flv").setHeader("X-Fixture", "native");
                if (path.equals("/json")) return new MockResponse().setBody("{\"ok\":true}");
                if (path.equals("/blocked")) {
                    blockedRequest.countDown();
                    return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                }
                if (path.equals("/socket")) return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, okhttp3.Response response) {
                        socket.send("first");
                        socket.send(ByteString.of((byte) 128, (byte) 255));
                    }
                });
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
    }

    @After public void tearDown() throws Exception {
        Throwable failure = null;
        for (Spider spider : spiders) {
            try {
                if (spider.local != null) spider.local.clear();
                spider.destroy();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        server.shutdown();
        if (failure != null) throw new AssertionError("Python source cleanup failed", failure);
    }

    private Spider load(String path, String source, String key) {
        sources.put(path, source);
        Spider spider = new Loader().spider(server.url(path).toString(), key, COOKIES, () -> "fixture-agent");
        spiders.add(spider);
        spider.init(context, server.url("/").toString());
        return spider;
    }

    private PyObject object(Spider spider) throws Exception {
        Field field = Spider.class.getDeclaredField("obj");
        field.setAccessible(true);
        return (PyObject) field.get(spider);
    }

    private byte[] readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = stream.read(buffer)) != -1) result.write(buffer, 0, count);
        return result.toByteArray();
    }

    @Test public void modernLegacyAndRealInputStreamPassThrough() throws Exception {
        Spider modern = load("/modern/main.py", """
                from base.spider import Spider as Base
                from java import jclass, jarray, jbyte
                class Spider(Base):
                    def init(self, extend=''):
                        self.native = jclass('java.io.ByteArrayInputStream')(jarray(jbyte)(bytes((0,128,255))))
                    def proxy(self, params):
                        return [200, 'application/octet-stream', self.native, {'X-Fixture': 'modern'}]
                """, "native-modern");
        Object[] response = modern.proxy(Map.of());
        assertSame(object(modern).get("native").toJava(InputStream.class), response[2]);
        assertArrayEquals(new byte[]{0, (byte)128, (byte)255}, readAll((InputStream) response[2]));
        ((InputStream) response[2]).close();
        Spider legacy = load("/legacy/main.py", """
                from base.spider import Spider as Base
                class Spider(Base):
                    def localProxy(self, params):
                        return [201, 'text/plain', 'data:text/plain;base64,aGVsbG8=', {}, 1]
                """, "native-legacy");
        Object[] older = legacy.proxy(Map.of());
        assertEquals(201, older[0]);
        assertEquals("hello", new String(readAll((InputStream) older[2]), StandardCharsets.UTF_8));
    }

    @Test public void nativeHttpMapCacheLegacyRequestsAndJsRunInPython310() throws Exception {
        Spider spider = load("/interfaces/main.py", """
                from base.spider import Spider as Base
                import sys, hashlib
                class Spider(Base):
                    def init(self, extend=''): self.url=extend
                    def homeContent(self, filter):
                        assert sys.version_info[:2] == (3,10)
                        assert self.net.json(self.url+'json') == {'ok':True}
                        assert self.fetch(self.url+'json').json() == {'ok':True}
                        assert self.net.getUserAgent() == 'fixture-agent'
                        marker=object()
                        values=self.net.map([3,1,2],lambda value,index:(value,index,marker),2)
                        assert [(v[0],v[1]) for v in values] == [(3,0),(1,1),(2,2)]
                        assert all(v[2] is marker for v in values)
                        def broken(*args): raise ValueError('mapper-original')
                        try: self.net.map([1],broken)
                        except ValueError as error: assert str(error)=='mapper-original'
                        else: raise AssertionError('mapper error lost')
                        try: self.net.cached('broken',{'ttl':1},lambda:broken())
                        except ValueError as error: assert str(error)=='mapper-original'
                        else: raise AssertionError('cache error lost')
                        assert self.local.get('missing',{'n':1}) == {'n':1}
                        assert self.js.eval('rid+1',{'globals':{'rid':41}}) == 42
                        assert self.js.eval("md5X('fixture')") == hashlib.md5(b'fixture').hexdigest()
                        with self.net.openStream(self.url+'data',{'callTimeout':0}) as reader:
                            assert reader.code==200 and reader.contentType=='video/x-flv'
                            assert reader.read(1)==b'b'
                            rest=[]
                            while True:
                                chunk=reader.read(65536)
                                if chunk is None: break
                                rest.append(chunk)
                            assert b''.join(rest)==b'inary-fixture'
                        assert self.page([3,4],2,2,total=8)['pagecount']==4
                        return {'native':True,'python':'3.10'}
                """, "native-interfaces");
        JSONObject result = new JSONObject(spider.homeContent(true));
        assertTrue(result.getBoolean("native"));
        assertEquals("3.10", result.getString("python"));
    }

    @Test public void generatorAndUrlStreamsCloseAtTheRealJniBoundary() throws Exception {
        Spider spider = load("/streams/main.py", """
                from base.spider import Spider as Base
                class Spider(Base):
                    def init(self, extend=''): self.url=extend; self.closed=0
                    def chunks(self):
                        try:
                            yield b''
                            yield bytearray(b'first')
                            yield memoryview(b'second')
                        finally: self.closed+=1
                    def proxy(self, params):
                        body=self.net.stream(self.url+'data') if params.get('url') else self.net.stream(self.chunks())
                        return [body['code'],body['contentType'],body['stream'],body['headers']]
                    def homeContent(self, filter): return {'closed':self.closed}
                """, "native-streams");
        InputStream generator = (InputStream) spider.proxy(Map.of())[2];
        assertEquals(0, new JSONObject(spider.homeContent(false)).getInt("closed"));
        assertEquals('f', generator.read());
        generator.close(); generator.close();
        assertEquals(1, new JSONObject(spider.homeContent(false)).getInt("closed"));
        Object[] forwarded = spider.proxy(Map.of("url", "true"));
        assertEquals("video/x-flv", forwarded[1]);
        assertEquals("binary-fixture", new String(readAll((InputStream) forwarded[2]), StandardCharsets.UTF_8));
        ((InputStream) forwarded[2]).close();
        InputStream owned = (InputStream) spider.proxy(Map.of())[2];
        assertEquals('f', owned.read());
        spider.close();
        assertEquals(-1, owned.read());
        assertEquals(2, new JSONObject(spider.homeContent(false)).getInt("closed"));
    }

    @Test public void continuousSocketAndDanmakuStopDiscardLateEvents() throws Exception {
        Spider spider = load("/socket/main.py", """
                from base.spider import Spider as Base
                class Spider(Base):
                    def init(self, extend=''): self.url=extend.replace('http:','ws:')+'socket'; self.stopped=0
                    def danmaku(self, id, emit):
                        def receive(socket,event):
                            if event['type']=='message':
                                value=event['content'].hex() if event['binary'] else event['content']
                                emit({'text':value})
                        self.socket=self.net.connect(self.url,{'timeout':5000,'idle':10000,'reconnect':False},receive)
                        self.emit=emit
                        def stop(): self.stopped+=1; self.socket.cancel()
                        return stop
                    def homeContent(self, filter):
                        self.emit({'text':'late'})
                        return {'stopped':self.stopped}
                """, "native-socket");
        CountDownLatch messages = new CountDownLatch(2);
        AtomicInteger received = new AtomicInteger();
        List<String> texts = java.util.Collections.synchronizedList(new ArrayList<>());
        AutoCloseable stop = spider.danmaku("room", value -> {
            texts.add(value.optString("text"));
            received.incrementAndGet();
            messages.countDown();
        });
        assertTrue("two WebSocket messages must arrive", messages.await(10, TimeUnit.SECONDS));
        stop.close(); stop.close();
        assertEquals(1, new JSONObject(spider.homeContent(false)).getInt("stopped"));
        assertEquals(2, received.get());
        assertTrue(texts.contains("first"));
        assertTrue(texts.contains("80ff"));
    }

    @Test public void closingGeneratorCancelsARealPendingHttpOpen() throws Exception {
        Spider spider = load("/blocking/main.py", """
                from base.spider import Spider as Base
                class Spider(Base):
                    def init(self,extend=''): self.url=extend; self.closed=0
                    def chunks(self):
                        try:
                            with self.net.openStream(self.url+'blocked',{'timeout':15000,'callTimeout':0}) as reader:
                                yield reader.read(1)
                        finally: self.closed+=1
                    def proxy(self,params): return [200,'video/x-flv',self.net.stream(self.chunks())['stream']]
                    def homeContent(self,filter): return {'closed':self.closed}
                """, "native-blocking");
        InputStream stream = (InputStream) spider.proxy(Map.of())[2];
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = worker.submit(() -> {
                try { stream.read(); } catch (IOException expected) { }
            });
            assertTrue("generator entered HTTP open", blockedRequest.await(5, TimeUnit.SECONDS));
            stream.close();
            read.get(5, TimeUnit.SECONDS);
            assertEquals(1, new JSONObject(spider.homeContent(false)).getInt("closed"));
        } finally {
            worker.shutdownNow();
            stream.close();
        }
    }

    @Test public void closingGeneratorCancelsOrdinaryAndCachedRequestsBeforeStreaming() throws Exception {
        Spider spider = load("/resolving/main.py", """
                from base.spider import Spider as Base
                class Spider(Base):
                    def init(self,extend=''): self.url=extend; self.closed=0
                    def chunks(self,cached):
                        try:
                            def resolve(): return self.net.req(self.url+'blocked',{'timeout':15000,'callTimeout':0})
                            result=self.net.cached('resolve',{'ttl':1000},resolve) if cached else resolve()
                            yield result.get('content') or b'fixture'
                        finally: self.closed+=1
                    def proxy(self,params): return [200,'video/x-flv',self.net.stream(self.chunks(params.get('cached')=='yes'))['stream']]
                    def homeContent(self,filter): return {'closed':self.closed}
                """, "native-resolving");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            int expectedClosed = 0;
            for (boolean cached : new boolean[]{false, true}) {
                blockedRequest = new CountDownLatch(1);
                InputStream stream = (InputStream) spider.proxy(cached ? Map.of("cached", "yes") : Map.of())[2];
                Future<?> read = worker.submit(() -> {
                    try { stream.read(); } catch (IOException expected) { }
                });
                try {
                    assertTrue("ordinary resolution request entered", blockedRequest.await(5, TimeUnit.SECONDS));
                    stream.close();
                    read.get(5, TimeUnit.SECONDS);
                    assertEquals(++expectedClosed, new JSONObject(spider.homeContent(false)).getInt("closed"));
                } finally {
                    stream.close();
                }
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test public void closingGeneratorInterruptsNativeRetrySleep() throws Exception {
        Spider spider = load("/sleep/main.py", """
                from base.spider import Spider as Base
                class Spider(Base):
                    def init(self,extend=''): self.sleeping=False; self.closed=0
                    def chunks(self):
                        try:
                            self.sleeping=True
                            self.net.sleep(60000)
                            yield b'fixture'
                        finally: self.closed+=1
                    def proxy(self,params): return [200,'video/x-flv',self.net.stream(self.chunks())['stream']]
                    def homeContent(self,filter): return {'sleeping':self.sleeping,'closed':self.closed}
                """, "native-sleep");
        InputStream stream = (InputStream) spider.proxy(Map.of())[2];
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = worker.submit(() -> {
                try { stream.read(); } catch (IOException expected) { }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!new JSONObject(spider.homeContent(false)).getBoolean("sleeping") && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(new JSONObject(spider.homeContent(false)).getBoolean("sleeping"));
            stream.close();
            read.get(5, TimeUnit.SECONDS);
            assertEquals(1, new JSONObject(spider.homeContent(false)).getInt("closed"));
            assertFalse(worker.submit(() -> Thread.currentThread().isInterrupted()).get());
        } finally {
            worker.shutdownNow();
            stream.close();
        }
    }

    @Test public void sameBasenameDependencyAndGenerationStayIsolated() throws Exception {
        String source = """
                from base.spider import Spider as Base
                class Spider(Base):
                    def getDependence(self): return ['helper']
                    def init(self, extend=''): self.helper=self.loadModule('helper')
                    def homeContent(self, filter): return {'value':self.helper.VALUE,'site':self.siteKey,'module':self.__class__.__module__}
                """;
        sources.put("/one/helper.py", "VALUE=1");
        sources.put("/two/helper.py", "VALUE=2");
        Spider first = load("/one/main.py", source, "native-one");
        Spider second = load("/two/main.py", source, "native-two");
        JSONObject one = new JSONObject(first.homeContent(false));
        JSONObject two = new JSONObject(second.homeContent(false));
        assertEquals(1, one.getInt("value"));
        assertEquals(2, two.getInt("value"));
        assertNotEquals(one.getString("module"), two.getString("module"));
        first.destroy();
        assertEquals(2, new JSONObject(second.homeContent(false)).getInt("value"));
        Spider refreshed = load("/two/main.py", source, "native-two");
        assertNotEquals(two.getString("module"), new JSONObject(refreshed.homeContent(false)).getString("module"));
    }

    @Test public void pinnedWasmDependenciesArePackagedInTheNativeVm() {
        new Loader();
        Python python = Python.getInstance();
        try (PyObject globals = python.getBuiltins().callAttr("dict")) {
            python.getBuiltins().callAttr("exec", """
                import sys, pywasm, numpy
                from importlib.metadata import version
                assert sys.version_info[:2] == (3,10)
                assert version('pywasm') == '1.0.8'
                assert numpy.__version__ == '1.26.2'
                assert callable(pywasm.load)
                """, globals);
        }
    }
}
